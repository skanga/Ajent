package com.github.skanga.ajent.protocol.acp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.core.persistence.ThreadStore;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AcpJsonRpcServerTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void portsTheCompleteOfflineAcpV1Lifecycle(@TempDir Path dataDirectory) throws Exception {
    var ids = new AtomicInteger();
    var authenticated = new AtomicBoolean(true);
    var logoutCalls = new AtomicInteger();
    var server = new AcpJsonRpcServer(dataDirectory,
        () -> new ThreadId("session-" + ids.incrementAndGet()), Profile.ASK,
        "claude-test", authenticated::get, () -> {
          authenticated.set(false);
          logoutCalls.incrementAndGet();
        }, "0.2.8");

    JsonNode initialized = result(server, 1, "initialize", """
        {"protocolVersion":1,"clientCapabilities":{}}
        """);
    assertThat(initialized.path("protocolVersion").intValue()).isEqualTo(1);
    assertThat(initialized.path("agentInfo").path("name").textValue()).isEqualTo("ajent");
    assertThat(initialized.path("agentInfo").path("version").textValue()).isEqualTo("0.2.8");
    JsonNode capabilities = initialized.path("agentCapabilities");
    assertThat(capabilities.path("loadSession").booleanValue()).isTrue();
    assertThat(capabilities.path("promptCapabilities").path("embeddedContext").booleanValue())
        .isTrue();
    assertThat(capabilities.path("auth").path("logout").isObject()).isTrue();
    assertThat(capabilities.path("sessionCapabilities").properties())
        .extracting(java.util.Map.Entry::getKey)
        .containsExactlyInAnyOrder("list", "resume", "close", "delete");

    JsonNode created = result(server, 2, "session/new", """
        {"cwd":"C:/workspace","mcpServers":[]}
        """);
    String sessionId = created.path("sessionId").textValue();
    assertThat(sessionId).isEqualTo("session-1");
    assertModes(created.path("modes"), "ask");

    List<JsonNode> modeFrames = call(server, 3, "session/set_mode",
        "{\"sessionId\":\"session-1\",\"modeId\":\"write\"}");
    assertThat(modeFrames).hasSize(2);
    assertThat(modeFrames.get(0).path("method").textValue()).isEqualTo("session/update");
    assertThat(modeFrames.get(0).path("params").path("sessionId").textValue())
        .isEqualTo(sessionId);
    assertThat(modeFrames.get(0).path("params").path("update").path("sessionUpdate").textValue())
        .isEqualTo("current_mode_update");
    assertThat(modeFrames.get(0).path("params").path("update").path("currentModeId").textValue())
        .isEqualTo("write");
    assertThat(modeFrames.get(1).path("result").isObject()).isTrue();

    JsonNode configured = result(server, 4, "session/set_config_option", """
        {"sessionId":"session-1","configId":"model","value":"claude-other"}
        """);
    assertThat(configured.path("configOptions").isArray()).isTrue();
    assertThat(configured.path("configOptions")).isEmpty();

    JsonNode listed = result(server, 5, "session/list", "{}");
    assertThat(listed.path("sessions")).singleElement().satisfies(session -> {
      assertThat(session.path("sessionId").textValue()).isEqualTo(sessionId);
      assertThat(session.path("cwd").textValue()).isEqualTo("C:/workspace");
      assertThat(session.path("title").textValue()).isEqualTo("ACP C:/workspace");
    });
    assertThat(result(server, 6, "session/list", "{\"cwd\":\"C:/other\"}")
        .path("sessions")).isEmpty();

    JsonNode loaded = result(server, 7, "session/load", """
        {"sessionId":"session-1","cwd":"C:/workspace","mcpServers":[]}
        """);
    assertThat(loaded.isObject()).isTrue();
    assertThat(loaded).isEmpty();
    JsonNode resumed = result(server, 8, "session/resume", """
        {"sessionId":"session-1","cwd":"C:/workspace","mcpServers":[]}
        """);
    assertModes(resumed.path("modes"), "write");
    assertThat(resumed.has("configOptions")).isFalse();

    assertThat(result(server, 9, "session/close",
        "{\"sessionId\":\"session-1\"}")).isEmpty();
    assertThat(result(server, 10, "session/list", "{}").path("sessions"))
        .singleElement().satisfies(session ->
            assertThat(session.path("sessionId").textValue()).isEqualTo(sessionId));
    assertThat(result(server, 11, "session/delete",
        "{\"sessionId\":\"session-1\"}")).isEmpty();
    assertThat(result(server, 12, "session/list", "{}").path("sessions")).isEmpty();

    assertThat(result(server, 13, "authenticate", "{\"methodId\":\"agent\"}")).isEmpty();
    assertThat(result(server, 14, "logout", "{}")).isEmpty();
    assertThat(logoutCalls).hasValue(1);
    JsonNode authError = call(server, 15, "authenticate", "{\"methodId\":\"agent\"}")
        .getLast().path("error");
    assertThat(authError.path("code").intValue()).isEqualTo(-32000);
    assertThat(authError.path("message").textValue()).contains("ajent login");

    JsonNode unknown = call(server, 16, "does/not/exist", "{}").getLast().path("error");
    assertThat(unknown.path("code").intValue()).isEqualTo(-32601);
  }

  @Test void reportsParseInvalidRequestInvalidParamsAndHandlerErrors(@TempDir Path directory)
      throws Exception {
    var server = new AcpJsonRpcServer(directory, () -> new ThreadId("one"), Profile.MINIMAL,
        "model", () -> true, () -> {}, "test");

    assertThat(frame(server.handleLine("{" )).path("error").path("code").intValue())
        .isEqualTo(-32700);
    assertThat(frame(server.handleLine("[]")).path("error").path("code").intValue())
        .isEqualTo(-32600);
    assertThat(call(server, 1, "session/new", "{}").getLast()
        .path("error").path("code").intValue()).isEqualTo(-32602);
    result(server, 2, "session/new", "{\"cwd\":\"C:/w\"}");
    assertThat(call(server, 3, "session/set_config_option", """
        {"sessionId":"one","configId":"temperatuer","value":"1"}
        """).getLast().path("error").path("code").intValue()).isEqualTo(-32603);
    assertThat(call(server, 4, "session/load", """
        {"sessionId":"missing","cwd":"C:/w"}
        """).getLast().path("error").path("code").intValue()).isEqualTo(-32603);
  }

  @Test void coversNotificationsDiskReloadModesIndexFailuresAndStdio(@TempDir Path directory)
      throws Exception {
    var ids = new AtomicInteger();
    var server = new AcpJsonRpcServer(directory,
        () -> new ThreadId("edge-" + ids.incrementAndGet()), Profile.WRITE,
        "model", () -> true, () -> {}, "edge");

    assertThat(result(server, 1, "initialize", "{\"protocolVersion\":0}")
        .path("protocolVersion").intValue()).isZero();
    assertThat(server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":"
        + "\"session/cancel\",\"params\":{\"sessionId\":\"none\"}}"))
        .isEmpty();
    assertThat(server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":"
        + "\"unknown\",\"params\":{}}" )).isEmpty();
    assertThat(frame(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":9,"
        + "\"method\":\"logout\",\"params\":[]}"))
        .path("error").path("code").intValue()).isEqualTo(-32602);
    assertThat(frame(server.handleLine("{\"id\":10,\"method\":\"logout\"}"))
        .path("error").path("code").intValue()).isEqualTo(-32600);
    assertThat(frame(server.handleLine("null")).path("error").path("code").intValue())
        .isEqualTo(-32600);
    assertThat(frame(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":11,"
        + "\"method\":\"logout\"}" )).path("result")).isEmpty();

    JsonNode created = result(server, 12, "session/new", "{\"cwd\":\"\"}");
    String id = created.path("sessionId").textValue();
    assertModes(created.path("modes"), "write");
    assertThat(result(server, 13, "session/list", "{}").path("sessions")).isEmpty();
    call(server, 14, "session/set_mode",
        "{\"sessionId\":\"" + id + "\",\"modeId\":\"minimal\"}");
    call(server, 15, "session/set_mode",
        "{\"sessionId\":\"" + id + "\",\"modeId\":\"ask\"}");
    List<JsonNode> fallback = call(server, 16, "session/set_mode",
        "{\"sessionId\":\"" + id + "\",\"modeId\":\"future\"}");
    assertThat(fallback.getFirst().path("params").path("update").path("currentModeId")
        .textValue()).isEqualTo("ask");
    assertThat(call(server, 17, "session/set_mode",
        "{\"sessionId\":\"missing\",\"modeId\":\"ask\"}").getLast()
        .path("error").path("code").intValue()).isEqualTo(-32603);
    assertThat(call(server, 18, "session/list", "{\"cwd\":1}").getLast()
        .path("error").path("code").intValue()).isEqualTo(-32602);

    var persisted = new Thread(new ThreadId("persisted"), "Persisted",
        List.of(new Message(Role.USER, "history", List.of(), List.of())));
    assertThat(new ThreadStore(directory).save(persisted)).isTrue();
    assertThat(result(server, 19, "session/load", """
        {"sessionId":"persisted","cwd":"C:/loaded"}
        """)).isEmpty();
    assertModes(result(server, 20, "session/resume", """
        {"sessionId":"persisted","cwd":"C:/loaded"}
        """).path("modes"), "write");

    assertThat(result(server, 21, "session/delete",
        "{\"sessionId\":\"persisted\"}")).isEmpty();
    assertThat(result(server, 22, "session/delete",
        "{\"sessionId\":\"persisted\"}")).isEmpty();

    Path index = directory.resolve("threads/acp_sessions.json");
    java.nio.file.Files.writeString(index, "[]");
    assertThat(result(server, 23, "session/list", "{}").path("sessions")).isEmpty();
    java.nio.file.Files.writeString(index, "{");
    assertThat(result(server, 24, "session/list", "{}").path("sessions")).isEmpty();

    var input = new java.io.BufferedReader(new java.io.StringReader(
        "{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"initialize\",\"params\":{}}\n"
            + "{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{}}\n"));
    var output = new java.io.StringWriter();
    server.serve(input, new java.io.PrintWriter(output));
    assertThat(output.toString().lines()).hasSize(1);

    Path blocked = directory.resolve("blocked");
    java.nio.file.Files.writeString(blocked, "file-not-directory");
    var bestEffort = new AcpJsonRpcServer(blocked, () -> new ThreadId("unsaved"), Profile.ASK,
        "model", () -> true, () -> {}, "edge");
    assertThat(result(bestEffort, 26, "session/new", "{\"cwd\":\"C:/x\"}")
        .path("sessionId").textValue()).isEqualTo("unsaved");

    var nullFailure = new AcpJsonRpcServer(directory, () -> {
      throw new IllegalStateException();
    }, Profile.ASK, "model", () -> true, () -> {}, "edge");
    assertThat(call(nullFailure, 27, "session/new", "{\"cwd\":\"C:/x\"}").getLast()
        .path("error").path("message").textValue()).isEqualTo("IllegalStateException");
  }

  private static JsonNode result(
      AcpJsonRpcServer server, int id, String method, String parameters) throws Exception {
    JsonNode response = call(server, id, method, parameters).getLast();
    assertThat(response.has("error")).as(response.toString()).isFalse();
    assertThat(response.path("id").intValue()).isEqualTo(id);
    return response.path("result");
  }

  private static List<JsonNode> call(
      AcpJsonRpcServer server, int id, String method, String parameters) throws Exception {
    String request = "{\"jsonrpc\":\"2.0\",\"id\":" + id
        + ",\"method\":" + JSON.writeValueAsString(method)
        + ",\"params\":" + parameters.strip() + "}";
    return server.handleLine(request).stream().map(AcpJsonRpcServerTest::parse).toList();
  }

  private static JsonNode frame(List<String> frames) {
    assertThat(frames).hasSize(1);
    return parse(frames.getFirst());
  }

  private static JsonNode parse(String value) {
    try {
      return JSON.readTree(value);
    } catch (java.io.IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void assertModes(JsonNode modes, String current) {
    assertThat(modes.path("currentModeId").textValue()).isEqualTo(current);
    assertThat(modes.path("availableModes")).extracting(mode -> mode.path("id").textValue())
        .containsExactly("ask", "write", "minimal");
  }
}
