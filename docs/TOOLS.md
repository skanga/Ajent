# Tools, permissions, and subagents

Ajent exposes the ordered Ajent coding-tool catalog through the interactive
agent, ACP, and standalone MCP server. Tool definitions, runtime dispatch,
permissions, scheduling, output budgets, and isolated subagents share one
composition path.

## Catalog contract

Each native tool specification includes:

- stable name and description;
- JSON input schema and annotations;
- effect set (read, write, execute, network, etc.);
- timeout and output-budget policy;
- runtime handler or external-tool adapter.

`NativeToolWireCatalog` preserves exact provider-facing order and schemas.
`ToolCatalog`/`ToolDispatcher` use the stricter runtime view. Startup validates
unique names and complete definitions. Unknown tools and invalid arguments
fail closed.

## Tool families

### Files and edits

Workspace-contained read, write, edit, directory, and patch/diff operations.
Edit matching supports exact and fuzzy recovery plus parameter-tag repair.
File snapshots track mtime, size, and fingerprint so an external modification
between observation and mutation is reported rather than overwritten.

An implicit read of a file larger than 32 KiB returns a structural outline with
line anchors. If no recognizable code structure exists, it returns a UTF-8-safe
leading 1 KiB slice and the total line count. A caller must then provide
`start_line` plus inclusive `end_line`, or `offset` plus `limit`, to retrieve
real content. This matches the native context-saving behavior and prevents an
unchanged unbounded retry from consuming another turn.

### Search and repository map

Literal/regex grep, glob/path discovery, symbol context, and PageRank-style
repository mapping. Search filters binary/NUL input, merges context ranges,
adds enclosing-symbol breadcrumbs, and uses bounded pagination/inter-file
guards. The standalone MCP server publishes `repo_map` from the checked-in
Ajent 23-tool registry; the older 0.2.8 Windows binary omitted that entry.

### Processes and diagnostics

Bounded merged process execution with a byte-activity idle watchdog,
platform-specific validation/sandbox wrapping, cancellation, timeout, and
large-output spill files. Diagnostic/test summaries retain useful error lines
inside the conversation budget.

### Git

Status, diff, log, and related repository operations plus private checkpoint
support. Commands operate in the workspace and return typed output/change
metadata.

### Web

Bounded HTTPS fetch and search adapters with redirect limits and typed network
errors. Network effects remain subject to profile permission.

### Memory, skills, and knowledge

Durable JSONL memory, six-root Agent Skills discovery/activation, repository
documents, BM25/dense retrieval, optional Ollama embeddings/query expansion/
reranking, MMR, compression, provenance, and confidence. Skills expose bounded
resources lazily; hidden/model-invisible rules follow the reference.

### Host tools

Todos/plans, document search, memory lifecycle, skills activation, and the
`task` subagent tool. Host services are injected so protocol and interactive
sessions use the same implementations.

Standalone MCP matches Ajent's host boundary: `task` is published but
reports that subagents are unavailable. Interactive and ACP sessions install
the provider-backed runner described below.

## Workspace sandbox

Every path is resolved against the canonical workspace. Traversal, absolute
escape, and symlink/reparse escape are rejected. A read allowlist may include
explicit external skill resources, but that does not imply write permission.

Workspace checks happen inside tool handlers even after model schema validation
and user approval. Permission is not a substitute for path containment.

## Process sandbox

`ProcessSandbox` selects the platform strategy and wraps accepted commands.
Configuration can request native/default/off behavior, but validators still
apply command and workspace rules. Cancellation stops the process tree as far
as the operating system permits.

The command validator (`BashValidator`) is a usability guardrail, not a
security boundary: it blocks commands that would hang the session (interactive
editors, bare REPLs, an editor-opening `git commit`) and refuses a few obvious
accidental footguns by literal-substring match, which is trivially bypassed by
obfuscation. Bash containment is enforced by the permission prompt (the `bash`
tool carries the `EXEC` effect), the process sandbox, and the workspace path
checks — not by the validator.

Subprocess capture is bounded independently from provider context. Large shell
output can spill to a sandbox-readable file while returning a head/error/tail
envelope and explicit location.

## Permission profiles

`PermissionPolicy` evaluates effect sets against:

- `ask`: consequential effects request confirmation;
- `write`: the native write-oriented allowance set proceeds without each
  prompt, while higher-risk effects remain governed;
- `minimal`: restricted/read-oriented exposure.

The permission modal allows once, always-for-session effect grants, or reject.
Session grants are immutable reducer state and do not silently persist across
sessions. The dispatcher enforces policy again for defense in depth.

## Scheduling

`ToolScheduler` uses effects and canonical paths. Independent reads can run in
parallel. Writes to unrelated paths may run according to native rules; calls
with conflicting/unknown effects serialize. The `task` tool has its own
scheduling override because it can execute a multi-turn isolated workload.

Results remain associated with original tool-call ids regardless of completion
order. Cancellation and late results are checked against the active turn.

## Output budgets

Each tool has a UTF-8-safe conversation budget. Ajent applies it twice: the
native provider layer first performs a head-only byte cap, then dynamic dispatch
applies the catalog's head, tail, or structured head/tail policy. Ajent preserves
both stages and their distinct elision markers. Structured file changes remain
independent from display truncation. A zero budget follows the native bypass
meaning.

Raw implementations also bound memory/disk/network capture. These two layers
solve different problems: adapter safety and model-context safety.

## Partial tool JSON

Provider tool arguments can arrive incrementally. Ajent retains bounded partial
JSON, repairs only the allowed incomplete structures, and checks required
fields before execution. The `todo` tool additionally projects useful partial
rows at native time/growth thresholds so the plan can update while arguments
stream.

## Doom-loop protection

Equivalent repeated calls/failures are tracked in the reducer. The native
repeat/step thresholds terminate an unproductive loop and produce a visible
error. Salvaged calls are deduplicated by stable identity/content so parser
recovery cannot execute the same action twice.

## Subagents

The `task` tool runs an isolated headless `AgentLoop` using a snapshot of the
currently selected provider, model, auth, and host services. Roles have exact
prompts and allowlists:

- explorer and reviewer are read-only;
- tester can run the testing-oriented set;
- coder can mutate within its scope;
- general receives the native broad set.

Subagents do not receive `task`, preventing recursive delegation. They have
turn/depth/output bounds, clean retry delays, a repeated-failure breaker, and
an activity feed. Parent cancellation trips the child. The parent receives a
condensed final report rather than the child's entire transcript.

## External MCP tools

Configured MCP servers are initialized and their tools adapted into the live
catalog. External calls flow through pooling, timeout, cancellation, output
budget, and permission boundaries. A failed external server does not remove
native tools. Name collisions fail rather than silently shadowing a native
tool.

## Testing a tool change

At minimum cover:

1. catalog schema/effects/annotation shape;
2. argument validation and unknown fields;
3. real temporary-workspace behavior;
4. containment, symlink, permission, timeout, and cancellation errors;
5. output budget and UTF-8 boundary;
6. dispatcher integration and provider wire exposure;
7. ACP/MCP exposure when applicable.

Integration tests cover the full standalone catalog, validation, workspace
refusal and recovery guidance, timeout, synchronous cancellation, large
implicit reads, structured changes, and UTF-8-safe two-stage truncation.

Run `mvn -q verify` from the reactor root.
