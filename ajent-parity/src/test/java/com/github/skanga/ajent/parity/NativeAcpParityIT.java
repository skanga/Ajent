package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

      Transcript javaTranscript;
      target.set(javaWorkspace.resolve("out.txt"));
      try (var agent = AgentProcess.start(javaCommand(ajentJar, javaWorkspace, endpoint),
          root.resolve("java-prompt-home"), true)) {
        javaTranscript = exercisePrompt(agent, javaWorkspace);
      }
      List<JsonNode> javaRequests = List.copyOf(requests);
      assertThat(target.get()).as(javaTranscript.toString()).hasContent("hello from acp\n");

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

  private static Transcript exercisePrompt(
      AgentProcess agent, Path workspace, String permissionOption) throws Exception {
    agent.call("initialize", JSON.readTree(
        "{\"protocolVersion\":1,\"clientCapabilities\":{}}"));
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

  private static long permissionRequestCount(Transcript transcript) {
    return transcript.exchanges().stream().flatMap(List::stream)
        .filter(frame -> "session/request_permission".equals(frame.path("method").asText()))
        .count();
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
        .toList();
    return new Transcript(normalized.sessionId(), exchanges);
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
        .toList();
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
    if (nativeProgram) text = text.replace("agentty", "ajent");
    return JSON.getNodeFactory().textNode(text);
  }

  private static List<String> command(Path executable, Path workspace) {
    return command(executable, workspace, "ollama");
  }

  private static List<String> command(Path executable, Path workspace, String provider) {
    return List.of(executable.toString(), "acp", "--workspace", workspace.toString(),
        "--sandbox", "off", "--provider", provider, "--model", "qwen3:14b");
  }

  private static List<String> javaCommand(Path jar, Path workspace) {
    return javaCommand(jar, workspace, "ollama");
  }

  private static List<String> javaCommand(Path jar, Path workspace, String provider) {
    String executable = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    return List.of(executable, "-jar", jar.toString(), "acp", "--workspace",
        workspace.toString(), "--sandbox", "off", "--provider", provider, "--model",
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
      int workspaceOption = effective.indexOf("--workspace");
      if (workspaceOption >= 0) {
        builder.directory(Path.of(effective.get(workspaceOption + 1)).toFile());
      }
      builder.environment().putAll(Map.of(
          "HOME", home.toString(), "USERPROFILE", home.toString(), "APPDATA", home.toString()));
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
