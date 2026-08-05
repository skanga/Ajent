# Session scrollback contract

This document narrows [RENDERING.md](RENDERING.md) to the invariants around
the alternate screen's session scrollback. These rules are load-bearing: a renderer can look
correct in a fixed screenshot and still duplicate, strand, or erase rows after
streaming, resize, or modal transitions.

## Why the renderer still tracks scrollback

Ajent now isolates its full-screen session in the alternate buffer so startup
does not inherit a cursor offset and exit restores the shell exactly. Long
coding sessions still exceed one viewport, so settled turns become scrollback
inside that alternate buffer while Ajent is running. Because host terminals do
not consistently expose alternate-buffer history, Ajent also reconstructs the
complete logical transcript for Page Up/Page Down and mouse-wheel navigation.
Escape or End returns to the live edge.

The cost is ownership: bytes already scrolled above the terminal viewport are
owned by the emulator and cannot be edited in place. Ajent must never treat
those rows as part of its mutable canvas.

## Three regions

At any frame, content belongs to one of three conceptual regions:

```text
native terminal scrollback       immutable, emulator-owned
frozen ledger / committed prefix immutable, Ajent-proven
live canvas suffix               mutable, repainted by Ajent
```

The ledger bridges logical messages and physical committed rows. The live
canvas contains only content whose cells Ajent may still change.

## Freeze eligibility

A user message is freezeable once it is part of the settled prefix. An
assistant run is freezeable only when:

- the session is idle;
- reveal parsing/finalization/glide is settled;
- every tool status in the run is terminal;
- no pending permission can mutate a tool card;
- compaction boundaries are respected.

Consecutive assistant messages can be continuations of one logical turn and
are frozen as a run. Freezing only the first message could strand a header or
make a later tool completion repaint an emulator-owned row.

## Seal-before-trim rule

Newly settled lines are first painted as part of a known frame. Only a later
frame may prove them committed and eligible for trimming from the mutable
surface. The renderer never creates and deletes a block in one frame, because
the terminal would have no evidence that the user ever saw it.

Ledger blocks store row weight and boundary information. Trimming removes
whole blocks. A large tool panel or wrapped message is not split at an
arbitrary physical row just to satisfy a target.

## Prefix identity

`frozenThrough` is an index into the durable message list, but index alone is
insufficient. The UI also retains message ids for the prefix. Before reusing
the ledger it verifies that the id at the boundary still matches.

Checkpoint rewind, thread replacement, persistence reload, or any operation
that reconstructs messages may shorten/reorder the list. A failed identity
proof discards the ledger and rehydrates from durable state.

## Resize

Rows depend on terminal width. A block frozen at 120 columns cannot be reused
at 80 columns even when its text is unchanged. Width change therefore clears
cached row geometry and chooses a bounded rehydration suffix at the new width.

Height change affects trim budgets and modal viewports but not text wrapping.
The inline-frame geometry witness still decides whether a small diff is safe.

## Cold rehydration

Loading a long thread cannot eagerly lay out every historical message on each
frame. Ajent walks backward by logical turn/run, estimates actual rendered row
cost, and retains a terminal-oriented suffix. If one assistant run itself is
oversized, it keeps a coherent trailing subset and emits the appropriate
assistant header.

Rehydration is only a view optimization. No conversation message or tool
output is deleted from persistence.

## Scrollback debt

Sometimes the logical frozen prefix grows faster than terminal rows can be
safely committed. `ScrollbackDebt` records the pending commitment. The inline
renderer grows/scrolls through a proven transition, then the trim policy can
release the corresponding ledger blocks.

Debt is tied to canvas/frame evidence, not a timer. Guessing that a terminal
“probably painted” after N milliseconds is not sufficient.

## Modal and overlay rule

Opening an overlay must not increase the frame below the bottom edge. If it
did, the terminal would scroll top rows into native history; closing the modal
would shrink the frame but could not pull those rows back, leaving duplicated
or stranded content.

Ajent clamps modal rows and overlays them inside the existing logical extent.
Thread replacement is different: it explicitly clears scrollback because the
new conversation has no prefix relationship to the previous surface.

## Failure recovery

When any proof fails, the renderer demotes rather than emitting a speculative
diff. A stale repaint uses known content without assuming matching previous
cells. A hard reset establishes fresh geometry. `Ctrl-L` performs the strongest
manual reset and clears emulator scrollback for the Ajent surface.

## Verification

The scrollback contract is covered by:

- prefix harness and oracle comparisons;
- frozen invariant fuzzing;
- oversized-entry and fresh-frame cases;
- active-stream mutable-tail tests;
- mid-run seam, wire, and freeze transitions;
- a three-turn real Ajent/Ajent provider differential whose physical
  scrollback snapshots must grow by exact prefix extension;
- width-change rehydration;
- long-session and real-thread probes.

When changing this subsystem, a passing snapshot test is insufficient. Exercise
at least: first frame, one new row, viewport overflow, settled freeze, active
tail mutation, resize, modal open/close, and whole-surface reset.
