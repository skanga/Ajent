# Ajent — Architecture Review

_Review date: 2026-07-27. Based on a layer-by-layer analysis of all eight
functional modules (~37k LOC main, 183 test files)._

## Verdict

This is a well-architected codebase. The bones are excellent: a pure
`(state, message) -> (state, effects)` reducer, sealed typestates that make
illegal states unrepresentable, an acyclic module graph, immutability and
virtual-thread confinement instead of lock soup, and a deliberate refusal to
bolt on an agent framework. `docs/ARCHITECTURE.md` is accurate to the code.

The debt is concentrated, not diffuse. Almost all of it lives in two god
classes, one misplaced subsystem, a handful of copy-paste sites, and a few
real data-safety/portability defects. Nothing here is a rewrite; it is all
localized cleanup.

## What's genuinely good

- **MVU / hexagonal core.** `AgentReducer` is pure (only an injected `Context`
  of clocks/RNG), so the whole conversation state machine is deterministic and
  testable. Side effects escape only through ports (`ProviderPort`, `ToolPort`,
  `PersistencePort`, ...). `AgentLoop` is the single-writer interpreter.
- **Sealed typestates.** `SessionPhase` physically omits `ActiveTurn` from
  `Idle` — "no turn in progress" is unrepresentable when idle. `ToolStatus`,
  `StreamEvent`, `RetryState`, `ToolResult`, `PickerState` follow suit. No deep
  inheritance anywhere; `final` classes + records + sealed interfaces.
- **Concurrency by confinement.** State is immutable; the reducer runs under one
  lock; provider streams and tools are one virtual thread each and re-enter as
  messages. Stale late-completions are turn-id-checked into no-ops. The terminal
  renderer avoids locks entirely via linear "consume-once" frame tokens.
- **Auth subsystem.** Clean precedence resolution, AES-256-GCM + HKDF-SHA256
  credential envelope keyed by machine seed, atomic 0600 writes, key zeroing in
  `finally`, legacy-plaintext migration. The strongest single subsystem.
- **Verification discipline.** 183 test files, fuzz/property tests around the
  terminal diff renderer, loopback HTTP framing tests, a 53-entry parity
  manifest. Only ~10 broad `catch` across ~37k LOC.

## Findings by severity

### 1. Data-safety / correctness (fix first)

| # | Finding | Location |
|---|---------|----------|
| A | **Rewind deletes post-checkpoint files.** `restore()` force-overwrites the tree (`checkout-index -a -f`) then deletes every tracked or untracked (non-gitignored) file absent from the snapshot. Files created after the checkpoint are silently destroyed; uncommitted edits overwritten. Non-transactional, no backup, no confirmation at this layer. | `ajent-tools/.../workspace/GitCheckpointStore.java:102-130` |
| B | **Two writers race on the same thread file.** Normal saves serialize through the single-worker `AsyncThreadWriter`; rewind calls `threadStore.save(truncated)` directly on a virtual thread, bypassing it. A stale in-flight coalesced save can resurrect truncated messages. | `ajent-cli/.../InteractiveCommand.java:329` vs `ajent-core/.../AsyncThreadWriter.java` |
| C | **Process sandbox fails open.** When mode is `auto` and no backend binary exists (bwrap/sandbox-exec), bash runs unsandboxed with `valid=true`. On WSL/Windows this is the common case. When active, bwrap uses `--share-net` — network is never contained. | `ajent-tools/.../process/ProcessSandbox.java:42-45,162` |
| D | **`BashValidator` is a denylist masquerading as a control.** Blocks literal substrings; trivially bypassed by whitespace/quoting/vars/base64. Fine as UX guardrail, not a security boundary. | `ajent-tools/.../process/BashValidator.java:15-48` |
| E | **Lossy reload.** No schema version field. On load, in-flight tool statuses are silently downgraded to `Failed("interrupted")` and missing timestamps default to `Instant.now()`. No forward-migration path. | `ajent-core/.../persistence/ThreadStore.java:151,225-236` |
| F | **IO failures swallowed silently.** `ThreadStore.save` returns `false` with no log; `loadAllMetadata` returns `List.of()` on any IO error (looks like "no history"); `GitCheckpointStore` swallows scratch/copy IOException (checkpoint silently no-ops). | `ThreadStore.java:78-80,116-118`; `GitCheckpointStore.java:164-165` |
| G | **Static, unbounded, cross-session caches.** `FileTools.READ_CACHE`/`SNAPSHOTS` are static, never evicted (cross-session staleness + leak). `EnvironmentHttpClient.BRIDGES` caches never-closed `SocksBridge`; sets `jdk...disableHostnameVerification` as a process-global property when `AGENTTY_INSECURE=1`. | `FileTools.java:62-63`; `EnvironmentHttpClient.java:39,91` |

