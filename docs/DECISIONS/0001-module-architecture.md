# ADR 0001: Maven modules and adapter boundaries

Status: accepted

## Context

Ajent keeps provider, process, filesystem, protocol, and terminal I/O
independently testable while presenting one cohesive terminal application.

## Decision

Use one JDK 25 Maven reactor with dependency direction
`domain <- core <- adapters <- cli`. Provider, tools, protocol, and terminal
adapters communicate through core/domain contracts rather than depending on
one another.

Behavior is introduced through focused tests first. Stable wire and terminal
fixtures are retained when they define a public contract.

Use records, sealed interfaces, and immutable collections for domain values. Mutable owners are confined to resource boundaries. Add external dependencies only at a tested boundary where the JDK is insufficient.

## Consequences

The module graph prevents terminal or provider details from leaking into
reducer logic. Integration tests can test the assembled application without
creating production coupling. Initial scaffolding is larger than a
single-module application, but protocol and provider work can proceed behind
stable ports and be rolled back independently.
