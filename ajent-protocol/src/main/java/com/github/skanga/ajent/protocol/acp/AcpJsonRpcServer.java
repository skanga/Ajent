package com.github.skanga.ajent.protocol.acp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.core.AgenttyDebugLog;
import com.github.skanga.ajent.core.persistence.ThreadLoadResult;
import com.github.skanga.ajent.core.persistence.ThreadStore;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import com.github.skanga.ajent.runtime.AgentLoop;
import com.github.skanga.ajent.runtime.AgentState;
import com.github.skanga.ajent.runtime.PermissionPort;
import com.github.skanga.ajent.runtime.RuntimeMessage;
import com.github.skanga.ajent.runtime.ToolCompletion;
import com.github.skanga.ajent.tools.runtime.FileChange;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Newline-delimited ACP v1 JSON-RPC lifecycle server. */
public final class AcpJsonRpcServer {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int PARSE_ERROR = -32700;
  private static final int INVALID_REQUEST = -32600;
  private static final int METHOD_NOT_FOUND = -32601;
  private static final int INVALID_PARAMS = -32602;
  private static final int INTERNAL_ERROR = -32603;
  private static final int AUTH_REQUIRED = -32000;
  private static final Client DISCONNECTED_CLIENT = new Client() {
    @Override public void send(String frame) {}

    @Override public CompletionStage<PermissionPort.Decision> requestPermission(
        String sessionId, ToolUse call) {
      return CompletableFuture.completedFuture(new PermissionPort.Decision(false, false));
    }
  };

  @FunctionalInterface
  public interface SessionFactory {
    AgentLoop create(Thread thread, Profile profile, String model, PermissionPort permissions,
                     BiConsumer<RuntimeMessage, AgentState> observer);
  }

  public interface Client {
    void send(String frame);

    CompletionStage<PermissionPort.Decision> requestPermission(
        String sessionId, ToolUse call);
  }

  private final Path threadsDirectory;
  private final Path sessionIndex;
  private final ThreadStore threads;
  private final Supplier<ThreadId> ids;
  private final Profile initialProfile;
  private final String initialModel;
  private final BooleanSupplier authenticated;
  private final BooleanSupplier promptAuthorized;
  private final Runnable logout;
  private final String version;
  private final SessionFactory sessionFactory;
  private final int contextMax;
  private final Map<String, Session> sessions = new LinkedHashMap<>();

  public AcpJsonRpcServer(
      Path dataDirectory,
      Supplier<ThreadId> ids,
      Profile initialProfile,
      String initialModel,
      BooleanSupplier authenticated,
      Runnable logout,
      String version) {
    this(dataDirectory, ids, initialProfile, initialModel, authenticated, logout, version,
        null, 0, authenticated);
  }

  public AcpJsonRpcServer(
      Path dataDirectory,
      Supplier<ThreadId> ids,
      Profile initialProfile,
      String initialModel,
      BooleanSupplier authenticated,
      Runnable logout,
      String version,
      SessionFactory sessionFactory,
      int contextMax) {
    this(dataDirectory, ids, initialProfile, initialModel, authenticated, logout, version,
        sessionFactory, contextMax, authenticated);
  }

  public AcpJsonRpcServer(
      Path dataDirectory,
      Supplier<ThreadId> ids,
      Profile initialProfile,
      String initialModel,
      BooleanSupplier authenticated,
      Runnable logout,
      String version,
      SessionFactory sessionFactory,
      int contextMax,
      BooleanSupplier promptAuthorized) {
    Path root = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath();
    threadsDirectory = root.resolve("threads");
    sessionIndex = threadsDirectory.resolve("acp_sessions.json");
    threads = new ThreadStore(root);
    this.ids = Objects.requireNonNull(ids, "ids");
    this.initialProfile = Objects.requireNonNull(initialProfile, "initialProfile");
    this.initialModel = Objects.requireNonNull(initialModel, "initialModel");
    this.authenticated = Objects.requireNonNull(authenticated, "authenticated");
    this.promptAuthorized = Objects.requireNonNull(promptAuthorized, "promptAuthorized");
    this.logout = Objects.requireNonNull(logout, "logout");
    this.version = Objects.requireNonNull(version, "version");
    this.sessionFactory = sessionFactory;
    if (contextMax < 0) throw new IllegalArgumentException("contextMax cannot be negative");
    this.contextMax = contextMax;
  }

  public List<String> handleLine(String line) {
    return handleLineAsync(line, DISCONNECTED_CLIENT).toCompletableFuture().join();
  }

