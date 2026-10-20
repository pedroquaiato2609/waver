# Core Technology Stack - v1.3

Revision 1.3 (2026-10-19): the product owner adopted Redis, Keycloak, HashiCorp Vault, Temporal, a schema registry with Avro and Debezium, a dedicated vector database (Qdrant), a service mesh (Istio) and the frontend tooling Nx, NgRx and Angular Material (ADR-0020 to ADR-0027). The core stack of ADR-0001 is unchanged.

## Core stack (ADR-0001, unchanged)
- Frontend: TypeScript + Angular.
- Backend: Java + Spring Boot.
- Relational database: PostgreSQL (source of truth for all business data).
- Messaging/event streaming: Apache Kafka.
- Containers: Docker. Orchestration: Kubernetes.
- Architecture: distributed microservices, DDD + Clean/Hexagonal inside each service, database per service.
- Sync integration: REST + OpenAPI. Async integration: Kafka + versioned events.

## Platform components (v1.3)
| Concern | Technology | ADR | Replaces (v1.2) |
|---|---|---|---|
| Versions and build | Java 25 LTS, Spring Boot 4.x, Maven multi-module per service | ADR-0007 | — |
| Identity provider | **Keycloak** (OIDC/OAuth 2.1, MFA/WebAuthn, SSO brokering, Organizations, token exchange) | ADR-0021 | Spring Authorization Server (ADR-0008) |
| Edge and BFFs | Spring Cloud Gateway + one Spring Boot BFF per experience | ADR-0009 | — |
| Cache, sessions, rate limiting, hot counters | **Redis** (non-authoritative) | ADR-0020 | PostgreSQL-only (ADR-0013) |
| Secrets and encryption | **HashiCorp Vault** (Kubernetes auth, dynamic DB credentials, Transit, Secrets Operator) | ADR-0022 | K8s Secrets + app envelope encryption (ADR-0014) |
| Durable execution / long-running processes | **Temporal** (Java SDK, PostgreSQL persistence) | ADR-0023 | PostgreSQL state machines (ADR-0018) |
| Event schemas | **Apicurio Registry** + **Avro**, CloudEvents binary mode | ADR-0024 | JSON Schema files in CI (ADR-0012) |
| Outbox relay | **Debezium** Outbox Event Router on **Kafka Connect** | ADR-0024 | Polling relay (ADR-0012) |
| Vector search | **Qdrant** (PostgreSQL stays source of truth) | ADR-0025 | pgvector (ADR-0011) |
| Service-to-service security and traffic | **Istio ambient** (mTLS, AuthorizationPolicy, egress gateway) | ADR-0026 | none |
| Tenant data isolation | Logical isolation + PostgreSQL Row-Level Security (+ Redis ACL prefixes, Qdrant payload filters) | ADR-0010 | — |
| Repository | Monorepo with enforced boundaries | ADR-0015 | — |
| Connectors | Declarative manifests first, Java SDK for complex cases | ADR-0016 | — |
| LLM provider abstraction | `model-gateway-service` | ADR-0017 | — |
| Frontend workspace and state | **Nx**, **NgRx SignalStore**, **Angular Material (M3)** themed by WAVER tokens behind `wv-*` wrappers | ADR-0027 | Angular CLI only, Signals only, CDK-only `ui` (ADR-0007) |

## Libraries allowed
Spring Security (resource server), Spring Data JPA, Spring Data Redis, Spring Kafka, Spring Cloud Gateway, Spring Session Data Redis, Spring Cloud Vault, Spring AI (model-gateway adapters only), Temporal Java SDK, Apicurio/Confluent-compatible Avro serdes, Qdrant Java client, Keycloak admin client and SPI (identity-access mapper only), Flyway, Micrometer (metrics + tracing bridge in OpenTelemetry format), Resilience4j, Caffeine (L1 cache), Redisson (locks, optional), MapStruct, Testcontainers, ArchUnit, JUnit 5, AssertJ, WireMock; Nx, Angular CLI builders, Angular Material, Angular CDK, NgRx (`@ngrx/signals`, `@ngrx/store`, `@ngrx/effects`, `@ngrx/entity`), RxJS, Playwright, OpenAPI Generator. Adding anything else follows STD-013.

## Build and quality tooling (development time only)
Java: Mockito, Spotless with google-java-format, JaCoCo, Maven Enforcer, OpenAPI Generator, OpenAPI lint/diff tool, Avro Maven plugin, Temporal test framework (replay tests), Gatling.
Frontend: Nx lint (enforce-module-boundaries), angular-eslint, Prettier, Playwright + axe-core, visual regression for `ui-*` libraries.

## Licenses to confirm (OPEN_DECISIONS)
| Component | License | Note |
|---|---|---|
| Redis 8 | RSALv2 / SSPLv1 / AGPLv3 | Internal backing service; Valkey (BSD) is the drop-in fallback — D-37 |
| HashiCorp Vault | Business Source License 1.1 | Internal secret manager is allowed; OpenBao (MPL 2.0) is the fallback — D-38 |
| Keycloak, Apicurio, Debezium, Qdrant, Istio | Apache 2.0 | — |
| Temporal, Nx, NgRx, Angular Material | MIT | — |

## Still open (require an ADR when needed)
- Cloud provider and its managed services: managed Kubernetes, PostgreSQL, Kafka, Redis, KMS for Vault auto-unseal. Each component above can run managed or in-cluster; the choice follows the cloud decision (D-24).
- Observability backend and CI provider (D-25).
- Payment gateway and fiscal document provider (D-03, D-08).
- Malware scanning of uploads, Markdown rendering library (STD-013).
- Object storage for documents (D-30).
