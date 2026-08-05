package com.github.skanga.ajent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.skanga.ajent.domain.Message;
import com.github.skanga.ajent.domain.Profile;
import com.github.skanga.ajent.domain.Role;
import com.github.skanga.ajent.domain.ThreadId;
import com.github.skanga.ajent.runtime.AgentState;
import com.github.skanga.ajent.terminal.JLineTerminalSession;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Ports Ajent's declarative visual_hash_coverage_test contract. */
final class VisualHashCoverageTest {
  @Test void productionUiUsesTheContractAsItsRenderGate() {
    Message first = new Message(Role.USER, "hello", List.of(), List.of());
    Message second = new Message(Role.USER, "again", List.of(), List.of());
    var thread = new com.github.skanga.ajent.domain.Thread(
        new ThreadId("visual-hash"), "", List.of(first, second));
    AgentState baseline = AgentState.initial(thread);
    var state = new AtomicReference<>(baseline);
    var ui = new InteractiveCommand.Ui(new SilentTerminal(), state,
        new InteractiveCommand.PermissionGate());

    long initialHash = ui.visualHash();
    state.set(new AgentState(baseline.thread(), baseline.phase(), baseline.activeTurnId(),
        baseline.turnCounter(), 12_345, 67_890, baseline.lastTickNanos() + 1_000_000,
        baseline.status(), baseline.toolDraft(), baseline.queued(), baseline.compaction(),
        baseline.oauthRefreshInFlight(), baseline.truncatedToolIds(), baseline.sessionGrants()));
    assertThat(ui.visualHash()).isEqualTo(initialHash);

    var messages = new ArrayList<>(thread.messages());
    messages.add(new Message(Role.USER, "new", List.of(), List.of()));
    state.set(AgentState.initial(new com.github.skanga.ajent.domain.Thread(
        thread.id(), thread.title(), messages, thread.createdAt(), thread.updatedAt(),
        thread.compactions())));
    assertThat(ui.visualHash()).isNotEqualTo(initialHash);

    ui.render();
    long rendered = ui.renderPasses();
    ui.render();
    assertThat(ui.renderPasses()).isEqualTo(rendered);
  }

  @Test void settledIdleStateIsStableAcrossCalls() {
    Model model = baseline();

    assertThat(hash(model)).isEqualTo(hash(model));
  }

  @Test void everyViewAxisAdvancesTheHash() {
    for (Axis axis : visualAxes()) {
      Model before = baseline();
      Model after = baseline();
      axis.mutate().accept(after);

      assertThat(hash(after))
          .as("axis '%s' must change the visual hash", axis.name())
          .isNotEqualTo(hash(before));
    }
  }

  @Test void nonVisualAxesPreserveTheHash() {
    List<Axis> invariantAxes = List.of(
        new Axis("last_tick clock", model -> model.lastTickNanos += 3_600_000_000_000L),
        new Axis("token counters", model -> {
          model.tokensIn = 12_345;
          model.tokensOut = 67_890;
        }));

    for (Axis axis : invariantAxes) {
      Model before = baseline();
      Model after = baseline();
      axis.mutate().accept(after);

      assertThat(hash(after))
          .as("axis '%s' must not change the visual hash", axis.name())
          .isEqualTo(hash(before));
    }
  }

  private static List<Axis> visualAxes() {
    return List.of(
        new Axis("messages.size (append a turn)", model ->
            model.messages.add(new MessageAxis("new", ""))),
        new Axis("live tail message text (render_key)", model ->
            model.messages.set(1, new MessageAxis("hi there more", ""))),
        new Axis("live tail pending_stream (render_key)", model ->
            model.messages.set(1, new MessageAxis("hi there", "buffered"))),
        new Axis("profile cycle", model -> model.profile = Profile.WRITE),
        new Axis("model_id swap", model -> model.modelId = "claude-haiku-4-5"),
        new Axis("pending_permission appears", model -> model.pendingPermission = true),
        new Axis("phase Idle -> Streaming", model -> model.phaseVariant = 1),
        new Axis("status banner text", model -> model.status = "something happened"),
        new Axis("status expiry (100ms bucket)", model -> model.statusExpiryBucket = 50),
        new Axis("spinner frame (while active)", model -> {
          model.active = true;
          model.spinnerFrame = 1;
        }),
        new Axis("composer text", model -> model.composerText = "typing"),
        new Axis("composer cursor", model -> {
          model.composerText = "abc";
          model.composerCursor = 2;
        }),
        new Axis("composer attachment count", model -> model.attachmentCount = 1),
        new Axis("composer queued count", model -> model.queuedCount = 1),
        new Axis("composer queued content", model -> model.queuedKey = 7),
        new Axis("composer queue peek", model -> model.queuePeekIndex = 1),
        new Axis("composer expanded toggle", model -> model.composerExpanded = true),
        new Axis("frozen prefix grows", model -> model.frozenBlocks = 1),
        new Axis("frozen_turn advances", model -> model.frozenTurn = 7),
        picker("model_picker opens", InteractiveVisualHash.Surface.MODEL_PICKER, 0, ""),
        picker("model_picker cursor move", InteractiveVisualHash.Surface.MODEL_PICKER, 3, ""),
        picker("model_picker query", InteractiveVisualHash.Surface.MODEL_PICKER, 0, "free"),
        picker("provider_picker opens", InteractiveVisualHash.Surface.PROVIDER_PICKER, 0, ""),
        picker("provider_picker cursor move", InteractiveVisualHash.Surface.PROVIDER_PICKER, 2, ""),
        picker("thread_list opens", InteractiveVisualHash.Surface.THREAD_LIST, 0, ""),
        picker("thread_list cursor move", InteractiveVisualHash.Surface.THREAD_LIST, 4, ""),
        cell("diff_review opens at cell", InteractiveVisualHash.Surface.DIFF_REVIEW, 0, 0),
        cell("diff_review hunk move", InteractiveVisualHash.Surface.DIFF_REVIEW, 1, 2),
        picker("command_palette opens", InteractiveVisualHash.Surface.COMMAND_PALETTE, 0, ""),
        picker("command_palette query", InteractiveVisualHash.Surface.COMMAND_PALETTE, 0, "git"),
        picker("command_palette index", InteractiveVisualHash.Surface.COMMAND_PALETTE, 5, "git"),
        picker("mention_palette opens", InteractiveVisualHash.Surface.MENTION_PALETTE, 0, ""),
        picker("mention_palette query", InteractiveVisualHash.Surface.MENTION_PALETTE, 0, "src"),
        picker("mention_palette index", InteractiveVisualHash.Surface.MENTION_PALETTE, 3, "src"),
        picker("symbol_palette opens", InteractiveVisualHash.Surface.SYMBOL_PALETTE, 0, ""),
        picker("symbol_palette query", InteractiveVisualHash.Surface.SYMBOL_PALETTE, 0, "foo"),
        picker("symbol_palette index", InteractiveVisualHash.Surface.SYMBOL_PALETTE, 2, "foo"),
        modal("todo modal opens", InteractiveVisualHash.Surface.TODO),
        modal("tool viewer opens", InteractiveVisualHash.Surface.TOOL_VIEWER),
        picker("tool viewer list cursor move", InteractiveVisualHash.Surface.TOOL_VIEWER, 2, ""),
        new Axis("tool viewer list -> body stage", model -> model.surfaces.put(
            InteractiveVisualHash.Surface.TOOL_VIEWER, surface(0, 0, "", true, 0))),
        new Axis("tool viewer body scroll", model -> model.surfaces.put(
            InteractiveVisualHash.Surface.TOOL_VIEWER, surface(0, 0, "", true, 5))),
        modal("code block picker opens", InteractiveVisualHash.Surface.CODE_BLOCKS),
        picker("code block picker cursor move", InteractiveVisualHash.Surface.CODE_BLOCKS, 3, ""),
        modal("login modal opens", InteractiveVisualHash.Surface.LOGIN));
  }

