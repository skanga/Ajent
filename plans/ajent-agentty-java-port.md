# Ajent: behavior-compatible Java port of AgenTTY

Status: approved for adversarial review
Reference date: 2026-07-16 (America/Los_Angeles)
Reference repository: `./agentty`
Reference commit: `c7594d64020cfdacb10b6a0b2074bcedcc827bba`
Pinned submodules: `maya@8c655268272b416faed1ba13ffb6d36c292415ed`, `acp-cpp@d8b80082f021fe15a081ddd9fe812667f9435ade`, `mcp-cpp@f87d78aa5e031cb80257692b3379805d54e54ca5`
Target: JDK 25, Maven, Windows/Linux/macOS

## 1. Definition of done

Ajent is complete only when all of the following are true:

1. Given the same workspace, configuration, scripted provider byte stream, terminal size, input events, and clock, Ajent produces the same externally observable behavior as the pinned AgenTTY reference. Deliberate differences (program name, Java distribution/startup characteristics, paths under `~/.ajent`) must be listed in `docs/PARITY.md`, reviewed individually, and must not change agent decisions or protocol behavior.
2. Every one of the 53 C++ files under `agentty/tests` is represented in `src/test/java`, `src/jmh/java`, or `src/testFixtures` by a named Java counterpart or an explicitly justified non-runnable fixture/capture tool. Every CTest-registered assertion and invariant has a Java assertion. Build-only probes become JMH benchmarks or opt-in diagnostic tests.
3. Differential tests run shared fixtures against AgenTTY and Ajent for CLI output/exit codes, persisted JSON, provider request and event translation, tool schemas/results, ACP/MCP JSON-RPC, reducer traces, and terminal ANSI/cell snapshots.
4. The complete feature surface is implemented: CLI/subcommands; auth and credential encryption; Anthropic, OpenAI-compatible, and Ollama providers; retries and cancellation; the agent loop; all 23 built-in tools; permissions and path-aware scheduling; sandbox/workspace enforcement; persistence; skills/memory; RAG; MCP client/server; ACP; air-gap mode; checkpoints/diff review; code-block execution; clipboard attachments; and interactive TUI behavior.
5. `mvn verify` passes on JDK 25, including unit, property, integration, protocol, and deterministic terminal tests. Opt-in live-provider and platform packaging smoke tests are documented and pass where credentials/platforms exist.
6. Ajent documentation is comparable in breadth and depth to the reference: user manual, architecture, rendering/scrollback internals, auth, provider/tool/protocol docs, troubleshooting, contributor/build/release docs, and a parity ledger.
7. The Ajent root is a clean Git repository whose history does not contain `agentty/`; the final repository is created at `https://github.com/skanga/Ajent`, pushed, and its CI is green. The reference checkout remains local and ignored.

## 2. Non-negotiable compatibility rules

- Treat the pinned source and tests as the specification. Documentation helps explain intent but never overrides executable behavior.
- Freeze the reference commit during the port. Upstream updates are a separate parity increment recorded in `docs/PARITY.md`; do not chase a moving target.
- Preserve wire behavior before refactoring. Port request JSON, SSE/NDJSON framing, error classification, retry limits, tool schemas, truncation, credential envelopes, and persisted JSON field-by-field.
- Define the comparator for each surface before capturing fixtures. Always retain and compare raw artifacts first. The only default normalization allowlist is nondeterministic timestamps, generated UUIDs, the declared program name, and declared data/workspace path roots. Every normalized-away difference needs a reviewed `docs/PARITY.md` row. Provider bodies, persisted JSON, tool schemas, exit codes, stop reasons, and ANSI/cell output use exact structural or byte/cell comparators appropriate to their formats; “semantically similar” is not a passing criterion.
- Preserve the pure application shape: `update(Model, Msg) -> Step(Model, List<Effect>)`, with all I/O behind ports. Use sealed interfaces for sum types, records for immutable values/events, enums for closed scalar sets, and explicit mutable runtime owners only at terminal/network/process boundaries.
- Make time, UUIDs, environment variables, filesystem roots, provider streams, process execution, and terminal size/input injectable. Determinism is required for parity tests.
- Do not adopt LangChain4j in the core. Reconsider it only for an isolated capability after a parity test proves that it does not alter the AgenTTY contract.
- Use external libraries only at edges where the JDK is insufficient. Initial expected set: JUnit 5 and jqwik (tests/properties), Jackson (JSON), JLine (raw terminal/input capabilities), and JMH (probes/benchmarks). Evaluate official ACP/MCP Java libraries behind adapters; keep them only if captured-wire tests prove parity. Pin all resolved versions and dependency checksums/lock metadata.
- Do not substitute a high-level TUI widget framework for Maya semantics. Implement the required cell canvas, ANSI diff, inline scrollback ledger, streaming Markdown, reveal pacing, caching, and visual-hash behavior in Java, using JLine only for terminal access.
- Keep secrets out of logs, test fixtures, Git history, exception messages, and debug dumps. Live tests use environment variables and are disabled by default.
- Never test a Java reimplementation solely against itself. Each protocol/serialization/rendering slice needs captured reference fixtures or a differential oracle.

