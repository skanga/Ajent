package com.github.skanga.ajent.terminal.input;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Incremental port of Maya's terminal input FSM. */
public final class TerminalInputDecoder {
  private static final byte[] PASTE_END = {0x1b, '[', '2', '0', '1', '~'};
  private static final int DEFAULT_MAX_CSI_BYTES = 256;
  private static final int DEFAULT_MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
  private static final long ESCAPE_TIMEOUT_NANOS = 50_000_000L;
  private enum State { GROUND, ESCAPE, CSI, SS3, OSC, UTF8, BRACKETED_PASTE }

  private State state = State.GROUND;
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private final int maxCsiBytes;
  private final int maxPayloadBytes;
  private final LongSupplier nanoTime;
  private long escapeStartedNanos;
  private int utf8CodePoint;
  private int utf8Remaining;
  private boolean kittyClipboardActive;
  private String kittyMime = "";
  private byte[] kittyData = new byte[0];

  public TerminalInputDecoder() {
    this(DEFAULT_MAX_CSI_BYTES, DEFAULT_MAX_PAYLOAD_BYTES, System::nanoTime);
  }

  TerminalInputDecoder(int maxCsiBytes, int maxPayloadBytes) {
    this(maxCsiBytes, maxPayloadBytes, System::nanoTime);
  }

  TerminalInputDecoder(int maxCsiBytes, int maxPayloadBytes, LongSupplier nanoTime) {
    if (maxCsiBytes < 1 || maxPayloadBytes < 1) {
      throw new IllegalArgumentException("input buffer limits must be positive");
    }
    this.maxCsiBytes = maxCsiBytes;
    this.maxPayloadBytes = maxPayloadBytes;
    this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
  }

  public List<TerminalEvent> feed(byte[] input) {
    var events = new ArrayList<TerminalEvent>();
    for (int index = 0; index < input.length;) {
      boolean reprocess = accept(Byte.toUnsignedInt(input[index]), events);
      if (!reprocess) index++;
    }
    return List.copyOf(events);
  }

  public List<TerminalEvent> feedUtf8(String input) {
    return feed(input.getBytes(StandardCharsets.UTF_8));
  }

  public List<TerminalEvent> flushEscape() {
    if (state != State.ESCAPE) return List.of();
    if (nanoTime.getAsLong() - escapeStartedNanos < ESCAPE_TIMEOUT_NANOS) return List.of();
    state = State.GROUND;
    buffer.reset();
    return List.of(key(TerminalKey.SpecialKey.ESCAPE, TerminalKey.Modifiers.NONE));
  }

  public boolean hasPending() {
    return state != State.GROUND;
  }

  /** Whether a lone ESC is still waiting to be distinguished from an escape sequence. */
  public boolean hasPendingEscape() {
    return state == State.ESCAPE;
  }

  public void reset() {
    state = State.GROUND;
    buffer.reset();
    utf8CodePoint = 0;
    utf8Remaining = 0;
    abortKittyClipboard();
  }

  private boolean accept(int value, List<TerminalEvent> events) {
    return switch (state) {
      case GROUND -> { ground(value, events); yield false; }
      case ESCAPE -> { escape(value, events); yield false; }
      case CSI -> { sequence(value, events, true); yield false; }
      case SS3 -> { sequence(value, events, false); yield false; }
      case OSC -> { osc(value, events); yield false; }
      case UTF8 -> utf8(value, events);
      case BRACKETED_PASTE -> paste(value, events);
    };
  }

