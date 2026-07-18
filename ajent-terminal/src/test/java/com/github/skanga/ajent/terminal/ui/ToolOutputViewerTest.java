package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ToolCallId;
import com.github.skanga.ajent.domain.ToolName;
import com.github.skanga.ajent.domain.ToolStatus;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ToolOutputViewerTest {
  @Test void openReportsEmptyAndStartsARecentListAtZero() {
    assertThat(ToolOutputViewer.open(List.of()).status())
        .isEqualTo("no tool outputs to inspect yet");
    var entries = List.of(entry("one"), entry("two"));
    assertThat(ToolOutputViewer.open(entries).state())
        .isEqualTo(new ToolOutputViewer.Open(entries, 0, false, 0, 0));
  }

  @Test void listMovementClampsAndEscapeCloses() {
    var entries = List.of(entry("one"), entry("two"));
    ToolOutputViewer.State state = new ToolOutputViewer.Open(entries, 0, false, 0, 0);
    assertThat(ToolOutputViewer.move(state, 99))
        .isEqualTo(new ToolOutputViewer.Open(entries, 1, false, 0, 0));
    assertThat(ToolOutputViewer.close(state)).isEqualTo(new ToolOutputViewer.Closed());
  }

  @Test void bodyMovementScrollsAndHorizontalStepChangesEntriesWithoutWrap() {
    var entries = List.of(entry("one"), entry("two"));
    ToolOutputViewer.State state = ToolOutputViewer.select(
        new ToolOutputViewer.Open(entries, 0, false, 0, 0));
    state = ToolOutputViewer.withMaxScroll(state, 20);
    state = ToolOutputViewer.move(state, 8);
    assertThat(state).isEqualTo(new ToolOutputViewer.Open(entries, 0, true, 8, 20));
    state = ToolOutputViewer.step(state, 1);
    assertThat(state).isEqualTo(new ToolOutputViewer.Open(entries, 1, true, 0, 20));
    assertThat(ToolOutputViewer.step(state, 1)).isSameAs(state);
    assertThat(ToolOutputViewer.close(state))
        .isEqualTo(new ToolOutputViewer.Open(entries, 1, false, 0, 20));
  }

  @Test void copyUsesSelectedSnapshotAtEitherStage() {
    var entries = List.of(entry("one"), entry("two"));
    var state = new ToolOutputViewer.Open(entries, 1, false, 0, 0);
    assertThat(ToolOutputViewer.copy(state).clipboard()).contains("two");
    assertThat(ToolOutputViewer.copy(state).status())
        .isEqualTo("tool output copied to clipboard");
  }

  @Test void collectsOnlySettledNonemptyOutputsNewestFirstWithNativeTrailingText() {
    ToolUse old = call("read", new ToolStatus.Done(1_000_000_000L, 2_240_000_000L, "old"));
    ToolUse empty = call("bash", new ToolStatus.Done(0, 0, ""));
    ToolUse pending = call("grep", new ToolStatus.Running(0, "working"));
    ToolUse failed = call("write", new ToolStatus.Failed(0, 40_000_000L, "bad"));
    var messages = List.of(new Message(Role.ASSISTANT, "", List.of(), List.of(old)),
        new Message(Role.ASSISTANT, "", List.of(), List.of(empty, pending, failed)));
    var entries = ToolOutputViewer.collect(messages,
        call -> new ToolOutputViewer.Metadata(call.name().value().toUpperCase(), "detail"));
    assertThat(entries).extracting(ToolOutputViewer.Entry::name)
        .containsExactly("write", "read");
    assertThat(entries.get(0).trailing()).isEqualTo("failed · 3 B");
    assertThat(entries.get(1).trailing()).isEqualTo("ok · 1.2s · 3 B");
  }

  private static ToolOutputViewer.Entry entry(String output) {
    ToolUse call = call("read", new ToolStatus.Done(output));
    return new ToolOutputViewer.Entry("read", "Read", "detail", "ok", output, false, call);
  }

  private static ToolUse call(String name, ToolStatus status) {
    return new ToolUse(new ToolCallId(name + "-id"), new ToolName(name), Map.of(), status);
  }
}