  public synchronized CompletionStage<List<String>> handleLineAsync(String line, Client client) {
    Objects.requireNonNull(client, "client");
    JsonNode request;
    try {
      request = JSON.readTree(line);
    } catch (JsonProcessingException exception) {
      return completed(error(JSON.nullNode(), PARSE_ERROR, "Parse error"));
    }
    if (request == null || !request.isObject()
        || !"2.0".equals(request.path("jsonrpc").asText())
        || !request.path("method").isTextual()) {
      JsonNode id = request != null && request.isObject() && request.has("id")
          ? request.path("id") : JSON.nullNode();
      return completed(error(id, INVALID_REQUEST, "Invalid Request"));
    }

    boolean notification = !request.has("id");
    JsonNode id = notification ? JSON.nullNode() : request.path("id");
    JsonNode parameters = request.path("params");
    if (parameters.isMissingNode() || parameters.isNull()) parameters = JSON.createObjectNode();
    if (!parameters.isObject()) {
      return notification ? CompletableFuture.completedFuture(List.of())
          : completed(error(id, INVALID_PARAMS, "params must be an object"));
    }

    var frames = new ArrayList<ObjectNode>();
    try {
      if ("session/prompt".equals(request.path("method").textValue())) {
        CompletionStage<JsonNode> prompt = prompt(parameters, client);
        return prompt.handle((result, exception) -> {
          if (notification) return List.of();
          if (exception == null) return List.of(encode(success(id, result)));
          java.lang.Throwable cause = unwrap(exception);
          if (cause instanceof RpcFailure failure) {
            return List.of(encode(error(id, failure.code, failure.getMessage())));
          }
          String message = cause.getMessage() == null ? cause.getClass().getSimpleName()
              : cause.getMessage();
          return List.of(encode(error(id, INTERNAL_ERROR, message)));
        });
      }
      JsonNode result = dispatch(request.path("method").textValue(), parameters, frames);
      if (!notification) frames.add(success(id, result));
    } catch (RpcFailure failure) {
      if (!notification) frames.add(error(id, failure.code, failure.getMessage()));
    } catch (RuntimeException exception) {
      if (!notification) frames.add(error(id, INTERNAL_ERROR, safeMessage(exception)));
    }
    return CompletableFuture.completedFuture(
        frames.stream().map(AcpJsonRpcServer::encode).toList());
  }

  public void serve(BufferedReader input, PrintWriter output) throws IOException {
    serve(input, output, ignored -> { });
  }

  public void serve(BufferedReader input, PrintWriter output, Consumer<String> wireTrace)
      throws IOException {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(wireTrace, "wireTrace");
    var client = new WireClient(output, wireTrace);
    String line;
    while ((line = input.readLine()) != null) {
      wireTrace.accept("acp ← " + line);
      if (client.acceptResponse(line)) continue;
      handleLineAsync(line, client).whenComplete((frames, exception) -> {
        if (exception != null) {
          client.send(encode(error(JSON.nullNode(), INTERNAL_ERROR,
              safeThrowable(unwrap(exception)))));
        } else {
          frames.forEach(client::send);
        }
      });
    }
    client.disconnect();
    List<AgentLoop> active;
    synchronized (this) {
      active = sessions.values().stream().map(session -> session.loop)
          .filter(Objects::nonNull).filter(loop -> !(loop.state().phase() instanceof
              com.github.skanga.ajent.domain.SessionPhase.Idle)).toList();
    }
    active.forEach(loop -> loop.dispatch(new RuntimeMessage.Cancel()));
  }

  private static final class WireClient implements Client {
    private final PrintWriter output;
    private final Consumer<String> wireTrace;
    private final Object outputLock = new Object();
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<String, CompletableFuture<PermissionPort.Decision>> pending =
        new ConcurrentHashMap<>();

    private WireClient(PrintWriter output, Consumer<String> wireTrace) {
      this.output = output;
      this.wireTrace = wireTrace;
    }

    @Override public void send(String frame) {
      synchronized (outputLock) {
        wireTrace.accept("acp → " + frame);
        output.println(frame);
        output.flush();
      }
    }

    @Override public CompletionStage<PermissionPort.Decision> requestPermission(
        String sessionId, ToolUse call) {
      String id = "ajent-permission-" + requestIds.incrementAndGet();
      var result = new CompletableFuture<PermissionPort.Decision>();
      pending.put(id, result);
      ObjectNode request = JSON.createObjectNode();
      request.put("jsonrpc", "2.0");
      request.put("id", id);
      request.put("method", "session/request_permission");
      ObjectNode parameters = request.putObject("params");
      parameters.put("sessionId", sessionId);
      ObjectNode toolCall = parameters.putObject("toolCall");
      toolCall.put("toolCallId", call.id().value());
      toolCall.put("title", toolTitle(call));
      toolCall.put("kind", toolKind(call.name().value()));
      toolCall.set("rawInput", JSON.valueToTree(call.arguments()));
      toolCall.set("locations", toolLocations(call));
      ArrayNode options = parameters.putArray("options");
      permissionOption(options, "allow_once", "Allow", "allow_once");
      permissionOption(options, "allow_always", "Always allow", "allow_always");
      permissionOption(options, "reject_once", "Reject", "reject_once");
      send(encode(request));
      return result;
    }

