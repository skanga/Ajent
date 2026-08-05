package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ActiveTurn;
import com.github.skanga.ajent.domain.CancellationSignal;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.SessionPhase;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rotating-turn/deep-run translation of Ajent's scrollback_oracle_test.cpp. */
final class ScrollbackOracleTest {
  private static final int[][] SHAPES = {{80, 30}, {60, 18}, {100, 50}, {46, 76}};

  @Test void rotatingTurnsDeepRunAndFollowupRemainAppendOnlyAcrossNativeShapes() {
    for (int[] shape : SHAPES) {
      var messages = new ArrayList<Message>();
      var harness = new RevealScrollbackTest.Harness(messages, shape[0], shape[1]);
      harness.frame(idle());
      for (int turn = 0; turn < 6; turn++) {
        switch (turn % 3) {
          case 0 -> proseTurn(harness, messages, turn, shape[1]);
          case 1 -> toolTurn(harness, messages, turn, shape[1]);
          default -> writeEditTurn(harness, messages, turn, shape[1]);
        }
      }
      deepRunTurn(harness, messages, 6, shape[1]);
      proseTurn(harness, messages, 7, shape[1]);

      harness.assertNoDuplicates();
      assertThat(messages).hasSizeGreaterThan(3 * shape[1]);
    }
  }

  private static void proseTurn(RevealScrollbackTest.Harness harness,
      List<Message> messages, int turn, int height) {
    messages.add(message(Role.USER, "turn " + turn + ": explain everything again"));
    harness.frame(active());
    Message reply = message(Role.ASSISTANT,
        "Turn " + turn + " opening: uniq-" + turn + "-open.");
    messages.add(reply);
    for (int frame = 0; frame < 3; frame++) harness.frame(active());
    reply = streamParagraphs(harness, messages, reply, turn, 0, 2 * height / 3, false, 1);
    messages.set(messages.size() - 1, reply);
    harness.settle();
    for (int frame = 0; frame < 6; frame++) harness.frame(idle());
  }

  private static void toolTurn(RevealScrollbackTest.Harness harness,
      List<Message> messages, int turn, int height) {
    messages.add(message(Role.USER, "turn " + turn + ": run checks uniq-" + turn + "-ask."));
    harness.frame(active());
    Message prose = message(Role.ASSISTANT,
        "Turn " + turn + " tool opening: uniq-" + turn + "-open.");
    messages.add(prose);
    int lead = height / 4 + 3;
    prose = streamParagraphs(harness, messages, prose, turn, 0, lead, true, 2);
    messages.set(messages.size() - 1, prose);
    int nextParagraph = lead;

    for (int round = 0; round < 3; round++) {
      if (round == 2) {
        nextParagraph = parallelRound(harness, messages, turn, height, nextParagraph);
        continue;
      }
      String tag = turn + "-" + round;
      ToolUse pending = bash(tag, new ToolStatus.Pending());
      Message toolMessage = assistant("", pending);
      messages.add(toolMessage);
      harness.frame(active());
      StringBuilder progress = new StringBuilder();
      int lines = round == 1 ? 2 * height + 10 : height / 2 + 8;
      for (int line = 0; line < lines; line++) {
        progress.append('[').append(line).append('/').append(lines)
            .append("] compiling translation unit ").append(line).append('\n');
        if (line % 3 == 2) {
          toolMessage = assistant("", bash(tag, new ToolStatus.Running(progress.toString())));
          messages.set(messages.size() - 1, toolMessage);
          harness.frame(active());
        }
      }
      toolMessage = assistant("", bash(tag,
          new ToolStatus.Done("ok: 42 tests passed (round " + round + ")")));
      messages.set(messages.size() - 1, toolMessage);
      if (round != 1) harness.frame(active());

      Message continuation = message(Role.ASSISTANT, "");
      messages.add(continuation);
      continuation = streamParagraphs(harness, messages, continuation, turn,
          nextParagraph, 3, true, 2);
      messages.set(messages.size() - 1, continuation);
      nextParagraph += 3;
    }
    harness.settle();
    for (int frame = 0; frame < 6; frame++) harness.frame(idle());
  }

