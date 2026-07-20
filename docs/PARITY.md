# Ajent parity ledger

Ajent targets behavioral parity with AgenTTY commit
`c7594d64020cfdacb10b6a0b2074bcedcc827bba`. The pinned dependency commits
are recorded in the machine-readable manifests under
`ajent-parity/src/test/resources/reference/`.

## Evidence rules

- Raw reference artifacts are retained and hashed before any normalization.
- JSON comparison requires exact field presence, types, values, and array
  order. JSON object key order is ignored.
- The only standing normalization allowance covers generated timestamps,
  generated identifiers, the declared program-name difference, and declared
  workspace/data-root prefixes.
- Every additional normalized or deliberate difference requires a ledger row
  with evidence and review. “Semantically similar” is not parity.
- A feature remains incomplete until its reference capture, Java test,
  implementation, differential result, and documentation evidence are all
  present.

## Reference baseline

| Item | Evidence | Status |
|---|---|---|
| AgenTTY source | pinned commit in `capture-manifest.json` | frozen |
| Maya | `8c655268272b416faed1ba13ffb6d36c292415ed` | populated |
| acp-cpp | `d8b80082f021fe15a081ddd9fe812667f9435ade` | populated |
| mcp-cpp | `f87d78aa5e031cb80257692b3379805d54e54ca5` | populated |
| Reference tests/probes | all 53 rows in `test-manifest.json` | inventoried; all 53 ported from source |
| Reference executable | Windows 0.2.8 binary SHA-256 in `capture-manifest.json` | verified |
| JDK 25 | user `JAVA_HOME`/`PATH` and project-local Maven toolchain select `C:\lang\jdk-25` | `java -version`, `mvn --version`, and `mvn test` green |
| Native suite | source is pinned; POSIX-only probes require Linux CI | deferred to cross-platform CI |

The optional executable characterization gate uses the ignored
`agentty/agentty.exe` checkout and never packages it:

```text
mvn -q verify -Pcharacterization
```

It verifies the pinned SHA-256 first, then compares real Ajent and AgenTTY
process exit codes and stdout/stderr bytes for version, help, invalid arguments,
isolated credential status/logout, installed-skills inventory, air-gap help,
invalid-workspace ACP/MCP startup, and the complete offline ACP session lifecycle
over real stdio. Only the manifest-declared program name, generated ACP session
id, and temporary home prefix are normalized; compatibility identifiers such as
`.config/agentty` and `--remote-agentty` stay literal. This gate exposed and now
regresses native Windows CRLF translation and the final blank usage line.

`NativeAcpParityIT` keeps both real processes alive and compares the exact
normalized JSON-RPC frame sequence for initialize, session creation, mode and
config changes, list/filter/load/resume/close/delete, logout, and
method-not-found handling. It also invokes each real `login` command in an
isolated home, then proves identical missing-`methodId` validation, successful
authentication from the persisted API key, logout, and post-logout
authentication failure. A loopback OpenAI-compatible provider also drives
both executables through the same streamed text/tool/usage turn, allow-once
permission request, real file write with ACP diff content, tool-result
continuation, and final stop reason. A second live turn selects `reject_once`,
proves the requested file remains absent, compares the rejected tool-result
continuation, and reaches the same final response. A third live turn selects
`allow_always` once, executes two sequential writes without a second permission
callback, and compares all three upstream requests. A fourth turn holds a local
HTTP stream open, sends notification-only `session/cancel`, and compares the
cancelled prompt result including native transport `_meta.error`. The gate
also holds two independent sessions at a provider barrier until both requests
are simultaneously in flight, then compares each session's exact frames without
assuming a cross-session scheduling order. It compares complete OpenAI-compatible
request sequences after workspace-only normalization, including system/user/history
messages, the provider-specific 22-tool subset and order,
output controls, canonical tool-argument replay, and continuation content.
This differential exposed and now regresses native omission of empty pending
`rawInput`, tool-metadata-before-usage ordering, structured file-change
projection, ACP text-block newlines, provider-specific tool ordering, and
canonical tool-argument serialization. The live write differential advertises
all ACP client filesystem and terminal capabilities and fails on any outbound
`fs/*` or `terminal/*` request. Both executables accept those capabilities but
keep tools application-local, matching `AgentServer::on_initialize`, which
does not retain client capabilities, and the absence of any callback call site
in AgenTTY. The similarly named methods in `acp-cpp` are generic library
surface, not reachable AgenTTY application behavior.

`NativeMcpParityIT` likewise compares real standalone MCP processes through
initialize, initialized notification, ping, every field of the ordered
22-tool catalog, an actual workspace write and structured change result,
method-not-found handling, and EOF shutdown. It exposed and now regresses the
native `serverInfo.title`, registry-specific catalog order and `repo_map`
omission, and correct Unicode punctuation throughout published tool metadata.
The same suite now configures real downstream stdio and Streamable HTTP
fixtures for both executables. It compares initialization and discovery,
text/structured/image/audio and error tool results, resources and templates,
resource reads, prompts, progress delivery, list-change refresh, request
timeouts without an invented cancellation notification, JSON/SSE envelopes,
session/protocol/custom HTTP headers, and shutdown behavior.
It also sends an explicit cancellation notification during a blocking
configured call and pins the shared synchronous behavior: the notification is
accepted but cannot preempt or propagate through the call, which retains its
configured timeout.

## Deliberate differences

| Surface | Pinned AgenTTY behavior | Ajent behavior | Evidence and rationale |
|---|---|---|---|
| Anthropic fields added after the 0.2.8 binary build | The downloaded pinned Windows binary omits the checked-in `<big-codebases>` and `<in-house-languages>` system-prompt sections and the `todo` tool's `eager_input_streaming` field. | Ajent includes all three source-defined fields. | `NativeAcpParityIT.nativeAnthropicRequestAndFragmentedSseMatchPinnedExecutable` first asserts the binary/source skew directly against `transport.cpp` and `spec.hpp`, then removes only those three fields before comparing every remaining request field. Following the frozen source preserves the intended current behavior instead of reproducing a stale release artifact. |
| Cold concurrent ACP tool catalogs | `AgentServer::wire_tools()` sets `wire_tools_built_` before filling its shared vector without synchronization. With two first prompts simultaneously composing requests, the pinned executable reproducibly sends the full 22-tool catalog on one request and omits `tools` on the other. | Both requests receive the complete immutable 22-tool provider catalog. | `NativeAcpParityIT.concurrentPromptsInSeparateSessionsMatchPinnedExecutable` requires the two-request barrier, exact isolated ACP frames, the native one-full/one-empty race shape, two identical full Java catalogs, and equality of every other request field. Reproducing undefined C++ data-race behavior would weaken concurrent agent functionality and Java memory safety, so this difference is preserved deliberately rather than silently normalized. |
| Configured MCP tool ordering | AgenTTY inserts external tools into an unordered map, so adding a server rehashes and interleaves native and external catalog entries. | Ajent returns the same tool set in deterministic local-first order. | `NativeMcpParityIT.configuredStdioClientMatchesPinnedExecutable` compares the complete catalog after sorting by name. MCP does not assign semantic meaning to `tools/list` order, and retaining deterministic Java order avoids importing hash-layout accidents. |
| Streamable HTTP startup ordering | AgenTTY can issue `notifications/initialized` and the first `tools/list` POST in either order because dispatch continues concurrently. | Ajent sends them deterministically in initialization order. | `NativeMcpParityIT.configuredStreamableHttpClientMatchesPinnedExecutable` canonicalizes only these two startup records and compares every request body and header otherwise. The race has no protocol-visible dependency and deterministic startup is safer. |

## Feature ledger