  private void ground(int value, List<TerminalEvent> events) {
    if (value == 0x1b) {
      state = State.ESCAPE;
      buffer.reset();
      buffer.write(value);
      escapeStartedNanos = nanoTime.getAsLong();
    } else if (value == '\r' || value == '\n') {
      events.add(key(TerminalKey.SpecialKey.ENTER, TerminalKey.Modifiers.NONE));
    } else if (value == '\t') {
      events.add(key(TerminalKey.SpecialKey.TAB, TerminalKey.Modifiers.NONE));
    } else if (value == 0x7f || value == 0x08) {
      events.add(key(TerminalKey.SpecialKey.BACKSPACE,
          value == 0x08 ? new TerminalKey.Modifiers(true, false, false)
              : TerminalKey.Modifiers.NONE));
    } else if (value == 0x1f) {
      events.add(character('/', new TerminalKey.Modifiers(true, false, false)));
    } else if (value < 0x20) {
      events.add(character('a' + value - 1, new TerminalKey.Modifiers(true, false, false)));
    } else if (value < 0x80) {
      events.add(character(value, TerminalKey.Modifiers.NONE));
    } else if ((value & 0xe0) == 0xc0) {
      startUtf8(value & 0x1f, 1, value);
    } else if ((value & 0xf0) == 0xe0) {
      startUtf8(value & 0x0f, 2, value);
    } else if ((value & 0xf8) == 0xf0) {
      startUtf8(value & 0x07, 3, value);
    } else {
      events.add(character(value, TerminalKey.Modifiers.NONE));
    }
  }

  private void escape(int value, List<TerminalEvent> events) {
    buffer.write(value);
    if (value == '[') {
      state = State.CSI;
    } else if (value == 'O') {
      state = State.SS3;
    } else if (value == ']') {
      state = State.OSC;
    } else {
      TerminalKey.Modifiers alt = new TerminalKey.Modifiers(false, true, false);
      if (value == '\r' || value == '\n') events.add(key(TerminalKey.SpecialKey.ENTER, alt));
      else if (value == 0x7f) events.add(key(TerminalKey.SpecialKey.BACKSPACE, alt));
      else if (value < 0x20) events.add(character(
          'a' + value - 1, new TerminalKey.Modifiers(true, true, false)));
      else events.add(character(value, alt));
      state = State.GROUND;
      buffer.reset();
    }
  }

  private void sequence(int value, List<TerminalEvent> events, boolean csi) {
    if (csi && buffer.size() >= maxCsiBytes) {
      state = State.GROUND;
      buffer.reset();
      return;
    }
    buffer.write(value);
    if (value < 0x40 || value > 0x7e) return;
    if (csi) parseCsi(events); else parseSs3(value, events);
    if (state != State.BRACKETED_PASTE) state = State.GROUND;
    if (state != State.BRACKETED_PASTE) buffer.reset();
  }

  private void parseSs3(int value, List<TerminalEvent> events) {
    TerminalKey.SpecialKey key = switch (value) {
      case 'A' -> TerminalKey.SpecialKey.UP;
      case 'B' -> TerminalKey.SpecialKey.DOWN;
      case 'C' -> TerminalKey.SpecialKey.RIGHT;
      case 'D' -> TerminalKey.SpecialKey.LEFT;
      case 'H' -> TerminalKey.SpecialKey.HOME;
      case 'F' -> TerminalKey.SpecialKey.END;
      case 'P' -> TerminalKey.SpecialKey.F1;
      case 'Q' -> TerminalKey.SpecialKey.F2;
      case 'R' -> TerminalKey.SpecialKey.F3;
      case 'S' -> TerminalKey.SpecialKey.F4;
      default -> null;
    };
    if (key != null) events.add(key(key, TerminalKey.Modifiers.NONE));
  }

  private void parseCsi(List<TerminalEvent> events) {
    byte[] raw = buffer.toByteArray();
    char finalByte = (char) raw[raw.length - 1];
    String parameters = new String(raw, 2, raw.length - 3, StandardCharsets.US_ASCII);
    int[] values = parameters(parameters);
    if (finalByte == '~' && values.length > 0) {
      if (values[0] == 200) {
        state = State.BRACKETED_PASTE;
        buffer.reset();
        return;
      }
      if (values[0] == 27 && values.length >= 3) {
        emitCodePoint(values[2], modifiers(values[1]), events);
        return;
      }
      TerminalKey.SpecialKey special = tilde(values[0]);
      if (special != null) events.add(key(special,
          modifiers(values.length > 1 ? values[1] : 1)));
      return;
    }
    if (finalByte == 'I') { events.add(new TerminalEvent.Focus(true)); return; }
    if (finalByte == 'O') { events.add(new TerminalEvent.Focus(false)); return; }
    if (parameters.startsWith("<") && (finalByte == 'M' || finalByte == 'm')) {
      mouse(parameters.substring(1), finalByte, events);
      return;
    }
    TerminalKey.SpecialKey navigation = switch (finalByte) {
      case 'A' -> TerminalKey.SpecialKey.UP;
      case 'B' -> TerminalKey.SpecialKey.DOWN;
      case 'C' -> TerminalKey.SpecialKey.RIGHT;
      case 'D' -> TerminalKey.SpecialKey.LEFT;
      case 'H' -> TerminalKey.SpecialKey.HOME;
      case 'F' -> TerminalKey.SpecialKey.END;
      default -> null;
    };
    if (navigation != null) {
      events.add(key(navigation, modifiers(values.length > 1 ? values[1] : 1)));
      return;
    }
    if (finalByte == 'Z') {
      events.add(key(TerminalKey.SpecialKey.BACK_TAB,
          new TerminalKey.Modifiers(false, false, true)));
      return;
    }
    if (finalByte == 'u' && values.length > 0) {
      emitCodePoint(values[0], modifiers(values.length > 1 ? values[1] : 1), events);
      return;
    }
    events.add(character('?', TerminalKey.Modifiers.NONE));
  }