## 3. Repository and Maven shape

Use one Maven reactor so dependency direction is enforced:

```text
ajent/
  pom.xml
  ajent-domain/        # records, sealed events/phases, policies; no I/O deps
  ajent-core/          # reducer, agent loop, scheduling, prompts, ports
  ajent-provider/      # HTTP/SSE/NDJSON, Anthropic/OpenAI/Ollama/auth
  ajent-tools/         # built-ins, workspace/sandbox/process, skills/memory/RAG
  ajent-protocol/      # ACP and MCP client/server adapters
  ajent-terminal/      # canvas, ANSI renderer, markdown, widgets, input loop
  ajent-cli/           # composition root, commands, distribution entry point
  ajent-parity/        # fixtures, reference runners, differential tests, JMH
  docs/
  plans/
```

Dependency direction is `domain <- core <- adapters <- cli`; terminal, providers, tools, and protocols depend on core ports and never on each other except through explicit adapter composition. `ajent-parity` may depend on every module but no production module may depend on it.

Maven gates:

- Maven Enforcer: JDK 25 and Maven 3.9.12+.
- Compiler: `--release 25`, all lint warnings enabled; no preview features unless a parity-relevant design is impossible without one.
- Surefire: unit/property tests; Failsafe: integration/differential tests.
- JaCoCo as a gap detector, not a substitute for the parity ledger. Require 90% branch coverage for `domain` and policy/reducer code, 80% aggregate line coverage, and explicit coverage for every sealed variant.
- Spotless or formatter plus Checkstyle/SpotBugs; CycloneDX SBOM; OWASP dependency check in CI (with a documented cache/update policy).
- Reproducible JARs and a thin launcher distribution; add `jlink`/native packaging only after functional parity.

## 4. Traceability artifacts

Create these before broad implementation:

- `docs/PARITY.md`: one row per reference feature, source location, reference test, Java test, status, known difference, and evidence.
- `ajent-parity/src/test/resources/reference/`: immutable captured CLI, JSON, SSE/NDJSON, ACP/MCP, reducer-trace, and ANSI/cell fixtures, each with reference commit metadata.
- `ajent-parity/src/test/resources/test-manifest.json`: all 53 C++ test/probe files classified as unit, property/fuzz, integration, terminal oracle, benchmark, or fixture capture; Java counterpart and run command.
- `docs/DEPENDENCIES.md`: each non-JDK dependency, why it is needed, alternatives considered, and the parity tests protecting its boundary.
- `NOTICE` and `docs/PROVENANCE.md`: AgenTTY/Maya/ACP/MCP copyright and license notices plus a per-file/per-feature record of reference-derived behavior, fixtures, assets, and attribution obligations. Update these during every slice, not only at release time.
- `docs/DECISIONS/`: ADRs for module structure, immutable state/effects, terminal renderer, JSON compatibility, credential storage, ACP/MCP adapters, and packaging.

The parity ledger is a release gate: no `unknown`, `planned`, or unjustified `not applicable` rows at completion.

## 5. Construction graph

```text
S0 reference oracle
  -> S1 build skeleton + parity/provenance ledgers
      -> S2 domain/contracts + shared edge ports + pure tests
          -> S3 persistence + exact credential envelope --+
          |      -> S4 auth HTTP + provider transports    --+--> S7 loop kernel/reducer,
          -> S5 tools/process/git + checkpoints/diff      --+    then task subagents
          -> S6 skills/memory/RAG (excluding task)        --+
                                                               -> S8 ACP/MCP
                                                               -> S9 terminal engine
                                                                   -> S10 interactive UX
                                                                       -> S11 hardening
                                                                           -> S12 docs/release CI
                                                                               -> S13 publish
```

S3, S5, and S6 can be developed independently after S2; S4 depends on S3's credential store/envelope but owns HTTP/TLS/OAuth exchange and refresh. Integration into the agent loop is serial. The `task` tool is deliberately deferred from S6 until the reusable headless loop kernel exists in S7. S8 and the lower terminal engine can overlap after S7 contracts stabilize. Steps touching shared sealed types or fixture schemas must be serialized.