| Surface | Reference fixture | Java test | Implementation | Differential result | Status |
|---|---|---|---|---|---|
| CLI and configuration | help/version/invalid raw fixtures plus the pinned Windows executable, `src/runtime/main.cpp` dispatch, `skills.cpp::cmd_skills`, and `airgap/airgap.cpp` | `AjentCliTest`, `AuthCommandsTest`, `SkillCommandsTest`, `McpServeCommandTest`, `AcpCommandTest`, `AirgapCommandTest`, `ProcessSandboxTest`, `CliParityTest`, `NativeExecutableParityIT`, `InteractiveCommandTest` | exact parser/help/version/invalid handling, including native Windows CRLF process output and the terminal blank usage line; testable top-level command seam; working API-key/OAuth `login`, secret-safe `status`, idempotent `logout`, six-root `skills` inventory with source/path/description/resources/model-visibility, full spec warnings, empty-install guidance, and CI exit status; operational `mcp-serve` and `acp` with native workspace validation, protocol isolation, and sandbox lifecycle; ACP additionally applies settings/provider credential precedence, Ask-default profile validation, and keyless-local prompt authorization; operational `airgap` with reverse dynamic SOCKS SSH, native liveness ordering and exit propagation, terminal markers, explicit and synthesized X11/Wayland clipboard relay, fail-fast three-step credential setup, complete trust-boundary help, and no-PTY Zed ACP configuration generation; interactive startup restores provider/model/effort/profile and durable always-allow grants, with profile changes atomically clearing the trust baseline | default full reactor and compiled Java 25 ACP stdio launch green; opt-in executable gate verifies the binary hash and exact process exit/stdout/stderr for version, help, invalid argument, isolated status/logout, installed skills, air-gap help, and invalid-workspace ACP/MCP after only declared program-name/home normalization; generated Zed arguments also match the provided binary | partial — interactive login and live-view differential cases remain |
| Pure FSM/model/edit/scheduler contracts | pinned C++ tests | translated unit tests, including all 13 scheduler cases | implemented | source assertions translated; native CTest confirmation pending | partial |
| Thread/settings persistence | `persistence.cpp`, `store.hpp`, and saved-thread picker/swap source | `ThreadStoreTest`, `SettingsStoreTest`, `AsyncThreadWriterTest`, `FilePersistencePortTest`, `ThreadPickerTest`, live swap/cycle coverage in `InteractiveCommandTest`, lifecycle coverage in `AgentLoopTest`, expanded `ConversationTest`, `NativeExecutableParityIT`, `NativeAcpParityIT` | complete persisted conversation shape, typed id-based load errors, legacy status migration, newest-first metadata-only walk, atomic thread/settings writes, exact settings ordinals/omissions, coalescing background writer, concrete runtime filesystem adapter, effect-worker-then-writer drain ordering on loop shutdown, and asynchronous saved-thread browse/load with current-thread anchoring, native navigation, cancellation/drain/preserve-before-swap, and wholesale scrollback reset | source-derived suites cover legacy fields, metadata-only walking, malformed state, credential envelopes, and corruption. Executable gates compare model persistence before sandbox validation, preservation of every unrelated settings collection/map, complete two-message ACP thread JSON, native title derivation, session-index JSON, generated id/timestamp shapes, graceful shutdown flush, and atomic temp cleanup | complete |
| Git workspace checkpoints | `workspace/checkpoint.cpp`, checkpoint picker, submit stamping, and restore reducer | `GitCheckpointStoreTest`, `CheckpointPickerTest`, checkpoint submit coverage in `AgentReducerTest`, live rewind coverage in `InteractiveCommandTest` | cached repo discovery; AgenTTY-compatible `refs/agentty/checkpoints/<id>` parentless commits; copied throwaway `GIT_INDEX_FILE`; tracked/untracked snapshots with ignore semantics and real-index isolation; 64-ref pruning; tree-to-tree rename-aware numstat including binary files; preflighted restore that rewrites snapshot files, deletes later non-ignored files, and preserves ignored output; checkpoint/user-message identity; oldest-to-newest picker with newest default, wrap/Home/End/j/k navigation and asynchronous diff states; idle/repo/history gates; filesystem restore followed by transcript/compaction truncation, durable replacement loop, old-prompt refill, and wholesale scrollback reset | real temporary Git repositories prove clean/changed/binary diff and byte restoration on Windows; live reducer/UI integration and full reactor gates are green; native binary differential capture remains | partial |
| Credential v1 envelope and OAuth lifecycle | `cred_crypt.cpp` fixed HKDF/AES-GCM contract, `auth.cpp` path/precedence/PKCE/refresh/command rules, and Anthropic OAuth constants | `CredentialCryptTest`, `CredentialStoreTest`, `CredentialResolverTest`, `CredentialPathsTest`, `MachineSeedTest`, `AnthropicOAuthClientTest`, `AnthropicOAuthLoginTest`, `AuthCommandsTest`, `CredentialOAuthRefreshPortTest`, runtime OAuth cases in `AgentReducerTest`/`AgentLoopTest`, `LoginModalTest`, `InteractiveCommandTest` | authenticated v1 envelope, tamper rejection, legacy plaintext migration, private atomic writes, CLI/environment/saved precedence, non-blocking expired-OAuth handoff, AgenTTY config path and Windows MachineGuid/user seed; exact Claude public-client authorization URL, S256 PKCE challenge, scopes, callback, state, joined `code#state` handling, and authorization-code/refresh forms over bounded JDK HTTP; typed network/bad-response/API/missing-token errors; working API-key and OAuth login with browser handoff and persisted expiry, secret-safe environment/saved status with every expiration variant, and idempotent logout; refresh-token fallback, expiry calculation, best-effort encrypted persistence, in-memory install, parked-stream lifecycle, OAuth dial-host override, and live TUI login modal | fixed cryptographic vector matches OpenSSL contract; loopback HTTP proves both code-exchange and refresh request/response shapes; command and modal tests prove saved results and non-disclosure; native binary differential capture remains | partial |
| Anthropic provider | `provider/anthropic/transport.cpp`, tool-result budget, image, cache-pinning, adaptive-thinking replay, system-prompt, SSE event, header, beta, request-body, cancellation, and liveness contracts | `AnthropicMessagesTest`, `AnthropicStreamDecoderTest`, `AnthropicWireTest`, `AgentSystemPromptTest`, `LiveProviderFactoryTest`, `ProviderModelCatalogTest`, Anthropic and stalled-stream loopback cases in `ProviderHttpTransportTest`, `HttpProviderPortTest`, `InteractiveCommandTest`, `NativeAcpParityIT` | message-block serializer; 64 KiB UTF-8-safe tool-result budget; last-two-message and last-tool cache pinning; thinking-enabled verbatim signed-thinking replay; hosted action/edit/shell/output/context/repository/DSL/memory behavioral prompt with platform-specific environment notes, bounded user/project/local CLAUDE.md tiers, durable learned-memory projection, and lazy skills catalog; call-time request composition with the exact native 22-tool provider catalog, model-specific output ceiling and override, effort/auth, and default model; exact typed OAuth/API-key headers, model/auth/tool-sensitive beta selection, OAuth preamble, system cache block, metadata identity, and adaptive-thinking request fields; byte-incremental SSE decoding for start, heartbeat, text close, thinking/signature, tool JSON, four-counter usage, all native stop reasons, forward-compatible unknown frames, mid-tool EOF repair, and exactly-once terminal events; live bounded JDK HTTP streaming with typed HTTP errors and Retry-After; cancellable asynchronous header wait and virtual-thread body watchdog that closes blocked reads within the 50 ms poll window or after the native 90-second byte-idle limit without duplicating terminal events; call-time runtime routing that consumes refreshed configuration, live model discovery, and persisted startup selection to complete real `AgentLoop` turns | source-derived suites cover API-key and OAuth composition; the executable gate compares exact API-key headers and complete initial/continuation bodies, fragmented SSE text/tool/usage, real permissioned execution, one-shot 429 refusal, cancellation, and ACP transcripts. The three source-ahead fields in the downloaded binary are isolated in the deliberate-difference ledger | complete |
| OpenAI-compatible providers | `openai_transport_test.cpp` assertions plus provider-selection/weak-model branches in `main.cpp` and `cmd_factory.cpp` | `OpenAiWireBuildersTest`, `OpenAiRequestTest`, `ProviderHttpTransportTest`, `ProviderModelCatalogTest`, `ProviderRegistryTest`, `LiveProviderFactoryTest`, `OpenAiStreamParserTest`, `OpenAiStreamDecoderTest`, `HttpProviderPortTest`, `InteractiveCommandTest`, `NativeAcpParityIT`, wire-framer tests | presets/auth resolution, exact request shape, JDK HTTP streaming on ordinary routes plus a narrowly scoped OkHttp HTTP/2 adapter for native dial/SOCKS overrides, model listing, SSE/native parsing, byte-incremental decoding, salvage, Retry-After, pre-header and blocked-body cancellation, exactly-once termination, 90-second byte-idle recovery, and per-call runtime routing; overridden hosted routes preserve logical URI/Host/SNI/certificate verification while changing only the TCP destination; shared live request composition selects compatible versus native Ollama dialect, injects a separately worded concise OpenAI-local prompt, carries probed context windows, publishes the native 22-tool provider subset in recall-biased order, switches weak Ollama models to JSON protocol while hiding skill/memory footguns, resolves persisted startup settings/credentials, canonically replays tool arguments, and implements the native retry classification table; explicit reducer provider-retry mode preserves interactive recovery while making ACP fail fast like the native direct stream loop | all enabled source assertions and provider-routing cases translated and loopback integration green; pinned-executable differential proves exact initial, fragmented two-tool batch assembly and canonical continuation, weak-model fragmented leaked-content salvage with one execution and structured continuation, accepted/rejected/sequential tool-result continuations, one-shot ACP `429`/`400` error behavior, local and hosted cancellation, and concurrent request behavior plus streamed text/tool/usage/stop behavior; an isolated HTTP/2 TLS fixture proves exact OpenAI/Groq/OpenRouter/Together/Cerebras paths, logical Host, bearer/content headers, versioned user agent, request body, result, and native `stream reset` cancellation, with the cold concurrent native catalog race recorded above | complete |
| Ollama provider | `ollama_transport_test.cpp` assertions | `OllamaWireTest`, `OllamaRequestBodyTest`, `OllamaStreamParserTest`, `OllamaStreamDecoderTest`, `ProviderHttpTransportTest`, `ProviderModelCatalogTest`, `NativeAcpParityIT` | native/JSON-protocol history and request bodies, exact slim prompt and memory tiers, environment options, model capability/context probing, structured/salvaged calls, native `call_ollama_<sequence>_<index>` synthesis, response pseudo-tool, model-specific 404 recovery hints, JDK HTTP and byte-incremental NDJSON | all 39 source scenarios translated and loopback integration green; pinned-executable ACP differentials prove exact native request bodies, fragmented text/usage/stop NDJSON, persisted-image/base64 replay, no-key local operation, structured tool execution, permission and role-tool continuation, weak-model grammar/catalog construction, fragmented JSON-protocol salvage with alias repair and filesystem execution, canonical JSON-protocol continuation and response unwrapping, 404 refusal diagnostics, and cancellation of an open NDJSON body | complete |
| Native tool catalog/dispatch | pinned `mcp-cpp` argument/filesystem/search/git/host/memory/web sources, subprocess idle-watchdog, shell-spill, rich-search, and shared file-state-cache contracts, web tests, AgenTTY `spec.hpp`/`mcp_tools_bridge.cpp`, `subagent.hpp`/`mcp_tools_backends.cpp`, and `toolset_e2e_test.cpp` | family suites plus `ToolCatalogTest`, `NativeToolWireCatalogTest`, `ToolDispatcherTest`, `ToolRuntimeFactoryTest`, `ToolRuntimeTest`, `ProviderBackedSubagentRunnerTest`, `JdkWebTransportTest`, `ProcessRunnerTest`, `ProcessToolsTest`, `ProcessSandboxTest`, `SearchToolsTest`, edit matcher/repair tests | all 23 operational catalog bodies and one exhaustive catalog-backed dispatcher; distinct exact native 22-tool projections for standalone MCP registration order and provider recall-biased order, both omitting `repo_map`, with exact descriptions/input schemas/annotations and stricter runtime permission metadata kept separate; a production composition root for filesystem/process/search/map/git, durable memory, six-root skills, document retrieval, todos, provider-backed subagents, and bounded redirect-following JDK HTTP; provider-agnostic isolated `task` turns on the shared headless loop with exact explorer/reviewer/tester/coder/general role prompts and allowlists, no recursive task exposure, read-only effect filtering, call-start snapshots of the currently selected provider/model/auth, 32k output requests, three 1/2/4-second clean retries, partial-turn discard, 24-turn and depth bounds, real tool execution, three-identical-failure breaker, typed 80 ms activity feed, parent cancellation, and condensed report harvesting; platform process sandbox selection/wrapping; byte-activity-reset subprocess idle watchdog with zero meaning unbounded, bounded 8 MiB merged capture, native 30,000-byte shell spill threshold, 2,000-byte head/ten-error-line/1,000-byte tail envelope, and sandbox-readable persisted output; whole-file grep with literal/regex occurrence counting, merged two-line context, enclosing-symbol breadcrumbs, 20/500 pagination, binary/NUL filtering, and a 20,000-byte inter-file guard; process-wide canonical file snapshots with mtime, size, and FNV-1a fingerprints, stale external-change warnings for write/edit, current-byte edit semantics, and post-mutation refresh; HTTPS fetch and three-engine search | every operational catalog body routes through Java; interactive/ACP/subagent requests publish the native provider-facing 22-tool subset while `mcp-serve` publishes its distinct native-ordered 22-tool registry; the complete pinned production-dispatch oracle is translated as one sequential real-workspace test spanning the 23-tool catalog/schema loop, filesystem/diff, search/map, bash, sandbox refusal, todo, durable memory lifecycle, memory-backed RAG, missing skill/task, offline web validation, real Git, and diagnostics; scripted multi-turn, live-selection snapshot, role isolation, retry, doom-loop, budget, activity, cancellation, output persistence, rich grep, and cache cases are also green; both wire orders and paired-workspace execution of all 22 standalone MCP tool families match the pinned executable after only declared nondeterminism normalization; direct edge differentials also prove validation, workspace recovery guidance, timeout, the native synchronous-cancellation limitation, large implicit smart reads, structured changes, and UTF-8-safe provider-plus-dispatcher truncation | complete |
| Permissions and scheduling | exhaustive `policy.hpp`, `effects.hpp`, `spec.hpp`, `tool.hpp`, and 13-case `scheduler_path_test.cpp` | `ToolPolicyTest`, `ToolCatalogTest`, `OutputBudgetTest`, `ToolDispatcherTest`, `ToolSchedulerTest`, `AgentReducerTest`, `InteractiveCommandTest` | exact 48-cell permission matrix/reasons, 23-tool ordered capability catalog, task scheduling override, coarse and path-aware parallel scheduling, dispatcher-enforced UTF-8-safe per-tool output budgets with native head/tail/head-tail markers, structured-change preservation, zero-budget bypass, unmodified typed failures, blocking approval/rejection, durable always-allow grants, startup rehydration, and profile-change trust reset | source assertions, reducer lifecycle, persisted trust transitions, and a real oversized-read dispatch translated; native differential capture remains | partial |
| Skills and memory | pinned `mcp-cpp` host/memory shells, AgenTTY `memory_store.cpp`, `skills.cpp`, `skills_engine_test.cpp`, and toolset e2e cases | `HostToolsTest`, `MemoryToolsTest`, `JsonlMemoryStoreTest`, `MemoryPromptTest`, `SkillEngineTest`, `KnowledgePipelineTest`, `AgenttyDocRetrieverTest`, `AgentSessionFactoryTest`, `InteractiveCommandTest` | typed host protocols; legacy-compatible durable JSONL with corruption handling, UTF-8 caps, Jaro dedup, supersede/rollover/atomic mutation, and bounded prompt projection; six-root Agent Skills discovery/precedence, lenient frontmatter and block scalars, metadata, hidden skills, bounded tier-3 resources, read-only sandbox allowlisting, catalog/activation payloads, activation dedup, lint, concrete host resolver, and lazy searchable skill/memory sources; hosted/local prompt projection and the shared durable memory, skill, and knowledge backends are composed for both ACP and the interactive TUI | all 44 deterministic `skills_engine_test.cpp` checks are translated, including precedence, lenient metadata, folded/literal blocks, hidden catalog behavior, resource payloads, activation deduplication, name/directory lint, and read-only external allowlisting; production ACP and interactive composition use the concrete backends; native differential capture remains | partial |
| RAG and repository map | pinned `mcp-cpp` repository map plus AgenTTY `rag_test.cpp`, `rag_hnsw_test.cpp`, `rag_expand_test.cpp`, `rag_rerank_test.cpp`, `knowledge_test.cpp`, and advanced stemming/semantic/MMR/confidence/hot-reload/cache/MCP/neural cases | `RepoMapToolsTest`, `RagCoreTest`, `HnswIndexTest`, `EmbeddingClientTest`, `RagQueryExpanderTest`, `RagRerankerTest`, `NeuralRerankerTest`, `RagStemmerTest`, `RagCorpusFilesystemTest`, `KnowledgePipelineTest`, `AgenttyDocRetrieverTest`, `InteractiveCommandTest`, `AcpCommandTest` | repository PageRank map; line-aligned semantic chunks and contextual hybrid BM25+dense retrieval with RRF, exact brute-force below 2,000 chunks and pure-Java HNSW above it; native little-endian graph/cache serialization and Windows file-clock metadata; bounded Ollama `/api/embed` batches with graceful lexical fallback and AgenTTY environment resolution; opt-in Porter normalization and generative multi-query expansion; deterministic feature reranking plus bounded-wave opt-in neural scoring, both through concrete JDK Ollama adapters; extractive compression, MMR, contexts/confidence; provenance-stamped multi-source router, filters, normalization, composable pipeline, canonical `search_docs` funnel, and production interactive/ACP knowledge composition | every deterministic assertion from all six pinned RAG/knowledge programs is translated, including vector numerics, fused-query edges, cache round trips, hot reload, neural degradation, provenance, compression fallback, native HNSW serialization, and live composition; executable manifest integrity guards the real counterpart paths and evidence; native differential capture remains | partial |
| Agent loop/reducer | `domain/session.hpp` phase/retry proofs, `domain/conversation.hpp` tool-state timestamps, `provider/error_class.hpp`, `tool/util/partial_json.cpp`, `runtime/app/update` stream/tool/compaction lifecycle, `cmd_factory.cpp` wire projection, scheduler, doom-loop, and salvage-dedup sources | `ConversationTest`, `SessionPhaseTest`, `ActiveTurnTest`, `ProviderErrorPolicyTest`, `PartialJsonTest`, `ConversationWireTest`, `AgentReducerTest`, `CanonicalReducerTraceTest`, `AgentLoopTest`, `ProviderBackedSubagentRunnerTest`, `HttpProviderPortTest`, `DispatcherToolPortTest`, `ToolSchedulerTest`, `DoomLoopBreakerTest`, `SalvagedCallDeduplicatorTest` | sealed four-phase typestate with active context absent from idle and exact transition legality; timestamped sealed tool statuses that retain monotonic start/finish invariants across pending, approval, execution, completion, failure, rejection, and cancellation; pure immutable submit/stream/tool/permission/cancel/compact/tick reducer with stale-turn rejection, UTF-8 8 MiB caps, typed effects, catalog-backed tool adapter, path-aware parallel scheduling, cancellable tool execution, typed stale-safe tool progress, and a virtual-thread effect interpreter reusable by CLI/protocol/TUI/subagents; deterministic renderer-independent transition tracing with canonical JSON-lines records (`beforeHash`, typed message, typed effects, `afterHash`), immutable-at-capture cancellation state, sorted maps/sets, stable binary/time projections, and complete-trace SHA-256 digests; typed text-block-close, thinking/signature, heartbeat, and four-counter usage events, including signed-thinking accumulation/replacement for replay, liveness-only compaction handling, and input+cache context accounting with zero-frame retention; exact typed HTTP and wire-error classification, six-step jittered backoff ladders, Retry-After clamping, 90-second budget decay, monotonic four-attempt mid-stream protection, heartbeat/first-delta health resets, scheduled-retry deduplication/cancellation, and FIFO queued-turn draining; native mid-session OAuth recovery with refresh-token gating, uncommitted-output protection, scheduled parking, fresh per-launch cancellation signals, concrete HTTP refresh/install, refresh success retry, typed failure teardown, late-result latch clearing, and queued-turn drain; native 120-second streaming-silence watchdog with `Fresh`/`StallFired` deduplication, cancel-token trip, zero-delay synthetic transient error, and two-second slow-tick rebase guard, driven by an active-only 100 ms headless cadence; both executing-tool recovery branches: a 30-second stranded-phase scheduler re-fire and a 330-second hung-worker failure with late-result discard; native partial-JSON closing and required-field guards, including live `todo` argument projection on the first fragment and then at the native 120 ms/512-byte time-and-growth bounds with invalid-row tolerance and pending status fallback; two-attempt same-context upstream-truncation recovery, retained block-close truncation signals, and non-retryable `max_tokens` protection; immutable wire-only compaction with latest-summary substitution, 65% compaction/95% normal soft ceilings, exact summary prompt, off-transcript bounded buffering, compaction-specific retry/cancel/queue behavior, persistence effects, post-turn auto-trigger, rapid-refill breaker, and quiet-turn re-enable; in-reducer three-repeat/25-step doom-loop enforcement and immutable re-leak reducer; concrete filesystem persistence port whose loop lifecycle waits for active dispatches to schedule their effects, drains submitted effect workers, then flushes and stops the coalescing writer; sealed, call-time-resolved live-provider adapter for Anthropic/OpenAI/Ollama | foundational headless and isolated subagent turns, live HTTP provider turns, tool continuations/progress/cancellation, transient/rate-limit/stall/auth recovery, tool-wedge and doom-loop recovery, truncated tool-input recovery, thinking replay, cache-aware usage, manual/automatic compaction, persistence shutdown, and source-derived canonical traces for fragmented chat, multi-tool batches, approval/execution, rejection, retry/cancel, compaction, checkpoint, and profile reset execute end-to-end; provider/model/thread trace cases and raw native capture remain | partial |
| ACP | pinned `acp-cpp` codecs, `acp_methods_test.py`, complete `acp_integration_test.cpp`, lifecycle/duplex portions of `async_prompt.cpp`, and `src/acp/server.cpp` | `AcpJsonRpcServerTest`, `AgentSessionFactoryTest`, `AcpCommandTest`, `AjentCliTest`, `NativeAcpParityIT` | newline-delimited JSON-RPC v2 dispatcher with a duplex, thread-safe stdio connection; exact v1 initialize capability lattice, including native acceptance-without-retention of advertised client fs/terminal capabilities and application-local tool execution; required-string `authenticate.methodId` validation and authenticate/logout; durable session index plus new/list/cwd-filter/load/resume/close/delete; Ask/Write/Minimal modes and call-time model selection; model config option; real asynchronous `AgentLoop` prompt turns with text/resource projection, deferred responses, provider continuation, usage updates, permissioned tool execution, exact outbound `session/request_permission` options, pending/metadata/in-progress/final tool cards, rejection, max-token/refusal/cancel stop mapping, per-session cancellation, concurrent-session isolation, active-prompt ownership, EOF cancellation, and durable completion; ordered `current_mode_update` notification-before-response; thread-store disk reload; idempotent deletion; parse/invalid-request/invalid-params/method-not-found/auth/internal error mapping; notification response suppression and best-effort corrupt/unwritable sidecar recovery; native-bounded load replay; production per-session composition of the selected live provider, profile-derived catalog permissions, the native 22-tool provider projection backed by all 23 operational tools, durable thread writer, context ceiling, prompt/memory/skills backends, and native distinction between authenticate credentials and keyless-local prompt authorization; top-level Java 25 `acp` stdio routing with protocol-clean stdout and native readiness diagnostics | all 26 pinned integration checks are directly translated: real duplex initialization/session/mode flow, two-completion turns, tool-result continuation, text/usage/tool updates, production sandboxed filesystem dispatch and persistence, approved on-disk writes, and rejected non-writes; source codecs, offline Python method assertions, cancel/concurrency cases, startup/settings/auth/profile cases, compiled Java 25 lifecycle smoke and pinned-executable persisted-login/authenticate/logout plus offline and live loopback-provider allow-once/reject-once/allow-always/cancel/concurrent prompt/tool/permission differentials are green; the live write case advertises all client fs/terminal capabilities and proves neither executable delegates tools through the generic `acp-cpp` callback surface | complete |
| MCP client/server | AgenTTY `src/mcp/serve.cpp`/`bridge.cpp`, native tool/effect projection and config trust rules, `src/mcp/http_server.cpp`, pinned `mcp_bridge_test.cpp`/`mcp_http_test.cpp`, and pinned `mcp-cpp` protocol/loopback codecs | `McpJsonRpcServerTest`, `NativeToolWireCatalogTest`, `McpServeCommandTest`, `McpConfigLoaderTest`, `McpClientSessionTest`, `McpStdioTransportTest`, `McpHttpTransportTest`, `McpRegistryTest`, `McpConnectionPoolTest`, `NativeMcpParityIT` | newline-delimited MCP JSON-RPC stdio server; initialize negotiation and Ajent implementation metadata/instructions; ping and initialized notification; native-ordered 22-tool standalone catalog with model-only `repo_map` omitted and exact schemas/descriptions; production composition with workspace/docs discovery, native unavailable standalone `task`, protocol-only stdout, sandbox enforcement, and all 22 tool-call families; exact edit diffs, grep terminal-newline context, typed local versus untyped host-shell errors, TLS-only URL validation, Git output, compatible data paths, and memory lifecycle; complete configured stdio and Streamable HTTP clients with discovery, rich results, resources, prompts, progress, refresh, sessions, headers, timeouts, and JSON/SSE decoding | every pinned bridge assertion is translated; native executable differentials cover initialize/ping/catalog, all 22 standalone tool families, errors/EOF, configured stdio and HTTP discovery/calls/resources/prompts/progress/refresh/cancellation/timeout/session/header behavior; the pinned Windows binary's positive-definition false-negative and stale pre-fusion RAG rendering are explicit deliberate differences while source-derived suites pin Ajent's working scanner and richer RAG | complete |
| Terminal/rendering | model-label/composer-edit suites, composer key-routing table, Maya input/Unicode/viewport/style/canvas/inline-frame suites, fresh-oversized and scrollback emulators, `table_render_test.cpp`, `md_shape_sweep.cpp`, `md_cache_probe.cpp`, `frozen_invariant_fuzz.cpp`, `reveal_smoothness_probe.cpp`, `stream_liveness_test.cpp`, `reveal_freeze_gate_probe.cpp`, `visual_hash_coverage_test.cpp`, `stream_async_freeze_test.cpp`, `stream_md_lag_test.cpp`, `scrollback_wire_fuzz.cpp`, `anim/text_reveal.hpp`, `test_motion.cpp`, `reveal_pacing_test.cpp`, streaming Markdown/reveal sources, and terminal lifecycle sequences | existing terminal suites plus `StreamingMarkdownTest`, `MarkdownShapeSweepTest`, `StreamingMarkdownCacheProbeTest`, `FrozenInvariantFuzzTest`, `ScrollbackLedgerTest`, `MarkdownRevealSmoothnessTest`, `StreamingMarkdownLivenessTest`, `StreamingMarkdownAsyncFreezeTest`, `StreamingMarkdownLagTest`, `ScrollbackWirePropertyTest`, `VisualHashCoverageTest`, `RateCursorTest`, `AnsiViewportTest`, `NativeTerminalParityIT`, and live transcript cases in `InteractiveCommandTest` | complete typed input, Unicode width, packed canvas/style/serialization, inline-frame shadow/scrollback machinery, JLine lifecycle, rate cursor, and production visual-state render gate described above; the interactive transcript now seals settled message blocks into a paint-measured generic `ScrollbackLedger`, records exact block heights from the same line-to-canvas pass, retains a mutable active back, trims only a previously painted separator-safe prefix under the native three-viewport/48-row and 120-entry policy, and consumes the ledger-minted debt exactly once into a coherent inline shadow; thread/prefix divergence and terminal-width changes rebuild through a hard reset; CommonMark/GFM block parsing with tables, task lists, strikethrough, headings, quotes, lists, rules, fenced/indented code, styled Unicode-width wrapping, native rejection of the malformed same-line table lead-in, shape-stable frame-aware Markdown masking, and a stateful per-message live row floor that resets on source replacement and terminal-width changes; rendered Markdown is memoized by exact source and width, refreshed with content mutation, invalidated by width changes, and returned without a whole-document mask walk when the reveal cursor is caught up; live state independently keeps RAF armed through quiet wire gaps/tool phases, while the typed settlement gate opens only after live state drops and the reveal/finalization tail drains; revealed complete blocks advance an exact UTF-8 committed-prefix extent so the mutable live tail remains bounded, and finish flushes it completely; large divergent settled-style Markdown updates use a virtual-thread parser at the native 16 KiB UTF-8 threshold and retain the previous tree until a render poll adopts the result, while prefix growth and the production live path remain synchronous and cannot expose that freeze window; the runnable interactive transcript owns that production widget for both live and settled assistant turns without exposing source punctuation; typed prefix-shift recovery commits exactly the renderer-measured overflow before soft repaint, and pure shrink cleanup positions the cursor on the new final row | I1-I7 are translated across 480 exact-seed frozen-prefix walks and production UI freeze/trim/resize cases; exact pacing, table, 31-shape, six-shape cache profile, seven-section smoothness, six-regime liveness, 40-subturn live-edge, async-freeze seam, 400-section long-turn lag bounds, freeze-window, W1-W5 scrollback-wire, and all 43 visual-hash axes plus both non-visual invariants are translated; the cache probe uses exact 24-byte/16 ms feeding and the native 1 ms plus 3× quartile failure rule; long-turn tests enforce the native 16,000-byte tail and generous 24× timing ceilings; the wire fuzz runs the four native terminal shapes with 40 exact-seed SplitMix64 walks per shape and caught a now-regressed pure-shrink cursor defect; full reactor and coverage gates are green; the executable gate captures native startup and a streamed provider turn under a real Windows 80x24 pseudo-console and Ajent through a deterministic fixed-size stream terminal, decodes both with the same tested Unicode-width ANSI viewport, and compares stable regions cell-for-cell; scripted cases cover single-line insertion and cursor movement, Shift+Enter multiline continuation and its border caption, wide-Unicode soft wrapping, Backspace, welcome metadata, fragmented OpenAI-compatible SSE, exact user/assistant rails and headers, turn separators, assistant Markdown padding, first-message title derivation and title-chip status chrome, provider/context status, and bounded Ctrl-C shutdown; the time-dependent animated wordmark frame and tool/permission/picker/review/thread/resize/scrollback scenarios remain | partial |
| Interactive UX and platform integration | `runtime/picker.hpp`, `runtime/command_palette.hpp`, `runtime/login.hpp`, `runtime/tool_output_viewer.hpp`, `runtime/view/view.cpp`, `runtime/view/thread/welcome_screen.cpp`, `runtime/view/thread/turn/agent_timeline`, status-bar and changes-strip configs, modal reducers, `app/subscribe.cpp`, and top-level `main.cpp` composition | `AppChromeTest`, `AgentTimelineTest`, `CommandPaletteTest`, `LoginModalTest`, `ModelProviderPickerTest`, `EffortTest`, `MentionSymbolPickerTest`, `WorkspaceMatcherTest`, `WorkspaceIndexTest`, `ImagePasteTest`, `SystemClipboardReaderTest`, `TerminalClipboardQueryTest`, `UnifiedDiffTest`, `ThreadPickerTest`, `PlanModalTest`, `CodeBlockPickerTest`, `CheckpointPickerTest`, `DiffReviewTest`, `ToolBodyPreviewTest`, `ToolOutputViewerTest`, `AttachmentTextTest`, `AjentCliTest`, `InteractiveCommandTest`, `MidrunWireTest`, plus terminal/render suites above | sealed picker/modal reducers; native-responsive outer app chrome with the exact 6×7 half-block wordmark font, height-tiered one-row fallback, measured label-to-key hint degradation, first-run-only starters after asynchronous history discovery, model/profile chips, bordered created/modified file facts, priority phase/provider/title/context activity row, and full-width severity banners; production no-subcommand raw JLine composition with configured provider, all native tools, persistence, blocking permission decisions, editable Unicode composer/paste, native Ctrl-C quit and live-Escape cancel priority; live command/model/provider/login/diff/tool-output surfaces; model-picker reasoning-effort labels and Left/Right cycling over exact capability-dependent off/low/medium/high/xhigh/max ladders, immediate persistence, selection/provider-switch degradation, and request-time safety clamp; word-boundary `@` file and `#` symbol triggers with modal keyboard ownership, cached bounded workspace scans, native skipped-directory/source-extension/declaration rules, scored file subsequence ranking, case-insensitive symbol-name filtering, clamped navigation, viewport rows, compact typed chips, workspace-contained latest-content resolution on every provider projection, 256 KiB file truncation, and declaration-centered excerpts; always-chip bracketed text paste with CR/CRLF normalization and exact line/byte metadata; raw PNG/JPEG/GIF/WEBP and quoted/escaped/path image ingestion with the native 8 MiB file bound; image-first Ctrl-V/Alt-V and empty-bracketed-paste clipboard routing through JDK desktop access, `AGENTTY_CLIPBOARD_CMD`, macOS/Linux command fallbacks, and a final terminal query using exact general OSC 52 text bytes or Kitty OSC 5522 multi-MIME bytes selected by `KITTY_WINDOW_ID`/`TERM`; native three-context structured LCS hunks with deletion-before-insertion patches, exact coordinates/counts, a six-million-cell block-replacement bound, unified rendering, accepted-hunk reconstruction, persistent per-hunk review status, wrap navigation, and exact Y/N/A/X decisions (review status does not roll back an already-executed tool); Ctrl-J saved-thread picker with newest-first async metadata refresh, active marker, wrap/page/jump navigation and current anchor; Alt-arrow recency cycle with native position toast; preserved old thread, cancellation/drain, cold session reconstruction, draft/modal/reveal/diff-ledger reset, and explicit terminal scrollback wipe on swaps; live todo sink with native status normalization, partial-stream plan mirroring, unchanged-update suppression, Ctrl-T/Open plan read-only modal, empty guidance and completed/total summary; native stable bordered action timelines per assistant batch with category counts/colors, ordered tree connectors, static in-flight event glyphs, animated running footer, terminal success/failure/rejection summaries, precise elapsed time, workspace/home path shortening, UTF-8-safe task fallback truncation, Unicode-width clipping, render-safe narrow fallback, and tool-specific header details; typed rich tool-body previews for edit/git diff, bash/diagnostics, write, read/definition, web JSON, generic line output, task, failures, and todos, including terminal-height streaming bounds, grep-to-read anchors, test/compiler summaries, task condensation, diff-plumbing removal, UTF-8 byte statistics, and native status tones; tolerant Ctrl-G fenced-block extraction, platform shell gating, numbered run/edit/copy picker, terminal suspension, bounded Windows execution, POSIX inherited-tty live execution with merged 2 MiB tee and parent signal isolation, elapsed title/pinned heartbeat and tty acknowledgement, PowerShell encoded-command quoting, result scrolling/copy/discard and compact Output attachment staging; placeholder-preserving persistence, compact transcript/composer chips, and submit-time provider expansion; real Git checkpoint creation/diff/rewind with transcript restore; asynchronous observer repaint, resize handling, typed incremental inline frames, and live cell-styled scramble/gradient/ghost/sweep/caret reveal with bounded final settlement | the tested production interactive agent loop now covers the principal session/provider/auth/history/plan/code/rewind actions; the runnable outer app hierarchy now includes native turn chrome and action timelines plus the responsive welcome, pending-changes strip, composer, and prioritized phase/provider/context/status surfaces; remaining widget polish and canonical live-loop differential traces remain | partial |
| Documentation/distribution | AgenTTY README/help/NOTICE, subsystem guides, and release executable layout | `AjentCliTest`, clean full-reactor package, direct-JAR and `ajent.cmd` launch smokes, POSIX launcher syntax check, local Markdown-link audit | root README plus architecture, design, auth, providers, configuration, complete environment reference, UI, rendering, inline scrollback, tools, protocols, code-block execution, troubleshooting, dependency, provenance, parity, contribution, and release guides; MIT license, upstream attribution, changelog; Maven Shade 3.6.2 executable dependency-containing JAR with merged service descriptors and signature filtering; stable `ajent-cli/target/ajent.jar`; Java 25 manifest entry point; Windows/POSIX launchers honoring `AJENT_JAVA_HOME` then `JAVA_HOME`; Maven Assembly 3.8.0 ZIP/TAR.GZ install layouts with executable POSIX mode and complete docs; aggregate CycloneDX 1.6 JSON SBOM; tag/version-guarded GitHub Actions release with SHA-256 checksums and artifact publication | clean Java 25 package is green; reactor/CLI versions agree at `0.2.8`; both archive formats contain the expected base directory, launchers, shaded JAR, legal files, changelog, full docs, and SBOM; extracted `ajent.cmd --version` and direct-JAR help smokes pass; POSIX mode is `0755`; shaded services, credential status, launcher syntax, and documentation links verified | partial — clean remote CI run and published GitHub release remain |

