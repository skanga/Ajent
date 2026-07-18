package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
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

    assertThat(captured.get().provider()).isEqualTo("ollama");
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
