package com.github.skanga.ajent.tools.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.skanga.ajent.tools.args.ArgReader;
import com.github.skanga.ajent.tools.host.HostServices.DocRetriever;
import com.github.skanga.ajent.tools.host.HostServices.SkillResolver;
import com.github.skanga.ajent.tools.host.HostServices.SubagentRunner;
import com.github.skanga.ajent.tools.host.HostServices.TodoItem;
import com.github.skanga.ajent.tools.host.HostServices.TodoSink;
import com.github.skanga.ajent.tools.runtime.ToolError;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolOutput;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import com.github.skanga.ajent.domain.CancellationSignal;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Consumer;

/** Protocol shells for tools whose work is supplied by the application host. */
public final class HostTools {
  private final TodoSink todoSink;
  private final SkillResolver skillResolver;
  private final DocRetriever docRetriever;
  private final SubagentRunner subagentRunner;

  public HostTools(TodoSink todoSink, SkillResolver skillResolver, DocRetriever docRetriever,
      SubagentRunner subagentRunner) {
    this.todoSink = todoSink;
    this.skillResolver = skillResolver;
    this.docRetriever = docRetriever;
    this.subagentRunner = subagentRunner;
  }

  public ToolResult execute(String name, JsonNode arguments) {
    return execute(name, arguments, new CancellationSignal());
  }

  public ToolResult execute(String name, JsonNode arguments, CancellationSignal cancellation) {
    return execute(name, arguments, cancellation, ignored -> {});
  }

  public ToolResult execute(String name, JsonNode arguments, CancellationSignal cancellation,
                            Consumer<String> progress) {
    return switch (name) {
      case "todo" -> todo(arguments);
      case "skill" -> skill(arguments);
      case "search_docs" -> searchDocs(arguments);
      case "task" -> task(arguments, cancellation, progress);
      default -> failure(ToolErrorKind.UNKNOWN, "unknown tool: " + name);
    };
  }

  private ToolResult todo(JsonNode arguments) {
    var args = new ArgReader(arguments);
    var items = new ArrayList<TodoItem>();
    var output = new StringBuilder();
    String description = args.string("display_description", "");
    if (!description.isEmpty()) output.append(description).append("\n\n");
    JsonNode todos = args.raw("todos");
    if (todos != null && todos.isArray()) for (JsonNode todo : todos) {
      if (!todo.isObject()) continue;
      String content = todo.path("content").asText("");
      String status = todo.path("status").asText("pending");
      char mark = status.equals("completed") ? 'x' : status.equals("in_progress") ? '-' : ' ';
      output.append('[').append(mark).append("] ").append(content).append('\n');
      items.add(new TodoItem(content, status));
    }
    if (todoSink != null) todoSink.set(items);
    return success(output.toString());
  }

  private ToolResult skill(JsonNode arguments) {
    String name = new ArgReader(arguments).string("name", "");
    if (name.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, "skill: `name` is required.");
    if (skillResolver == null) return failure(ToolErrorKind.NOT_FOUND,
        "skill: unknown skill '" + name + "'.");
    var resolution = skillResolver.load(name);
    if (resolution.body().isEmpty()) return failure(ToolErrorKind.NOT_FOUND,
        resolution.error().isEmpty() ? "skill: unknown skill '" + name + "'." : resolution.error());
    return success(resolution.body().orElseThrow());
  }

  private ToolResult searchDocs(JsonNode arguments) {
    var args = new ArgReader(arguments);
    String query = args.string("query", "");
    if (query.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, "search_docs: `query` is required.");
    if (docRetriever == null) return failure(ToolErrorKind.NOT_FOUND, "search_docs: backend unavailable");
    int limit = Math.clamp(args.integer("k", 6), 1, 20);
    var response = docRetriever.retrieve(new HostServices.DocQuery(query, limit));
    if (!response.error().isEmpty()) return failure(ToolErrorKind.UNKNOWN,
        "search_docs: " + response.error());
    String description = args.string("display_description", "");
    var body = new StringBuilder();
    if (!description.isEmpty()) body.append(description).append('\n');
    if (response.hits().isEmpty()) return success(body + "No matching documents for: " + query);
    body.append(response.hits().size()).append(" results (mode: ")
        .append(response.mode().isEmpty() ? "default" : response.mode()).append(")\n");
    for (var hit : response.hits()) {
      String tag = hit.source().isEmpty() ? "" : hit.source() + ':';
      body.append("\n-- ").append(tag).append(hit.path()).append(':').append(hit.lineStart())
          .append('-').append(hit.lineEnd()).append("  (score ")
          .append(String.format(Locale.ROOT, "%.4f", hit.score())).append(")\n").append(hit.text());
      if (!body.isEmpty() && body.charAt(body.length() - 1) != '\n') body.append('\n');
    }
    return success(body.toString());
  }

  private ToolResult task(JsonNode arguments, CancellationSignal cancellation,
                          Consumer<String> progress) {
    if (subagentRunner == null || !subagentRunner.available()) return failure(ToolErrorKind.UNKNOWN,
        "task: subagent unavailable (not configured, or max nesting depth reached).");
    var args = new ArgReader(arguments);
    String prompt = args.string("prompt", "");
    if (prompt.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS, "task: `prompt` is required.");
    var response = subagentRunner.run(new HostServices.SubagentRequest(prompt,
        args.string("agent_type", "general")), cancellation, progress);
    return response.error() ? failure(ToolErrorKind.UNKNOWN, response.report()) : success(response.report());
  }

  private static ToolResult success(String text) { return new ToolResult.Success(new ToolOutput(text)); }
  private static ToolResult failure(ToolErrorKind kind, String detail) {
    return new ToolResult.Failure(new ToolError(kind, detail));
  }
}
