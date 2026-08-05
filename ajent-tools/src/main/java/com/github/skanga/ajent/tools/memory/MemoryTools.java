package com.github.skanga.ajent.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.skanga.ajent.tools.args.ArgReader;
import com.github.skanga.ajent.tools.runtime.ToolError;
import com.github.skanga.ajent.tools.runtime.ToolErrorKind;
import com.github.skanga.ajent.tools.runtime.ToolOutput;
import com.github.skanga.ajent.tools.runtime.ToolResult;
import java.util.ArrayList;
import java.util.List;

/** Ajent-compatible remember, forget, and wipe protocol shells. */
public final class MemoryTools {
  private final MemoryStore store;

  public MemoryTools(MemoryStore store) { this.store = store; }

  public ToolResult execute(String name, JsonNode arguments) {
    if (store == null || store.scopes().isEmpty())
      return failure(ToolErrorKind.NOT_FOUND, "memory store unavailable");
    return switch (name) {
      case "remember" -> remember(arguments);
      case "forget" -> forget(arguments);
      case "wipe_memory" -> wipe(arguments);
      default -> failure(ToolErrorKind.UNKNOWN, "unknown tool: " + name);
    };
  }

  private ToolResult remember(JsonNode arguments) {
    var args = new ArgReader(arguments);
    String text = args.string("text", "").strip();
    if (text.isEmpty()) return failure(ToolErrorKind.INVALID_ARGS,
        "remember: `text` is required (one short sentence).");
    List<String> scopes = store.scopes();
    String scope = args.string("scope", scopes.getFirst());
    if (scope.equals("global") || scope.equals("all")) scope = scopes.getLast();
    if (!scopes.contains(scope)) return failure(ToolErrorKind.INVALID_ARGS,
        "remember: unknown scope '" + scope + "'.");
    var result = store.append(new MemoryStore.AppendRequest(text, scope, args.bool("pin", false),
        parseTags(args.raw("tags")), args.string("supersedes", "")));
    if (result.error() != null && !result.error().isEmpty()) return failure(ToolErrorKind.UNKNOWN,
        "remember: " + result.error());
    var message = new StringBuilder(result.deduped() ? "Already knew that (refreshed "
        + result.id() + ")." : "Remembered [" + result.id() + "].");
    if (result.note() != null && !result.note().isEmpty()) message.append(' ').append(result.note());
    if (result.rolled() > 0) message.append(" (").append(result.rolled())
        .append(" old record(s) rolled.)");
    return success(message.toString());
  }

  private ToolResult forget(JsonNode arguments) {
    var args = new ArgReader(arguments);
    String id = args.string("id", "");
    String substring = args.string("substring", "");
    if (!id.isEmpty()) {
      int count = store.forgetById(id);
      return success(count > 0 ? "Forgot [" + id + "]." : "No record with id " + id + ".");
    }
    if (substring.isBlank()) return failure(ToolErrorKind.INVALID_ARGS,
        "forget: provide `id` or a non-empty `substring`.");
    if (args.bool("dry_run", false)) {
      List<MemoryStore.Record> matches = store.previewForget(substring);
      if (matches.isEmpty()) return success("No records match \"" + substring + "\".");
      var output = new StringBuilder("Would remove ").append(matches.size()).append(" record(s):\n");
      for (var match : matches) output.append("  [").append(match.id()).append("] ")
          .append(match.text()).append('\n');
      return success(output.toString());
    }
    return success("Forgot " + store.forgetBySubstring(substring)
        + " record(s) matching \"" + substring + "\".");
  }

  private ToolResult wipe(JsonNode arguments) {
    var args = new ArgReader(arguments);
    String scope = args.string("scope", "");
    if (!store.scopes().contains(scope)) return failure(ToolErrorKind.INVALID_ARGS,
        "wipe_memory: unknown scope '" + scope + "'.");
    if (!args.bool("confirm", false)) {
      store.previewForget("");
      return success("This will wipe scope '" + scope + "'. Re-call with confirm:true to proceed.");
    }
    var count = store.wipe(scope);
    if (count.isEmpty()) return failure(ToolErrorKind.UNKNOWN,
        "wipe_memory: scope '" + scope + "' unresolvable.");
    return success("Wiped " + count.getAsInt() + " record(s) from scope '" + scope + "'.");
  }

  static List<String> parseTags(JsonNode tags) {
    var result = new ArrayList<String>();
    if (tags == null) return result;
    if (tags.isArray()) tags.forEach(tag -> { if (tag.isTextual()) result.add(tag.textValue()); });
    else if (tags.isTextual()) for (String tag : tags.textValue().split(",", -1)) {
      if (!tag.isBlank()) result.add(tag.strip());
    }
    return result;
  }

  private static ToolResult success(String text) { return new ToolResult.Success(new ToolOutput(text)); }
  private static ToolResult failure(ToolErrorKind kind, String detail) {
    return new ToolResult.Failure(new ToolError(kind, detail));
  }
}
