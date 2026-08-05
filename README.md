# Ajent

Ajent is a standalone Java 25 interactive coding agent for the terminal. It
provides Anthropic and OpenAI-compatible providers, a native tool catalog,
permission profiles, saved threads, plans, code-block execution, Git
checkpoints and rewind, MCP and ACP servers, skills, authentication, and
air-gap commands. The implementation uses Java records, sealed interfaces,
virtual threads, and the JDK HTTP client.

## Requirements

- JDK 25 to compile and run Ajent
- Maven 3.9.12 or newer, or the included Maven wrapper
- Git for workspace checkpoints and Git tools
- A real terminal for the interactive UI

Maven may itself run on an older JDK because this project selects JDK 25
through `.mvn/toolchains.xml`. The resulting application must still be run by
JDK 25. On Windows, set `AJENT_JAVA_HOME` or `JAVA_HOME` accordingly:

```powershell
$env:AJENT_JAVA_HOME = "C:\lang\jdk-25"
```

## Build and test

From the repository root:

```powershell
mvn -q test
mvn -q package "-Djacoco.skip=true"
```

The complete executable, dependency-containing JAR is written to:

```text
ajent-cli\target\ajent.jar
```

Performance probes are packaged separately as `ajent-benchmarks\target\benchmarks.jar`.
For example, the real-thread resume probe accepts either its synthetic default or a saved thread:

```powershell
& "$env:AJENT_JAVA_HOME\bin\java.exe" -jar ajent-benchmarks\target\benchmarks.jar RealthreadProbeBenchmark
& "$env:AJENT_JAVA_HOME\bin\java.exe" -jar ajent-benchmarks\target\benchmarks.jar RealthreadProbeBenchmark -p "threadFile=C:\path\thread.json"
& "$env:AJENT_JAVA_HOME\bin\java.exe" -jar ajent-benchmarks\target\benchmarks.jar LongSessionProbeBenchmark
& "$env:AJENT_JAVA_HOME\bin\java.exe" -jar ajent-benchmarks\target\benchmarks.jar O1ProbeBenchmark
```

The standalone stream fixture and composer diagnostics live in the same shaded JAR:

```powershell
& "$env:AJENT_JAVA_HOME\bin\java.exe" -cp ajent-benchmarks\target\benchmarks.jar com.github.skanga.ajent.cli.AnthropicMarkdownStream capture stream.jsonl
& "$env:AJENT_JAVA_HOME\bin\java.exe" -cp ajent-benchmarks\target\benchmarks.jar com.github.skanga.ajent.cli.AnthropicMarkdownStream replay stream.jsonl --trace
& "$env:AJENT_JAVA_HOME\bin\java.exe" -cp ajent-benchmarks\target\benchmarks.jar com.github.skanga.ajent.cli.ComposerFlickerProbe 120 96
```

The JaCoCo skip on `package` only avoids running the verification-time coverage
gate when producing a local executable. It does not skip tests. Use
`mvn -q verify` for the complete quality gate: deterministic whitespace,
dependency convergence, tests and coverage, SpotBugs, packaging, and the SBOM.
The opt-in vulnerability scan is `mvn -q -Psecurity -DskipTests verify`; CI
caches its NVD data and uploads the HTML and JSON reports.

## Run

On Windows, after packaging:

```powershell
.\ajent.cmd --version
.\ajent.cmd --help
.\ajent.cmd --workspace .
```

On Linux or macOS:

```sh
./ajent --version
./ajent --help
./ajent --workspace .
```

Or invoke the JAR directly with JDK 25:

```powershell
& "$env:AJENT_JAVA_HOME\bin\java.exe" -jar ajent-cli\target\ajent.jar --workspace .
```

With no subcommand, Ajent opens the interactive coding agent. The workspace
defaults to the current directory and confines filesystem tools to that tree.

## Authentication and providers

Anthropic is the default provider. Sign in interactively:

```powershell
.\ajent.cmd login
.\ajent.cmd status
.\ajent.cmd --workspace .
```

Alternatively, provide an API key for the current process:

```powershell
$env:ANTHROPIC_API_KEY = "..."
.\ajent.cmd --provider anthropic --workspace .
```

OpenAI-compatible providers use `OPENAI_API_KEY`, a provider-specific key such
as `GROQ_API_KEY`, or the one-shot `--key` option:

```powershell
$env:OPENAI_API_KEY = "..."
.\ajent.cmd --provider openai --model MODEL_ID --workspace .
```

