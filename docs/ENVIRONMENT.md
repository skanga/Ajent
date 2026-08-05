# Environment variable reference

Ajent uses its own `AJENT_` environment namespace and does not read legacy
`AJENT_` or `MAYA_` variables. Command-line options take precedence where the
same setting has a CLI form. Unless stated otherwise, unset or empty variables
select the documented default.

Boolean switches accept a non-empty value other than `0` or `false`; a few
terminal compatibility switches use the native first-character rule described
below.

## Provider credentials

| Variable | Purpose |
| --- | --- |
| `ANTHROPIC_API_KEY` | Anthropic API-key credential; takes precedence over a saved login. |
| `OPENAI_API_KEY` | OpenAI key and fallback key for compatible hosted providers. |
| `CODEX_HOME` | Codex CLI directory used only during explicit `login --provider codex` import. |
| `AJENT_CODEX_CLIENT_VERSION` | Optional semantic Codex CLI version sent during subscription model discovery. |
| `GROQ_API_KEY` | Groq-specific key before the OpenAI fallback. |
| `OPENROUTER_API_KEY` | OpenRouter-specific key before the OpenAI fallback. |
| `TOGETHER_API_KEY` | Together-specific key before the OpenAI fallback. |
| `CEREBRAS_API_KEY` | Cerebras-specific key before the OpenAI fallback. |

Do not persist these values in repository files, shell profiles shared with
other users, debug captures, or CI logs.

## HTTP routing and air-gap operation

| Variable | Purpose |
| --- | --- |
| `AJENT_SOCKS_PROXY=HOST:PORT` | Route provider, OAuth, and general JDK HTTP traffic through a SOCKS5 endpoint. |
| `AJENT_API_HOST=HOST:PORT` | Override the provider dial destination while preserving the logical TLS/HTTP host. |
| `AJENT_OAUTH_HOST=HOST:PORT` | Override the OAuth dial destination. |
| `AJENT_INSECURE=1` | Disable certificate and hostname verification process-wide. |
| `AJENT_AIRGAP_SSH="ARGS"` | Extra whitespace-delimited arguments for the `ssh` and `scp` commands used by `ajent airgap`. |
| `AJENT_NO_SSH_THROTTLE=1` | Disable the 80 ms minimum interactive frame interval detected for SSH sessions. |

`AJENT_INSECURE` is intended only for a controlled tunnel or test server. It
changes the JDK HTTP client's TLS behavior for the process and must not be used
as a routine certificate workaround.

`AJENT_AIRGAP_SSH` is split as arguments rather than evaluated by a shell.
For example, `-i keyfile -p 2222 -J bastion` adds an identity, port, and jump
host. Paths containing whitespace cannot be represented by this compatibility
variable; configure SSH aliases in `~/.ssh/config` instead.

## Provider and generation tuning

| Variable | Default | Purpose |
| --- | --- | --- |
| `AJENT_MAX_OUTPUT_TOKENS=N` | model-derived | Positive leading integer overriding the provider output ceiling. |
| `AJENT_OLLAMA_NUM_CTX=N` | probed window clamped to 8,192–32,768 | Ollama `num_ctx`. |
| `AJENT_OLLAMA_NUM_PREDICT=N` | request limit bounded by context | Ollama `num_predict`. |
| `AJENT_OLLAMA_TEMPERATURE=X` | protocol-dependent | Ollama floating-point temperature override. |

Invalid or non-positive integer overrides fall back to native calculation.
`AJENT_MAX_OUTPUT_TOKENS` changes the generation ceiling, not Ajent's context
compaction thresholds. Weak local models may still select the JSON tool
protocol and its native low-temperature defaults; an explicit Ollama
temperature wins last.

## Retrieval and local Ollama services

| Variable | Default | Purpose |
| --- | --- | --- |
| `AJENT_OLLAMA_HOST=HOST[:PORT]` | `127.0.0.1:11434` | Ollama endpoint used by embeddings, query expansion, and neural reranking. |
| `AJENT_EMBED_MODEL=ID` | `nomic-embed-text` | Ollama embedding model. |
| `AJENT_RAG_SKILLS` | enabled | Include discovered skills as a knowledge source. |
| `AJENT_RAG_MEMORY` | enabled | Include durable memory as a knowledge source. |
| `AJENT_RAG_MCP` | disabled | Include an external MCP knowledge source when one is available. |
| `AJENT_RAG_EXPAND` | disabled | Enable generative multi-query expansion. |
| `AJENT_RAG_EXPAND_MODEL=ID` | `AJENT_MODEL`, then `llama3.2` | Expansion model. |
| `AJENT_RAG_EXPAND_N=N` | `4` | Number of expansion variants, clamped to 1–8. |
| `AJENT_RAG_NEURAL` | disabled | Enable bounded neural reranking after deterministic retrieval. |
| `AJENT_RAG_NEURAL_MODEL=ID` | `llama3.2` | Neural scoring model. |
| `AJENT_MODEL=ID` | empty | Fallback only for the RAG expansion model. |

