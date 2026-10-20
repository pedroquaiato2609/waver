# STD-012 - CI/CD Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro compatibility checks against Apicurio Registry and schema registration at release, Temporal workflow replay tests, Nx affected for the frontend, Istio policy generation/validation, Vault policy review, Keycloak realm configuration as code).
Version: 1.3
Date: 2026-10-19
Related: RFC-0014 (deployment), RFC-0015 (Kubernetes), ADR-0007, ADR-0015, ADR-0021, ADR-0022, ADR-0023, ADR-0024, ADR-0026, ADR-0027, STD-010, STD-011, STD-013, STD-014

## Purpose
Define the mandatory pipeline stages, gates and promotion rules for every WAVER service, frontend app, contract, platform module and shared infrastructure component. The CI provider is open (TECH_STACK); this standard states what every pipeline MUST do, so it can be implemented on any provider.

## Scope
All paths of the monorepo (ADR-0015). Pipelines are path-based: a change builds, tests and deploys only the affected units and their dependants (e.g., a change in `contracts/openapi/contract-service/` re-runs compatibility checks and the builds of services that consume that client; a change in `contracts/events/contract/` re-runs the Avro compatibility check and the builds of the producing service and its consumers). In `frontend/`, Nx `affected` computes the affected apps and libraries (ADR-0027). Shared infrastructure under `deploy/platform/<component>/` has its own pipeline (§Shared infrastructure components).

## Pipeline overview
| # | Stage | PR | Merge to `main` | Gate type |
|---|---|---|---|---|
| 1 | Build (Maven per service / Nx `affected` build per app and library) | ✓ | ✓ | Blocking |
| 2 | Unit tests + coverage report (Nx `affected` test for the frontend) | ✓ | ✓ | Blocking |
| 3 | Architecture tests (ArchUnit, Maven Enforcer, Nx `enforce-module-boundaries` with scope tags, no direct `@angular/material` imports outside `ui-*`) | ✓ | ✓ | Blocking |
| 4 | Contract compatibility: OpenAPI diff, Avro compatibility check of `.avsc` files against the Apicurio Registry (`BACKWARD_TRANSITIVE`), connector manifest schema | ✓ | ✓ | Blocking |
| 4a | Temporal workflow replay tests (services that own workflows) | ✓ | ✓ | Blocking |
| 5 | Static checks: migration linter, chart schema + policy tests, Istio policy generation and validation, Vault policy lint, doc lint, CODEOWNERS coverage | ✓ | ✓ | Blocking |
| 6 | Integration tests with Testcontainers (PostgreSQL, Kafka, Apicurio Registry, Redis, Qdrant where used; Temporal test environment), tenancy test kit, previous-version DB compatibility | ✓ | ✓ | Blocking |
| 7 | SAST | ✓ | ✓ | Blocking on High/Critical |
| 8 | Secret scanning (diff on PR, full history nightly) | ✓ | ✓ | Blocking |
| 9 | Dependency scan + license check (STD-013) | ✓ | ✓ | Blocking per SLA |
| 10 | Container image build (multi-stage, minimal JRE base) + container scan | ✓ (no push) | ✓ | Blocking per SLA |
| 11 | SBOM generation (CycloneDX or SPDX) attached to the image | — | ✓ | Blocking |
| 12 | Push by digest + image signing | — | ✓ | Blocking |
| 13 | Deploy to `dev`: schema registration in Apicurio → Vault policy/role apply → migrate Job → Debezium connector config → rollout + smoke tests | — | ✓ | Blocking |
| 14 | Preview environment `preview-pr-<n>` (frontends/BFFs only) + E2E subset | ✓ | — | Blocking for frontend PRs |
| 15 | E2E suite (Playwright for UIs, API journeys) in `dev`; mesh authorization checks for changed dependency edges | — | ✓ | Blocks promotion |
| 16 | Promotion to `staging` by digest (same deploy order as stage 13) + smoke + E2E + per-audience access tests (ADR-0009) | — | auto | Blocks production |
| 17 | Promotion to `production` by digest: approval + signature verification + schema registration + migrate Job + rollout + smoke + post-deploy watch | — | manual approval | — |

## Rules

### General
1. Pipelines are defined as code in the repository; changing a pipeline is a reviewed PR (platform owners, STD-011).
2. Every stage is reproducible: pinned tool versions, pinned base images by digest, no network downloads outside the approved artifact proxies.
3. PR pipeline target duration: p50 ≤ 15 minutes for one service. Slow suites are parallelized, not skipped.
4. Flaky tests are quarantined within 1 working day with a ticket; quarantine never applies to architecture, tenancy, contract, workflow replay or security checks.
5. Build once (RFC-0014 §2): the image built on `main` is the only artifact deployed to every environment. Rebuilding for an environment is forbidden.

