# Environment variable reference

Ajent retains AgenTTY's environment names so existing scripts, tunnels, MCP
configuration, and local-model tuning continue to work. Command-line options
take precedence where the same setting has a CLI form. Unless stated
otherwise, unset or empty variables select the native default.

Boolean switches accept a non-empty value other than `0` or `false`; a few
terminal compatibility switches use the native first-character rule described
below.

## Provider credentials

| Variable | Purpose |
| --- | --- |
| `ANTHROPIC_API_KEY` | Anthropic API-key credential; takes precedence over a saved login. |
| `OPENAI_API_KEY` | OpenAI key and fallback key for compatible hosted providers. |
| `GROQ_API_KEY` | Groq-specific key before the OpenAI fallback. |
| `OPENROUTER_API_KEY` | OpenRouter-specific key before the OpenAI fallback. |
| `TOGETHER_API_KEY` | Together-specific key before the OpenAI fallback. |
| `CEREBRAS_API_KEY` | Cerebras-specific key before the OpenAI fallback. |

Do not persist these values in repository files, shell profiles shared with
other users, debug captures, or CI logs.

## HTTP routing and air-gap operation

| Variable | Purpose |
| --- | --- |
| `AGENTTY_SOCKS_PROXY=HOST:PORT` | Route provider, OAuth, and general JDK HTTP traffic through a SOCKS5 endpoint. |
| `AGENTTY_API_HOST=HOST:PORT` | Override the provider dial destination while preserving the logical TLS/HTTP host. |
| `AGENTTY_OAUTH_HOST=HOST:PORT` | Override the OAuth dial destination. |
| `AGENTTY_INSECURE=1` | Disable certificate and hostname verification process-wide. |
| `AGENTTY_AIRGAP_SSH="ARGS"` | Extra whitespace-delimited arguments for the `ssh` and `scp` commands used by `ajent airgap`. |
| `AGENTTY_NO_SSH_THROTTLE=1` | Disable the 80 ms minimum interactive frame interval detected for SSH sessions. |

`AGENTTY_INSECURE` is intended only for a controlled tunnel or test server. It
changes the JDK HTTP client's TLS behavior for the process and must not be used
as a routine certificate workaround.

`AGENTTY_AIRGAP_SSH` is split as arguments rather than evaluated by a shell.
For example, `-i keyfile -p 2222 -J bastion` adds an identity, port, and jump
host. Paths containing whitespace cannot be represented by this compatibility
variable; configure SSH aliases in `~/.ssh/config` instead.

## Provider and generation tuning

| Variable | Default | Purpose |
| --- | --- | --- |
| `AGENTTY_MAX_OUTPUT_TOKENS=N` | model-derived | Positive leading integer overriding the provider output ceiling. |
| `AGENTTY_OLLAMA_NUM_CTX=N` | probed window clamped to 8,192–32,768 | Ollama `num_ctx`. |
| `AGENTTY_OLLAMA_NUM_PREDICT=N` | request limit bounded by context | Ollama `num_predict`. |
| `AGENTTY_OLLAMA_TEMPERATURE=X` | protocol-dependent | Ollama floating-point temperature override. |

Invalid or non-positive integer overrides fall back to native calculation.
`AGENTTY_MAX_OUTPUT_TOKENS` changes the generation ceiling, not Ajent's context
compaction thresholds. Weak local models may still select the JSON tool
protocol and its native low-temperature defaults; an explicit Ollama
temperature wins last.

## Retrieval and local Ollama services

| Variable | Default | Purpose |
| --- | --- | --- |
| `AGENTTY_OLLAMA_HOST=HOST[:PORT]` | `127.0.0.1:11434` | Ollama endpoint used by embeddings, query expansion, and neural reranking. |
| `AGENTTY_EMBED_MODEL=ID` | `nomic-embed-text` | Ollama embedding model. |
| `AGENTTY_RAG_SKILLS` | enabled | Include discovered skills as a knowledge source. |
| `AGENTTY_RAG_MEMORY` | enabled | Include durable memory as a knowledge source. |
| `AGENTTY_RAG_MCP` | disabled | Include an external MCP knowledge source when one is available. |
| `AGENTTY_RAG_EXPAND` | disabled | Enable generative multi-query expansion. |
| `AGENTTY_RAG_EXPAND_MODEL=ID` | `AGENTTY_MODEL`, then `llama3.2` | Expansion model. |
| `AGENTTY_RAG_EXPAND_N=N` | `4` | Number of expansion variants, clamped to 1–8. |
| `AGENTTY_RAG_NEURAL` | disabled | Enable bounded neural reranking after deterministic retrieval. |
| `AGENTTY_RAG_NEURAL_MODEL=ID` | `llama3.2` | Neural scoring model. |
| `AGENTTY_MODEL=ID` | empty | Compatibility fallback only for the RAG expansion model. |