Reasoning tier: use the strongest available review/reasoning tier for S0 comparator design, S2 contracts, S4 wire protocols, S7 loop/reducer, S8 protocols, S9 rendering, and S11 security/differential review. Default execution is sufficient for mechanical fixture translation, documentation, packaging, and already-specified adapter work. Each step is one local, reviewable commit series because no Ajent remote exists until S13; do not create a remote early merely to manufacture PRs.

## 6. Executable steps

### S0 — Freeze and measure the reference oracle

Context: The local root is not a Git repository. The reference checkout is clean on `master` at the pinned commit. It contains 89 `.cpp` sources, 113 headers, 53 test/probe programs, 48 docs pages, about 50.7k production lines, 19.7k test lines, and 10.3k documentation lines. The current shell resolves Java/Maven to JDK 21, although JDK 25 is installed at `C:\lang\jdk-25` and Maven 3.9.12 uses it correctly when `JAVA_HOME` is set for the process. CMake is installed at `C:\bin\cmake-3.27.1\bin\cmake.exe` (and in two MinGW trees) but is not on PATH; no C++ compiler was resolved by the initial shell check. GitHub CLI is installed but its `skanga` token is invalid.

Tasks:

1. Record commit and submodule SHAs in fixture metadata and `docs/PARITY.md`.
2. Select the installed JDK 25 for the build and prove both `java -version` and `mvn -version` use it. Do not silently compile with 21.
3. Locate/enable the installed CMake and a C++26-capable MSVC/MinGW/Clang toolchain, then build the reference and all registered CTests on Windows if supported. If no compatible compiler is installed or a reference test is POSIX-only, run it in Linux CI/WSL later and record the platform constraint rather than weakening it.
4. Run CTest with output capture; separately build/run non-CTest probes and benchmark/capture tools where deterministic. Save command, environment, exit code, stdout/stderr hashes, and produced fixtures.
5. Capture `--help`, `--version`, invalid arguments, all subcommands, configuration precedence, and persistence schema behavior.
6. Define raw and structural comparators, then create scripted local HTTP servers for Anthropic SSE, OpenAI SSE, and Ollama NDJSON; capture raw requests/responses and emitted event traces from the reference.
7. Capture terminal output at fixed sizes with a virtual clock/input trace for welcome, composing, streaming Markdown, tools, permissions, pickers, diff review, thread switching, and shutdown.
8. Capture the complete native tool catalog (name, description, JSON schema, effects, timeout, permission flag, and output budget) plus representative real-dispatch success, validation error, workspace refusal, timeout/cancel, permission, and truncation results for every tool family.
9. Add a reference-only deterministic reducer harness and capture canonical message/effect/state traces for ordinary chat, fragmented streaming, tool batches, approval/rejection, retry/cancel, compaction, provider/model/profile switching, checkpoints, and thread lifecycle. If a state hash contains pointer/time noise, preserve the raw artifact and document the smallest field-level comparator rather than replacing it with a Java-defined hash.
10. Capture raw ACP and MCP client/server JSON-RPC transcripts before implementing either protocol: every ACP method/notification enumerated in S8, MCP initialize/capabilities/list/call/resources/prompts/list-changed/progress/shutdown, malformed requests, errors, permission callbacks, cancellation, and concurrent ordering.

Verification:

```powershell
java -version
mvn -version
git -C agentty rev-parse HEAD
cmake -S agentty -B agentty/build -DAGENTTY_BUILD_TESTS=ON
cmake --build agentty/build --target tests --config Release
ctest --test-dir agentty/build -C Release --output-on-failure
```

Exit: the reference test matrix and capture manifest are complete and contain all required fixture classes: CLI/config/persistence, provider wire, native tool catalog/dispatch, reducer traces, ACP/MCP raw transcripts, and terminal cell/ANSI captures. Every unavailable test or capture has a concrete Linux/macOS CI route. Rollback: captured artifacts can be regenerated because they contain the pinned SHA, comparator version, and runner version.

### S1 — Initialize Ajent and the test-first build skeleton

Tasks:

1. Initialize Git at the Ajent root. First create `.gitignore` with `/agentty/`, `/target/`, IDE files, secrets, live captures, and local Java toolchain files. Confirm `git status` never lists reference files before the first commit.
2. Review the licenses/notices in AgenTTY and all three pinned submodules; choose Ajent's compatible license, create `LICENSE`, `NOTICE`, and `docs/PROVENANCE.md`, and record attribution as fixtures, behavior, and assets are ported.
3. Create the reactor/modules above, package namespace `com.github.skanga.ajent`, Maven wrapper, and basic CI on Windows, Ubuntu, and macOS using JDK 25.
4. Add empty production ports/types only as needed to compile the first slice; no placeholder behavior that returns success.
5. Create the test manifest, parity ledger, comparator specifications, deterministic test clock/UUID/environment/filesystem/process/provider/terminal fixtures, and a raw reference-fixture verifier.
6. Complete the first green test-first slice: port CLI goldens plus `fsm_test`, `fuzzy_match_smoke`, `param_tag_repair_test`, and `model_caps_test`; observe assertions fail before implementation, implement only that behavior, and finish with default `mvn verify` green. Future untranslated tests remain manifest/fixture entries until their owning step. An optional `characterization` profile may expose expected-red future contracts but is never part of default CI.

Verification: `mvn -B -ntp verify` runs green on JDK 25. CI uploads test reports and raw/structural fixture diffs.

Exit: all later work has a stable build, deterministic seams, and traceability. Rollback: modules can be removed independently without touching the reference.

### S2 — Port domain model, shared edge ports, policies, and every pure test contract

Implement modern Java equivalents:

- Strong IDs as validated records (`ThreadId`, `ToolCallId`, `ModelId`, `CheckpointId`, `ToolName`, `MessageId`).
- Conversation/thread/message/tool-use records; sealed `ToolStatus` variants; images, compactions, pending permissions, todos, profiles, model catalog/capabilities/effort.
- Sealed session phases (`Idle`, `Streaming`, `AwaitingPermission`, `ExecutingTool`) containing the shared active context; retry state and stream state.
- Sealed message families matching all 15 top-level reference groups, with exhaustive pattern-switch reducers.
- Effects as an immutable bit set; the exact 48-cell permission matrix; exclusive-versus-composable scheduling and path-overlap refinement.
- UTF-8-safe head/tail/head-tail output budgets, partial JSON repair, weak-model tool-call salvage/dedup, and doom-loop guards.
- Stable ports for filesystem/atomic files, processes/Git, HTTP/TLS/proxy, credentials, clocks/IDs/environment, provider streams, and terminal I/O. These ports define ownership and error/cancellation types only; adapters are implemented by their owning later slice.

Before production algorithms in each area, translate all applicable assertions from `model_caps_test`, `param_tag_repair_test`, `tool_result_budget_test`, `scheduler_path_test`, `salvage_dedup_test`, `doom_loop_test`, `composer_edit_test`, `model_label_test`, `fsm_test`, and `fuzzy_match_smoke`. Add reflection-based tests proving every permitted sealed subtype is handled.

Verification: module tests, jqwik properties over all effect/profile cells and arbitrary UTF-8 truncation boundaries, JSON round-trips, and a generated variant-coverage report.

Exit: domain/core contracts are green without network, terminal, or real filesystem dependencies. Rollback: records are versioned via fixture schema; incompatible changes require fixture migrations.

### S3 — Persistence, settings, and exact credential envelope

Tasks:

1. Port thread/settings JSON exactly, including old-field compatibility, message/tool status variants, compactions, metadata-only thread listing, title generation, deletion, and debounced per-thread saves.
2. Implement atomic `temp -> fsync -> replace` writes with platform-specific best effort and tests for crash/failure paths. Default Ajent data to `~/.ajent`; provide an explicit compatibility/import command for `~/.agentty` rather than silently sharing files.
3. Port credential source precedence and file permissions, and reproduce the v1 envelope exactly: platform machine/user seed (Windows MachineGuid + USERNAME; POSIX machine-id + uid), HKDF-SHA256 with `agentty-credentials-v1`, and AES-256-GCM JSON envelope. Prove Java reads reference fixtures and AgenTTY reads Java fixtures. Any desired format change requires explicit user approval and a consistent top-level parity-scope change; one-time import alone is not parity.
4. Port the storage-side PKCE/auth value records and API-key/OAuth credential persistence. HTTP exchange, refresh, TLS, proxying, and browser launching belong to S4.

Tests first: persistence golden files (old and current), corruption/recovery, concurrent saves, auth precedence, PKCE vectors, file permissions, secret-redaction, and bidirectional cross-language credential fixtures.

Exit: persistence and credential-format parity rows are green on all supported OSes. Rollback: schema writers retain backups/migration version; credential tests use isolated roots and never destroy the source file.

