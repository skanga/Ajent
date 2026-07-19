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

### Changed

- Ported native state machines to immutable records and sealed interfaces with
  virtual-thread effect execution.

### Known limitations

- Full native terminal visual differential capture remains under parity work.
- OpenAI Responses API and ChatGPT/Codex OAuth are not implemented; AgenTTY's
  OpenAI-compatible transport uses Chat Completions and API keys.
