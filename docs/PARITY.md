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
| Reference tests/probes | all 53 rows in `test-manifest.json` | inventoried; 12 deterministic suites ported from source |
| Reference executable | Windows 0.2.8 binary SHA-256 in `capture-manifest.json` | verified |
| JDK 25 | user `JAVA_HOME`/`PATH` and project-local Maven toolchain select `C:\lang\jdk-25` | `java -version`, `mvn --version`, and `mvn test` green |
| Native suite | source is pinned; POSIX-only probes require Linux CI | deferred to cross-platform CI |

## Feature ledger

| Surface | Reference fixture | Java test | Implementation | Differential result | Status |
|---|---|---|---|---|---|
| CLI and configuration | help/version/invalid raw fixtures | `AjentCliTest`, `CliParityTest` | parser, help, version, invalid handling | exact after `agentty` → `ajent` name substitution | partial |
| Pure FSM/model/edit/scheduler contracts | pinned C++ tests | translated unit tests, including all 13 scheduler cases | implemented | source assertions translated; native CTest confirmation pending | partial |
| Thread/settings persistence | `persistence.cpp` and `store.hpp` source schemas | `ThreadStoreTest`, `SettingsStoreTest`, `AsyncThreadWriterTest`, expanded `ConversationTest` | complete persisted conversation shape, typed load errors, legacy status migration, metadata-only walk, atomic thread/settings writes, exact settings ordinals/omissions, coalescing background writer and drain-on-stop | source assertions and JSON schema translated; native binary differential capture remains | partial |
| Credential v1 envelope | `cred_crypt.cpp` fixed HKDF/AES-GCM contract and `auth.cpp` path/precedence rules | `CredentialCryptTest`, `CredentialStoreTest`, `CredentialResolverTest`, `CredentialPathsTest`, `MachineSeedTest` | authenticated v1 envelope, tamper rejection, legacy plaintext migration, private atomic writes, CLI/environment/saved precedence, non-blocking expired-OAuth handoff, AgenTTY config path and Windows MachineGuid/user seed | independent fixed cryptographic vector matches OpenSSL contract; native binary differential capture remains | partial |
| Anthropic provider | tool-result budget and image cases | `AnthropicMessagesTest` | message-block serializer and 64 KiB UTF-8 budget | source assertions translated; live transport missing | partial |
| OpenAI-compatible providers | `openai_transport_test.cpp` assertions | `OpenAiWireBuildersTest`, `OpenAiRequestTest`, `ProviderHttpTransportTest`, `ProviderModelCatalogTest`, `ProviderRegistryTest`, `OpenAiStreamParserTest`, `OpenAiStreamDecoderTest`, wire-framer tests | presets/auth resolution, exact request shape, JDK HTTP streaming, model listing, SSE/native parsing, byte-incremental decoding, salvage, cancellation, and retry hints | all enabled source assertions translated and loopback integration green; native differential capture and full retry-class table remain | partial |
| Ollama provider | `ollama_transport_test.cpp` assertions | `OllamaWireTest`, `OllamaRequestBodyTest`, `OllamaStreamParserTest`, `OllamaStreamDecoderTest`, `ProviderHttpTransportTest`, `ProviderModelCatalogTest` | native/JSON-protocol history and request bodies, exact slim prompt and memory tiers, environment options, model capability/context probing, structured/salvaged calls, response pseudo-tool, JDK HTTP and byte-incremental NDJSON | all 39 source scenarios translated and loopback integration green; native differential capture remains | partial |
| Native tool catalog/dispatch | pinned `mcp-cpp` argument/filesystem/search/git/host/memory/web sources, web tests, and AgenTTY `toolset_e2e_test.cpp` | family suites plus `ToolDispatcherTest`, `ToolRuntimeTest`, edit matcher/repair tests | all 23 catalog bodies and one exhaustive catalog-backed dispatcher: safe filesystem/search/map tools, subprocesses/diagnostics/git, host/memory protocols, HTTPS fetch, and three-engine search | every catalog body routes through Java; JDK web adapter, persistent memory/RAG/skill backends, idle-timeout/output-spill parity, richer grep context, cache parity, and native differential capture remain | partial |
| Permissions and scheduling | exhaustive `policy.hpp`, `effects.hpp`, `spec.hpp`, and 13-case `scheduler_path_test.cpp` | `ToolPolicyTest`, `ToolCatalogTest`, `OutputBudgetTest`, `ToolSchedulerTest` | exact 48-cell permission matrix/reasons, 23-tool ordered capability catalog, task scheduling override, coarse and path-aware parallel scheduling, UTF-8-safe output budgets | source assertions translated; reducer permission lifecycle and native differential capture remain | partial |
| Skills and memory | pinned `mcp-cpp` host/memory shells, AgenTTY `memory_store.cpp`, and toolset e2e cases | `HostToolsTest`, `MemoryToolsTest`, `JsonlMemoryStoreTest`, `MemoryPromptTest` | typed host protocols, legacy-compatible durable JSONL, corruption-tolerant loads, UTF-8 caps, Jaro dedup, cross-scope supersede, pinned rollover, atomic mutation, tail-50 loading, 6 KiB prompt ranking, pinned cap exemption, stable chronological rendering, and 400-byte record clipping | durable memory and bounded prompt projection ported; skill discovery and memory-to-RAG fusion remain | partial |
| RAG and repository map | pinned `mcp-cpp` repository map plus AgenTTY `rag_test.cpp`, `rag_rerank_test.cpp`, and advanced stemming/semantic/MMR/confidence cases | `RepoMapToolsTest`, `RagCoreTest`, `RagRerankerTest`, `RagStemmerTest` | repository PageRank map; line-aligned semantic document chunks, fenced-code/list preservation, overlap-safe breadcrumb context, contextual BM25 with opt-in Porter vocabulary normalization, cosine, reciprocal-rank fusion, BM25 corpus and multi-query fusion; feature-fusion reranking, exact extractive compression, Jaccard MMR diversification, structured contexts, and score-distribution confidence | deterministic lexical retrieval/ranking foundation ported; filesystem hot reload/cache, neural reranking, embeddings/HNSW, knowledge routing, memory fusion, and differential capture remain | partial |
| Agent loop/reducer | doom-loop and salvage-dedup source suites | `DoomLoopBreakerTest`, `SalvagedCallDeduplicatorTest` | loop breaker and immutable re-leak reducer | source assertions translated; full loop missing | partial |
| ACP | planned | planned | planned | missing | planned |
| MCP client/server | planned | planned | planned | missing | planned |
| Terminal/rendering | model-label and composer-edit source suites | `ModelLabelsTest`, `ComposerEditorTest` | labels, immutable composer word deletion, chip placeholders, undo | source assertions translated; renderer missing | partial |
| Interactive UX and platform integration | planned | planned | planned | missing | planned |
| Documentation/distribution | inventory pending | planned | planned | missing | planned |

The construction plan in `plans/ajent-agentty-java-port.md` is the dependency
and exit-gate authority for advancing these rows.
