package com.github.skanga.ajent.parity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class AnsiViewportTest {
  @Test
  void appliesCursorMovementEraseAndSaveRestore() {
    var viewport = new AnsiViewport(8, 3);

    viewport.feed("alpha\r\n12345\u001b[2DXY\u001b7\u001b[1;1HZ\u001b8!\u001b[K");

    assertThat(viewport.lines()).containsExactly("Zlpha", "123XY!", "");
  }

  @Test
  void tracksAutowrapScrollbackWideCellsAndOsc() {
    var viewport = new AnsiViewport(4, 2);

    viewport.feed("ab界x\r\nlast\u001b]0;ignored\u0007\r\nnext");

    assertThat(viewport.scrollback()).containsExactly("ab界", "x");
    assertThat(viewport.lines()).containsExactly("last", "next");
  }

  @Test
  void honorsPrivateAutowrapAndDisplayScrollbackErase() {
    var viewport = new AnsiViewport(3, 2);

    viewport.feed("abc\u001b[?7lZ\u001b[?7hQ\r\nR\u001b[3J");

    assertThat(viewport.scrollback()).isEmpty();
    assertThat(viewport.lines()).containsExactly("Q", "R");
  }
}
