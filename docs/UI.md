# Interactive terminal interface

Ajent runs as a full-screen alternate-screen terminal application. The shell
viewport and its scrollback are restored unchanged when Ajent exits. Within
the session, Ajent owns a browsable transcript view while keeping the mutable
live surface pinned to the current turn. Keyboard-owned modal views handle
commands, providers, models, threads, permissions, diffs, plans, checkpoints,
code blocks, tool output, and login.

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

1. the responsive welcome screen for an empty thread, or settled and
   streaming conversation turns;
2. queued user-turn previews and an inline permission decision when present;
3. the pending-changes strip;
4. the composer;
5. the phase, connection, context, and status bar;
6. an active modal/overlay when one owns input.

On an empty roomy terminal, flexible blank rows keep the composer and status
near the bottom edge. Conversation content consumes those rows naturally as
the transcript grows.

User and assistant turns have stable message identities. Assistant markdown is
rendered incrementally. Tool calls appear as typed compact panels with
tool-specific previews. Queued turns look like user turns and carry
`queued #N / total`; Alt-arrow editing marks the active slot with
`✎ editing`.

### Conversation turns

Every settled or live conversation turn uses AgenTTY's left `┃` rail. The
header identifies user turns as `❯ You` and assistant turns with `✦` plus the
capability-aware model name, such as `Opus 4.6`, `Sonnet 4`, or a normalized
local model label. User, Opus, Sonnet, Haiku, and fallback rails use the
native magenta, bright-magenta, blue, bright-cyan, and cyan identity colors;
header metadata remains muted. The right side shows local `HH:mm` time,
elapsed response time from the preceding user message when it is at least
100 ms, the logical turn number, and `↺ checkpoint` when the user message
owns a workspace checkpoint.

Consecutive assistant messages are one visual run: only the first subturn has
a header, while prose, Markdown, tool panels, and errors remain inside the
same rail. A blank railed row separates the header from its body. The body is
laid out three columns narrower so the rail never steals content width, and
narrow headers truncate by Unicode terminal-cell width. Body slots are
separated by one blank railed row, and errors use the native red `⚠` inline
row. Cold-history recovery may start inside a very large assistant run; in
that case Ajent restores a real assistant header at the retained boundary
rather than exposing an orphaned body.

### Welcome screen

An empty thread uses Ajent's compact three-row half-block `AJENT` wordmark, the
“a calm middleware between you and the model” tagline, and shortcut rows
labeled `Navigate`, `Actions`, and `Session`. Provider, model, and profile
details are deliberately omitted from the banner. It collapses to a compact
one-row `AJENT` identity on
short or narrow terminals. The welcome screen does not request animation
frames. Shortcut labels collapse before their keys, and each group wraps by
measured Unicode cell width.

The three starter prompts appear only after saved-thread discovery completes
and proves this is a genuine first run. On a roomy terminal they occupy a
centered 62-column rounded card with a letter-spaced heading. Returning users
keep the quieter welcome even when the currently selected thread is empty.

### Changes and status chrome

Successful file-changing tools feed a bordered `Changes (N files)` strip
above the composer. Each entry keeps its created/modified marker and added and
removed line totals. Review, accept, and reject hints shed in that order when
the terminal is too narrow; file facts remain visible. The global actions are
shown as `Shift-A accept` and `Shift-X reject`, leaving lowercase letters
available for composer input.

The bottom activity row prioritizes permission, compaction, streaming, tool
execution, authentication, queued work, and idle state. It includes the
thread title when space permits and reports `Connection not checked` until
the first real provider stream event changes it to `Connected`; a provider
error changes it to `Provider unavailable`. A single phase glyph represents
the current state. Context details
appear only after real token usage exists; unavailable rates, token slots,
gauges, and percentages are omitted instead of rendered as zeroes or dashes.
Runtime and UI notifications replace the activity detail with a full-width
severity banner and truncate by terminal-cell width instead of wrapping the
fixed chrome.

When process tools are not OS-sandboxed — `--sandbox=off`, or the default
`auto` mode on a platform with no backend (Windows, or Linux/WSL without
bubblewrap) — the chrome shows a persistent `⚠  sandbox: …` warning row above
the status panel, and the same notice is printed to stderr at startup. Bash and
other process tools still run (contained only by the permission prompt and the
workspace path checks), so the warning stays visible for the whole session.
This is a deliberate divergence from AgenTTY (see [PARITY.md](PARITY.md)).

## Composer

The composer stores text, a UTF-16 cursor index, and an attachment list.
Attachments appear in the text as private placeholders and render as compact
chips. Moving, deleting, undoing, recalling history, or editing queued turns
moves text and attachments together.

The composer uses a deliberately dim border, separates the block cursor from placeholder
text, and labels its actions as `Enter send`, `Shift+Enter newline`, and
`Ctrl+E expand`. Its right footer uses available width for provider, model,
and queue identity; the profile already appears with the model in the welcome
header. An unavailable provider is marked `(unavailable)`. On very narrow
terminals the footer preserves the provider before secondary controls. Detail
sheds responsively rather than capping the main surface width.

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
The executable parity flow also holds a real provider stream open, queues a
second turn, compares its native user-turn chrome and the queue-aware composer,
then verifies that the pending turn drains automatically when the stream ends.

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

