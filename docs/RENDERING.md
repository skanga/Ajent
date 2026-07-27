# Rendering and session scrollback

Ajent's terminal renderer runs inside the terminal emulator's alternate
screen. Settled conversation enters that buffer's session scrollback while
the active tail, composer, status, and modal surfaces remain safely
repaintable. Exiting Ajent restores the shell's original screen and scrollback.

## Pipeline

```text
AgentState + UI state
        |
    visual hash
        |
  styled logical lines
        |
   TerminalCanvas cells
        |
 CanvasSerializer rows
        |
 InlineFrameRenderer typestate
        |
 ANSI bytes to the terminal
```

The view first builds styled lines. Markdown, tool panels, queue previews,
status, and composer text are rendered into terminal-width rows. A
`TerminalCanvas` turns those rows into cells with explicit style ids and
Unicode widths. `CanvasSerializer` produces stable content rows, and
`InlineFrameRenderer` chooses a verified diff, stale redraw, scrollback commit,
or hard reset.

## Visual hash gate

Rendering is event-driven. Before building the frame, the UI computes a cheap
hash over every state axis the view reads:

- message count and render keys for the mutable tail;
- frozen-prefix geometry;
- profile, model, phase, permission, and visible status;
- composer text/cursor/attachments/expanded state;
- queued count, content key, and active queue-peek slot;
- modal variants, cursors, queries, scroll positions, and content keys;
- animation buckets only while an animation is active.

Token counters and reducer tick timestamps are omitted because the current view
does not paint them. If the candidate equals the last painted hash, rendering
returns before markdown/layout/canvas work. `VisualHashCoverageTest` mutates
every declared visual axis and proves the hash changes, then mutates nonvisual
axes and proves stability.

## Cells and Unicode

`UnicodeWidth` implements terminal display width for combining marks, wide
East Asian characters, emoji, variation selectors, and malformed input.
`ColumnTextWrapper` wraps by display cells rather than Java string length.
Surrogate pairs remain one code point, attachment placeholders remain one
editing unit, and continuation cells are explicit in the canvas.

Styles are interned by `TerminalStylePool`. Cells refer to compact style ids,
which keeps equality/diff checks inexpensive. Markdown lines retain styled
spans so emphasis, headings, code, links, tables, task lists, and strikeout do
not collapse to plain text before serialization.

## Inline frame typestate

The renderer represents proof strength as sealed states:

- `Empty`: nothing has been painted;
- `Fresh`: a first frame has a known canvas but no diff witness;
- `Synced`: previous cells and geometry match the terminal surface;
- `Stale`: content is known but a safe small diff is not;
- `HardReset`: geometry/prefix evidence failed and a whole surface is needed;
- `Sealed`: no further rendering is legal.

A synced frame can emit a minimal diff only when its canvas witness and
scrollback proof both remain valid. If the witness remains but scrollback
overflow must be committed, rows are committed and the frame demotes to stale.
If the witness fails, it demotes to a hard reset. This makes unsafe diff paths
unrepresentable without an explicit state transition.

## Frozen prefix and live suffix

The transcript is split at `frozenThrough`.

The frozen prefix contains user turns and terminal assistant runs whose tool
statuses and reveal animation are final. Each sealed block enters a
`ScrollbackLedger` with row weight and boundary metadata. The live suffix is
rebuilt every frame and may include a streaming assistant tail, pending tools,
queue previews, status, composer, and modals.

Assistant continuations are frozen as a run, not independently, so headers and
tool panels cannot split inconsistently. A reveal may finish during the paint
that displays its last bytes; the UI schedules one final frame so the next
freeze decision observes the settled state.

## Rehydration

The frozen ledger is a performance cache, not the source of truth. It is
discarded and reconstructed when:

- the thread id changes;
- terminal width changes;
- the stored prefix is longer than the conversation;
- a message id at the prefix boundary no longer matches;
- checkpoint rewind or thread replacement resets the surface.

For a long idle thread, rehydration finds a bounded suffix whose rendered rows
fit the terminal-oriented budget. Oversized assistant runs retain a coherent
tail with a fresh header. This avoids rendering an entire historical thread on
every cold open while durable messages remain intact.

