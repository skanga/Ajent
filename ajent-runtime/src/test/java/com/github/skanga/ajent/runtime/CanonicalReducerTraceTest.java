package com.github.skanga.ajent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.MessageId;
import com.github.skanga.ajent.domain.CheckpointId;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Thread;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.provider.stream.StopReason;
import com.github.skanga.ajent.provider.stream.StreamEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class CanonicalReducerTraceTest {
  @Test
  void capturesCanonicalOrdinaryAndFragmentedChatTransitions() {
    var nanos = new AtomicLong(1_000);
    var ids = new AtomicInteger();
    var reducer = new AgentReducer(new AgentReducer.Context(nanos::get,
        () -> Instant.parse("2026-01-02T03:04:05Z"),
        () -> new MessageId("message-" + ids.incrementAndGet()),
        ignored -> PermissionVerdict.ALLOW, () -> 1.0, () -> 200_000));
    var thread = new Thread(new ThreadId("thread-1"), "Trace", List.of(),
        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
        List.of());

    var trace = CanonicalReducerTrace.capture(reducer, AgentState.initial(thread), List.of(
        new RuntimeMessage.Submit("hello", List.of()),
        new RuntimeMessage.ProviderEvent(1, new StreamEvent.Started()),
        new RuntimeMessage.ProviderEvent(1, new StreamEvent.TextDelta("Hel")),
        new RuntimeMessage.ProviderEvent(1, new StreamEvent.TextDelta("lo")),
        new RuntimeMessage.ProviderEvent(1, new StreamEvent.Usage(12, 3, 4, 5)),
        new RuntimeMessage.ProviderEvent(1,
            new StreamEvent.Finished(StopReason.END_TURN))));

    assertThat(trace.entries()).hasSize(6);
    assertThat(trace.entries().getFirst().message()).isEqualTo(
        "{\"$type\":\"RuntimeMessage.Submit\",\"attachments\":[],\"checkpointId\":null,"
            + "\"images\":[],\"text\":\"hello\"}");
    assertThat(trace.entries().getFirst().effects())
        .extracting(value -> value.substring(0, value.indexOf(',', 10)))
        .containsExactly("{\"$type\":\"RuntimeEffect.Persist\"",
            "{\"$type\":\"RuntimeEffect.StartStream\"");
    assertThat(trace.entries().get(2).message()).contains("StreamEvent.TextDelta", "Hel");
    assertThat(trace.entries()).allSatisfy(entry -> {
      assertThat(entry.beforeHash()).matches("[0-9a-f]{64}");
      assertThat(entry.afterHash()).matches("[0-9a-f]{64}");
    });
    assertThat(trace.entries().get(1).beforeHash())
        .isEqualTo(trace.entries().getFirst().afterHash());
    assertThat(trace.entries().get(5).afterHash())
        .isEqualTo("4a79a45b2944bc6c2548e903898dacacd882a491937ef2f49f56aebb8959a611");
    assertThat(CanonicalReducerTrace.jsonLines(trace)).hasLineCount(6);
    assertThat(CanonicalReducerTrace.traceHash(trace))
        .isEqualTo("b6a9a93504911fed9fbdc5d2c15ed0e65b2bf323055abc1c4092cf7780c85e47");
    assertThat(trace.state().phase().label()).isEqualTo("idle");
    assertThat(trace.state().thread().messages().getLast().text()).isEqualTo("Hello");
    assertThat(trace.state().tokensIn()).isEqualTo(21);
    assertThat(trace.state().tokensOut()).isEqualTo(3);
  }

  @Test
  void canonicalProjectionSortsUnorderedValuesAndTracksCancellationState() {
    var signal = new com.github.skanga.ajent.domain.CancellationSignal();
    assertThat(CanonicalReducerTrace.canonicalJson(java.util.Map.of("z", 1, "a", 2)))
        .isEqualTo("{\"a\":2,\"z\":1}");
    assertThat(CanonicalReducerTrace.canonicalJson(java.util.Set.of("z", "a")))
        .isEqualTo("[\"a\",\"z\"]");
    assertThat(CanonicalReducerTrace.canonicalJson(signal)).contains("\"cancelled\":false");
    signal.cancel();
    assertThat(CanonicalReducerTrace.canonicalJson(signal)).contains("\"cancelled\":true");
  }

  @Test
  void profileChangeIsRepresentedEvenWhenItOnlyClearsSessionGrants() {
    assertThat(CanonicalReducerTrace.canonicalJson(
        new RuntimeMessage.ProfileChanged(Profile.MINIMAL)))
        .isEqualTo("{\"$type\":\"RuntimeMessage.ProfileChanged\","
            + "\"profile\":\"MINIMAL\"}");
  }

  @Test
  void pinsCanonicalWholeTurnScenarioTraces() {
    Map<String, String> actual = Map.of(
        "approve-tool", CanonicalReducerTrace.traceHash(scenario(PermissionVerdict.PROMPT,
            new RuntimeMessage.Submit("read it", List.of()),
            event(1, new StreamEvent.ToolUseStart("call-1", "read")),
            event(1, new StreamEvent.ToolUseDelta("{\"path\":\"README.md\"}")),
            event(1, new StreamEvent.ToolUseEnd()),
            event(1, new StreamEvent.Finished(StopReason.TOOL_USE)),
            new RuntimeMessage.PermissionResolved("call-1", true, false),
            new RuntimeMessage.ToolCompleted(1, "call-1",
                new ToolCompletion.Success("contents")),
            event(2, new StreamEvent.TextDelta("done")),
            event(2, new StreamEvent.Finished(StopReason.END_TURN)))),
        "reject-tool", CanonicalReducerTrace.traceHash(scenario(PermissionVerdict.PROMPT,
            new RuntimeMessage.Submit("write it", List.of()),
            event(1, new StreamEvent.ToolUseStart("call-1", "write")),
            event(1, new StreamEvent.ToolUseDelta("{\"file_path\":\"x\",\"content\":\"y\"}")),
            event(1, new StreamEvent.ToolUseEnd()),
            event(1, new StreamEvent.Finished(StopReason.TOOL_USE)),
            new RuntimeMessage.PermissionResolved("call-1", false, false),
            event(2, new StreamEvent.TextDelta("not written")),
            event(2, new StreamEvent.Finished(StopReason.END_TURN)))),
        "retry-cancel", CanonicalReducerTrace.traceHash(scenario(PermissionVerdict.ALLOW,
            new RuntimeMessage.Submit("slow request", List.of()),
            event(1, new StreamEvent.Error("connection reset", Optional.empty(),
                com.github.skanga.ajent.provider.ErrorClass.TRANSIENT, false)),
            new RuntimeMessage.RetryStream(1),
            event(1, new StreamEvent.TextDelta("partial")),
            new RuntimeMessage.Cancel(),
            new RuntimeMessage.RetryStream(1))),
        "compact-checkpoint", CanonicalReducerTrace.traceHash(scenario(PermissionVerdict.ALLOW,
            new RuntimeMessage.Submit("remember", List.of(),
                Optional.of(new CheckpointId("checkpoint-1"))),
            event(1, new StreamEvent.TextDelta("answer")),
            event(1, new StreamEvent.Finished(StopReason.END_TURN)),
            new RuntimeMessage.CompactContext(),
            event(2, new StreamEvent.TextDelta("summary")),
            event(2, new StreamEvent.Finished(StopReason.END_TURN)),
            new RuntimeMessage.ProfileChanged(Profile.WRITE))),
        "multi-tool-batch", CanonicalReducerTrace.traceHash(scenario(PermissionVerdict.ALLOW,
            new RuntimeMessage.Submit("inspect", List.of()),
            event(1, new StreamEvent.ToolUseStart("read-1", "read")),
            event(1, new StreamEvent.ToolUseDelta("{\"path\":\"README.md\"}")),
            event(1, new StreamEvent.ToolUseEnd()),
            event(1, new StreamEvent.ToolUseStart("glob-1", "glob")),
            event(1, new StreamEvent.ToolUseDelta("{\"pattern\":\"**/*.java\"}")),
            event(1, new StreamEvent.ToolUseEnd()),
            event(1, new StreamEvent.Finished(StopReason.TOOL_USE)),
            new RuntimeMessage.ToolCompleted(1, "read-1",
                new ToolCompletion.Success("read output")),
            new RuntimeMessage.ToolCompleted(1, "glob-1",
                new ToolCompletion.Success("glob output")),
            event(2, new StreamEvent.TextDelta("done")),
            event(2, new StreamEvent.Finished(StopReason.END_TURN)))));

    assertThat(actual).isEqualTo(Map.of(
        "approve-tool", "8eb745054a2bbc0460315d24c682318890a0b84fd44b1be7cbf35964b30a2afa",
        "reject-tool", "089b1f0992f7e69b0ade93a6bda511a685219b5946ea3a469f87f95b06704f10",
        "retry-cancel", "d697304da87ce75c7faedf7aa56a79977ae07de52a3291edd1154613cd034460",
        "compact-checkpoint", "0c4179c5834579dee2eebbd283756427d7fbb7ff656213a404737c9b38450087",
        "multi-tool-batch", "01196e81bc014dc4e8b0533b539b46b31b6e616196665729bd396fb13e93a7ba"));
  }

  private static RuntimeMessage.ProviderEvent event(long turn, StreamEvent event) {
    return new RuntimeMessage.ProviderEvent(turn, event);
  }

  private static CanonicalReducerTrace.Result scenario(PermissionVerdict permission,
                                                        RuntimeMessage... messages) {
    var ids = new AtomicInteger();
    var reducer = new AgentReducer(new AgentReducer.Context(() -> 1_000,
        () -> Instant.parse("2026-01-02T03:04:05Z"),
        () -> new MessageId("message-" + ids.incrementAndGet()), ignored -> permission,
        () -> 1.0, () -> 200_000));
    var thread = new Thread(new ThreadId("thread-1"), "Trace", List.of(),
        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
        List.of());
    return CanonicalReducerTrace.capture(reducer, AgentState.initial(thread), List.of(messages));
  }
}
