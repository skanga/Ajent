# Providers and models

Ajent supports Anthropic, Ollama, and a family of OpenAI Chat
Completions-compatible providers. Provider selection is runtime state: switch
inside the UI and the next turn uses the new provider/model/auth snapshot.

## Supported dialects

### Anthropic

Uses the Messages API with typed content blocks, tool use/results, images,
prompt caching, signed thinking replay, adaptive reasoning, usage counters, and
incremental SSE. Both API-key and Anthropic OAuth credentials are supported.

### Ollama

Uses Ollama's native chat/model/embedding endpoints. Ajent probes models and
context capabilities, selects native or JSON-protocol tool handling according
to model strength, and can use Ollama for optional repository embeddings,
query expansion, and reranking.

### OpenAI-compatible

Presets include OpenAI, Groq, OpenRouter, Together, Cerebras, llama.cpp, and a
custom host. They use `/v1/chat/completions`, incremental SSE, OpenAI-style
tool calls, and `/v1/models` where supported.

Ajent does not currently implement `/v1/responses`. That is an optional future
extension, not part of the pinned AgenTTY behavior.

## Selecting a provider

At startup:

```powershell
.\ajent.cmd --provider anthropic --model MODEL_ID --workspace .
.\ajent.cmd --provider openai --model MODEL_ID --workspace .
.\ajent.cmd --provider ollama --model MODEL_ID --workspace .
.\ajent.cmd --provider llama.cpp --model MODEL_ID --workspace .
```

Inside the UI, Ctrl-P opens providers and Ctrl-/ opens models. Provider switch
may request a key or custom URL, then fetch a model catalog. Model/provider
choices and last-used per-provider models are persisted.

## Authentication

Hosted presets use provider-specific environment variables, saved encrypted
keys, or `--key`. Anthropic also supports OAuth. Local presets are explicitly
keyless. See [AUTH.md](AUTH.md) for precedence and storage.

## Request composition

The runtime builds each request at call time from:

- the current conversation wire projection;
- selected provider/model/effort and model capabilities;
- provider-specific system prompt;
- ordered native tool specifications permitted by profile;
- expanded attachment bodies;
- context and output ceilings;
- current authentication and endpoint overrides.

Anthropic and local prompts are deliberately different. Hosted models receive
the full action/tool/repository/memory/skills guidance. Ollama receives a slim
action-heavy prompt with local-model safeguards. Other OpenAI-compatible local
or hosted endpoints receive a concise prompt without pretending to use
Anthropic wire semantics. Authored `CLAUDE.md` tiers remain available where the
reference uses them; learned memory/skills are included only in supported
prompt paths.

## Conversation projection

Stored messages are not serialized generically. Anthropic builders preserve
content-block ordering, signed thinking, cache points, tool results, and image
blocks. OpenAI builders create role messages and tool call/result pairs.
Ollama builders support both native history and JSON-protocol recovery.

Before serialization, normal turns soft-trim to approximately the reference
context target. Compaction uses a separate bounded summary request. Provider
output limits are clamped by model capability and explicit request overrides.

## Streaming

Transport reads bytes incrementally and feeds a provider decoder. Decoders
emit typed events for:

- message/block start;
- text delta and text-block close;
- thinking and signature;
- tool-call start and partial JSON;
- usage counters;
- heartbeat/liveness;
- message stop, refusal, maximum tokens, cancellation, and errors.

Unknown forward-compatible frames are ignored where the reference does so.
EOF during a partial tool call attempts bounded structural repair and salvage;
deduplication prevents a salvaged call from running again if a later complete
frame describes the same call.

## Cancellation and liveness

JDK HTTP requests run asynchronously. Cancellation can close a request waiting
for headers or a body read. A byte-idle watchdog closes stalled streams; the
reducer separately watches semantic progress. Terminal events are emitted at
most once even when cancellation, EOF, timeout, and parser failure race.

## Retry policy

Status codes, transport errors, malformed stream, overload, authentication,
refusal, and maximum-token stops are classified before retry. Retryable errors
use native delay ladders with jitter, `Retry-After` clamping, and a decaying
budget. First healthy deltas/heartbeats reset the appropriate protection.
Visible mid-stream output sharply limits retry to avoid duplication.

## Reasoning effort

`ModelCapabilities.fromId` maps known model families to supported effort
ladders and output/context traits. The model picker only offers valid effort
values. A stored effort is clamped again at request time so switching from a
capable model to a weaker one cannot send an invalid field.

## Endpoint and network overrides

The environment-aware HTTP client applies proxy/air-gap and native dial/host
overrides consistently to model listing, provider requests, OAuth, MCP HTTP,
web tools, and optional Ollama RAG calls. A custom host is normalized before
paths are appended. Debug logs redact secrets.

## Adding a provider

1. Decide whether the provider is an existing OpenAI-compatible preset or a
   genuinely new wire dialect.
2. Add registry metadata, auth environment, endpoint, and model-catalog rules.
3. For a new dialect, add a request builder, incremental decoder, and transport
   adapter behind the provider-neutral contracts.
4. Add exact body/header/event/cancellation/retry tests and loopback HTTP.
5. Add prompt/capability selection tests and update documentation/parity.

Do not add a provider through a general LLM framework that changes the wire or
retry semantics of existing providers.

## Diagnostics

For a failed provider, test in this order:

1. `ajent status` and selected provider;
2. model listing;
3. a no-tool minimal prompt;
4. one read-only tool call;
5. cancellation and a longer stream;
6. proxy/air-gap route if present.

Capture HTTP status and sanitized response body, never authorization headers.
See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
