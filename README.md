# Ajent

Ajent is a modern Java 25 port of [AgenTTY](https://github.com/1ay1/agentty),
an interactive coding agent for the terminal. The port preserves AgenTTY's
conversation model, provider protocols, native tool contracts, permission
profiles, persistence formats, terminal behavior, and keyboard-driven
workflows while using Java records, sealed interfaces, virtual threads, and
the JDK HTTP client.

Ajent is under active parity work. The main interactive agent, Anthropic and
OpenAI-compatible providers, the native tool catalog, saved threads, plans,
code-block execution, Git checkpoints/rewind, MCP server, ACP server, skills,
authentication, and air-gap commands are runnable today. See
[docs/PARITY.md](docs/PARITY.md) for the precise translated-test and feature
ledger.

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

The JaCoCo skip on `package` only avoids running the verification-time coverage
gate when producing a local executable. It does not skip tests. Use
`mvn -q verify` for the complete quality gate.

## Run

On Windows, after packaging:

```powershell
.\ajent.cmd --version
.\ajent.cmd --help
.\ajent.cmd --workspace .
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
- `Ctrl-C`: exit
- `Ctrl-J`: browse saved threads
- `Alt-Left` / `Alt-Right`: cycle recent threads
- `Ctrl-T`: open the plan
- `Ctrl-G`: run a fenced code block from the latest assistant reply
- `Ctrl-O`: inspect tool output
- `Left` / `Right` in the model picker: cycle supported reasoning effort

Write, execution, and network tools are governed by the active permission
profile. The default `ask` profile prompts before consequential operations.

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
- `ajent-parity`: source/binary parity fixtures and manifests
- `agentty`: local upstream reference checkout; never included in Ajent releases

## Development and parity

Ported behavior starts with Java tests that reproduce AgenTTY's original test
conditions. Run every module from the reactor root; do not validate modules in
isolation:

```powershell
mvn -q test
```

The authoritative construction sequence is
[plans/ajent-agentty-java-port.md](plans/ajent-agentty-java-port.md). Source
provenance is recorded in [docs/PROVENANCE.md](docs/PROVENANCE.md), while
[docs/PARITY.md](docs/PARITY.md) distinguishes implemented, verified, and
remaining native behavior.

## License and upstream attribution

Ajent is a clean Java port derived from AgenTTY's observable behavior, tests,
and documented interfaces. See [NOTICE](NOTICE) and
[docs/PROVENANCE.md](docs/PROVENANCE.md) for attribution and source mapping.
