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
| Reference tests/probes | all 53 rows in `test-manifest.json` | inventoried; 10 deterministic suites ported from source |
| Reference executable | Windows 0.2.8 binary SHA-256 in `capture-manifest.json` | verified |
| JDK 25 | user `JAVA_HOME`/`PATH` and project-local Maven toolchain select `C:\lang\jdk-25` | `java -version`, `mvn --version`, and `mvn test` green |
| Native suite | source is pinned; POSIX-only probes require Linux CI | deferred to cross-platform CI |

## Feature ledger

| Surface | Reference fixture | Java test | Implementation | Differential result | Status |
|---|---|---|---|---|---|
| CLI and configuration | help/version/invalid raw fixtures | `AjentCliTest`, `CliParityTest` | parser, help, version, invalid handling | exact after `agentty` → `ajent` name substitution | partial |
| Pure FSM/model/edit/scheduler contracts | pinned C++ tests | translated unit tests, including all 13 scheduler cases | implemented | source assertions translated; native CTest confirmation pending | partial |
| Thread/settings persistence | planned | planned | planned | missing | planned |
| Credential v1 envelope | planned | planned | planned | missing | planned |
| Anthropic provider | tool-result budget and image cases | `AnthropicMessagesTest` | message-block serializer and 64 KiB UTF-8 budget | source assertions translated; live transport missing | partial |
| OpenAI-compatible providers | planned | planned | planned | missing | planned |
| Ollama provider | planned | planned | planned | missing | planned |
| Native tool catalog/dispatch | planned | planned | planned | missing | planned |
| Permissions and scheduling | planned | planned | planned | missing | planned |
| Skills and memory | planned | planned | planned | missing | planned |
| RAG and repository map | planned | planned | planned | missing | planned |
| Agent loop/reducer | doom-loop and salvage-dedup source suites | `DoomLoopBreakerTest`, `SalvagedCallDeduplicatorTest` | loop breaker and immutable re-leak reducer | source assertions translated; full loop missing | partial |
| ACP | planned | planned | planned | missing | planned |
| MCP client/server | planned | planned | planned | missing | planned |
| Terminal/rendering | model-label and composer-edit source suites | `ModelLabelsTest`, `ComposerEditorTest` | labels, immutable composer word deletion, chip placeholders, undo | source assertions translated; renderer missing | partial |
| Interactive UX and platform integration | planned | planned | planned | missing | planned |
| Documentation/distribution | inventory pending | planned | planned | missing | planned |

The construction plan in `plans/ajent-agentty-java-port.md` is the dependency
and exit-gate authority for advancing these rows.
