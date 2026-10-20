# STD-013 - Dependency Management Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Redis, Keycloak, HashiCorp Vault, Temporal, Apicurio Registry + Avro, Debezium, Qdrant, Istio, Nx, NgRx and Angular Material, with their client libraries and licenses).
Version: 1.3
Date: 2026-10-19
Related: ADR-0001, ADR-0007, ADR-0015, ADR-0020..0027, TECH_STACK, STD-012

## Purpose
Keep WAVER on one coherent, secure and legally safe set of third-party libraries. Versions are managed centrally, new libraries are added deliberately, and vulnerabilities are fixed within defined deadlines.

## Scope
Java (Maven) dependencies of every service and platform module, Angular/npm dependencies of the Nx frontend workspace, container base images, the images and charts of shared infrastructure components (`deploy/platform/<component>/`), and build tooling used in pipelines.

## Rules

### Central version management
1. All Java versions are managed in `platform/java/waver-platform-bom`, which imports the Spring Boot BOM of the approved 4.x line and declares every other allowed library (ADR-0007).
2. Services import the BOM and declare dependencies without versions. Overriding a BOM-managed version in a service fails CI unless an approved exception is referenced (ADR-0007).
3. The Nx frontend workspace has a single `package.json` and a single committed lockfile (Nx integrated repository, ADR-0027); Nx, Angular, Angular CDK, Angular Material and NgRx are upgraded together with `nx migrate`, which applies their migrations. Node.js follows the LTS line supported by the Angular version in use.
4. Lockfiles and the BOM are the only places versions change; version ranges (`^`, `~`, `[1.0,)`, `LATEST`) are forbidden.
5. Container base images, shared component images and their Helm charts are pinned (images by digest, charts by exact version) and updated through the same process.

### Allowed libraries
6. Only the stack in TECH_STACK may be used. Allowed libraries (v1.3):

| Area | Libraries |
|---|---|
| Java runtime | Spring Boot, Spring Framework, Spring Security (OAuth2 resource server and client; tokens are issued by Keycloak, ADR-0021), Spring Data JPA, Spring Data Redis, Spring Session Data Redis (BFFs), Spring Kafka, Spring Cloud Gateway (edge-gateway only, including `RedisRateLimiter`), Spring Cloud Vault (Kubernetes auth, database credentials, Transit), Spring AI (model-gateway adapters only), Temporal Java SDK (services that own workflows), Apache Avro + Apicurio/Confluent-compatible Avro serializers/deserializers (registry-aware, `auto.register.schemas=false`; CloudEvents binary-mode headers are mapped by `waver-platform-starter`), Qdrant Java client (knowledge-service and memory-service only), Keycloak admin client (identity-access-service only), Keycloak SPI (claims-mapper module only), Redisson (short locks, optional), Flyway, Micrometer (+ OpenTelemetry bridge), Resilience4j, Caffeine (L1 cache), MapStruct, PostgreSQL JDBC driver |
| Java test | JUnit 5, AssertJ, Testcontainers (PostgreSQL, Kafka, Redis, Qdrant and generic containers for Apicurio Registry/Vault), Temporal testing (`temporal-testing`), ArchUnit, WireMock |
| Java build | Avro Maven plugin (generation from `contracts/events`), OpenAPI Generator |
| Frontend | Angular, Angular CDK, Angular Material (only inside `ui-*` libraries), NgRx (`@ngrx/signals`, `@ngrx/store`, `@ngrx/effects`, `@ngrx/entity`), RxJS, Angular Signals, i18n library chosen in STD-002, Playwright (E2E), OpenAPI Generator (clients) |
| Frontend tooling | Nx (`nx`, `@nx/angular`, `@nx/eslint`, `@nx/playwright`), Angular CLI builders, angular-eslint, Prettier |
| Domain modules | JDK only (AR-007); allow-list empty in v1 |

7. Libraries pulled in transitively by allowed libraries are acceptable but MUST NOT be used directly unless listed.
8. Libraries that introduce new infrastructure (caches, brokers, datastores, workflow engines, secret managers, service-mesh SDKs, cloud/vendor SDKs) are forbidden without an ADR. The infrastructure adopted by ADR-0020..0027 is listed in the next table; anything else (e.g., another cache, another workflow engine, a vector database other than Qdrant) still needs an ADR.

### Shared infrastructure components (v1.3)
These run as separate, unmodified backing services; WAVER code talks to them only through the client libraries above. Versions are pinned in `deploy/platform/<component>/` (rule 5).

