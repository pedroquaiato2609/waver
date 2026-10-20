# STD-011 - Git and Pull Request Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro schemas, Temporal, Vault, Keycloak, Istio and the Nx frontend workspace in ownership, review and required checks).
Version: 1.3
Date: 2026-10-19
Related: ADR-0015 (monorepo), ADR-0021..0027, STD-012 (CI/CD), STD-015 (documentation), RFC-0014 (deployment)

## Purpose
Define how code, contracts, deployment values and documentation change in the WAVER monorepo so that `main` is always releasable, every change is reviewed by its owners, and history explains why each change was made. The Git hosting product is open; rules are expressed as repository settings any provider can enforce.

## Scope
Everything in the `waver/` monorepo: `services/`, `frontend/`, `contracts/`, `platform/`, `deploy/`, `tooling/`, `docs/`.

## Rules

### Branching (trunk-based)
1. `main` is the only long-lived branch. It MUST always build, pass required checks and be deployable to `dev`.
2. Direct pushes to `main` are blocked for everyone, including administrators. Force-push and history rewrite on `main` are blocked.
3. Work happens on short-lived branches merged within 2 working days; branches older than 5 days SHOULD be split or rebased.
4. Branch name: `<type>/<ticket>-<short-slug>`, lowercase, kebab-case. `<type>` is a Conventional Commits type. Examples: `feat/wav-412-contract-amendments`, `fix/wav-977-outbox-header-mapping`, `docs/wav-1003-rfc-0014`.
5. Unfinished work is merged behind platform flags (RFC-0014 §6), not kept on long-running branches.
6. Hotfix branches `hotfix/<service>-<version>` are created from the production tag only when `main` cannot be released; the fix MUST land on `main` first or in the same day.

### Commits (Conventional Commits 1.0)
7. Format: `<type>(<scope>)!: <imperative summary>`; summary ≤ 72 characters, no trailing period.
8. Types: `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`, `revert`.
9. Scope = service or area: `contract-service`, `frontend-workspace`, `frontend-shared` (Nx `scope:shared` libraries), `contracts`, `platform`, `deploy`, `deploy-platform` (shared infrastructure under `deploy/platform/<component>/`), `docs`.
10. `!` or a `BREAKING CHANGE:` footer marks a breaking change; inside a contract major version this is only allowed under the AR-015 exception process.
11. Footers reference the ticket (`Refs: WAV-412`) and, when relevant, the ADR/RFC (`Refs: RFC-0014`).
12. Secrets, personal data or production data MUST NOT appear in commits, branch names or PR text (STD-014).

### Pull requests
13. Merge strategy: squash merge only; the PR title becomes the commit message and MUST follow rule 7.
14. Size: ≤ 400 changed lines, excluding generated code, lockfiles and snapshots. PRs over 800 lines need a justification in the description and are split when possible.
15. One PR changes one service, plus its contracts (OpenAPI, Avro schemas), deployment values, Vault policy fragment and Debezium connector configuration. Changes to several services are separate PRs, each independently deployable (AR-005, RFC-0014 §8). A shared infrastructure component (`deploy/platform/<component>/`) is changed in its own PR, never together with a service. Mechanical platform-wide changes (e.g., BOM bump) are the exception and are labelled `cross-cutting`.
16. The PR description uses the template: what/why, linked ticket, ADR/RFC references, risk, test evidence, rollout and rollback notes, screenshots for UI, migration phase (expand/backfill/switch/contract) when applicable, workflow versioning approach (patch or new build ID) when a Temporal workflow changes, and the new dependency edges when `dependencies` in chart values change (mesh and NetworkPolicy impact).
17. Draft PRs are allowed and do not request reviews.

### Reviews and CODEOWNERS
18. Every directory has owners in the repository `CODEOWNERS` file (location depends on the Git hosting provider; ADR-0015). Unowned paths fail CI.
19. Required approvals:

| Path | Approvals |
|---|---|
| `services/<service>/`, `frontend/apps/<app>/`, `frontend/libs/<app-scope>/**` | 1 code owner |
| `frontend/libs/shared/**` (Nx `scope:shared`, including `ui-*` wrappers over Angular Material) | 1 frontend platform owner + 1 code owner of an app |
| `contracts/**` (OpenAPI, `contracts/events/**/*.avsc`) | 1 owner of the producing service + 1 WAVER Architecture |
| `platform/**`, `deploy/helm/**` (library chart) | 2, including 1 platform owner |
| `deploy/platform/**` (Keycloak, Vault, Redis, Temporal, Apicurio, Kafka Connect, Qdrant, Istio) | 2, including 1 platform owner |
| `deploy/platform/vault/**`, `deploy/platform/istio/**` (Vault policies, egress allow-lists, mesh-wide policies), `deploy/platform/keycloak/**` (realm, clients, mappers) | +1 Security reviewer |
| `deploy/environments/production/**` | Pipeline-generated promotion PR: 1 service owner who is not the change author (RFC-0014) |
| identity-access-service (including the Keycloak claims-mapper SPI), connector-service, `waver-platform-starter` security code | +1 Security reviewer |
| `docs/03_ENGINEERING/ADR/**`, `AR/**`, `04_ARCHITECTURE/**` | 1 WAVER Architecture |

20. Authors MUST NOT approve their own PRs; approvals are dismissed when new commits are pushed.
21. Reviewers respond within 1 working day. Review comments that block merge are marked as blocking; nits are optional.
22. Review checklist: AR compliance (AR_INDEX), tests at the right level (STD-010), tenant context (AR-008, including Temporal activities and Qdrant filters), idempotency (AR-006, including Temporal activities), workflow determinism and versioning (ADR-0023), logging without secrets/PII (STD-006), contract compatibility (AR-015, Avro defaults for new fields), least-privilege Vault policy and dependency edges (ADR-0022, ADR-0026), runbook/dashboards for new alerts (STD-015).

### Required checks before merge
23. All required checks of the PR pipeline (STD-012) pass: build, unit tests, ArchUnit and Nx module boundaries, contract compatibility (OpenAPI diff, Avro compatibility against Apicurio Registry), Temporal workflow replay tests, integration tests, SAST, dependency and secret scans, chart/policy validation (including generated Istio policies and Vault policy lint), doc lint. Checks run only for affected paths, but the required-check set is defined per path so skipped checks are reported as "not applicable", never as missing.
24. Branch must be up to date with `main` (or pass a merge queue) before merge.
25. All review conversations resolved.

### Tags and versions
26. Each service and frontend app is versioned independently with SemVer derived from Conventional Commits since its last tag: tag `<service>/v<major>.<minor>.<patch>` (e.g., `contract-service/v1.14.0`), `frontend-workspace/v2.3.0`.
27. Tags are created by the pipeline only; humans do not push tags.

### Commit signing
28. Commits on `main` SHOULD be signed; the pipeline identity MUST sign the promotion commits it creates.

## Examples
```text
feat(contract-service): add amendment effective dates
fix(agent-runtime)!: reject drafts in deployment resolution
docs(docs): expand RFC-0014 rollback procedures

Refs: WAV-1003, RFC-0014
```

```text
# CODEOWNERS (handles are placeholders)
/services/contract-service/           @waver/team-commercial
/contracts/openapi/contract-service/  @waver/team-commercial @waver/architecture
/contracts/events/contract/           @waver/team-commercial @waver/architecture
/frontend/libs/shared/                @waver/frontend-platform
/platform/                            @waver/platform
/deploy/platform/                     @waver/platform
/deploy/platform/vault/               @waver/platform @waver/security
/deploy/platform/istio/               @waver/platform @waver/security
/deploy/environments/production/      @waver/platform
/docs/03_ENGINEERING/ADR/             @waver/architecture
```

## Verification
- Repository settings: protected `main`, required checks, required code-owner reviews, squash-only, dismiss stale approvals.
- CI lint of PR title and branch name; CI check for unowned paths in CODEOWNERS.
- PR size bot comment with line count (excluding generated paths).

## Exceptions
Requested in the PR description with label `std-exception` and approved by WAVER Architecture. Emergency fixes may merge with one approval from any code owner during an incident; a follow-up review is mandatory within 1 working day.
