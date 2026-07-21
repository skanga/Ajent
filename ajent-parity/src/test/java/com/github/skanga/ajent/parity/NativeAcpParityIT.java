package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Differential ACP v1 characterization against the pinned AgenTTY executable. */
final class NativeAcpParityIT {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void offlineLifecycleMatchesPinnedExecutableOverStdio(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path workspace = Files.createDirectories(root.resolve("workspace"));

    try (var nativeAgent = AgentProcess.start(
             command(nativeBinary, workspace), root.resolve("native-home"), false);
         var javaAgent = AgentProcess.start(
             javaCommand(ajentJar, workspace), root.resolve("java-home"), true)) {
      Transcript nativeTranscript = exercise(nativeAgent, workspace);
      Transcript javaTranscript = exercise(javaAgent, workspace);

      assertThat(normalize(nativeTranscript, true))
          .isEqualTo(normalize(javaTranscript, false));
    }
  }

  @Test
  void completedThreadAndSessionIndexPersistenceMatchPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-persistence-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-persistence-workspace"));
    Path nativeHome = root.resolve("native-persistence-home");
    Path javaHome = root.resolve("java-persistence-home");
    var captures = new java.util.concurrent.CopyOnWriteArrayList<HostedCapture>();
    HostedH2Server provider = hostedProvider(captures);
    provider.start();
    Map<String, String> environment = hostedEnvironment(provider);
    try {
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(commandWithKey(
          nativeBinary, nativeWorkspace, "openai"), nativeHome, false, environment)) {
        nativeTranscript = exercisePlainPrompt(agent, nativeWorkspace, "persist this turn");
      }
      captures.clear();

      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommandWithKey(
          ajentJar, javaWorkspace, "openai"), javaHome, true, environment)) {
        javaTranscript = exercisePlainPrompt(agent, javaWorkspace, "persist this turn");
      }

      JsonNode nativeThread = normalizedPersistedThread(
          nativeHome, nativeTranscript.sessionId(), nativeWorkspace);
      JsonNode javaThread = normalizedPersistedThread(
          javaHome, javaTranscript.sessionId(), javaWorkspace);
      assertThat(javaThread).isEqualTo(nativeThread);
      assertThat(javaThread.path("messages")).hasSize(2);
      assertThat(javaThread.at("/messages/0/role").textValue()).isEqualTo("user");
      assertThat(javaThread.at("/messages/0/text").textValue()).isEqualTo("persist this turn\n");
      assertThat(javaThread.at("/messages/1/role").textValue()).isEqualTo("assistant");
      assertThat(javaThread.at("/messages/1/text").textValue()).isEqualTo("Hosted reply.");

      assertThat(normalizedSessionIndex(
          nativeHome, nativeTranscript.sessionId(), nativeWorkspace))
          .isEqualTo(normalizedSessionIndex(
              javaHome, javaTranscript.sessionId(), javaWorkspace));
      assertThat(Files.exists(nativeHome.resolve(".agentty/threads/acp_sessions.json.tmp")))
          .isFalse();
      assertThat(Files.exists(javaHome.resolve(".agentty/threads/acp_sessions.json.tmp")))
          .isFalse();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void nativeOllamaRequestAndFragmentedNdjsonMatchPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-ollama-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-ollama-workspace"));
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = scriptedOllamaProvider(requests);
    provider.start();
    Map<String, String> environment = Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + provider.getAddress().getPort());
    try {
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace),
          root.resolve("native-ollama-home"), false, environment)) {
        nativeTranscript = exercisePlainPrompt(agent, nativeWorkspace, "ollama parity probe");
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();

      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace),
          root.resolve("java-ollama-home"), true, environment)) {
        javaTranscript = exercisePlainPrompt(agent, javaWorkspace, "ollama parity probe");
      }
      List<JsonNode> javaRequests = List.copyOf(requests);

      assertThat(nativeRequests).hasSize(1);
      assertThat(javaRequests).hasSize(1);
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void nativeOllamaReloadedImageRequestMatchesPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-ollama-image-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-ollama-image-workspace"));
    Path nativeHome = root.resolve("native-ollama-image-home");
    Path javaHome = root.resolve("java-ollama-image-home");
    writeImageThread(nativeHome);
    writeImageThread(javaHome);
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = scriptedOllamaProvider(requests);
    provider.start();
    Map<String, String> environment = Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + provider.getAddress().getPort());
    try {
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace),
          nativeHome, false, environment)) {
        nativeTranscript = exerciseReloadedPrompt(agent, nativeWorkspace, "image-thread");
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();

      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace),
          javaHome, true, environment)) {
        javaTranscript = exerciseReloadedPrompt(agent, javaWorkspace, "image-thread");
      }
      List<JsonNode> javaRequests = List.copyOf(requests);

      assertThat(nativeRequests).hasSize(1);
      assertThat(javaRequests).hasSize(1);
      assertThat(nativeRequests.getFirst().at("/messages/1/images/0").textValue())
          .isEqualTo("YWJj");
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void nativeOllamaToolCallAndContinuationMatchPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-ollama-tool-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-ollama-tool-workspace"));
    var target = new AtomicReference<Path>();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = scriptedOllamaToolProvider(target, requests);
    provider.start();
    Map<String, String> environment = Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + provider.getAddress().getPort());
    try {
      Path nativeTarget = nativeWorkspace.resolve("ollama-tool.txt");
      target.set(nativeTarget);
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace),
          root.resolve("native-ollama-tool-home"), false, environment)) {
        nativeTranscript = exercisePrompt(agent, nativeWorkspace, "allow_always");
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();
      assertThat(nativeTarget).hasContent("written by ollama\n");

      Path javaTarget = javaWorkspace.resolve("ollama-tool.txt");
      target.set(javaTarget);
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace),
          root.resolve("java-ollama-tool-home"), true, environment)) {
        javaTranscript = exercisePrompt(agent, javaWorkspace, "allow_always");
      }
      List<JsonNode> javaRequests = List.copyOf(requests);
      assertThat(javaTarget).hasContent("written by ollama\n");

      assertThat(nativeRequests).hasSize(2);
      assertThat(javaRequests).hasSize(2);
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void nativeOllamaWeakModelJsonToolCallMatchesPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-ollama-json-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-ollama-json-workspace"));
    var target = new AtomicReference<Path>();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = scriptedOllamaJsonProtocolProvider(target, requests);
    provider.start();
    Map<String, String> environment = Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + provider.getAddress().getPort());
    String model = "qwen2.5-coder:7b";
    try {
      Path nativeTarget = nativeWorkspace.resolve("ollama-json-tool.txt");
      target.set(nativeTarget);
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace, "ollama", model),
          root.resolve("native-ollama-json-home"), false, environment)) {
        nativeTranscript = exercisePrompt(agent, nativeWorkspace, "allow_always");
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();
      assertThat(nativeTarget).hasContent("written by weak ollama\n");

      Path javaTarget = javaWorkspace.resolve("ollama-json-tool.txt");
      target.set(javaTarget);
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace, "ollama", model),
          root.resolve("java-ollama-json-home"), true, environment)) {
        javaTranscript = exercisePrompt(agent, javaWorkspace, "allow_always");
      }
      List<JsonNode> javaRequests = List.copyOf(requests);
      assertThat(javaTarget).hasContent("written by weak ollama\n");

      assertThat(nativeRequests).hasSize(2);
      assertThat(javaRequests).hasSize(2);
      assertThat(nativeRequests.getFirst().has("format")).isTrue();
      assertThat(nativeRequests.getFirst().has("tools")).isFalse();
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void nativeOllamaHttpErrorMatchesPinnedExecutable(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-ollama-error-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-ollama-error-workspace"));
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = ollamaErrorProvider(requests);
    provider.start();
    Map<String, String> environment = Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + provider.getAddress().getPort());
    try {
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace),
          root.resolve("native-ollama-error-home"), false, environment)) {
        nativeTranscript = exercisePlainPrompt(agent, nativeWorkspace, "missing model probe");
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();

      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace),
          root.resolve("java-ollama-error-home"), true, environment)) {
        javaTranscript = exercisePlainPrompt(agent, javaWorkspace, "missing model probe");
      }
      List<JsonNode> javaRequests = List.copyOf(requests);

      assertThat(nativeRequests).hasSize(1);
      assertThat(javaRequests).hasSize(1);
      assertThat(response(nativeTranscript.exchanges().getFirst())
          .at("/result/stopReason").textValue()).isEqualTo("refusal");
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void nativeOllamaCancellationMatchesPinnedExecutable(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-ollama-cancel-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-ollama-cancel-workspace"));
    var activeGate = new AtomicReference<CancelGate>();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = stalledOllamaProvider(activeGate, requests);
    provider.start();
    Map<String, String> environment = Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + provider.getAddress().getPort());
    try {
      CancelGate nativeGate = new CancelGate();
      activeGate.set(nativeGate);
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace),
          root.resolve("native-ollama-cancel-home"), false, environment)) {
        nativeTranscript = exerciseCancellation(agent, nativeWorkspace, nativeGate);
        nativeGate.release().set(true);
        assertThat(nativeGate.finished().await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        nativeGate.release().set(true);
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();

      CancelGate javaGate = new CancelGate();
      activeGate.set(javaGate);
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace),
          root.resolve("java-ollama-cancel-home"), true, environment)) {
        javaTranscript = exerciseCancellation(agent, javaWorkspace, javaGate);
        javaGate.release().set(true);
        assertThat(javaGate.finished().await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        javaGate.release().set(true);
      }
      List<JsonNode> javaRequests = List.copyOf(requests);

      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
      assertThat(response(nativeTranscript.exchanges().getFirst())
          .at("/result/stopReason").textValue()).isEqualTo("cancelled");
    } finally {
      CancelGate gate = activeGate.get();
      if (gate != null) gate.release().set(true);
      provider.stop(0);
    }
  }

  @Test
  void nativeAnthropicRequestAndFragmentedSseMatchPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-anthropic-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-anthropic-workspace"));
    var captures = new java.util.concurrent.CopyOnWriteArrayList<HostedCapture>();
    HostedH2Server provider = hostedAnthropicProvider(captures);
    provider.start();
    Map<String, String> environment = hostedEnvironment(provider);
    try {
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(commandWithKey(
          nativeBinary, nativeWorkspace, "anthropic"),
          root.resolve("native-anthropic-home"), false, environment)) {
        nativeTranscript = exercisePlainPrompt(agent, nativeWorkspace, "anthropic parity probe");
      }
      assertThat(captures).hasSize(1);
      HostedCapture nativeCapture = captures.getFirst();

      captures.clear();
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommandWithKey(
          ajentJar, javaWorkspace, "anthropic"),
          root.resolve("java-anthropic-home"), true, environment)) {
        javaTranscript = exercisePlainPrompt(agent, javaWorkspace, "anthropic parity probe");
      }
      assertThat(captures).hasSize(1);
      HostedCapture javaCapture = captures.getFirst();

      assertThat(nativeCapture.path()).isEqualTo("/v1/messages?beta=true");
      assertThat(javaCapture.path()).isEqualTo("/v1/messages?beta=true");
      assertGeneratedAnthropicUserId(nativeCapture.body());
      assertGeneratedAnthropicUserId(javaCapture.body());
      HostedCapture normalizedNative = normalizeHostedCapture(
          nativeCapture, nativeWorkspace, true);
      HostedCapture normalizedJava = normalizeHostedCapture(
          javaCapture, javaWorkspace, false);
      assertThat(normalizedNative.selectedHeaders()).isEqualTo(normalizedJava.selectedHeaders());
      assertThat(normalizedNative.body().at("/system/0/text").textValue())
          .doesNotContain("<big-codebases>", "<in-house-languages>");
      assertThat(normalizedJava.body().at("/system/0/text").textValue())
          .contains("<big-codebases>", "<in-house-languages>");
      assertThat(Files.readString(
          repository.resolve("agentty/src/provider/anthropic/transport.cpp")))
          .contains("<big-codebases>", "<in-house-languages>");
      assertThat(toolByName(normalizedNative.body(), "todo").has("eager_input_streaming"))
          .isFalse();
      assertThat(toolByName(normalizedJava.body(), "todo").path("eager_input_streaming")
          .booleanValue()).isTrue();
      assertThat(Files.readString(repository.resolve("agentty/include/agentty/tool/spec.hpp")))
          .containsPattern("ToolSpec\\{\"todo\"[^\\r\\n]+true,");
      assertThat(firstJsonListDifference(
          List.of(withoutSourceAheadAnthropicFields(normalizedNative.body())),
          List.of(withoutSourceAheadAnthropicFields(normalizedJava.body())))).isEmpty();
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(response(nativeTranscript.exchanges().getFirst())
          .at("/result/stopReason").textValue()).isEqualTo("end_turn");
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void nativeAnthropicToolCallAndContinuationMatchPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-anthropic-tool-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-anthropic-tool-workspace"));
    var target = new AtomicReference<Path>();
    var captures = new java.util.concurrent.CopyOnWriteArrayList<HostedCapture>();
    HostedH2Server provider = hostedAnthropicToolProvider(target, captures);
    provider.start();
    Map<String, String> environment = hostedEnvironment(provider);
    try {
      Path nativeTarget = nativeWorkspace.resolve("anthropic-tool.txt");
      target.set(nativeTarget);
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(commandWithKey(
          nativeBinary, nativeWorkspace, "anthropic"),
          root.resolve("native-anthropic-tool-home"), false, environment)) {
        nativeTranscript = exercisePrompt(agent, nativeWorkspace, "allow_always");
      }
      List<HostedCapture> nativeCaptures = List.copyOf(captures);
      captures.clear();
      assertThat(nativeTarget).hasContent("written by anthropic\n");

      Path javaTarget = javaWorkspace.resolve("anthropic-tool.txt");
      target.set(javaTarget);
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommandWithKey(
          ajentJar, javaWorkspace, "anthropic"),
          root.resolve("java-anthropic-tool-home"), true, environment)) {
        javaTranscript = exercisePrompt(agent, javaWorkspace, "allow_always");
      }
      List<HostedCapture> javaCaptures = List.copyOf(captures);
      assertThat(javaTarget).hasContent("written by anthropic\n");

      assertThat(nativeCaptures).hasSize(2);
      assertThat(javaCaptures).hasSize(2);
      assertAnthropicCapturesMatch(
          nativeCaptures, javaCaptures, nativeWorkspace, javaWorkspace);
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void nativeAnthropicHttpErrorMatchesPinnedExecutable(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-anthropic-error-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-anthropic-error-workspace"));
    var captures = new java.util.concurrent.CopyOnWriteArrayList<HostedCapture>();
    HostedH2Server provider = hostedAnthropicErrorProvider(captures);
    provider.start();
    Map<String, String> environment = hostedEnvironment(provider);
    try {
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(commandWithKey(
          nativeBinary, nativeWorkspace, "anthropic"),
          root.resolve("native-anthropic-error-home"), false, environment)) {
        nativeTranscript = exercisePlainPrompt(agent, nativeWorkspace, "fail once");
      }
      List<HostedCapture> nativeCaptures = List.copyOf(captures);
      captures.clear();

      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommandWithKey(
          ajentJar, javaWorkspace, "anthropic"),
          root.resolve("java-anthropic-error-home"), true, environment)) {
        javaTranscript = exercisePlainPrompt(agent, javaWorkspace, "fail once");
      }
      List<HostedCapture> javaCaptures = List.copyOf(captures);

      assertThat(nativeCaptures).hasSize(1);
      assertThat(javaCaptures).hasSize(1);
      assertAnthropicCapturesMatch(
          nativeCaptures, javaCaptures, nativeWorkspace, javaWorkspace);
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(response(nativeTranscript.exchanges().getFirst())
          .at("/result/stopReason").textValue()).isEqualTo("refusal");
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void nativeAnthropicCancellationMatchesPinnedExecutable(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-anthropic-cancel-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-anthropic-cancel-workspace"));
    var activeGate = new AtomicReference<CancelGate>();
    var captures = new java.util.concurrent.CopyOnWriteArrayList<HostedCapture>();
    HostedH2Server provider = hostedAnthropicStalledProvider(activeGate, captures);
    provider.start();
    Map<String, String> environment = hostedEnvironment(provider);
    try {
      CancelGate nativeGate = new CancelGate();
      activeGate.set(nativeGate);
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(commandWithKey(
          nativeBinary, nativeWorkspace, "anthropic"),
          root.resolve("native-anthropic-cancel-home"), false, environment)) {
        nativeTranscript = exerciseCancellation(agent, nativeWorkspace, nativeGate);
      } finally {
        nativeGate.release().set(true);
        assertThat(nativeGate.finished().await(5, TimeUnit.SECONDS)).isTrue();
      }
      List<HostedCapture> nativeCaptures = List.copyOf(captures);
      captures.clear();

      CancelGate javaGate = new CancelGate();
      activeGate.set(javaGate);
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommandWithKey(
          ajentJar, javaWorkspace, "anthropic"),
          root.resolve("java-anthropic-cancel-home"), true, environment)) {
        javaTranscript = exerciseCancellation(agent, javaWorkspace, javaGate);
      } finally {
        javaGate.release().set(true);
        assertThat(javaGate.finished().await(5, TimeUnit.SECONDS)).isTrue();
      }
      List<HostedCapture> javaCaptures = List.copyOf(captures);

      assertThat(nativeCaptures).hasSize(1);
      assertThat(javaCaptures).hasSize(1);
      assertAnthropicCapturesMatch(
          nativeCaptures, javaCaptures, nativeWorkspace, javaWorkspace);
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(response(nativeTranscript.exchanges().getFirst())
          .at("/result/stopReason").textValue()).isEqualTo("cancelled");
    } finally {
      CancelGate gate = activeGate.get();
      if (gate != null) gate.release().set(true);
      provider.stop(0);
    }
  }

  @Test
  void livePromptPermissionToolAndContinuationMatchPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-workspace"));
    var target = new AtomicReference<Path>();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = scriptedProvider(target, requests);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    try {
      Transcript nativeTranscript;
      target.set(nativeWorkspace.resolve("out.txt"));
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace, endpoint),
          root.resolve("native-prompt-home"), false)) {
        nativeTranscript = exercisePrompt(agent, nativeWorkspace);
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();
      assertThat(target.get()).as(nativeTranscript.toString()).hasContent("hello from acp\n");
      assertThat(clientCallbackRequestCount(nativeTranscript)).isZero();

      Transcript javaTranscript;
      target.set(javaWorkspace.resolve("out.txt"));
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace, endpoint),
          root.resolve("java-prompt-home"), true)) {
        javaTranscript = exercisePrompt(agent, javaWorkspace);
      }
      List<JsonNode> javaRequests = List.copyOf(requests);
      assertThat(target.get()).as(javaTranscript.toString()).hasContent("hello from acp\n");
      assertThat(clientCallbackRequestCount(javaTranscript)).isZero();

      Transcript normalizedNative = normalizePrompt(nativeTranscript, nativeWorkspace, true);
      Transcript normalizedJava = normalizePrompt(javaTranscript, javaWorkspace, false);
      assertThat(firstDifference(normalizedNative, normalizedJava)).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void rejectedPermissionAndContinuationMatchPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-reject-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-reject-workspace"));
    var target = new AtomicReference<Path>();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = scriptedProvider(target, requests);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    try {
      target.set(nativeWorkspace.resolve("rejected.txt"));
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace, endpoint),
          root.resolve("native-reject-home"), false)) {
        nativeTranscript = exercisePrompt(agent, nativeWorkspace, "reject_once");
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();
      assertThat(target.get()).doesNotExist();

      target.set(javaWorkspace.resolve("rejected.txt"));
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace, endpoint),
          root.resolve("java-reject-home"), true)) {
        javaTranscript = exercisePrompt(agent, javaWorkspace, "reject_once");
      }
      List<JsonNode> javaRequests = List.copyOf(requests);
      assertThat(target.get()).doesNotExist();

      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void allowAlwaysPersistsAcrossTwoToolsLikePinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-always-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-always-workspace"));
    var targets = new AtomicReference<List<Path>>();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = scriptedTwoWriteProvider(targets, requests);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    try {
      List<Path> nativeTargets = List.of(
          nativeWorkspace.resolve("first.txt"), nativeWorkspace.resolve("second.txt"));
      targets.set(nativeTargets);
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace, endpoint),
          root.resolve("native-always-home"), false)) {
        nativeTranscript = exercisePrompt(agent, nativeWorkspace, "allow_always");
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();
      assertThat(nativeTargets.get(0)).hasContent("first\n");
      assertThat(nativeTargets.get(1)).hasContent("second\n");
      assertThat(permissionRequestCount(nativeTranscript)).isOne();

      List<Path> javaTargets = List.of(
          javaWorkspace.resolve("first.txt"), javaWorkspace.resolve("second.txt"));
      targets.set(javaTargets);
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace, endpoint),
          root.resolve("java-always-home"), true)) {
        javaTranscript = exercisePrompt(agent, javaWorkspace, "allow_always");
      }
      List<JsonNode> javaRequests = List.copyOf(requests);
      assertThat(javaTargets.get(0)).hasContent("first\n");
      assertThat(javaTargets.get(1)).hasContent("second\n");
      assertThat(permissionRequestCount(javaTranscript)).isOne();

      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void fragmentedMultiToolBatchMatchesPinnedExecutable(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-fragment-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-fragment-workspace"));
    var target = new AtomicReference<Path>();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = scriptedFragmentedMultiToolProvider(target, requests);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    try {
      Path nativeTarget = nativeWorkspace.resolve("fragmented.txt");
      target.set(nativeTarget);
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace, endpoint),
          root.resolve("native-fragment-home"), false)) {
        nativeTranscript = exercisePrompt(agent, nativeWorkspace, "allow_always");
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();
      assertThat(nativeTarget).hasContent("second fragment\n");

      Path javaTarget = javaWorkspace.resolve("fragmented.txt");
      target.set(javaTarget);
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace, endpoint),
          root.resolve("java-fragment-home"), true)) {
        javaTranscript = exercisePrompt(agent, javaWorkspace, "allow_always");
      }
      List<JsonNode> javaRequests = List.copyOf(requests);
      assertThat(javaTarget).hasContent("second fragment\n");

      assertThat(nativeRequests).hasSize(2);
      assertThat(javaRequests).hasSize(2);
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void weakModelLeakedToolCallSalvageMatchesPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-salvage-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-salvage-workspace"));
    var target = new AtomicReference<Path>();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = scriptedWeakModelSalvageProvider(target, requests);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    String model = "qwen2.5-coder:7b";
    try {
      Path nativeTarget = nativeWorkspace.resolve("salvaged.txt");
      target.set(nativeTarget);
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(
          command(nativeBinary, nativeWorkspace, endpoint, model),
          root.resolve("native-salvage-home"), false)) {
        nativeTranscript = exercisePrompt(agent, nativeWorkspace);
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();
      assertThat(nativeTarget).hasContent("salvaged tool call\n");

      Path javaTarget = javaWorkspace.resolve("salvaged.txt");
      target.set(javaTarget);
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(
          javaCommand(ajentJar, javaWorkspace, endpoint, model),
          root.resolve("java-salvage-home"), true)) {
        javaTranscript = exercisePrompt(agent, javaWorkspace);
      }
      List<JsonNode> javaRequests = List.copyOf(requests);
      assertThat(javaTarget).hasContent("salvaged tool call\n");

      assertThat(nativeRequests).hasSize(2);
      assertThat(javaRequests).hasSize(2);
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void rateLimitedHttpErrorDoesNotRetryInAcpLikePinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-retry-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-retry-workspace"));
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = retryingProvider(attempts, requests);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    try {
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace, endpoint),
          root.resolve("native-retry-home"), false)) {
        nativeTranscript = exercisePlainPrompt(agent, nativeWorkspace, "please recover");
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      assertThat(attempts).hasValue(1);
      requests.clear();
      attempts.set(0);

      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace, endpoint),
          root.resolve("java-retry-home"), true)) {
        javaTranscript = exercisePlainPrompt(agent, javaWorkspace, "please recover");
      }
      List<JsonNode> javaRequests = List.copyOf(requests);
      assertThat(attempts).hasValue(1);

      assertThat(nativeRequests).hasSize(1);
      assertThat(javaRequests).hasSize(1);
      assertThat(response(nativeTranscript.exchanges().getFirst())
          .at("/result/stopReason").textValue()).isEqualTo("refusal");
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void terminalHttpErrorMapsToRefusalLikePinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-http-error-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-http-error-workspace"));
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = terminalErrorProvider(requests);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    try {
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace, endpoint),
          root.resolve("native-http-error-home"), false)) {
        nativeTranscript = exercisePlainPrompt(agent, nativeWorkspace, "fail once");
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();

      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace, endpoint),
          root.resolve("java-http-error-home"), true)) {
        javaTranscript = exercisePlainPrompt(agent, javaWorkspace, "fail once");
      }
      List<JsonNode> javaRequests = List.copyOf(requests);

      assertThat(nativeRequests).hasSize(1);
      assertThat(javaRequests).hasSize(1);
      assertThat(response(nativeTranscript.exchanges().getFirst())
          .at("/result/stopReason").textValue()).isEqualTo("refusal");
      assertThat(firstDifference(
          normalizePrompt(nativeTranscript, nativeWorkspace, true),
          normalizePrompt(javaTranscript, javaWorkspace, false))).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void cancellationOfStalledProviderTurnMatchesPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-cancel-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-cancel-workspace"));
    var activeGate = new AtomicReference<CancelGate>();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    HttpServer provider = stalledProvider(activeGate, requests);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    try {
      CancelGate nativeGate = new CancelGate();
      activeGate.set(nativeGate);
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace, endpoint),
          root.resolve("native-cancel-home"), false)) {
        nativeTranscript = exerciseCancellation(agent, nativeWorkspace, nativeGate);
      } finally {
        nativeGate.release().set(true);
        assertThat(nativeGate.finished().await(5, TimeUnit.SECONDS)).isTrue();
      }
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();

      CancelGate javaGate = new CancelGate();
      activeGate.set(javaGate);
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace, endpoint),
          root.resolve("java-cancel-home"), true)) {
        javaTranscript = exerciseCancellation(agent, javaWorkspace, javaGate);
      } finally {
        javaGate.release().set(true);
        assertThat(javaGate.finished().await(5, TimeUnit.SECONDS)).isTrue();
      }
      List<JsonNode> javaRequests = List.copyOf(requests);

      Transcript normalizedNative = normalizePrompt(nativeTranscript, nativeWorkspace, true);
      Transcript normalizedJava = normalizePrompt(javaTranscript, javaWorkspace, false);
      assertThat(firstDifference(normalizedNative, normalizedJava))
          .as("native=%s%njava=%s", normalizedNative, normalizedJava).isEmpty();
      assertThat(firstJsonListDifference(
          normalizeRequests(nativeRequests, nativeWorkspace, true),
          normalizeRequests(javaRequests, javaWorkspace, false))).isEmpty();
      assertThat(response(nativeTranscript.exchanges().getFirst())
          .at("/result/stopReason").textValue()).isEqualTo("cancelled");
    } finally {
      CancelGate gate = activeGate.get();
      if (gate != null) gate.release().set(true);
      provider.stop(0);
    }
  }

  @Test
  void hostedPresetEndpointsAndHeadersMatchPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    var captures = new java.util.concurrent.CopyOnWriteArrayList<HostedCapture>();
    HostedH2Server provider = hostedProvider(captures);
    provider.start();
    Map<String, String> environment = hostedEnvironment(provider);
    Map<String, String> paths = Map.of(
        "openai", "/v1/chat/completions",
        "groq", "/openai/v1/chat/completions",
        "openrouter", "/api/v1/chat/completions",
        "together", "/v1/chat/completions",
        "cerebras", "/v1/chat/completions");
    Map<String, String> hosts = Map.of(
        "openai", "api.openai.com",
        "groq", "api.groq.com",
        "openrouter", "openrouter.ai",
        "together", "api.together.xyz",
        "cerebras", "api.cerebras.ai");
    try {
      for (String preset : List.of("openai", "groq", "openrouter", "together", "cerebras")) {
        Path nativeWorkspace = Files.createDirectories(root.resolve("native-" + preset));
        captures.clear();
        Transcript nativeTranscript;
        try (var agent = AgentProcess.start(
            commandWithKey(nativeBinary, nativeWorkspace, preset),
            root.resolve("native-" + preset + "-home"), false, environment)) {
          nativeTranscript = exercisePlainPrompt(agent, nativeWorkspace, "hosted preset probe");
        }
        assertThat(captures).as("native %s transcript=%s", preset, nativeTranscript).hasSize(1);
        HostedCapture nativeCapture = captures.getFirst();

        Path javaWorkspace = Files.createDirectories(root.resolve("java-" + preset));
        captures.clear();
        Transcript javaTranscript;
        try (var agent = AgentProcess.start(
            javaCommandWithKey(ajentJar, javaWorkspace, preset),
            root.resolve("java-" + preset + "-home"), true, environment)) {
          javaTranscript = exercisePlainPrompt(agent, javaWorkspace, "hosted preset probe");
        }
        assertThat(captures).as("java %s transcript=%s", preset, javaTranscript).hasSize(1);
        HostedCapture javaCapture = captures.getFirst();

        assertThat(nativeCapture.path()).isEqualTo(paths.get(preset));
        assertThat(javaCapture.path()).isEqualTo(paths.get(preset));
        assertThat(nativeCapture.selectedHeaders()).containsEntry("host", hosts.get(preset));
        assertThat(javaCapture.selectedHeaders()).containsEntry("host", hosts.get(preset));
        assertThat(normalizeHostedCapture(nativeCapture, nativeWorkspace, true))
            .as(preset).isEqualTo(normalizeHostedCapture(javaCapture, javaWorkspace, false));
        assertThat(normalizePrompt(nativeTranscript, nativeWorkspace, true))
            .isEqualTo(normalizePrompt(javaTranscript, javaWorkspace, false));
      }
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void hostedTlsCancellationMatchesPinnedExecutable(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-hosted-cancel"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-hosted-cancel"));
    var activeGate = new AtomicReference<CancelGate>();
    var captures = new java.util.concurrent.CopyOnWriteArrayList<HostedCapture>();
    HostedH2Server provider = hostedStalledProvider(activeGate, captures);
    provider.start();
    Map<String, String> environment = hostedEnvironment(provider);
    try {
      CancelGate nativeGate = new CancelGate();
      activeGate.set(nativeGate);
      Transcript nativeTranscript;
      try (var agent = AgentProcess.start(commandWithKey(nativeBinary, nativeWorkspace, "openai"),
          root.resolve("native-hosted-cancel-home"), false, environment)) {
        nativeTranscript = exerciseCancellation(agent, nativeWorkspace, nativeGate);
      } finally {
        nativeGate.release().set(true);
        assertThat(nativeGate.finished().await(5, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(captures).hasSize(1);
      HostedCapture nativeCapture = captures.getFirst();

      captures.clear();
      CancelGate javaGate = new CancelGate();
      activeGate.set(javaGate);
      Transcript javaTranscript;
      try (var agent = AgentProcess.start(javaCommandWithKey(ajentJar, javaWorkspace, "openai"),
          root.resolve("java-hosted-cancel-home"), true, environment)) {
        javaTranscript = exerciseCancellation(agent, javaWorkspace, javaGate);
      } finally {
        javaGate.release().set(true);
        assertThat(javaGate.finished().await(5, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(captures).hasSize(1);
      HostedCapture javaCapture = captures.getFirst();

      assertThat(normalizeHostedCapture(nativeCapture, nativeWorkspace, true))
          .isEqualTo(normalizeHostedCapture(javaCapture, javaWorkspace, false));
      assertThat(normalizePrompt(nativeTranscript, nativeWorkspace, true))
          .isEqualTo(normalizePrompt(javaTranscript, javaWorkspace, false));
      assertThat(response(nativeTranscript.exchanges().getFirst())
          .at("/result/stopReason").textValue()).isEqualTo("cancelled");
    } finally {
      CancelGate gate = activeGate.get();
      if (gate != null) gate.release().set(true);
      provider.stop(0);
    }
  }

  @Test
  void concurrentPromptsInSeparateSessionsMatchPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-concurrent-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-concurrent-workspace"));
    var activeBarrier = new AtomicReference<ConcurrentBarrier>();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    var providerExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    HttpServer provider = concurrentProvider(activeBarrier, requests, providerExecutor);
    provider.start();
    String endpoint = "127.0.0.1:" + provider.getAddress().getPort();
    try {
      ConcurrentBarrier nativeBarrier = new ConcurrentBarrier();
      activeBarrier.set(nativeBarrier);
      ConcurrentTranscript nativeTranscript;
      try (var agent = AgentProcess.start(command(nativeBinary, nativeWorkspace, endpoint),
          root.resolve("native-concurrent-home"), false)) {
        nativeTranscript = exerciseConcurrentPrompts(agent, nativeWorkspace, nativeBarrier);
      }
      assertThat(nativeBarrier.finished().await(5, TimeUnit.SECONDS)).isTrue();
      List<JsonNode> nativeRequests = List.copyOf(requests);
      requests.clear();

      ConcurrentBarrier javaBarrier = new ConcurrentBarrier();
      activeBarrier.set(javaBarrier);
      ConcurrentTranscript javaTranscript;
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace, endpoint),
          root.resolve("java-concurrent-home"), true)) {
        javaTranscript = exerciseConcurrentPrompts(agent, javaWorkspace, javaBarrier);
      }
      assertThat(javaBarrier.finished().await(5, TimeUnit.SECONDS)).isTrue();
      List<JsonNode> javaRequests = List.copyOf(requests);

      assertThat(nativeRequests).hasSize(2);
      assertThat(javaRequests).hasSize(2);
      assertThat(normalizeConcurrent(nativeTranscript, nativeWorkspace, true))
          .isEqualTo(normalizeConcurrent(javaTranscript, javaWorkspace, false));
      assertConcurrentRequestsMatchExceptNativeColdCatalogRace(
          sortedNormalizedRequests(nativeRequests, nativeWorkspace, true),
          sortedNormalizedRequests(javaRequests, javaWorkspace, false));
    } finally {
      ConcurrentBarrier barrier = activeBarrier.get();
      if (barrier != null) barrier.release().countDown();
      provider.stop(0);
      providerExecutor.close();
    }
  }

  @Test
  void persistedCredentialAuthenticationLifecycleMatchesPinnedExecutable(@TempDir Path root)
      throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path workspace = Files.createDirectories(root.resolve("authenticated-workspace"));
    Path nativeHome = root.resolve("native-auth-home");
    Path javaHome = root.resolve("java-auth-home");
    installApiKey(List.of(nativeBinary.toString(), "login"), nativeHome, false);
    installApiKey(List.of(javaExecutable(), "-jar", ajentJar.toString(), "login"),
        javaHome, true);

    Transcript nativeTranscript;
    try (var agent = AgentProcess.start(command(nativeBinary, workspace, "anthropic"),
        nativeHome, false)) {
      nativeTranscript = exerciseAuthentication(agent);
    }
    Transcript javaTranscript;
    try (var agent = AgentProcess.start(javaCommand(ajentJar, workspace, "anthropic"),
        javaHome, true)) {
      javaTranscript = exerciseAuthentication(agent);
    }

    assertThat(normalize(nativeTranscript, true)).isEqualTo(normalize(javaTranscript, false));
  }

  private static Transcript exercisePrompt(AgentProcess agent, Path workspace) throws Exception {
    return exercisePrompt(agent, workspace, "allow_once");
  }

  private static Transcript exerciseCancellation(
      AgentProcess agent, Path workspace, CancelGate gate) throws Exception {
    agent.call("initialize", JSON.readTree(
        "{\"protocolVersion\":1,\"clientCapabilities\":{}}"));
    List<JsonNode> created = agent.call("session/new", JSON.createObjectNode()
        .put("cwd", workspace.toString()).set("mcpServers", JSON.createArrayNode()));
    String sessionId = response(created).path("result").path("sessionId").textValue();
    ObjectNode params = session(sessionId);
    var prompt = JSON.createArrayNode();
    prompt.addObject().put("type", "text").put("text", "wait until cancelled");
    params.set("prompt", prompt);
    int promptId = agent.sendRequest("session/prompt", params);
    assertThat(gate.started().await(5, TimeUnit.SECONDS)).isTrue();
    agent.sendNotification("session/cancel", session(sessionId));
    return new Transcript(sessionId, List.of(agent.readUntilResponse(promptId, "")));
  }

  private static ConcurrentTranscript exerciseConcurrentPrompts(
      AgentProcess agent, Path workspace, ConcurrentBarrier barrier) throws Exception {
    agent.call("initialize", JSON.readTree(
        "{\"protocolVersion\":1,\"clientCapabilities\":{}}"));
    List<JsonNode> firstCreated = agent.call("session/new", JSON.createObjectNode()
        .put("cwd", workspace.toString()).set("mcpServers", JSON.createArrayNode()));
    List<JsonNode> secondCreated = agent.call("session/new", JSON.createObjectNode()
        .put("cwd", workspace.toString()).set("mcpServers", JSON.createArrayNode()));
    String firstSession = response(firstCreated).at("/result/sessionId").textValue();
    String secondSession = response(secondCreated).at("/result/sessionId").textValue();
    int firstPrompt = agent.sendRequest("session/prompt",
        prompt(firstSession, "first concurrent prompt"));
    int secondPrompt = agent.sendRequest("session/prompt",
        prompt(secondSession, "second concurrent prompt"));
    assertThat(barrier.started().await(5, TimeUnit.SECONDS)).isTrue();
    barrier.release().countDown();
    List<JsonNode> frames = agent.readUntilResponses(Set.of(firstPrompt, secondPrompt));
    return new ConcurrentTranscript(
        firstSession, firstPrompt, secondSession, secondPrompt, frames);
  }

  private static Transcript exerciseAuthentication(AgentProcess agent) throws Exception {
    var exchanges = new ArrayList<List<JsonNode>>();
    exchanges.add(agent.call("initialize", JSON.readTree(
        "{\"protocolVersion\":1,\"clientCapabilities\":{}}")));
    exchanges.add(agent.call("authenticate", JSON.createObjectNode()));
    exchanges.add(agent.call("authenticate", JSON.readTree("{\"methodId\":\"agent\"}")));
    exchanges.add(agent.call("logout", JSON.createObjectNode()));
    exchanges.add(agent.call("authenticate", JSON.readTree("{\"methodId\":\"agent\"}")));
    return new Transcript("__NO_SESSION__", List.copyOf(exchanges));
  }

  private static void installApiKey(
      List<String> command, Path home, boolean javaProcess) throws Exception {
    Files.createDirectories(home);
    var effective = new ArrayList<>(command);
    if (javaProcess) effective.add(1, "-Duser.home=" + home);
    var builder = new ProcessBuilder(effective).redirectErrorStream(false);
    builder.environment().putAll(Map.of(
        "HOME", home.toString(), "USERPROFILE", home.toString(), "APPDATA", home.toString()));
    Process process = builder.start();
    try (var input = process.getOutputStream()) {
      input.write("2\nsk-ant-parity-test\n".getBytes(StandardCharsets.UTF_8));
    }
    assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(stderr).isZero();
    assertThat(stdout + stderr).doesNotContain("sk-ant-parity-test");
  }

  private static ObjectNode prompt(String sessionId, String text) {
    ObjectNode params = session(sessionId);
    var prompt = JSON.createArrayNode();
    prompt.addObject().put("type", "text").put("text", text);
    params.set("prompt", prompt);
    return params;
  }

  private static Transcript exercisePrompt(
      AgentProcess agent, Path workspace, String permissionOption) throws Exception {
    agent.call("initialize", initializeWithClientCallbacks());
    List<JsonNode> created = agent.call("session/new", JSON.createObjectNode()
        .put("cwd", workspace.toString()).set("mcpServers", JSON.createArrayNode()));
    String sessionId = response(created).path("result").path("sessionId").textValue();
    ObjectNode params = session(sessionId);
    var prompt = JSON.createArrayNode();
    prompt.addObject().put("type", "text").put("text", "please write the file");
    params.set("prompt", prompt);
    return new Transcript(sessionId, List.of(
        agent.callWithPermission("session/prompt", params, permissionOption)));
  }

  private static Transcript exercisePlainPrompt(
      AgentProcess agent, Path workspace, String text) throws Exception {
    agent.call("initialize", initializeWithClientCallbacks());
    List<JsonNode> created = agent.call("session/new", JSON.createObjectNode()
        .put("cwd", workspace.toString()).set("mcpServers", JSON.createArrayNode()));
    String sessionId = response(created).path("result").path("sessionId").textValue();
    return new Transcript(sessionId, List.of(agent.call("session/prompt", prompt(sessionId, text))));
  }

  private static Transcript exerciseReloadedPrompt(
      AgentProcess agent, Path workspace, String sessionId) throws Exception {
    agent.call("initialize", initializeWithClientCallbacks());
    agent.call("session/load", session(sessionId).put("cwd", workspace.toString())
        .set("mcpServers", JSON.createArrayNode()));
    return new Transcript(sessionId, List.of(
        agent.call("session/prompt", prompt(sessionId, "describe the prior image"))));
  }

  private static void writeImageThread(Path home) throws Exception {
    Path threads = Files.createDirectories(home.resolve(".agentty/threads"));
    Files.writeString(threads.resolve("image-thread.json"), """
        {"id":"image-thread","title":"Image parity","created_at":1,"updated_at":1,
         "messages":[{"id":"image-message","role":"user","text":"prior image",
          "timestamp":1,"tool_calls":[],"images":[
            {"media_type":"image/png","data":"YWJj"}]}],"compactions":[]}
        """, StandardCharsets.UTF_8);
  }

  private static HttpServer scriptedProvider(
      AtomicReference<Path> target, List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try (exchange) {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        requests.add(request.deepCopy());
        boolean continuation = false;
        for (JsonNode message : request.path("messages")) {
          if ("tool".equals(message.path("role").asText())) continuation = true;
        }
        String body;
        if (continuation) {
          body = "data: {\"choices\":[{\"delta\":{\"content\":\"Done.\"}}]}\n\n"
              + "data: {\"usage\":{\"prompt_tokens\":1300,\"completion_tokens\":10},"
              + "\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
              + "data: [DONE]\n\n";
        } else {
          String arguments = JSON.writeValueAsString(JSON.createObjectNode()
              .put("path", target.get().toString()).put("content", "hello from acp\n"));
          ObjectNode frame = JSON.createObjectNode();
          ObjectNode choice = frame.putArray("choices").addObject();
          ObjectNode delta = choice.putObject("delta");
          delta.put("content", "Writing the file. ");
          ObjectNode call = delta.putArray("tool_calls").addObject();
          call.put("index", 0).put("id", "tc_write_0").put("type", "function");
          call.putObject("function").put("name", "write").put("arguments", arguments);
          body = "data: " + JSON.writeValueAsString(frame) + "\n\n"
              + "data: {\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":40},"
              + "\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
              + "data: [DONE]\n\n";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    return server;
  }

  private static HttpServer scriptedOllamaProvider(List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/show", exchange -> {
      try (exchange) {
        byte[] bytes = "{\"model_info\":{\"qwen3.context_length\":32768},"
            .concat("\"capabilities\":[\"tools\"]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.createContext("/api/chat", exchange -> {
      try (exchange) {
        requests.add(JSON.readTree(exchange.getRequestBody()).deepCopy());
        String first = "{\"model\":\"qwen3:14b\",\"message\":{"
            + "\"role\":\"assistant\",\"content\":\"Ollama parity \"},\"done\":false}\n";
        String second = "{\"model\":\"qwen3:14b\",\"message\":{"
            + "\"role\":\"assistant\",\"content\":\"reply.\"},\"done\":false}\n";
        String done = "{\"model\":\"qwen3:14b\",\"message\":{"
            + "\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
            + "\"done_reason\":\"stop\",\"prompt_eval_count\":321,\"eval_count\":7}\n";
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, 0);
        byte[] body = (first + second + done).getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < body.length; offset += 7) {
          exchange.getResponseBody().write(body, offset, Math.min(7, body.length - offset));
          exchange.getResponseBody().flush();
        }
      }
    });
    return server;
  }

  private static HttpServer scriptedOllamaToolProvider(
      AtomicReference<Path> target, List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/show", exchange -> {
      try (exchange) {
        byte[] bytes = "{\"model_info\":{\"qwen3.context_length\":32768},"
            .concat("\"capabilities\":[\"tools\"]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.createContext("/api/chat", exchange -> {
      try (exchange) {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        requests.add(request.deepCopy());
        boolean continuation = false;
        for (JsonNode message : request.path("messages")) {
          if ("tool".equals(message.path("role").asText())) continuation = true;
        }
        ObjectNode frame = JSON.createObjectNode().put("model", "qwen3:14b");
        ObjectNode message = frame.putObject("message").put("role", "assistant");
        if (continuation) {
          message.put("content", "Ollama tool complete.");
        } else {
          message.put("content", "");
          ObjectNode function = message.putArray("tool_calls").addObject().putObject("function");
          function.put("name", "write").putObject("arguments")
              .put("path", target.get().toString()).put("content", "written by ollama\n");
        }
        frame.put("done", true).put("done_reason", continuation ? "stop" : "tool_calls")
            .put("prompt_eval_count", continuation ? 420 : 350)
            .put("eval_count", continuation ? 8 : 12);
        byte[] bytes = (JSON.writeValueAsString(frame) + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    return server;
  }

  private static HttpServer scriptedOllamaJsonProtocolProvider(
      AtomicReference<Path> target, List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/show", exchange -> {
      try (exchange) {
        byte[] bytes = "{\"model_info\":{\"qwen2.context_length\":32768},"
            .concat("\"capabilities\":[\"tools\"]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.createContext("/api/chat", exchange -> {
      try (exchange) {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        requests.add(request.deepCopy());
        boolean continuation = requests.size() % 2 == 0;
        List<String> chunks;
        if (continuation) {
          chunks = List.of("{\"tool_name\":\"response\",",
              "\"tool_args\":{\"text\":\"Weak tool complete.\"}}");
        } else {
          ObjectNode tool = JSON.createObjectNode();
          tool.putArray("thoughts").add("write the requested file");
          tool.put("tool_name", "write");
          tool.putObject("tool_args").put("file_path", target.get().toString())
              .put("content", "written by weak ollama\n");
          String payload = JSON.writeValueAsString(tool);
          int first = payload.length() / 3;
          int second = first * 2;
          chunks = List.of(payload.substring(0, first), payload.substring(first, second),
              payload.substring(second));
        }
        var body = new StringBuilder();
        for (String chunk : chunks) {
          ObjectNode frame = JSON.createObjectNode().put("model", "qwen2.5-coder:7b");
          frame.putObject("message").put("role", "assistant").put("content", chunk);
          frame.put("done", false);
          body.append(JSON.writeValueAsString(frame)).append('\n');
        }
        ObjectNode done = JSON.createObjectNode().put("model", "qwen2.5-coder:7b");
        done.putObject("message").put("role", "assistant").put("content", "");
        done.put("done", true).put("done_reason", "stop")
            .put("prompt_eval_count", continuation ? 480 : 410)
            .put("eval_count", continuation ? 9 : 15);
        body.append(JSON.writeValueAsString(done)).append('\n');
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    return server;
  }

  private static HttpServer ollamaErrorProvider(List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/show", exchange -> {
      try (exchange) {
        byte[] bytes = "{\"model_info\":{},\"capabilities\":[\"tools\"]}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.createContext("/api/chat", exchange -> {
      try (exchange) {
        requests.add(JSON.readTree(exchange.getRequestBody()).deepCopy());
        byte[] bytes = "{\"error\":\"model 'qwen3:14b' not found\"}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(404, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    return server;
  }

  private static HttpServer stalledOllamaProvider(
      AtomicReference<CancelGate> activeGate, List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/api/show", exchange -> {
      try (exchange) {
        byte[] bytes = "{\"model_info\":{\"qwen3.context_length\":32768},"
            .concat("\"capabilities\":[\"tools\"]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.createContext("/api/chat", exchange -> {
      CancelGate gate = activeGate.get();
      try (exchange) {
        requests.add(JSON.readTree(exchange.getRequestBody()).deepCopy());
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, 0);
        String heartbeat = "{\"model\":\"qwen3:14b\",\"message\":{"
            + "\"role\":\"assistant\",\"content\":\"\"},\"done\":false}\n";
        exchange.getResponseBody().write(heartbeat.repeat(128).getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        gate.started().countDown();
        while (!gate.release().get()) {
          java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
        }
      } catch (java.io.IOException ignored) {
        // Cancellation closes the response stream.
      } finally {
        gate.started().countDown();
        gate.finished().countDown();
      }
    });
    return server;
  }

  private static HttpServer scriptedTwoWriteProvider(
      AtomicReference<List<Path>> targets, List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try (exchange) {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        requests.add(request.deepCopy());
        int toolResults = 0;
        for (JsonNode message : request.path("messages")) {
          if ("tool".equals(message.path("role").asText())) toolResults++;
        }
        String body;
        if (toolResults >= 2) {
          body = "data: {\"choices\":[{\"delta\":{\"content\":\"Both done.\"}}]}\n\n"
              + "data: {\"usage\":{\"prompt_tokens\":1400,\"completion_tokens\":10},"
              + "\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
              + "data: [DONE]\n\n";
        } else {
          int index = toolResults;
          String content = index == 0 ? "first\n" : "second\n";
          String arguments = JSON.writeValueAsString(JSON.createObjectNode()
              .put("path", targets.get().get(index).toString()).put("content", content));
          ObjectNode frame = JSON.createObjectNode();
          ObjectNode choice = frame.putArray("choices").addObject();
          ObjectNode delta = choice.putObject("delta");
          delta.put("content", "Writing file " + (index + 1) + ". ");
          ObjectNode call = delta.putArray("tool_calls").addObject();
          call.put("index", 0).put("id", "tc_write_" + index).put("type", "function");
          call.putObject("function").put("name", "write").put("arguments", arguments);
          body = "data: " + JSON.writeValueAsString(frame) + "\n\n"
              + "data: {\"usage\":{\"prompt_tokens\":" + (1200 + index * 100)
              + ",\"completion_tokens\":40},"
              + "\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
              + "data: [DONE]\n\n";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    return server;
  }

  private static HttpServer scriptedFragmentedMultiToolProvider(
      AtomicReference<Path> target, List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try (exchange) {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        requests.add(request.deepCopy());
        int toolResults = 0;
        for (JsonNode message : request.path("messages")) {
          if ("tool".equals(message.path("role").asText())) toolResults++;
        }
        String body;
        if (toolResults >= 2) {
          body = "data: {\"choices\":[{\"delta\":{\"content\":\"Both fragments done.\"}}]}\n\n"
              + "data: {\"usage\":{\"prompt_tokens\":1450,\"completion_tokens\":12},"
              + "\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
              + "data: [DONE]\n\n";
        } else {
          String first = JSON.writeValueAsString(JSON.createObjectNode()
              .put("path", target.get().toString()).put("content", "first fragment\n"));
          String second = JSON.writeValueAsString(JSON.createObjectNode()
              .put("path", target.get().toString()).put("content", "second fragment\n"));
          int firstCut = first.length() / 2;
          int secondCut = second.length() / 2;
          body = toolCallDelta(0, "tc_fragment_0", "write", first.substring(0, firstCut))
              + toolCallDelta(0, "", "", first.substring(firstCut))
              + toolCallDelta(1, "tc_fragment_1", "write", second.substring(0, secondCut))
              + toolCallDelta(1, "", "", second.substring(secondCut))
              + "data: {\"usage\":{\"prompt_tokens\":1350,\"completion_tokens\":60},"
              + "\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
              + "data: [DONE]\n\n";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    return server;
  }

  private static HttpServer scriptedWeakModelSalvageProvider(
      AtomicReference<Path> target, List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try (exchange) {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        requests.add(request.deepCopy());
        boolean continuation = false;
        for (JsonNode message : request.path("messages")) {
          if ("tool".equals(message.path("role").asText())) continuation = true;
        }
        String body;
        if (continuation) {
          body = "data: {\"choices\":[{\"delta\":{\"content\":\"Salvage done.\"}}]}\n\n"
              + "data: {\"usage\":{\"prompt_tokens\":1250,\"completion_tokens\":8},"
              + "\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
              + "data: [DONE]\n\n";
        } else {
          String leaked = JSON.writeValueAsString(JSON.createObjectNode()
              .put("name", "write").set("arguments", JSON.createObjectNode()
                  .put("path", target.get().toString()).put("content", "salvaged tool call\n")));
          int firstCut = leaked.length() / 3;
          int secondCut = leaked.length() * 2 / 3;
          body = contentDelta(leaked.substring(0, firstCut))
              + contentDelta(leaked.substring(firstCut, secondCut))
              + contentDelta(leaked.substring(secondCut))
              + "data: {\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":35},"
              + "\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
              + "data: [DONE]\n\n";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    return server;
  }

  private static HttpServer retryingProvider(
      java.util.concurrent.atomic.AtomicInteger attempts, List<JsonNode> requests)
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try (exchange) {
        requests.add(JSON.readTree(exchange.getRequestBody()).deepCopy());
        if (attempts.incrementAndGet() == 1) {
          byte[] bytes = "{\"error\":{\"message\":\"slow down\","
              .concat("\"type\":\"rate_limit\"}}")
              .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("Retry-After", "1");
          exchange.sendResponseHeaders(429, bytes.length);
          exchange.getResponseBody().write(bytes);
          return;
        }
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"Recovered.\"}}]}\n\n"
            + "data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":2},"
            + "\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    return server;
  }

  private static HttpServer terminalErrorProvider(List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try (exchange) {
        requests.add(JSON.readTree(exchange.getRequestBody()).deepCopy());
        byte[] bytes = "{\"error\":{\"message\":\"invalid request\","
            .concat("\"type\":\"invalid_request_error\"}}")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(400, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    return server;
  }

  private static String contentDelta(String content) throws IOException {
    ObjectNode frame = JSON.createObjectNode();
    frame.putArray("choices").addObject().putObject("delta").put("content", content);
    return "data: " + JSON.writeValueAsString(frame) + "\n\n";
  }

  private static String toolCallDelta(
      int index, String id, String name, String arguments) throws IOException {
    ObjectNode frame = JSON.createObjectNode();
    ObjectNode call = frame.putArray("choices").addObject().putObject("delta")
        .putArray("tool_calls").addObject().put("index", index);
    if (!id.isEmpty()) call.put("id", id).put("type", "function");
    ObjectNode function = call.putObject("function");
    if (!name.isEmpty()) function.put("name", name);
    function.put("arguments", arguments);
    return "data: " + JSON.writeValueAsString(frame) + "\n\n";
  }

  private static HostedH2Server hostedProvider(List<HostedCapture> captures) throws Exception {
    return new HostedH2Server((request, response, callback) -> {
      captures.add(hostedCapture(request));
      String body = "data: {\"choices\":[{\"delta\":{\"content\":\"Hosted reply.\"}}]}\n\n"
          + "data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":2},"
          + "\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
          + "data: [DONE]\n\n";
      response.setStatus(200);
      response.getHeaders().put("content-type", "text/event-stream");
      response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
    });
  }

  private static HostedH2Server hostedAnthropicProvider(List<HostedCapture> captures)
      throws Exception {
    return new HostedH2Server((request, response, callback) -> {
      captures.add(anthropicHostedCapture(request));
      String body = anthropicEvent("message_start", """
          {"type":"message_start","message":{"id":"msg_parity","type":"message",
           "role":"assistant","content":[],"model":"parity-model","stop_reason":null,
           "stop_sequence":null,"usage":{"input_tokens":123,"output_tokens":1,
           "cache_creation_input_tokens":2,"cache_read_input_tokens":3}}}
          """)
          + anthropicEvent("ping", "{\"type\":\"ping\"}")
          + anthropicEvent("content_block_start", """
              {"type":"content_block_start","index":0,
               "content_block":{"type":"text","text":""}}
              """)
          + anthropicEvent("content_block_delta", """
              {"type":"content_block_delta","index":0,
               "delta":{"type":"text_delta","text":"Anthropic parity "}}
              """)
          + anthropicEvent("content_block_delta", """
              {"type":"content_block_delta","index":0,
               "delta":{"type":"text_delta","text":"reply."}}
              """)
          + anthropicEvent("content_block_stop",
              "{\"type\":\"content_block_stop\",\"index\":0}")
          + anthropicEvent("message_delta", """
              {"type":"message_delta","delta":{"stop_reason":"end_turn",
               "stop_sequence":null},"usage":{"output_tokens":7}}
              """)
          + anthropicEvent("message_stop", "{\"type\":\"message_stop\"}");
      response.setStatus(200);
      response.getHeaders().put("content-type", "text/event-stream");
      writeFragmented(response, body.getBytes(StandardCharsets.UTF_8), 7, callback);
    });
  }

  private static HostedH2Server hostedAnthropicToolProvider(
      AtomicReference<Path> target, List<HostedCapture> captures) throws Exception {
    return new HostedH2Server((request, response, callback) -> {
      HostedCapture capture = anthropicHostedCapture(request);
      captures.add(capture);
      boolean continuation = false;
      for (JsonNode message : capture.body().path("messages")) {
        for (JsonNode block : message.path("content")) {
          if ("tool_result".equals(block.path("type").asText())) continuation = true;
        }
      }
      String body;
      if (continuation) {
        body = anthropicMessageStart(250, 5, 6)
            + anthropicEvent("content_block_start", """
                {"type":"content_block_start","index":0,
                 "content_block":{"type":"text","text":""}}
                """)
            + anthropicEvent("content_block_delta", """
                {"type":"content_block_delta","index":0,
                 "delta":{"type":"text_delta","text":"Anthropic tool complete."}}
                """)
            + anthropicEvent("content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}")
            + anthropicMessageDelta("end_turn", 8)
            + anthropicEvent("message_stop", "{\"type\":\"message_stop\"}");
      } else {
        String arguments = JSON.writeValueAsString(JSON.createObjectNode()
            .put("file_path", target.get().toString())
            .put("content", "written by anthropic\n"));
        int split = arguments.length() / 2;
        body = anthropicMessageStart(200, 10, 20)
            + anthropicEvent("content_block_start", """
                {"type":"content_block_start","index":0,
                 "content_block":{"type":"text","text":""}}
                """)
            + anthropicEvent("content_block_delta", """
                {"type":"content_block_delta","index":0,
                 "delta":{"type":"text_delta","text":"Writing with Anthropic."}}
                """)
            + anthropicEvent("content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}")
            + anthropicEvent("content_block_start", """
                {"type":"content_block_start","index":1,
                 "content_block":{"type":"tool_use","id":"toolu_write_0",
                 "name":"write","input":{}}}
                """)
            + anthropicInputDelta(1, arguments.substring(0, split))
            + anthropicInputDelta(1, arguments.substring(split))
            + anthropicEvent("content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":1}")
            + anthropicMessageDelta("tool_use", 12)
            + anthropicEvent("message_stop", "{\"type\":\"message_stop\"}");
      }
      response.setStatus(200);
      response.getHeaders().put("content-type", "text/event-stream");
      writeFragmented(response, body.getBytes(StandardCharsets.UTF_8), 5, callback);
    });
  }

  private static HostedH2Server hostedAnthropicErrorProvider(List<HostedCapture> captures)
      throws Exception {
    return new HostedH2Server((request, response, callback) -> {
      captures.add(anthropicHostedCapture(request));
      byte[] body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
          .concat("\"message\":\"slow down\"}}")
          .getBytes(StandardCharsets.UTF_8);
      response.setStatus(429);
      response.getHeaders().put("content-type", "application/json");
      response.getHeaders().put("retry-after", "3");
      response.write(true, ByteBuffer.wrap(body), callback);
    });
  }

  private static String anthropicMessageStart(
      int inputTokens, int cacheCreationTokens, int cacheReadTokens) {
    ObjectNode event = JSON.createObjectNode().put("type", "message_start");
    ObjectNode message = event.putObject("message").put("id", "msg_parity")
        .put("type", "message").put("role", "assistant").put("model", "parity-model");
    message.putArray("content");
    message.putNull("stop_reason").putNull("stop_sequence");
    message.putObject("usage").put("input_tokens", inputTokens).put("output_tokens", 0)
        .put("cache_creation_input_tokens", cacheCreationTokens)
        .put("cache_read_input_tokens", cacheReadTokens);
    return anthropicEvent("message_start", event.toString());
  }

  private static String anthropicMessageDelta(String stopReason, int outputTokens) {
    ObjectNode event = JSON.createObjectNode().put("type", "message_delta");
    event.putObject("delta").put("stop_reason", stopReason).putNull("stop_sequence");
    event.putObject("usage").put("output_tokens", outputTokens);
    return anthropicEvent("message_delta", event.toString());
  }

  private static String anthropicInputDelta(int index, String partialJson) {
    ObjectNode event = JSON.createObjectNode().put("type", "content_block_delta")
        .put("index", index);
    event.putObject("delta").put("type", "input_json_delta").put("partial_json", partialJson);
    return anthropicEvent("content_block_delta", event.toString());
  }

  private static String anthropicEvent(String event, String data) {
    try {
      return "event: " + event + "\ndata: "
          + JSON.writeValueAsString(JSON.readTree(data)) + "\n\n";
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid Anthropic fixture JSON", exception);
    }
  }

  private static void writeFragmented(
      Response response, byte[] body, int fragmentBytes, Callback callback) {
    writeFragmented(response, body, fragmentBytes, 0, callback);
  }

  private static void writeFragmented(
      Response response, byte[] body, int fragmentBytes, int offset, Callback callback) {
    int length = Math.min(fragmentBytes, body.length - offset);
    boolean last = offset + length == body.length;
    response.write(last, ByteBuffer.wrap(body, offset, length), Callback.from(
        () -> {
          if (last) callback.succeeded();
          else writeFragmented(response, body, fragmentBytes, offset + length, callback);
        }, callback::failed));
  }

  private static HostedH2Server hostedStalledProvider(
      AtomicReference<CancelGate> activeGate, List<HostedCapture> captures) throws Exception {
    return new HostedH2Server((request, response, callback) -> {
      CancelGate gate = activeGate.get();
      captures.add(hostedCapture(request));
      response.setStatus(200);
      response.getHeaders().put("content-type", "text/event-stream");
      byte[] heartbeat = ": waiting\n\n".getBytes(StandardCharsets.UTF_8);
      response.write(false, ByteBuffer.wrap(heartbeat), Callback.from(
          gate.started()::countDown,
          failure -> {
            gate.started().countDown();
            gate.finished().countDown();
            callback.failed(failure);
          }));
      java.lang.Thread.startVirtualThread(() -> {
        while (!gate.release().get()) {
          java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
        }
        response.write(true, ByteBuffer.allocate(0), Callback.from(
            () -> {
              gate.finished().countDown();
              callback.succeeded();
            },
            failure -> {
              gate.finished().countDown();
              callback.failed(failure);
            }));
      });
    });
  }

  private static HostedH2Server hostedAnthropicStalledProvider(
      AtomicReference<CancelGate> activeGate, List<HostedCapture> captures) throws Exception {
    return new HostedH2Server((request, response, callback) -> {
      CancelGate gate = activeGate.get();
      captures.add(anthropicHostedCapture(request));
      response.setStatus(200);
      response.getHeaders().put("content-type", "text/event-stream");
      byte[] heartbeat = ": waiting\n\n".getBytes(StandardCharsets.UTF_8);
      response.write(false, ByteBuffer.wrap(heartbeat), Callback.from(
          gate.started()::countDown,
          failure -> {
            gate.started().countDown();
            gate.finished().countDown();
            callback.failed(failure);
          }));
      java.lang.Thread.startVirtualThread(() -> {
        while (!gate.release().get()) {
          java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
        }
        response.write(true, ByteBuffer.allocate(0), Callback.from(
            () -> {
              gate.finished().countDown();
              callback.succeeded();
            },
            failure -> {
              gate.finished().countDown();
              callback.failed(failure);
            }));
      });
    });
  }

  private static KeyStore tlsKeys() throws Exception {
    byte[] encoded = Base64.getMimeDecoder().decode(Files.readAllBytes(repositoryRoot().resolve(
        "ajent-provider/src/test/resources/insecure-test.p12.b64")));
    char[] password = "changeit".toCharArray();
    KeyStore keys = KeyStore.getInstance("PKCS12");
    keys.load(new java.io.ByteArrayInputStream(encoded), password);
    return keys;
  }

  private static HostedCapture hostedCapture(Request request) throws IOException {
    JsonNode body = JSON.readTree(Content.Source.asString(request));
    Map<String, String> headers = Map.of(
        "accept", header(request, "accept"),
        "authorization", header(request, "authorization"),
        "content-type", header(request, "content-type"),
        "host", request.getHttpURI().getAuthority(),
        "user-agent", header(request, "user-agent"));
    return new HostedCapture(request.getHttpURI().getPath(), headers, body.deepCopy());
  }

  private static HostedCapture anthropicHostedCapture(Request request) throws IOException {
    JsonNode body = JSON.readTree(Content.Source.asString(request));
    var headers = new java.util.TreeMap<String, String>();
    for (String name : List.of("accept", "anthropic-beta", "anthropic-dangerous-direct-browser-access",
        "anthropic-version", "content-type", "user-agent", "x-api-key", "x-app",
        "x-stainless-retry-count", "x-stainless-timeout")) {
      headers.put(name, header(request, name));
    }
    return new HostedCapture(request.getHttpURI().getPathQuery(), Map.copyOf(headers),
        body.deepCopy());
  }

  private static String header(Request request, String name) {
    String value = request.getHeaders().get(name);
    return value == null ? "" : value;
  }

  private static Map<String, String> hostedEnvironment(HostedH2Server server) {
    return Map.of(
        "AGENTTY_API_HOST", "127.0.0.1:" + server.getAddress().getPort(),
        "AGENTTY_INSECURE", "1");
  }

  private static HostedCapture normalizeHostedCapture(
      HostedCapture capture, Path workspace, boolean nativeAgent) {
    var headers = new java.util.TreeMap<>(capture.selectedHeaders());
    if (nativeAgent) {
      headers.replaceAll((ignored, value) -> value.replace("agentty", "ajent"));
    }
    JsonNode body = normalizeRequests(List.of(capture.body()), workspace, nativeAgent).getFirst();
    return new HostedCapture(capture.path(), Map.copyOf(headers), body);
  }

  private static void assertAnthropicCapturesMatch(
      List<HostedCapture> nativeCaptures,
      List<HostedCapture> javaCaptures,
      Path nativeWorkspace,
      Path javaWorkspace) throws Exception {
    assertThat(nativeCaptures).hasSameSizeAs(javaCaptures).isNotEmpty();
    for (int index = 0; index < nativeCaptures.size(); index++) {
      HostedCapture nativeCapture = nativeCaptures.get(index);
      HostedCapture javaCapture = javaCaptures.get(index);
      assertThat(nativeCapture.path()).isEqualTo("/v1/messages?beta=true");
      assertThat(javaCapture.path()).isEqualTo("/v1/messages?beta=true");
      assertGeneratedAnthropicUserId(nativeCapture.body());
      assertGeneratedAnthropicUserId(javaCapture.body());
      HostedCapture normalizedNative = normalizeHostedCapture(
          nativeCapture, nativeWorkspace, true);
      HostedCapture normalizedJava = normalizeHostedCapture(
          javaCapture, javaWorkspace, false);
      assertThat(normalizedNative.selectedHeaders()).isEqualTo(normalizedJava.selectedHeaders());
      assertThat(firstJsonListDifference(
          List.of(withoutSourceAheadAnthropicFields(normalizedNative.body())),
          List.of(withoutSourceAheadAnthropicFields(normalizedJava.body()))))
          .as("Anthropic request %s", index + 1)
          .isEmpty();
    }
  }

  private static HttpServer stalledProvider(
      AtomicReference<CancelGate> activeGate, List<JsonNode> requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      CancelGate gate = activeGate.get();
      try (exchange) {
        requests.add(JSON.readTree(exchange.getRequestBody()).deepCopy());
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        byte[] heartbeat = ": waiting\n\n".getBytes(StandardCharsets.UTF_8);
        while (!gate.release().get()) {
          exchange.getResponseBody().write(heartbeat);
          exchange.getResponseBody().flush();
          gate.started().countDown();
          java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
        }
      } catch (java.io.IOException ignored) {
        // Cancellation closes the response stream.
      } finally {
        gate.started().countDown();
        gate.finished().countDown();
      }
    });
    return server;
  }

  private static HttpServer concurrentProvider(
      AtomicReference<ConcurrentBarrier> activeBarrier,
      List<JsonNode> requests,
      java.util.concurrent.Executor executor) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/v1/chat/completions", exchange -> {
      ConcurrentBarrier barrier = activeBarrier.get();
      try (exchange) {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        requests.add(request.deepCopy());
        String user = latestUserText(request);
        barrier.started().countDown();
        try {
          if (!barrier.release().await(5, TimeUnit.SECONDS)) {
            throw new IOException("concurrent provider release timed out");
          }
        } catch (InterruptedException interrupted) {
          java.lang.Thread.currentThread().interrupt();
          throw new IOException("concurrent provider interrupted", interrupted);
        }
        String reply = user.contains("first") ? "first reply" : "second reply";
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"" + reply
            + "\"}}]}\n\n"
            + "data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":2},"
            + "\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      } finally {
        barrier.finished().countDown();
      }
    });
    return server;
  }

  private static String latestUserText(JsonNode request) {
    String result = "";
    for (JsonNode message : request.path("messages")) {
      if ("user".equals(message.path("role").asText())) {
        result = message.path("content").asText();
      }
    }
    return result;
  }

  private static long permissionRequestCount(Transcript transcript) {
    return transcript.exchanges().stream().flatMap(List::stream)
        .filter(frame -> "session/request_permission".equals(frame.path("method").asText()))
        .count();
  }

  private static long clientCallbackRequestCount(Transcript transcript) {
    return transcript.exchanges().stream().flatMap(List::stream)
        .filter(frame -> isClientCallback(frame.path("method").asText()))
        .count();
  }

  private static boolean isClientCallback(String method) {
    return method.startsWith("fs/") || method.startsWith("terminal/");
  }

  private static ObjectNode initializeWithClientCallbacks() {
    ObjectNode parameters = JSON.createObjectNode().put("protocolVersion", 1);
    ObjectNode capabilities = parameters.putObject("clientCapabilities");
    capabilities.putObject("fs").put("readTextFile", true).put("writeTextFile", true);
    capabilities.put("terminal", true);
    return parameters;
  }

  private record CancelGate(
      java.util.concurrent.CountDownLatch started,
      java.util.concurrent.CountDownLatch finished,
      AtomicBoolean release) {
    private CancelGate() {
      this(new java.util.concurrent.CountDownLatch(1),
          new java.util.concurrent.CountDownLatch(1), new AtomicBoolean());
    }
  }

  private record ConcurrentBarrier(
      java.util.concurrent.CountDownLatch started,
      java.util.concurrent.CountDownLatch release,
      java.util.concurrent.CountDownLatch finished) {
    private ConcurrentBarrier() {
      this(new java.util.concurrent.CountDownLatch(2),
          new java.util.concurrent.CountDownLatch(1),
          new java.util.concurrent.CountDownLatch(2));
    }
  }

  private static Transcript exercise(AgentProcess agent, Path workspace) throws Exception {
    var exchanges = new ArrayList<List<JsonNode>>();
    exchanges.add(agent.call("initialize", JSON.readTree(
        "{\"protocolVersion\":1,\"clientCapabilities\":{}}")));
    List<JsonNode> created = agent.call("session/new", JSON.createObjectNode()
        .put("cwd", workspace.toString()).set("mcpServers", JSON.createArrayNode()));
    exchanges.add(created);
    String sessionId = response(created).path("result").path("sessionId").textValue();
    exchanges.add(agent.call("session/set_mode", session(sessionId).put("modeId", "write")));
    exchanges.add(agent.call("session/set_config_option", session(sessionId)
        .put("configId", "model").put("value", "qwen3:14b")));
    exchanges.add(agent.call("session/list", JSON.createObjectNode()));
    exchanges.add(agent.call("session/list", JSON.createObjectNode().put("cwd", "C:/other")));
    exchanges.add(agent.call("session/load", session(sessionId)
        .put("cwd", workspace.toString()).set("mcpServers", JSON.createArrayNode())));
    exchanges.add(agent.call("session/resume", session(sessionId)
        .put("cwd", workspace.toString()).set("mcpServers", JSON.createArrayNode())));
    exchanges.add(agent.call("session/close", session(sessionId)));
    exchanges.add(agent.call("session/delete", session(sessionId)));
    exchanges.add(agent.call("session/list", JSON.createObjectNode()));
    exchanges.add(agent.call("logout", JSON.createObjectNode()));
    exchanges.add(agent.call("does/not/exist", JSON.createObjectNode()));
    return new Transcript(sessionId, List.copyOf(exchanges));
  }

  private static ObjectNode session(String sessionId) {
    return JSON.createObjectNode().put("sessionId", sessionId);
  }

  private static JsonNode response(List<JsonNode> exchange) {
    return exchange.stream().filter(frame -> frame.has("id")).findFirst().orElseThrow();
  }

  private static Transcript normalize(Transcript transcript, boolean nativeProgram) {
    List<List<JsonNode>> exchanges = transcript.exchanges().stream()
        .map(exchange -> exchange.stream()
            .map(frame -> normalize(frame, transcript.sessionId(), nativeProgram))
            .toList())
        .toList();
    return new Transcript("<SESSION>", exchanges);
  }

  private static Transcript normalizePrompt(
      Transcript transcript, Path workspace, boolean nativeProgram) {
    Transcript normalized = normalize(transcript, nativeProgram);
    List<List<JsonNode>> exchanges = normalized.exchanges().stream()
        .map(exchange -> exchange.stream()
            .map(frame -> normalizePromptFrame(frame, workspace)).toList())
        .map(NativeAcpParityIT::mergeAdjacentAgentMessageChunks)
        .toList();
    return new Transcript(normalized.sessionId(), exchanges);
  }

  private static List<JsonNode> mergeAdjacentAgentMessageChunks(List<JsonNode> frames) {
    var merged = new ArrayList<JsonNode>();
    for (JsonNode frame : frames) {
      if (!merged.isEmpty() && isAgentMessageChunk(merged.getLast())
          && isAgentMessageChunk(frame) && merged.getLast().path("params").path("update")
              .path("messageId").equals(
                  frame.path("params").path("update").path("messageId"))) {
        ObjectNode previous = merged.removeLast().deepCopy();
        ObjectNode content = (ObjectNode) previous.path("params").path("update").path("content");
        content.put("text", content.path("text").asText()
            + frame.path("params").path("update").path("content").path("text").asText());
        merged.add(previous);
      } else {
        merged.add(frame);
      }
    }
    return List.copyOf(merged);
  }

  private static boolean isAgentMessageChunk(JsonNode frame) {
    return "session/update".equals(frame.path("method").asText())
        && "agent_message_chunk".equals(
            frame.path("params").path("update").path("sessionUpdate").asText());
  }

  private static List<JsonNode> normalizeRequests(
      List<JsonNode> requests, Path workspace, boolean nativeProgram) {
    String workspaceText = workspace.toString();
    String jsonEncodedWorkspace;
    try {
      String quoted = JSON.writeValueAsString(workspaceText);
      jsonEncodedWorkspace = quoted.substring(1, quoted.length() - 1);
    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
      throw new AssertionError("cannot JSON-encode workspace path", impossible);
    }
    return requests.stream()
        .map(request -> normalize(request, "__NO_SESSION__", nativeProgram))
        .map(request -> replaceText(request, workspaceText, "<WORKSPACE>"))
        .map(request -> replaceText(request, jsonEncodedWorkspace, "<WORKSPACE>"))
        .map(NativeAcpParityIT::normalizeGeneratedProviderMetadata)
        .toList();
  }

  private static JsonNode normalizeGeneratedProviderMetadata(JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      value.properties().forEach(entry -> result.set(entry.getKey(),
          normalizeGeneratedProviderMetadata(entry.getValue())));
      JsonNode userId = result.path("user_id");
      if (userId.isTextual()) {
        try {
          JsonNode identity = JSON.readTree(userId.textValue());
          if (identity.isObject()
              && identity.path("device_id").asText().matches("[0-9a-f]{32}")
              && identity.path("session_id").asText().matches("[0-9a-f]{32}")) {
            ObjectNode normalized = (ObjectNode) identity;
            normalized.put("device_id", "<DEVICE>");
            normalized.put("session_id", "<SESSION>");
            result.put("user_id", JSON.writeValueAsString(normalized));
          }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
          // Non-Anthropic user_id values remain exact.
        }
      }
      return result;
    }
    if (value.isArray()) {
      var result = JSON.createArrayNode();
      value.forEach(item -> result.add(normalizeGeneratedProviderMetadata(item)));
      return result;
    }
    return value.deepCopy();
  }

  private static JsonNode withoutSourceAheadAnthropicFields(JsonNode body) {
    ObjectNode result = body.deepCopy();
    JsonNode prompt = result.at("/system/0/text");
    if (prompt.isTextual()) {
      String text = prompt.textValue()
          .replaceAll("(?s)<big-codebases>\\n.*?</big-codebases>\\n\\n", "")
          .replaceAll("(?s)<in-house-languages>\\n.*?</in-house-languages>\\n\\n", "");
      ((ObjectNode) result.at("/system/0")).put("text", text);
    }
    for (JsonNode tool : result.path("tools")) {
      if ("todo".equals(tool.path("name").asText()) && tool instanceof ObjectNode object) {
        object.remove("eager_input_streaming");
      }
    }
    return result;
  }

  private static JsonNode normalizedPersistedThread(
      Path home, String sessionId, Path workspace) throws Exception {
    Path file = home.resolve(".agentty/threads").resolve(sessionId + ".json");
    assertThat(file).isRegularFile();
    ObjectNode result = (ObjectNode) JSON.readTree(file.toFile());
    assertThat(result.path("id").textValue()).isEqualTo(sessionId);
    result.put("id", "<SESSION>");
    normalizeGeneratedEpoch(result, "created_at");
    normalizeGeneratedEpoch(result, "updated_at");
    for (JsonNode message : result.path("messages")) {
      ObjectNode object = (ObjectNode) message;
      assertThat(object.path("id").textValue()).matches("[0-9a-f]{16}");
      object.put("id", "<MESSAGE>");
      normalizeGeneratedEpoch(object, "timestamp");
    }
    return replacePersistenceRoot(result, workspace);
  }

  private static JsonNode normalizedSessionIndex(
      Path home, String sessionId, Path workspace) throws Exception {
    Path file = home.resolve(".agentty/threads/acp_sessions.json");
    assertThat(file).isRegularFile();
    JsonNode raw = JSON.readTree(file.toFile());
    assertThat(raw).hasSize(1);
    JsonNode rawSession = raw.path(sessionId);
    assertThat(rawSession.isObject()).isTrue();
    ObjectNode session = rawSession.deepCopy();
    normalizeGeneratedEpoch(session, "updatedAt");
    ObjectNode result = JSON.createObjectNode();
    result.set("<SESSION>", replacePersistenceRoot(session, workspace));
    return result;
  }

  private static void normalizeGeneratedEpoch(ObjectNode value, String field) {
    assertThat(value.path(field).isIntegralNumber()).as(field).isTrue();
    assertThat(value.path(field).longValue()).as(field).isPositive();
    value.put(field, 0);
  }

  private static JsonNode replacePersistenceRoot(JsonNode value, Path workspace) {
    if (value.isTextual()) {
      return JSON.getNodeFactory().textNode(value.textValue()
          .replace(workspace.toString(), "<WORKSPACE>"));
    }
    if (value.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      value.properties().forEach(entry ->
          result.set(entry.getKey(), replacePersistenceRoot(entry.getValue(), workspace)));
      return result;
    }
    if (value.isArray()) {
      var result = JSON.createArrayNode();
      value.forEach(item -> result.add(replacePersistenceRoot(item, workspace)));
      return result;
    }
    return value.deepCopy();
  }

  private static JsonNode toolByName(JsonNode body, String name) {
    for (JsonNode tool : body.path("tools")) {
      if (name.equals(tool.path("name").asText())) return tool;
    }
    throw new AssertionError("missing provider tool " + name);
  }

  private static void assertGeneratedAnthropicUserId(JsonNode body) throws Exception {
    JsonNode identity = JSON.readTree(body.at("/metadata/user_id").textValue());
    var names = new java.util.TreeSet<String>();
    identity.fieldNames().forEachRemaining(names::add);
    assertThat(names).containsExactly("device_id", "session_id");
    assertThat(identity.path("device_id").textValue()).matches("[0-9a-f]{32}");
    assertThat(identity.path("session_id").textValue()).matches("[0-9a-f]{32}");
  }

  private static List<JsonNode> sortedNormalizedRequests(
      List<JsonNode> requests, Path workspace, boolean nativeProgram) {
    return normalizeRequests(requests, workspace, nativeProgram).stream()
        .sorted(java.util.Comparator.comparing(NativeAcpParityIT::latestUserText)
            .thenComparing(JsonNode::toString))
        .toList();
  }

  private static Map<String, List<JsonNode>> normalizeConcurrent(
      ConcurrentTranscript transcript, Path workspace, boolean nativeProgram) {
    var first = new ArrayList<JsonNode>();
    var second = new ArrayList<JsonNode>();
    for (JsonNode frame : transcript.frames()) {
      String sessionId;
      List<JsonNode> target;
      if (frame.has("id") && frame.path("id").asInt(-1) == transcript.firstPrompt()) {
        sessionId = transcript.firstSession();
        target = first;
      } else if (frame.has("id")
          && frame.path("id").asInt(-1) == transcript.secondPrompt()) {
        sessionId = transcript.secondSession();
        target = second;
      } else if (transcript.firstSession().equals(frame.at("/params/sessionId").asText())) {
        sessionId = transcript.firstSession();
        target = first;
      } else if (transcript.secondSession().equals(frame.at("/params/sessionId").asText())) {
        sessionId = transcript.secondSession();
        target = second;
      } else {
        throw new AssertionError("unowned concurrent ACP frame: " + frame);
      }
      target.add(normalizePromptFrame(
          normalize(frame, sessionId, nativeProgram), workspace));
    }
    return Map.of("first", List.copyOf(first), "second", List.copyOf(second));
  }

  private static void assertConcurrentRequestsMatchExceptNativeColdCatalogRace(
      List<JsonNode> nativeRequests, List<JsonNode> javaRequests) {
    assertThat(nativeRequests).hasSameSizeAs(javaRequests);
    List<JsonNode> nativeWithTools = nativeRequests.stream().filter(request -> request.has("tools"))
        .toList();
    List<JsonNode> nativeWithoutTools = nativeRequests.stream()
        .filter(request -> !request.has("tools")).toList();
    assertThat(nativeWithTools).as("native initialized wire catalog").singleElement();
    assertThat(nativeWithoutTools).as("native cold-cache race").singleElement();
    JsonNode nativeCatalog = nativeWithTools.getFirst().path("tools");
    assertThat(javaRequests).allSatisfy(request ->
        assertThat(request.path("tools")).isEqualTo(nativeCatalog));

    var repairedNative = new ArrayList<JsonNode>();
    for (JsonNode request : nativeRequests) {
      ObjectNode repaired = request.deepCopy();
      if (!repaired.has("tools")) repaired.set("tools", nativeCatalog.deepCopy());
      repairedNative.add(repaired);
    }
    assertThat(firstJsonListDifference(repairedNative, javaRequests)).isEmpty();
  }

  private static String firstJsonListDifference(
      List<JsonNode> actual, List<JsonNode> expected) {
    if (actual.size() != expected.size()) {
      return "provider request count: expected " + expected.size() + " but was " + actual.size();
    }
    for (int index = 0; index < actual.size(); index++) {
      String difference = jsonDifference(
          actual.get(index), expected.get(index), "providerRequest[" + index + "]");
      if (!difference.isEmpty()) return difference;
    }
    return "";
  }

  private static String firstDifference(Transcript actual, Transcript expected) {
    if (!actual.sessionId().equals(expected.sessionId())) return "sessionId";
    if (actual.exchanges().size() != expected.exchanges().size()) return "exchange count";
    for (int exchange = 0; exchange < actual.exchanges().size(); exchange++) {
      List<JsonNode> left = actual.exchanges().get(exchange);
      List<JsonNode> right = expected.exchanges().get(exchange);
      if (left.size() != right.size()) return "exchange[" + exchange + "] frame count";
      for (int frame = 0; frame < left.size(); frame++) {
        String difference = jsonDifference(left.get(frame), right.get(frame),
            "exchange[" + exchange + "][" + frame + "]");
        if (!difference.isEmpty()) return difference;
      }
    }
    return "";
  }

  private static String jsonDifference(JsonNode actual, JsonNode expected, String path) {
    if (actual.isNumber() && expected.isNumber()) {
      return actual.decimalValue().compareTo(expected.decimalValue()) == 0 ? ""
          : path + ": expected " + expected + " but was " + actual;
    }
    if (actual.isTextual() && expected.isTextual()) {
      String left = actual.textValue();
      String right = expected.textValue();
      if (left.equals(right)) return "";
      int index = 0;
      while (index < left.length() && index < right.length()
          && left.charAt(index) == right.charAt(index)) index++;
      int start = Math.max(0, index - 40);
      int leftEnd = Math.min(left.length(), index + 80);
      int rightEnd = Math.min(right.length(), index + 80);
      return path + ": text mismatch at char " + index + ", expected "
          + quoted(right.substring(start, rightEnd)) + " but was "
          + quoted(left.substring(start, leftEnd));
    }
    if (actual.isObject() && expected.isObject()) {
      var actualNames = new java.util.TreeSet<String>();
      var expectedNames = new java.util.TreeSet<String>();
      actual.fieldNames().forEachRemaining(actualNames::add);
      expected.fieldNames().forEachRemaining(expectedNames::add);
      if (!actualNames.equals(expectedNames)) {
        return path + ": expected fields " + expectedNames + " but was " + actualNames;
      }
      for (String name : actualNames) {
        String difference = jsonDifference(actual.get(name), expected.get(name), path + "." + name);
        if (!difference.isEmpty()) return difference;
      }
      return "";
    }
    if (actual.isArray() && expected.isArray()) {
      if (actual.size() != expected.size()) {
        return path + ": expected array size " + expected.size() + " but was " + actual.size();
      }
      for (int index = 0; index < actual.size(); index++) {
        String difference = jsonDifference(
            actual.get(index), expected.get(index), path + "[" + index + "]");
        if (!difference.isEmpty()) return difference;
      }
      return "";
    }
    return actual.equals(expected) ? ""
        : path + ": expected " + expected + " but was " + actual;
  }

  private static String quoted(String value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
      throw new AssertionError("cannot quote diagnostic text", impossible);
    }
  }

  private static JsonNode normalizePromptFrame(JsonNode frame, Path workspace) {
    JsonNode normalized = replaceText(frame, workspace.toString(), "<WORKSPACE>");
    if ("session/request_permission".equals(normalized.path("method").asText())) {
      ((ObjectNode) normalized).put("id", "<PERMISSION_REQUEST>");
    }
    var messageIds = new java.util.LinkedHashSet<String>();
    collectFieldValues(normalized, "messageId", messageIds);
    for (String id : messageIds) normalized = replaceText(normalized, id, "<MESSAGE>");
    return normalized;
  }

  private static void collectFieldValues(JsonNode value, String field,
                                         java.util.Set<String> result) {
    if (value.isObject()) {
      value.properties().forEach(entry -> {
        if (entry.getKey().equals(field) && entry.getValue().isTextual()) {
          result.add(entry.getValue().textValue());
        }
        collectFieldValues(entry.getValue(), field, result);
      });
    } else if (value.isArray()) {
      value.forEach(item -> collectFieldValues(item, field, result));
    }
  }

  private static JsonNode replaceText(JsonNode value, String before, String after) {
    if (value.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      value.properties().forEach(entry ->
          result.set(entry.getKey(), replaceText(entry.getValue(), before, after)));
      return result;
    }
    if (value.isArray()) {
      var result = JSON.createArrayNode();
      value.forEach(item -> result.add(replaceText(item, before, after)));
      return result;
    }
    return value.isTextual()
        ? JSON.getNodeFactory().textNode(value.textValue().replace(before, after))
        : value.deepCopy();
  }

  private static JsonNode normalize(JsonNode value, String sessionId, boolean nativeProgram) {
    if (value.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      value.properties().forEach(entry ->
          result.set(entry.getKey(), normalize(entry.getValue(), sessionId, nativeProgram)));
      return result;
    }
    if (value.isArray()) {
      var result = JSON.createArrayNode();
      value.forEach(item -> result.add(normalize(item, sessionId, nativeProgram)));
      return result;
    }
    if (value.isIntegralNumber()) return JSON.getNodeFactory().numberNode(value.longValue());
    if (!value.isTextual()) return value.deepCopy();
    String text = value.textValue().replace(sessionId, "<SESSION>");
    if (nativeProgram) {
      text = text.replace(".agentty", "<AGENT_DATA_DIRECTORY>")
          .replace("agentty", "ajent")
          .replace("<AGENT_DATA_DIRECTORY>", ".agentty");
    }
    return JSON.getNodeFactory().textNode(text);
  }

  private static List<String> command(Path executable, Path workspace) {
    return command(executable, workspace, "ollama");
  }

  private static List<String> command(Path executable, Path workspace, String provider) {
    return command(executable, workspace, provider, "qwen3:14b");
  }

  private static List<String> command(
      Path executable, Path workspace, String provider, String model) {
    return List.of(executable.toString(), "acp", "--workspace", workspace.toString(),
        "--sandbox", "off", "--provider", provider, "--model", model);
  }

  private static List<String> commandWithKey(
      Path executable, Path workspace, String provider) {
    return List.of(executable.toString(), "acp", "--workspace", workspace.toString(),
        "--sandbox", "off", "--provider", provider, "--model", "parity-model",
        "--key", "sk-hosted-parity-test");
  }

  private static List<String> javaCommand(Path jar, Path workspace) {
    return javaCommand(jar, workspace, "ollama");
  }

  private static List<String> javaCommand(Path jar, Path workspace, String provider) {
    return javaCommand(jar, workspace, provider, "qwen3:14b");
  }

  private static List<String> javaCommand(
      Path jar, Path workspace, String provider, String model) {
    return List.of(javaExecutable(), "-jar", jar.toString(), "acp", "--workspace",
        workspace.toString(), "--sandbox", "off", "--provider", provider, "--model",
        model);
  }

  private static List<String> javaCommandWithKey(Path jar, Path workspace, String provider) {
    return List.of(javaExecutable(), "-jar", jar.toString(), "acp", "--workspace",
        workspace.toString(), "--sandbox", "off", "--provider", provider, "--model",
        "parity-model", "--key", "sk-hosted-parity-test");
  }

  private static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name, "");
    if (value.isBlank()) throw new AssertionError("missing system property " + name);
    return value;
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("ajent-parity"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException("Ajent repository root not found");
  }

  private record Transcript(String sessionId, List<List<JsonNode>> exchanges) {}

  private record HostedCapture(
      String path, Map<String, String> selectedHeaders, JsonNode body) {
    private HostedCapture {
      selectedHeaders = Map.copyOf(selectedHeaders);
      body = body.deepCopy();
    }
  }

  @FunctionalInterface
  private interface HostedHandler {
    void handle(Request request, Response response, Callback callback) throws Exception;
  }

  private static final class HostedH2Server {
    private final Server server = new Server();
    private final ServerConnector connector;

    private HostedH2Server(HostedHandler hostedHandler) throws Exception {
      var tls = new SslContextFactory.Server();
      KeyStore keys = tlsKeys();
      String certificateAlias = keys.aliases().nextElement();
      tls.setKeyStore(keys);
      tls.setKeyStorePassword("changeit");
      tls.setKeyManagerPassword("changeit");
      tls.setCertAlias(certificateAlias);
      tls.setSniRequired(false);
      tls.setSNISelector((keyType, issuers, session, sniHost, certificates) -> certificateAlias);
      var configuration = new HttpConfiguration();
      configuration.setSendServerVersion(false);
      configuration.setSendDateHeader(false);
      var secureRequests = new SecureRequestCustomizer();
      secureRequests.setSniHostCheck(false);
      secureRequests.setSniRequired(false);
      configuration.addCustomizer(secureRequests);
      var h2 = new HTTP2ServerConnectionFactory(configuration);
      var alpn = new ALPNServerConnectionFactory("h2");
      var ssl = new SslConnectionFactory(tls, alpn.getProtocol());
      ssl.setEnsureSecureRequestCustomizer(false);
      connector = new ServerConnector(server, ssl, alpn, h2);
      connector.setHost("127.0.0.1");
      connector.setPort(0);
      server.addConnector(connector);
      server.setHandler(new Handler.Abstract() {
        @Override public boolean handle(
            Request request, Response response, Callback callback) throws Exception {
          hostedHandler.handle(request, response, callback);
          return true;
        }
      });
    }

    void start() throws Exception { server.start(); }

    InetSocketAddress getAddress() {
      return new InetSocketAddress(connector.getHost(), connector.getLocalPort());
    }

    void stop(int ignoredDelaySeconds) {
      try {
        server.stop();
      } catch (Exception exception) {
        throw new IllegalStateException("unable to stop HTTP/2 test server", exception);
      }
    }
  }

  private record ConcurrentTranscript(
      String firstSession,
      int firstPrompt,
      String secondSession,
      int secondPrompt,
      List<JsonNode> frames) {
    private ConcurrentTranscript {
      frames = List.copyOf(frames);
    }
  }

  private static final class AgentProcess implements AutoCloseable {
    private final Process process;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;
    private final java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
    private final java.lang.Thread stderrReader;
    private int nextId;

    private AgentProcess(Process process) {
      this.process = process;
      stdout = new BufferedReader(new InputStreamReader(
          process.getInputStream(), StandardCharsets.UTF_8));
      stdin = new BufferedWriter(new OutputStreamWriter(
          process.getOutputStream(), StandardCharsets.UTF_8));
      stderrReader = java.lang.Thread.ofVirtual().start(() -> {
        try {
          process.getErrorStream().transferTo(stderr);
        } catch (java.io.IOException ignored) {
          // The process exit closes this stream.
        }
      });
    }

    static AgentProcess start(List<String> command, Path home, boolean javaProcess)
        throws Exception {
      return start(command, home, javaProcess, Map.of());
    }

    static AgentProcess start(
        List<String> command, Path home, boolean javaProcess, Map<String, String> environment)
        throws Exception {
      Files.createDirectories(home);
      var effective = new ArrayList<>(command);
      if (javaProcess) effective.add(1, "-Duser.home=" + home);
      var builder = new ProcessBuilder(effective).redirectErrorStream(false);
      int workspaceOption = effective.indexOf("--workspace");
      if (workspaceOption >= 0) {
        builder.directory(Path.of(effective.get(workspaceOption + 1)).toFile());
      }
      builder.environment().putAll(Map.of(
          "HOME", home.toString(), "USERPROFILE", home.toString(), "APPDATA", home.toString()));
      builder.environment().putAll(environment);
      return new AgentProcess(builder.start());
    }

    List<JsonNode> call(String method, JsonNode params) throws Exception {
      int id = sendRequest(method, params);
      return readUntilResponse(id, "");
    }

    List<JsonNode> callWithPermission(String method, JsonNode params, String optionId)
        throws Exception {
      int id = sendRequest(method, params);
      return readUntilResponse(id, optionId);
    }

    int sendRequest(String method, JsonNode params) throws Exception {
      int id = ++nextId;
      ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id)
          .put("method", method);
      request.set("params", params);
      write(request);
      return id;
    }

    void sendNotification(String method, JsonNode params) throws Exception {
      ObjectNode notification = JSON.createObjectNode().put("jsonrpc", "2.0")
          .put("method", method);
      notification.set("params", params);
      write(notification);
    }

    private void write(JsonNode frame) throws Exception {
      stdin.write(JSON.writeValueAsString(frame));
      stdin.newLine();
      stdin.flush();
    }

    List<JsonNode> readUntilResponse(int id, String permissionOption) throws Exception {
      var frames = new ArrayList<JsonNode>();
      while (true) {
        String line = stdout.readLine();
        if (line == null) throw new AssertionError("ACP process exited: " + stderr());
        JsonNode frame = JSON.readTree(line);
        frames.add(frame);
        if (isClientCallback(frame.path("method").asText())) {
          throw new AssertionError("ACP application emitted unsupported client callback: "
              + frame);
        }
        if (!permissionOption.isEmpty()
            && "session/request_permission".equals(frame.path("method").asText())) {
          ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
          response.set("id", frame.path("id"));
          response.putObject("result").putObject("outcome")
              .put("outcome", "selected").put("optionId", permissionOption);
          stdin.write(JSON.writeValueAsString(response));
          stdin.newLine();
          stdin.flush();
          continue;
        }
        if (frame.path("id").asInt(-1) == id) return List.copyOf(frames);
      }
    }

    List<JsonNode> readUntilResponses(Set<Integer> responseIds) throws Exception {
      var pending = new java.util.HashSet<>(responseIds);
      var frames = new ArrayList<JsonNode>();
      while (!pending.isEmpty()) {
        String line = stdout.readLine();
        if (line == null) throw new AssertionError("ACP process exited: " + stderr());
        JsonNode frame = JSON.readTree(line);
        frames.add(frame);
        if (frame.has("id") && frame.path("id").canConvertToInt()) {
          pending.remove(frame.path("id").intValue());
        }
      }
      return List.copyOf(frames);
    }

    @Override public void close() throws Exception {
      stdin.close();
      if (!process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new AssertionError("ACP process did not exit");
      }
      stderrReader.join(Duration.ofSeconds(2));
      assertThat(process.exitValue()).as(stderr()).isZero();
    }

    private String stderr() {
      return stderr.toString(StandardCharsets.UTF_8);
    }
  }
}
