# Interactive terminal interface

Ajent runs as an inline terminal application. It keeps the shell's native
scrollback, renders settled conversation rows above a mutable live surface,
and uses keyboard-owned modal views for commands, providers, models, threads,
permissions, diffs, plans, checkpoints, code blocks, tool output, and login.

## Starting the interface

```powershell
.\ajent.cmd --workspace .
```

The workspace defaults to the current directory. The selected provider,
model, profile, effort, and saved credentials are loaded before the session is
composed. A real TTY is recommended; ACP and MCP modes are separate stdio
protocol processes and do not render this interface.

## Main surface

The live surface is ordered as:

1. settled and streaming conversation turns;
2. queued user-turn previews;
3. permission, runtime, or UI status;
4. the composer;
5. an active modal/overlay when one owns input.

User and assistant turns have stable message identities. Assistant markdown is
rendered incrementally. Tool calls appear as typed compact panels with
tool-specific previews. Queued turns look like user turns and carry
`queued #N / total`; Alt-arrow editing marks the active slot with
`✎ editing`.

## Composer

The composer stores text, a UTF-16 cursor index, and an attachment list.
Attachments appear in the text as private placeholders and render as compact
chips. Moving, deleting, undoing, recalling history, or editing queued turns
moves text and attachments together.

### Submission and editing

| Key | Action |
| --- | --- |
| `Enter` | Submit the current draft |
| `Shift-Enter`, `Alt-Enter` | Insert a newline |
| `Left`, `Right` | Move one Unicode code point or one attachment chip |
| `Ctrl-Left`, `Ctrl-Right` | Move by word; with an empty idle composer, cycle threads |
| `Home`, `End` | Move to the draft boundary |
| `Backspace` | Delete the previous code point or whole chip |
| `Ctrl-U` | Delete to the beginning of the current line |
| `Ctrl-W` | Delete the previous word |
| `Alt-D` | Delete the next word |
| `Ctrl-Z` | Undo |
| `Ctrl-Shift-Z`, `Ctrl-Y` | Redo |
| `Ctrl-E` | Toggle expanded composer state |

Undo and redo keep at most 64 whole-draft snapshots. A new edit after undo
discards the redo branch. Submitting or switching threads clears transient
composer history.

### Submitted-turn history

With an empty composer and no queued turns, Up loads the most recent non-empty
user message in the current thread. Further Up presses walk backward; Down
walks toward the newest message and then restores the live draft. Attachments
are retained. Editing a recalled message ends history walking and treats it as
the new draft.

### Pending queue

Submitting while a turn is active appends a pending turn instead of replacing
the active context.

| Key | Queue behavior |
| --- | --- |
| `Up` on an empty composer | Drain all pending turns into one editable draft |
| `Alt-Up` | Save the live draft and load the newest queued slot |
| repeated `Alt-Up` | Commit edits and walk toward the oldest slot |
| `Alt-Down` | Commit edits and walk toward the queue tail |
| `Alt-Down` past the tail | Restore the saved live draft |
| `Alt-Backspace` on an empty, unpeeked composer | Drop the newest pending turn |
| `Enter` while peeking | Remove the original slot and submit/requeue the edited value |

Whole-queue recall remaps attachment indices while concatenating slots. It is
destructive: the turns live in the composer until resubmitted.

## Attachments and workspace navigation

Type `@` at a word boundary to open the workspace file picker. It performs a
bounded cached scan, ignores generated/vendor directories, and ranks paths by
subsequence match. Selecting a file inserts a chip whose contents are resolved
again when the provider request is built.

Type `#` at a word boundary to search indexed declarations. A selected symbol
chip records path and line and expands to a declaration-centered excerpt.

Bracketed text paste always becomes a paste chip. CRLF and CR are normalized
to LF, and the chip displays line/byte metadata. Small one-line pastes show a
compact preview. Image input accepts PNG, JPEG, GIF, and WebP bytes or a quoted
path within the native size bound.

`Ctrl-V` and `Alt-V` run smart paste: image clipboard first, then text. Ajent
uses the JDK desktop clipboard when available, configured platform commands,
and finally a terminal clipboard query where supported.

## Global keys

| Key | Action |
| --- | --- |
| `Ctrl-C` | Quit Ajent |
| `Esc` | Cancel a live model turn; close the owning modal |
| `Ctrl-K` | Open the command palette |
| `/` on an empty composer | Open the command palette |
| `Ctrl-/` | Open the model picker |
| `Ctrl-P` | Open the provider picker |
| `Ctrl-J` | Open saved threads |
| `Alt-Left`, `Alt-Right` | Cycle adjacent threads |
| `Ctrl-N` | Create a new thread |
| `Ctrl-R` | Review pending changes |
| `Ctrl-T` | Open the plan |
| `Ctrl-G` | Open runnable code blocks |
| `Ctrl-O` | Open tool outputs |
| `Ctrl-L` | Redraw and clear Ajent's terminal scrollback surface |
| `Shift-Tab` | Cycle permission profile |

