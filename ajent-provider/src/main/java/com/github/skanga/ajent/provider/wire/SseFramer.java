package com.github.skanga.ajent.provider.wire;

import java.util.Objects;
import java.util.function.Consumer;

/** Incremental Server-Sent Events grouping layered over {@link LineFramer}. */
public final class SseFramer {
  public static final int DEFAULT_DATA_ACCUMULATION_MAX = 4 * 1024 * 1024;

  public record Event(String name, String data) {
    public Event {
      name = Objects.requireNonNull(name, "name");
      data = Objects.requireNonNull(data, "data");
    }
  }

  private final LineFramer lines;
  private final int dataAccumulationMax;
  private final StringBuilder data = new StringBuilder(8 * 1024);
  private String eventName = "";
  private boolean skipEvent;

  public SseFramer() {
    this(LineFramer.DEFAULT_COMPACT_THRESHOLD, DEFAULT_DATA_ACCUMULATION_MAX);
  }

  public SseFramer(int compactThreshold, int dataAccumulationMax) {
    if (dataAccumulationMax < 1) {
      throw new IllegalArgumentException("dataAccumulationMax must be positive");
    }
    lines = new LineFramer(compactThreshold);
    this.dataAccumulationMax = dataAccumulationMax;
  }

  public void feed(byte[] bytes, Consumer<Event> onEvent) {
    Objects.requireNonNull(onEvent, "onEvent");
    lines.feed(bytes, line -> consumeLine(line, onEvent));
  }

  private void consumeLine(String line, Consumer<Event> onEvent) {
    if (line.isEmpty()) {
      if (!skipEvent && (!data.isEmpty() || !eventName.isEmpty())) {
        onEvent.accept(new Event(eventName, data.toString()));
      }
      eventName = "";
      data.setLength(0);
      skipEvent = false;
      return;
    }
    if (skipEvent) return;
    if (line.startsWith("event:")) {
      eventName = valueAfterColon(line, 6);
    } else if (line.startsWith("data:")) {
      String value = valueAfterColon(line, 5);
      int addition = value.length() + (data.isEmpty() ? 0 : 1);
      if (data.length() + addition > dataAccumulationMax) {
        data.setLength(0);
        eventName = "";
        skipEvent = true;
        return;
      }
      if (!data.isEmpty()) data.append('\n');
      data.append(value);
    }
  }

  private static String valueAfterColon(String line, int start) {
    int index = start;
    while (index < line.length() && line.charAt(index) == ' ') index++;
    return line.substring(index);
  }
}
