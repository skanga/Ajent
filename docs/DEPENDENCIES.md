# Dependencies

Ajent adds a library only when the JDK cannot express a parity requirement cleanly. Versions are pinned in the root Maven POM.

| Dependency | Scope | Why it is present | Boundary evidence |
|---|---|---|---|
| JUnit 6.1.0 | test | Java unit and differential test engine | all reactor test reports |
| AssertJ 3.27.6 | test | readable, typed assertions for translated C++ conditions | translated pure suites and CLI parity tests |
| Jackson 2.22.0 | `ajent-tools`, `ajent-provider` | mutable JSON tree for byte-shape-compatible argument repair and structural provider wire serialization | `ParameterTagRepairTest`, `AnthropicMessagesTest` |
| JLine terminal + FFM 3.30.0 | `ajent-terminal` | JDK 25 has no portable raw terminal, resize-signal, terminal-size, or cross-platform console-control API; the FFM provider is JLine's recommended Java 22+ native backend | official JLine terminal/provider documentation; `ComposerKeyRouterTest`; terminal lifecycle tests as the renderer lands |
| JaCoCo 0.8.15 | build | enforced coverage gap detection | `mvn verify`; 80% module gates, 90% domain branch gate |

No LLM orchestration framework is present. In particular, LangChain4j is intentionally absent because Ajent must preserve AgenTTY's provider wire format, reducer decisions, and tool semantics directly.

Maven Compiler 3.14.1, Enforcer 3.6.3, Surefire/Failsafe 3.5.4 are pinned build plugins rather than runtime dependencies.
