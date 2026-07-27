package com.github.skanga.ajent.provider.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.skanga.ajent.core.AjentDebugLog;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import com.github.skanga.ajent.provider.ChatRequest;
import com.github.skanga.ajent.provider.ToolSpecification;
import com.github.skanga.ajent.provider.openai.OpenAiWire;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OllamaWire {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int CONTEXT_FLOOR = 8_192;
  private static final int CONTEXT_CEILING = 32_768;

  private OllamaWire() {}

  public static ArrayNode buildMessages(List<Message> messages, boolean jsonProtocol) {
    ArrayNode result = JSON.createArrayNode();
    for (Message message : messages) {
      boolean hasText = !message.text().isEmpty();
      boolean hasImages = message.role() == Role.USER
          && message.images().stream().anyMatch(image -> !image.isEmpty());
      boolean hasTools = message.role() == Role.ASSISTANT && !message.toolCalls().isEmpty();
      if (jsonProtocol && hasTools) {
        addJsonProtocolHistory(result, message, hasText);
        continue;
      }
      if (hasText || hasImages || hasTools) {
        ObjectNode wire = result.addObject();
        wire.put("role", message.role() == Role.USER ? "user" : "assistant");
        wire.put("content", message.text());
        if (hasImages) {
          ArrayNode images = wire.putArray("images");
          message.images().stream().filter(image -> !image.isEmpty())
              .map(image -> Base64.getEncoder().encodeToString(image.bytes()))
              .forEach(images::add);
        }
        if (hasTools) {
          ArrayNode calls = wire.putArray("tool_calls");
          for (ToolUse tool : message.toolCalls()) {
            ObjectNode call = calls.addObject();
            call.put("id", tool.id().value());
            ObjectNode function = call.putObject("function");
            function.put("name", tool.name().value());
            function.set("arguments", JSON.valueToTree(tool.arguments()));
          }
        }
      }
      if (hasTools) {
        for (ToolUse tool : message.toolCalls()) {
          ObjectNode toolResult = result.addObject();
          toolResult.put("role", "tool");
          toolResult.put("tool_name", tool.name().value());
          toolResult.put("content", toolOutput(tool));
        }
      }
    }
    return result;
  }

  public static Map<String, Object> buildOptions(OllamaRequestOptions request) {
    return buildOptions(request, System.getenv());
  }

  public static Map<String, Object> buildOptions(
      OllamaRequestOptions request, Map<String, String> environment) {
    int context = leadingInteger(environment.get("AJENT_OLLAMA_NUM_CTX"));
    if (context <= 0) context = request.contextWindow() > 0
        ? Math.clamp(request.contextWindow(), CONTEXT_FLOOR, CONTEXT_CEILING)
        : CONTEXT_FLOOR;
    int prediction = leadingInteger(environment.get("AJENT_OLLAMA_NUM_PREDICT"));
    if (prediction <= 0) {
      prediction = request.maxTokens() > 0 ? request.maxTokens() : 4_096;
      prediction = Math.min(prediction, context / 2);
      prediction = Math.max(prediction, Math.min(2_048, context));
    }
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("num_ctx", context);
    options.put("num_predict", prediction);
    if (request.jsonProtocol()) {
      options.put("temperature", 0.2);
      options.put("top_p", 0.9);
    }
    Double temperature = leadingDouble(environment.get("AJENT_OLLAMA_TEMPERATURE"));
    if (temperature != null) options.put("temperature", temperature);
    return Map.copyOf(options);
  }

  public static ObjectNode buildRequestBody(ChatRequest request) {
    boolean jsonProtocol = request.jsonProtocol() && !request.tools().isEmpty();
    ObjectNode body = JSON.createObjectNode();
    body.put("model", request.model());
    body.put("stream", true);
    body.put("keep_alive", "10m");
    body.set("options", JSON.valueToTree(buildOptions(new OllamaRequestOptions(
        request.maxTokens(), request.contextWindow(), jsonProtocol))));
    ArrayNode messages = body.putArray("messages");
    String systemPrompt = request.systemPrompt();
    if (jsonProtocol) systemPrompt += jsonProtocolAddendum(request.tools());
    if (!systemPrompt.isEmpty()) {
      ObjectNode system = messages.addObject();
      system.put("role", "system");
      system.put("content", systemPrompt);
    }
    messages.addAll(buildMessages(request.messages(), jsonProtocol));
    if (!request.tools().isEmpty() && !jsonProtocol) {
      body.set("tools", OpenAiWire.buildTools(request.tools()));
    }
    if (jsonProtocol) body.set("format", jsonProtocolSchema(request.tools()));
    return body;
  }

  public static HttpRequest buildHttpRequest(ChatRequest request) {
    try {
      String body = JSON.writeValueAsString(buildRequestBody(request));
      HttpRequest.Builder builder = HttpRequest.newBuilder(
              OpenAiWire.endpointUri(request.endpoint(), "/api/chat"))
          .header("accept", "application/json")
          .header("content-type", "application/json")
          .header("user-agent", "ajent/0.2.8")
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      OpenAiWire.addAuthorization(builder, request.auth());
      return builder.build();
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Unable to serialize Ollama request", exception);
    }
  }

  public static String systemPrompt() {
    return systemPrompt(Path.of("").toAbsolutePath(),
        Path.of(System.getProperty("user.home", "")), System.getProperty("os.name", "unknown"));
  }

  public static String systemPrompt(Path workingDirectory, Path home, String operatingSystem) {
    String lowerOs = operatingSystem.toLowerCase(java.util.Locale.ROOT);
    String osName = lowerOs.startsWith("win") || lowerOs.contains("windows") ? "Windows"
        : lowerOs.contains("mac") || lowerOs.contains("darwin") ? "macOS" : "Linux";
    String shell = "Windows".equals(osName) ? "cmd.exe" : "sh";
    String prompt = """
        You are ajent, a terminal coding assistant. You are helpful, direct, and act on requests instead of asking which option to pick. Keep replies concise.

        CONVERSATION MEMORY
        - The full conversation so far is in the messages above. ALWAYS use earlier messages to answer follow-up questions (names, files, decisions the user already gave you).
        - If the user told you a fact earlier (e.g. their name), recall it from the conversation; never say you don't have it.

        TOOLS
        - Tools let you read/edit files and run commands. Call a tool ONLY when the task needs it. For greetings, chit-chat, or questions you can answer from the conversation, reply in plain text — do NOT call a tool.
        - When a task DOES need an action (rename/move/delete a file, run a shell command, read or edit code), you MUST actually call the tool. NEVER describe the command in prose or a code block and claim it ran — that does nothing. NEVER say a file was created, renamed, or deleted unless a tool you called returned that result.
        - To run a shell command (mv, rm, mkdir, git, etc.) call the `bash` tool with a `command` argument. There is NO `git` or `mv` tool — use `bash`. To edit an existing file use `edit`; use `write` only to create a new file.
        - Emit tool calls through the tool-call channel, NOT as JSON or a ```code block``` in your reply.
        - Make ONE tool call at a time and wait for its result. Never invent a tool result.
        - Never call remember/forget/wipe_memory unless the user asks you to remember or forget something.
        - For questions about the user's OWN docs, manuals, specs, or notes (anything you can't reliably answer from general knowledge), call `search_docs` FIRST to retrieve the relevant passages, then answer from what it returns. Do NOT guess from memory when the answer should come from their documents.

        OUTPUT
        - Output is rendered as GitHub-flavoured markdown in a terminal. Use fenced code blocks for code. Keep tables small.

        ENVIRONMENT
        - os: %s
        - shell: %s
        - cwd: %s
        """.formatted(osName, shell, workingDirectory);
    return prompt + memoryBlocks(home, workingDirectory);
  }

  private static String memoryBlocks(Path home, Path project) {
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
    try {
      byte[] bytes = Files.readAllBytes(path);
      return new String(bytes, 0, Math.min(bytes.length, 64 * 1024), StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      return "";
    }
  }

  private static int leadingInteger(String value) {
    if (value == null || value.isEmpty() || !Character.isDigit(value.charAt(0))) return 0;
    long result = 0;
    for (int index = 0; index < value.length() && Character.isDigit(value.charAt(index)); index++) {
      result = Math.min(Integer.MAX_VALUE, result * 10 + value.charAt(index) - '0');
    }
    return (int) result;
  }

  private static Double leadingDouble(String value) {
    if (value == null) return null;
    var matcher = java.util.regex.Pattern.compile(
        "^[\\s]*[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?").matcher(value);
    if (!matcher.find()) {
      if (!value.isEmpty()) AjentDebugLog.log(
          "ollama.env_temperature.parse", "invalid floating-point value: " + value);
      return null;
    }
    try {
      return Double.parseDouble(matcher.group());
    } catch (NumberFormatException failure) {
      AjentDebugLog.log("ollama.env_temperature.parse", failure);
      return null;
    }
  }

  private static void addJsonProtocolHistory(
      ArrayNode result, Message message, boolean hasText) {
    if (hasText) {
      ObjectNode prose = result.addObject();
      prose.put("role", "assistant");
      prose.put("content", message.text());
    }
    for (ToolUse tool : message.toolCalls()) {
      ObjectNode callObject = JSON.createObjectNode();
      callObject.set("tool_args", sortedObjectKeys(JSON.valueToTree(tool.arguments())));
      callObject.put("tool_name", tool.name().value());
      ObjectNode callMessage = result.addObject();
      callMessage.put("role", "assistant");
      try {
        callMessage.put("content", JSON.writeValueAsString(callObject));
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("Unable to serialize Ollama JSON protocol history", exception);
      }
      ObjectNode toolResult = result.addObject();
      toolResult.put("role", "user");
      toolResult.put("content", "TOOL RESULT (" + tool.name().value() + "):\n" + toolOutput(tool));
    }
  }

  private static JsonNode sortedObjectKeys(JsonNode value) {
    if (value.isObject()) {
      ObjectNode sorted = JSON.createObjectNode();
      var names = new java.util.ArrayList<String>();
      value.fieldNames().forEachRemaining(names::add);
      names.sort(String::compareTo);
      for (String name : names) sorted.set(name, sortedObjectKeys(value.get(name)));
      return sorted;
    }
    if (value.isArray()) {
      ArrayNode sorted = JSON.createArrayNode();
      value.forEach(item -> sorted.add(sortedObjectKeys(item)));
      return sorted;
    }
    return value.deepCopy();
  }

  private static String jsonProtocolAddendum(List<ToolSpecification> tools) {
    StringBuilder result = new StringBuilder("""


        ## How to act (IMPORTANT — read carefully)
        You do NOT have a function-calling API. To use a tool you MUST reply with ONE single JSON object and NOTHING else — no prose before or after it, no markdown fences. The JSON object has exactly these fields:
          - "thoughts": array of short strings, your reasoning
          - "tool_name": the EXACT name of one tool from the list below
          - "tool_args": an object of arguments for that tool

        Example (run a shell command):
        {"thoughts":["I need to list files"],"tool_name":"bash","tool_args":{"command":"ls -la"}}

        Rules:
        - Output the JSON object ALONE, valid JSON, double quotes.
        - Use ONE tool per reply, then wait for its result in the next message before the next step.
        - The tool's result comes back as a user message beginning `TOOL RESULT (toolname):` — read it, then emit your NEXT JSON object (another tool call, or a "response" object when the task is done).
        - `tool_name` must be one of the listed names, never an action verb like read/write/run.
        - Tools act on LOCAL files and commands only. `read` opens a local file by path — it does NOT fetch a URL. To get a web page use `web_fetch` with a `url`; to search the web use `web_search`. Never pass an http(s):// address to `read`.
        - If a tool result is an ERROR, do NOT re-issue the same call — it will fail again. Fix the arguments (a path that doesn't exist, or the wrong tool), or answer the user with what you already have.
        - If you do NOT need a tool (a greeting, or a question you can answer from the conversation), set "tool_name" to "response" and put your reply text in "tool_args": {"text": "..."}.

        ## Available tools
        """);
    for (ToolSpecification tool : tools) {
      result.append("- ").append(tool.name());
      if (!tool.description().isEmpty()) {
        String description = tool.description().lines().findFirst().orElse("");
        result.append(": ").append(truncateUtf8(description, 160));
      }
      JsonNode properties = tool.inputSchema().path("properties");
      if (properties.isObject() && !properties.isEmpty()) {
        var names = new java.util.ArrayList<String>();
        properties.fieldNames().forEachRemaining(names::add);
        result.append("  (args: ").append(String.join(", ", names)).append(')');
      }
      result.append('\n');
    }
    return result.toString();
  }

  private static String truncateUtf8(String value, int maxBytes) {
    int bytes = 0;
    int end = 0;
    while (end < value.length()) {
      int codePoint = value.codePointAt(end);
      int width = new String(Character.toChars(codePoint))
          .getBytes(StandardCharsets.UTF_8).length;
      if (bytes + width > maxBytes) break;
      bytes += width;
      end += Character.charCount(codePoint);
    }
    return value.substring(0, end);
  }

  private static ObjectNode jsonProtocolSchema(List<ToolSpecification> tools) {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("thoughts").put("type", "array")
        .putObject("items").put("type", "string");
    ObjectNode toolName = properties.putObject("tool_name");
    toolName.put("type", "string");
    ArrayNode names = toolName.putArray("enum");
    tools.forEach(tool -> names.add(tool.name()));
    names.add("response");
    properties.putObject("tool_args").put("type", "object");
    schema.putArray("required").add("tool_name").add("tool_args");
    return schema;
  }

  private static String toolOutput(ToolUse tool) {
    String output = tool.status().output();
    if (!output.isEmpty()) return output;
    if (tool.status() instanceof ToolStatus.Rejected) return "(rejected by user)";
    if (!tool.status().isTerminal()) return "(no output)";
    return output;
  }
}
