# STD-010 - Testing Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Testcontainers for Redis, Keycloak, Vault, Qdrant, Apicurio + Kafka Connect/Debezium; Temporal test framework and replay tests; NgRx store tests; Nx and Angular Material visual regression).
Version: 1.3
Date: 2026-10-19
Applies to: every service, BFF, platform library, Angular app/library, event and API contract, and every agent release
Related: ADR-0007, ADR-0009, ADR-0010, ADR-0020, ADR-0021, ADR-0022, ADR-0023, ADR-0024, ADR-0025, ADR-0026, ADR-0027, AR-005, AR-006, RFC-0016, STD-001..STD-009, STD-012

Keywords MUST, MUST NOT, SHOULD follow RFC 2119. Allowed test libraries: JUnit 5, AssertJ, Testcontainers, WireMock, ArchUnit, the Temporal Java SDK test framework (`temporal-testing`: `TestWorkflowEnvironment`, `TestActivityEnvironment`, `WorkflowReplayer`) and Debezium's Testcontainers support (Java); the Angular builder's unit-test runner via `nx test`, Angular CDK/Material testing harnesses (inside `ui-*` only), NgRx testing utilities (`@ngrx/store/testing`, `@ngrx/signals/testing`), Playwright with axe-core (frontend). Containers use official Testcontainers modules where they exist (PostgreSQL, Kafka, Vault, Qdrant); Redis, Keycloak, Apicurio Registry and Kafka Connect are started from their official images through `GenericContainer` wrappers in `waver-platform-test` (third-party container modules need STD-013). Shared fixtures live in `platform/java/waver-platform-test`.

## 1. Test pyramid per service
| Level | Scope | Infrastructure | Runs | Target share |
|---|---|---|---|---|
| Domain unit | aggregates, value objects, domain services, aggregate status transitions | none (plain JUnit) | every PR | ~60% of tests |
| Application | use cases with fakes for all ports | none | every PR | ~25% |
| Workflow | Temporal workflows with mocked activities; replay of recorded histories | `TestWorkflowEnvironment` (time-skipping), `WorkflowReplayer` | every PR | every workflow type and change |
| Adapter integration | persistence, web, Kafka/Avro/outbox, HTTP clients, Redis, Vault, Qdrant, Keycloak (where used) | Testcontainers PostgreSQL, Kafka + Apicurio + Kafka Connect/Debezium, Redis, Vault, Qdrant, Keycloak; WireMock | every PR | ~10% |
| Component | whole `bootstrap` app, other services stubbed | Testcontainers + WireMock | every PR | a few per use case family |
| Contract | OpenAPI and event schemas | generated stubs/examples | every PR | per contract |
| E2E | Angular app → BFF → services, per audience | preview env / staging | PR (frontend/BFF), nightly | critical journeys only |
| Agent evaluation | AgentVersion behaviour | evaluation-service sandbox | on publish, nightly | per agent / release |

## 2. Domain unit tests
- **T-01** Every invariant, state transition and error code of the domain has a test. Aggregate status machines (e.g., `ProvisioningJob`, `DunningCase` statuses that Temporal workflows drive through use cases) are tested exhaustively from their transition table with parameterized tests: every allowed transition succeeds, every other transition fails with the documented code; re-applying an applied transition is a no-op (activity idempotency, STD-001 J-40).
- **T-02** Domain tests use no Spring context, no containers and a fixed `Clock`. They run in milliseconds.

```java
@ParameterizedTest
@EnumSource(value = ContractStatus.class, names = {"DRAFT", "ACTIVE", "CANCELLED"})
void activation_is_rejected_unless_signed(ContractStatus status) {
  var contract = ContractTestData.aContract().withStatus(status).build();
  assertThatThrownBy(() -> contract.activate(LocalDate.parse("2026-11-01"), FIXED_CLOCK))
      .isInstanceOf(BusinessRuleViolation.class)
      .extracting("code").isEqualTo(ContractErrors.CONTRACT_NOT_SIGNED);
}
```

## 3. Application tests with fakes
- **T-03** Use cases are tested with hand-written fakes of their ports (in-memory repositories, `RecordingEventPublisher`, `FixedClock`, `StubAccessPolicy` that can allow or deny). Fakes for shared platform ports come from `waver-platform-test`.
- **T-04** Each use case has at least: happy path (state + emitted events), authorization denied (no state change, no events), not found, and the main business-rule failure.