### S4 — Auth HTTP/TLS and provider wire protocols/streaming

Tasks:

1. Implement reusable incremental UTF-8 line and SSE framers matching `wire.hpp`, cancellation, timeouts, headers, debug capture redaction, and backpressure.
2. Port OAuth PKCE browser/login exchange, refresh serialization, status/logout behavior at the composition boundary, JDK system trust, SOCKS/proxy and host overrides, TLS verification/insecure mode, and certificate tests. Never make insecure mode sticky.
3. Anthropic: request/system/tool schemas, OAuth/API-key headers, SSE events, content blocks, tool calls, usage, stop reasons, error classes, retry watchdog/caps, token refresh, and model catalog.
4. OpenAI-compatible: explicitly cover `openai`, `groq`, `openrouter`, `together`, `cerebras`, and raw `host:port`, including endpoint/header differences, provider-specific environment key then `OPENAI_API_KEY` fallback, saved-provider/per-provider-model behavior, message/tool translation, streamed fragmented tool-call assembly, reasoning/usage, finish reasons, errors, and weak-model content salvage.
5. Ollama: native `/api/chat` message/image/tool format, NDJSON framing, structured calls, errors, local defaults, no-key behavior, provider/model persistence, and no accidental OpenAI translation.
6. Use JDK `HttpClient` unless an observed parity requirement cannot be achieved; document and test any replacement.

Tests first: translate all assertions from `openai_transport_test` and `ollama_transport_test`; add captured Anthropic fixture tests from `anthropic_md_stream`, fragmented-byte framing properties, cancellation, retry-class tables, and request byte/semantic comparisons to reference captures.

Exit: scripted transports yield identical event traces under the reviewed normalization allowlist and exact structural request JSON/header sets (except explicitly order-insensitive JSON object/header comparison). Live smoke tests for each provider are opt-in and never required for ordinary CI. Rollback: transports are isolated adapters selected by the registry and can be reverted independently without changing domain/core contracts.

### S5 — Built-in tools, workspace boundary, sandbox, and subprocesses

Port all catalog entries with exact names, schemas, effects, timeouts, approval flags, and budgets:

`read`, `edit`, `write`, `bash`, `grep`, `glob`, `list_dir`, `todo`, `web_fetch`, `web_search`, `find_definition`, `diagnostics`, `git_status`, `git_diff`, `git_log`, `git_commit`, `remember`, `forget`, `wipe_memory`, `task`, `skill`, `search_docs`, `repo_map`.

Tasks:

1. Implement a single registry/catalog and validate uniqueness, schema completeness, effect parity, timeout behavior, and unknown-tool fail-closed behavior.
2. Port normalized/workspace-checked paths, symlink/junction traversal defense, stale-file snapshots, fuzzy edit matching, atomic writes, UTF-8 handling, and change records.
3. Port subprocess streaming/cancellation/output caps and OS shell encoding. Implement sandbox modes/backends with identical `auto/on/off` failures; tests must distinguish workspace boundary from OS sandbox.
4. Port Git tools using the Git executable with structured, locale-stable parsing and temporary-repository tests.
5. Port web tools with injectable HTTP/search backends and offline refusal tests. Do not bind core tool behavior to a third-party search service.
6. Port path-aware parallel batches: writes/exec serialize when blind or overlapping; independent paths and read/net work compose according to the reference.
7. Port checkpoint capture/list/diff-preview/restore and diff hunk accept/reject atop the now-real filesystem/process/Git adapters; preserve dirty-worktree safety.

Tests first: translate `toolset_e2e_test` end-to-end through the real Java dispatch path plus fuzzy matcher, scheduler, budget, workspace escape, symlink/junction, timeout, cancellation, and Windows quoting tests.

Exit: every built-in except `task` has schema/effect/output golden evidence and real-dispatch coverage; checkpoint/diff integration is green. `task` retains its catalog/schema entry but its execution adapter is completed in S7. Rollback: tools are registered independently and can be disabled only with an explicit failing parity row.

### S6 — Skills, memory, RAG, repo map, and subagents

Tasks:

1. Port skill discovery across `.ajent/.agents/.claude` user/project roots, shadowing, lenient frontmatter, resources, linting, activation/dedup/reset, and allowlisted reads. Add `.agentty` compatibility only as documented import/interop behavior.
2. Port JSONL user/project memory, selection budgets, remember/forget/wipe, corruption tolerance, and prompt injection boundaries.
3. Port chunking, stemming, BM25, cosine, RRF, HNSW persistence/search, query fusion, MMR, reranking, confidence, compression, knowledge sources/router/pipeline, and optional Ollama embeddings. Algorithms must match reference fixtures before optimizing with Vector API.
4. Port `search_docs` fallback/index cache and `repo_map` behavior, limits, language heuristics, and guidance.

