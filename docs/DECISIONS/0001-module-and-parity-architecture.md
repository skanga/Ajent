# ADR 0001: Maven modules and parity-first boundaries

Status: accepted

## Context

Ajent must match a C++ terminal coding agent while using modern Java and keeping provider, process, filesystem, protocol, and terminal I/O independently testable.

## Decision

Use one JDK 25 Maven reactor with dependency direction `domain <- core <- adapters <- cli`. Provider, tools, protocol, and terminal adapters communicate through core/domain contracts rather than depending on one another. The parity module may depend on the complete application but production modules never depend on parity fixtures.

Behavior is introduced through translated reference tests first. Raw oracle fixtures are retained and hashed; comparison occurs before normalization. The only standing CLI normalization is the declared program name change from `agentty` to `ajent`.

Use records, sealed interfaces, and immutable collections for domain values. Mutable owners are confined to resource boundaries. Add external dependencies only at a tested boundary where the JDK is insufficient.

## Consequences

The module graph prevents terminal or provider details from leaking into reducer logic. Differential fixtures can test the assembled application without creating production coupling. Initial scaffolding is larger than a single-module application, but later protocol/provider work can proceed behind stable ports and be rolled back independently.