  private static int parallelRound(RevealScrollbackTest.Harness harness,
      List<Message> messages, int turn, int height, int nextParagraph) {
    ToolUse first = bash(turn + "-2a", new ToolStatus.Pending());
    ToolUse second = bash(turn + "-2b", new ToolStatus.Pending());
    Message batch = assistant("", List.of(first, second));
    messages.add(batch);
    harness.frame(active());
    StringBuilder progress = new StringBuilder();
    int lines = 2 * height + 10;
    for (int line = 0; line < lines; line++) {
      progress.append("[par ").append(line).append('/').append(lines).append("] linking\n");
      if (line % 3 == 2) {
        first = bash(turn + "-2a", new ToolStatus.Running(""));
        second = bash(turn + "-2b", new ToolStatus.Running(progress.toString()));
        batch = assistant("", List.of(first, second));
        messages.set(messages.size() - 1, batch);
        harness.frame(active());
      }
    }
    first = bash(turn + "-2a", new ToolStatus.Done("ok: A done"));
    second = bash(turn + "-2b", new ToolStatus.Running(progress + "[tail]\n"));
    messages.set(messages.size() - 1, assistant("", List.of(first, second)));
    harness.frame(active());
    second = bash(turn + "-2b", new ToolStatus.Done("ok: B done"));
    messages.set(messages.size() - 1, assistant("", List.of(first, second)));
    harness.frame(active());
    Message continuation = message(Role.ASSISTANT, "");
    messages.add(continuation);
    continuation = streamParagraphs(harness, messages, continuation, turn,
        nextParagraph, 3, true, 2);
    messages.set(messages.size() - 1, continuation);
    return nextParagraph + 3;
  }

  private static void writeEditTurn(RevealScrollbackTest.Harness harness,
      List<Message> messages, int turn, int height) {
    messages.add(message(Role.USER, "turn " + turn + ": write and fix uniq-" + turn + "-ask."));
    harness.frame(active());
    Message prose = message(Role.ASSISTANT,
        "Turn " + turn + " write opening: uniq-" + turn + "-open.");
    messages.add(prose);
    int lead = height / 4 + 3;
    prose = streamParagraphs(harness, messages, prose, turn, 0, lead, true, 2);
    messages.set(messages.size() - 1, prose);

    StringBuilder content = new StringBuilder();
    ToolUse write = write(turn, "", new ToolStatus.Pending());
    messages.add(assistant("", write));
    harness.frame(active());
    for (int line = 0; line < 3 * height; line++) {
      content.append("auto v").append(line).append(" = compute_")
          .append(turn).append('(').append(line).append(");\n");
      if (line % 10 == 9) {
        write = write(turn, content.toString(), new ToolStatus.Pending());
        messages.set(messages.size() - 1, assistant("", write));
        harness.frame(active());
      }
    }
    write = write(turn, content.toString(), new ToolStatus.Running(""));
    messages.set(messages.size() - 1, assistant("", write));
    harness.frame(active());
    write = write(turn, content.toString(), new ToolStatus.Done("Wrote file"));
    messages.set(messages.size() - 1, assistant("", write));
    harness.frame(active());

    Message continuation = message(Role.ASSISTANT, "");
    messages.add(continuation);
    continuation = streamParagraphs(harness, messages, continuation, turn, lead, 3, true, 2);
    messages.set(messages.size() - 1, continuation);

    var edits = new ArrayList<Map<String, String>>();
    ToolUse edit = edit(turn, edits, new ToolStatus.Pending());
    messages.add(assistant("", edit));
    harness.frame(active());
    StringBuilder diff = new StringBuilder("```diff\n");
    for (int hunk = 0; hunk < 5; hunk++) {
      String oldText = hunkBody("old", turn, hunk);
      String newText = hunkBody("new", turn, hunk);
      edits.add(Map.of("old_text", oldText, "new_text", newText));
      edit = edit(turn, edits, new ToolStatus.Pending());
      messages.set(messages.size() - 1, assistant("", edit));
      harness.frame(active());
      diff.append("@@ -").append(hunk * 10 + 1).append(",6 +")
          .append(hunk * 10 + 1).append(",6 @@\n");
      oldText.lines().forEach(line -> diff.append('-').append(line).append('\n'));
      newText.lines().forEach(line -> diff.append('+').append(line).append('\n'));
    }
    edit = edit(turn, edits, new ToolStatus.Running(""));
    messages.set(messages.size() - 1, assistant("", edit));
    harness.frame(active());
    edit = edit(turn, edits, new ToolStatus.Done(diff.append("```\n").toString()));
    messages.set(messages.size() - 1, assistant("", edit));
    Message finalText = message(Role.ASSISTANT, "");
    messages.add(finalText);
    finalText = streamParagraphs(harness, messages, finalText, turn, lead + 3, 3, true, 2);
    messages.set(messages.size() - 1, finalText);
    harness.settle();
    for (int frame = 0; frame < 6; frame++) harness.frame(idle());
  }