    private boolean acceptResponse(String line) {
      JsonNode response;
      try {
        response = JSON.readTree(line);
      } catch (JsonProcessingException exception) {
        return false;
      }
      if (response == null || !response.isObject() || response.has("method")
          || !response.path("id").isTextual()) return false;
      String id = response.path("id").textValue();
      CompletableFuture<PermissionPort.Decision> waiting = pending.remove(id);
      if (waiting == null) return false;
      if (response.has("error")) {
        waiting.complete(new PermissionPort.Decision(false, false));
        return true;
      }
      JsonNode outcome = response.path("result").path("outcome");
      String option = "selected".equals(outcome.path("outcome").asText())
          ? outcome.path("optionId").asText() : "";
      waiting.complete(new PermissionPort.Decision(
          "allow_once".equals(option) || "allow_always".equals(option),
          "allow_always".equals(option)));
      return true;
    }

    private void disconnect() {
      pending.values().forEach(future ->
          future.complete(new PermissionPort.Decision(false, false)));
      pending.clear();
    }

    private static void permissionOption(
        ArrayNode options, String id, String name, String kind) {
      ObjectNode option = options.addObject();
      option.put("optionId", id);
      option.put("name", name);
      option.put("kind", kind);
    }
  }

  private JsonNode dispatch(String method, JsonNode parameters, List<ObjectNode> frames) {
    return switch (method) {
      case "initialize" -> initialize(parameters);
      case "authenticate" -> authenticate(parameters);
      case "logout" -> logout();
      case "session/new" -> newSession(parameters);
      case "session/load" -> loadSession(parameters, frames);
      case "session/resume" -> resumeSession(parameters, frames);
      case "session/list" -> listSessions(parameters);
      case "session/close" -> closeSession(parameters);
      case "session/delete" -> deleteSession(parameters);
      case "session/set_mode" -> setMode(parameters, frames);
      case "session/set_config_option" -> setConfigOption(parameters);
      case "session/cancel" -> cancelSession(parameters);
      default -> throw new RpcFailure(METHOD_NOT_FOUND, "Method not found: " + method);
    };
  }

  private JsonNode initialize(JsonNode parameters) {
    int offered = parameters.path("protocolVersion").asInt(1);
    ObjectNode result = JSON.createObjectNode();
    result.put("protocolVersion", offered >= 1 ? 1 : offered);
    ObjectNode capabilities = result.putObject("agentCapabilities");
    capabilities.put("loadSession", true);
    ObjectNode prompt = capabilities.putObject("promptCapabilities");
    prompt.put("image", false);
    prompt.put("audio", false);
    prompt.put("embeddedContext", true);
    ObjectNode mcp = capabilities.putObject("mcpCapabilities");
    mcp.put("http", false);
    mcp.put("sse", false);
    capabilities.putObject("auth").putObject("logout");
    ObjectNode session = capabilities.putObject("sessionCapabilities");
    session.putObject("delete");
    session.putObject("resume");
    session.putObject("close");
    session.putObject("list");
    ObjectNode info = result.putObject("agentInfo");
    info.put("name", "ajent");
    info.put("version", version);
    result.putArray("authMethods");
    return result;
  }

  private JsonNode authenticate(JsonNode parameters) {
    requiredText(parameters, "methodId");
    if (!authenticated.getAsBoolean()) {
      throw new RpcFailure(AUTH_REQUIRED,
          "ajent has no credentials — run `ajent login` first");
    }
    return JSON.createObjectNode();
  }

  private JsonNode logout() {
    logout.run();
    return JSON.createObjectNode();
  }

  private JsonNode newSession(JsonNode parameters) {
    String cwd = requiredText(parameters, "cwd");
    ThreadId id = Objects.requireNonNull(ids.get(), "ids result");
    String title = cwd.isEmpty() ? "ACP session" : "ACP " + cwd;
    var thread = new Thread(id, title, List.of());
    var session = new Session(id.value(), cwd, initialProfile, initialModel, thread);
    sessions.put(id.value(), session);
    threads.save(thread);
    index(session);
    ObjectNode result = JSON.createObjectNode();
    result.put("sessionId", id.value());
    result.set("modes", modes(session.profile));
    return result;
  }

  private JsonNode loadSession(JsonNode parameters, List<ObjectNode> frames) {
    String id = requiredText(parameters, "sessionId");
    String cwd = requiredText(parameters, "cwd");
    if (id.isEmpty()) throw new IllegalStateException("session/load: missing sessionId");
    Session live = sessions.get(id);
    if (live != null) {
      if (!cwd.isEmpty()) live.cwd = cwd;
      replay(id, live.thread, frames);
      return JSON.createObjectNode();
    }
    ThreadLoadResult loaded = threads.load(threadsDirectory.resolve(id + ".json"));
    if (!(loaded instanceof ThreadLoadResult.Success success)) {
      throw new IllegalStateException("session/load: no such session: " + id);
    }
    sessions.put(id, new Session(id, cwd, initialProfile, initialModel, success.thread()));
    replay(id, success.thread(), frames);
    return JSON.createObjectNode();
  }