`NativeTerminalParityIT` now also drives both executables through a deterministic
Ask-profile Bash permission turn. It compares the six-row live permission card,
native argument description, running and completed action timeline, completed
output/footer, assistant continuation seam, phase elapsed slot, and measured
idle breadcrumb/context degradation cell-for-cell at 80x24. Only spinner phase
and subprocess wall-clock cells are normalized. Tool-card and permission flow is
therefore executable-proven; queued/attachment composer behavior, pickers, diff
review, thread switching, resize, and append-only scrollback remain in the
terminal differential backlog.

The same executable run now opens the provider picker with Ctrl+P, captures the
initial and Down-navigated frames, closes it with Escape, and proves the complete
76-column bordered bottom overlay. Registry blurbs, authentication/local notes,
cursor and scrollbar rails, blank padding, legend, key hints, and the native
short-terminal `rows - 8` viewport clamp are shared production behavior. The
remaining picker backlog is limited to the model, thread, mention, symbol,
code-block, and checkpoint surfaces.

Ctrl+K now uses the same production picker chrome with the native search header,
separator, protected descriptions, cursor/scroll rail, clipping, and responsive
viewport. The executable gate captures the open palette and compares a unique
`provider` filter cell-for-cell. The pinned 0.2.8 executable has fourteen rows
and predates the checked-in source's `Inspect tool outputs` palette entry;
Ajent retains that source-correct fifteenth command, so only the common uniquely
filtered view is asserted exactly. Command-palette rendering and filtering are
complete; the remaining picker backlog is model, thread, mention, symbol,
code-block, and checkpoint surfaces.

