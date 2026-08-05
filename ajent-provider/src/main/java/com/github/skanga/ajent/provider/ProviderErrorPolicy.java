package com.github.skanga.ajent.provider;

import java.time.Duration;
import java.util.Locale;

/** Ajent's provider error classification, retry caps, and backoff ladders. */
public final class ProviderErrorPolicy {
  public static final int MAX_RETRIES = 6;
  public static final Duration RETRY_DECAY = Duration.ofSeconds(90);
  private static final long[] TRANSIENT_MILLIS = {500, 2_000, 5_000, 12_000, 25_000, 45_000};
  private static final long[] RATE_LIMIT_MILLIS = {3_000, 8_000, 20_000, 40_000, 60_000, 90_000};

  private ProviderErrorPolicy() {}

  public static ErrorClass classify(HttpErrorKind kind, int httpStatus) {
    return switch (kind) {
      case CANCELLED -> ErrorClass.CANCELLED;
      case RESOLVE, CONNECT, TLS, PROTOCOL, SOCKET_HANGUP, TIMEOUT, PEER_CLOSED ->
          ErrorClass.TRANSIENT;
      case STATUS -> classifyHttpStatus(httpStatus);
      case BODY, UNKNOWN -> ErrorClass.TERMINAL;
    };
  }

  public static ErrorClass classifyHttpStatus(int status) {
    if (status == 401 || status == 403) return ErrorClass.AUTH;
    if (status == 429) return ErrorClass.RATE_LIMIT;
    return switch (status) {
      case 408, 502, 503, 504, 529 -> ErrorClass.TRANSIENT;
      default -> ErrorClass.TERMINAL;
    };
  }

  public static ErrorClass classify(String message) {
    String value = message.toLowerCase(Locale.ROOT);
    if (value.contains("cancel")) return ErrorClass.CANCELLED;
    if (containsAny(value, "401", "403", "authentication_error", "invalid api key",
        "not authenticated")) return ErrorClass.AUTH;
    if (containsAny(value, "rate_limit", "429")) return ErrorClass.RATE_LIMIT;
    if (containsAny(value, "overloaded", "overload_error", "502", "503", "504", "529",
        "connection", "timeout", "eof", "broken pipe", "network", "stall"))
      return ErrorClass.TRANSIENT;
    return ErrorClass.TERMINAL;
  }

  public static Duration backoff(ErrorClass kind, int attempt) {
    int index = Math.clamp(attempt, 0, 5);
    long[] table = kind == ErrorClass.RATE_LIMIT ? RATE_LIMIT_MILLIS : TRANSIENT_MILLIS;
    return Duration.ofMillis(table[index]);
  }

  public static Duration backoffWithJitter(ErrorClass kind, int attempt, double factor) {
    if (!Double.isFinite(factor) || factor < 0.80 || factor > 1.20)
      throw new IllegalArgumentException("jitter factor must be in [0.80, 1.20]");
    long millis = (long) (backoff(kind, attempt).toMillis() * factor);
    return Duration.ofMillis(Math.max(100, millis));
  }

  public static int maxRetries(ErrorClass kind, boolean midStream) {
    return switch (kind) {
      case RATE_LIMIT, AUTH -> MAX_RETRIES;
      case TRANSIENT -> midStream ? 4 : MAX_RETRIES;
      case CANCELLED, TERMINAL -> 0;
    };
  }

  private static boolean containsAny(String value, String... needles) {
    for (String needle : needles) if (value.contains(needle)) return true;
    return false;
  }
}
