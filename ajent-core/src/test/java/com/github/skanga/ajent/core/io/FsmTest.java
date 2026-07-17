package com.github.skanga.ajent.core.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FsmTest {
  @Fsm.TransitionsTo({B.class}) private static final class A implements Fsm.State {}
  @Fsm.TransitionsTo({C.class}) private static final class B implements Fsm.State {}
  @Fsm.TransitionsTo({D.class}) private static final class C implements Fsm.State {}
  private static final class D implements Fsm.State {}
  @Fsm.TransitionsTo({B.class, C.class}) private static final class R implements Fsm.State {}
  private static final class NotAState {}

  @Test
  void edgeLegalityComesOnlyFromEachStatesDeclaredSuccessors() {
    assertThat(Fsm.isLegalEdge(A.class, B.class)).isTrue();
    assertThat(Fsm.isLegalEdge(B.class, C.class)).isTrue();
    assertThat(Fsm.isLegalEdge(C.class, D.class)).isTrue();
    assertThat(Fsm.isLegalEdge(R.class, B.class)).isTrue();
    assertThat(Fsm.isLegalEdge(R.class, C.class)).isTrue();
    assertThat(Fsm.isLegalEdge(A.class, C.class)).isFalse();
    assertThat(Fsm.isLegalEdge(A.class, D.class)).isFalse();
    assertThat(Fsm.isLegalEdge(B.class, A.class)).isFalse();
    assertThat(Fsm.isLegalEdge(A.class, A.class)).isFalse();
    assertThat(Fsm.isLegalEdge(D.class, A.class)).isFalse();
    assertThat(Fsm.isLegalEdge(R.class, D.class)).isFalse();
  }

  @Test
  void stateTraitAndTransitionGuardRejectInvalidTypesAndEdges() {
    assertThat(Fsm.isState(A.class)).isTrue();
    assertThat(Fsm.isState(D.class)).isTrue();
    assertThat(Fsm.isState(NotAState.class)).isFalse();
    Fsm.requireLegalEdge(A.class, B.class);
    assertThatThrownBy(() -> Fsm.requireLegalEdge(A.class, D.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(A.class.getName()).hasMessageContaining(D.class.getName());
  }

  @Test
  void ownedCapabilityCleansUpExactlyOnceOnFailureButNotAfterTransfer() {
    List<String> log = new ArrayList<>();
    try (var token = new Fsm.CapabilityToken<>("conn-ok", value -> log.add("~" + value))) {
      assertThat(token.transfer()).isEqualTo("conn-ok");
    }
    assertThat(log).isEmpty();

    try (var ignored = new Fsm.CapabilityToken<>("conn-fail", value -> log.add("~" + value))) {
      // Dropping the token models a failed transition.
    }
    assertThat(log).containsExactly("~conn-fail");
  }

  @Test
  void capabilityCannotBeConsumedOrClosedTwice() {
    List<String> log = new ArrayList<>();
    var token = new Fsm.CapabilityToken<>("p", value -> log.add("~" + value));
    assertThat(token.transfer()).isEqualTo("p");
    assertThatThrownBy(token::transfer).isInstanceOf(IllegalStateException.class);
    token.close();
    token.close();
    assertThat(log).isEmpty();
  }
}