### 2. Structural — two god classes

- **`InteractiveCommand.java` — 3531 LOC.** The nested `static final class Ui`
  spans lines 942-3530 (~2,588 LOC, ~60 fields) holding every
  picker/modal/composer/scrollback/key-handling/render/dispatch concern.
  Decompose into per-modal controllers + a renderer.
- **`AgentReducer.java` — 1289 LOC** (next-largest 425). One `update` switch
  fanning to ~40 private methods plus embedded prompt strings and its own
  `ObjectMapper`. Split into per-message-kind collaborators. It is pure, which
  caps the risk.

### 3. Misplaced subsystem

- **`rag/` is 25 files / 2539 LOC — 33% of `ajent-tools`** — an entire embedded
  retrieval engine (HNSW, BM25, embeddings, rerankers, chunkers, Ollama
  transports) backing ~2 tools. Belongs in its own module.

### 4. Duplication (mechanical, low-risk)

- Bootstrap triplicated across composition roots: `resolveDocs` + sandbox-init +
  provider/credential resolution copy-pasted across `AcpCommand`,
  `McpServeCommand`, `InteractiveCommand`. Extract a `CommandContext` helper.
- Provider message serialization written 3x hand-rolled (`AnthropicMessages`,
  `OpenAiWire`, `OllamaWire`); system-prompt/`<memory>` builder and
  `readBounded` copy-pasted OpenAI<->Ollama; 14 separate `ObjectMapper`s.
- Dead + divergent parser layer: `OpenAiStreamParser.parseSse/parseNdjson`
  unused in production (test-only) while Ollama's decoder delegates to its batch
  parser. Delete the dead one.
- `success()`/`failure()`/description helpers copy-pasted into 8 tool-family
  handlers; new tool needs 4 edit sites with only a name-equality guard.
- 5 near-identical picker-viewport blocks in `AppChrome`; a helper
  (`appendPickerRows`) exists but 3 callers were never migrated.

### 5. Coupling / abstraction leaks

- Terminal parses raw tool stdout (`AgentTimeline`/`ToolBodyPreview` scrape
  `"failed with exit code "`, `"Found "`, `[branch hash]`, porcelain git). Any
  wording change in `ajent-tools` silently degrades the UI.
- `ajent-runtime` (the "ports" layer) imports concrete `ajent-core`/`provider`
  internals; the port abstraction is partly bypassed.
- Tool-taxonomy switches duplicated into protocol + cli when
  `NativeToolWireCatalog` already exists in `tools`.
- Domain leak: `AttachmentText` and `ModelCapabilities.fromId` are
  application/wire policy sitting in the dependency-free domain module.
- Cross-package cycle: `provider/openai` <-> `provider/ollama`.

### 6. Build / portability

- **`.mvn/maven.config` hard-forces `--toolchains .mvn/toolchains.xml`, which
  hardcodes `C:\lang\jdk-25`** — a Windows-only absolute path, committed. On
  Linux/macOS/CI the build cannot resolve the JDK-25 toolchain without editing a
  tracked file. Make it env-driven or provide per-OS toolchains.
- No `module-info.java` despite 8 Maven modules — boundaries enforced by Maven
  only, not JPMS. Minor.

## Recommended order

1. **Data safety:** guard rewind (A), route rewind saves through
   `AsyncThreadWriter` (B), stop swallowing save/checkpoint IO errors (F).
2. **Security honesty:** sandbox `auto` should fail closed or warn loudly (C);
   document `BashValidator` as UX-only (D); bound/evict static caches, close
   `SocksBridge` (G).
3. **Portability:** fix the hardcoded toolchain path (§6).
4. **Structural, as capacity allows:** carve `Ui` out of `InteractiveCommand`;
   split `AgentReducer`; extract bootstrap helper; move `rag/` to its own module;
   give the timeline structured tool results.

Items 1-3 are small and high-value. Item 4 is the long game and none of it is
urgent — the architecture tolerates the god classes because they are
pure/serialized.