  private JsonNode resumeSession(JsonNode parameters, List<ObjectNode> frames) {
    loadSession(parameters, frames);
    Session session = requireSession(requiredText(parameters, "sessionId"), "session/resume");
    ObjectNode result = JSON.createObjectNode();
    result.set("modes", modes(session.profile));
    return result;
  }

  private CompletionStage<JsonNode> prompt(JsonNode parameters, Client client) {
    if (sessionFactory == null) {
      throw new RpcFailure(METHOD_NOT_FOUND, "Method not found: session/prompt");
    }
    if (!promptAuthorized.getAsBoolean()) {
      throw new RpcFailure(AUTH_REQUIRED,
          "ajent has no credentials — run `ajent login` first");
    }
    String id = requiredText(parameters, "sessionId");
    Session session = sessions.get(id);
    if (session == null) {
      throw new RpcFailure(INVALID_PARAMS, "unknown sessionId: " + id);
    }
    String text = promptText(parameters.path("prompt"));
    if (text.isEmpty()) throw new RpcFailure(INVALID_PARAMS, "prompt must contain text");
    if (session.loop != null
        && !(session.loop.state().phase() instanceof com.github.skanga.ajent.domain.SessionPhase.Idle)) {
      throw new RpcFailure(INVALID_PARAMS, "session already has an active prompt: " + id);
    }
    if (session.thread.messages().isEmpty()) {
      session.thread = new Thread(session.thread.id(), title(text), session.thread.messages(),
          session.thread.createdAt(), session.thread.updatedAt(), session.thread.compactions());
    }

    var result = new CompletableFuture<JsonNode>();
    var projection = new TurnProjection(session, client, result);
    projection.seed(session.thread);
    PermissionPort permissions = call -> requestPermission(session, call);
    try {
      session.projection = projection;
      if (session.loop == null) {
        session.loop = Objects.requireNonNull(sessionFactory.create(session.thread,
            session.profile, session.model, permissions,
            (message, state) -> observeSession(session, message, state)),
            "sessionFactory result");
      } else {
        projection.seed(session.loop.state());
      }
      session.loop.dispatch(new RuntimeMessage.Submit(text, List.of()));
    } catch (RuntimeException exception) {
      AgenttyDebugLog.log("acp.run_turn", exception);
      result.completeExceptionally(exception);
    }
    return result;
  }

  private PermissionPort.Decision requestPermission(Session session, ToolUse call) {
    TurnProjection projection;
    synchronized (this) {
      projection = session.projection;
    }
    return projection == null ? new PermissionPort.Decision(false, false)
        : projection.requestPermission(call);
  }

  private synchronized void observeSession(
      Session session, RuntimeMessage message, AgentState state) {
    if (session.projection != null) session.projection.observe(message, state);
  }

  private static String promptText(JsonNode prompt) {
    if (!prompt.isArray()) throw new RpcFailure(INVALID_PARAMS, "prompt must be an array");
    var text = new StringBuilder();
    for (JsonNode block : prompt) {
      if (!block.isObject()) throw new RpcFailure(INVALID_PARAMS, "invalid prompt block");
      String type = block.path("type").asText();
      if ("text".equals(type)) {
        if (!block.path("text").isTextual()) {
          throw new RpcFailure(INVALID_PARAMS, "text prompt block requires text");
        }
        text.append(block.path("text").textValue()).append('\n');
      } else if ("resource_link".equals(type)) {
        String name = block.path("name").asText();
        String uri = block.path("uri").asText();
        text.append("[resource: ").append(name.isEmpty() ? uri : name)
            .append(" (").append(uri).append(")]\n");
      } else if ("resource".equals(type)
          && block.path("resource").path("text").isTextual()) {
        text.append(block.path("resource").path("text").textValue()).append('\n');
      }
    }
    return text.toString();
  }

  private static String title(String text) {
    String oneLine = text.strip().replaceAll("\\s+", " ");
    return oneLine.substring(0, Math.min(80, oneLine.length()));
  }

  private static void replay(String sessionId, Thread thread, List<ObjectNode> frames) {
    int start = Math.max(0, thread.messages().size() - 200);
    for (int index = start; index < thread.messages().size(); index++) {
      Message message = thread.messages().get(index);
      if (message.role() == Role.USER) {
        if (!message.text().isEmpty()) {
          frames.add(messageChunk(sessionId, message, "user_message_chunk"));
        }
      } else if (message.role() == Role.ASSISTANT) {
        if (!message.text().isEmpty()) {
          frames.add(messageChunk(sessionId, message, "agent_message_chunk"));
        }
        int maximum = Math.min(100, message.toolCalls().size());
        for (int toolIndex = 0; toolIndex < maximum; toolIndex++) {
          ToolUse tool = message.toolCalls().get(toolIndex);
          frames.add(updateNotification(sessionId, toolAnnouncement(tool)));
          frames.add(updateNotification(sessionId, toolCompletion(tool)));
        }
      }
    }
  }

