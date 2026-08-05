package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic transcript shapes shared by the native session-scaling probe ports. */
final class SessionBenchmarkSupport {
  private static final AtomicInteger IDS = new AtomicInteger();
  private static final String USER_PROMPT =
      "Please refactor the auth flow to use the new provider, "
          + "and run the test suite afterwards. Note any flakes. ";
  private static final List<String> PROSE = List.of(
      "I'll start by exploring the auth flow so I can see what's actually wired together. "
          + "The login handler in `src/auth/login.cpp` looks like the right entry point.",
      "The provider factory currently constructs a `LegacyAuth` on every call. "
          + "I'll swap that for the new `NewAuth::create` builder, which returns a "
          + "`Result<Session>` so the caller can surface init errors instead of crashing.",
      "Three callers depend on the old signature. I'll touch each one in turn: "
          + "`src/api/login.cpp`, `src/cli/auth_cmd.cpp`, and `tests/test_auth_flow.cpp`.");

  private SessionBenchmarkSupport() {}

  record Shape(String name, int turns, int writeLines, int penultimateWriteLines,
               int editHunks, int bashLines, int readLines, int proseParagraphs) {
    static Shape named(String name) {
      return switch (name) {
        case "A" -> new Shape("6t x 300-line write", 6, 300, 0, 0, 0, 0, 1);
        case "B" -> new Shape("6t x 800-line write", 6, 800, 0, 0, 0, 0, 1);
        case "C" -> new Shape("20t x 500-line write", 20, 500, 0, 0, 0, 0, 1);
        case "D" -> new Shape("80t x 500-line write", 80, 500, 0, 0, 0, 0, 1);
        case "E" -> new Shape("200t x 500-line write", 200, 500, 0, 0, 0, 0, 1);
        case "F" -> new Shape("20t x 10-hunk edit", 20, 0, 0, 10, 0, 0, 1);
        case "G" -> new Shape("80t x Write+Edit+Bash+Read", 80, 200, 0, 3, 30, 80, 2);
        case "H" -> new Shape("6t x 3000-line write", 6, 3000, 0, 0, 0, 0, 1);
        case "I" -> new Shape("off-screen 3000-line write + small tail",
            6, 8, 3000, 0, 0, 0, 1);
        default -> throw new IllegalArgumentException("unknown long-session shape: " + name);
      };
    }

    static Shape writeShape(int turns, int lines) {
      return new Shape(turns + "t x " + lines + "-line", turns, lines, 0, 0, 0, 0, 1);
    }
  }

  static List<Message> transcript(Shape shape) {
    var messages = new ArrayList<Message>(shape.turns() * 2);
    for (int turn = 0; turn < shape.turns(); turn++) {
      messages.add(BenchmarkUiSupport.user(userPrompt(80)));
      var tools = new ArrayList<ToolUse>();
      int writeLines = shape.penultimateWriteLines() > 0 && turn == shape.turns() - 2
          ? shape.penultimateWriteLines() : shape.writeLines();
      if (writeLines > 0) tools.add(write("src/auth/login.cpp", writeLines,
          new ToolStatus.Done("wrote " + writeLines + " lines")));
      if (shape.editHunks() > 0) tools.add(edit("src/api/login.cpp", shape.editHunks()));
      if (shape.bashLines() > 0) tools.add(done("bash", Map.of(
          "command", "cmake --build build -j10"), bashOutput(shape.bashLines())));
      if (shape.readLines() > 0) tools.add(done("read", Map.of(
          "path", "tests/test_auth.cpp"), BenchmarkUiSupport.code(shape.readLines())));
      messages.add(BenchmarkUiSupport.assistant(prose(shape.proseParagraphs()), tools));
    }
    return List.copyOf(messages);
  }

  static com.github.skanga.ajent.domain.Thread thread(Shape shape) {
    return new com.github.skanga.ajent.domain.Thread(
        new ThreadId("session-benchmark"), "Long-session bench: " + shape.name(),
        transcript(shape));
  }

  static ToolUse write(String path, int lines, ToolStatus status) {
    return new ToolUse(new ToolCallId("write-" + IDS.incrementAndGet()), new ToolName("write"),
        Map.of("file_path", path, "content", BenchmarkUiSupport.code(lines)), status);
  }

  static ToolUse edit(String path, int hunks) {
    var edits = new ArrayList<Map<String, String>>();
    for (int hunk = 0; hunk < hunks; hunk++) {
      edits.add(Map.of("old_text", BenchmarkUiSupport.code(3 + hunk % 6),
          "new_text", BenchmarkUiSupport.code(3 + (hunk + 2) % 6)));
    }
    return done("edit", Map.of("file_path", path, "edits", edits),
        "applied " + hunks + " edits to " + path);
  }

  static ToolUse done(String name, Map<String, ?> arguments, String output) {
    var copiedArguments = new HashMap<String, Object>();
    copiedArguments.putAll(arguments);
    return new ToolUse(new ToolCallId("call-" + IDS.incrementAndGet()), new ToolName(name),
        copiedArguments, new ToolStatus.Done(1, 2, output));
  }

  static String prose(int paragraphs) {
    var result = new StringBuilder();
    for (int index = 0; index < paragraphs; index++) {
      result.append(PROSE.get(index % PROSE.size())).append("\n\n");
    }
    return result.toString();
  }

  static String streamingProse(int lines) {
    var result = new StringBuilder(lines * 56);
    for (int index = 0; index < lines; index++) {
      result.append(index % 5 == 0
          ? "Here's the next step in the refactor, paragraph line."
          : "    auto x = compute(step, ctx); // inline detail").append('\n');
    }
    return result.toString();
  }

  static String longAnswer(int lines) {
    var result = new StringBuilder(lines * 100);
    for (int index = 0; index < lines; index++) {
      if (index % 7 == 0) result.append("## Section ").append(index).append("\n\n");
      result.append("This is a sentence of a long streaming answer that the model is writing "
          + "out token by token, line ").append(index).append(".\n\n");
    }
    return result.toString();
  }

  private static String userPrompt(int characters) {
    return USER_PROMPT.repeat((characters + USER_PROMPT.length() - 1) / USER_PROMPT.length())
        .substring(0, characters);
  }

  private static String bashOutput(int lines) {
    String line = "[  0.084s] linking target ajent\n";
    return line.repeat(lines);
  }
}