Tests first: translate `skills_engine_test`, `rag_test`, `rag_hnsw_test`, `rag_rerank_test`, `rag_expand_test`, `rag_advanced_test`, and `knowledge_test`; add memory, repo-map, and subagent isolation properties.

Exit: all skills/memory/RAG/repo-map suites are deterministic and offline by default. Rollback: on-disk indexes carry format/version hashes and rebuild safely.

### S7 — Agent loop and complete pure reducer

Tasks:

1. Assemble the immutable `Model`, all message families, and per-domain reducers. Effects are descriptions executed by a structured-concurrency runtime; results return only as messages. Extract a reusable headless loop kernel before implementing recursive/subagent behavior.
2. Implement prompt construction/guidance, user turns/attachments, provider/model switching, reasoning effort, streaming deltas, tool scheduling, permissions, cancellation, queueing, retries, compaction, todo, thread switching, checkpoints, diff review, status banners, and shutdown.
3. Preserve phase invariants and ownership: every non-idle phase contains active context; stale stream/tool events are ignored by typed turn/call IDs; cancellation completes once.
4. Complete the `task` tool atop the reusable headless loop kernel with scoped tool registry, cancellation, output budgets, parallel fan-out, retry/doom-loop protections, and no permission escalation.
5. Emit a canonical reducer trace (`before hash`, message, effects, `after hash`) and compare raw and allowlist-normalized forms to reference scenarios.

Tests first: scenario tables covering every message subtype and phase edge; deterministic multi-tool/retry/cancel/permission/provider-switch traces; property tests for no orphaned active state, no duplicate effects, and eventual idle under bounded scripted inputs.

Exit: headless scripted sessions match raw reference artifacts wherever deterministic and match only the reviewed allowlist-normalized fields otherwise; `task` isolation/concurrency tests pass without any terminal renderer. Rollback: reducer slices remain separate and replayable from traces.

### S8 — ACP and MCP in both directions

Tasks:

1. ACP server over stdio JSON-RPC: `initialize`, `authenticate`, `logout`, `session/new`, `session/load`, `session/resume`, `session/list`, `session/close`, `session/delete`, `session/set_mode`, `session/set_config_option`, asynchronous `session/prompt`, and `session/cancel`, plus every notification/capability exposed by the pinned header. Preserve persistence replay, permission callbacks, workspace enforcement, concurrent-session/cancel behavior, ordering, and exact error mapping; no method may be waved away as “where supported.”
2. MCP server (`mcp-serve`): initialize/capabilities, tools/list, tools/call, progress/errors, stdio framing, and the same native registry/policies as the TUI.
3. MCP client: trusted user config, gated project config, explicit override, stdio and Streamable HTTP, tool/resource/prompt discovery, live `tools/list_changed`, namespacing/collisions, cancellation, shutdown, and effect defaults.
4. Evaluate official Java SDKs only behind protocol ports and retain them only if exact captured-wire tests pass. Otherwise implement the required JSON-RPC subset with Jackson/JDK HTTP.

Tests first: translate `acp_integration_test`, `mcp_bridge_test`, `mcp_http_test`, and Python smoke/method scripts into Java integration tests plus a raw golden JSON-RPC transcript and error/concurrency case for every enumerated ACP method and notification.

Exit: Zed ACP smoke passes; external MCP conformance client can list/call tools; Ajent consumes stdio and HTTP reference servers. Rollback: protocol adapters can switch implementation without changing core.

### S9 — Deterministic Java terminal/rendering engine

This step ports the behavior of the relevant Maya submodule, not merely AgenTTY adapters.

Tasks:

1. Cell/canvas/style/width engine with Unicode grapheme and wcwidth behavior, clipping, wrapping, overlays, mouse/key parsing, resize, alternate-screen/inline modes, and ANSI emission.
2. Inline renderer and scrollback ledger with append-only committed rows, frozen/live split, divider symmetry, canvas shrink, trim, resume warmup, and hash-keyed cache.
3. Streaming Markdown parser/renderer with GFM tables, lists, quotes, code fences, links, incremental prefix caching, asynchronous-settle rules, reveal cursor pacing/finalize ramp, stable height, and animation scheduling.
4. Visual hash and cache contracts: every visible model axis changes the hash; nonvisual counters do not; identical hashes skip frames without freezing reveal.
5. Virtual terminal emulator and controllable animation clock in test fixtures; JLine only owns native terminal access, not layout semantics.

