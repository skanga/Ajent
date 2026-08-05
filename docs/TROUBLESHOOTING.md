# Troubleshooting Ajent

For the complete set of transport, provider, RAG, MCP, terminal, and tracing
switches referenced below, see [the environment variable reference](ENVIRONMENT.md).

Start with the smallest failing boundary. Confirm Java/Maven, then CLI
configuration, authentication, model listing, a minimal provider turn, tools,
and finally terminal rendering. Avoid enabling broad debug output before you
know which boundary is failing.

## Installation and build

### `release version 25 not supported`

Ajent compiles and runs on JDK 25. Verify:

```powershell
java -version
mvn -version
```

The repository uses `.mvn/toolchains.xml`. Ensure it points to an installed JDK
25. For the launcher, set `AJENT_JAVA_HOME` or `JAVA_HOME` to JDK 25.

### Maven reports an older required version

The reactor enforces Maven 3.9.12 or newer. Use `mvnw.cmd`/`mvnw` or install a
newer Maven. Run commands from the repository root so every module participates.

### Coverage check fails after tests pass

`verify` enforces aggregate line and branch floors. Open the failing module's
`target/site/jacoco/index.html` and add behavior-focused tests for missed paths;
do not lower the threshold or skip JaCoCo for a release-quality build.

### SpotBugs check fails

Treat findings in handwritten code as defects first. Fix ownership, nullability,
resource lifecycle, or concurrency at the source. Add an entry to
`config/spotbugs-exclude.xml` only for a narrow, documented contract such as
generated JMH padding or an intentionally exact Ajent wire/platform behavior.

### Dependency-Check cannot update the NVD database

The OWASP scan is isolated in the `security` profile because its database update
requires network access. Set `NVD_API_KEY` to avoid public API throttling and
reuse `~/.m2/dependency-check-data`; CI restores and saves that directory. A
failed update must not be interpreted as a clean vulnerability report.

## Startup and configuration

### UI is blank or constrained to 80×24

Ajent's executable JAR enables native access in its manifest so JLine can use
the Windows FFM terminal provider and read the live console dimensions. Rebuild
with `mvn clean package` if the JAR predates that manifest entry. As a diagnostic
for an older or repackaged JAR, launch it with
`java --enable-native-access=ALL-UNNAMED -jar ajent.jar`. The 80×24 viewport is
only an emergency fallback for a terminal that genuinely reports zero size.

### Workspace rejected

`--workspace` must name an existing directory. Avoid a file, missing path, or
NUL-containing value. Filesystem tools are confined to the canonical workspace
root.

### Wrong provider/model starts

CLI values override persisted settings. Run with explicit `--provider` and
`--model`, then inspect `status`. Inside the UI, use Ctrl-P and Ctrl-/.
Selections affect the next turn; an in-flight request retains its snapshot.

### Local provider has no models

Verify the server independently and confirm its base URL/dialect. Ollama uses
its native model API; llama.cpp/custom hosts use OpenAI-compatible endpoints.
An empty catalog may be a connection error, an endpoint path mismatch, or a
server with no installed model.

## Authentication

Run:

```powershell
.\ajent.cmd status
```

Environment variables override saved credentials. `logout` cannot unset the
parent shell. OAuth codes are tied to one PKCE/state attempt; restart login if
the browser tab and terminal attempt do not match. See [AUTH.md](AUTH.md).

Never post the credential file, authorization header, OAuth code, access token,
or refresh token in a bug report.

### Codex import says credentials are in the keyring

Ajent detects but does not extract OS-keyring secrets. Set
`cli_auth_credentials_store = "file"` in Codex configuration, run
`codex login` again, then run `ajent login --provider codex`. Ajent copies the
resulting file into its own encrypted store without modifying the source.

### Codex session expired

Run `codex login` followed by `ajent login --provider codex`. OpenAI API keys
are unrelated and should be configured only for `--provider openai`.

## Provider and network failures

### Immediate 401/403

Confirm credential source and provider. A key accepted by one OpenAI-compatible
host is not valid for another. For OAuth, check expiry and perform a fresh
login if refresh was rejected.

### 404 from a custom host

Check whether the supplied base URL already contains `/v1`. Ajent's
OpenAI-compatible transport uses Chat Completions (`/v1/chat/completions`).
Only the separate `codex` provider uses Responses. Confirm the custom server
implements the Chat Completions dialect.

### Stream stalls

Ajent has byte-idle HTTP and semantic-progress watchdogs. If it waits until a
typed timeout, capture provider/server versions, whether a proxy is present,
and whether any heartbeat bytes arrived. A proxy that buffers SSE defeats
incremental streaming even when the final response is valid.