`TurnChromeTest` and live `InteractiveCommandTest` coverage now pin the native
turn shell independently of the broader renderer: `❯ You` and model-aware
`✦` identity, native per-speaker rail colors, muted metadata, local time,
elapsed-time thresholds, logical turn numbering, checkpoint markers,
continuation-header suppression, measured narrow-header degradation, a bold
left rail, three-column body reservation, slot gaps, the native inline error
shape, and preserved Markdown span styling. Cold rehydration still creates a
real header when it must retain only the tail of a very large assistant run.

The `midrun_freeze_test.cpp` single-freeze oracle is translated across
`InteractiveCommandTest`, `ScrollbackLedgerTest`, and the production terminal code. An active
assistant subturn run remains one mutable live turn until idle reveal settlement, then seals
exactly once with one header and every body. Nonterminal tools hold the gate closed. Cold
rehydration walks backward by whole speaker runs and cuts an oversized newest assistant run at
a recent subturn while restoring its header. Separator-safe trim cannot expose a blank top row;
a fully painted top deletion commits exactly its dropped row debt; and noisy bash output counts
only its rendered four-row tail. These cases cover all nine checks in the pinned native program.

The complementary `midrun_seam_test.cpp` oracle is translated in `MidrunSeamTest` across all
11 native scenarios. It compares the committed rows throughout deep incremental tool runs,
running-to-done card expansion, chunked Markdown streaming, reveal drain, and the final one-shot
freeze. Assistant subturns remain one visual run with no synthetic gaps, while compaction records
form hard run boundaries and render exactly one `Conversation compacted` divider in both the live
and frozen projections. Tall edit/write/read bodies, a three-write cadence, prefix hoisting, and a
new user turn after a formerly running tool retain every prior card exactly once without rewriting
anything above the mutable terminal-height tail.

