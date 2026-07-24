# Dependencies

Ajent adds a library only when the JDK cannot express a parity requirement cleanly. Versions are pinned in the root Maven POM.

| Dependency | Scope | Why it is present | Boundary evidence |
|---|---|---|---|
| JUnit 6.1.0 | test | Java unit and differential test engine | all reactor test reports |
| AssertJ 3.27.6 | test | readable, typed assertions for translated C++ conditions | translated pure suites and CLI parity tests |
| Jackson 2.22.0 | `ajent-tools`, `ajent-provider` | mutable JSON tree for byte-shape-compatible argument repair and structural provider wire serialization | `ParameterTagRepairTest`, `AnthropicMessagesTest` |
| JLine terminal + FFM 3.30.0 | `ajent-terminal` | JDK 25 has no portable raw terminal, resize-signal, terminal-size, or cross-platform console-control API; the FFM provider is JLine's recommended Java 22+ native backend | official JLine terminal/provider documentation; `ComposerKeyRouterTest`; terminal lifecycle tests as the renderer lands |
| flexmark-java 0.64.8 core + GFM table/strikethrough/task-list extensions | `ajent-terminal` | the JDK has no CommonMark/GFM parser; using the mature parser avoids a partial home-grown grammar while Ajent retains its own terminal layout, styling, and rate-paced reveal | `StreamingMarkdownTest`, live transcript cases in `InteractiveCommandTest`, pinned `table_render_test.cpp` oracle |
| OkHttp JVM 5.4.0 | `ajent-provider` | JDK 25's HTTP client downgrades HTTPS tunneled through an HTTP proxy to HTTP/1.1; AgenTTY requires a dial-only host/port or SOCKS override that retains the logical URI, Host, SNI, certificate target, cancellation, and HTTP/2 | `EnvironmentHttpClientTest`; hosted HTTP/2 cases in `NativeAcpParityIT` |
| Jetty HTTP/2 server 12.1.11 | `ajent-parity` test only | the JDK HTTP server cannot negotiate HTTP/2, while the pinned AgenTTY hosted transport refuses a TLS peer without `h2` ALPN | hosted preset/header and TLS-cancellation cases in `NativeAcpParityIT` |
| pty4j 0.13.12 | `ajent-parity` test only | the JDK has no API for launching a child process under a deterministic pseudo-terminal; terminal executable differentials require a real console because AgenTTY rejects redirected stdin | `NativeTerminalParityIT` |
| JaCoCo 0.8.15 | build | enforced coverage gap detection | `mvn verify`; 80% module gates, 90% domain branch gate |
| Spotless Maven 3.8.0 | build | deterministic trailing-whitespace and final-newline formatting gate without rewriting the source-derived Java layout | `mvn verify` (`validate` phase) |
| SpotBugs Maven 4.10.3.0 / SpotBugs 4.10.3 | build | bytecode-level defect detection at maximum effort and medium confidence | `mvn verify` (`verify` phase) |
| OWASP Dependency-Check Maven 12.2.2 | CI security profile | scans the aggregate dependency graph and fails at CVSS 7 or higher; the NVD database is cached between Linux CI runs and `NVD_API_KEY` is read only from the CI secret environment | `mvn -Psecurity -DskipTests verify`; dedicated `dependency-check` CI job and uploaded HTML/JSON reports |

No LLM orchestration framework is present. In particular, LangChain4j is intentionally absent because Ajent must preserve AgenTTY's provider wire format, reducer decisions, and tool semantics directly.

Maven Compiler 3.14.1, Enforcer 3.6.3, Surefire/Failsafe 3.5.4 are pinned build plugins rather than runtime dependencies.