  private static ObjectNode messageChunk(String sessionId, Message message, String kind) {
    ObjectNode update = JSON.createObjectNode();
    update.put("sessionUpdate", kind);
    ObjectNode content = update.putObject("content");
    content.put("type", "text");
    content.put("text", message.text());
    update.put("messageId", message.id().value());
    return updateNotification(sessionId, update);
  }

  private static ObjectNode toolAnnouncement(ToolUse tool) {
    ObjectNode update = JSON.createObjectNode();
    update.put("sessionUpdate", "tool_call");
    update.put("toolCallId", tool.id().value());
    update.put("title", toolTitle(tool));
    update.put("kind", toolKind(tool.name().value()));
    update.put("status", "pending");
    update.putArray("content");
    update.set("locations", toolLocations(tool));
    if (!tool.arguments().isEmpty()) update.set("rawInput", JSON.valueToTree(tool.arguments()));
    return update;
  }

  private static ObjectNode toolCompletion(ToolUse tool) {
    return toolCompletion(tool, java.util.Optional.empty());
  }

  private static ObjectNode toolCompletion(
      ToolUse tool, java.util.Optional<FileChange> change) {
    ObjectNode update = JSON.createObjectNode();
    update.put("sessionUpdate", "tool_call_update");
    update.put("toolCallId", tool.id().value());
    boolean failed = tool.status() instanceof ToolStatus.Failed
        || tool.status() instanceof ToolStatus.Rejected;
    update.put("status", failed ? "failed" : "completed");
    String output = tool.status().output();
    if (change.isPresent() || !output.isEmpty()) {
      ArrayNode content = update.putArray("content");
      change.ifPresent(file -> {
        ObjectNode diff = content.addObject();
        diff.put("type", "diff");
        diff.put("path", file.path());
        diff.put("newText", file.after());
        if (!file.before().isEmpty()) diff.put("oldText", file.before());
      });
      if (!output.isEmpty()) {
        ObjectNode item = content.addObject();
        item.put("type", "content");
        ObjectNode text = item.putObject("content");
        text.put("type", "text");
        text.put("text", output);
        update.putObject("rawOutput").put("text", output);
      }
    }
    return update;
  }

  private static ObjectNode updateNotification(String sessionId, ObjectNode update) {
    ObjectNode notification = JSON.createObjectNode();
    notification.put("jsonrpc", "2.0");
    notification.put("method", "session/update");
    ObjectNode parameters = notification.putObject("params");
    parameters.put("sessionId", sessionId);
    parameters.set("update", update);
    return notification;
  }

  private static String toolTitle(ToolUse tool) {
    String name = tool.name().value();
    if ("read".equals(name) || "edit".equals(name) || "write".equals(name)) {
      String path = stringArgument(tool, "path");
      if (!path.isEmpty()) return name + " " + path;
    }
    if ("bash".equals(name)) {
      String command = stringArgument(tool, "command");
      if (!command.isEmpty()) return "bash: " + command.substring(0, Math.min(80, command.length()));
    }
    if ("grep".equals(name) || "glob".equals(name)) {
      String pattern = stringArgument(tool, "pattern");
      if (!pattern.isEmpty()) return name + " " + pattern;
    }
    return name;
  }

  private static ArrayNode toolLocations(ToolUse tool) {
    ArrayNode locations = JSON.createArrayNode();
    String name = tool.name().value();
    if (!("read".equals(name) || "edit".equals(name) || "write".equals(name)
        || "list_dir".equals(name) || "git_diff".equals(name)
        || "diagnostics".equals(name))) return locations;
    String path = stringArgument(tool, "path");
    if (path.isEmpty()) return locations;
    ObjectNode location = locations.addObject();
    location.put("path", path);
    Object line = tool.arguments().get("line");
    if (line instanceof Byte || line instanceof Short || line instanceof Integer
        || line instanceof Long || line instanceof java.math.BigInteger) {
      location.put("line", ((Number) line).longValue());
    }
    return locations;
  }

  private static String stringArgument(ToolUse tool, String name) {
    Object value = tool.arguments().get(name);
    return value instanceof String text ? text : "";
  }

  private static String toolKind(String name) {
    return switch (name) {
      case "read", "list_dir", "git_status", "git_diff", "git_log", "skill" -> "read";
      case "edit", "write" -> "edit";
      case "bash", "diagnostics", "git_commit" -> "execute";
      case "grep", "glob", "find_definition", "search_docs", "repo_map" -> "search";
      case "web_fetch", "web_search" -> "fetch";
      case "todo", "task" -> "think";
      default -> "other";
    };
  }

  private final class TurnProjection {
    private final Session session;
    private final Client client;
    private final CompletableFuture<JsonNode> result;
    private final Map<String, Class<?>> statuses = new LinkedHashMap<>();
    private ObjectNode pendingUsage;
    private SessionPhase lastPhase = new SessionPhase.Idle();

