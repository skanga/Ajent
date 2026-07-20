# ACP and MCP protocols

Ajent supports Agent Client Protocol (ACP) for editor-hosted coding sessions,
MCP clients for external tools, and a standalone MCP server that publishes
Ajent's tool catalog. Protocol stdout is reserved for newline-delimited JSON-RPC.

## ACP server

Run:

```powershell
.\ajent.cmd acp --workspace .
```

The server reads JSON-RPC requests from stdin and writes responses/notifications
to stdout. Diagnostics go to stderr. The initialize response advertises the
pinned ACP v1 capability lattice.

### Lifecycle

The implementation supports initialization, authentication/logout, session
new/list/load/resume/close/delete, mode updates, model configuration, prompts,
cancellation, and durable thread replay. Sessions are isolated and one active
prompt owns a session at a time.

`authenticate` requires the ACP v1 string `methodId`. It validates the same
wire shape as AgenTTY, succeeds when startup resolved installed credentials,
and returns `AuthRequired` after `logout` clears them.

Clients may advertise `fs.readTextFile`, `fs.writeTextFile`, and `terminal` in
their initialize capabilities. AgenTTY accepts but does not retain or invoke
those capabilities: its built-in filesystem and terminal tools execute in the
agent runtime, and its only outbound request is `session/request_permission`.
Ajent intentionally matches that application behavior. The `fs/*` and
`terminal/*` methods provided by the generic `acp-cpp` client library are not
call sites in AgenTTY itself.

Each session composes a real `AgentLoop` with selected provider, profile-derived
tool catalog, persistence, checkpoints, skills/memory/RAG, MCP external tools,
and permission bridge. Loading/resuming reconstructs from the durable thread;
deletion is idempotent.

### Prompt projection

ACP text and resource blocks become one Ajent user turn with attachments where
appropriate. Provider text/thinking/usage becomes ordered session updates.
Tool calls emit pending, metadata, in-progress, and final cards. Permissioned
tools issue outbound `session/request_permission` with exact choices, then map
the client decision back into the reducer.

Prompt completion maps normal end, maximum tokens, refusal, cancellation, and
errors to the corresponding ACP stop reason. Cancellation is scoped to the
session and does not stop another concurrent session.

### Protocol safety

Parse error, invalid request, invalid params, method not found, authentication,
and internal failure have distinct JSON-RPC mappings. Notifications suppress
responses. EOF cancels active prompts and drains persistence. Corrupt optional
sidecar/index data degrades best-effort without contaminating stdout.

## MCP client

Ajent loads MCP configuration through `McpConfigLoader` and supports:

- stdio child-process transports;
- Streamable HTTP transports;
- session initialization and tool discovery;
- pooled connections and bounded calls;
- conversion to Ajent external tool specifications/runtime.

Stdio transport owns process lifecycle and protocol-clean streams. HTTP
transport uses the shared environment-aware JDK client. Sessions validate
JSON-RPC ids/results/errors and close outstanding calls on transport failure.

External tool schemas and annotations are retained, then Ajent applies its own
effect/permission/output safety boundary. Configuration/server failure is
reported without disabling the native catalog.

## Standalone MCP server

Run:

```powershell
.\ajent.cmd mcp-serve --workspace .
```

This publishes the complete validated native tool set over stdio JSON-RPC.
The same production `ToolRuntimeFactory` provides workspace filesystem,
process/search/map/Git, web, memory, skills, RAG, todos, and subagent services.
Provider-backed subagents snapshot the configured live provider per call.

The server validates workspace, provider/auth/profile/sandbox configuration
before entering the protocol loop. stdout contains protocol bytes only.

## Debugging protocol mode

Use a harness that keeps request/response lines intact. Do not pipe a banner,
progress UI, shell prompt, or log formatter into stdout. Capture stderr
separately.

Diagnose in order:

1. one `initialize` request;
2. authentication if required;
3. tool or session listing;
4. a read-only call/prompt;
5. permission round trip;
6. cancellation;
7. durable load/resume.

Raw ACP tracing is optional and best-effort. It may include prompts and source
content; authorization secrets are redacted. MCP transport errors include the
server/transport boundary but should not echo secret environment values.

## Compatibility tests

Protocol verification includes codecs, schema round trips, loopback duplex
streams, asynchronous prompts, real tool execution, permissions, usage/tool
updates, cancellation/concurrency, persistence, notification suppression,
error mappings, and top-level Java 25 launch smokes. The parity module also
retains source mappings and captured method assertions from AgenTTY.

## Integrating an editor or client

Launch the shaded JAR or `ajent.cmd acp` with a stable workspace and no wrapper
that writes stdout. Send `initialize`, authenticate/configure, create or load a
session, then issue prompts. Consume notifications continuously while awaiting
the final response; permission requests are bidirectional and can occur during
a prompt.

For MCP, configure either command/arguments/environment for stdio or the
Streamable HTTP URL/headers. Treat external MCP servers as trusted with the
effects you allow: arguments can include source paths and results can enter the
model context.