`midrun_wire_test.cpp` is translated separately in `MidrunWireTest`; unlike the logical seam
suite, it feeds the production UI's emitted ANSI bytes into a viewport plus native-scrollback
emulator while carrying the same typed inline-frame shadow between updates. All 11 source shapes
remain `Synced`: growing read runs and late grep highlights, Running/Done/freeze write lifecycles,
idle and long-text finalization, pure-bottom shrink, output-elided and full-body top trim, and four
model-picker open/close cycles. Previously committed physical rows are append-only and distinctive
card boundaries remain exactly once. The model picker is consequently painted as a bottom overlay
that preserves the base frame's height instead of appending rows; provider-switch status remains
inside that overlay.

The reveal-on extension in `reveal_scrollback_test.cpp` is translated in
`RevealScrollbackTest` as the same 52-case geometry matrix: four 12–24-row terminals, four
32–60-row terminals, and the source's normal-only submit-mid-reveal cases. Long prose, six
text/tool subturns, repeated mid-reveal submits, a live closing-fence fold, prior tall writes,
running-to-failed tool growth, and 14-turn front-trim storms all carry a verified `Synced` shadow
while an ANSI emulator proves native scrollback is immutable and globally unique numbered oracle
rows never duplicate. The shared Markdown renderer now also mirrors AgenTTY's fold boundary: a
closed fenced-code body above 40 lines becomes one `▸ N lines hidden` row in both live and frozen
paths, while an open fence or exactly 40 body lines stays expanded.

