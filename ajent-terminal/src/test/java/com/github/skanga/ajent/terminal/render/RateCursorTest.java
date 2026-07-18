package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class RateCursorTest {
  private static final double FRAME = 1.0 / 60.0;

  @Test void tracksFastStreamWithoutGrowingLagOrTeleporting() {
    var cursor = new RateCursor(90, 0.3);
    double target = 0, previous = 0, maximumAdvance = 0;
    for (int frame = 0; frame < 20 * 60; frame++) {
      target += 2000 * FRAME;
      double position = cursor.tick(target, FRAME);
      maximumAdvance = Math.max(maximumAdvance, position - previous);
      previous = position;
    }
    assertThat(target - cursor.position()).isLessThanOrEqualTo(2000 * 0.3 * 4);
    assertThat(maximumAdvance).isLessThanOrEqualTo(2000 * FRAME * 8);
  }

  @Test void slowStreamGlidesAtTheFloorWithoutRunningPastWire() {
    var cursor = new RateCursor(90, 0.3);
    double target = 0, previous = 0, peak = 0;
    for (int frame = 0; frame < 10 * 60; frame++) {
      target += 40 * FRAME;
      double position = cursor.tick(target, FRAME);
      assertThat(position).isLessThanOrEqualTo(target + 1e-6);
      peak = Math.max(peak, (position - previous) / FRAME);
      previous = position;
    }
    assertThat(peak).isLessThanOrEqualTo(90 * 1.8 * 1.05);
  }

  @Test void finalizeRampFlushesLargeBacklogByDeadline() {
    var cursor = new RateCursor(90, 0.3);
    double target = 30_000;
    cursor.tick(target, FRAME);
    assertThat(target - cursor.position()).isGreaterThan(1000);
    double remaining = 0.2;
    int frames = 0;
    while (cursor.position() < target - 1 && frames < 60) {
      cursor.setDeadline(remaining);
      cursor.tick(target, FRAME);
      remaining -= FRAME;
      frames++;
    }
    assertThat(cursor.position()).isGreaterThanOrEqualTo(target - 1);
    assertThat(frames * FRAME).isLessThanOrEqualTo(0.25);
  }

  @Test void fatChunkSlidesAcrossFramesInsteadOfTeleporting() {
    var cursor = new RateCursor(90, 0.3);
    double target = 0;
    for (int frame = 0; frame < 60; frame++) {
      target += 200 * FRAME;
      cursor.tick(target, FRAME);
    }
    target += 500;
    double before = cursor.position();
    cursor.tick(target, FRAME);
    double firstAdvance = cursor.position() - before;
    int frames = 1;
    while (cursor.position() < target - 1 && frames < 600) {
      cursor.tick(target, FRAME);
      frames++;
    }
    assertThat(firstAdvance).isLessThan(120);
    assertThat(frames).isGreaterThanOrEqualTo(5);
    assertThat(frames * FRAME).isLessThanOrEqualTo(1.5);
  }

  @Test void supportsPacingSmoothingFloorsResetAndNonpositiveElapsedEdges() {
    var cursor = new RateCursor(-1, -1);
    cursor.setPacing(120, 0.5);
    cursor.setPacing(-1, -1);
    cursor.setSmoothing(0, 2);
    cursor.setSmoothing(-1, -1);
    cursor.setPosition(-3);
    assertThat(cursor.position()).isZero();
    cursor.advanceFloor(5);
    cursor.advanceFloor(2);
    assertThat(cursor.position()).isEqualTo(5);
    assertThat(cursor.tick(100, -1)).isEqualTo(5);
    cursor.setDeadline(0);
    assertThat(cursor.ramping()).isTrue();
    cursor.tick(100, 0.01);
    cursor.clearDeadline();
    assertThat(cursor.ramping()).isFalse();
    cursor.tick(1, 0.01);
    assertThat(cursor.position()).isEqualTo(1);
    cursor.reset();
    assertThat(cursor.position()).isZero();
  }
}