```java
@Test
void suspend_requires_permission() {
  var access = StubAccessPolicy.denying(Permission.CUSTOMER_SUSPEND);
  var service = new SuspendCustomerService(customers, events, access, FIXED_CLOCK, tenantContext);
  assertThatThrownBy(() -> service.suspend(cmd)).isInstanceOf(AccessDenied.class);
  assertThat(events.published()).isEmpty();
}
```

## 4. Adapter integration tests (Testcontainers)
- **T-05** Persistence adapters run against PostgreSQL in Testcontainers (official image; `wal_level=logical` for producing services) with the real Flyway migrations (run with `SET ROLE <svc>_owner`), connecting as a login user that is a member of `<svc>_app`, exactly as Vault would create it (STD-005 D-29), so RLS and role inheritance are active. H2 or other in-memory databases are forbidden.
- **T-06** Kafka adapters run against Testcontainers PostgreSQL + Kafka + Apicurio Registry + Kafka Connect with the Debezium PostgreSQL connector and the platform Outbox Event Router configuration (singleton containers; the kit registers the service's schemas and connector): event published once per committed transaction with the expected `ce_*` headers and Avro value; nothing published on rollback; duplicate delivery (connector restart) processed once (inbox); stale `aggregateversion` ignored; unknown `ce_type` skipped; poison record routed to `<topic>.dlq`; registry outage retried, not DLQ'd (STD-004 K-23, STD-008 E-11).
- **T-07** HTTP client adapters are tested with WireMock: success mapping, 4xx translation, timeout, 5xx with retry, circuit breaker opening, `traceparent` and token propagation.
- **T-08** Web adapters are tested with the security filter chain enabled: 401 without token, problem+json shape, `ETag`/`If-Match`, `Idempotency-Key` replay (STD-003).
- **T-09** Containers are started once per test JVM (singleton pattern) and each test uses fresh random tenant IDs instead of truncating tables (and fresh key prefixes/collections per run for Redis and Qdrant), so tests can run in parallel.
- **T-30** **Redis** adapters (ADR-0020) run against a Redis container configured with the service's ACL user from the Helm chart: the kit asserts every key written matches the service prefix and has a TTL (STD-005 D-30, D-31), that ACL-forbidden prefixes and commands fail, that Lua scripts are atomic under concurrent calls, and that each documented fallback works when the container is paused (cache miss, local limiter, PostgreSQL quota fallback, 503 for idempotency/locks).
- **T-31** **Keycloak** (ADR-0021): most services validate tokens minted by the `waver-platform-test` token issuer (fast, deterministic claims). identity-access-service, the BFFs, edge-gateway and the protocol-mapper SPI are tested against a Keycloak container loaded with the realm configuration-as-code: login with PKCE through the BFF, token exchange, client credentials, step-up (ACR) and the mapper's fail-closed behaviour when identity-access is unavailable.
- **T-32** **Vault** (ADR-0022): connector-service's Transit adapter and the lease holders' decrypt paths run against a Vault container with the policies from `deploy/platform/vault/`: encrypt/decrypt round trip with per-org context, a decrypt with another org's context fails, model-gateway can decrypt only `LLM_PROVIDER_KEY` and channel-service only `CHANNEL_PROVIDER_TOKEN`. A platform test covers dynamic PostgreSQL credentials with HikariCP rotation (no failed request during rotation, no connection older than its credential).
- **T-33** **Qdrant** (ADR-0025): vector adapters run against a Qdrant container with the versioned collection definitions; the tenancy kit (T-10) is extended to Qdrant — data indexed for tenant A or domain A is never returned for B under any filter combination, a missing tenant context returns nothing, hits deleted in PostgreSQL are dropped by the final check, and alias swap to a new `_v<N+1>` collection is atomic.

## 4a. Temporal workflow tests (ADR-0023, STD-001 §9)
- **T-28** **Replay tests** are mandatory for every workflow type: `WorkflowReplayer` replays (a) the golden histories produced by the workflow tests of the previous release, committed under `src/test/resources/temporal/histories/<WorkflowType>/`, and (b) histories exported from staging for every workflow type still running there (IDs only, no PII by construction, STD-001 J-36). Any non-determinism error fails the build; a change that intentionally alters the command sequence must add a `Workflow.getVersion` patch (or ship under a new build ID) and keep the old histories replaying.
- **T-29** Workflows are tested with `TestWorkflowEnvironment` (time-skipping) and mocked activities: happy path, every compensation path, timer expiry (e.g., approval not received in 7 days), signal/update ordering, `continueAsNew` boundaries, and that business failures end with the expected failure activity rather than a failed workflow (STD-008 E-20). Activities are tested with `TestActivityEnvironment` against fakes of the application ports: idempotency on retry, `ApplicationFailure` type equals the `ErrorCode`, heartbeat on long activities.

```java
@Test
void provisioning_fails_when_approval_never_arrives() {
  var activities = new RecordingProvisioningActivities();        // hand-written fake (T-03)
  worker.registerActivitiesImplementations(activities);
  env.start();
  var wf = client.newWorkflowStub(ProvisioningJobWorkflow.class, options("provisioning-job-" + JOB_ID));
  WorkflowClient.start(wf::run, new ProvisioningJobInput(JOB_ID, ORG_ID, true));
  env.sleep(Duration.ofDays(8));                                 // time-skipping
  assertThat(activities.calls()).containsExactly("createWorkspace", "fail:PROVISIONING_APPROVAL_EXPIRED");
}

@Test
void current_code_replays_recorded_histories() throws Exception {
  WorkflowReplayer.replayWorkflowExecutionsFromResources("temporal/histories/ProvisioningJobWorkflow",
      ProvisioningJobWorkflowImpl.class);
}
```

## 5. Tenancy isolation test kit (ADR-0010)
- **T-10** Every repository adapter and every tenant-scoped endpoint is covered by the shared kit from `waver-platform-test`. It creates data as tenant A and asserts that tenant B cannot list, read, update or delete it, that a missing tenant context returns zero rows, and that direct SQL as the `<svc>_app` role without `waver.org_id` returns nothing.

```java
class ContractRepositoryTenancyTest extends TenancyIsolationContract<Contract, ContractId> {
  @Override protected Contract newAggregate(OrgId org) { return ContractTestData.aContract().forOrg(org).build(); }
  @Override protected ContractRepository repository() { return repository; }
}
```
- **T-11** Endpoint-level isolation: for each tenant-scoped route, a request with tenant B's token for tenant A's resource returns 404 (STD-003 R-29). Reseller portfolio and WAVER oversight paths have explicit positive and negative tests plus an assertion that an audit event was emitted.

## 6. Contract tests
- **T-12** Providers implement interfaces generated from `contracts/openapi` (compile-time conformance) and component tests validate responses, including problem bodies, against the contract schemas.
- **T-13** Consumers (services and BFFs) use clients generated from the provider contract and test against WireMock stubs built from the contract `examples`. A provider change that breaks a consumer's examples fails the provider build.
- **T-14** Events: producers validate every emitted event's Avro `data` against its `.avsc` and its `ce_*` headers against STD-004 §2; consumers parse every `examples/` file of the versions they subscribe to and a record written with the newest schema of each subscribed subject (ADR-0024). The CI compatibility check against Apicurio Registry plus the two-way linter (STD-004 section 4) runs on every change under `contracts/events/`.

## 7. Architecture tests
- **T-15** Every service runs the shared ArchUnit suite (layers, domain purity, no cross-service imports, no `KafkaTemplate` outside the platform messaging module, `SafeHttpClient` usage, banned crypto, Temporal workflow determinism bans, Redis/Vault/Qdrant/Avro types only in their adapters) plus its own rules (STD-001 J-31). agent-runtime additionally asserts `objective-engine` and `flow-engine` do not depend on each other (ADR-0019).

## 8. Frontend tests
- **T-16** `ui-*` components (Angular Material wrappers and custom components) ship WAVER component harnesses (built on the Material/CDK harnesses) and are tested for keyboard interaction, ARIA attributes and states; features never use Material harnesses directly. **NgRx store tests**: SignalStores are tested through `TestBed` with fakes of the generated BFF clients — methods, computed signals, request status, error mapping to `Problem`, flattening behaviour (e.g., `exhaustMap` ignores a second submit, `switchMap` cancels a stale search); `@ngrx/store` reducers and selectors are tested as pure functions, effects with `provideMockActions`, and components reading app-wide state with `provideMockStore`. Smart components are tested with harnesses, not DOM selectors.
- **T-34** Visual regression: Playwright screenshots of `apps/ui-catalog` for every `ui-*` component in light/dark × standard/compact density; baselines change only with brand-owner approval (RFC-0021 §6.4). Nx `affected` runs the job when a `ui-*` library or `ui-theme` changes.
- **T-17** Playwright E2E suites exist per app/audience (`apps/control-e2e`, `apps/partner-e2e`, `apps/workspace-e2e`), use role-based locators, log in through Keycloak with synthetic test users of the environment's realm, and run against the PR preview environment and staging with synthetic tenants.
- **T-18** Per-audience access tests (ADR-0009): a customer session cannot reach marketplace, catalog pricing, wholesale or reseller routes or APIs (UI route and direct BFF call both fail); a reseller cannot see another reseller's customers; feature-not-contracted screens appear when an entitlement is missing.
- **T-19** Critical journeys covered end-to-end: reseller quote → contract → activation → customer sees the capability; customer creates an agent (both strategies), tests in Playground, publishes; dunning → suspension → reactivation; WAVER oversight of a reseller. Each page in these journeys runs an automated accessibility check (WCAG 2.2 AA).

## 9. Agent evaluation tests (evaluation-service)
- **T-20** Every AgentVersion must pass its TestSuites (expected outcomes, tool-call assertions, policy assertions) in evaluation-service sandbox mode before a production Deployment; the ReleaseGate result is stored and audited (RFC-0016).
- **T-21** Platform changes to agent-runtime, engines, prompt compilation, tool-runtime or model routing run the **platform regression suites** (reference agents for both strategies) and **red-team suites** (prompt injection via messages/documents/tool outputs, tool privilege escalation, cross-domain knowledge leakage, PII exfiltration).
- **T-22** Per-PR pipelines use a deterministic model stub (WireMock behind model-gateway adapters) with recorded responses; live-model evaluation runs nightly and before release with pass thresholds over repeated runs to absorb non-determinism, within a cost budget per run.

## 10. Coverage and quality gates
- **T-23** Line + branch coverage gates per service: domain ≥ 90%, application ≥ 85%, overall ≥ 80%; `ui-*` and `data-access-*` libraries ≥ 80%, feature libraries and apps ≥ 70%. Coverage never justifies assertion-free tests; reviewers reject tests without meaningful assertions.
- **T-24** Flaky tests are quarantined only with a ticket and fixed within 7 days; `Thread.sleep` in tests is forbidden (use the polling helper in `waver-platform-test`).

## 11. Test data
- **T-25** Production data, production dumps and "anonymized" copies MUST NOT be used in any test, preview or staging environment. Data is synthetic, created through test data builders (`ContractTestData.aContract()`), with fake but well-formed identifiers (valid-format CPF/CNPJ generators, `example.com` e-mails).
- **T-26** Customer evaluation datasets belong to the customer's tenant and are used only inside that tenant's evaluation runs; they are never copied into platform suites.
- **T-27** Test secrets are generated per run or read from example config; real credentials never appear in test code or fixtures.

## Enforcement
| Rule | Enforced by |
|---|---|
| T-01..T-04 | Coverage gate (T-23) + review checklist (STD-011) |
| T-05..T-09, T-30..T-33 | `waver-platform-test` base classes and container kits; build fails if a service has repositories, Redis/Vault/Qdrant adapters or an outbox but no matching Testcontainers tests |
| T-28, T-29 Temporal | Required replay stage in CI for services with workers (STD-012); build fails if a workflow type has no golden history |
| T-10, T-11 tenancy kit | Platform test that discovers every repository bean/route and fails if it is not covered by the kit |
| T-12..T-14 contracts | CI contract stages (STD-012); compatibility check on `contracts/**` |
| T-15 | ArchUnit in `mvn verify` |
| T-16, T-34 frontend | `nx affected -t test` and the visual-regression job (STD-002 A-42, A-43) |
| T-17..T-19 E2E | Required Playwright job for PRs touching `frontend/**` or `*-bff` |
| T-20..T-22 evaluation | ReleaseGate in agent-builder-service blocks Deployment; nightly pipeline |
| T-23, T-24 | CI coverage report and flaky-test tracker |
| T-25..T-27 | No network path from CI/staging to production databases; secret scanning; review |

## Open Questions
1. Mockito ships inside `spring-boot-starter-test` but is not in TECH_STACK; this standard prefers fakes. Decide whether Mockito is allowed for adapter edge cases.
2. An OpenAPI response validator is not yet chosen; register it through STD-013 (JaCoCo and the Playwright accessibility engine are listed as build tooling in TECH_STACK; event payloads are validated with Avro itself, so no JSON Schema validator is needed for events).
3. ~~Load/performance testing tooling~~ — Gatling is listed as build tooling in TECH_STACK; data-plane load tests must also cover Redis quota checks, Qdrant retrieval and Temporal-backed waits before GA.
4. ~~Anonymized production snapshot in RFC-0007~~ — resolved in the v1.2 review: RFC-0007 now uses a synthetic large-volume dataset generator in `waver-platform-test`.
5. CI duration: the Debezium + Apicurio chain (T-06) is heavy for every PR of every service; proposal: full chain in the platform suite and nightly per service, with per-PR service tests using the same containers but a shared, pre-started Kafka Connect cluster.