ChatGPT subscription access is a distinct `codex` provider. Sign in with the
official Codex CLI, then explicitly copy that login into Ajent's encrypted
store:

```powershell
codex login
.\ajent.cmd login --provider codex
.\ajent.cmd --provider codex --model MODEL_ID --workspace .
```

Local providers do not require a key:

```powershell
.\ajent.cmd --provider ollama --model MODEL_ID --workspace .
.\ajent.cmd --provider llama.cpp --model MODEL_ID --workspace .
```

Never place API keys in the repository or command history. Environment values
override saved credentials.

## Interactive controls

The command palette (`Ctrl-K`) exposes the available actions. Common controls
include:

- `Enter`: submit; `Shift-Enter` or `Alt-Enter`: insert a newline
- `Esc`: cancel an active model turn or close the current modal
- `Page Up` / `Page Down`: browse the in-session transcript;
  `Esc` or `End` returns to the live view
- mouse drag uses the terminal's native text selection; in Windows Terminal,
  `Ctrl-Shift-C` copies the selection and `Ctrl-Shift-V` pastes
- `Ctrl-C`: exit
- `Ctrl-J`: browse saved threads
- `Alt-Left` / `Alt-Right`: cycle recent threads
- `Ctrl-T`: open the plan
- `Ctrl-G`: run a fenced code block from the latest assistant reply
- `Ctrl-O`: inspect tool output
- `Left` / `Right` in the model picker: cycle supported reasoning effort
- `@` at a word boundary: attach a workspace file
- `#` at a word boundary: attach a workspace symbol excerpt
- bracketed text paste: insert one compact paste attachment chip
- `Ctrl-V` / `Alt-V`: smart-paste an image first, then clipboard text
- `Y` / `N` in change review: mark the active hunk; `A` / `X` apply to all
- `Shift-A` / `Shift-X` outside the modal: accept or reject all pending changes

Write, execution, and network tools are governed by the active permission
profile. The `ask` profile prompts before consequential operations; `minimal`
also prompts for reads, while `write` is fully autonomous. A fresh interactive
settings file starts in `write` mode, and later launches restore the saved
profile from `~/.ajent/settings.json`.
The `task` tool delegates a self-contained job to an isolated subagent using
the currently selected provider and model. Explorer and reviewer delegates are
read-only; tester, coder, and general delegates receive their native scoped
tool sets. Cancelling the parent turn also cancels an active delegate.

## Other process modes

```powershell
.\ajent.cmd skills
.\ajent.cmd mcp-serve --workspace .
.\ajent.cmd acp --workspace .
.\ajent.cmd airgap --help
```

`mcp-serve` and `acp` use standard input/output as protocol channels; avoid
wrapping them with commands that write unrelated text to stdout.

## Repository structure

- `ajent-domain`: immutable conversation and configuration records
- `ajent-core`: scheduling, persistence, credentials, and shared services
- `ajent-provider`: Anthropic and OpenAI-compatible wire transports
- `ajent-tools`: sandboxed native coding tools
- `ajent-runtime`: agent reducer, effects, provider/tool loop, and compaction
- `ajent-protocol`: ACP and MCP protocol servers and clients
- `ajent-terminal`: terminal input, rendering, composer, and modal models
- `ajent-cli`: process commands and the interactive composition root
- `ajent-benchmarks`: standalone JMH performance probes and diagnostics

## Development

Behavior changes start with focused Java tests. Run every module from the
reactor root so cross-module composition is covered:

```powershell
mvn -q test
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) and [design invariants](docs/DESIGN.md)
- [Configuration and state](docs/CONFIGURATION.md)
- [Environment variable reference](docs/ENVIRONMENT.md)
- [Authentication](docs/AUTH.md) and [providers](docs/PROVIDERS.md)
- [Terminal UI](docs/UI.md), [rendering](docs/RENDERING.md), and
  [session scrollback](docs/INLINE_SCROLLBACK.md)
- [Tools, permissions, and subagents](docs/TOOLS.md)
- [ACP and MCP protocols](docs/PROTOCOLS.md)
- [Running assistant code blocks](docs/RUN_CODE_BLOCK.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Dependencies](docs/DEPENDENCIES.md)
- [Contributing](CONTRIBUTING.md), [release procedure](docs/RELEASE.md), and
  [changelog](CHANGELOG.md)

## License

Ajent is distributed under the MIT License. See [LICENSE](LICENSE) and
[NOTICE](NOTICE).
