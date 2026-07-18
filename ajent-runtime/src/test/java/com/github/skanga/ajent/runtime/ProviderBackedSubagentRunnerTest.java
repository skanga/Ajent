package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.github.skanga.ajent.tools.host.HostServices;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProviderBackedSubagentRunnerTest {
  @Test
  void productionConstructorStartsUnavailableUntilCompositionInstallsItsDependencies() {
    var runner = new ProviderBackedSubagentRunner(java.net.http.HttpClient.newHttpClient());
    assertThat(runner.available()).isFalse();
  }

  @Test
  void runsAnIsolatedMultiTurnExplorerAndSnapshotsTheCurrentProviderSelection(@TempDir Path root)
      throws Exception {
    var prompt = new AtomicReference<String>();
    var toolNames = new AtomicReference<List<String>>();
    var models = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var selected = new AtomicReference<>(configuration(root, "model-a", new ProviderAuth.ApiKey("k")));
    var secondConfiguration = configuration(root, "model-b", new ProviderAuth.ApiKey("k"));
    var completions = new AtomicInteger();
    var runner = new ProviderBackedSubagentRunner((configuration, systemPrompt, tools) -> {
      models.add(configuration.model());
      prompt.set(systemPrompt);
      toolNames.set(tools.stream().map(com.github.skanga.ajent.provider.ToolSpecification::name)
          .toList());
      int completion = completions.incrementAndGet();
      return (turn, messages, cancellation, events) -> {
        if (completion == 1) {
          events.accept(new StreamEvent.ToolUseStart("read-1", "read"));
          events.accept(new StreamEvent.ToolUseDelta("{\"path\":\"README.md\"}"));
          events.accept(new StreamEvent.ToolUseEnd());
          selected.set(secondConfiguration);
          events.accept(new StreamEvent.Finished(StopReason.TOOL_USE));
        } else {
          assertThat(messages).extracting(message -> message.toolCalls().size())
              .contains(1);
          events.accept(new StreamEvent.TextDelta("OUTCOME: mapped the code."));
          events.accept(new StreamEvent.Finished(StopReason.END_TURN));
        }
      };
    });
    var calls = new java.util.concurrent.CopyOnWriteArrayList<ToolUse>();
    runner.bind(call -> {
      calls.add(call);
      return new ToolCompletion.Success("file body");
    });
    runner.install(selected::get);

    var activity = new java.util.concurrent.CopyOnWriteArrayList<String>();
    HostServices.SubagentResponse response = runner.run(
        new HostServices.SubagentRequest("map it", "explorer"), new CancellationSignal(),
        activity::add);

    assertThat(response.error()).isFalse();
    assertThat(response.report()).isEqualTo(
        "Subagent report (explorer, 2 turns):\n\nOUTCOME: mapped the code.");
    assertThat(calls).singleElement().satisfies(call -> {
      assertThat(call.name().value()).isEqualTo("read");
      assertThat(call.arguments()).containsEntry("path", "README.md");
    });
    assertThat(models).containsExactly("model-a", "model-a");
    assertThat(prompt.get()).contains("Your role: EXPLORER", "You are READ-ONLY");
    assertThat(toolNames.get()).contains("read", "grep", "repo_map")
        .doesNotContain("task", "write", "bash", "web_fetch", "web_search");
    assertThat(activity).anyMatch(line -> line.contains("⚙ read  README.md"))
        .anyMatch(line -> line.contains("✓ read  README.md"))
        .anyMatch(line -> line.contains("▸ OUTCOME: mapped the code."));
  }

  @Test
  void availabilityRequiresInstallationAndCredentialsExceptForKeylessLocalProviders(
      @TempDir Path root) throws Exception {
    var runner = new ProviderBackedSubagentRunner((configuration, prompt, tools) ->
        (turn, messages, cancellation, events) -> {});
    runner.bind(call -> new ToolCompletion.Success("ok"));
    assertThat(runner.available()).isFalse();
    var remote = configuration(root, "remote", new ProviderAuth.Empty());
    runner.install(() -> remote);
    assertThat(runner.available()).isFalse();
    var local = new LiveProviderFactory.Configuration("ollama", "local",
        new ProviderAuth.Empty(), "", components(root).systemPrompt(), 8_192, Map.of());
    runner.install(() -> local);
    assertThat(runner.available()).isTrue();
  }

  @Test
  void generalAgentCannotRecursivelyInvokeTaskEvenIfProviderEmitsIt(@TempDir Path root)
      throws Exception {
    var exposed = new AtomicReference<List<String>>();
    var runner = new ProviderBackedSubagentRunner((configuration, prompt, tools) -> {
      exposed.set(tools.stream().map(com.github.skanga.ajent.provider.ToolSpecification::name)
          .toList());
      return (turn, messages, cancellation, events) -> {
        events.accept(new StreamEvent.ToolUseStart("nested", "task"));
        events.accept(new StreamEvent.ToolUseDelta("{\"prompt\":\"fork\"}"));
        events.accept(new StreamEvent.ToolUseEnd());
        events.accept(new StreamEvent.Finished(StopReason.TOOL_USE));
      };
    });
    var executed = new AtomicInteger();
    runner.bind(call -> {
      executed.incrementAndGet();
      return new ToolCompletion.Success("should not run");
    });
    var configured = configuration(root, "model", new ProviderAuth.ApiKey("k"));
    runner.install(() -> configured);

    HostServices.SubagentResponse response = runner.run(
        new HostServices.SubagentRequest("fork", "unknown-type"));

    assertThat(exposed.get()).doesNotContain("task");
    assertThat(executed.get()).isZero();
    assertThat(response.report()).startsWith("Subagent report (general,");
  }

  @Test
  void retriesThreeFailedCompletionsWithNativeBackoffAndDropsTheirPartialTurns(
      @TempDir Path root) throws Exception {
    var attempts = new AtomicInteger();
    var sleeps = new java.util.concurrent.CopyOnWriteArrayList<Duration>();
    var runner = new ProviderBackedSubagentRunner((configuration, prompt, tools) ->
        (turn, messages, cancellation, events) -> {
          int attempt = attempts.incrementAndGet();
          if (attempt <= 3) {
            events.accept(new StreamEvent.TextDelta("discard-" + attempt));
            events.accept(new StreamEvent.Error("temporary-" + attempt));
          } else {
            events.accept(new StreamEvent.TextDelta("final report"));
            events.accept(new StreamEvent.Finished(StopReason.END_TURN));
          }
        }, sleeps::add);
    runner.bind(call -> new ToolCompletion.Success("unused"));
    var configured = configuration(root, "model", new ProviderAuth.ApiKey("k"));
    runner.install(() -> configured);

    HostServices.SubagentResponse response = runner.run(
        new HostServices.SubagentRequest("retry", "general"));

    assertThat(attempts.get()).isEqualTo(4);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2),
        Duration.ofSeconds(4));
    assertThat(response.error()).isFalse();
    assertThat(response.report()).isEqualTo(
        "Subagent report (general, 1 turn):\n\nfinal report");
    assertThat(response.report()).doesNotContain("discard");
  }

  @Test
  void exhaustedCompletionRetriesBecomeOneTerminalToolError(@TempDir Path root)
      throws Exception {
    var attempts = new AtomicInteger();
    var runner = new ProviderBackedSubagentRunner((configuration, prompt, tools) ->
        (turn, messages, cancellation, events) -> {
          attempts.incrementAndGet();
          events.accept(new StreamEvent.Error("still offline"));
        });
    runner.bind(call -> new ToolCompletion.Success("unused"));
    var configured = configuration(root, "model", new ProviderAuth.ApiKey("k"));
    runner.install(() -> configured);

    HostServices.SubagentResponse response = runner.run(
        new HostServices.SubagentRequest("retry", "general"));

    assertThat(attempts.get()).isEqualTo(4);
    assertThat(response.error()).isTrue();
    assertThat(response.report()).isEqualTo("Subagent report (general, 1 turn):\n\n"
        + "[subagent failed without producing a final report]");
  }

  @Test
  void brokenConfigurationSupplierAndBrokenProgressObserverAreContained(@TempDir Path root)
      throws Exception {
    var unavailable = new ProviderBackedSubagentRunner((configuration, prompt, tools) ->
        (turn, messages, cancellation, events) -> {});
    unavailable.bind(call -> new ToolCompletion.Success("unused"));
    unavailable.install(() -> { throw new IllegalStateException("bad settings"); });
    assertThat(unavailable.available()).isFalse();

    var runner = new ProviderBackedSubagentRunner((configuration, prompt, tools) ->
        (turn, messages, cancellation, events) -> {
          events.accept(new StreamEvent.TextDelta("done"));
          events.accept(new StreamEvent.Finished(StopReason.END_TURN));
        });
    runner.bind(call -> new ToolCompletion.Success("unused"));
    var configured = configuration(root, "model", new ProviderAuth.ApiKey("k"));
    runner.install(() -> configured);
    HostServices.SubagentResponse response = runner.run(
        new HostServices.SubagentRequest("finish", "general"), new CancellationSignal(),
        ignored -> { throw new IllegalStateException("observer failed"); });
    assertThat(response.error()).isFalse();
    assertThat(response.report()).endsWith("\n\ndone");
  }

  @Test
  void stopsAfterTheSameToolCallFailsThreeTimes(@TempDir Path root) throws Exception {
    var completions = new AtomicInteger();
    var runner = new ProviderBackedSubagentRunner((configuration, prompt, tools) ->
        (turn, messages, cancellation, events) -> {
          int index = completions.incrementAndGet();
          events.accept(new StreamEvent.ToolUseStart("read-" + index, "read"));
          events.accept(new StreamEvent.ToolUseDelta("{\"path\":\"missing\"}"));
          events.accept(new StreamEvent.ToolUseEnd());
          events.accept(new StreamEvent.Finished(StopReason.TOOL_USE));
        });
    runner.bind(call -> new ToolCompletion.Failure("not found"));
    var configured = configuration(root, "model", new ProviderAuth.ApiKey("k"));
    runner.install(() -> configured);

    HostServices.SubagentResponse response = runner.run(
        new HostServices.SubagentRequest("find it", "explorer"));

    assertThat(completions.get()).isEqualTo(3);
    assertThat(response.error()).isFalse();
    assertThat(response.report()).isEqualTo("Subagent report (explorer, 3 turns):\n\n"
        + "[subagent stopped: the same tool call failed 3× in a row without converging]");
  }

  @Test
  void stopsAtTwentyFourToolCompletionsWithoutStartingATwentyFifthProviderRequest(
      @TempDir Path root) throws Exception {
    var completions = new AtomicInteger();
    var runner = new ProviderBackedSubagentRunner((configuration, prompt, tools) -> {
      int index = completions.incrementAndGet();
      return (turn, messages, cancellation, events) -> {
        events.accept(new StreamEvent.ToolUseStart("read-" + index, "read"));
        events.accept(new StreamEvent.ToolUseDelta("{\"path\":\"file-" + index + "\"}"));
        events.accept(new StreamEvent.ToolUseEnd());
        events.accept(new StreamEvent.Finished(StopReason.TOOL_USE));
      };
    });
    runner.bind(call -> new ToolCompletion.Success("ok"));
    var configured = configuration(root, "model", new ProviderAuth.ApiKey("k"));
    runner.install(() -> configured);

    HostServices.SubagentResponse response = runner.run(
        new HostServices.SubagentRequest("keep reading", "explorer"));

    assertThat(completions.get()).isEqualTo(ProviderBackedSubagentRunner.MAX_TURNS);
    assertThat(response.error()).isFalse();
    assertThat(response.report()).isEqualTo("Subagent report (explorer, 24 turns):\n\n"
        + "[subagent hit its turn budget without producing a final report]");
  }

  @Test
  void parentCancellationTripsTheIsolatedProviderAndReturnsPromptly(@TempDir Path root)
      throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var observedCancellation = new java.util.concurrent.atomic.AtomicBoolean();
    var runner = new ProviderBackedSubagentRunner((configuration, prompt, tools) ->
        (turn, messages, cancellation, events) -> {
          entered.countDown();
          while (!cancellation.isCancelled()) {
            try {
              java.lang.Thread.sleep(5);
            } catch (InterruptedException exception) {
              java.lang.Thread.currentThread().interrupt();
              break;
            }
          }
          observedCancellation.set(cancellation.isCancelled());
        });
    runner.bind(call -> new ToolCompletion.Success("unused"));
    var configured = configuration(root, "model", new ProviderAuth.ApiKey("k"));
    runner.install(() -> configured);
    var parent = new CancellationSignal();

    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var result = executor.submit(() -> runner.run(
          new HostServices.SubagentRequest("wait", "general"), parent));
      assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      parent.cancel();
      assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(new HostServices.SubagentResponse("subagent cancelled", true));
    }
    assertThat(observedCancellation).isTrue();
  }

  private static LiveProviderFactory.Configuration configuration(
      Path root, String model, ProviderAuth auth) throws Exception {
    return new LiveProviderFactory.Configuration("anthropic", model, auth, "",
        components(root).systemPrompt(), 200_000, Map.of());
  }

  private static ToolRuntimeFactory.Components components(Path root) throws Exception {
    Path workspace = java.nio.file.Files.createDirectories(root.resolve("workspace"));
    Path home = java.nio.file.Files.createDirectories(root.resolve("home"));
    return ToolRuntimeFactory.compose(ToolRuntimeFactory.Configuration.standalone(workspace, home));
  }
}