The broader `scrollback_oracle_test.cpp` program is translated in `ScrollbackOracleTest` over its
exact `80×30`, `60×18`, `100×50`, and `46×76` shapes. Each run rotates six complete turns through
long prose, bursty bash progress, a two-tool parallel committed-chrome seam, write
tail-window-to-full expansion, and five incrementally arriving edit hunks that switch to the
settled diff path. After repeated front trims it adds the source-sized `3 × height + 10` deep edit
run, a growing running-tool edge, and the immediate prose follow-up. Across all 32 turns, physical
scrollback never shrinks or rewrites, the inline frame remains synchronized, and AgenTTY's
committed-only `uniq-*` token rule finds no stranded card or prose copy.

`visual_hash_coverage_test.cpp` is translated as the same declarative render-gate contract.
All 43 native view axes advance Ajent's FNV-style visual fingerprint, including live-tail keys,
permission and phase changes, composer state, every picker/modal cursor and query, frozen-prefix
growth, and tool-viewer stage/scroll. The last-tick clock and token counters are deliberately
absent, and a settled idle state is stable. The fingerprint now gates the production UI rather
than existing only as a test model. Ajent also fingerprints its viewport and thread identity so a
resize or same-shaped thread swap cannot suppress the required hard reset. Requested reveal,
freeze, and first-paint trim transitions remain an explicit ungated one-shot chain; once they
settle, unchanged observer ticks skip layout and paint.