The primary interactive provider/model is selected through `--provider`,
`--model`, or saved settings; `AGENTTY_MODEL` does not replace that selection.
If the local embedding or generation service is unavailable, retrieval degrades
to its deterministic lexical path rather than failing the coding session.

## MCP client configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `AGENTTY_MCP_CONFIG=PATH` | discovery | Use one explicit MCP JSON configuration file. |
| `AGENTTY_MCP_ALLOW_PROJECT=1` | disabled | Trust and enable workspace-local `.agentty/mcp.json`. |
| `AGENTTY_MCP_TIMEOUT_MS=N` | `60000` | Per-call timeout for connected MCP servers. |
| `AGENTTY_MCP_CONNECT_TIMEOUT_MS=N` | `15000` | Global startup connection deadline. |

Without an explicit path, Ajent checks workspace `.agentty/mcp.json`, then user
`~/.agentty/mcp.json`. The workspace file is ignored unless the project trust
switch is enabled because it can name arbitrary commands. An explicit file is
considered an intentional trust decision. Invalid non-positive timeout values
fall back to their defaults.

See `PROTOCOLS.md` for the `mcpServers`/`servers` JSON shape, stdio command and
environment fields, HTTP URL/headers, namespacing, and lifecycle.

## Knowledge and documentation roots

| Variable | Purpose |
| --- | --- |
| `AGENTTY_DOCS_DIR=PATH` | Explicit filesystem root for document retrieval. |
| `XDG_CONFIG_HOME` | Base for compatible saved credentials. |
| `HOME`, `USERPROFILE` | Home fallback for state, credentials, skills, memory, and authored instructions. |

When `AGENTTY_DOCS_DIR` is absent, Ajent selects workspace `docs/`, then
workspace `.agentty/knowledge`, when either exists. A malformed explicit path
disables that document root rather than escaping the workspace tool sandbox.

## Clipboard and terminal rendering

| Variable | Purpose |
| --- | --- |
| `AGENTTY_CLIPBOARD_CMD=COMMAND` | Shell command whose stdout is interpreted as clipboard image bytes. |
| `AGENTTY_FROZEN_COLLAPSE=1` | Enable compact rendering for frozen transcript sections. |
| `MAYA_FORCE_SYNC=1` | Force synchronized terminal output and the faster local frame cadence. |
| `MAYA_NO_SYNC=1` | Disable synchronized terminal output. |
| `MAYA_COMPAT_REPAINT=1` | Force compatibility repaint behavior for inline frames. |

`AGENTTY_CLIPBOARD_CMD` is intentionally a shell command and therefore a code-
execution boundary. Configure only a command you trust. It is forwarded by the
air-gap launcher or synthesized by `--clipboard-relay`.

Ajent also reads standard terminal identity variables such as `TERM`,
`TERM_PROGRAM`, `TERM_PROGRAM_VERSION`, `KITTY_WINDOW_ID`, `WT_SESSION`, VTE,
Alacritty, Ghostty, WezTerm, Konsole, tmux/screen, and SSH markers. These are
capability fingerprints rather than user configuration. They select synchronized
output, clipboard query dialect, inline repaint compatibility, and frame rate.

`AGENTTY_FROZEN_COLLAPSE` is enabled when its first character is `1`, `t`, `T`,
`y`, or `Y`. `AGENTTY_NO_SSH_THROTTLE` is enabled by any non-empty value whose
first character is not `0`.

## Diagnostics and protocol traces

| Variable | Purpose |
| --- | --- |
| `AGENTTY_DEBUG_LOG=PATH` | Append best-effort typed catch-site diagnostics. |
| `AGENTTY_DEBUG_API=1` | Enable raw provider timing/event diagnostics. |
| `AGENTTY_DEBUG_FILE=PATH` | Destination for API diagnostics; defaults to `agentty-api.log`. |
| `AGENTTY_ACP_TRACE=1` | Copy ACP input/output trace lines to stderr. |

API debug output may contain prompts, streamed content, tool arguments, file
paths, provider metadata, and other sensitive session data even though tests
guard against authorization-header disclosure. Store it privately, keep it out
of bug reports unless redacted, and delete it when diagnosis is complete.

ACP trace output goes to stderr so stdout remains a valid JSON-RPC channel.
General debug logging is observational: failure to open or append the log never
fails a provider call or persistence operation.

## Java and launcher selection

| Variable | Purpose |
| --- | --- |
| `AJENT_JAVA_HOME` | Preferred Java 25 installation for the bundled launchers. |
| `JAVA_HOME` | Launcher fallback when `AJENT_JAVA_HOME` is unset. |

If neither is set, the launchers use `java` from `PATH`. The runtime must be
Java 25 even when Maven itself starts under a different JDK and selects 25
through the toolchain file.