    private TurnProjection(
        Session session, Client client, CompletableFuture<JsonNode> result) {
      this.session = session;
      this.client = client;
      this.result = result;
    }

    private void seed(AgentState state) {
      lastPhase = state.phase();
      seed(state.thread());
    }

    private void seed(Thread thread) {
      thread.messages().stream().filter(message -> message.role() == Role.ASSISTANT)
          .flatMap(message -> message.toolCalls().stream()).forEach(call ->
              statuses.put(call.id().value(), call.status().getClass()));
    }

    private void observe(RuntimeMessage message, AgentState state) {
      synchronized (AcpJsonRpcServer.this) {
        if (session.projection != this || result.isDone()) return;
        session.thread = state.thread();
        SessionPhase previousPhase = lastPhase;
        projectMessage(message, state);
        projectTools(message, state);
        projectCompletion(message, state, previousPhase);
        lastPhase = state.phase();
      }
    }

    private PermissionPort.Decision requestPermission(ToolUse call) {
      client.send(encode(updateNotification(session.id, statusUpdate(call, "pending"))));
      try {
        return client.requestPermission(session.id, call).toCompletableFuture().join();
      } catch (CompletionException exception) {
        return new PermissionPort.Decision(false, false);
      }
    }

    private void projectMessage(RuntimeMessage message, AgentState state) {
      if (message instanceof RuntimeMessage.ProviderEvent(
          long ignored, StreamEvent.TextDelta delta)) {
        latestAssistant(state).ifPresent(assistant -> {
          ObjectNode update = JSON.createObjectNode();
          update.put("sessionUpdate", "agent_message_chunk");
          ObjectNode content = update.putObject("content");
          content.put("type", "text");
          content.put("text", delta.text());
          update.put("messageId", assistant.id().value());
          send(update);
        });
      } else if (message instanceof RuntimeMessage.ProviderEvent(
          long ignored, StreamEvent.Usage usage)) {
        ObjectNode update = JSON.createObjectNode();
        update.put("sessionUpdate", "usage_update");
        long used = (long) usage.inputTokens() + usage.outputTokens()
            + usage.cacheCreationInputTokens() + usage.cacheReadInputTokens();
        update.put("used", Math.max(0L, used));
        update.put("size", contextMax);
        pendingUsage = update;
      } else if (message instanceof RuntimeMessage.ProviderEvent(
          long ignored, StreamEvent.Finished ignoredFinished) && pendingUsage != null) {
        send(pendingUsage);
        pendingUsage = null;
      } else if (message instanceof RuntimeMessage.ProviderEvent(
          long ignored, StreamEvent.Error ignoredError) && pendingUsage != null) {
        send(pendingUsage);
        pendingUsage = null;
      }
    }

    private void projectTools(RuntimeMessage message, AgentState state) {
      List<ToolUse> calls = state.thread().messages().stream()
          .filter(candidate -> candidate.role() == Role.ASSISTANT)
          .flatMap(candidate -> candidate.toolCalls().stream()).toList();
      for (ToolUse call : calls) {
        Class<?> previous = statuses.get(call.id().value());
        if (previous == null) send(toolAnnouncement(call));
        if (message instanceof RuntimeMessage.ProviderEvent(
            long ignored, StreamEvent.ToolUseEnd ignoredEnd)
            && call == calls.getLast()) {
          ObjectNode metadata = JSON.createObjectNode();
          metadata.put("sessionUpdate", "tool_call_update");
          metadata.put("toolCallId", call.id().value());
          metadata.put("title", toolTitle(call));
          metadata.set("rawInput", JSON.valueToTree(call.arguments()));
          metadata.set("locations", toolLocations(call));
          send(metadata);
        }
        Class<?> current = call.status().getClass();
        if (previous != null && previous != current) {
          if (call.status() instanceof ToolStatus.Running) {
            send(statusUpdate(call, "in_progress"));
          } else if (call.status().isTerminal()) {
            java.util.Optional<FileChange> change = message instanceof RuntimeMessage.ToolCompleted done
                && done.callId().equals(call.id().value())
                && done.result() instanceof ToolCompletion.Success success
                ? success.change() : java.util.Optional.empty();
            send(toolCompletion(call, change));
          }
        }
        statuses.put(call.id().value(), current);
      }
    }

