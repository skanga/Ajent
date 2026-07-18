package com.github.skanga.ajent.cli;

import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.ToolUse;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Cheap projection of every state axis that can change the interactive frame. */
final class InteractiveVisualHash {
  private static final long OFFSET_BASIS = 1_469_598_103_934_665_603L;
  private static final long PRIME = 1_099_511_628_211L;

  enum Surface {
    MODEL_PICKER,
    PROVIDER_PICKER,
    THREAD_LIST,
    DIFF_REVIEW,
    COMMAND_PALETTE,
    MENTION_PALETTE,
    SYMBOL_PALETTE,
    TODO,
    TOOL_VIEWER,
    CODE_BLOCKS,
    LOGIN,
    CHECKPOINTS,
    PLAN,
    VIEWPORT
  }

  record ComposerState(
      String text, int cursor, int attachmentCount, int queuedCount, boolean expanded) {
    ComposerState {
      text = Objects.requireNonNull(text, "text");
      if (cursor < 0 || attachmentCount < 0 || queuedCount < 0) {
        throw new IllegalArgumentException("negative composer visual axis");
      }
    }
  }

  record SurfaceState(
      int variant, int index, int secondaryIndex, String query,
      boolean detail, int scroll, long contentKey) {
    SurfaceState {
      query = Objects.requireNonNull(query, "query");
    }

    static SurfaceState closed() {
      return new SurfaceState(0, 0, 0, "", false, 0, 0);
    }
  }

  record State(
      int messageCount,
      int frozenBlocks,
      int frozenTurn,
      List<Long> liveMessageRenderKeys,
      Profile profile,
      String modelId,
      boolean pendingPermission,
      int phaseVariant,
      String status,
      long statusExpiryBucket,
      boolean active,
      int spinnerFrame,
      ComposerState composer,
      Map<Surface, SurfaceState> surfaces,
      long animationBucket,
      long lastTickNanos,
      int tokensIn,
      int tokensOut) {
    State {
      if (messageCount < 0 || frozenBlocks < 0 || frozenTurn < 0) {
        throw new IllegalArgumentException("negative transcript visual axis");
      }
      liveMessageRenderKeys = List.copyOf(liveMessageRenderKeys);
      profile = Objects.requireNonNull(profile, "profile");
      modelId = Objects.requireNonNull(modelId, "modelId");
      status = Objects.requireNonNull(status, "status");
      composer = Objects.requireNonNull(composer, "composer");
      var copied = new EnumMap<Surface, SurfaceState>(Surface.class);
      copied.putAll(Objects.requireNonNull(surfaces, "surfaces"));
      surfaces = Map.copyOf(copied);
    }
  }

  private InteractiveVisualHash() {}

  static long hash(State state) {
    Objects.requireNonNull(state, "state");
    Mixer mixer = new Mixer();
    mixer.mix(state.messageCount());
    mixer.mix(state.frozenBlocks());
    mixer.mix(state.frozenTurn());
    state.liveMessageRenderKeys().forEach(mixer::mix);
    mixer.mix(state.profile().ordinal());
    mixer.mix(state.modelId());
    mixer.mix(state.pendingPermission());
    mixer.mix(state.phaseVariant());
    mixer.mix(state.status());
    if (state.statusExpiryBucket() != 0) mixer.mix(state.statusExpiryBucket());
    if (state.active()) mixer.mix(Math.floorMod(state.spinnerFrame(), 10));
    mixer.mix(state.composer().text());
    mixer.mix(state.composer().cursor());
    mixer.mix(state.composer().attachmentCount());
    mixer.mix(state.composer().queuedCount());
    mixer.mix(state.composer().expanded());
    for (Surface surface : Surface.values()) {
      SurfaceState value = state.surfaces().getOrDefault(surface, SurfaceState.closed());
      mixer.mix(value.variant());
      mixer.mix(value.index());
      mixer.mix(value.secondaryIndex());
      mixer.mix(value.query());
      mixer.mix(value.detail());
      mixer.mix(value.scroll());
      mixer.mix(value.contentKey());
    }
    if (state.animationBucket() != 0) mixer.mix(state.animationBucket());
    // lastTickNanos and token counters are deliberately omitted: the view never reads them.
    return mixer.value;
  }

  static long messageKey(String text, String pendingStream) {
    Mixer mixer = new Mixer();
    mixer.mix(Objects.requireNonNull(text, "text"));
    mixer.mix(Objects.requireNonNull(pendingStream, "pendingStream"));
    return mixer.value;
  }

  static long messageKey(Message message) {
    Objects.requireNonNull(message, "message");
    Mixer mixer = new Mixer();
    mixer.mix(message.role().ordinal());
    mixer.mix(message.text());
    mixer.mix(message.attachments().size());
    message.attachments().forEach(attachment -> {
      mixer.mix(attachment.kind().ordinal());
      mixer.mix(attachment.path());
      mixer.mix(attachment.name());
      mixer.mix(attachment.lineNumber());
      mixer.mix(attachment.lineCount());
      mixer.mix(attachment.byteCount());
    });
    mixer.mix(message.toolCalls().size());
    for (ToolUse call : message.toolCalls()) {
      mixer.mix(call.id().value());
      mixer.mix(call.name().value());
      mixer.mix(sampledKey(call.arguments()));
      mixer.mix(call.status().getClass().getName());
      mixer.mix(call.status().output());
    }
    mixer.mix(message.error().orElse(""));
    mixer.mix(message.textBlockClosed());
    mixer.mix(message.isCompactSummary());
    return mixer.value;
  }

  static long sampledKey(Object value) {
    return value == null ? 0 : Integer.toUnsignedLong(value.hashCode());
  }

  private static final class Mixer {
    private long value = OFFSET_BASIS;

    private void mix(boolean input) { mix(input ? 1 : 0); }
    private void mix(int input) { mix((long) input); }
    private void mix(long input) { value = (value ^ input) * PRIME; }

    private void mix(String input) {
      mix(input.length());
      if (input.isEmpty()) return;
      mix(input.charAt(0));
      mix(input.charAt(input.length() - 1));
      if (input.length() >= 16) mix(input.charAt(input.length() / 2));
    }
  }
}
