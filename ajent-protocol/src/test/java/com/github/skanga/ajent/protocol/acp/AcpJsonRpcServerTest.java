package com.github.skanga.ajent.protocol.acp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.core.persistence.ThreadLoadResult;
import com.github.skanga.ajent.core.persistence.ThreadStore;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.github.skanga.ajent.runtime.AgentLoop;
import com.github.skanga.ajent.runtime.AgentReducer;
import com.github.skanga.ajent.runtime.AgentState;
import com.github.skanga.ajent.runtime.DispatcherToolPort;
import com.github.skanga.ajent.runtime.FilePersistencePort;
import com.github.skanga.ajent.runtime.PermissionPort;
import com.github.skanga.ajent.runtime.PermissionVerdict;
import com.github.skanga.ajent.runtime.ToolCompletion;
import com.github.skanga.ajent.tools.runtime.ToolRuntimeFactory;
import com.github.skanga.ajent.tools.runtime.FileChange;
import java.nio.file.Files;
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
        {"protocolVersion":1,"clientCapabilities":{
          "fs":{"readTextFile":true,"writeTextFile":true},"terminal":true
        }}
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

    JsonNode missingMethod = call(server, 13, "authenticate", "{}")
        .getLast().path("error");
    assertThat(missingMethod.path("code").intValue()).isEqualTo(-32602);
    assertThat(missingMethod.path("message").textValue())
        .isEqualTo("missing required field: methodId");
    assertThat(result(server, 14, "authenticate", "{\"methodId\":\"agent\"}")).isEmpty();
    assertThat(result(server, 15, "logout", "{}")).isEmpty();
    assertThat(logoutCalls).hasValue(1);
    JsonNode authError = call(server, 16, "authenticate", "{\"methodId\":\"agent\"}")
        .getLast().path("error");
    assertThat(authError.path("code").intValue()).isEqualTo(-32000);
    assertThat(authError.path("message").textValue()).contains("ajent login");

    JsonNode unknown = call(server, 17, "does/not/exist", "{}").getLast().path("error");
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
    var trace = new java.util.ArrayList<String>();
    server.serve(input, new java.io.PrintWriter(output), trace::add);
    assertThat(output.toString().lines()).hasSize(1);
    assertThat(trace).hasSize(3);
    assertThat(trace).anyMatch(line -> line.startsWith("acp ← "))
        .anyMatch(line -> line.startsWith("acp → "));

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

  @Test void loadReplaysOnlyTheNewestMessagesAndBoundedFinalToolCards(@TempDir Path directory)
      throws Exception {
    var messages = new java.util.ArrayList<Message>();
    for (int index = 0; index < 205; index++) {
      messages.add(message("u" + index, Role.USER, "message-" + index, List.of()));
    }
    var tool = new ToolUse(new ToolCallId("tool-1"), new ToolName("write"),
        java.util.Map.of("path", "C:/workspace/file.txt", "line", 12),
        new ToolStatus.Failed("permission denied"));
    messages.add(message("assistant", Role.ASSISTANT, "I tried.", List.of(tool)));
    assertThat(new ThreadStore(directory).save(
        new Thread(new ThreadId("history"), "History", messages))).isTrue();
    var server = new AcpJsonRpcServer(directory, () -> new ThreadId("unused"), Profile.ASK,
        "model", () -> true, () -> {}, "test");

    List<JsonNode> frames = call(server, 1, "session/load", """
        {"sessionId":"history","cwd":"C:/workspace"}
        """);

    assertThat(frames).hasSize(203);
    assertThat(frames.getFirst().path("method").textValue()).isEqualTo("session/update");
    JsonNode firstUpdate = frames.getFirst().path("params").path("update");
    assertThat(firstUpdate.path("sessionUpdate").textValue()).isEqualTo("user_message_chunk");
    assertThat(firstUpdate.path("messageId").textValue()).isEqualTo("u6");
    assertThat(firstUpdate.path("content").path("type").textValue()).isEqualTo("text");
    assertThat(firstUpdate.path("content").path("text").textValue()).isEqualTo("message-6");
    assertThat(frames).noneMatch(frame -> "u5".equals(
        frame.path("params").path("update").path("messageId").textValue()));

    JsonNode assistant = frames.get(frames.size() - 4).path("params").path("update");
    assertThat(assistant.path("sessionUpdate").textValue()).isEqualTo("agent_message_chunk");
    assertThat(assistant.path("messageId").textValue()).isEqualTo("assistant");
    assertThat(assistant.path("content").path("text").textValue()).isEqualTo("I tried.");
    JsonNode announcement = frames.get(frames.size() - 3).path("params").path("update");
    assertThat(announcement.path("sessionUpdate").textValue()).isEqualTo("tool_call");
    assertThat(announcement.path("toolCallId").textValue()).isEqualTo("tool-1");
    assertThat(announcement.path("title").textValue()).isEqualTo("write C:/workspace/file.txt");
    assertThat(announcement.path("kind").textValue()).isEqualTo("edit");
    assertThat(announcement.path("status").textValue()).isEqualTo("pending");
    assertThat(announcement.path("content")).isEmpty();
    assertThat(announcement.path("locations")).singleElement().satisfies(location -> {
      assertThat(location.path("path").textValue()).isEqualTo("C:/workspace/file.txt");
      assertThat(location.path("line").intValue()).isEqualTo(12);
    });
    assertThat(announcement.path("rawInput").path("path").textValue())
        .isEqualTo("C:/workspace/file.txt");
    JsonNode completion = frames.get(frames.size() - 2).path("params").path("update");
    assertThat(completion.path("sessionUpdate").textValue()).isEqualTo("tool_call_update");
    assertThat(completion.path("toolCallId").textValue()).isEqualTo("tool-1");
    assertThat(completion.path("status").textValue()).isEqualTo("failed");
    assertThat(completion.path("content").get(0).path("type").textValue()).isEqualTo("content");
    assertThat(completion.path("content").get(0).path("content").path("text").textValue())
        .isEqualTo("permission denied");
    assertThat(completion.path("rawOutput").path("text").textValue())
        .isEqualTo("permission denied");
    assertThat(frames.getLast().path("result")).isEmpty();
  }

  @Test void loadReplaysAtMostOneHundredToolsPerAssistantMessage(@TempDir Path directory)
      throws Exception {
    var tools = new java.util.ArrayList<ToolUse>();
    for (int index = 0; index < 101; index++) {
      tools.add(new ToolUse(new ToolCallId("tool-" + index), new ToolName("bash"),
          java.util.Map.of("command", "command-" + index), new ToolStatus.Done("ok")));
    }
    assertThat(new ThreadStore(directory).save(new Thread(new ThreadId("many-tools"),
        "Many tools", List.of(message("assistant", Role.ASSISTANT, "", tools))))).isTrue();
    var server = new AcpJsonRpcServer(directory, () -> new ThreadId("unused"), Profile.ASK,
        "model", () -> true, () -> {}, "test");

    List<JsonNode> frames = call(server, 1, "session/load", """
        {"sessionId":"many-tools","cwd":"C:/workspace"}
        """);

    assertThat(frames).hasSize(201);
    assertThat(frames.subList(0, 200)).extracting(frame ->
        frame.path("params").path("update").path("toolCallId").textValue())
        .contains("tool-0", "tool-99")
        .doesNotContain("tool-100");
    assertThat(frames.getLast().path("result")).isEmpty();
  }

  @Test void loadReplaysNativeToolKindsTitlesLocationsAndEmptyResults(@TempDir Path directory)
      throws Exception {
    String[] names = {"read", "edit", "grep", "glob", "list_dir", "git_status",
        "git_diff", "git_log", "skill", "diagnostics", "git_commit", "find_definition",
        "search_docs", "repo_map", "web_fetch", "web_search", "todo", "task", "custom"};
    var tools = new java.util.ArrayList<ToolUse>();
    for (int index = 0; index < names.length; index++) {
      java.util.Map<String, Object> arguments = switch (names[index]) {
        case "read", "list_dir", "git_diff", "diagnostics" ->
            java.util.Map.of("path", "path-" + index);
        case "grep" -> java.util.Map.of("pattern", "needle");
        default -> java.util.Map.of();
      };
      ToolStatus status = index == names.length - 1
          ? new ToolStatus.Rejected() : new ToolStatus.Done("");
      tools.add(new ToolUse(new ToolCallId("tool-" + index), new ToolName(names[index]),
          arguments, status));
    }
    assertThat(new ThreadStore(directory).save(new Thread(new ThreadId("tool-shapes"),
        "Tool shapes", List.of(message("assistant", Role.ASSISTANT, "", tools))))).isTrue();
    var server = new AcpJsonRpcServer(directory, () -> new ThreadId("unused"), Profile.ASK,
        "model", () -> true, () -> {}, "test");

    List<JsonNode> frames = call(server, 1, "session/load", """
        {"sessionId":"tool-shapes","cwd":"C:/workspace"}
        """);
    List<JsonNode> announcements = frames.stream()
        .map(frame -> frame.path("params").path("update"))
        .filter(update -> "tool_call".equals(update.path("sessionUpdate").textValue()))
        .toList();

    assertThat(announcements).extracting(update -> update.path("kind").textValue())
        .contains("read", "edit", "search", "execute", "fetch", "think", "other");
    assertThat(announcements).extracting(update -> update.path("title").textValue())
        .contains("read path-0", "edit", "grep needle", "glob");
    assertThat(announcements.stream().filter(update ->
        "tool-4".equals(update.path("toolCallId").textValue())).findFirst().orElseThrow()
        .path("locations").get(0).path("path").textValue()).isEqualTo("path-4");
    JsonNode rejected = frames.get(frames.size() - 2).path("params").path("update");
    assertThat(rejected.path("status").textValue()).isEqualTo("failed");
    assertThat(rejected.has("content")).isFalse();
    assertThat(rejected.has("rawOutput")).isFalse();
  }

  @Test void promptRunsARealPermissionedTurnAndStreamsNativeUpdates(@TempDir Path directory)
      throws Exception {
    var providerCalls = new java.util.concurrent.atomic.AtomicInteger();
    var toolCalls = new java.util.concurrent.atomic.AtomicInteger();
    AcpJsonRpcServer.SessionFactory factory =
        (thread, profile, model, permissions, observer) -> {
      var reducer = new AgentReducer(new AgentReducer.Context(System::nanoTime,
          java.time.Instant::now, MessageId::random, call -> PermissionVerdict.PROMPT));
      return new AgentLoop(AgentState.initial(thread), reducer,
          (turn, messages, cancellation, sink) -> {
            if (providerCalls.incrementAndGet() == 1) {
              sink.accept(new StreamEvent.TextDelta("Writing the file."));
              sink.accept(new StreamEvent.ToolUseStart("write-1", "write"));
              sink.accept(new StreamEvent.ToolUseDelta(
                  "{\"path\":\"C:/workspace/file.txt\",\"content\":\"hello\"}"));
              sink.accept(new StreamEvent.ToolUseEnd());
              sink.accept(new StreamEvent.Usage(20, 0, 2, 3));
              sink.accept(new StreamEvent.Usage(0, 5, 0, 0));
              sink.accept(new StreamEvent.Finished(StopReason.TOOL_USE));
            } else {
              sink.accept(new StreamEvent.TextDelta("Done."));
              sink.accept(new StreamEvent.Usage(30, 0, 1, 2));
              sink.accept(new StreamEvent.Usage(0, 4, 0, 0));
              sink.accept(new StreamEvent.Finished(StopReason.END_TURN));
            }
          }, call -> {
            toolCalls.incrementAndGet();
            return new ToolCompletion.Success("wrote file", java.util.Optional.of(
                new FileChange("C:/workspace/file.txt", 1, 0, "", "hello")));
          }, permissions, saved -> {}, observer);
    };
    var server = new AcpJsonRpcServer(directory, () -> new ThreadId("prompt-session"),
        Profile.ASK, "model", () -> true, () -> {}, "test", factory, 200_000);
    result(server, 1, "session/new", "{\"cwd\":\"C:/workspace\"}");
    var updates = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    var permissionCalls = new java.util.concurrent.atomic.AtomicInteger();
    AcpJsonRpcServer.Client client = new AcpJsonRpcServer.Client() {
      @Override public void send(String frame) {
        updates.add(parse(frame));
      }

      @Override public java.util.concurrent.CompletionStage<PermissionPort.Decision>
          requestPermission(String sessionId, ToolUse call) {
        permissionCalls.incrementAndGet();
        assertThat(sessionId).isEqualTo("prompt-session");
        assertThat(call.name().value()).isEqualTo("write");
        return java.util.concurrent.CompletableFuture.completedFuture(
            new PermissionPort.Decision(true, false));
      }
    };

    List<String> responseFrames = server.handleLineAsync("""
        {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{
          "sessionId":"prompt-session","prompt":[
            {"type":"text","text":"please write the file"}
          ]}}
        """, client).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);

    JsonNode response = parse(responseFrames.getLast());
    assertThat(response.path("result").path("stopReason").textValue()).isEqualTo("end_turn");
    assertThat(providerCalls).hasValue(2);
    assertThat(toolCalls).hasValue(1);
    assertThat(permissionCalls).hasValue(1);
    List<JsonNode> projected = updates.stream().map(frame ->
        frame.path("params").path("update")).toList();
    assertThat(projected).extracting(update -> update.path("sessionUpdate").textValue())
        .contains("agent_message_chunk", "tool_call", "tool_call_update", "usage_update");
    assertThat(projected.stream().filter(update ->
        "tool_call".equals(update.path("sessionUpdate").textValue())).findFirst().orElseThrow()
        .has("rawInput")).isFalse();
    assertThat(projected.stream().filter(update ->
        "agent_message_chunk".equals(update.path("sessionUpdate").textValue()))
        .map(update -> update.path("content").path("text").textValue()).toList())
        .containsExactly("Writing the file.", "Done.");
    assertThat(projected.stream().filter(update ->
        "tool_call_update".equals(update.path("sessionUpdate").textValue()))
        .map(update -> update.path("status").textValue()).filter(value -> value != null).toList())
        .contains("in_progress", "completed");
    JsonNode completed = projected.stream().filter(update ->
        "completed".equals(update.path("status").textValue())).findFirst().orElseThrow();
    assertThat(completed.path("content").get(0).path("type").textValue()).isEqualTo("diff");
    assertThat(completed.path("content").get(0).path("path").textValue())
        .isEqualTo("C:/workspace/file.txt");
    assertThat(completed.path("content").get(0).path("newText").textValue())
        .isEqualTo("hello");
    assertThat(completed.path("content").get(0).has("oldText")).isFalse();
    assertThat(completed.path("rawOutput").path("text").textValue()).isEqualTo("wrote file");
    int metadataIndex = java.util.stream.IntStream.range(0, projected.size())
        .filter(index -> projected.get(index).has("title")
            && "tool_call_update".equals(
                projected.get(index).path("sessionUpdate").textValue()))
        .findFirst().orElseThrow();
    int usageIndex = java.util.stream.IntStream.range(0, projected.size())
        .filter(index -> "usage_update".equals(
            projected.get(index).path("sessionUpdate").textValue()))
        .findFirst().orElseThrow();
    assertThat(metadataIndex).isLessThan(usageIndex);
    assertThat(projected.stream().filter(update ->
        "usage_update".equals(update.path("sessionUpdate").textValue())))
        .hasSize(2).allSatisfy(update -> assertThat(update.path("size").intValue())
            .isEqualTo(200_000));
    assertThat(projected.stream().filter(update ->
        "usage_update".equals(update.path("sessionUpdate").textValue()))
        .map(update -> update.path("used").intValue())).containsExactly(5, 4);
    ThreadLoadResult persisted = new ThreadStore(directory)
        .load(directory.resolve("threads/prompt-session.json"));
    assertThat(persisted).isInstanceOfSatisfying(ThreadLoadResult.Success.class, loaded ->
        assertThat(loaded.thread()).satisfies(thread -> {
          assertThat(thread.title()).isEqualTo("please write the file ");
          assertThat(thread.messages()).hasSize(3);
        }));
  }

  @Test void pinnedIntegrationExecutesApprovedWriteAndNeverExecutesRejectedWrite(
      @TempDir Path directory) throws Exception {
    Path workspace = Files.createDirectories(directory.resolve("workspace"));
    Path target = workspace.resolve("out.txt");
    var tools = ToolRuntimeFactory.compose(
        ToolRuntimeFactory.Configuration.standalone(workspace, directory.resolve("home")));
    var completions = new AtomicInteger();
    var sawToolResult = new AtomicBoolean();
    String arguments = JSON.createObjectNode().put("path", target.toString())
        .put("content", "hello from acp\n").toString();
    AcpJsonRpcServer.SessionFactory factory = (thread, profile, model, permissions, observer) ->
        new AgentLoop(AgentState.initial(thread), new AgentReducer(new AgentReducer.Context(
            System::nanoTime, java.time.Instant::now, MessageId::random,
            call -> PermissionVerdict.PROMPT)),
            (turn, messages, cancellation, sink) -> {
              int completion = completions.getAndIncrement();
              if (completion % 2 == 0) {
                String id = "write-real-" + completion;
                sink.accept(new StreamEvent.Started());
                sink.accept(new StreamEvent.TextDelta("Writing the file."));
                sink.accept(new StreamEvent.ToolUseStart(id, "write"));
                sink.accept(new StreamEvent.ToolUseDelta(arguments));
                sink.accept(new StreamEvent.ToolUseEnd());
                sink.accept(new StreamEvent.Usage(1_200, 40, 0, 0));
                sink.accept(new StreamEvent.Finished(StopReason.TOOL_USE));
              } else {
                String expected = "write-real-" + (completion - 1);
                sawToolResult.set(messages.stream().flatMap(message -> message.toolCalls().stream())
                    .anyMatch(call -> call.id().value().equals(expected)
                        && !(call.status() instanceof ToolStatus.Pending)));
                sink.accept(new StreamEvent.TextDelta("Done."));
                sink.accept(new StreamEvent.Usage(1_300, 10, 0, 0));
                sink.accept(new StreamEvent.Finished(StopReason.END_TURN));
              }
            }, new DispatcherToolPort(tools.dispatcher()), permissions,
            new FilePersistencePort(directory), observer);
    var server = new AcpJsonRpcServer(directory, () -> new ThreadId("real-acp-session"),
        Profile.ASK, "model", () -> true, () -> {}, "test", factory, 200_000);
    result(server, 1, "session/new", "{\"cwd\":" + JSON.writeValueAsString(workspace.toString())
        + "}");
    var frames = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
    var permissionCalls = new AtomicInteger();
    var reject = new AtomicBoolean();
    AcpJsonRpcServer.Client client = new AcpJsonRpcServer.Client() {
      @Override public void send(String frame) { frames.add(parse(frame)); }

      @Override public java.util.concurrent.CompletionStage<PermissionPort.Decision>
          requestPermission(String sessionId, ToolUse call) {
        permissionCalls.incrementAndGet();
        assertThat(sessionId).isEqualTo("real-acp-session");
        assertThat(call.name().value()).isEqualTo("write");
        return java.util.concurrent.CompletableFuture.completedFuture(
            new PermissionPort.Decision(!reject.get(), false));
      }
    };

    List<String> approved = server.handleLineAsync("""
        {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{
          "sessionId":"real-acp-session","prompt":[{"type":"text","text":"write"}]}}
        """, client).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(parse(approved.getLast()).path("result").path("stopReason").textValue())
        .isEqualTo("end_turn");
    assertThat(completions).hasValue(2);
    assertThat(permissionCalls).hasValue(1);
    assertThat(sawToolResult).isTrue();
    assertThat(Files.readString(target)).contains("hello from acp");
    List<JsonNode> approvedUpdates = frames.stream()
        .map(frame -> frame.path("params").path("update")).toList();
    assertThat(approvedUpdates.stream().filter(update ->
        "agent_message_chunk".equals(update.path("sessionUpdate").asText())))
        .extracting(update -> update.path("content").path("text").asText())
        .contains("Writing the file.", "Done.");
    assertThat(approvedUpdates.stream().filter(update ->
        "usage_update".equals(update.path("sessionUpdate").asText()))).hasSize(2);
    assertThat(approvedUpdates.stream().filter(update ->
        "completed".equals(update.path("status").asText()))).hasSize(1);

    Files.delete(target);
    reject.set(true);
    int framesBeforeReject = frames.size();
    List<String> rejected = server.handleLineAsync("""
        {"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{
          "sessionId":"real-acp-session","prompt":[{"type":"text","text":"again"}]}}
        """, client).toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(parse(rejected.getLast()).path("result").path("stopReason").textValue())
        .isEqualTo("end_turn");
    assertThat(completions).hasValue(4);
    assertThat(permissionCalls).hasValue(2);
    assertThat(sawToolResult).isTrue();
    assertThat(target).doesNotExist();
    assertThat(frames.subList(framesBeforeReject, frames.size()).stream()
        .map(frame -> frame.path("params").path("update"))
        .filter(update -> "failed".equals(update.path("status").asText()))).hasSize(1);
    result(server, 4, "session/close", "{\"sessionId\":\"real-acp-session\"}");
  }

  @Test void stdioRemainsDuplexWhilePermissionAndPromptAreOutstanding(@TempDir Path directory)
      throws Exception {
    var completions = new java.util.concurrent.atomic.AtomicInteger();
    AcpJsonRpcServer.SessionFactory factory = (thread, profile, model, permissions, observer) ->
        new AgentLoop(AgentState.initial(thread), new AgentReducer(new AgentReducer.Context(
            System::nanoTime, java.time.Instant::now, MessageId::random,
            call -> PermissionVerdict.PROMPT)),
            (turn, messages, cancellation, sink) -> {
              if (completions.incrementAndGet() == 1) {
                sink.accept(new StreamEvent.ToolUseStart("write-stdio", "write"));
                sink.accept(new StreamEvent.ToolUseDelta(
                    "{\"path\":\"C:/workspace/file.txt\",\"content\":\"hello\"}"));
                sink.accept(new StreamEvent.ToolUseEnd());
                sink.accept(new StreamEvent.Finished(StopReason.TOOL_USE));
              } else {
                sink.accept(new StreamEvent.TextDelta("finished"));
                sink.accept(new StreamEvent.Finished(StopReason.END_TURN));
              }
            }, call -> new ToolCompletion.Success("written"), permissions, saved -> {}, observer);
    var server = new AcpJsonRpcServer(directory, () -> new ThreadId("stdio-session"),
        Profile.ASK, "model", () -> true, () -> {}, "test", factory, 200_000);
    var clientWriter = new java.io.PipedWriter();
    var serverReader = new java.io.PipedReader(clientWriter);
    var input = new java.io.BufferedReader(serverReader);
    var responseLines = new java.util.concurrent.LinkedBlockingQueue<String>();
    var output = new java.io.PrintWriter(new LineWriter(responseLines), true);
    var requests = new java.io.PrintWriter(clientWriter, true);
    var served = new java.util.concurrent.CompletableFuture<Void>();
    java.lang.Thread.startVirtualThread(() -> {
      try {
        server.serve(input, output);
        served.complete(null);
      } catch (Exception exception) {
        served.completeExceptionally(exception);
      }
    });

    requests.println("""
        {"jsonrpc":"2.0","id":1,"method":"session/new","params":{"cwd":"C:/workspace"}}
        """.strip());
    assertThat(parse(readLine(responseLines)).path("result").path("sessionId").textValue())
        .isEqualTo("stdio-session");
    requests.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/prompt\","
        + "\"params\":{\"sessionId\":\"stdio-session\",\"prompt\":["
        + "{\"type\":\"text\",\"text\":\"write\"}]}}");

    JsonNode permission = null;
    JsonNode promptResult = null;
    while (promptResult == null) {
      JsonNode frame = parse(readLine(responseLines));
      if ("session/request_permission".equals(frame.path("method").asText())) {
        permission = frame;
        requests.println("{\"jsonrpc\":\"2.0\",\"id\":"
            + JSON.writeValueAsString(frame.path("id").textValue())
            + ",\"result\":{\"outcome\":{\"outcome\":\"selected\","
            + "\"optionId\":\"allow_once\"}}}");
      } else if (frame.path("id").asInt() == 2) {
        promptResult = frame;
      }
    }

    assertThat(permission).isNotNull();
    assertThat(permission.path("params").path("toolCall").path("toolCallId").textValue())
        .isEqualTo("write-stdio");
    assertThat(permission.path("params").path("options"))
        .extracting(option -> option.path("optionId").textValue())
        .containsExactly("allow_once", "allow_always", "reject_once");
    assertThat(promptResult.path("result").path("stopReason").textValue())
        .isEqualTo("end_turn");
    clientWriter.close();
    served.get(5, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test void cancelOwnsOnlyItsSessionWhileAnotherPromptCompletes(@TempDir Path directory)
      throws Exception {
    var blockedStarted = new java.util.concurrent.CountDownLatch(1);
    var ids = new java.util.concurrent.atomic.AtomicInteger();
    AcpJsonRpcServer.SessionFactory factory =
        (thread, profile, model, permissions, observer) ->
            new AgentLoop(AgentState.initial(thread), new AgentReducer(new AgentReducer.Context(
                System::nanoTime, java.time.Instant::now, MessageId::random,
                call -> PermissionVerdict.ALLOW)),
                (turn, messages, cancellation, sink) -> {
                  if ("session-1".equals(thread.id().value())) {
                    blockedStarted.countDown();
                    while (!cancellation.isCancelled()) {
                      java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    }
                    sink.accept(new StreamEvent.Error("cancelled"));
                  } else {
                    sink.accept(new StreamEvent.TextDelta("independent"));
                    sink.accept(new StreamEvent.Finished(StopReason.END_TURN));
                  }
                }, call -> new ToolCompletion.Success("unused"), permissions,
                saved -> {}, observer);
    var server = new AcpJsonRpcServer(directory,
        () -> new ThreadId("session-" + ids.incrementAndGet()), Profile.ASK, "model",
        () -> true, () -> {}, "test", factory, 200_000);
    result(server, 1, "session/new", "{\"cwd\":\"C:/one\"}");
    result(server, 2, "session/new", "{\"cwd\":\"C:/two\"}");

    var first = server.handleLineAsync("""
        {"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{
          "sessionId":"session-1","prompt":[{"type":"text","text":"wait"}]}}
        """, rejectingClient()).toCompletableFuture();
    assertThat(blockedStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    JsonNode duplicate = parse(server.handleLineAsync("""
        {"jsonrpc":"2.0","id":30,"method":"session/prompt","params":{
          "sessionId":"session-1","prompt":[{"type":"text","text":"again"}]}}
        """, rejectingClient()).toCompletableFuture()
        .get(5, java.util.concurrent.TimeUnit.SECONDS).getLast());
    assertThat(duplicate.path("error").path("code").intValue()).isEqualTo(-32602);
    List<String> second = server.handleLineAsync("""
        {"jsonrpc":"2.0","id":4,"method":"session/prompt","params":{
          "sessionId":"session-2","prompt":[{"type":"text","text":"finish"}]}}
        """, rejectingClient()).toCompletableFuture()
        .get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(parse(second.getLast()).path("result").path("stopReason").textValue())
        .isEqualTo("end_turn");

    result(server, 5, "session/cancel", "{\"sessionId\":\"session-1\"}");
    assertThat(parse(first.get(5, java.util.concurrent.TimeUnit.SECONDS).getLast())
        .path("result").path("stopReason").textValue()).isEqualTo("cancelled");
    assertThat(parse(first.get().getLast()).at("/result/_meta/error").textValue())
        .isEqualTo("cancelled");
    assertThat(parse(second.getLast()).path("result").path("stopReason").textValue())
        .isEqualTo("end_turn");
    assertThat(result(server, 6, "session/cancel",
        "{\"sessionId\":\"does-not-exist\"}")).isEmpty();
  }

  @Test void promptMapsModelModeMaxTokensRefusalAuthAndInvalidSession(@TempDir Path directory)
      throws Exception {
    var ids = new java.util.concurrent.atomic.AtomicInteger();
    var selections = new java.util.concurrent.CopyOnWriteArrayList<String>();
    AcpJsonRpcServer.SessionFactory factory =
        (thread, profile, model, permissions, observer) -> {
          selections.add(profile + ":" + model);
          return new AgentLoop(AgentState.initial(thread), new AgentReducer(
              new AgentReducer.Context(System::nanoTime, java.time.Instant::now,
                  MessageId::random, call -> PermissionVerdict.ALLOW)),
              (turn, messages, cancellation, sink) -> {
                if ("max-model".equals(model)) {
                  sink.accept(new StreamEvent.Finished(StopReason.MAX_TOKENS));
                } else {
                  sink.accept(new StreamEvent.Error("provider refused",
                      java.util.Optional.empty(),
                      com.github.skanga.ajent.provider.ErrorClass.TERMINAL, false));
                }
              }, call -> new ToolCompletion.Success("unused"), permissions,
              saved -> {}, observer);
        };
    var server = new AcpJsonRpcServer(directory,
        () -> new ThreadId("mapped-" + ids.incrementAndGet()), Profile.ASK, "initial",
        () -> true, () -> {}, "test", factory, 100_000);
    result(server, 1, "session/new", "{\"cwd\":\"C:/max\"}");
    result(server, 2, "session/set_mode",
        "{\"sessionId\":\"mapped-1\",\"modeId\":\"minimal\"}");
    result(server, 3, "session/set_config_option",
        "{\"sessionId\":\"mapped-1\",\"configId\":\"model\",\"value\":\"max-model\"}");
    JsonNode maximum = parse(server.handleLineAsync("""
        {"jsonrpc":"2.0","id":4,"method":"session/prompt","params":{
          "sessionId":"mapped-1","prompt":[{"type":"text","text":"long"}]}}
        """, rejectingClient()).toCompletableFuture()
        .get(5, java.util.concurrent.TimeUnit.SECONDS).getLast());
    assertThat(maximum.path("result").path("stopReason").textValue()).isEqualTo("max_tokens");
    assertThat(selections).containsExactly("MINIMAL:max-model");

    result(server, 5, "session/new", "{\"cwd\":\"C:/error\"}");
    result(server, 6, "session/set_config_option",
        "{\"sessionId\":\"mapped-2\",\"configId\":\"model\",\"value\":\"error-model\"}");
    JsonNode refusal = parse(server.handleLineAsync("""
        {"jsonrpc":"2.0","id":7,"method":"session/prompt","params":{
          "sessionId":"mapped-2","prompt":[{"type":"text","text":"fail"}]}}
        """, rejectingClient()).toCompletableFuture()
        .get(5, java.util.concurrent.TimeUnit.SECONDS).getLast());
    assertThat(refusal.path("result").path("stopReason").textValue()).isEqualTo("refusal");
    assertThat(refusal.path("result").path("_meta").path("error").textValue())
        .isEqualTo("provider refused");

    JsonNode unknown = parse(server.handleLineAsync("""
        {"jsonrpc":"2.0","id":8,"method":"session/prompt","params":{
          "sessionId":"absent","prompt":[{"type":"text","text":"x"}]}}
        """, rejectingClient()).toCompletableFuture().get().getLast());
    assertThat(unknown.path("error").path("code").intValue()).isEqualTo(-32602);
    var unauthenticated = new AcpJsonRpcServer(directory, () -> new ThreadId("auth"), Profile.ASK,
        "model", () -> false, () -> {}, "test", factory, 100_000);
    result(unauthenticated, 9, "session/new", "{\"cwd\":\"C:/auth\"}");
    JsonNode auth = parse(unauthenticated.handleLineAsync("""
        {"jsonrpc":"2.0","id":10,"method":"session/prompt","params":{
          "sessionId":"auth","prompt":[{"type":"text","text":"x"}]}}
        """, rejectingClient()).toCompletableFuture().get().getLast());
    assertThat(auth.path("error").path("code").intValue()).isEqualTo(-32000);

    var keylessLocal = new AcpJsonRpcServer(directory,
        () -> new ThreadId("local"), Profile.ASK, "local-model",
        () -> false, () -> {}, "test", factory, 100_000, () -> true);
    result(keylessLocal, 11, "session/new", "{\"cwd\":\"C:/local\"}");
    JsonNode local = parse(keylessLocal.handleLineAsync("""
        {"jsonrpc":"2.0","id":12,"method":"session/prompt","params":{
          "sessionId":"local","prompt":[{"type":"text","text":"x"}]}}
        """, rejectingClient()).toCompletableFuture()
        .get(5, java.util.concurrent.TimeUnit.SECONDS).getLast());
    assertThat(local.path("error").path("code").intValue()).isNotEqualTo(-32000);
    assertThat(call(keylessLocal, 13, "authenticate", "{\"methodId\":\"agent\"}")
        .getLast().path("error").path("code").intValue()).isEqualTo(-32000);
  }

  @Test void promptValidatesBlocksRuntimeNotificationsAndFactoryFailures(@TempDir Path directory)
      throws Exception {
    assertThatIllegalArgumentException().isThrownBy(() -> new AcpJsonRpcServer(directory,
        () -> new ThreadId("bad"), Profile.ASK, "model", () -> true, () -> {}, "test",
        (thread, profile, model, permissions, observer) -> null, -1));
    var legacy = new AcpJsonRpcServer(directory, () -> new ThreadId("legacy"), Profile.ASK,
        "model", () -> true, () -> {}, "test");
    result(legacy, 1, "session/new", "{\"cwd\":\"C:/legacy\"}");
    assertThat(call(legacy, 2, "session/prompt", """
        {"sessionId":"legacy","prompt":[{"type":"text","text":"x"}]}
        """).getLast().path("error").path("code").intValue()).isEqualTo(-32601);

    var captured = new java.util.concurrent.atomic.AtomicReference<String>();
    AcpJsonRpcServer.SessionFactory immediate =
        (thread, profile, model, permissions, observer) ->
            new AgentLoop(AgentState.initial(thread), new AgentReducer(new AgentReducer.Context(
                System::nanoTime, java.time.Instant::now, MessageId::random,
                call -> PermissionVerdict.ALLOW)),
                (turn, messages, cancellation, sink) -> {
                  captured.set(messages.reversed().stream()
                      .filter(message -> message.role() == Role.USER).findFirst().orElseThrow()
                      .text());
                  sink.accept(new StreamEvent.Finished(StopReason.END_TURN));
                }, call -> new ToolCompletion.Success("unused"), permissions,
                saved -> {}, observer);
    var server = new AcpJsonRpcServer(directory, () -> new ThreadId("blocks"), Profile.ASK,
        "model", () -> true, () -> {}, "test", immediate, 10_000);
    result(server, 3, "session/new", "{\"cwd\":\"C:/blocks\"}");
    for (String invalid : List.of(
        "{\"sessionId\":\"blocks\",\"prompt\":{}}",
        "{\"sessionId\":\"blocks\",\"prompt\":[1]}",
        "{\"sessionId\":\"blocks\",\"prompt\":[{\"type\":\"text\"}]}",
        "{\"sessionId\":\"blocks\",\"prompt\":[]}")) {
      assertThat(call(server, 4, "session/prompt", invalid).getLast()
          .path("error").path("code").intValue()).isEqualTo(-32602);
    }
    JsonNode embedded = call(server, 5, "session/prompt", """
        {"sessionId":"blocks","prompt":[
          {"type":"text","text":"inspect"},
          {"type":"resource_link","name":"guide","uri":"file:///guide.md"},
          {"type":"resource","resource":{"text":"context"}},
          {"type":"image","data":"ignored"}
        ]}
        """).getLast();
    assertThat(embedded.path("result").path("stopReason").textValue()).isEqualTo("end_turn");
    assertThat(captured.get()).isEqualTo(
        "inspect\n[resource: guide (file:///guide.md)]\ncontext\n");
    List<String> notification = server.handleLineAsync("""
        {"jsonrpc":"2.0","method":"session/prompt","params":{
          "sessionId":"blocks","prompt":[{"type":"text","text":"notify"}]}}
        """, rejectingClient()).toCompletableFuture()
        .get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(notification).isEmpty();

    AcpJsonRpcServer.SessionFactory broken =
        (thread, profile, model, permissions, observer) -> {
          throw new IllegalStateException();
        };
    var failure = new AcpJsonRpcServer(directory, () -> new ThreadId("failure"), Profile.ASK,
        "model", () -> true, () -> {}, "test", broken, 10_000);
    result(failure, 6, "session/new", "{\"cwd\":\"C:/failure\"}");
    JsonNode error = call(failure, 7, "session/prompt", """
        {"sessionId":"failure","prompt":[{"type":"text","text":"fail"}]}
        """).getLast();
    assertThat(error.path("error").path("code").intValue()).isEqualTo(-32603);
    assertThat(error.path("error").path("message").textValue())
        .isEqualTo("IllegalStateException");
  }

  private static JsonNode result(
      AcpJsonRpcServer server, int id, String method, String parameters) throws Exception {
    JsonNode response = call(server, id, method, parameters).getLast();
    assertThat(response.has("error")).as(response.toString()).isFalse();
    assertThat(response.path("id").intValue()).isEqualTo(id);
    return response.path("result");
  }

  private static AcpJsonRpcServer.Client rejectingClient() {
    return new AcpJsonRpcServer.Client() {
      @Override public void send(String ignored) {}

      @Override public java.util.concurrent.CompletionStage<PermissionPort.Decision>
          requestPermission(String sessionId, ToolUse call) {
        return java.util.concurrent.CompletableFuture.completedFuture(
            new PermissionPort.Decision(false, false));
      }
    };
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

  private static String readLine(
      java.util.concurrent.BlockingQueue<String> lines) throws Exception {
    String line = lines.poll(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(line).isNotNull();
    return line;
  }

  private static final class LineWriter extends java.io.Writer {
    private final java.util.concurrent.BlockingQueue<String> lines;
    private final StringBuilder pending = new StringBuilder();

    private LineWriter(java.util.concurrent.BlockingQueue<String> lines) {
      this.lines = lines;
    }

    @Override public synchronized void write(char[] buffer, int offset, int length) {
      for (int index = offset; index < offset + length; index++) {
        char value = buffer[index];
        if (value == '\n') {
          lines.add(pending.toString());
          pending.setLength(0);
        } else if (value != '\r') {
          pending.append(value);
        }
      }
    }

    @Override public void flush() {}

    @Override public void close() {}
  }

  private static void assertModes(JsonNode modes, String current) {
    assertThat(modes.path("currentModeId").textValue()).isEqualTo(current);
    assertThat(modes.path("availableModes")).extracting(mode -> mode.path("id").textValue())
        .containsExactly("ask", "write", "minimal");
  }

  private static Message message(
      String id, Role role, String text, List<ToolUse> toolCalls) {
    return new Message(new MessageId(id), role, text, List.of(), List.of(), "", "",
        toolCalls, java.time.Instant.EPOCH, java.util.Optional.empty(),
        java.util.Optional.empty(), false, false);
  }
}
