package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.tools.policy.Effect;
import com.github.skanga.ajent.tools.policy.EffectSet;
import com.github.skanga.ajent.tools.runtime.ExternalToolRuntime;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AgentSessionFactoryTest {
  @Test
  void createsIndependentLoopWithSessionModelAndConfiguredContext(@TempDir Path root)
      throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    var tools = ToolRuntimeFactory.compose(
        ToolRuntimeFactory.Configuration.standalone(workspace, home));
    var base = new LiveProviderFactory.Configuration(
        "ollama", "default", new ProviderAuth.Empty(), "", tools.systemPrompt(),
        32_768, Map.of());
    var captured = new AtomicReference<LiveProviderFactory.Configuration>();
    var factory = new AgentSessionFactory(tools, base, HttpClient.newHttpClient(),
        root.resolve("data"), (configuration, ignored) -> {
          captured.set(configuration);
          return (turn, messages, cancellation, sink) -> {};
        });

    try (var loop = factory.create(
        new Thread(new ThreadId("session"), "Session", List.of()), Profile.ASK,
        "qwen3:14b", call -> new PermissionPort.Decision(true, false),
        (message, state) -> {})) {
      assertThat(loop.state().thread().id().value()).isEqualTo("session");
      loop.dispatch(new RuntimeMessage.Submit("hello", List.of()));
      waitFor(() -> captured.get() != null);
      assertThat(captured.get().model()).isEqualTo("qwen3:14b");
      assertThat(captured.get().provider()).isEqualTo("ollama");
    }

    var liveProfile = new AtomicReference<>(Profile.WRITE);
    var liveModel = new AtomicReference<>("first");
    try (var loop = factory.create(
        new Thread(new ThreadId("dynamic"), "Dynamic", List.of()), liveProfile::get,
        liveModel::get, call -> new PermissionPort.Decision(true, false),
        (message, state) -> {})) {
      liveProfile.set(Profile.MINIMAL);
      liveModel.set("second");
      loop.dispatch(new RuntimeMessage.Submit("hello", List.of()));
      waitFor(() -> captured.get() != null && "second".equals(captured.get().model()));
      assertThat(loop.state().thread().id().value()).isEqualTo("dynamic");
      assertThat(captured.get().model()).isEqualTo("second");
    }

    var liveConfiguration = new AtomicReference<>(base);
    try (var loop = factory.create(
        new Thread(new ThreadId("provider"), "Provider", List.of()), liveProfile::get,
        (AgentSessionFactory.ConfigurationSource) liveConfiguration::get,
        call -> new PermissionPort.Decision(true, false), (message, state) -> {})) {
      liveConfiguration.set(new LiveProviderFactory.Configuration(
          "openai", "third", new ProviderAuth.ApiKey("key"), "", tools.systemPrompt(),
          65_536, Map.of()));
      loop.dispatch(new RuntimeMessage.Submit("hello", List.of()));
      waitFor(() -> captured.get() != null && "openai".equals(captured.get().provider()));
      assertThat(captured.get().model()).isEqualTo("third");
    }

    assertThat(factory.contextMax()).isEqualTo(32_768);
  }

  @Test
  void appliesNativeProfilePolicyAndDeniesUnknownTools() {
    assertThat(AgentSessionFactory.permission(call("read"), Profile.ASK))
        .isEqualTo(PermissionVerdict.ALLOW);
    assertThat(AgentSessionFactory.permission(call("read"), Profile.MINIMAL))
        .isEqualTo(PermissionVerdict.PROMPT);
    assertThat(AgentSessionFactory.permission(call("bash"), Profile.ASK))
        .isEqualTo(PermissionVerdict.PROMPT);
    assertThat(AgentSessionFactory.permission(call("bash"), Profile.WRITE))
        .isEqualTo(PermissionVerdict.ALLOW);
    assertThat(AgentSessionFactory.permission(call("not-a-tool"), Profile.WRITE))
        .isEqualTo(PermissionVerdict.DENY);
  }

  @Test
  void appliesTheSameProfilePolicyToDynamicTools(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path home = Files.createDirectories(root.resolve("home"));
    var external = new ExternalToolRuntime() {
      @Override public List<ToolSpecification> specifications() { return List.of(); }
      @Override public Optional<EffectSet> effects(String name) {
        return switch (name) {
          case "remote_read" -> Optional.of(EffectSet.of(Effect.READ_FS));
          case "remote_net" -> Optional.of(EffectSet.of(Effect.NET));
          default -> Optional.empty();
        };
      }
      @Override public ToolResult execute(
          String name, com.fasterxml.jackson.databind.node.ObjectNode arguments) {
        throw new UnsupportedOperationException();
      }
    };
    var configuration = new ToolRuntimeFactory.Configuration(
        workspace, workspace, home, null, request -> {
          throw new UnsupportedOperationException();
        }, null, null, new com.github.skanga.ajent.tools.process.ProcessRunner(), external);
    var tools = ToolRuntimeFactory.compose(configuration);

    assertThat(AgentSessionFactory.permission(call("remote_read"), Profile.ASK, tools))
        .isEqualTo(PermissionVerdict.ALLOW);
    assertThat(AgentSessionFactory.permission(call("remote_read"), Profile.MINIMAL, tools))
        .isEqualTo(PermissionVerdict.PROMPT);
    assertThat(AgentSessionFactory.permission(call("remote_net"), Profile.ASK, tools))
        .isEqualTo(PermissionVerdict.PROMPT);
    assertThat(AgentSessionFactory.permission(call("missing"), Profile.WRITE, tools))
        .isEqualTo(PermissionVerdict.DENY);
  }

  private static ToolUse call(String name) {
    return new ToolUse(
        new ToolCallId("call"), new ToolName(name), Map.of(), new ToolStatus.Pending());
  }

  private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      java.lang.Thread.sleep(1);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }
}