Tests first: translate terminal suites and probes: `midrun_freeze_test`, `midrun_seam_test`, `midrun_wire_test`, `stream_liveness_test`, `stream_md_lag_test`, `table_render_test`, `reveal_pacing_test`, `stream_async_freeze_test`, `reveal_smoothness_probe`, `md_shape_sweep`, `md_cache_probe`, `reveal_freeze_gate_probe`, `visual_hash_coverage_test`, `frozen_invariant_fuzz`, `scrollback_wire_fuzz`, `reveal_scrollback_test`, and `scrollback_oracle_test`. Port `scrollback_prefix_harness` as an opt-in diagnostic. Use jqwik seed reporting.

Exit: append-only oracle, fuzz suites, rendering goldens, and performance-shape assertions pass at multiple terminal sizes on Windows and POSIX. Rollback: renderer has no I/O side effects and can be replayed from snapshots.

### S10 — Full interactive UX and platform integrations

Tasks:

1. Port widget hierarchy and design tokens: app layout, welcome, conversation/turn, agent timeline/tool previews, permission, checkpoint divider, activity indicator, changes strip, composer/chips, status bar components, overlays, pickers, palettes, todo, login, diff review, and tool output viewer.
2. Match all keybindings, command palette actions, queue behavior, focus/cursor semantics, accessibility-friendly no-animation mode if reference provides it, terminal suspend/resume, Ctrl+C/Esc behavior, and clean terminal restoration on exceptions/signals.
3. Port code-block discovery/picker/cleaning/platform shell selection/interactive execution, 2 MiB capture, result card, attach/copy/discard.
4. Port clipboard text/image capture and platform fallbacks, attachments, MIME/size validation, and remote override.
5. Port air-gap setup/run/ACP config generation using `ssh`, SOCKS forwarding, credential copy, TLS host preservation, signal forwarding, quoting, and cleanup.

Tests first: golden input-event scenarios, captured cell grids/ANSI, `composer_edit_test`, code-block cases, fake clipboard/process tests, air-gap command construction, and crash-terminal-restoration subprocess tests.

Exit: the interactive flows match reference captures and manual acceptance checklists on Windows Terminal plus one Linux and one macOS terminal. Rollback: all platform commands are built as argument vectors and dry-run-testable.

### S11 — Differential hardening, performance, and security

Tasks:

1. Run every manifest row. Compare normalized outputs against the pinned reference; investigate rather than update goldens until the difference is understood and recorded.
2. Port build-only probes to JMH/diagnostic runners: `long_session_bench`, `o1_probe`, `realthread_probe`, `tallcard_probe`, `stream_cpu_probe`, `loop_body_split_probe`, `composer_flicker_probe`, `edit_turn_cpu_probe`, `reveal_lag_probe`, `tool_boundary_burst_probe`, `anthropic_md_stream`, and `md_shrink_debug`.
3. Set performance shape gates: no O(total transcript) work per streaming frame, stable long-turn Markdown cost, bounded memory for tool-heavy threads, cancellation latency, and startup measurements. Java need not match sub-millisecond native startup, but any user-visible regression must be measured/documented.
4. Threat-test workspace escapes, symlink/junction TOCTOU, shell injection, malicious MCP/ACP payloads, ANSI injection, oversized provider/tool frames, credential leakage, unsafe deserialization, path traversal in skills, and decompression/resource exhaustion.
5. Run mutation testing on policy, scheduling, truncation, framing, and reducer guards; add tests for surviving mutants.

Verification:

```powershell
mvn -B -ntp clean verify
mvn -pl ajent-parity -Pparity verify
mvn -pl ajent-parity -Pbench verify
```

Exit: all 53 manifest rows close, differential suite is green, no critical/high dependency or code findings remain, and performance curves are bounded. Rollback: fixture changes require review with old/new semantic diff.

### S12 — Documentation, distribution, and release CI

Tasks:

1. Write `README.md`, `CHANGELOG.md`, contribution guide, architecture, design, auth, rendering, inline scrollback, UI/widget reference, run-code-block, troubleshooting, and parity docs.
2. Recreate the website-manual breadth: quick start, installation, providers, profiles, interface, CLI, configuration, workspace, threads, tools, skills, sandboxing, proxies, air-gap, ACP, MCP, architecture, building, FAQ, and platform notes.
3. Generate CLI help and configuration/tool tables from production metadata where possible, with tests that docs do not drift.
4. Produce cross-platform launch scripts and distributions. Start with a reproducible Maven-built application plus JRE guidance; then add `jlink`/`jpackage` artifacts for Windows, Linux, and macOS. Do not claim a single static binary.
5. CI matrix: JDK 25 on Windows/Linux/macOS; unit/integration/parity; Linux terminal/fuzz; packaging smoke; SBOM; dependency/security scans; artifact checksums; release workflow.

