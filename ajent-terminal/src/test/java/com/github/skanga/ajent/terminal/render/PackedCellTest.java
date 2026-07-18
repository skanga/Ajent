package com.github.skanga.ajent.terminal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

final class PackedCellTest {
  @Test void packsFieldsIntoTheExactNativeBitLayout() {
    var cell = new PackedCell(0x1f642, 0xabcd, 0x7e, 2);
    assertThat(cell.pack()).isEqualTo(0x027e_abcd_0001_f642L);
    assertThat(PackedCell.unpack(cell.pack())).isEqualTo(cell);
  }

  @Test void portsNativeCharacterAndStyleRoundTrips() {
    assertThat(PackedCell.unpack(new PackedCell(0x2603, 0, 0, 0).pack()).character())
        .isEqualTo(0x2603);
    var styled = PackedCell.unpack(new PackedCell('A', 42, 0, 0).pack());
    assertThat(styled.character()).isEqualTo('A');
    assertThat(styled.styleId()).isEqualTo(42);
  }

  @Test void defaultCellIsAnOrdinarySpace() {
    assertThat(PackedCell.BLANK).isEqualTo(new PackedCell(' ', 0, 0, 0));
    assertThat(PackedCell.unpack(PackedCell.BLANK.pack())).isEqualTo(PackedCell.BLANK);
  }

  @Test void exposesWidePairMarkersUsedByDiffBoundaryRepair() {
    assertThat(new PackedCell('X', 0, 0, 1).isWideLead()).isTrue();
    assertThat(new PackedCell(' ', 0, 0, 2).isWideTrail()).isTrue();
    assertThat(PackedCell.BLANK.isWideLead()).isFalse();
  }

  @Test void rejectsFieldsThatWouldAliasDuringPacking() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PackedCell('A', 0x1_0000, 0, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PackedCell('A', 0, 0x100, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PackedCell('A', 0, 0, 0x100));
  }
}