Ajent leaves terminal mouse reporting disabled so ordinary host-terminal text
selection remains available. In Windows Terminal, drag to select,
`Ctrl-Shift-C` to copy, and `Ctrl-Shift-V` to paste. Transcript history uses
`Page Up` and `Page Down`; this avoids taking ownership of drag gestures merely
to receive mouse-wheel events.

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
| `Page Up`, `Page Down` | Browse the in-session transcript |
| `Esc` or `End` in transcript history | Return to the live view |
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
without a key. Provider switching is two-phase: Ajent authenticates and fetches
the new catalog while the current provider remains active, then commits the
provider and model together only after model confirmation. Escape, an empty
catalog, or discovery failure leaves the old selection untouched.

The model picker supports text filtering, favorites, and capability-aware
reasoning effort. Left/Right cycle only through the selected model's valid
ladder (`off`, `low`, `medium`, `high`, `xhigh`, or `max` as applicable).
Selection and favorites are persisted. The next request snapshots the current
provider/model/auth/effort configuration. Codex supports `off`, `low`,
`medium`, and `high`; Codex mini clamps low to medium, and only cataloged
xhigh-capable models expose `xhigh`.

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

Each assistant tool batch renders inside the native stable `A C T I O N S`
card. Its header counts inspect, mutate, execute, VCS, plan, and agent actions;
tree connectors retain batch order; pending/running rows use a static event dot
while only the footer spinner animates. Settled rows use success, failure, or
rejection glyphs and the footer reports completed actions and elapsed time.
Tool names, categories, statuses, paths, summaries, and body stripes retain
their distinct terminal styles. Tool-specific one-line details include paths,
commands and exit codes, match/hit/entry/result counts, Git state, memory ids,
todo progress, and subagent turn counts. Long content clips on Unicode display
width, task prompt fallbacks truncate on UTF-8 boundaries, and even extremely
narrow terminals remain render-safe.

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
The executable parity run seeds an older saved conversation, navigates to it,
performs the asynchronous swap, and compares its rehydrated viewport against
AgenTTY. Ajent uses the checked-in native source's destructive
viewport-and-scrollback reset for this explicit content replacement.

In a Git workspace, submitted user turns can carry a checkpoint. Rewind opens
an oldest-to-newest checkpoint picker with asynchronous file/add/delete
summaries. Selecting a checkpoint restores the worktree snapshot, truncates
later conversation and compaction records, persists the replacement thread,
and stages the old prompt for editing. Rows use native `#<turn>` labels,
relative time, and `N file(s) +A −D`, `no changes`, or a loading ellipsis.
The selected row starts on the newest checkpoint; Up/Down moves, Enter rewinds,
and Escape cancels. A completed rewind clears the inline surface and shows
`▎ ▶ rewound · files restored · backup at refs/ajent/rewind-backups`.

Rewind is destructive: it overwrites uncommitted edits and deletes files added
since the checkpoint. Before restoring, Ajent snapshots the current working
tree to a `refs/ajent/rewind-backups/<timestamp>` ref (newest 16 kept), so a
rewind is recoverable, and the banner points at that namespace. To inspect or
restore the pre-rewind state, run `git for-each-ref refs/ajent/rewind-backups/`
and check out or diff that commit. These backup refs are internal and never
appear in the checkpoint picker. This banner is a deliberate divergence from
AgenTTY (see [PARITY.md](PARITY.md)).

## Change review

Filesystem edit/write tools produce structured changes. The diff review modal
uses native three-context unified hunks. Navigation moves across files and
hunks. `y`/`n` mark the active hunk accepted/rejected; `a`/`x` apply the choice
to all. Outside the modal, the equivalent global shortcuts are `Shift-A` and
`Shift-X`.

Applying a review is transactional. Ajent verifies every target still contains
the reviewed result, stages metadata-preserving replacements, and then commits
the complete set. A failure or concurrent edit restores already committed
targets and retains the pending review for retry. Rejection deletes only files
created by the reviewed tool operation; a pre-existing empty file is restored
to an empty file. Atomic replacements preserve executable permissions and
other copyable platform attributes.

## Terminal compatibility

Ajent enables bracketed paste and modern keyboard protocols where available,
but retains legacy CSI/SS3/control-byte decoding. Synchronized output is used
when the terminal advertises it. Remote/unsynchronized terminals receive a
slower animation cadence to reduce progressive repaint and wire traffic.

On resize, Ajent rehydrates the mutable surface at the new width rather than
diffing incompatible cell geometry. If display corruption occurs, press
`Ctrl-L`; this intentionally clears Ajent's inline scrollback region.
The executable parity gate performs a real 80x24 to 96x28 PTY resize and
compares the reflowed shortcut and composer regions against AgenTTY.
It also runs three loopback-provider turns and verifies that physical
scrollback grows by exact prefix extension while the final committed
conversation matches AgenTTY cell-for-cell.

## Accessibility and diagnostics

Status is never conveyed only by color: rows include labels, markers, and
tool-state text. The renderer uses Unicode display width and avoids splitting
attachment placeholders. Very narrow terminals degrade by wrapping and modal
clamping rather than dropping actions.

Debug logs and ACP wire traces are opt-in and best-effort. They should be
treated as source-sensitive even though authorization fields are redacted.
See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for diagnostic workflows.
