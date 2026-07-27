package com.github.skanga.ajent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AjentDebugLogTest {
  @Test void appendsNativeTimestampedLinesAndNeverThrows(@TempDir Path root) throws Exception {
    Path log = root.resolve("debug.log");
    AjentDebugLog.write(log.toString(),
        LocalDateTime.of(2026, 7, 18, 1, 2, 3, 4_000_000), "rag.expand_n.env", "bad number");
    AjentDebugLog.write(log.toString(),
        LocalDateTime.of(2026, 7, 18, 1, 2, 4, 5_000_000), "mcp.timeout", "failed");

    assertThat(Files.readString(log)).isEqualTo(
        "2026-07-18 01:02:03.004 [rag.expand_n.env] bad number\n"
            + "2026-07-18 01:02:04.005 [mcp.timeout] failed\n");
    AjentDebugLog.write("\0invalid", LocalDateTime.now(), "where", "ignored");
    AjentDebugLog.write("", LocalDateTime.now(), "where", "ignored");
  }
}