## Scrollback trimming

`FrozenScrollbackTrimPolicy` trims only blocks that have already been painted
and proven committed. A newly sealed block cannot disappear in the same frame
that first introduces it. The ledger tracks scrollback debt when terminal
geometry means rows must be committed before they can leave the mutable
surface.

Trimming is block-aware. It never slices through a logical message/tool panel
just to meet a numeric row target. Compaction boundaries are stored as their
own separator blocks so they remain visible when adjacent history is trimmed.

## Streaming markdown and reveal

Provider text arrives in arbitrary byte and semantic chunks. The conversation
stores accumulated text; `StreamingMarkdown` incrementally parses the current
content and drives a wall-clock reveal. Reveal speed is bytes/characters per
second rather than frames per second, so reducing repaint cadence on SSH or an
unsynchronized terminal does not slow the perceived prose rate.

The reveal lifecycle distinguishes live, finalizing, parsing, gliding, and
settled states. Tool panels may be deferred until prose reaches a stable edge.
If a panel arrives during reveal, the deferral policy either holds it briefly
or snaps the reveal to the edge and shows the panel. This prevents a large tool
card from repeatedly moving under partially revealed text.

The scheduler continues ticks after the provider stops until reveal and the
settle/freeze handoff complete. Stopping the clock as soon as network bytes end
would strand partially revealed text until the next keypress.

## Animation cadence

Ajent requests animation frames only when needed. The scheduler selects a
cadence from terminal capabilities and remote-session hints:

- synchronized output can use a smoother cadence;
- unsynchronized terminals repaint more slowly to reduce visible progressive
  drawing;
- SSH sessions apply a floor that limits ANSI bandwidth;
- idle settled frames schedule nothing.

Synchronized output wraps a frame in DEC mode 2026 markers so compatible
terminals present all cell changes atomically. The serializer falls back to
ordinary ANSI without changing logical frame state.

## Overlays and modal stability

Modal lists are clamped to available terminal rows and keep the selected row in
view. Bottom overlays replace rows within the existing logical extent rather
than growing the frame below the viewport. This avoids pushing the welcome or
conversation top into unrecoverable native scrollback on every open/close.

Thread replacement and `Ctrl-L` use an explicit clear/reset sequence because
the next surface has no valid committed-prefix relation to the old one. Normal
modal close does not clear scrollback.

## Tool previews

Tool output is rendered from typed status plus `ToolBodyPreview`, not from one
generic text dump. File reads, edits/diffs, processes, diagnostics, Git, web,
tasks, todos, and failures each have bounded structures and tones. Grep hits
can annotate later read previews. Streaming output respects terminal height;
the complete bounded result remains available in the tool-output viewer.

## Failure recovery

The renderer prefers correctness over a small diff. Geometry mismatch,
impossible cursor position, invalid scrollback proof, stale prefix identity,
or serialization inconsistency demotes the frame. A hard reset repaints the
known logical surface and re-establishes a witness.

`Ctrl-L` is the manual escape hatch. It clears the display plus terminal
scrollback (`CSI 2J`, `CSI 3J`, home), resets the inline frame, invalidates the
visual hash, and paints from current state. This is intentionally stronger
than an ordinary redraw.

## Test and probe coverage

Rendering has several kinds of evidence:

- Unicode width, ANSI, canvas, serializer, and frame unit tests;
- CommonMark and styled markdown fixtures;
- reveal pacing, liveness, freeze-gate, and smoothness tests;
- scrollback oracle, prefix harness, fuzz, and invariant tests;
- mid-run seam/freeze/wire tests with changing tool output;
- composer flicker and edit-turn CPU probes;
- long-session and real-thread JMH probes.

The standalone composer probe can be run from the benchmark JAR:

```powershell
& "$env:AJENT_JAVA_HOME\bin\java.exe" -cp ajent-benchmarks\target\benchmarks.jar `
  com.github.skanga.ajent.cli.ComposerFlickerProbe 120 96
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the runtime boundary and
[UI.md](UI.md) for user-facing terminal behavior.
