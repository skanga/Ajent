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

/** Differential standalone MCP characterization against the pinned AgenTTY executable. */
final class NativeMcpParityIT {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void handshakeCatalogAndRealWriteMatchPinnedExecutable(@TempDir Path root) throws Exception {
    Path repository = repositoryRoot();
    Path nativeBinary = Path.of(requiredProperty("agentty.binary")).toAbsolutePath().normalize();
    Path ajentJar = repository.resolve("ajent-cli/target/ajent.jar");
    Path nativeWorkspace = Files.createDirectories(root.resolve("native-workspace"));
    Path javaWorkspace = Files.createDirectories(root.resolve("java-workspace"));

    Transcript nativeTranscript;
    try (var process = McpProcess.start(command(nativeBinary, nativeWorkspace),
        root.resolve("native-home"), false)) {
      nativeTranscript = exercise(process, nativeWorkspace);
    }
    assertThat(nativeWorkspace.resolve("served.txt")).as(nativeTranscript.toString())
        .hasContent("through MCP");

    Transcript javaTranscript;
    try (var process = McpProcess.start(javaCommand(ajentJar, javaWorkspace),
        root.resolve("java-home"), true)) {
      javaTranscript = exercise(process, javaWorkspace);
    }
    assertThat(javaWorkspace.resolve("served.txt")).as(javaTranscript.toString())
        .hasContent("through MCP");

    Transcript nativeNormalized = normalize(nativeTranscript, nativeWorkspace, true);
    Transcript javaNormalized = normalize(javaTranscript, javaWorkspace, false);
    assertThat(firstDifference(nativeNormalized, javaNormalized)).isEmpty();
  }

  private static Transcript exercise(McpProcess process, Path workspace) throws Exception {
    var exchanges = new ArrayList<JsonNode>();
    exchanges.add(process.call("initialize", JSON.readTree("""
        {"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"ajent-parity","version":"1"}}
        """)));
    process.notify("notifications/initialized", JSON.createObjectNode());
    exchanges.add(process.call("ping", JSON.createObjectNode()));
    exchanges.add(process.call("tools/list", JSON.createObjectNode()));
    ObjectNode write = JSON.createObjectNode().put("name", "write");
    write.putObject("arguments").put("path", workspace.resolve("served.txt").toString())
        .put("content", "through MCP");
    exchanges.add(process.call("tools/call", write));
    exchanges.add(process.call("does/not/exist", JSON.createObjectNode()));
    return new Transcript(List.copyOf(exchanges));
  }

  private static Transcript normalize(Transcript transcript, Path workspace, boolean nativeProgram) {
    return new Transcript(transcript.frames().stream()
        .map(frame -> normalize(frame, workspace.toString(), nativeProgram)).toList());
  }

  private static JsonNode normalize(JsonNode value, String workspace, boolean nativeProgram) {
    if (value.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      value.properties().forEach(entry ->
          result.set(entry.getKey(), normalize(entry.getValue(), workspace, nativeProgram)));
      return result;
    }
    if (value.isArray()) {
      var result = JSON.createArrayNode();
      value.forEach(item -> result.add(normalize(item, workspace, nativeProgram)));
      return result;
    }
    if (value.isIntegralNumber()) return JSON.getNodeFactory().numberNode(value.longValue());
    if (!value.isTextual()) return value.deepCopy();
    String text = value.textValue().replace(workspace, "<WORKSPACE>");
    if (nativeProgram) text = text.replace("agentty", "ajent");
    return JSON.getNodeFactory().textNode(text);
  }

  private static String firstDifference(Transcript actual, Transcript expected) {
    if (actual.frames().size() != expected.frames().size()) return "frame count";
    for (int index = 0; index < actual.frames().size(); index++) {
      String difference = jsonDifference(
          actual.frames().get(index), expected.frames().get(index), "frame[" + index + "]");
      if (!difference.isEmpty()) return difference;
    }
    return "";
  }

  private static String jsonDifference(JsonNode actual, JsonNode expected, String path) {
    if (actual.isNumber() && expected.isNumber()) {
      return actual.decimalValue().compareTo(expected.decimalValue()) == 0 ? ""
          : path + ": expected " + expected + " but was " + actual;
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

  private static List<String> command(Path executable, Path workspace) {
    return List.of(executable.toString(), "mcp-serve", "--workspace", workspace.toString(),
        "--sandbox", "off", "--provider", "ollama", "--model", "qwen3:14b");
  }

  private static List<String> javaCommand(Path jar, Path workspace) {
    String executable = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    return List.of(executable, "-jar", jar.toString(), "mcp-serve", "--workspace",
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

  private record Transcript(List<JsonNode> frames) {}

  private static final class McpProcess implements AutoCloseable {
    private final Process process;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;
    private final java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
    private final java.lang.Thread stderrReader;
    private int nextId;

    private McpProcess(Process process) {
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

    static McpProcess start(List<String> command, Path home, boolean javaProcess)
        throws Exception {
      Files.createDirectories(home);
      var effective = new ArrayList<>(command);
      if (javaProcess) effective.add(1, "-Duser.home=" + home);
      var builder = new ProcessBuilder(effective).redirectErrorStream(false);
      builder.environment().putAll(Map.of(
          "HOME", home.toString(), "USERPROFILE", home.toString(), "APPDATA", home.toString()));
      return new McpProcess(builder.start());
    }

    JsonNode call(String method, JsonNode params) throws Exception {
      int id = ++nextId;
      ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id)
          .put("method", method);
      request.set("params", params);
      write(request);
      String line = stdout.readLine();
      if (line == null) throw new AssertionError("MCP process exited: " + stderr());
      JsonNode response = JSON.readTree(line);
      assertThat(response.path("id").asInt(-1)).as(response.toString()).isEqualTo(id);
      return response;
    }

    void notify(String method, JsonNode params) throws Exception {
      ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
      request.set("params", params);
      write(request);
    }

    private void write(JsonNode frame) throws Exception {
      stdin.write(JSON.writeValueAsString(frame));
      stdin.newLine();
      stdin.flush();
    }

    @Override public void close() throws Exception {
      stdin.close();
      if (!process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new AssertionError("MCP process did not exit");
      }
      stderrReader.join(Duration.ofSeconds(2));
      assertThat(process.exitValue()).as(stderr()).isZero();
    }

    private String stderr() {
      return stderr.toString(StandardCharsets.UTF_8);
    }
  }
}