The standalone `scrollback_prefix_harness.cpp` is no longer treated as an informational probe.
`ScrollbackPrefixHarnessTest` ports all five assertion-bearing scenarios across its exact four
terminal shapes: oversized startup ordering and uniqueness, committed-prefix proof and clamp,
the deliberately detectable bare re-emit hazard versus HardReset safety, generation-bound marker
commit with stale reuse rejection, and witnessed/rejected/vacuous scrollback proofs. The Java
proof type now exposes the native validity, overflow-count, and binding observations while keeping
single-use ownership enforcement in the render signature. Emitted bytes are checked through the
same ANSI viewport/native-scrollback emulator used by the full wire oracle.

`tool_boundary_burst_probe.cpp` is translated across its exact 15 wire-rate, boundary-gap, and
block-close-signal combinations. The unsafe immediate snap remains a positive reproduction of the
visual burst. Ajent's production UI now finishes the text boundary, holds the tool panel only while
the reveal cursor has real backlog, and uses the native 1,500 ms maximum as a typed snap-and-show
recovery. The deterministic probe bounds every fixed frame to 120 newly exposed content cells, and
an integration test proves actual tool rows stay absent until the boundary is released.

The standalone `md_shrink_debug.cpp` fixture selector is also executable evidence rather than a
print-only manual aid. All eight native bodies replay at every UTF-8 byte boundary with the same
80-column, 16 ms live-reveal conditions, and each frame asserts that the rendered height never
retreats. This directly covers link references, loose and nested lists, raw HTML, quoted fences,
the long push summary, bold bullets, and the paragraph-to-list transition.

`reveal_lag_probe.cpp` now drives its exact 800 cps prose-plus-60-line-fenced-code stream on the
native 16 ms simulated frame cadence. Ajent exposes the corresponding duration-typed finalize
request, uses the probe's 160 ms deadline at the production text/tool seam, and proves that the
eager tail drains within the bound with both reveal progress and live scheduling disarmed.

Performance probes live in the dedicated `ajent-benchmarks` Maven module and are built into a
standalone JMH 1.37 harness. `realthread_probe.cpp` is represented by separate persisted-thread
load, cold full-resume render, and warm cached-render measurements. Its portable default creates a
240-message, 120-tool thread, while `-p threadFile=<path>` runs the same paths over real saved JSON.
The generated benchmark registry and a one-iteration JDK 25 smoke run are verified locally.

The same harness now executes `tallcard_probe` through cold, warm, and running-write frames for
the exact seven native line/height pairs, and `edit_turn_cpu_probe` through settled, running-tail,
and streaming-edit states with six two-hunk 80-line edits across the native seven-height sweep.
Both use the production `InteractiveCommand.Ui` and tool-body renderer rather than synthetic string
formatting, and both have generated-registry plus JDK 25 single-shot smoke evidence.

`stream_cpu_probe` and `loop_body_split_probe` share the exact native eight-turn, 400-line-tool
backdrop and live 16 ms growth shape. The first measures prose growth and the settled-edit/running-
bash path at 80x30. The second separates Ajent's actual immutable message-key projection,
visual-hash/layout/wire gate, and rendered-frame projection at 120x50; it deliberately does not
invent Maya's separate subscription phase because Ajent has no corresponding runtime operation.

`long_session_bench` retains the native A-I scenario table: the 6/20/80/200-turn write shapes,
ten-hunk edit shape, realistic Write+Edit+Bash+Read mix, 3000-line pathological write, and the
off-screen 3000-line penultimate card. Its JMH methods measure construction, message render keys,
cold bounded resume, warm visual-gate frames, and active mid-run frames. The separate streaming
state preserves both native write and prose sweeps at 8, 100, 400, 800, 1500, and 3000 lines.

`o1_probe` preserves all eight settled transcript pairs through the 500-turn stress shape, the
1/5/10/20/40/80/160/320 accumulated-edit run, five write-card sizes through 8000 lines, five prose
and wire sizes through 5000 lines, and the three 50/200-turn bounded-resume footprints. These
measure Ajent's production frozen-scrollback ledger and terminal byte stream directly. Native-only
Maya element-tree build/paint counters are represented by Ajent's combined production UI frame;
the Java port does not report a synthetic phase that its renderer does not have.

The standalone `AnthropicMarkdownStream` developer tool ports `anthropic_md_stream` end to end.
Capture resolves the same environment/saved Anthropic credentials as Ajent, makes one request
through the production streaming transport, and records monotonic `{t_ms,delta}` JSONL. Replay
feeds that fixture through the production `StreamingMarkdown` renderer with native-equivalent
realtime, codepoint feed-rate, reveal pacing, drain, width, no-effects, and frame-trace controls.
Its deterministic tests exercise UTF-8 fixture parsing and every delta without requiring network.

`ComposerFlickerProbe` drives the exact native long tight-list text shape through Ajent's real
inline UI and a wcwidth-aware ANSI viewport emulator. It retains the 120-paragraph and 96-byte
defaults and reports composer movement/up-bounces/in-place rewrites, hidden-to-reappearing rows,
row churn, content-height shrink, out-of-order list visibility, and wire-byte percentiles. The
only UI-specific adaptation is locating Ajent's `> ` composer row instead of AgenTTY's bordered
Maya composer; the measured terminal phenomena and default stream cadence are unchanged.

Captured subprocesses now project the complete accumulated stdout/stderr
snapshot at AgenTTY's 80 ms cadence and perform a mandatory final flush. The
typed callback travels through `AgentLoop`, the catalog dispatcher, process
tools, and OS-sandbox wrappers for both `bash` and `diagnostics`; callback
failures remain best-effort and cannot change command completion.

Every successful built-in tool result also crosses the catalog's native
per-tool output boundary before reaching the interactive agent, ACP, or MCP.
The dispatcher applies the declared UTF-8 byte cap and head/tail/head-tail
strategy while preserving structured file-change metadata; zero-budget tools
and typed failures bypass truncation exactly as in AgenTTY.

That same closed dispatcher normalizes null, scalar, and array arguments to an
empty object, reports an absent tool as typed `not found`, and contains any
runtime exception from a tool implementation as typed `unknown` with the
native `tool crashed: ...` detail. These guarantees therefore apply uniformly
to interactive, ACP, MCP-server, and isolated-subagent calls.

The built-in grep path now matches whole files, counts repeated occurrences
on one line, emits merged two-line-context blocks with enclosing-symbol
breadcrumbs, pages twenty matches from a 500-match scan, marks the scan limit,
filters the complete native binary-extension set plus NUL-bearing content, and
stops between files at the native 20,000-byte rendered-page guard. Search
roots use the write-strength workspace boundary (not external read
allowlists), while a missing or non-directory root inside that boundary is an
empty traversal rather than a false out-of-workspace error.

The terminal input boundary tests additionally pin AgenTTY's 256-byte CSI and
16 MiB OSC/bracketed-paste limits. They prove that invalid UTF-8 continuations
and paste-overflow bytes are reprocessed in the ground state, while the byte
that overflows CSI or OSC is discarded with the partial sequence.
Differential edge assertions also cover the 50 ms bare-Escape timeout,
unknown-SS3 suppression, horizontal-wheel reports, zero and extra mouse
fields, saturating/private CSI parameters, and AgenTTY's padding-terminated
base64 decoder.

The reveal foundation includes Maya's rate-smoothed bounded-lag `RateCursor`.
Translated pacing probes prove a 2,000-codepoint/s stream does not accumulate
unbounded lag, a slow stream never runs past the wire, a 30,000-codepoint
finalize backlog meets its deadline, and a 500-codepoint burst slides over
multiple frames instead of teleporting.

The production transcript consumes that cursor at AgenTTY's 90 cps floor,
150 ms lag, 250 ms frame-gap cap, and 160 ms finalization ramp. A coalesced
daemon scheduler requests frames while the cursor, hot tail, or four-second
live-caret window remains relevant. `TextRevealEffect` ports Maya's reusable
height-stable decorator: deterministic width-one scramble glyphs, exact
45 ms churn hash, 36-code-point hot-to-cool RGB trail, invisible width-matched
ghost cells, pulsing sweep head, 650 ms end caret, last-line confinement,
fractional eager-row fronts, and structural border/wide-glyph protection.
`TextRevealEffectTest`, `TextRevealTest`, and `InteractiveCommandTest` prove the
native reveal/ghost boundary, RGB bands, Unicode-safe clipping, display-width
stability, bounded catch-up/final drain, clean scramble settlement, live ANSI
style projection, and termination of the wake-up chain after the caret window.

The compact live view also routes every textual row through the pinned
Unicode-width table. `ColumnTextWrapperTest` proves CJK/emoji cell wrapping,
supplementary-code-point atomicity, logical blank-line preservation, and
forward progress in a one-column viewport.

