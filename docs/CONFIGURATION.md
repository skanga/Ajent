# Configuration and state

Ajent preserves AgenTTY's command-line, environment, and persistence contracts.
This guide distinguishes transient process configuration from durable state so
that an override never silently becomes a saved secret.

## Precedence

For a normal launch, explicit command-line values win over environment values,
which win over saved settings, which win over built-in defaults. Provider
credentials follow the more specific order documented in `AUTH.md`: `--key`,
provider-specific environment variable, compatible fallback variable, saved
credential, then no credential for keyless local providers.

The workspace defaults to the current directory. Relative paths are resolved
against that directory and normalized before use.

## Command-line options

| Option | Meaning | Default |
| --- | --- | --- |
| `-w`, `--workspace DIR` | filesystem and process containment root | current directory |
| `-m`, `--model ID` | model for this launch | last provider model or provider default |
| `--provider ID` | provider preset or custom `host[:port]` | saved provider, then Anthropic |
| `-k`, `--key KEY` | one-launch credential override | none |
| `-p`, `--profile MODE` | `ask`, `minimal`, or `write` permission profile | saved profile; `write` for new interactive state |
| `--sandbox MODE` | `auto`, `on`, or `off` process sandbox selection | `auto` |
| `-h`, `--help` | print usage | — |
| `-V`, `--version` | print version | — |

Passing `--workspace /` intentionally disables the workspace boundary and
should be reserved for a trusted session. `--sandbox off` disables only the OS
process wrapper; it does not disable workspace path checks or tool permission
decisions.

## Environment variables

Authentication:

- `ANTHROPIC_API_KEY`
- `OPENAI_API_KEY`
- `GROQ_API_KEY`
- `OPENROUTER_API_KEY`
- `TOGETHER_API_KEY`
- `CEREBRAS_API_KEY`

Runtime and transport:

- `AGENTTY_SOCKS_PROXY=host:port` routes outbound HTTP through the air-gap
  bridge contract.
- `AGENTTY_API_HOST=host:port` overrides the provider dial destination while
  retaining the logical request host.
- `AGENTTY_OAUTH_HOST=host:port` overrides the OAuth dial destination.
- `AGENTTY_INSECURE=1` disables TLS certificate and hostname verification for
  controlled test or tunnel environments. It is process-wide and unsafe on an
  untrusted network.
- `AGENTTY_DEBUG_LOG=PATH` writes best-effort diagnostic events to a file;
  protocol stdout remains clean.

Location and launcher variables:

- `AJENT_JAVA_HOME` selects the JDK used by `ajent.cmd` or `ajent`.
- `JAVA_HOME` is the launcher fallback.
- `XDG_CONFIG_HOME`, then `HOME`/`USERPROFILE`, determines the compatible
  credential location.

The `AGENTTY_` names are retained intentionally for native compatibility. See
`ENVIRONMENT.md` for the complete provider, MCP, RAG, Ollama, terminal,
air-gap, and diagnostics reference.

## Persistent files

Ajent keeps AgenTTY-compatible application data under `~/.agentty`:

```text
~/.agentty/
  settings.json
  threads/
    THREAD_ID.json
    acp_sessions.json
```

`settings.json` stores the selected model, numeric permission profile,
favorites, provider, provider-specific model selections, reasoning effort,
always-allowed tool names, and provider keys saved through the provider UI.
Writes use a temporary file, forced flush, and atomic replacement when the
filesystem supports it. Malformed optional state falls back to defaults and is
reported only through the debug log.

Thread files retain stable message/tool ids, roles, text, images, attachments,
thinking/signatures, tool results, errors, checkpoints, timestamps, and wire
compaction records. `acp_sessions.json` is a metadata sidecar mapping ACP
session ids to working directories, titles, and update times; it is not a
conversation file and is skipped by the saved-thread metadata walk. Completed
turns are flushed before process shutdown. The executable parity gate compares
both files against AgenTTY after a real streamed ACP turn and also verifies
model-setting persistence before later startup validation fails.

Anthropic login credentials are stored separately at
`$XDG_CONFIG_HOME/agentty/credentials.json`, or
`~/.config/agentty/credentials.json` when XDG configuration is unset. The file
uses the compatible encrypted credential envelope described in `AUTH.md`.

The legacy `.agentty` names are part of data compatibility, not a typo. Do not
rename these locations without a migration that reads both formats and proves
round-trip compatibility.

## Workspace configuration and authored context

Ajent discovers authored instruction and knowledge sources from the workspace
composition path. Depending on provider and feature, these include:

- `CLAUDE.md` in the user home and workspace;
- `CLAUDE.local.md` in the workspace;
- workspace `docs/` content for the lightweight knowledge index;
- `.agentty/knowledge` for compatible local knowledge;
- discovered skill specifications described by the `skills` command.

Instruction files are bounded before entering a provider prompt. Tool path
arguments remain subject to the workspace sandbox even when authored context
mentions paths elsewhere.

## Permission profiles

- `ask` prompts for consequential write, execution, and network operations.
- `minimal` also prompts for reads.
- `write` is fully autonomous and auto-approves reads, writes, execution, and
  network effects.

An approval for the current call is not durable. Choosing the “always” action
records the tool name in `always_allow_tools`; use that option only for a tool
whose entire argument surface you trust. Cycling profile clears these grants
both in the active session and in settings. ACP starts in `ask` unless its
`--profile` option says otherwise; it does not use the TUI's saved profile.

## Recovery

To diagnose state without deleting it, run `ajent status`, set
`AGENTTY_DEBUG_LOG`, and inspect `~/.agentty/settings.json`. Back up the data
directory before manual edits. Removing one corrupt thread file loses that
thread; removing `settings.json` resets preferences; `ajent logout` is the
supported way to remove saved Anthropic credentials.

See `AUTH.md`, `PROVIDERS.md`, `TOOLS.md`, and `TROUBLESHOOTING.md` for the
corresponding subsystem details.