| Component | ADR | License | Fallback if the license is not accepted |
|---|---|---|---|
| Redis 8.x | ADR-0020 | Tri-license RSALv2 / SSPLv1 / AGPLv3 (pending legal confirmation, D-37) | Valkey (BSD-3-Clause), API-compatible |
| HashiCorp Vault + Vault Secrets Operator | ADR-0022 | Business Source License 1.1 (pending legal confirmation, D-38) | OpenBao (MPL-2.0), API-compatible |
| Keycloak | ADR-0021 | Apache-2.0 | — |
| Temporal server and Temporal Web | ADR-0023 | MIT | — |
| Apicurio Registry | ADR-0024 | Apache-2.0 | — |
| Kafka Connect (Apache Kafka) + Debezium PostgreSQL connector | ADR-0024 | Apache-2.0 | — |
| Qdrant | ADR-0025 | Apache-2.0 | — |
| Istio (istiod, ztunnel, waypoints, gateways, istioctl) | ADR-0026 | Apache-2.0 | — |
| Nx (open-source core) | ADR-0027 | MIT | — |
| NgRx | ADR-0027 | MIT | — |
| Angular Material / Angular CDK | ADR-0027 | MIT | — |

8a. Paid or source-available add-ons of these components (e.g., hosted Nx Cloud, Vault Enterprise features, Redis modules under other terms) are not allowed without an ADR.
8b. Shared components follow the vendor's supported release lines; a component more than one minor line behind the latest stable release is reported weekly, and upgrades follow RFC-0014 §12.

### Adding a library
9. Open a PR that adds the library to `waver-platform-bom` (or the frontend `package.json`) and to the table above, with: purpose, alternatives in the allowed list and why they are insufficient, license, maintenance health (release in the last 12 months, more than one maintainer), known vulnerabilities, size impact, owner.
10. Approval: 1 platform owner + 1 WAVER Architecture; Security for crypto, auth, parsing or serialization libraries.
11. If the library implies a new technology choice rather than a utility, an ADR is required instead.

### Update cadence
12. Automated update PRs (tool open) run weekly for patch and minor versions of BOM entries, the frontend lockfile, base images and shared component images/charts.
13. Patch and minor updates are merged within 2 weeks; Spring Boot minor lines and Angular majors (with Nx, Angular Material and NgRx) within 3 months of release; Java follows LTS releases only (ADR-0007). Shared component upgrades are rehearsed in `staging` (RFC-0014 §12).
14. Services adopt a new BOM version within 30 days (security fixes: per SLA below); services more than two BOM releases behind are reported weekly.

### License policy
| Category | Licenses | Rule |
|---|---|---|
| Allowed | Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, EPL-2.0, PostgreSQL, CC0-1.0 | Use freely |
| Review | MPL-2.0, LGPL-2.1/3.0, EPL-1.0, CDDL | Legal/architecture approval before use |
| Forbidden | GPL-2.0/3.0 (without classpath exception), AGPL, SSPL, BUSL, Commons Clause, unlicensed code | Never in libraries linked into WAVER artifacts or shipped to customers |

15. CI license check fails on forbidden licenses and on unknown licenses in Java and npm dependencies and in container images built by WAVER.
15a. The license policy above applies to code linked into, or distributed with, WAVER artifacts. Shared infrastructure components that run unmodified as separate internal backing services and are never offered to customers as a service are evaluated per component in their ADR; the only accepted cases are Redis (RSALv2/SSPLv1/AGPLv3, ADR-0020) and HashiCorp Vault (BUSL-1.1, ADR-0022), both pending legal confirmation (D-37, D-38) and both with an API-compatible permissive fallback (Valkey, OpenBao) that the deployment design must keep usable. Their client libraries (e.g., Spring Data Redis, Lettuce, Redisson, Spring Cloud Vault) must themselves be in the Allowed category. The CI license check allow-lists exactly these two component images by digest.

### Vulnerability SLAs (from detection to fix in `production`)
| Severity | SLA |
|---|---|
| Critical, known exploited or reachable | 72 hours |
| Critical | 7 days |
| High | 30 days |
| Medium | 90 days |
| Low | Next regular update |

16. If no fix exists, a mitigation (configuration, edge-gateway request filter, platform kill switch) is applied within the SLA and recorded with expiry.
17. Builds fail on findings past their SLA; accepted risks need Security approval and an expiry of at most 90 days.

## Examples
```xml
<!-- services/contract-service/pom.xml -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>br.com.waver.platform</groupId>
      <artifactId>waver-platform-bom</artifactId>
      <version>${waver.platform.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<!-- dependencies below declare no <version> -->
```

## Verification
- Maven Enforcer: banned version overrides, `bannedDependencies` (cross-service and non-allowed groups), `dependencyConvergence`, `requireUpperBoundDeps`.
- CI dependency scan, license check and container scan (STD-012 stages 9–10), including shared component images (STD-012 rule 25).
- Nx lint rule: `@angular/material` imported only from `ui-*` libraries (ADR-0027).
- Weekly report of BOM adoption, shared component versions and open vulnerabilities per service and component.

## Exceptions
Recorded in the PR adding the override, with reason, owner and expiry (≤ 90 days), approved by a platform owner (and Security for vulnerability acceptance).