  private static Axis picker(String name, InteractiveVisualHash.Surface surface,
      int index, String query) {
    return new Axis(name, model -> model.surfaces.put(
        surface, surface(index, 0, query, false, 0)));
  }

  private static Axis cell(String name, InteractiveVisualHash.Surface surface,
      int first, int second) {
    return new Axis(name, model -> model.surfaces.put(
        surface, surface(first, second, "", false, 0)));
  }

  private static Axis modal(String name, InteractiveVisualHash.Surface surface) {
    return new Axis(name, model -> model.surfaces.put(
        surface, surface(0, 0, "", false, 0)));
  }

  private static InteractiveVisualHash.SurfaceState surface(
      int index, int secondary, String query, boolean detail, int scroll) {
    return new InteractiveVisualHash.SurfaceState(1, index, secondary, query, detail, scroll, 0);
  }

  private static Model baseline() {
    Model model = new Model();
    model.messages.add(new MessageAxis("hello", ""));
    model.messages.add(new MessageAxis("hi there", ""));
    for (InteractiveVisualHash.Surface surface : InteractiveVisualHash.Surface.values()) {
      model.surfaces.put(surface, InteractiveVisualHash.SurfaceState.closed());
    }
    return model;
  }

  private static long hash(Model model) {
    List<Long> renderKeys = model.messages.stream()
        .map(message -> InteractiveVisualHash.messageKey(message.text(), message.pendingStream()))
        .toList();
    return InteractiveVisualHash.hash(new InteractiveVisualHash.State(
        model.messages.size(), model.frozenBlocks, model.frozenTurn, renderKeys,
        model.profile, model.modelId, model.pendingPermission, model.phaseVariant,
        model.status, model.statusExpiryBucket, model.active, model.spinnerFrame,
        new InteractiveVisualHash.ComposerState(model.composerText, model.composerCursor,
            model.attachmentCount, model.queuedCount, model.queuedKey, model.queuePeekIndex,
            model.composerExpanded),
        model.surfaces, 0, model.lastTickNanos, model.tokensIn, model.tokensOut));
  }

  private record Axis(String name, Consumer<Model> mutate) {}
  private record MessageAxis(String text, String pendingStream) {}

  private static final class Model {
    private final List<MessageAxis> messages = new ArrayList<>();
    private final EnumMap<InteractiveVisualHash.Surface, InteractiveVisualHash.SurfaceState>
        surfaces = new EnumMap<>(InteractiveVisualHash.Surface.class);
    private int frozenBlocks;
    private int frozenTurn;
    private Profile profile = Profile.ASK;
    private String modelId = "claude-opus-4-5";
    private boolean pendingPermission;
    private int phaseVariant;
    private String status = "";
    private long statusExpiryBucket;
    private boolean active;
    private int spinnerFrame;
    private String composerText = "";
    private int composerCursor;
    private int attachmentCount;
    private int queuedCount;
    private long queuedKey;
    private int queuePeekIndex = -1;
    private boolean composerExpanded;
    private long lastTickNanos;
    private int tokensIn;
    private int tokensOut;
  }

  private static final class SilentTerminal implements InteractiveCommand.TerminalPort {
    @Override public JLineTerminalSession.Size size() {
      return new JLineTerminalSession.Size(80, 24);
    }

    @Override public void write(String value) {}
  }
}
