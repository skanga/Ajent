# Ajent architecture

Ajent is a behavior-first Java 25 port of AgenTTY. Its architecture preserves
the reference agent's functional update loop while separating wire protocols,
tools, persistence, terminal rendering, and process entry points into Maven
modules. Records carry immutable data, sealed interfaces make state variants
exhaustive, and virtual threads host blocking edge work without moving mutable
session state out of the reducer.

## Runtime shape

One interactive session has four layers:

```text
terminal / ACP client / MCP client
              |
        composition root
              |
     AgentLoop dispatch queue
              |
 AgentReducer: (state, message) -> (state, effects)
              |
 provider, tool, persistence, timer and OAuth ports
```

`AgentReducer` is the authority for conversation and turn state. It never
performs network, process, filesystem, or clock work directly. Instead it
returns `RuntimeEffect` values. `AgentLoop` serializes messages, interprets
effects through injected ports, and dispatches the resulting messages back to
the reducer. This gives tests a deterministic reducer while production can use
virtual threads for provider streams and tools.

The session phase is a sealed typestate:

- `Idle`: no active turn exists.
- `Streaming`: a provider request is producing assistant blocks.
- `ExecutingTool`: one or more tool calls are scheduled or running.
- `AwaitingPermission`: a consequential tool is waiting for a user decision.

An `ActiveTurn` owns the turn id, cancellation signal, retry state, provider
progress, and partial tool state. Messages carry a turn id where a late result
could otherwise corrupt a newer turn. The reducer ignores stale provider,
timer, tool, and OAuth completions.

## Maven modules

### `ajent-domain`

Dependency-free value types: threads, messages, attachments, tool calls,
session phases, retry states, model capabilities, profiles, and identifiers.
These types contain invariants but no I/O.

### `ajent-core`

Shared algorithms and durable storage: thread/settings JSON, asynchronous
coalescing writes, scheduling, fuzzy matching support, finite-state utilities,
doom-loop detection, and debug logging. It depends only on the domain plus
Jackson at the persistence boundary.

### `ajent-provider`

Provider-neutral request/stream contracts and concrete Anthropic,
OpenAI-compatible, and Ollama wire implementations. It owns incremental SSE
and NDJSON decoding, HTTP cancellation/watchdogs, model catalogs, endpoint
presets, authentication resolution, credential encryption, and OAuth HTTP
flows. It does not own conversation state.

### `ajent-tools`

The AgenTTY-compatible tool catalog and dispatch implementation: workspace
filesystem operations, edit matching, processes, search, repository map, Git,
web, memory, skills, RAG, todos, and subagent host services. Workspace and
process sandboxes are enforced here, before operating-system work begins.

### `ajent-runtime`

The reducer/effect loop and production session factory. It projects stored
conversation messages into provider wire messages, coordinates permission and
tool execution, retries providers, compacts context, refreshes OAuth tokens,
persists threads, and runs isolated provider-backed subagents.

### `ajent-protocol`

ACP and MCP adapters. ACP exposes Ajent as an editor-hosted coding agent with
durable sessions and asynchronous prompts. MCP support includes client
transports for stdio and Streamable HTTP, pooled external tools, configuration
loading, and Ajent's standalone JSON-RPC tool server.

### `ajent-terminal`

Terminal protocol decoding and the pure rendering toolkit: Unicode cell
width, wrapping, markdown, canvas diffing, inline-frame proofs, scrollback
ledgers, reveal pacing, composer editing, and modal/picker state machines.
JLine is confined to this edge.

### `ajent-cli`

The executable composition root. It resolves configuration, credentials,
providers, tools, MCP servers, persistence, checkpoints, and terminal services,
then connects them to `AgentLoop`. It also implements `login`, `logout`,
`status`, `skills`, `mcp-serve`, `acp`, and `airgap` process modes.

### `ajent-parity` and `ajent-benchmarks`

These modules may depend on the application, but production code never depends
on them. `ajent-parity` owns frozen reference manifests, source mappings,
goldens, and differential contracts. `ajent-benchmarks` contains JMH ports of
native probes and standalone diagnostic entry points.

## Dependency direction

The effective dependency graph is intentionally acyclic:

```text
domain
  ^
core
  ^-----------------------^
provider              terminal
  ^       ^               ^
tools     |               |
  ^       |               |
runtime --+               |
  ^                       |
protocol -----------------+
  ^
cli
```

Some edge modules depend on several lower modules because they compose
contracts, but lower modules do not reach upward into the CLI. Provider and
terminal code do not depend on each other. Tools can use provider-neutral
types for isolated subagents, while the runtime installs the actual runner.

## A normal turn