  private static void emitCodePoint(
      int codePoint, TerminalKey.Modifiers modifiers, List<TerminalEvent> events) {
    TerminalKey.SpecialKey special = switch (codePoint) {
      case 9 -> TerminalKey.SpecialKey.TAB;
      case 13 -> TerminalKey.SpecialKey.ENTER;
      case 27 -> TerminalKey.SpecialKey.ESCAPE;
      case 127 -> TerminalKey.SpecialKey.BACKSPACE;
      case 57344, 57345, 57346, 57347, 57348, 57349, 57350, 57351, 57352, 57353,
           57354, 57355 -> TerminalKey.SpecialKey.values()[
              TerminalKey.SpecialKey.F1.ordinal() + codePoint - 57344];
      case 57356 -> TerminalKey.SpecialKey.UP;
      case 57357 -> TerminalKey.SpecialKey.DOWN;
      case 57358 -> TerminalKey.SpecialKey.LEFT;
      case 57359 -> TerminalKey.SpecialKey.RIGHT;
      case 57360 -> TerminalKey.SpecialKey.HOME;
      case 57361 -> TerminalKey.SpecialKey.END;
      case 57362 -> TerminalKey.SpecialKey.PAGE_UP;
      case 57363 -> TerminalKey.SpecialKey.PAGE_DOWN;
      case 57364 -> TerminalKey.SpecialKey.INSERT;
      case 57365 -> TerminalKey.SpecialKey.DELETE;
      default -> null;
    };
    if (special != null) events.add(key(special, modifiers));
    else if (codePoint >= 32 && Character.isValidCodePoint(codePoint)) {
      events.add(character(codePoint, modifiers));
    }
  }

  private static void mouse(String parameters, char finalByte, List<TerminalEvent> events) {
    int[] values = parameters(parameters);
    if (values.length < 3) return;
    int code = values[0];
    TerminalKey.Modifiers mods = new TerminalKey.Modifiers(
        (code & 16) != 0, (code & 8) != 0, (code & 4) != 0);
    TerminalEvent.Button button = switch (code & 0x43) {
      case 0 -> TerminalEvent.Button.LEFT;
      case 1 -> TerminalEvent.Button.MIDDLE;
      case 2 -> TerminalEvent.Button.RIGHT;
      case 64 -> TerminalEvent.Button.SCROLL_UP;
      case 65 -> TerminalEvent.Button.SCROLL_DOWN;
      case 66 -> TerminalEvent.Button.SCROLL_LEFT;
      case 67 -> TerminalEvent.Button.SCROLL_RIGHT;
      default -> TerminalEvent.Button.NONE;
    };
    TerminalEvent.Kind kind = finalByte == 'm' ? TerminalEvent.Kind.RELEASE
        : (code & 32) != 0 ? TerminalEvent.Kind.MOVE : TerminalEvent.Kind.PRESS;
    events.add(new TerminalEvent.Mouse(button, kind, values[1], values[2], mods));
  }

