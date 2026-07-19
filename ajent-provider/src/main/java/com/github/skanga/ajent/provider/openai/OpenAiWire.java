package com.github.skanga.ajent.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.provider.ChatRequest;
import com.github.skanga.ajent.provider.auth.ProviderAuth;
import com.github.skanga.ajent.provider.ollama.OllamaWire;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class OpenAiWire {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String USER_AGENT = "ajent/0.1.0-SNAPSHOT";

  private OpenAiWire() {}

  public static ArrayNode buildTools(List<ToolSpecification> tools) {
    ArrayNode result = JSON.createArrayNode();
    for (var tool : tools) {
      ObjectNode item = result.addObject();
      item.put("type", "function");
      ObjectNode function = item.putObject("function");
      function.put("name", tool.name());
      function.put("description", tool.description());
      function.set("parameters", tool.inputSchema());
    }
    return result;
  }

  public static ArrayNode buildMessages(Thread thread) {
    ArrayNode result = JSON.createArrayNode();
    for (Message message : thread.messages()) {
      boolean hasText = !message.text().isEmpty();
      boolean hasImages = message.role() == Role.USER
          && message.images().stream().anyMatch(image -> !image.isEmpty());
      boolean hasTools = message.role() == Role.ASSISTANT && !message.toolCalls().isEmpty();
      if (hasText || hasImages || hasTools) {
        result.add(primaryMessage(message, hasImages, hasTools));
      }
      if (hasTools) {
        for (ToolUse tool : message.toolCalls()) {
          result.add(toolResult(tool));
        }
      }
    }
    return result;
  }

  public static ObjectNode buildRequestBody(ChatRequest request) {
    ObjectNode body = JSON.createObjectNode();
    body.put("model", request.model());
    body.put("stream", true);
    ArrayNode messages = JSON.createArrayNode();
    if (!request.systemPrompt().isEmpty()) {
      ObjectNode system = messages.addObject();
      system.put("role", "system");
      system.put("content", request.systemPrompt());
    }
    if (request.endpoint().nativeApi()) {
      body.putObject("options").put("num_predict", request.maxTokens());
      messages.addAll(OllamaWire.buildMessages(request.messages(), false));
    } else {
      body.put("max_tokens", request.maxTokens());
      body.putObject("stream_options").put("include_usage", true);
      messages.addAll(buildMessages(new Thread(
          new com.github.skanga.ajent.domain.ThreadId(""), "", request.messages())));
    }
    body.set("messages", messages);
    if (!request.tools().isEmpty()) body.set("tools", buildTools(request.tools()));
    return body;
  }

  public static HttpRequest buildHttpRequest(ChatRequest request) {
    try {
      String body = JSON.writeValueAsString(buildRequestBody(request));
      HttpRequest.Builder builder = HttpRequest.newBuilder(endpointUri(
              request.endpoint(), request.endpoint().path()))
          .header("accept", "application/json")
          .header("content-type", "application/json")
          .header("user-agent", USER_AGENT)
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      addAuthorization(builder, request.auth());
      return builder.build();
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Unable to serialize OpenAI request", exception);
    }
  }

  /** AgenTTY's concise prompt for OpenAI-compatible and other local-model endpoints. */
  public static String localModelSystemPrompt(
      Path workingDirectory, Path home, String operatingSystem) {
    Path workspace = workingDirectory.toAbsolutePath().normalize();
    String lowerOs = operatingSystem.toLowerCase(Locale.ROOT);
    String osName = lowerOs.startsWith("win") || lowerOs.contains("windows") ? "Windows"
        : lowerOs.contains("mac") || lowerOs.contains("darwin") ? "macOS" : "Linux";
    String shell = "Windows".equals(osName) ? "cmd.exe" : "sh";
    String prompt = """
        You are ajent, a terminal coding assistant. You are helpful, direct, and act on requests instead of asking which option to pick. Keep replies concise.

        CONVERSATION MEMORY
        - The full conversation so far is provided in the messages. ALWAYS use earlier messages to answer follow-up questions (names, files, decisions the user already gave you).
        - If the user told you a fact earlier (e.g. their name), recall it from the conversation; do not say you don't have it.

        TOOLS
        - Tools let you read/edit files and run commands. Call a tool ONLY when the task needs it (touch files, run a command, search the codebase). For greetings, chit-chat, or questions you can answer from the conversation, reply in plain text — do NOT call a tool.
        - To edit an existing file use `edit` (targeted change). Use `write` only to create a new file.
        - Make ONE tool call at a time and wait for its result before the next. Never invent a tool result.
        - Never call remember/forget/wipe_memory unless the user asks you to remember or forget something.

        OUTPUT
        - Output is rendered as GitHub-flavoured markdown in a terminal. Use fenced code blocks for code. Keep tables small.

        ENVIRONMENT
        - os: %s
        - shell: %s
        - cwd: %s
        """.formatted(osName, shell, workspace);
    return prompt + authoredMemoryBlocks(home, workspace);
  }

  public static URI endpointUri(Endpoint endpoint, String path) {
    try {
      int port = endpoint.useTls() && endpoint.port() == 443 ? -1
          : !endpoint.useTls() && endpoint.port() == 80 ? -1 : endpoint.port();
      return new URI(endpoint.useTls() ? "https" : "http", null, endpoint.host(),
          port, path, null, null);
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("Invalid provider endpoint", exception);
    }
  }

  public static void addAuthorization(HttpRequest.Builder builder, ProviderAuth auth) {
    String value = switch (auth) {
      case ProviderAuth.Empty ignored -> "";
      case ProviderAuth.Bearer bearer -> bearer.token();
      case ProviderAuth.ApiKey apiKey -> apiKey.value();
    };
    if (!value.isEmpty()) builder.header("authorization", "Bearer " + value);
  }

  private static String authoredMemoryBlocks(Path home, Path project) {
    String user = readBounded(home.resolve("CLAUDE.md"));
    String workspace = readBounded(project.resolve("CLAUDE.md"));
    String local = readBounded(project.resolve("CLAUDE.local.md"));
    if (user.isEmpty() && workspace.isEmpty() && local.isEmpty()) return "";
    StringBuilder result = new StringBuilder("\n\n<memory>\n"
        + "Project-specific guidance the user has authored. Treat these as "
        + "persistent context for THIS workspace and user.\n");
    appendMemory(result, "user-memory", user);
    appendMemory(result, "project-memory", workspace);
    appendMemory(result, "local-memory", local);
    return result.append("</memory>").toString();
  }

  private static void appendMemory(StringBuilder target, String tag, String content) {
    if (!content.isEmpty()) {
      target.append('<').append(tag).append(">\n").append(content)
          .append("\n</").append(tag).append(">\n");
    }
  }

  private static String readBounded(Path path) {
    if (!Files.isRegularFile(path)) return "";
    try (var input = Files.newInputStream(path)) {
      return new String(input.readNBytes(64 * 1024), StandardCharsets.UTF_8);
    } catch (java.io.IOException | SecurityException ignored) {
      return "";
    }
  }

  private static ObjectNode primaryMessage(Message message, boolean hasImages, boolean hasTools) {
    ObjectNode result = JSON.createObjectNode();
    result.put("role", message.role() == Role.USER ? "user" : "assistant");
    if (hasImages) {
      ArrayNode content = result.putArray("content");
      if (!message.text().isEmpty()) {
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", message.text());
      }
      for (var image : message.images()) {
        if (image.isEmpty()) continue;
        ObjectNode part = content.addObject();
        part.put("type", "image_url");
        part.putObject("image_url").put(
            "url", "data:" + image.mediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.bytes()));
      }
    } else {
      result.put("content", message.text());
    }
    if (hasTools) {
      ArrayNode calls = result.putArray("tool_calls");
      for (ToolUse tool : message.toolCalls()) {
        ObjectNode call = calls.addObject();
        call.put("id", tool.id().value());
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", tool.name().value());
        try {
          function.put("arguments", JSON.writeValueAsString(tool.arguments()));
        } catch (JsonProcessingException exception) {
          throw new IllegalStateException("Unable to serialize OpenAI tool arguments", exception);
        }
      }
    }
    return result;
  }

  private static ObjectNode toolResult(ToolUse tool) {
    ObjectNode result = JSON.createObjectNode();
    result.put("role", "tool");
    result.put("tool_call_id", tool.id().value());
    String output = tool.status().output();
    if (output.isEmpty()) {
      if (tool.status() instanceof ToolStatus.Rejected) output = "(rejected by user)";
      else if (!tool.status().isTerminal()) output = "(no output)";
    }
    result.put("content", output);
    return result;
  }
}
