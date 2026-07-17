package com.github.skanga.ajent.protocol.acp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.core.persistence.ThreadLoadResult;
import com.github.skanga.ajent.core.persistence.ThreadStore;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
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
import java.util.function.BooleanSupplier;
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

  private final Path threadsDirectory;
  private final Path sessionIndex;
  private final ThreadStore threads;
  private final Supplier<ThreadId> ids;
  private final Profile initialProfile;
  private final String initialModel;
  private final BooleanSupplier authenticated;
  private final Runnable logout;
  private final String version;
  private final Map<String, Session> sessions = new LinkedHashMap<>();

  public AcpJsonRpcServer(
      Path dataDirectory,
      Supplier<ThreadId> ids,
      Profile initialProfile,
      String initialModel,
      BooleanSupplier authenticated,
      Runnable logout,
      String version) {
    Path root = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath();
    threadsDirectory = root.resolve("threads");
    sessionIndex = threadsDirectory.resolve("acp_sessions.json");
    threads = new ThreadStore(root);
    this.ids = Objects.requireNonNull(ids, "ids");
    this.initialProfile = Objects.requireNonNull(initialProfile, "initialProfile");
    this.initialModel = Objects.requireNonNull(initialModel, "initialModel");
    this.authenticated = Objects.requireNonNull(authenticated, "authenticated");
    this.logout = Objects.requireNonNull(logout, "logout");
    this.version = Objects.requireNonNull(version, "version");
  }

  public synchronized List<String> handleLine(String line) {
    JsonNode request;
    try {
      request = JSON.readTree(line);
    } catch (JsonProcessingException exception) {
      return List.of(encode(error(JSON.nullNode(), PARSE_ERROR, "Parse error")));
    }
    if (request == null || !request.isObject()
        || !"2.0".equals(request.path("jsonrpc").asText())
        || !request.path("method").isTextual()) {
      JsonNode id = request != null && request.isObject() && request.has("id")
          ? request.path("id") : JSON.nullNode();
      return List.of(encode(error(id, INVALID_REQUEST, "Invalid Request")));
    }

    boolean notification = !request.has("id");
    JsonNode id = notification ? JSON.nullNode() : request.path("id");
    JsonNode parameters = request.path("params");
    if (parameters.isMissingNode() || parameters.isNull()) parameters = JSON.createObjectNode();
    if (!parameters.isObject()) {
      return notification ? List.of()
          : List.of(encode(error(id, INVALID_PARAMS, "params must be an object")));
    }

    var frames = new ArrayList<ObjectNode>();
    try {
      JsonNode result = dispatch(request.path("method").textValue(), parameters, frames);
      if (!notification) frames.add(success(id, result));
    } catch (RpcFailure failure) {
      if (!notification) frames.add(error(id, failure.code, failure.getMessage()));
    } catch (RuntimeException exception) {
      if (!notification) frames.add(error(id, INTERNAL_ERROR, safeMessage(exception)));
    }
    return frames.stream().map(AcpJsonRpcServer::encode).toList();
  }

  public void serve(BufferedReader input, PrintWriter output) throws IOException {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(output, "output");
    String line;
    while ((line = input.readLine()) != null) {
      for (String frame : handleLine(line)) output.println(frame);
      output.flush();
    }
  }

  private JsonNode dispatch(String method, JsonNode parameters, List<ObjectNode> frames) {
    return switch (method) {
      case "initialize" -> initialize(parameters);
      case "authenticate" -> authenticate();
      case "logout" -> logout();
      case "session/new" -> newSession(parameters);
      case "session/load" -> loadSession(parameters);
      case "session/resume" -> resumeSession(parameters);
      case "session/list" -> listSessions(parameters);
      case "session/close" -> closeSession(parameters);
      case "session/delete" -> deleteSession(parameters);
      case "session/set_mode" -> setMode(parameters, frames);
      case "session/set_config_option" -> setConfigOption(parameters);
      case "session/cancel" -> JSON.createObjectNode();
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

  private JsonNode authenticate() {
    if (!authenticated.getAsBoolean()) {
      throw new RpcFailure(AUTH_REQUIRED,
          "ajent has no credentials â€” run `ajent login` first");
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

  private JsonNode loadSession(JsonNode parameters) {
    String id = requiredText(parameters, "sessionId");
    String cwd = requiredText(parameters, "cwd");
    if (id.isEmpty()) throw new IllegalStateException("session/load: missing sessionId");
    Session live = sessions.get(id);
    if (live != null) {
      if (!cwd.isEmpty()) live.cwd = cwd;
      return JSON.createObjectNode();
    }
    ThreadLoadResult loaded = threads.load(threadsDirectory.resolve(id + ".json"));
    if (!(loaded instanceof ThreadLoadResult.Success success)) {
      throw new IllegalStateException("session/load: no such session: " + id);
    }
    sessions.put(id, new Session(id, cwd, initialProfile, initialModel, success.thread()));
    return JSON.createObjectNode();
  }

  private JsonNode resumeSession(JsonNode parameters) {
    loadSession(parameters);
    Session session = requireSession(requiredText(parameters, "sessionId"), "session/resume");
    ObjectNode result = JSON.createObjectNode();
    result.set("modes", modes(session.profile));
    return result;
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
    sessions.remove(requiredText(parameters, "sessionId"));
    return JSON.createObjectNode();
  }

  private JsonNode deleteSession(JsonNode parameters) {
    String id = requiredText(parameters, "sessionId");
    sessions.remove(id);
    threads.delete(new ThreadId(id));
    unindex(id);
    return JSON.createObjectNode();
  }

  private JsonNode setMode(JsonNode parameters, List<ObjectNode> frames) {
    String id = requiredText(parameters, "sessionId");
    Session session = requireSession(id, "session/set_mode");
    session.profile = profile(requiredText(parameters, "modeId"), session.profile);
    ObjectNode update = JSON.createObjectNode();
    update.put("sessionUpdate", "current_mode_update");
    update.put("currentModeId", modeId(session.profile));
    ObjectNode notification = JSON.createObjectNode();
    notification.put("jsonrpc", "2.0");
    notification.put("method", "session/update");
    ObjectNode payload = notification.putObject("params");
    payload.put("sessionId", id);
    payload.set("update", update);
    frames.add(notification);
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
    session.model = requiredText(parameters, "value");
    ObjectNode result = JSON.createObjectNode();
    result.putArray("configOptions");
    return result;
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
    } catch (IOException | RuntimeException ignored) {
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

  private static String safeMessage(RuntimeException exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }

  private static final class Session {
    private final String id;
    private String cwd;
    private Profile profile;
    private String model;
    private final Thread thread;

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
