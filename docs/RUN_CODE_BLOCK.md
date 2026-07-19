# Running code blocks from assistant replies

Ajent can extract fenced code blocks from the newest assistant reply and run a
supported shell block without copying it manually. The workflow mirrors
AgenTTY and keeps execution, capture, terminal suspension, and result staging
explicit.

## Open the picker

Press `Ctrl-G`, or choose **Run code block** from the command palette. Ajent
requires an idle session and at least one fenced block in the newest assistant
message. The picker shows each block's number, first-line preview, language,
and line count.

Controls:

- Up/Down or `j`/`k`: move;
- Enter or `1`–`9`: run the selected/numbered block;
- `e`: place the clean block text in the composer for editing;
- `y`: copy the block;
- `Esc` or `q`: close.

Unknown or non-shell language labels remain available for copy/edit but are
not executed as a guessed language. Empty blocks are ignored.

## Command selection

The extractor removes Markdown fences and preserves the exact body newlines.
Language aliases are normalized only for execution gating. Shell-like blocks
use the platform's supported command runner; Ajent does not reinterpret a
Python/Java/etc. block as shell merely because it contains executable text.

On Windows, commands are transported through PowerShell using an encoded
command so quotes, newlines, Unicode, and shell metacharacters survive without
a second ad-hoc quoting pass. On POSIX, the runner can inherit the user's TTY
for an interactive-looking command while a bounded tee captures merged output.

## Terminal suspension

Before starting a process, Ajent commits the current inline frame and restores
terminal modes that a child expects. The child is then allowed to write to the
terminal. A pinned heartbeat/title indicates the command and elapsed time and
reminds the user that `Ctrl-C` stops the child.

When the child ends, Ajent re-enters raw/bracketed-paste/keyboard modes,
invalidates the previous frame witness, and paints a result surface. It never
diffs the post-child screen against the pre-child canvas because the child may
have moved the cursor, changed style, or scrolled.

## Bounds and cancellation

Execution has a native-compatible deadline and bounded merged capture. The
process tree is stopped on timeout or user cancellation. Output beyond the
capture limit is truncated with an explicit marker; it is not allowed to grow
the Java heap without bound.

On POSIX, parent signal handling is isolated while the child owns the TTY. On
Windows, the bounded process runner and PowerShell wrapper handle termination.
Ajent restores its previous signal/terminal handlers even when startup fails.

## Result surface

The result displays:

- the command preview;
- running/completed/timed-out state;
- exit code;
- elapsed/capture metadata;
- captured output, or an explicit no-output message.

Controls:

- arrows or `j`/`k`: scroll by row;
- Page Up/Down, Home/End: larger navigation;
- `y`: copy output;
- `a`: attach the output to the composer as a compact chip;
- `Esc`, `q`, or discard action: close without attaching.

Attaching records command, exit metadata, line count, and bytes. The provider
sees the expanded output at request-build time; the transcript/composer retain
the compact chip.

## Security model

Code-block execution is a direct user action, not a provider-issued tool call.
Read the command before running it. It executes with the current user's
permissions and working directory, subject to the code-block runner's process
bounds but not to a promise that arbitrary shell code is harmless.

For untrusted replies, use `e` to inspect/edit or `y` to copy into a separate
sandbox. Never run a block containing credentials you would not type into the
same shell.

## Troubleshooting

### `isn't runnable here`

The language is not mapped to a supported shell on this platform. Stage it
with `e` or run the appropriate interpreter manually.

### Result UI is visually corrupted

Press `Ctrl-L`. Child processes can emit terminal control sequences; Ajent
normally hard-resets its frame witness after suspension, but the manual redraw
is available for terminal-specific behavior.

### Command timed out but descendants remain

Record the platform, shell, and command and inspect the process-runner debug
log. Ajent attempts process-tree termination, but unusual detached processes
can escape their parent job/group depending on OS policy.

### Output was truncated

Use the ordinary `bash` tool or run in an external terminal and redirect to a
workspace file, then inspect the file with bounded read/search tools. The UI
capture limit is deliberate.

## Implementation and tests

The live state machine is `CodeBlockPicker`; process suspension and execution
are composed by `InteractiveCommand`. Coverage includes extraction, language
gating, numbering, edit/copy, Windows quoting, timeout/cancel, result scrolling,
capture bounds, signal restoration, and output attachment staging.
