# Changelog

All notable Ajent changes are documented here. Versions follow Semantic
Versioning while the pre-1.0 port may still change unsupported extension APIs.

## [0.2.8] - 2026-07-19

### Added

- Modern Java 25 Maven reactor covering AgenTTY's domain, persistence,
  providers, tool runtime, agent loop, ACP/MCP protocols, terminal UI, parity
  fixtures, and performance probes.
- Anthropic OAuth/API-key authentication, OpenAI Chat Completions-compatible
  providers, and native Ollama transport.
- Interactive saved threads, queued prompts, attachments, plans, permissions,
  checkpoints, diff review, code-block execution, skills, memory, and RAG.
- Windows and POSIX launchers, cross-platform CI, source provenance, parity
  ledger, and subsystem documentation.
- Reproducible ZIP and TAR.GZ distributions, a CycloneDX 1.6 SBOM, SHA-256
  checksums, and tag-validated GitHub Release automation.
- Native-executable MCP client characterization for configured stdio and
  Streamable HTTP servers, including rich tool results, resources, prompts,
  progress, list refresh, timeouts, sessions, headers, and JSON/SSE envelopes.
- Native-executable standalone MCP characterization that executes every one of
  the 22 published tool families against paired deterministic workspaces.
- Native-executable Ollama characterization for `/api/chat` request bodies,
  fragmented NDJSON, persisted images, native and weak-model JSON-protocol tool
  execution/continuation, HTTP errors, and live cancellation.
- Native-executable Anthropic characterization for exact Messages API requests,
  fragmented SSE, tool execution/continuation, HTTP errors, and cancellation.
- Native-executable persistence characterization for settings save ordering,
  complete ACP thread/session-index JSON, atomic cleanup, and shutdown flush.
- Native-executable tool edge characterization for validation, workspace
  recovery guidance, timeout, cancellation, large implicit reads, UTF-8-safe
  truncation, and structured changes.

### Changed

- Ported native state machines to immutable records and sealed interfaces with
  virtual-thread effect execution.
- Matched AgenTTY's Windows process CRLF behavior and terminal blank line in
  CLI usage output; added a pinned executable characterization profile.
- Matched hosted OpenAI-compatible HTTP/2 routing, preset paths and headers,
  versioned user agents, dial/SOCKS overrides, and exact TLS cancellation.
- Matched AgenTTY's MCP empty-parameter envelopes, protocol-version headers,
  timeout wording, structured JSON formatting, and absence of unsolicited
  cancellation notifications; progress delivery no longer blocks response
  processing.
- Pinned AgenTTY's explicit MCP cancellation limitation: a cancellation
  notification queued during a synchronous configured tool call neither
  preempts the call nor propagates downstream, and the normal timeout wins.
- Matched standalone MCP edit diffs, terminal-newline grep context, typed local
  error wrapping, host-shell error wrapping, TLS-only URL validation, Git
  output, application-data paths, and unavailable `task` behavior.
- Matched Ollama's synthetic missing tool-call ids and model-specific HTTP 404
  recovery hint.
- Matched Ollama's UTF-8-byte tool-description truncation and recursively
  canonical JSON-protocol continuation history.
- Matched Anthropic's request prompt terminator and ACP projection of the latest
  provider usage frame; documented three source-defined fields absent from the
  downloaded 0.2.8 binary.
- Matched native pre-validation model persistence and ACP title derivation from
  the newline-terminated stored user message.
- Matched native large-file read outlining/leading slices, inclusive
  `end_line`, workspace recovery guidance, and the provider-plus-dispatcher
  two-stage output budget.

### Known limitations

- Full native terminal visual differential capture remains under parity work.
- OpenAI Responses API and ChatGPT/Codex OAuth are not implemented; AgenTTY's
  OpenAI-compatible transport uses Chat Completions and API keys.
- The pinned AgenTTY 0.2.8 Windows binary has a positive `find_definition`
  false-negative and predates the checked-in multi-source/confidence RAG
  output. Ajent retains the source-correct implementations for both.
