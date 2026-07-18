package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.ThreadId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ThreadPickerTest {
  private static final List<ThreadPicker.Entry> THREADS = List.of(
      entry("new", "Newest"), entry("current", ""), entry("old", "Oldest"));

  @Test void opensAtCurrentWrapsAndDisplaysUntitled() {
    PickerState.OneAxis state = ThreadPicker.open(THREADS, new ThreadId("current"));
    assertThat(state).isEqualTo(new PickerState.OpenAt(1, ""));
    assertThat(THREADS.get(1).displayTitle()).isEqualTo("(untitled)");
    assertThat(ThreadPicker.move(state, THREADS, 2))
        .isEqualTo(new PickerState.OpenAt(0, ""));
    assertThat(ThreadPicker.move(new PickerState.OpenAt(0, ""), THREADS, -1))
        .isEqualTo(new PickerState.OpenAt(2, ""));
  }

  @Test void jumpsByNativeFourteenRowsAndClamps() {
    List<ThreadPicker.Entry> many = java.util.stream.IntStream.range(0, 30)
        .mapToObj(index -> entry("t" + index, "Thread " + index)).toList();
    PickerState.OneAxis state = new PickerState.OpenAt(20, "");
    assertThat(ThreadPicker.jump(state, many, ThreadPicker.Jump.PAGE_UP))
        .isEqualTo(new PickerState.OpenAt(6, ""));
    assertThat(ThreadPicker.jump(state, many, ThreadPicker.Jump.PAGE_DOWN))
        .isEqualTo(new PickerState.OpenAt(29, ""));
    assertThat(ThreadPicker.jump(state, many, ThreadPicker.Jump.HOME))
        .isEqualTo(new PickerState.OpenAt(0, ""));
    assertThat(ThreadPicker.jump(state, many, ThreadPicker.Jump.END))
        .isEqualTo(new PickerState.OpenAt(29, ""));
  }

  @Test void selectionClosesAndEmptyOrClosedStatesSelectNothing() {
    assertThat(ThreadPicker.select(new PickerState.OpenAt(2, ""), THREADS).entry())
        .contains(THREADS.get(2));
    assertThat(ThreadPicker.select(new PickerState.OpenAt(3, ""), THREADS).entry()).isEmpty();
    assertThat(ThreadPicker.select(new PickerState.Closed(), THREADS).entry()).isEmpty();
    assertThat(ThreadPicker.move(new PickerState.Closed(), THREADS, 1))
        .isEqualTo(new PickerState.Closed());
    assertThat(ThreadPicker.jump(new PickerState.OpenAt(), List.of(), ThreadPicker.Jump.END))
        .isEqualTo(new PickerState.OpenAt());
    assertThat(ThreadPicker.close(new PickerState.OpenAt())).isEqualTo(new PickerState.Closed());
  }

  private static ThreadPicker.Entry entry(String id, String title) {
    return new ThreadPicker.Entry(new ThreadId(id), title, Instant.EPOCH);
  }
}
