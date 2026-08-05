# Changelog

All notable Ajent changes are documented here. Versions follow Semantic
Versioning while pre-1.0 releases may still change unsupported extension APIs.

## [0.2.8] - 2026-07-19

### Added

- Standalone Java 25 Maven application with immutable domain state,
  virtual-thread effect execution, and a terminal-first interactive UI.
- Anthropic OAuth and API-key authentication, OpenAI-compatible providers,
  ChatGPT/Codex subscription import, native Ollama transport, and local model
  support.
- Sandboxed coding tools, permission profiles, skills, memory, repository RAG,
  subagents, plans, attachments, diff review, code-block execution, and Git
  checkpoints with recoverable rewind.
- Saved and queued conversations, streaming Markdown, responsive terminal
  rendering, model/provider pickers, and interactive authentication.
- ACP and MCP protocol servers, air-gap mode, Windows and POSIX launchers,
  cross-platform CI, reproducible archives, SBOM generation, checksums, and
  release automation.

### Quality

- Unit, integration, wire, terminal, fuzz, and performance coverage across the
  full Maven reactor.
- Enforced dependency convergence, JaCoCo coverage, Spotless formatting,
  SpotBugs analysis, and an opt-in OWASP dependency scan.