Live command interpretation now includes new-thread and profile-cycle in
addition to compact, quit, and tool-output inspection. New-thread cancels and
drains the abandoned loop, creates a fresh durable thread/session, clears UI
draft/modal/reveal state, and emits AgenTTY's explicit `2J`/`3J`/home reset for
a wholesale transcript swap. Profile cycling follows Write → Ask → Minimal,
updates future permission classification without cancelling an active turn,
clears session-level always-allow grants, and atomically persists the setting.

Anthropic model discovery now ports the native `/v1/models?limit=100` probe:
10-second request timeout, 1 MiB response bound, API-key versus OAuth headers,
upstream display-name parsing, and the exact Opus/Sonnet/Haiku 4.5 seed when
credentials or a usable response are absent. Loopback tests pin both auth
shapes and fallback behavior; this catalog is the live model picker's source.

The live model picker now opens from the command palette, fetches catalogs on
a virtual thread, reconciles persisted favorites, and owns wraparound arrows,
Home/End/Page navigation, Unicode filtering/backspace, favorite toggling, and
selection. The active model is supplier-backed at provider-launch time: an
in-flight HTTP request is never cancelled, while a tool continuation or later
turn observes the new model. Selection and favorites are atomically persisted;
an empty provider catalog retains the current model as a safe visible row.

The provider picker now renders AgenTTY's eight ordered presets plus the
custom-host sentinel and owns wraparound/jump/page navigation. Provider
configuration is resolved at each stream launch, so a preset switch preserves
the transcript and in-flight request while later launches observe the new
backend. Switches resolve freshly saved Anthropic credentials and each
provider's saved/environment key chain, refuse a hosted backend that would
immediately 401, recall its last model, persist provider/model together, and
open a fresh asynchronous model picker. The custom-host row routes into the
same typed login workflow described below.

That login handoff is now connected for every typed reducer action. The live
modal renders provider-targeted masked API-key input, normalized custom-host
input, OAuth authorization URL/code entry, exchanging state, and recoverable
failures. It routes paste/cursor/backspace keys without leaking secrets,
opens the system browser when available, exchanges OAuth on a virtual thread,
persists encrypted API-key/OAuth credentials (including expiry), installs
fresh Anthropic auth into future launches, saves provider keys before a
hosted switch, and refetches models after provider/custom-host completion.

Filesystem write/edit completions now preserve their structured `FileChange`
through the runtime adapter instead of dropping it. The interactive controller
accumulates a per-thread pending ledger; Review changes renders a two-axis
file/hunk modal with accept/reject and file navigation, while palette-wide
Accept all / Reject all update or clear the same ledger with native count
statuses. The bounded LCS engine splits distant changes into native
three-context hunks, merges changes within six context rows, preserves exact
one-based coordinates, and reconstructs partial accept/reject selections.

Saved conversation browsing now follows the native recency deck. Ctrl-J or
Open threads opens immediately against cached metadata and refreshes the
newest-first list on a virtual thread; the cursor anchors to the current
thread, marks it explicitly, wraps with arrows, and clamps 14-row page/Home/
End jumps. Selecting the current row only closes the picker. A different row
is loaded through the typed `ThreadId` store boundary; failures leave the live
conversation untouched, while success preserves the old nonempty thread,
cancels and drains its loop, reconstructs a session with the active profile
and provider suppliers, clears thread-owned drafts/modals/reveal/diff state,
and emits `ESC[2J ESC[3J ESC[H` for the sanctioned wholesale swap. Alt-Left/
Right cycles the same deck from the current anchor, refuses active replies,
and retains the native `thread k/N · title` status after the reset.

The production todo tool now feeds a thread-safe live ledger instead of a
discarding null sink. Its typed adapter normalizes `pending`, `in_progress`,
and `completed` into the same three-state plan vocabulary. During a `todo`
tool call, tolerant partial-JSON closing mirrors valid `todos[]` rows on the
first fragment and then only after both the native 120 ms interval and 512-byte
growth window; malformed rows are ignored and missing/unknown statuses become
pending. Unchanged projections do not repaint, while a changed preview or
completed tool result immediately updates the plan. Ctrl-T or Open plan opens
the native read-only surface: empty guidance when no plan exists,
checkbox/status rows otherwise, a completed/total footer, and strict
Escape-only dismissal so typed keys cannot leak into the composer.

Every timeline tool header now has the native discriminated body beneath it.
Each assistant tool batch also owns a stable bordered `A C T I O N S` card with
source-matched category statistics, tree joints, status glyphs, per-segment
colors and emphasis, elapsed footer, and a footer-only live spinner. Header
details retain their native lifecycle-stable summaries, including parsed result
counts and UTF-8-safe task prompt fallback truncation; paths shorten against
the current workspace or `HOME`, and display-width clipping preserves styles.
Edits switch from bounded cross-hunk streaming tails to complete settled
minus/plus hunks or fenced unified diffs; writes similarly use a
terminal-height tail while streaming and a complete numbered body with exact
UTF-8 statistics when settled. Bash and diagnostics show live progress,
compact test results with failing names, compiler coordinates, or a four-line
tail. Reads use source-line gutters and inherit same-turn Grep anchors;
web-fetch JSON is formatted and tail-bounded; generic tools get numbered
tails; subagent activity/report bodies are capped at the native eight/five
rows; failures and todo states retain their distinct red/green/cyan tones.
Git previews remove plumbing and preserve hunk/add/remove/context semantics.

Ctrl-G and Run code block now scan backward to the newest assistant message
that actually contains a fence. The extractor ports both backtick and tilde
fences, CommonMark's three-space indentation allowance, CRLF handling,
first-word/lowercase language tags, unterminated EOF recovery, empty-block
suppression, and the all-nonempty-lines rule for safely removing `$ ` / `> `
transcript prompts. The picker clamps arrow movement, supports direct 1-9
selection, and retains edit/copy for non-runnable languages. Platform gating
matches AgenTTY's Windows cmd/PowerShell and POSIX shell vocabularies;
PowerShell bodies use UTF-16LE `-EncodedCommand` to survive cmd quoting.
On Windows a selected command temporarily restores cooked terminal modes, runs
through the configured process runner with the native 30,000-byte/120-second
bounds, then restores raw inline modes. On POSIX it now invokes `/bin/sh -c`
with stdin inherited from the cooked real terminal, merges stdout/stderr into
one pipe, tees every byte live without text transcoding, and retains a bounded
2 MiB copy. The parent begins ignoring INT/QUIT only after child startup and
restores the previous handlers afterward, preserving Ctrl-C's native
command-only interruption behavior. Capture overflow still displays in full
on screen and adds AgenTTY's exact truncation marker to the retained copy.
Tests pin process-builder redirects, startup/failure ordering, output failure,
capture bounds, raw terminal writes, signal restoration, and CLI platform
routing. Both paths emit the native framed command/status treatment before
restoring raw inline modes and opening the captured result card. Result output
can be scrolled, copied, discarded, or attached as the native compact Output
chip. The shared attachment contract
uses the original SOH-delimited indexed placeholders, preserves chip-form text
and attachment metadata in persisted messages, renders compact labels in both
the composer and transcript, treats a placeholder as one cursor/backspace
unit, and expands the full command and captured bytes only in provider-bound
message copies. Token estimation accounts for the expanded payload.
While a command runs, Ajent now mirrors AgenTTY's elapsed OSC title on both
platform paths, the 250 ms Windows in-place spinner, and the one-second POSIX
spinner pinned below a temporary DECSTBM scroll region. The status region and
title are restored before the completion footer. A real POSIX tty then waits
for one raw, non-echoed acknowledgement key and restores its cooked attributes
before the inline UI resumes; redirected/dumb terminals skip that pause.

Interactive submits inside a Git worktree now stamp the user message and its
snapshot with one ID, then create the snapshot concurrently with provider
startup. The snapshot is a parentless commit pinned in the original
`refs/agentty/checkpoints/<id>` namespace. A copied scratch index stages all
tracked changes, deletions, and non-ignored untracked files without moving
HEAD, changing the user's index, or creating a stash. Diff previews compare a
fresh worktree tree to the pinned tree and report files, insertions, and
deletions; binary changes count as files without invented line totals. Restore
resolves and lists both file sets before writing, checks out the snapshot
through the scratch index, deletes later non-ignored files, and leaves ignored
build output alone. The Rewind palette opens newest-by-default over
checkpointed user turns, loads previews concurrently, and gates destructive
selection on an idle session. Success truncates the transcript and invalid
compaction records before the target turn, drains and replaces the loop,
refills the old prompt for editing, clears thread-owned UI state, persists the
shortened conversation (or removes an empty persisted file), and performs the
sanctioned full scrollback reset.

The construction plan in `plans/ajent-agentty-java-port.md` is the dependency
and exit-gate authority for advancing these rows.
