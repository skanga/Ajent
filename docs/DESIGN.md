# Ajent design notes

This document records the invariants that make Ajent behave like AgenTTY. It
is a guide for changing the implementation without accidentally changing the
agent.

## Porting rule

Observable reference behavior wins over convenience, framework convention, or
what a new implementation would normally choose. A change starts with one of:

- a translated AgenTTY assertion;
- a captured wire, terminal, or CLI fixture;
- an explicit source-derived invariant with a Java test;
- an approved Ajent extension clearly separated from parity behavior.

Passing compilation or a generic chat smoke test is not parity evidence. The
authoritative status is tracked in [PARITY.md](PARITY.md) and the reference
manifest under `ajent-parity/src/test/resources/reference/`.

## Immutable center, imperative edges

Conversation state is an immutable `AgentState`. `AgentReducer.update` accepts
one `RuntimeMessage` and returns a `Step` containing the next state and a list
of effects. The reducer may allocate records and collections but may not call
the filesystem, clock, HTTP client, terminal, or process APIs.

Edges are allowed to be imperative because their effects are explicit:

- `ProviderPort` streams typed provider events;
- `ToolPort` executes catalog calls and reports progress/completion;
- `PermissionPort` resolves a user decision;
- `PersistencePort` records durable state;
- `OAuthRefreshPort` refreshes and installs credentials;
- checkpoint and attachment ports resolve workspace-specific data.

The loop serializes all reducer messages. Virtual threads improve blocking I/O
scalability; they do not authorize concurrent mutation of session state.

## Exhaustive state

Sealed interfaces are used wherever AgenTTY has a closed variant set: session
phases, stream events, runtime messages/effects, tool results, credentials,
retry state, picker state, and terminal frame state. Switch expressions must
remain exhaustive. A new variant therefore forces every relevant boundary to
make an explicit decision.

Records copy incoming lists/maps and validate identifiers, cursor positions,
and non-null values. Byte arrays are copied at boundaries where ownership can
escape. These are semantic invariants, not merely defensive style.

## Stable identities and stale-result rejection

Every turn has a monotonically increasing id. Provider events, tool progress,
tool completion, scheduled retry, OAuth completion, and watchdog messages are
accepted only for the active id and appropriate phase. This makes cancellation
and thread replacement safe even when an operating-system or HTTP callback
arrives late.

Messages and tool calls have durable ids because rendering, persistence,
checkpoint association, reveal state, and ACP notifications all need stable
identity. Never replace an id merely because the record containing it is
reconstructed.

## Stored form versus wire form

Ajent deliberately has more than one projection of a conversation:

- stored messages retain attachment placeholders and attachment metadata;
- terminal messages render placeholders as compact chips;
- provider requests expand attachment bodies and apply provider-specific block
  ordering, caching, thinking replay, and context trimming;
- ACP projects content into protocol content blocks and updates;
- compaction builds a bounded temporary wire conversation without rewriting
  historical user-visible messages.

Do not “simplify” these into a single text string. Doing so breaks attachment
round trips, provider cache behavior, or durable replay.

## Context and output budgets

Limits are applied at the narrowest correct boundary:

- tool implementations bound raw process/file/network capture;
- the dispatcher applies native per-tool UTF-8-safe head/tail budgets;
- provider request builders soft-trim history to the selected model context;
- compaction uses its own target and summary prompt;
- stream decoders cap partial JSON and accumulated blocks;
- terminal previews bound visible rows independently from retained output.

Truncation must preserve valid UTF-8 and include the native marker/shape. A
limit of zero has its documented native meaning; it is not automatically
treated as “empty.”

## Provider neutrality

The runtime speaks `ChatRequest` and typed `StreamEvent`, but request builders
remain provider-specific. Anthropic content blocks, OpenAI chat messages, and
Ollama native/JSON protocols are not forced into one least-common-denominator
wire serializer.

Provider selection is call-time configuration. Switching provider, model,
authentication, effort, or context capability affects the next request without
rebuilding conversation history. In-flight turns retain their own snapshot.

## Tool catalog as a single source

The ordered native catalog owns tool name, description, schema, effects,
timeout, output budget, and annotations. Interactive, ACP, MCP server, and
subagent composition derive exposure from this source. Separate wire and
runtime views may enforce different strictness, but duplicate hand-written
tool lists are not allowed.

The permission decision is based on effects, not tool-name folklore. The
dispatcher revalidates arguments and workspace paths after permission. A
subagent role receives an explicit allowlist and cannot recursively expose the
`task` tool.

## Queue and composer semantics

Queued turns carry chip-form text plus attachments. Plain Up on an empty
composer drains the whole queue into one editable draft. Alt-Up/Alt-Down walks
individual queued slots from newest to oldest, commits edits when moving, and
restores the live draft after the tail. Submitting a peeked slot removes its
original position before the edited turn is sent or appended at the queue
tail.

History walking is separate and mutually exclusive. It visits non-empty user
messages in reverse chronological order and restores the live draft after
walking forward past the newest item. Any edit ends history walking. Composer
undo/redo snapshots text, cursor, and attachments together and is capped at 64
states.

## Terminal rendering invariants

The UI owns the terminal's alternate screen for the duration of an interactive
session and restores the shell screen on exit or suspension. Settled rows may
move into the alternate buffer's session scrollback; live rows must remain
mutable. The renderer therefore still tracks a committed prefix and a live
suffix.

The visual hash must include every state axis read by the view and exclude
axes the view does not read. A false negative freezes visible state; a false
positive wastes CPU and can cause terminal flicker. `VisualHashCoverageTest`
is the contract for this projection.

The canvas diff and inline frame are typestates. A synced frame can use a small
diff only when its geometry and scrollback witness still prove continuity.
Resize, thread replacement, invalid prefix identity, or failed proof demotes to
a stale/hard-reset path.

## Persistence and shutdown

Thread/settings writes are atomic. Background thread persistence is coalesced
because provider streams can emit many small updates. Shutdown ordering is:

1. stop accepting new interactive work;
2. cancel or finish the active turn according to the caller contract;
3. let submitted effect workers schedule final reducer messages;
4. drain the effect executor;
5. flush and close the persistence writer.

Changing this order can lose the final assistant/tool state even if every
individual write is atomic.

## Error taxonomy

Errors are typed before they reach policy. Provider HTTP status, transport
failure, malformed stream, refusal, max-token stop, cancellation, and idle
timeout have different retry and display behavior. Tool errors distinguish
invalid arguments, permission/sandbox denial, not found, timeout, process
failure, and internal failure.

User-visible error strings are part of CLI/TUI parity where fixtures assert
them. Internal exceptions should be wrapped at the adapter boundary and must
not disclose credentials or raw authorization headers.

## Extension policy

An Ajent-only feature is acceptable when it does not silently replace the
reference path. It should have:

- an explicit configuration or command boundary;
- tests proving default AgenTTY behavior is unchanged;
- documentation labeling the extension;
- no new dependency unless the JDK cannot meet the requirement cleanly.

Examples such as a future OpenAI Responses API transport should be a new
provider dialect behind the existing runtime contract, not a change to the
OpenAI Chat Completions builder used for parity.

## Review checklist

Before merging a behavioral change, verify:

1. The relevant AgenTTY source/test or capture is identified.
2. A Java test failed for the intended behavioral reason before implementation.
3. State remains immutable and new variants are exhaustively handled.
4. Cancellation, stale callbacks, byte limits, and secret handling were tested.
5. The complete reactor passes `mvn -q verify`.
6. Documentation and the parity ledger describe the resulting truth.