    private void projectCompletion(
        RuntimeMessage message, AgentState state, SessionPhase previousPhase) {
      if (!(state.phase() instanceof com.github.skanga.ajent.domain.SessionPhase.Idle)) return;
      String stop = null;
      String failure = "";
      if (message instanceof RuntimeMessage.Cancel) {
        if (previousPhase instanceof SessionPhase.Streaming) return;
        stop = "cancelled";
      } else if (message instanceof RuntimeMessage.ProviderEvent(
          long ignored, StreamEvent.Finished finished)) {
        stop = finished.stopReason() == StopReason.MAX_TOKENS ? "max_tokens" : "end_turn";
      } else if (message instanceof RuntimeMessage.ProviderEvent(
          long ignored, StreamEvent.Error error)) {
        stop = error.errorClass() == com.github.skanga.ajent.provider.ErrorClass.CANCELLED
            ? "cancelled" : "refusal";
        failure = error.message();
      }
      if (stop == null) return;
      ObjectNode response = JSON.createObjectNode();
      response.put("stopReason", stop);
      if (!failure.isEmpty()) response.putObject("_meta").put("error", failure);
      threads.save(state.thread());
      index(session);
      result.complete(response);
    }

    private void send(ObjectNode update) {
      client.send(encode(updateNotification(session.id, update)));
    }
  }

  private static java.util.Optional<Message> latestAssistant(AgentState state) {
    if (state.thread().messages().isEmpty()) return java.util.Optional.empty();
    Message message = state.thread().messages().getLast();
    return message.role() == Role.ASSISTANT ? java.util.Optional.of(message)
        : java.util.Optional.empty();
  }

  private static ObjectNode statusUpdate(ToolUse call, String status) {
    ObjectNode update = JSON.createObjectNode();
    update.put("sessionUpdate", "tool_call_update");
    update.put("toolCallId", call.id().value());
    update.put("status", status);
    return update;
  }

  private JsonNode listSessions(JsonNode parameters) {
    String filter = optionalText(parameters, "cwd");
    ObjectNode index = readIndex();
    for (Session session : sessions.values()) {
      if (!index.has(session.id)) index.set(session.id, indexValue(session));
    }
    ArrayNode listed = JSON.createArrayNode();
    index.properties().forEach(entry -> {
      JsonNode metadata = entry.getValue();
      String cwd = metadata.path("cwd").asText();
      if (cwd.isEmpty() || !filter.isEmpty() && !filter.equals(cwd)) return;
      ObjectNode info = listed.addObject();
      info.put("sessionId", entry.getKey());
      info.put("cwd", cwd);
      if (metadata.path("title").isTextual()) {
        info.put("title", metadata.path("title").textValue());
      }
    });
    return JSON.createObjectNode().set("sessions", listed);
  }

  private JsonNode closeSession(JsonNode parameters) {
    Session removed = sessions.remove(requiredText(parameters, "sessionId"));
    closeLoop(removed);
    return JSON.createObjectNode();
  }

  private JsonNode deleteSession(JsonNode parameters) {
    String id = requiredText(parameters, "sessionId");
    closeLoop(sessions.remove(id));
    threads.delete(new ThreadId(id));
    unindex(id);
    return JSON.createObjectNode();
  }

  private JsonNode cancelSession(JsonNode parameters) {
    Session session = sessions.get(requiredText(parameters, "sessionId"));
    if (session != null && session.loop != null) session.loop.dispatch(new RuntimeMessage.Cancel());
    return JSON.createObjectNode();
  }

  private static void closeLoop(Session session) {
    if (session == null || session.loop == null) return;
    AgentLoop loop = session.loop;
    session.loop = null;
    java.lang.Thread.startVirtualThread(() -> {
      try {
        loop.dispatch(new RuntimeMessage.Cancel());
      } catch (IllegalStateException ignored) {
        // Already closed.
      }
      loop.close();
    });
  }

  private JsonNode setMode(JsonNode parameters, List<ObjectNode> frames) {
    String id = requiredText(parameters, "sessionId");
    Session session = requireSession(id, "session/set_mode");
    Profile revised = profile(requiredText(parameters, "modeId"), session.profile);
    if (revised != session.profile) closeIdleLoop(session);
    session.profile = revised;
    ObjectNode update = JSON.createObjectNode();
    update.put("sessionUpdate", "current_mode_update");
    update.put("currentModeId", modeId(session.profile));
    frames.add(updateNotification(id, update));
    return JSON.createObjectNode();
  }

  private JsonNode setConfigOption(JsonNode parameters) {
    Session session = requireSession(
        requiredText(parameters, "sessionId"), "session/set_config_option");
    String configId = requiredText(parameters, "configId");
    if (!"model".equals(configId)) {
      throw new IllegalStateException("session/set_config_option: unknown configId '"
          + configId + "' (supported: model)");
    }
    String value = requiredText(parameters, "value");
    if (!value.equals(session.model)) closeIdleLoop(session);
    session.model = value;
    ObjectNode result = JSON.createObjectNode();
    result.putArray("configOptions");
    return result;
  }

  private static void closeIdleLoop(Session session) {
    if (session.loop == null
        || !(session.loop.state().phase() instanceof
            com.github.skanga.ajent.domain.SessionPhase.Idle)) return;
    closeLoop(session);
  }

  private Session requireSession(String id, String operation) {
    Session session = sessions.get(id);
    if (session == null) throw new IllegalStateException(operation + ": unknown sessionId: " + id);
    return session;
  }

