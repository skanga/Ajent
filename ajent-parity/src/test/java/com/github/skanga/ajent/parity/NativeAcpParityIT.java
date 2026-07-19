package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
    if (!value.isTextual()) return value.deepCopy();
    String text = value.textValue().replace(sessionId, "<SESSION>");
    if (nativeProgram) text = text.replace("agentty", "ajent");
    return JSON.getNodeFactory().textNode(text);
  }

  private static List<String> command(Path executable, Path workspace) {
    return List.of(executable.toString(), "acp", "--workspace", workspace.toString(),
        "--sandbox", "off", "--provider", "ollama", "--model", "qwen3:14b");
  }

  private static List<String> javaCommand(Path jar, Path workspace) {
    String executable = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    return List.of(executable, "-jar", jar.toString(), "acp", "--workspace",
        workspace.toString(), "--sandbox", "off", "--provider", "ollama", "--model",
        "qwen3:14b");
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
      Files.createDirectories(home);
      var effective = new ArrayList<>(command);
      if (javaProcess) effective.add(1, "-Duser.home=" + home);
      var builder = new ProcessBuilder(effective).redirectErrorStream(false);
      builder.environment().putAll(Map.of(
          "HOME", home.toString(), "USERPROFILE", home.toString(), "APPDATA", home.toString()));
      return new AgentProcess(builder.start());
    }

    List<JsonNode> call(String method, JsonNode params) throws Exception {
      int id = ++nextId;
      ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id)
          .put("method", method);
      request.set("params", params);
      stdin.write(JSON.writeValueAsString(request));
      stdin.newLine();
      stdin.flush();

      var frames = new ArrayList<JsonNode>();
      while (true) {
        String line = stdout.readLine();
        if (line == null) throw new AssertionError("ACP process exited: " + stderr());
        JsonNode frame = JSON.readTree(line);
        frames.add(frame);
        if (frame.path("id").asInt(-1) == id) return List.copyOf(frames);
      }
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