### Build and test
6. Java: `mvn -B verify` per service with the platform BOM (STD-013); Java 25, Spring Boot 4.x (ADR-0007); Avro specific records are generated at build time from `contracts/events` (Avro Maven plugin). Frontend: Nx `affected` runs lint, unit tests and production builds only for the projects affected by the change; a change in a `scope:shared` library affects all three apps. Nx remote cache, if used, is self-hosted (ADR-0027).
7. Architecture tests use the shared rules from `waver-platform-test` (AR-001, AR-003, AR-004, AR-006, AR-007, AR-008, AR-009, AR-013, AR-014), including the Temporal rule "workflow classes call infrastructure only through activities" (ADR-0023). A service MUST NOT disable a shared rule; deviations go through the AR exceptions register.
8. Integration tests use Testcontainers with the same major PostgreSQL, Kafka, Apicurio Registry, Redis and Qdrant versions as production (only those the service uses), and the Temporal test framework for workflows; no shared test databases or shared registries.
9. The previous-version compatibility test applies the new migrations, then runs the previous release's integration suite against that schema (RFC-0014).

### Contracts
10. OpenAPI: lint + diff against the latest released contract of the same major; any breaking change fails (AR-015).
11. Events: Avro lint + compatibility check of each changed `contracts/events/<context>/<EventName>.v<N>.avsc` against the subject's registered versions in the Apicurio Registry of `staging` (the most advanced non-production registry), using the registry's `BACKWARD_TRANSITIVE` rule, before merge. New fields require defaults; enums require a default symbol; catalog metadata attributes (`x-waver-topic`, `x-waver-producer`, `x-waver-consumers`, `x-waver-pii`) are required (STD-015). The CloudEvents header contract (required `ce_*` headers, `orgid` for tenant-owned events) is checked by the outbox writer tests (ADR-0024).
12. Contract artifacts (Maven artifacts with generated clients and Avro specific records; npm packages of OpenAPI clients for the frontend) are published only from `main`.
12a. **Schema registration at release.** Schemas are registered only by the pipeline (services run with `auto.register.schemas=false`), in each environment's registry, as the first step of the deployment to that environment (stage 13, 16, 17; RFC-0014 §2). Registration is idempotent; a rejection by the registry fails the deployment before the migrate Job. Registered versions are never deleted in `staging` or `production`.

### Workflows (Temporal)
12b. Every change to a workflow implementation runs replay tests (Temporal test framework `WorkflowReplayer`) against a set of recorded histories per workflow type: histories from `staging` captured at each release plus synthetic histories covering every branch. Histories from `production` are used only after a PII scrubbing export approved by Security.
12c. A non-deterministic change fails the stage unless it is guarded by `Workflow.getVersion` or the PR declares a new worker build ID with the old build kept until its executions drain (RFC-0014 §9). The release records the build ID (`release.temporalBuildId`).

### Mesh, Vault and identity configuration
12d. **Istio policy generation and validation.** The library chart renders the `AuthorizationPolicy`, waypoint enrollment and routes of each service from `dependencies` and `platform` values. CI asserts: every `allowedClients`/`dependsOn` edge exists in RFC-0002 §4 and respects the plane matrix (RFC-0015 §6); every service ships its `AuthorizationPolicy`; retries are disabled on every route; `PeerAuthentication` is STRICT; `istioctl analyze` reports no errors on the rendered resources. Egress `ServiceEntry` changes under `deploy/platform/istio/egress/` additionally require Security approval (STD-011).
12e. **Vault policy review.** Vault policies, roles and engine configuration are code (`deploy/platform/vault/` and the per-service fragment rendered from chart values). CI lints them and checks least privilege: a service policy may only reference its own KV path, its own database roles and the Transit operations allowed by ADR-0022 (encrypt/decrypt only for connector-service; decrypt of leased kinds only for model-gateway-service and channel-service). Any change to a policy granting Transit or database access requires a Security reviewer (STD-011).
12f. **Keycloak configuration.** Realm `waver`, clients, protocol mappers and identity-provider templates are exported as code in `deploy/platform/keycloak/`, validated in CI and applied through the Keycloak Admin API by the pipeline; manual console changes in `staging`/`production` are detected by the drift job and reverted (ADR-0021).

### Security
13. SAST and dependency/container scans fail on findings above the SLA thresholds of STD-013; accepted risks are recorded with expiry, never silently ignored.
14. Secret scanning blocks the PR; a detected real secret is rotated immediately in Vault even if the PR is closed (STD-014).
15. Images are signed in the pipeline; the signature and SBOM are stored next to the image in the registry. Deploy steps verify the signature before applying manifests; admission verification follows RFC-0015 §8.
16. Pipeline credentials are short-lived (workload identity federation preferred); the production deployer identity is separate from the build identity and only usable by the promotion job. The pipeline authenticates to Vault, the Apicurio Registry, the Kafka Connect REST API and the Keycloak Admin API with per-environment identities that only the deployment jobs can use.