Global keys are evaluated after the active modal has first refusal. Login and
permission prompts own all relevant input. `Esc` on the bare idle composer is
inert, which also permits terminals that encode Alt-arrow as an Escape prefix.

## Command palette

The command palette is a fuzzy filtered list and is the discoverable route to
actions without dedicated keys. Type to filter, use Up/Down, and press Enter.
It includes new/open threads, compaction, provider/model selection, login,
change review, plan, code blocks, tool outputs, checkpoint rewind, profile
cycle, and quit.

Opening the palette through `/` does not insert a slash. A slash in any
non-empty draft remains literal so URLs, paths, regexes, and prose are not
hijacked.

## Models, providers, and reasoning effort

The provider picker lists configured presets plus a custom-host entry. A
hosted provider may transition to secret input; a local provider can proceed
without a key. Successful switching fetches the provider's model catalog and
opens the model picker.

The model picker supports text filtering, favorites, and capability-aware
reasoning effort. Left/Right cycle only through the selected model's valid
ladder (`off`, `low`, `medium`, `high`, `xhigh`, or `max` as applicable).
Selection and favorites are persisted. The next request snapshots the current
provider/model/auth/effort configuration.

## Permission prompt and profiles

The interactive profile is restored from settings; a new settings file uses
AgenTTY's `write` default. Under `ask`, a consequential tool opens a blocking
prompt:

- `y`: allow this call;
- `a`: always allow this tool and persist that grant;
- `n` or `Esc`: reject.

The modal displays the tool and the live transcript retains the pending tool
card. `write` auto-approves every catalog effect; `minimal` prompts for reads as
well as execution, writes, and network access. Changing profile clears all
always-allow grants in memory and on disk so a stricter profile re-arms its
prompts. Policy is enforced again in the dispatcher, not only by the modal.

## Plans and tool output

The plan modal mirrors live `todo` tool arguments, including partial streamed
JSON updates. Items normalize to pending, in-progress, or completed and the
footer reports completed/total. The modal is read-only.

The tool-output viewer collects completed calls from the current thread. The
list shows tool-specific titles/status; Enter opens the bounded body. Arrow,
`j`/`k`, Page Up/Down, Home/End scroll; `y` copies; Escape returns to the list.

## Code blocks

`Ctrl-G` extracts fenced blocks from the newest assistant reply. The picker
shows language, preview, and line count. Enter or a number runs a supported
shell block, `e` stages it in the composer, and `y` copies it.

Execution temporarily suspends the inline terminal UI. Windows uses bounded
PowerShell command transport; POSIX can inherit the TTY while teeing bounded
output. The result card shows command, exit code/timeout, elapsed state, and
captured output. It can be scrolled, copied, discarded, or attached to the
composer. See [RUN_CODE_BLOCK.md](RUN_CODE_BLOCK.md).

## Threads and checkpoints

Saved threads are loaded as newest-first metadata rows. The current thread is
anchored and marked. Up/Down, `j`/`k`, Home/End, and Page Up/Down navigate;
Enter performs a cold session replacement. Before a swap, Ajent cancels the
old session, drains/persists it, reconstructs the selected provider/tool
composition, clears draft/modal/reveal state, and resets the inline surface.

In a Git workspace, submitted user turns can carry a checkpoint. Rewind opens
an oldest-to-newest checkpoint picker with asynchronous file/add/delete
summaries. Selecting a checkpoint restores the worktree snapshot, truncates
later conversation and compaction records, persists the replacement thread,
and stages the old prompt for editing.

## Change review

Filesystem edit/write tools produce structured changes. The diff review modal
uses native three-context unified hunks. Navigation moves across files and
hunks. `y`/`n` mark the active hunk accepted/rejected; `a`/`x` apply the choice
to all. Review status records the user's inspection decision—it does not undo
an operation that already ran.

## Terminal compatibility

Ajent enables bracketed paste and modern keyboard protocols where available,
but retains legacy CSI/SS3/control-byte decoding. Synchronized output is used
when the terminal advertises it. Remote/unsynchronized terminals receive a
slower animation cadence to reduce progressive repaint and wire traffic.

On resize, Ajent rehydrates the mutable surface at the new width rather than
diffing incompatible cell geometry. If display corruption occurs, press
`Ctrl-L`; this intentionally clears Ajent's inline scrollback region.

## Accessibility and diagnostics

Status is never conveyed only by color: rows include labels, markers, and
tool-state text. The renderer uses Unicode display width and avoids splitting
attachment placeholders. Very narrow terminals degrade by wrapping and modal
clamping rather than dropping actions.

Debug logs and ACP wire traces are opt-in and best-effort. They should be
treated as source-sensitive even though authorization fields are redacted.
See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for diagnostic workflows.