  private void index(Session session) {
    ObjectNode index = readIndex();
    index.set(session.id, indexValue(session));
    writeIndex(index);
  }

  private static ObjectNode indexValue(Session session) {
    ObjectNode value = JSON.createObjectNode();
    value.put("cwd", session.cwd);
    value.put("title", session.thread.title());
    value.put("updatedAt", Instant.now().getEpochSecond());
    return value;
  }

  private void unindex(String id) {
    ObjectNode index = readIndex();
    if (index.remove(id) != null) writeIndex(index);
  }

  private ObjectNode readIndex() {
    if (!Files.isRegularFile(sessionIndex)) return JSON.createObjectNode();
    try {
      JsonNode value = JSON.readTree(sessionIndex.toFile());
      return value instanceof ObjectNode object ? object : JSON.createObjectNode();
    } catch (IOException | RuntimeException exception) {
      AgenttyDebugLog.log("acp.load_session_index", exception);
      return JSON.createObjectNode();
    }
  }

  private void writeIndex(ObjectNode index) {
    Path temporary = sessionIndex.resolveSibling(sessionIndex.getFileName() + ".tmp");
    try {
      Files.createDirectories(threadsDirectory);
      byte[] content = JSON.writeValueAsBytes(index);
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.move(temporary, sessionIndex,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, sessionIndex, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException | RuntimeException failure) {
      AgenttyDebugLog.log("acp.index_session", failure);
      // AgenTTY's session sidecar is best effort; thread persistence is authoritative.
    } finally {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException ignored) {
        // Best-effort cleanup of a failed atomic write.
      }
    }
  }

  private static ObjectNode modes(Profile profile) {
    ObjectNode modes = JSON.createObjectNode();
    modes.put("currentModeId", modeId(profile));
    ArrayNode available = modes.putArray("availableModes");
    mode(available, "ask", "Ask", "Prompt before edits, commands, and network access");
    mode(available, "write", "Write",
        "Edit files and run commands without prompting; still prompt for risky ops");
    mode(available, "minimal", "Minimal", "Prompt for everything, including file reads");
    return modes;
  }

  private static void mode(
      ArrayNode modes, String id, String name, String description) {
    ObjectNode mode = modes.addObject();
    mode.put("id", id);
    mode.put("name", name);
    mode.put("description", description);
  }

  private static Profile profile(String id, Profile fallback) {
    return switch (id) {
      case "ask" -> Profile.ASK;
      case "write" -> Profile.WRITE;
      case "minimal" -> Profile.MINIMAL;
      default -> fallback;
    };
  }

  private static String modeId(Profile profile) {
    return switch (profile) {
      case ASK -> "ask";
      case WRITE -> "write";
      case MINIMAL -> "minimal";
    };
  }

  private static String requiredText(JsonNode parameters, String field) {
    JsonNode value = parameters.path(field);
    if (!value.isTextual()) throw new RpcFailure(INVALID_PARAMS, "missing required field: " + field);
    return value.textValue();
  }

  private static String optionalText(JsonNode parameters, String field) {
    JsonNode value = parameters.path(field);
    if (value.isMissingNode() || value.isNull()) return "";
    if (!value.isTextual()) throw new RpcFailure(INVALID_PARAMS, "expected string: " + field);
    return value.textValue();
  }

  private static ObjectNode success(JsonNode id, JsonNode result) {
    ObjectNode response = JSON.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id.deepCopy());
    response.set("result", result);
    return response;
  }

  private static ObjectNode error(JsonNode id, int code, String message) {
    ObjectNode response = JSON.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id.deepCopy());
    ObjectNode error = response.putObject("error");
    error.put("code", code);
    error.put("message", message);
    return response;
  }

  private static String encode(JsonNode frame) {
    try {
      return JSON.writeValueAsString(frame);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to encode ACP frame", exception);
    }
  }

  private static CompletionStage<List<String>> completed(ObjectNode frame) {
    return CompletableFuture.completedFuture(List.of(encode(frame)));
  }

  private static java.lang.Throwable unwrap(java.lang.Throwable exception) {
    java.lang.Throwable current = exception;
    while ((current instanceof CompletionException
        || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) current = current.getCause();
    return current;
  }

  private static String safeMessage(RuntimeException exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }

  private static String safeThrowable(java.lang.Throwable exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }

  private static final class Session {
    private final String id;
    private String cwd;
    private Profile profile;
    private String model;
    private Thread thread;
    private AgentLoop loop;
    private TurnProjection projection;

    private Session(
        String id, String cwd, Profile profile, String model, Thread thread) {
      this.id = id;
      this.cwd = cwd;
      this.profile = profile;
      this.model = model;
      this.thread = thread;
    }
  }

  private static final class RpcFailure extends RuntimeException {
    private final int code;

    private RpcFailure(int code, String message) {
      super(message);
      this.code = code;
    }
  }
}
