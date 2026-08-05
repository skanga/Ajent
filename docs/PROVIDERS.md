# Providers and models

Ajent supports Anthropic, ChatGPT/Codex subscriptions, Ollama, and a family of
OpenAI Chat Completions-compatible providers. Provider selection is runtime state: switch
inside the UI and the next turn uses the new provider/model/auth snapshot.

## Supported dialects

### Anthropic

Uses the Messages API with typed content blocks, tool use/results, images,
prompt caching, signed thinking replay, adaptive reasoning, usage counters, and
incremental SSE. Both API-key and Anthropic OAuth credentials are supported.

Loopback tests cover the exact Messages API path, API-key headers, complete
request bodies, generated metadata shape, fragmented SSE text and tool-input
assembly, four-counter usage, stop reasons, a permissioned write and
tool-result continuation, one-shot `429` handling, and cancellation of an open
response body.

### Ollama

Uses Ollama's native chat/model/embedding endpoints. Ajent probes models and
context capabilities, selects native or JSON-protocol tool handling according
to model strength, and can use Ollama for optional repository embeddings,
query expansion, and reranking.

Loopback tests verify complete `/api/chat` bodies,
fragmented NDJSON, usage and stop reasons, structured tool execution and
continuation, persisted-image replay, no-key operation, model-specific 404
guidance, and cancellation of an open response body. A weak
`qwen2.5-coder:7b` turn additionally proves the exact grammar schema, inline
UTF-8-byte-truncated tool catalog, fragmented JSON call salvage, argument
repair, filesystem execution, canonical continuation history, and unwrapped
`response` pseudo-tool. Missing native tool-call ids use Ajent's exact
`call_ollama_<sequence>_<index>` form.

### OpenAI-compatible

Presets include OpenAI, Groq, OpenRouter, Together, Cerebras, llama.cpp, and a
custom host. They use `/v1/chat/completions`, incremental SSE, OpenAI-style
tool calls, and `/v1/models` where supported.

Loopback tests drive OpenAI-compatible turns and verify complete request sequences,
including accepted, rejected, and two sequential tool-result continuations. It
also splits each of two calls across multiple SSE frames, executes the
same-path batch in order, and compares the canonical two-result continuation.
For a weak `qwen2.5-coder:7b` model, it additionally streams a bare JSON call
through `delta.content` with `finish_reason: stop`, then proves identical
salvage, one execution, synthetic call identity, and structured continuation.
The same suite proves the ACP-specific error policy: `429` is one-shot
even with `Retry-After`, and a terminal `400` becomes the same refusal with
exact error metadata. The interactive reducer separately retains
transient/rate-limit retry ladders; provider recovery is surface-specific.
This covers the native 22-tool provider subset and recall-biased order,
system/user messages, output controls, tool-call replay, canonical argument
encoding, and usage/stop stream handling without sending traffic to an external
provider.
The suite also holds local HTTP and hosted HTTP/2 SSE bodies open, cancels each
turn through ACP, and requires the native cancellation error and prompt
metadata. For OpenAI, Groq, OpenRouter, Together, and Cerebras it also preserves
the logical Host and TLS SNI while safely dialing a loopback server, then
compares the exact preset path, bearer/content headers, versioned user agent,
request body, and streamed result.

Concurrent Ajent sessions always receive the full immutable provider tool
catalog.

### Codex (ChatGPT subscription)

Codex is a distinct provider kind. It uses the streaming
Responses protocol at the ChatGPT Codex backend, not API-key OpenAI Chat
Completions. Requests contain stateless user/assistant messages, images,
function calls, and `function_call_output` continuations. The decoder handles
fragmented text, reasoning summaries, function arguments, usage, completion,
cancellation, and failures.

Models are discovered from the authenticated Codex catalog using the account's
`chatgpt-account-id`. Ajent refreshes imported tokens before expiry. Because
this backend follows Codex client behavior rather than the public OpenAI API
contract, treat it as experimental. See [AUTH.md](AUTH.md) for explicit Codex
CLI import and keyring detection.

Codex reasoning effort is provider-aware rather than inferred from Anthropic
model names. Standard models expose backend default, low, medium, and high;
Codex mini exposes backend default, medium, and high; known compatible models
may additionally expose xhigh. Unsupported extended values clamp to high.

## Selecting a provider

At startup:

```powershell
.\ajent.cmd --provider anthropic --model MODEL_ID --workspace .
.\ajent.cmd --provider codex --model MODEL_ID --workspace .
.\ajent.cmd --provider openai --model MODEL_ID --workspace .
.\ajent.cmd --provider ollama --model MODEL_ID --workspace .
.\ajent.cmd --provider llama.cpp --model MODEL_ID --workspace .
```

Inside the UI, Ctrl-P opens providers and Ctrl-/ opens models. Provider switch
may request a key or custom URL, then fetch a model catalog without replacing
the active provider. The provider and model are committed together after model
confirmation. Escape or catalog failure keeps the previous pair. On the
command line, first use of an explicit provider requires `--model`; later
launches may use that provider's saved model. Model/provider choices and
last-used per-provider models are persisted.

## Authentication

Hosted OpenAI-compatible presets use provider-specific environment variables,
saved encrypted keys, or `--key`. Anthropic supports its own OAuth. Codex uses
an explicitly imported ChatGPT subscription session. Local presets are
explicitly keyless. See [AUTH.md](AUTH.md) for precedence and storage.

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
blocks. OpenAI builders create Chat Completions role messages and tool
call/result pairs. Codex builders create Responses input items and stateless
function-call continuations. Ollama builders support both native history and
JSON-protocol recovery.

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

HTTP requests run asynchronously. Ordinary routes remain on the JDK client;
provider dial/SOCKS overrides use a narrowly scoped OkHttp HTTP/2 adapter because
the JDK client downgrades HTTPS-over-CONNECT to HTTP/1.1. Cancellation aborts the
underlying call while waiting for headers or a body read. A byte-idle watchdog
closes stalled streams; the reducer separately watches semantic progress.
Terminal events are emitted at most once even when cancellation, EOF, timeout,
and parser failure race. User cancellation is not treated as provider
unavailability, and late events are attributed to the provider that owned the
turn rather than whichever provider is currently selected.

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

The environment-aware HTTP clients apply proxy/air-gap and native dial/host
overrides consistently to model listing, provider requests, OAuth, MCP HTTP,
web tools, and optional Ollama RAG calls. Hosted provider overrides retain
HTTP/2 plus the logical URI, Host header, TLS SNI, and certificate-verification
target while changing only the TCP destination. A custom host is normalized
before paths are appended. Debug logs redact secrets.

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
