package com.github.skanga.ajent.terminal.ui;

import com.github.skanga.ajent.domain.ModelCapabilities;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.terminal.ModelLabels;
import com.github.skanga.ajent.terminal.render.UnicodeWidth;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** AgenTTY turn identity, metadata, and left-rail formatting. */
public final class TurnChrome {
  public enum SpeakerTone { USER, OPUS, SONNET, HAIKU, FALLBACK }

  public record Config(
      Role role,
      String modelId,
      Instant timestamp,
      int turnNumber,
      Optional<Duration> elapsed,
      boolean checkpoint,
      boolean continuation,
      int width,
      ZoneId zone) {
    public Config {
      role = Objects.requireNonNull(role, "role");
      modelId = Objects.requireNonNull(modelId, "modelId");
      timestamp = Objects.requireNonNull(timestamp, "timestamp");
      elapsed = Objects.requireNonNull(elapsed, "elapsed");
      zone = Objects.requireNonNull(zone, "zone");
      if (turnNumber < 0 || width < 1) throw new IllegalArgumentException("invalid turn chrome");
      elapsed.ifPresent(value -> {
        if (value.isNegative()) throw new IllegalArgumentException("negative elapsed time");
      });
    }
  }

  public record Header(
      String glyph, String label, SpeakerTone tone, String meta, String text) {
    public Header {
      glyph = Objects.requireNonNull(glyph, "glyph");
      label = Objects.requireNonNull(label, "label");
      tone = Objects.requireNonNull(tone, "tone");
      meta = Objects.requireNonNull(meta, "meta");
      text = Objects.requireNonNull(text, "text");
    }
  }

  private TurnChrome() {}

  public static Optional<Header> header(Config config) {
    Objects.requireNonNull(config, "config");
    if (config.continuation()) return Optional.empty();
    String glyph = config.role() == Role.USER ? "❯" : "✦";
    Speaker speaker = speaker(config.role(), config.modelId());
    String meta = meta(config);
    String left = glyph + " " + speaker.label();
    return Optional.of(new Header(glyph, speaker.label(), speaker.tone(), meta,
        headerLine(left, meta, config.width())));
  }

  /** Native queued-turn header: normal speaker identity with host-supplied queue metadata. */
  public static Header headerWithMeta(Role role, String modelId, String meta, int width) {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(modelId, "modelId");
    Objects.requireNonNull(meta, "meta");
    if (width < 1) throw new IllegalArgumentException("invalid turn chrome width");
    String glyph = role == Role.USER ? "❯" : "✦";
    Speaker speaker = speaker(role, modelId);
    String left = glyph + " " + speaker.label();
    return new Header(glyph, speaker.label(), speaker.tone(), meta,
        headerLine(left, meta, width));
  }

  public static SpeakerTone speakerTone(Role role, String modelId) {
    return speaker(Objects.requireNonNull(role, "role"),
        Objects.requireNonNull(modelId, "modelId")).tone();
  }

  public static String rail(String body) {
    return "┃  " + Objects.requireNonNull(body, "body");
  }

  private record Speaker(SpeakerTone tone, String label) {}

  private static Speaker speaker(Role role, String modelId) {
    if (role == Role.USER) return new Speaker(SpeakerTone.USER, "You");
    ModelCapabilities capabilities = ModelCapabilities.fromId(modelId);
    String family = capabilities.isOpus() ? "Opus"
        : capabilities.isSonnet() ? "Sonnet"
        : capabilities.isHaiku() ? "Haiku" : "";
    SpeakerTone tone = capabilities.isOpus() ? SpeakerTone.OPUS
        : capabilities.isSonnet() ? SpeakerTone.SONNET
        : capabilities.isHaiku() ? SpeakerTone.HAIKU : SpeakerTone.FALLBACK;
    if (family.isEmpty()) return new Speaker(tone, ModelLabels.pretty(modelId));
    if (capabilities.generation() <= 0) return new Speaker(tone, family);
    String label = family + " " + capabilities.generation();
    return new Speaker(tone,
        capabilities.revision() > 0 ? label + "." + capabilities.revision() : label);
  }

  private static String meta(Config config) {
    StringBuilder meta = new StringBuilder(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        .withZone(config.zone()).format(config.timestamp()));
    config.elapsed().filter(value -> value.compareTo(Duration.ofMillis(100)) >= 0)
        .ifPresent(value -> meta.append("  ·  ").append(duration(value)));
    if (config.turnNumber() > 0) meta.append("  ·  turn ").append(config.turnNumber());
    if (config.checkpoint()) meta.append("  ·  ↺ checkpoint");
    return meta.toString();
  }

  private static String duration(Duration duration) {
    double seconds = duration.toNanos() / 1_000_000_000.0;
    if (seconds < 1) return String.format(Locale.ROOT, "%.0fms", seconds * 1_000);
    if (seconds < 60) return String.format(Locale.ROOT, "%.1fs", seconds);
    int minutes = (int) seconds / 60;
    return String.format(Locale.ROOT, "%dm%.0fs", minutes, seconds - minutes * 60);
  }

  private static String headerLine(String left, String meta, int width) {
    int leftWidth = columns(left), metaWidth = columns(meta);
    if (leftWidth + metaWidth < width) {
      return left + " ".repeat(width - leftWidth - metaWidth - 1) + meta + " ";
    }
    if (leftWidth >= width) return truncate(left, width);
    int remaining = width - leftWidth;
    return left + truncate(meta, remaining);
  }

  private static String truncate(String value, int width) {
    if (width <= 0) return "";
    if (columns(value) <= width) return value;
    if (width == 1) return "…";
    StringBuilder output = new StringBuilder();
    int used = 0;
    for (int offset = 0; offset < value.length();) {
      int codePoint = value.codePointAt(offset);
      int cellWidth = UnicodeWidth.of(codePoint);
      if (used + cellWidth > width - 1) break;
      output.appendCodePoint(codePoint);
      used += cellWidth;
      offset += Character.charCount(codePoint);
    }
    return output.append('…').toString();
  }

  private static int columns(String value) {
    return UnicodeWidth.stringWidth(value, UnicodeWidth.Mode.MODERN);
  }
}
