# Building and releasing Ajent

Ajent releases are JDK 25 executable JAR distributions. The same shaded JAR is
used on Windows, Linux, and macOS; small platform launchers select Java and
forward arguments.

## Release inputs

A release commit must contain:

- a versioned, green Maven reactor;
- `LICENSE`, `NOTICE`, `README.md`, and the documentation set;
- `ajent.cmd` and executable `ajent` launchers;
- no `agentty` checkout, credentials, build output, captures, or local IDE
  files;
- an updated `CHANGELOG.md` entry.

The root POM version and `AjentCli.VERSION` must agree. Snapshot versions are
development builds and must not be published as final releases.

## Verification gate

Run from the repository root with JDK 25 configured:

```text
mvn -q verify
```

This compiles every module, runs unit and integration tests, checks dependency
convergence and deterministic whitespace, runs maximum-effort SpotBugs, enforces
aggregate 80 percent line and branch coverage, packages the distribution, and
emits its CycloneDX SBOM.

Run the aggregate dependency vulnerability gate as well:

```text
mvn -q -Psecurity -DskipTests verify
```

It fails for dependencies at CVSS 7 or higher and writes HTML and JSON reports
under `target`. Set `NVD_API_KEY` when available; CI caches the NVD data directory
at `~/.m2/dependency-check-data`.

Also verify the packaged CLI:

```text
mvn -q package -Djacoco.skip=true
java -jar ajent-cli/target/ajent.jar --version
java -jar ajent-cli/target/ajent.jar --help
```

Run `sh -n ajent` on a POSIX host and launch `ajent.cmd --version` on Windows.
CI repeats the full reactor gate on Ubuntu, Windows, and macOS.

## Distribution contents

The release archive layout is:

```text
ajent-VERSION/
  ajent.jar
  ajent
  ajent.cmd
  README.md
  LICENSE
  NOTICE
  CHANGELOG.md
  ajent-sbom.json
  docs/
```

The package phase produces both ZIP and TAR.GZ archives and a reproducible
CycloneDX 1.6 JSON SBOM. The tag-triggered release workflow verifies that the
tag and non-snapshot Maven version agree, runs the full reactor, generates a
`SHA256SUMS` file covering both archives and the standalone SBOM, and publishes
them through GitHub Releases. The executable JAR must be the shaded
`ajent-cli/target/ajent.jar`, not the unshaded original JAR Maven Shade keeps
for diagnostics.

Ajent does not bundle a JRE. Users must provide JDK/JRE 25 with the required
terminal and foreign-function support. This keeps one auditable artifact and
avoids platform-specific native-image differences.

## Version procedure

1. Choose the semantic version and move completed changelog entries from
   `Unreleased` into that version with the release date.
2. Update the Maven reactor version and `AjentCli.VERSION` together.
3. Run the verification and launcher checks above.
4. Inspect `git status --short`, `git diff --check`, and the staged file list.
5. Confirm `agentty/` and every `target/` directory are absent from the staged
   tree.
6. Commit the release, create an annotated `vVERSION` tag, and push the commit
   and tag.
7. Create the GitHub release, attach both platform-neutral archives and their
   checksums, and verify them from a clean directory.

Never publish from a dirty worktree or replace an existing release tag.

## Clean-install smoke test

In an empty temporary directory, unpack the artifact and run:

```text
./ajent --version
./ajent --help
```

On Windows run the equivalent `ajent.cmd` commands. Then start a keyless local
provider or use a disposable test key, confirm the interactive screen opens,
and exit with Ctrl-C. Protocol releases should additionally send a pinned ACP
`initialize` request and MCP `initialize` request over stdio and confirm stdout
contains only JSON-RPC frames.

## Rollback

GitHub releases and tags are immutable release records. If a published artifact
is wrong, mark the release as affected, fix forward with a new patch version,
and retain the old checksums for provenance. Revoke any credential accidentally
included in an artifact before doing anything else.
