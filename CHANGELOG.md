# Changelog

All notable Ajent changes are documented here. Versions follow Semantic
Versioning while the pre-1.0 port may still change unsupported extension APIs.

## Unreleased

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

### Changed

- Ported native state machines to immutable records and sealed interfaces with
  virtual-thread effect execution.

### Known limitations

- Full native terminal visual differential capture and release packaging remain
  under parity work.
- OpenAI Responses API and ChatGPT/Codex OAuth are not implemented; AgenTTY's
  OpenAI-compatible transport uses Chat Completions and API keys.