### Proxy or air-gap issues

Test model listing and OAuth hosts separately. Provider API, OAuth authorize,
and OAuth token exchange may use different destinations. In air-gap mode,
verify SSH liveness and clipboard relay before launching the editor integration.

## Tool problems

### Permission prompt repeats

`y` allows one call; `a` grants the effect class for the current session.
Different effects or paths can still require another decision. Switching
threads creates a new session grant set.

### Path is outside workspace

Ajent resolves canonical paths and rejects traversal/symlink escapes. Move the
file into the workspace or choose a different workspace; do not bypass the
sandbox by spelling the same path differently.

### Process command rejected

The selected profile and platform process sandbox may reject dangerous or
unsupported command shapes. Inspect the displayed policy reason. `--sandbox
off` changes process wrapping only when explicitly allowed by configuration;
it does not remove all tool validation.

### Tool output is truncated

Raw capture and conversation output have separate bounds. Search a generated
file, narrow the command, or use paging arguments. Truncation markers are part
of the contract and prevent one tool from consuming the model context.

### Repeated identical tool calls stop

The doom-loop breaker detects repeated equivalent calls/failures and terminates
the cycle. Rephrase the request or resolve the underlying error before
continuing; raising the limit would only spend more tokens on the same action.

## Threads, Git, and checkpoints

### Saved thread will not load

The loader returns typed corrupt/schema/I/O errors and does not overwrite the
file. Preserve it for diagnosis. Check JSON encoding and whether the file was
written by a compatible schema.

### Checkpoint menu says Git is unavailable

The workspace must be inside a Git repository and Git must be on `PATH`.
Checkpoint creation uses private refs and a throwaway index; it does not stage
changes into your real index.

### Rewind leaves ignored files

This is intentional. Rewind restores the checkpoint snapshot and removes later
non-ignored files while preserving ignored build/cache outputs.

### Git command fails with stale file warning

Ajent fingerprints files observed by tools. If another program changes a file
between read and edit/write, the mutation fails rather than overwriting the
external change. Read the file again and reapply the edit.

## Terminal interface

### Screen flickers or tears

Use a terminal supporting synchronized output, and check whether tmux/SSH
passes DEC mode 2026 through. Ajent lowers cadence on unsynchronized/remote
terminals. Record terminal name, `$TERM`, dimensions, tmux version, and whether
the issue occurs only while streaming.

### Rows duplicate after resize or modal close

Press `Ctrl-L` to reset Ajent's inline surface. If reproducible, capture exact
terminal dimensions and key sequence. The scrollback prefix/fuzz tests model
these transitions, so a minimal sequence is valuable.

### Paste is inserted as a chip

Expected: bracketed text paste becomes one paste attachment. Use ordinary
typing for literal small text if you do not want a chip. Ctrl-V/Alt-V attempts
image clipboard first, then text.

### Shift-Enter submits instead of newline

Some terminals do not report Shift on Enter. Use Alt-Enter, which is the legacy
fallback. Enable Kitty keyboard protocol or modifyOtherKeys if supported.

### Ctrl-J behaves like Enter

Legacy terminals can encode both Return and Ctrl-J as LF with no remaining
discriminator. Use Ctrl-K and choose **Open threads**, or use a terminal with a
modern keyboard protocol.

## ACP and MCP

Protocol modes reserve stdout for newline-delimited JSON-RPC. Send diagnostics
to stderr and do not wrap the process with banners or shell startup output.

For ACP, verify `initialize` and authentication before creating a session.
Only one prompt owns a session at a time; cancellation is per session. For MCP,
verify the configured command/URL, initialize handshake, tool catalog, and
request timeout separately. External MCP failures should not prevent native
tools from loading.

## Diagnostics

Ajent supports best-effort debug, raw provider, and ACP wire logging through
the `AJENT_*` environment switches implemented by `AjentDebugLog` and protocol
tracing. See `ENVIRONMENT.md` for the exact names.
Logs redact authorization material but can include prompts, source fragments,
tool arguments, paths, and model output.

When filing a report, include:

- Ajent commit/version;
- `java -version` and `mvn -version` where relevant;
- operating system and terminal;
- provider and model, never the key;
- exact command/key sequence;
- expected versus actual behavior;
- smallest safe log/fixture reproducing it;
- whether `mvn -q verify` passes from a clean checkout.

## Recovery without data loss

Prefer copying a suspect thread/settings/credential file before changing it.
Do not run destructive Git cleanup to “fix” Ajent. Thread JSON and checkpoints
can often establish what happened even when the terminal surface is corrupt.
`Ctrl-L` is safe for terminal recovery; it clears terminal scrollback, not
conversation persistence or workspace files.