1. The UI dispatches `RuntimeMessage.Submit` containing chip-form text and
   attachments.
2. The reducer stamps an optional Git checkpoint, appends the user message,
   creates a fresh `ActiveTurn`, and emits a provider-start effect.
3. `ConversationWire` builds a context-bounded request. Attachment placeholders
   are expanded only for the provider; stored/rendered messages retain chips.
4. The provider decoder emits typed stream events for text, thinking,
   signatures, tool JSON, usage, stop reasons, and errors.
5. The reducer immutably updates the assistant tail. If tools were requested,
   it applies policy and scheduling and emits permission/tool effects.
6. Tool progress and completions update the typed tool status. Terminal tool
   results are projected into the next provider continuation.
7. A final provider stop returns the session to idle, persists the thread, and
   drains the next queued user turn if one exists.

Cancellation trips the active turn's `CancellationSignal`; transports and tool
workers observe the same signal. Late completion messages remain harmless
because the reducer checks the turn id and phase.

## Retry, liveness, and recovery

Provider failures are typed before policy is applied. Retryable failures use
the native capped jitter/backoff ladders and `Retry-After` clamping. Healthy
heartbeats and first deltas decay retry budget. Mid-stream retries are more
conservative because visible output may already have been committed.

Two independent liveness mechanisms are important:

- provider byte-idle watchdogs close blocked HTTP reads;
- reducer ticks detect a streaming phase with no semantic progress and recover
  stranded tool workers.

OAuth refresh parks a request only when no uncommitted output would be lost.
On success, the in-memory credential is replaced and the turn is launched with
a fresh cancellation signal. On failure, the typed error closes the turn and
allows queued work to drain safely.

## Tool safety boundaries

Tool exposure begins with the single ordered native catalog. Each tool has a
JSON schema, effect set, timeout, output budget, and annotations. Permission
policy combines that effect set with the active `ask`, `write`, or `minimal`
profile. The dispatcher validates again; UI approval is not treated as the
only safety boundary.

Filesystem paths are resolved canonically under the selected workspace.
Process commands pass through platform-specific validation and sandbox
wrapping. Search and output paths enforce byte/row limits before results enter
the conversation. External MCP tools are adapted into the same runtime catalog
and permission path.

## Persistence

Threads and settings use AgenTTY-compatible JSON shapes. Writes are atomic and
thread persistence is coalesced asynchronously. Loop shutdown first prevents
new dispatch, waits for effect workers to schedule their follow-up work, then
drains the persistence writer.

Credentials use an authenticated `v1` envelope derived from a machine/user
seed through HKDF-SHA256 and encrypted with AES-256-GCM. Plain legacy input is
migrated on successful read. Credential details are never written to the
terminal or debug log.

## Why no general agent framework

Ajent does not use LangChain4j in its core. AgenTTY's observable behavior
depends on exact wire blocks, retry decisions, permission transitions,
incremental parsing, and terminal timing. A general orchestration framework
would add a second state machine and make those contracts harder to prove.
Libraries are used only where the JDK lacks a suitable edge facility; see
[DEPENDENCIES.md](DEPENDENCIES.md).

## Verification architecture

Tests are organized around boundaries rather than implementation layers:

- pure translated assertions for domain, schedulers, policy, parsers, and
  render math;
- loopback HTTP tests for exact headers, bodies, framing, cancellation, and
  idle watchdogs;
- real temporary workspaces and Git repositories for tools/checkpoints;
- reducer traces with stable hashes and typed effects;
- ACP/MCP transcript tests over duplex streams;
- cell, ANSI, scrollback, fuzz, and performance probes for terminal behavior;
- a 53-entry reference manifest that maps every pinned AgenTTY test/probe to
  Java evidence.

`mvn -q verify` is the release-quality local gate. It runs the whole reactor,
enforces dependency convergence, compiles for Java 25 with lint warnings, runs
tests, produces JaCoCo reports, enforces aggregate line and branch floors,
checks deterministic whitespace, runs maximum-effort SpotBugs analysis, packages
the distribution, and emits the aggregate CycloneDX SBOM. The separate
`security` profile adds an aggregate OWASP Dependency-Check scan without making
ordinary offline development depend on the NVD service.

## Related documentation

- [DESIGN.md](DESIGN.md): invariants and deliberate tradeoffs
- [AUTH.md](AUTH.md): credentials, OAuth, and provider precedence
- [UI.md](UI.md): interactive workflows and key ownership
- [RENDERING.md](RENDERING.md): canvas, inline frame, reveal, and scrollback
- [PARITY.md](PARITY.md): requirement-by-requirement evidence ledger
- [PROVENANCE.md](PROVENANCE.md): pinned upstream source and fixture policy