  private boolean paste(int value, List<TerminalEvent> events) {
    if (buffer.size() >= maxPayloadBytes) {
      events.add(new TerminalEvent.Paste(buffer.toByteArray()));
      state = State.GROUND;
      buffer.reset();
      return true;
    }
    buffer.write(value);
    byte[] content = buffer.toByteArray();
    if (content.length < PASTE_END.length) return false;
    int offset = content.length - PASTE_END.length;
    for (int index = 0; index < PASTE_END.length; index++) {
      if (content[offset + index] != PASTE_END[index]) return false;
    }
    events.add(new TerminalEvent.Paste(java.util.Arrays.copyOf(content, offset)));
    state = State.GROUND;
    buffer.reset();
    return false;
  }

  private void startUtf8(int prefix, int remaining, int raw) {
    utf8CodePoint = prefix;
    utf8Remaining = remaining;
    buffer.reset();
    buffer.write(raw);
    state = State.UTF8;
  }

  private boolean utf8(int value, List<TerminalEvent> events) {
    if ((value & 0xc0) != 0x80) {
      state = State.GROUND;
      buffer.reset();
      utf8CodePoint = 0;
      utf8Remaining = 0;
      return true;
    }
    buffer.write(value);
    utf8CodePoint = (utf8CodePoint << 6) | (value & 0x3f);
    if (--utf8Remaining == 0) {
      events.add(character(utf8CodePoint, TerminalKey.Modifiers.NONE));
      state = State.GROUND;
      buffer.reset();
    }
    return false;
  }

  private void osc(int value, List<TerminalEvent> events) {
    if (buffer.size() >= maxPayloadBytes) {
      state = State.GROUND;
      buffer.reset();
      return;
    }
    buffer.write(value);
    byte[] raw = buffer.toByteArray();
    boolean bell = value == 0x07;
    boolean stringTerminator = raw.length >= 2
        && raw[raw.length - 2] == 0x1b && value == '\\';
    if (!bell && !stringTerminator) return;
    int end = raw.length - (bell ? 1 : 2);
    String body = new String(raw, 2, end - 2, StandardCharsets.US_ASCII);
    parseOsc(body, events);
    state = State.GROUND;
    buffer.reset();
  }

  private void parseOsc(String body, List<TerminalEvent> events) {
    if (body.startsWith("5522;")) {
      parseKittyClipboard(body.substring(5), events);
      return;
    }
    if (!body.startsWith("52;")) return;
    int separator = body.indexOf(';', 3);
    if (separator < 0) return;
    String encoded = body.substring(separator + 1);
    if (encoded.isEmpty() || encoded.equals("?")) return;
    byte[] decoded = decodeBase64(encoded);
    if (decoded != null) events.add(new TerminalEvent.Paste(decoded));
  }

  private void parseKittyClipboard(String body, List<TerminalEvent> events) {
    int separator = body.indexOf(';');
    String metadata = separator < 0 ? body : body.substring(0, separator);
    String payload = separator < 0 ? "" : body.substring(separator + 1);
    String type = "";
    String status = "";
    String mimeEncoded = "";
    for (String field : metadata.split(":")) {
      int equals = field.indexOf('=');
      if (equals < 0) continue;
      String key = field.substring(0, equals);
      String fieldValue = field.substring(equals + 1);
      switch (key) {
        case "type" -> type = fieldValue;
        case "status" -> status = fieldValue;
        case "mime" -> mimeEncoded = fieldValue;
        default -> { }
      }
    }
    if (!type.equals("read")) return;
    if (status.equals("OK")) {
      abortKittyClipboard();
      kittyClipboardActive = true;
      return;
    }
    if (!kittyClipboardActive) return;
    if (status.equals("DATA")) {
      byte[] mimeBytes = decodeBase64(mimeEncoded);
      if (mimeBytes == null) { abortKittyClipboard(); return; }
      String mime = new String(mimeBytes, StandardCharsets.UTF_8);
      if (!mime.equals(kittyMime)) {
        if (!kittyMime.isEmpty() && rank(mime) <= rank(kittyMime)) return;
        kittyMime = mime;
        kittyData = new byte[0];
      }
      byte[] chunk = decodeBase64(payload);
      if (chunk == null || kittyData.length + chunk.length > maxPayloadBytes) {
        abortKittyClipboard();
        return;
      }
      byte[] combined = java.util.Arrays.copyOf(kittyData, kittyData.length + chunk.length);
      System.arraycopy(chunk, 0, combined, kittyData.length, chunk.length);
      kittyData = combined;
      return;
    }
    if (status.equals("DONE")) {
      if (kittyData.length > 0) events.add(new TerminalEvent.Paste(kittyData));
      abortKittyClipboard();
      return;
    }
    abortKittyClipboard();
  }