The primary interactive provider/model is selected through `--provider`,
`--model`, or saved settings; `AJENT_MODEL` does not replace that selection.
If the local embedding or generation service is unavailable, retrieval degrades
to its deterministic lexical path rather than failing the coding session.

## MCP client configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `AJENT_MCP_CONFIG=PATH` | discovery | Use one explicit MCP JSON configuration file. |
| `AJENT_MCP_ALLOW_PROJECT=1` | disabled | Trust and enable workspace-local `.ajent/mcp.json`. |
| `AJENT_MCP_TIMEOUT_MS=N` | `60000` | Per-call timeout for connected MCP servers. |
| `AJENT_MCP_CONNECT_TIMEOUT_MS=N` | `15000` | Global startup connection deadline. |

Without an explicit path, Ajent checks workspace `.ajent/mcp.json`, then user
`~/.ajent/mcp.json`. The workspace file is ignored unless the project trust
switch is enabled because it can name arbitrary commands. An explicit file is
considered an intentional trust decision. Invalid non-positive timeout values
fall back to their defaults.

See `PROTOCOLS.md` for the `mcpServers`/`servers` JSON shape, stdio command and
environment fields, HTTP URL/headers, namespacing, and lifecycle.

## Knowledge and documentation roots

| Variable | Purpose |
| --- | --- |
| `AJENT_DOCS_DIR=PATH` | Explicit filesystem root for document retrieval. |
| `XDG_CONFIG_HOME` | Base for saved credentials. |
| `HOME`, `USERPROFILE` | Home fallback for state, credentials, skills, memory, and authored instructions. |

When `AJENT_DOCS_DIR` is absent, Ajent selects workspace `docs/`, then
workspace `.ajent/knowledge`, when either exists. A malformed explicit path
disables that document root rather than escaping the workspace tool sandbox.

## Clipboard and terminal rendering

| Variable | Purpose |
| --- | --- |
| `AJENT_CLIPBOARD_CMD=COMMAND` | Shell command whose stdout is interpreted as clipboard image bytes. |
| `AJENT_FROZEN_COLLAPSE=1` | Enable compact rendering for frozen transcript sections. |
| `AJENT_FORCE_SYNC=1` | Force synchronized terminal output and the faster local frame cadence. |
| `AJENT_NO_SYNC=1` | Disable synchronized terminal output. |
| `AJENT_COMPAT_REPAINT=1` | Force compatibility repaint behavior for inline frames. |

`AJENT_CLIPBOARD_CMD` is intentionally a shell command and therefore a code-
execution boundary. Configure only a command you trust. It is forwarded by the
air-gap launcher or synthesized by `--clipboard-relay`.

Ajent also reads standard terminal identity variables such as `TERM`,
`TERM_PROGRAM`, `TERM_PROGRAM_VERSION`, `KITTY_WINDOW_ID`, `WT_SESSION`, VTE,
Alacritty, Ghostty, WezTerm, Konsole, tmux/screen, and SSH markers. These are
capability fingerprints rather than user configuration. They select synchronized
output, clipboard query dialect, inline repaint compatibility, and frame rate.

`AJENT_FROZEN_COLLAPSE` is enabled when its first character is `1`, `t`, `T`,
`y`, or `Y`. `AJENT_NO_SSH_THROTTLE` is enabled by any non-empty value whose
first character is not `0`.

## Diagnostics and protocol traces

| Variable | Purpose |
| --- | --- |
| `AJENT_DEBUG_LOG=PATH` | Append best-effort typed catch-site diagnostics. |
| `AJENT_DEBUG_API=1` | Enable raw provider timing/event diagnostics. |
| `AJENT_DEBUG_FILE=PATH` | Destination for API diagnostics; defaults to `ajent-api.log`. |
| `AJENT_ACP_TRACE=1` | Copy ACP input/output trace lines to stderr. |

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
