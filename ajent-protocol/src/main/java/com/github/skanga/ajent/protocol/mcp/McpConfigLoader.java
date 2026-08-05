package com.github.skanga.ajent.protocol.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skanga.ajent.core.AjentDebugLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves and parses Ajent-compatible MCP configuration with its project spawn gate. */
public final class McpConfigLoader {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(15);

  public sealed interface Server permits Server.Stdio, Server.Http {
    String name();

    record Stdio(String name, String command, List<String> arguments,
                 Map<String, String> environment) implements Server {
      public Stdio {
        name = Objects.requireNonNull(name, "name");
        command = Objects.requireNonNull(command, "command");
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
      }
    }

    record Http(String name, String url, Map<String, String> headers) implements Server {
      public Http {
        name = Objects.requireNonNull(name, "name");
        url = Objects.requireNonNull(url, "url");
        headers = Map.copyOf(headers);
      }
    }
  }

  public record LoadResult(
      Optional<Path> path,
      boolean projectLocal,
      boolean permitted,
      List<Server> servers,
      Duration callTimeout,
      Duration connectTimeout,
      List<String> diagnostics) {
    public LoadResult {
      path = Objects.requireNonNull(path, "path");
      servers = List.copyOf(servers);
      callTimeout = Objects.requireNonNull(callTimeout, "callTimeout");
      connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
      diagnostics = List.copyOf(diagnostics);
    }
  }

  private record Selection(Optional<Path> path, boolean projectLocal) {}

  private McpConfigLoader() {}

  public static LoadResult load(
      Path workspace, Path home, Map<String, String> environment) {
    Objects.requireNonNull(workspace, "workspace");
    Objects.requireNonNull(home, "home");
    environment = Map.copyOf(environment);
    Duration callTimeout = timeout(environment.get("AJENT_MCP_TIMEOUT_MS"),
        DEFAULT_CALL_TIMEOUT, "mcp.call_timeout.env");
    Duration connectTimeout = timeout(environment.get("AJENT_MCP_CONNECT_TIMEOUT_MS"),
        DEFAULT_CONNECT_TIMEOUT, "mcp.connect_timeout.env");
    Selection selected = resolve(workspace, home, environment);
    if (selected.path().isEmpty()) {
      return new LoadResult(Optional.empty(), false, true, List.of(), callTimeout,
          connectTimeout, List.of());
    }
    Path path = selected.path().orElseThrow();
    if (selected.projectLocal()
        && !truthy(environment.get("AJENT_MCP_ALLOW_PROJECT"))) {
      return new LoadResult(Optional.of(path), true, false, List.of(), callTimeout,
          connectTimeout, List.of("ignoring workspace-local " + path
              + "; set AJENT_MCP_ALLOW_PROJECT=1 to enable it"));
    }

    var diagnostics = new ArrayList<String>();
    JsonNode document;
    try {
      document = JSON.readTree(path.toFile());
    } catch (IOException exception) {
      diagnostics.add("failed to parse " + path + ": " + exception.getMessage());
      return new LoadResult(Optional.of(path), selected.projectLocal(), true, List.of(),
          callTimeout, connectTimeout, diagnostics);
    }
    JsonNode entries = objectField(document, "mcpServers");
    if (entries == null) entries = objectField(document, "servers");
    if (entries == null) {
      return new LoadResult(Optional.of(path), selected.projectLocal(), true, List.of(),
          callTimeout, connectTimeout, diagnostics);
    }

    var servers = new ArrayList<Server>();
    entries.properties().forEach(entry -> parse(entry.getKey(), entry.getValue(), servers,
        diagnostics));
    return new LoadResult(Optional.of(path), selected.projectLocal(), true, servers,
        callTimeout, connectTimeout, diagnostics);
  }

  private static Selection resolve(
      Path workspace, Path home, Map<String, String> environment) {
    String explicit = environment.getOrDefault("AJENT_MCP_CONFIG", "");
    if (!explicit.isEmpty()) {
      Path path = Path.of(explicit).toAbsolutePath();
      return new Selection(Files.isRegularFile(path) ? Optional.of(path) : Optional.empty(), false);
    }
    Path project = workspace.resolve(".ajent/mcp.json").toAbsolutePath();
    if (Files.isRegularFile(project)) return new Selection(Optional.of(project), true);
    Path user = home.resolve(".ajent/mcp.json").toAbsolutePath();
    return new Selection(Files.isRegularFile(user) ? Optional.of(user) : Optional.empty(), false);
  }

  private static void parse(
      String name, JsonNode specification, List<Server> servers, List<String> diagnostics) {
    if (!specification.isObject()) {
      diagnostics.add("mcp server '" + name + "' must be an object");
      return;
    }
    String url = text(specification.path("url"));
    String type = text(specification.path("type"));
    boolean http = !url.isEmpty() || "http".equals(type) || "sse".equals(type)
        || "streamable-http".equals(type);
    if (http) {
      servers.add(new Server.Http(name, url, stringMap(specification.path("headers"))));
      return;
    }
    String command = text(specification.path("command"));
    if (command.isEmpty()) {
      diagnostics.add("mcp server '" + name + "' has no command");
      return;
    }
    var arguments = new ArrayList<String>();
    JsonNode configuredArguments = specification.path("args");
    if (configuredArguments.isArray()) configuredArguments.forEach(value ->
        arguments.add(stringify(value)));
    servers.add(new Server.Stdio(name, command, arguments,
        stringMap(specification.path("env"))));
  }

  private static Map<String, String> stringMap(JsonNode node) {
    if (!node.isObject()) return Map.of();
    var values = new LinkedHashMap<String, String>();
    node.properties().forEach(entry -> values.put(entry.getKey(), stringify(entry.getValue())));
    return values;
  }

  private static String stringify(JsonNode value) {
    if (value.isTextual()) return value.textValue();
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      return value.toString();
    }
  }

  private static String text(JsonNode value) {
    return value.isTextual() ? value.textValue() : "";
  }

  private static JsonNode objectField(JsonNode document, String name) {
    if (document == null || !document.isObject()) return null;
    JsonNode value = document.path(name);
    return value.isObject() ? value : null;
  }

  private static Duration timeout(String value, Duration fallback, String debugLocation) {
    if (value == null || value.isEmpty()) return fallback;
    try {
      long milliseconds = Long.parseLong(value);
      return milliseconds > 0 ? Duration.ofMillis(milliseconds) : fallback;
    } catch (NumberFormatException exception) {
      AjentDebugLog.log(debugLocation, exception);
      return fallback;
    }
  }

  private static boolean truthy(String value) {
    if (value == null || value.isEmpty()) return false;
    return switch (value.charAt(0)) {
      case '1', 't', 'T', 'y', 'Y' -> true;
      default -> false;
    };
  }
}