  private void abortKittyClipboard() {
    kittyClipboardActive = false;
    kittyMime = "";
    kittyData = new byte[0];
  }

  private static int rank(String mime) {
    return switch (mime) {
      case "image/png" -> 5;
      case "image/jpeg" -> 4;
      case "image/webp" -> 3;
      case "image/gif" -> 2;
      default -> mime.startsWith("image/") ? 1 : 0;
    };
  }

  private static byte[] decodeBase64(String value) {
    var decoded = new ByteArrayOutputStream(value.length() / 4 * 3 + 3);
    int accumulator = 0;
    int bits = 0;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '=') break;
      if (character == ' ' || character == '\t'
          || character == '\r' || character == '\n') continue;
      int digit;
      if (character >= 'A' && character <= 'Z') digit = character - 'A';
      else if (character >= 'a' && character <= 'z') digit = character - 'a' + 26;
      else if (character >= '0' && character <= '9') digit = character - '0' + 52;
      else if (character == '+') digit = 62;
      else if (character == '/') digit = 63;
      else return null;
      accumulator = (accumulator << 6) | digit;
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        decoded.write((accumulator >> bits) & 0xff);
      }
    }
    return decoded.toByteArray();
  }

  private static int[] parameters(String value) {
    if (value.isEmpty()) return new int[0];
    var result = new java.util.ArrayList<Integer>();
    int current = 0;
    boolean hasDigit = false;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character >= '0' && character <= '9') {
        if (current < 100_000_000) current = current * 10 + character - '0';
        hasDigit = true;
      } else if (character == ';') {
        result.add(hasDigit ? current : 0);
        current = 0;
        hasDigit = false;
      }
    }
    result.add(hasDigit ? current : 0);
    return result.stream().mapToInt(Integer::intValue).toArray();
  }

  private static TerminalKey.Modifiers modifiers(int parameter) {
    int bits = Math.max(1, parameter) - 1;
    return new TerminalKey.Modifiers((bits & 4) != 0, (bits & 2) != 0, (bits & 1) != 0);
  }

  private static TerminalKey.SpecialKey tilde(int code) {
    return switch (code) {
      case 1 -> TerminalKey.SpecialKey.HOME;
      case 2 -> TerminalKey.SpecialKey.INSERT;
      case 3 -> TerminalKey.SpecialKey.DELETE;
      case 4 -> TerminalKey.SpecialKey.END;
      case 5 -> TerminalKey.SpecialKey.PAGE_UP;
      case 6 -> TerminalKey.SpecialKey.PAGE_DOWN;
      case 11 -> TerminalKey.SpecialKey.F1;
      case 12 -> TerminalKey.SpecialKey.F2;
      case 13 -> TerminalKey.SpecialKey.F3;
      case 14 -> TerminalKey.SpecialKey.F4;
      case 15 -> TerminalKey.SpecialKey.F5;
      case 17 -> TerminalKey.SpecialKey.F6;
      case 18 -> TerminalKey.SpecialKey.F7;
      case 19 -> TerminalKey.SpecialKey.F8;
      case 20 -> TerminalKey.SpecialKey.F9;
      case 21 -> TerminalKey.SpecialKey.F10;
      case 23 -> TerminalKey.SpecialKey.F11;
      case 24 -> TerminalKey.SpecialKey.F12;
      default -> null;
    };
  }

  private static TerminalEvent.Key key(
      TerminalKey.SpecialKey key, TerminalKey.Modifiers modifiers) {
    return new TerminalEvent.Key(new TerminalKey(key, modifiers));
  }

  private static TerminalEvent.Key character(
      int codePoint, TerminalKey.Modifiers modifiers) {
    return new TerminalEvent.Key(new TerminalKey(new TerminalKey.CharacterKey(codePoint), modifiers));
  }
}
