package com.github.skanga.ajent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SessionPhaseTest {
  @Test void portsTheCompleteNativeTransitionMatrix() {
    Set<Edge> legal = Set.of(
        new Edge(SessionPhase.Kind.IDLE, SessionPhase.Kind.IDLE),
        new Edge(SessionPhase.Kind.IDLE, SessionPhase.Kind.STREAMING),
        new Edge(SessionPhase.Kind.STREAMING, SessionPhase.Kind.IDLE),
        new Edge(SessionPhase.Kind.STREAMING, SessionPhase.Kind.AWAITING_PERMISSION),
        new Edge(SessionPhase.Kind.AWAITING_PERMISSION, SessionPhase.Kind.IDLE),
        new Edge(SessionPhase.Kind.AWAITING_PERMISSION, SessionPhase.Kind.EXECUTING_TOOL),
        new Edge(SessionPhase.Kind.EXECUTING_TOOL, SessionPhase.Kind.IDLE),
        new Edge(SessionPhase.Kind.EXECUTING_TOOL, SessionPhase.Kind.STREAMING));

    for (SessionPhase.Kind from : EnumSet.allOf(SessionPhase.Kind.class))
      for (SessionPhase.Kind to : EnumSet.allOf(SessionPhase.Kind.class))
        assertThat(SessionPhase.isLegalTransition(from, to))
            .as("%s -> %s", from, to).isEqualTo(legal.contains(new Edge(from, to)));
  }

  @Test void typedTransitionsPreserveTheExactActiveContext() {
    var cancellation = new CancellationSignal();
    ActiveTurn original = ActiveTurn.start(cancellation, 100)
        .withRetryState(new RetryState.Scheduled())
        .withTransientRetries(2);

    SessionPhase.Streaming streaming = SessionPhase.start(new SessionPhase.Idle(), original);
    SessionPhase.AwaitingPermission permission = SessionPhase.landPermission(streaming);
    SessionPhase.ExecutingTool executing = SessionPhase.executeTool(permission);
    SessionPhase.Streaming resumed = SessionPhase.resumeStream(executing);

    assertThat(streaming.active()).containsSame(original);
    assertThat(permission.context()).isSameAs(original);
    assertThat(executing.context()).isSameAs(original);
    assertThat(resumed.context()).isSameAs(original);
    assertThat(SessionPhase.finish(resumed)).isEqualTo(new SessionPhase.Idle());
  }

  @Test void labelsPredicatesAndAbortMatchNativePhases() {
    ActiveTurn active = ActiveTurn.start(new CancellationSignal(), 1);
    SessionPhase[] phases = {new SessionPhase.Idle(), new SessionPhase.Streaming(active),
        new SessionPhase.AwaitingPermission(active), new SessionPhase.ExecutingTool(active)};
    assertThat(phases).extracting(SessionPhase::label)
        .containsExactly("idle", "streaming", "permission", "working");
    assertThat(phases).extracting(SessionPhase::kind).containsExactly(
        SessionPhase.Kind.IDLE, SessionPhase.Kind.STREAMING,
        SessionPhase.Kind.AWAITING_PERMISSION, SessionPhase.Kind.EXECUTING_TOOL);
    assertThat(phases[0].active()).isEmpty();
    assertThat(phases[1].active()).containsSame(active);
    assertThat(phases[2].active()).containsSame(active);
    assertThat(phases[3].active()).containsSame(active);
    assertThat(SessionPhase.abort(phases[1])).isEqualTo(new SessionPhase.Idle());
    assertThat(SessionPhase.abort(phases[2])).isEqualTo(new SessionPhase.Idle());
    assertThat(SessionPhase.abort(phases[3])).isEqualTo(new SessionPhase.Idle());
    assertThatIllegalArgumentException().isThrownBy(() -> SessionPhase.abort(phases[0]));
  }

  @Test void rejectionAndTerminalToolCompletionReturnIdle() {
    ActiveTurn active = ActiveTurn.start(new CancellationSignal(), 1);
    assertThat(SessionPhase.reject(new SessionPhase.AwaitingPermission(active)))
        .isEqualTo(new SessionPhase.Idle());
    assertThat(SessionPhase.doneTool(new SessionPhase.ExecutingTool(active)))
        .isEqualTo(new SessionPhase.Idle());
  }

  private record Edge(SessionPhase.Kind from, SessionPhase.Kind to) {}
}
