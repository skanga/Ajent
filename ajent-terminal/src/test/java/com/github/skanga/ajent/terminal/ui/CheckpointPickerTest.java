package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.CheckpointId;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CheckpointPickerTest {
  @Test void buildsOldestFirstUserTurnEntriesAndOpensOnNewest() {
    var messages = List.of(user("one", "first", Optional.of(new CheckpointId("one"))),
        new Message(Role.ASSISTANT, "answer", List.of(), List.of()),
        user("two", "no checkpoint", Optional.empty()),
        user("three", "  third\nrest", Optional.of(new CheckpointId("three"))));
    List<CheckpointPicker.Entry> entries = CheckpointPicker.entries(messages);
    assertThat(entries).extracting(CheckpointPicker.Entry::turn).containsExactly(1, 3);
    assertThat(entries).extracting(CheckpointPicker.Entry::preview).containsExactly("first", "third");
    assertThat(CheckpointPicker.open(entries)).isEqualTo(new CheckpointPicker.Open(entries, 1));
  }

  @Test void wrapsMovementAndAppliesReadyFailedDiffsWithoutMovingCursor() {
    var entries = CheckpointPicker.entries(List.of(
        user("one", "first", Optional.of(new CheckpointId("one"))),
        user("two", "second", Optional.of(new CheckpointId("two")))));
    CheckpointPicker.State state = CheckpointPicker.open(entries);
    state = CheckpointPicker.move(state, 1);
    assertThat(((CheckpointPicker.Open) state).index()).isZero();
    state = CheckpointPicker.diff(state, 0, Optional.of(new int[] {2, 3, 4}));
    assertThat(CheckpointPicker.selected(state).orElseThrow())
        .extracting(CheckpointPicker.Entry::diffState, CheckpointPicker.Entry::filesChanged,
            CheckpointPicker.Entry::insertions, CheckpointPicker.Entry::deletions)
        .containsExactly(CheckpointPicker.DiffState.READY, 2, 3, 4);
    state = CheckpointPicker.diff(state, 1, Optional.empty());
    assertThat(((CheckpointPicker.Open) state).entries().get(1).diffState())
        .isEqualTo(CheckpointPicker.DiffState.FAILED);
    assertThat(CheckpointPicker.diff(state, 9, Optional.empty())).isEqualTo(state);
  }

  @Test void previewIsUtf8SafeEmptyAwareAndSelectionHandlesClosed() {
    assertThat(CheckpointPicker.preview("   \nrest")).isEqualTo("(no prompt text)");
    String preview = CheckpointPicker.preview("\uD83D\uDE42".repeat(30));
    assertThat(preview).endsWith("\u2026");
    assertThat(preview.substring(0, preview.length() - 1).codePoints()).hasSize(24);
    assertThat(CheckpointPicker.open(List.of())).isEqualTo(new CheckpointPicker.Closed());
    assertThat(CheckpointPicker.move(new CheckpointPicker.Closed(), 1))
        .isEqualTo(new CheckpointPicker.Closed());
    assertThat(CheckpointPicker.selected(new CheckpointPicker.Closed())).isEmpty();
    assertThat(CheckpointPicker.close(new CheckpointPicker.Open(List.of(), 0)))
        .isEqualTo(new CheckpointPicker.Closed());
  }

  private static Message user(String id, String text, Optional<CheckpointId> checkpoint) {
    return new Message(new MessageId(id), Role.USER, text, List.of(), List.of(), "", "", List.of(),
        Instant.EPOCH, checkpoint, Optional.empty(), false);
  }
}