  private static void deepRunTurn(RevealScrollbackTest.Harness harness,
      List<Message> messages, int turn, int height) {
    messages.add(message(Role.USER, "turn 6: long edits uniq-6-ask."));
    messages.add(message(Role.ASSISTANT, "Turn 6 deep opening: uniq-6-open."));
    harness.frame(active());
    int edits = 3 * height + 10;
    for (int index = 0; index < edits; index++) {
      String tag = "6e" + index;
      ToolUse edit = new ToolUse(new ToolCallId("deep-" + tag), new ToolName("edit"),
          Map.of("path", "src/deep_" + tag + ".java",
              "edits", List.of(Map.of("old_text", "a();\n", "new_text", "b();\n"))),
          new ToolStatus.Done("edited uniq-" + tag + "-done"));
      messages.add(assistant("", edit));
      int burst = index < edits / 2 ? 1 : 3;
      if (index % burst == burst - 1) harness.frame(active());
    }
    ToolUse running = bash("deep-6", new ToolStatus.Running(""));
    messages.add(assistant("", running));
    StringBuilder progress = new StringBuilder();
    for (int line = 0; line < height + 12; line++) {
      progress.append('[').append(line).append("] deep step\n");
      if (line % 3 == 2) {
        running = bash("deep-6", new ToolStatus.Running(progress.toString()));
        messages.set(messages.size() - 1, assistant("", running));
        harness.frame(active());
      }
    }
    messages.set(messages.size() - 1,
        assistant("", bash("deep-6", new ToolStatus.Done("ok uniq-6-run-done"))));
    Message continuation = message(Role.ASSISTANT, "");
    messages.add(continuation);
    continuation = streamParagraphs(harness, messages, continuation, turn, 0, 3, true, 2);
    messages.set(messages.size() - 1, continuation);
    harness.settle();
  }

  private static Message streamParagraphs(RevealScrollbackTest.Harness harness,
      List<Message> messages, Message message, int turn, int first, int count,
      boolean wrapping, int burst) {
    int pending = 0;
    for (int paragraph = first; paragraph < first + count; paragraph++) {
      String text = message.text() + "\n\nParagraph uniq-" + turn + '-' + paragraph
          + " covers stage " + paragraph + " of the pipeline in turn " + turn + '.'
          + (wrapping ? " It elaborates at considerable length about provider objects and the "
              + "translation unit defining the missing symbol so this wraps across rows." : "");
      message = withText(message, text);
      messages.set(messages.size() - 1, message);
      if (++pending >= burst) { pending = 0; harness.frame(active()); harness.frame(active()); }
    }
    if (pending > 0) harness.frame(active());
    return message;
  }

  private static ToolUse bash(String tag, ToolStatus status) {
    return new ToolUse(new ToolCallId("bash-" + tag), new ToolName("bash"),
        Map.of("command", "make " + tag, "display_description", "uniq-" + tag + ". go"), status);
  }

  private static ToolUse write(int turn, String content, ToolStatus status) {
    return new ToolUse(new ToolCallId("write-" + turn), new ToolName("write"),
        Map.of("file_path", "src/gen_" + turn + ".java", "content", content), status);
  }

  private static ToolUse edit(int turn, List<Map<String, String>> edits, ToolStatus status) {
    return new ToolUse(new ToolCallId("edit-" + turn), new ToolName("edit"),
        Map.of("path", "src/gen_" + turn + ".java", "edits", List.copyOf(edits)), status);
  }

  private static String hunkBody(String prefix, int turn, int hunk) {
    var body = new StringBuilder();
    for (int line = 0; line < 6; line++) body.append(prefix).append('_').append(turn)
        .append("_h").append(hunk).append("_l").append(line).append("();\n");
    return body.toString();
  }

  private static Message assistant(String text, ToolUse tool) {
    return assistant(text, List.of(tool));
  }

  private static Message assistant(String text, List<ToolUse> tools) {
    return new Message(Role.ASSISTANT, text, List.of(), tools);
  }

  private static Message message(Role role, String text) {
    return new Message(role, text, List.of(), List.of());
  }

  private static Message withText(Message message, String text) {
    return new Message(message.id(), message.role(), text, message.images(), message.attachments(),
        message.thinking(), message.thinkingSignature(), message.toolCalls(), message.timestamp(),
        message.checkpointId(), message.error(), message.textBlockClosed(), message.isCompactSummary());
  }

  private static SessionPhase active() {
    return new SessionPhase.Streaming(ActiveTurn.start(new CancellationSignal(), 1));
  }

  private static SessionPhase idle() { return new SessionPhase.Idle(); }
}