Exit: a new user can install, authenticate, run a turn, understand permissions, configure providers/MCP, troubleshoot, and contribute using Ajent docs alone. Documentation parity checklist is complete. Rollback: generated-reference sections are reproducible from production metadata; packaging workflows remain separate from the verified Maven artifacts until each platform smoke passes.

### S13 — Clean-history GitHub publication

Prerequisites: every prior exit gate passes; user has reauthenticated `gh`; publication is explicitly authorized by the goal.

Tasks:

1. Verify `agentty/` is ignored and absent from index/history: `git check-ignore agentty`, `git ls-files agentty`, and a history object/path audit.
2. Re-audit the continuously maintained license/provenance ledger and ensure no C++ reference files, secrets, live captures, credentials, local paths, or build outputs are committed while all required AgenTTY/submodule attribution remains present.
3. Commit Ajent intentionally in coherent history, create public repository `skanga/Ajent`, set `origin`, push the default branch, and enable required CI settings.
4. Verify repository contents via GitHub, CI status, clone into a clean temporary path, run `mvn verify`, and smoke the packaged CLI.
5. Tag the first release only after clean-clone verification; attach artifacts, checksums, SBOM, release notes, and parity statement.

Verification:

```powershell
git check-ignore agentty
git ls-files agentty
git status --short
gh auth status
gh repo create skanga/Ajent --public --source . --remote origin --push
gh run list --repo skanga/Ajent
```

Exit: `https://github.com/skanga/Ajent` exists, contains no `agentty` checkout/history, CI is green, clean-clone verification passes, and documentation/release artifacts are visible. Rollback: repository creation/push happens last; until then all work is local and recoverable.

## 7. Test-port classification rule

- `*_test.cpp` and registered correctness probes become ordinary JUnit/Failsafe tests.
- Randomized fuzz programs become jqwik properties with reproducible seeds and bounded CI examples plus nightly extended runs.
- Render/scrollback tests retain both semantic cell-grid assertions and wire-level ANSI/oracle assertions.
- `*_probe.cpp`, `*_bench.cpp`, capture tools, and deliberately unregistered executables become JMH or opt-in diagnostic profiles; their underlying invariants become normal tests when possible.
- Reference-submodule-only behaviors (especially Maya Markdown/rendering) are still in scope because AgenTTY's observed behavior depends on them.
- Platform-skipped reference tests are not dropped; they run on the applicable CI OS and have an explicit skip reason elsewhere.

## 8. Anti-patterns that invalidate the port

- Declaring parity because the Java code compiles or because happy-path chat works.
- Replacing provider transports with a generic LLM abstraction and accepting changed JSON, retries, usage, or tool-call behavior.
- Replacing terminal behavior with line-oriented printing or an unrelated widget framework.
- Translating source file-by-file without first extracting fixtures and behavioral contracts.
- Porting only CTest-registered binaries while ignoring build-only probes that encode performance/rendering requirements.
- Updating golden files to make Java pass without demonstrating why the reference output is wrong or the difference is intentional.
- Sharing mutable application state across network/tool/render threads.
- Using sleeps, real clocks, live services, user home directories, or ambient credentials in default tests.
- Weakening workspace/sandbox checks on Windows because POSIX primitives differ.
- Committing the `agentty` checkout, secrets, generated captures, or provider responses.
- Publishing before GitHub auth, clean-history audit, CI, and clean-clone verification succeed.

## 9. Plan mutation protocol

This plan may change only through a recorded entry in `docs/PARITY.md` or an ADR:

1. State the discovered reference behavior/evidence.
2. Identify affected steps, fixtures, tests, and dependency edges.
3. Choose split/insert/reorder/abandon; never silently delete a parity requirement.
4. Add or update a failing test before changing implementation.
5. Re-run downstream gates and record the outcome.

An upstream AgenTTY update is a new milestone: pin the new SHA, diff tests/docs/source, add new ledger rows, regenerate only affected fixtures, and repeat differential review.

## 10. Immediate next milestone

Execute S0 only: select JDK 25, prove the reference build/test situation on this Windows host, and create the reference/test manifest. Do not scaffold broad production code until the oracle and test classification exist.