### Deployment and promotion
17. Deployment uses Helm with one chart per service (RFC-0015 §10), images referenced by digest, values from `deploy/environments/<env>/<service>.yaml`.
18. Order per environment: signature verification → schema registration in Apicurio (§12a) → Vault policy/role apply for the service → `<service>-migrate` Job → Debezium connector configuration (when changed) → rolling update → smoke tests. Failure in any step stops the pipeline; rollout failure triggers automatic revert to the previous digest (RFC-0014 §7).
19. Promotion writes the digest, chart version and registered schema set to `deploy/environments/<env>/<service>.yaml` through a pipeline commit; `staging` is auto-merged after `dev` gates pass; `production` requires approval of a service owner who is not the change author.
20. Production deploys run during the support window by default (business hours, `America/Sao_Paulo`), not after 15:00 on Fridays or during declared freezes; T0 services and T0 shared components need on-call presence.
21. Post-deploy watch: for 15 minutes after a production rollout the pipeline compares error rate and p95 latency with the previous version and pages the owner on regression. During a data-plane canary, the comparison uses waypoint metrics split by version and the pipeline controls the `HTTPRoute` weights (RFC-0014 §4).
22. The pipeline emits a deployment marker (service or component, env, version, digest, actor) to observability (RFC-0009).
23. A `rollback` job exists for every service: redeploys the previous digest from Git history, skipping build and staging gates but never signature verification. Schemas are not rolled back (RFC-0014 §7).

### Previews
24. Preview environments exist only for PRs that change `frontend/` or a `*-bff`; they contain only the changed frontends/BFFs (per Nx `affected`), use `dev` backends and shared components, and synthetic data, and are deleted on PR close or after 72 h idle.

### Shared infrastructure components
25. Each component under `deploy/platform/<component>/` (Keycloak, Vault, Redis, Temporal, Apicurio Registry, Kafka Connect + Debezium, Qdrant, Istio) has a pipeline with: chart/values validation, image digests pinned and signature-verified, container and license scans (STD-013), deploy to `dev` → `staging` → `production` with approval, and the component's upgrade procedure (RFC-0014 §12), including the pre-upgrade backup or snapshot where RFC-0014 requires it.
26. Component pipelines never run together with a service release, and T0 components (Vault, Keycloak, Istio, Temporal) are upgraded with on-call presence.

## Example (provider-neutral pseudo-pipeline)
```yaml
pipeline: contract-service
triggers: { paths: [services/contract-service/**, contracts/openapi/contract-service/**, contracts/events/contract/**, platform/java/**] }
stages:
  - build:        mvn -B -pl services/contract-service -am verify -DskipITs     # generates Avro specific records
  - arch:         mvn -B -pl services/contract-service test -Dgroups=architecture
  - contracts:    [tooling/contract-check openapi contracts/openapi/contract-service/v1.yaml,
                   tooling/contract-check avro contracts/events/contract --registry staging]
  - replay:       mvn -B -pl services/contract-service test -Dgroups=workflow-replay
  - static:       [tooling/migration-lint services/contract-service, helm lint deploy/helm/contract-service,
                   tooling/mesh-policy-check deploy/helm/contract-service, istioctl analyze (rendered),
                   tooling/vault-policy-lint deploy/helm/contract-service]
  - integration:  mvn -B -pl services/contract-service verify -Pintegration   # Testcontainers
  - security:     [sast, secret-scan, dependency-scan, license-check]
  - image:        [docker build, container-scan, sbom, push-by-digest, sign]
  - deploy-dev:   [verify-signature, register-schemas, vault-apply, helm upgrade --install (migrate Job + connector config + rollout), smoke]
  - promote:      { to: staging, by: digest, auto: true }
  - promote:      { to: production, by: digest, approval: service-owner-not-author }
```

```yaml
pipeline: frontend
triggers: { paths: [frontend/**, contracts/openapi/**] }
stages:
  - affected:     npx nx affected -t lint test build --base=origin/main   # includes enforce-module-boundaries
  - e2e:          npx nx affected -t e2e                                    # Playwright + axe-core
  - image:        [docker build per affected app, container-scan, sbom, push-by-digest, sign]
```

## Verification
- Pipeline definitions are linted for the mandatory stages; a service without them cannot be merged (service-manifest check, AR-005). Services that own Temporal workflows must have stage 4a; producing services must have schema registration in their deploy stages.
- Monthly audit: sample of production digests traced back to a signed image, SBOM, passing gates, registered schema versions and approval.

## Exceptions
Skipping a blocking stage requires approval by WAVER Architecture (and Security for stages 7–12 and for Vault/mesh policy checks) recorded in the PR, with expiry. Incident hotfixes may use the expedited path (reduced E2E set in `staging`), never skipping signature verification, contract checks, workflow replay tests, schema registration or migrations ordering.
