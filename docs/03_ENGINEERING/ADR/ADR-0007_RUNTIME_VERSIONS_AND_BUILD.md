# ADR-0007 - Runtime Versions, Build Tooling and Workspace Layout
Status: PROPOSED — frontend parts superseded by ADR-0027 (Nx, NgRx, Angular Material) on 2026-10-19
Date: 2026-10-19

## Context
ADR-0001 fixed the stack (Angular/TypeScript, Java/Spring Boot, PostgreSQL, Kafka, Docker, Kubernetes) but not versions, build tools or how frontends and services are organized. Without this, every team picks its own, and AR-005 (independent lifecycle) turns into "independent everything".

## Decision
- **Java**: current LTS (Java 25). Upgrades follow LTS releases only.
- **Spring Boot**: latest supported major line (4.x), with Spring Framework, Spring Security, Spring Data JPA, Spring Kafka and Spring Cloud Gateway from the same release train.
- **Build**: Maven, one multi-module project per service (`<service>-domain`, `<service>-application`, `<service>-adapters`, `<service>-bootstrap`) so the Clean/Hexagonal dependency rule (AR-007) is enforced by the module graph, not only by convention.
- **Frontend**: one Angular workspace (Angular CLI, no extra monorepo tool) with three applications — `control`, `partner`, `workspace` (Builder lives inside `workspace`) — and shared libraries `ui` (design system on Angular CDK), `auth`, `api-clients` (generated from OpenAPI), `i18n`, `flow-canvas` (Flow Builder canvas) and feature libraries per app (e.g., `builder-*`) as defined in RFC-0021.
- **Containers**: one image per service, Docker multi-stage build on a minimal JRE base image.
- **Repository**: single monorepo (ADR-0015).

## Alternatives
- Gradle: equally valid; Maven chosen for convention-over-configuration and simpler module enforcement for a growing team.
- One Angular workspace per app: rejected; design system and auth would be duplicated three times.

## Consequences
- Version bumps are platform-wide, coordinated through a BOM (`waver-platform-bom`).
- Builder UX ships with the Workspace release cadence.

## Compliance / Verification
- CI fails if a service overrides BOM-managed versions without an approved exception.
- ArchUnit tests in every service verify module/package dependencies.

## Supersedes / Superseded by
Complements ADR-0001.
