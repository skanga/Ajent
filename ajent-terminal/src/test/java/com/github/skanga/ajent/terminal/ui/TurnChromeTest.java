package com.github.skanga.ajent.terminal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Role;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class TurnChromeTest {
  private static final ZoneId UTC = ZoneId.of("UTC");

  @Test
  void rendersNativeUserAndAssistantIdentityWithMeasuredMeta() {
    TurnChrome.Header user = TurnChrome.header(new TurnChrome.Config(
        Role.USER, "claude-opus-4-6", Instant.parse("2026-07-19T12:34:00Z"),
        1, Optional.empty(), true, false, 72, UTC)).orElseThrow();
    assertThat(user.glyph()).isEqualTo("❯");
    assertThat(user.label()).isEqualTo("You");
    assertThat(user.tone()).isEqualTo(TurnChrome.SpeakerTone.USER);
    assertThat(user.meta()).isEqualTo("12:34  ·  turn 1  ·  ↺ checkpoint");
    assertThat(user.text()).startsWith("❯ You").endsWith(user.meta() + " ").hasSize(72);

    TurnChrome.Header assistant = TurnChrome.header(new TurnChrome.Config(
        Role.ASSISTANT, "claude-opus-4-6", Instant.parse("2026-07-19T12:34:00Z"),
        1, Optional.of(Duration.ofMillis(4_240)), false, false, 72, UTC)).orElseThrow();
    assertThat(assistant.glyph()).isEqualTo("✦");
    assertThat(assistant.label()).isEqualTo("Opus 4.6");
    assertThat(assistant.tone()).isEqualTo(TurnChrome.SpeakerTone.OPUS);
    assertThat(assistant.meta()).isEqualTo("12:34  ·  4.2s  ·  turn 1");
    assertThat(assistant.text()).startsWith("✦ Opus 4.6").endsWith(assistant.meta() + " ");
  }

  @Test
  void normalizesKnownAndLocalModelsAndDurationBoundaries() {
    assertThat(label("claude-sonnet-4-20250514")).isEqualTo("Sonnet 4");
    assertThat(label("claude-haiku-3-5")).isEqualTo("Haiku 3.5");
    assertThat(label("openai/qwen2.5-coder:7b")).isEqualTo("Qwen2.5 Coder 7b");
    assertThat(tone("claude-sonnet-4-20250514")).isEqualTo(TurnChrome.SpeakerTone.SONNET);
    assertThat(tone("claude-haiku-3-5")).isEqualTo(TurnChrome.SpeakerTone.HAIKU);
    assertThat(tone("openai/qwen2.5-coder:7b")).isEqualTo(TurnChrome.SpeakerTone.FALLBACK);

    assertThat(meta(Duration.ofMillis(99))).isEqualTo("12:34  ·  turn 2");
    assertThat(meta(Duration.ofMillis(100))).isEqualTo("12:34  ·  100ms  ·  turn 2");
    assertThat(meta(Duration.ofSeconds(42))).isEqualTo("12:34  ·  42.0s  ·  turn 2");
    assertThat(meta(Duration.ofSeconds(130))).isEqualTo("12:34  ·  2m10s  ·  turn 2");
  }

  @Test
  void continuationSuppressesHeaderAndNarrowHeaderNeverOverflows() {
    assertThat(TurnChrome.header(new TurnChrome.Config(
        Role.ASSISTANT, "claude-opus-4-6", Instant.EPOCH, 1, Optional.empty(),
        false, true, 80, UTC))).isEmpty();
    TurnChrome.Header narrow = TurnChrome.header(new TurnChrome.Config(
        Role.ASSISTANT, "claude-opus-4-6", Instant.parse("2026-07-19T12:34:00Z"),
        123, Optional.of(Duration.ofMinutes(2)), false, false, 12, UTC)).orElseThrow();
    assertThat(narrow.text()).hasSizeLessThanOrEqualTo(12).startsWith("✦");
    assertThat(TurnChrome.rail("body")).isEqualTo("┃  body");
  }

  private static String label(String model) {
    return TurnChrome.header(new TurnChrome.Config(Role.ASSISTANT, model, Instant.EPOCH,
        0, Optional.empty(), false, false, 80, UTC)).orElseThrow().label();
  }

  private static TurnChrome.SpeakerTone tone(String model) {
    return TurnChrome.header(new TurnChrome.Config(Role.ASSISTANT, model, Instant.EPOCH,
        0, Optional.empty(), false, false, 80, UTC)).orElseThrow().tone();
  }

  private static String meta(Duration duration) {
    return TurnChrome.header(new TurnChrome.Config(Role.ASSISTANT, "claude-opus-4-6",
        Instant.parse("2026-07-19T12:34:00Z"), 2, Optional.of(duration), false, false,
        80, UTC)).orElseThrow().meta();
  }
}
