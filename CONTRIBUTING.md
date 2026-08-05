# Contributing to Ajent

Ajent is a standalone Java terminal coding agent. Contributions are judged by
observable behavior, compatibility, tests, and Java design quality.

## Prerequisites

- JDK 25
- Maven 3.9.12 or newer, or the checked-in Maven wrapper
- Git

The Maven reactor selects JDK 25 through `.mvn/toolchains.xml`. Keep all module
versions and dependency versions centralized in the root `pom.xml`.

## Development workflow

1. Identify the affected contract, test, fixture, or documented interaction.
2. Add a Java test that fails for the missing behavior.
3. Implement the smallest complete behavior that makes the test
   pass without weakening existing contracts.
4. Run the whole reactor from the repository root:

   ```text
   mvn -q test
   mvn -q verify
   ```

5. Update the relevant user or architecture documentation when behavior
   changes.

Do not validate a feature with only a module-specific Maven invocation. Cross-
module composition is part of the product, and the aggregate JaCoCo gate is
enforced at 80 percent line and branch coverage. The same `verify` command also
checks deterministic whitespace with Spotless and fails on medium-confidence
SpotBugs findings at maximum analysis effort.

Dependency changes must additionally pass the opt-in aggregate vulnerability
scan:

```text
mvn -q -Psecurity -DskipTests verify
```

The scan fails at CVSS 7 or higher. CI supplies `NVD_API_KEY`, caches
`~/.m2/dependency-check-data`, and publishes both report formats.

## Porting rules

- Preserve wire formats, ordering, defaults, cancellation, retry timing,
  persistence compatibility, terminal keys, and error classification.
- Prefer immutable records, sealed interfaces, exhaustive switches, and
  virtual threads where they express the native state machine accurately.
- Keep provider-specific wire shapes outside the provider-neutral reducer.
- Do not add a framework merely to shorten local code. Ajent deliberately uses
  the JDK HTTP client and explicit adapters; LangChain4j is not required.
- Keep stored conversation data separate from provider wire projection.
- Treat stale asynchronous results as normal input to reject, not as impossible
  states.
- Never include credentials, provider captures, private prompts, or generated
  build output in a commit.

## Tests and fixtures

Name tests for behavior rather than implementation. Pure reducers and parsers
should use deterministic unit tests. Filesystem, process, HTTP, terminal, ACP,
and MCP behavior should use bounded integration tests with temporary
directories or loopback servers.

Tests must not require a live provider account, modify the user's actual
configuration, depend on execution order, or leave processes running.

## Style and dependency changes

Compile with all configured warnings enabled. Keep public types documented when
their contract is not obvious. Avoid mutable global state, wildcard imports,
reflection-based wiring, and catch-all exception handling that erases a typed
failure.

Before adding a dependency, document why the JDK or an existing dependency is
insufficient, pin the version in the root POM, and update
`docs/DEPENDENCIES.md`. Dependencies must converge under the Maven Enforcer
rule and use a license compatible with Ajent's MIT distribution.

## Commits and reviews

Keep commits cohesive and use an imperative summary such as `Fix provider
retry classification`. Include the translated conditions, relevant edge cases,
and verification command in the change description. Reviewers should be able
to trace each parity claim to source and tests without relying on the author's
memory.

See `docs/ARCHITECTURE.md` and `docs/DESIGN.md` before making cross-module
changes.
