# Dependencies

Ajent adds a library only when the JDK cannot express a parity requirement cleanly. Versions are pinned in the root Maven POM.

| Dependency | Scope | Why it is present | Boundary evidence |
|---|---|---|---|
| JUnit 6.1.0 | test | Java unit and differential test engine | all reactor test reports |
| AssertJ 3.27.6 | test | readable, typed assertions for translated C++ conditions | translated pure suites and CLI parity tests |
| Jackson 2.22.1 | `ajent-tools`, `ajent-provider` | mutable JSON tree for byte-shape-compatible argument repair and structural provider wire serialization | `ParameterTagRepairTest`, `AnthropicMessagesTest` |
| JLine terminal + FFM 3.30.0 | `ajent-terminal` | JDK 25 has no portable raw terminal, resize-signal, terminal-size, or cross-platform console-control API; the FFM provider is JLine's recommended Java 22+ native backend | official JLine terminal/provider documentation; `ComposerKeyRouterTest`; terminal lifecycle tests as the renderer lands |
| flexmark-java 0.64.8 core + GFM table/strikethrough/task-list extensions | `ajent-terminal` | the JDK has no CommonMark/GFM parser; using the mature parser avoids a partial home-grown grammar while Ajent retains its own terminal layout, styling, and rate-paced reveal | `StreamingMarkdownTest`, live transcript cases in `InteractiveCommandTest`, pinned `table_render_test.cpp` oracle |
| OkHttp JVM 5.4.0 | `ajent-provider` | JDK 25's HTTP client downgrades HTTPS tunneled through an HTTP proxy to HTTP/1.1; Ajent requires a dial-only host/port or SOCKS override that retains the logical URI, Host, SNI, certificate target, cancellation, and HTTP/2 | `EnvironmentHttpClientTest`; hosted HTTP/2 cases in `NativeAcpParityIT` |
| JaCoCo 0.8.15 | build | enforced coverage gap detection | `mvn verify`; 80% module gates, 90% domain branch gate |
| Spotless Maven 3.8.0 | build | deterministic trailing-whitespace and final-newline formatting gate without rewriting the source-derived Java layout | `mvn verify` (`validate` phase) |
| SpotBugs Maven 4.10.3.0 / SpotBugs 4.10.3 | build | bytecode-level defect detection at maximum effort and medium confidence | `mvn verify` (`verify` phase) |
| OWASP Dependency-Check Maven 12.2.2 | CI security profile | scans the aggregate dependency graph and fails at CVSS 7 or higher; the NVD database is restored and saved even when a completed scan reports findings, `NVD_API_KEY` is read only from the CI secret environment, and CI rejects exact artifact/CVE suppressions when unused (offline stale-cache runs do not) | `mvn -Psecurity -DskipTests verify`; dedicated `dependency-check` CI job, `config/dependency-check-suppressions.xml`, and uploaded HTML/JSON reports |

No LLM orchestration framework is present. In particular, LangChain4j is
intentionally absent because Ajent owns its provider wire formats, reducer
decisions, and tool semantics directly.

Maven Compiler 3.14.1, Enforcer 3.6.3, Surefire/Failsafe 3.5.4 are pinned build plugins rather than runtime dependencies.
