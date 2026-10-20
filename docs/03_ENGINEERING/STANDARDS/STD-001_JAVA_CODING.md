# STD-001 - Java Coding Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Temporal, Redis via Spring Data Redis, HashiCorp Vault via Spring Cloud Vault, Avro + Debezium outbox, Qdrant).
Version: 1.3
Date: 2026-10-19
Applies to: every Java service under `services/*` and every module under `platform/java/*`
Related: ADR-0007, ADR-0010, ADR-0015, ADR-0019, ADR-0020, ADR-0022, ADR-0023, ADR-0024, ADR-0025, AR-001, AR-003, AR-006, AR-007, AR-015, CONVENTIONS, DEPENDENCY_RULES, STD-004, STD-005, STD-006, STD-008, STD-009, STD-010, STD-014

Keywords MUST, MUST NOT, SHOULD follow RFC 2119. A deviation needs a written exception approved in the PR by the service owner and a platform reviewer.

## 1. Module layout (ADR-0007)
- **J-01** Each service is one Maven multi-module project in `services/<service>/` with exactly these modules (plus engine modules where an ADR allows, e.g., `objective-engine`, `flow-engine` in agent-runtime per ADR-0019):

```text
services/contract-service/
  pom.xml                         # parent, imports waver-platform-bom
  contract-service-domain/        # pure Java: aggregates, value objects, domain events, repository ports
  contract-service-application/   # use cases, inbound/outbound ports, commands/queries, transactions
  contract-service-adapters/      # web (generated OpenAPI interfaces), kafka/avro, persistence (JPA), http clients,
                                  # temporal (workflows, activities, client), redis, vault, vector (Qdrant)
  contract-service-bootstrap/     # @SpringBootApplication, configuration (incl. Spring Cloud Vault, Temporal workers), wiring; the only executable jar
```
- **J-02** Allowed module dependencies: `bootstrap → adapters → application → domain`. `domain` depends only on the JDK and a framework-free platform kernel (IDs, `Money`, `ErrorCode`; see Open Question 4). No reverse or skipping edges except `bootstrap → application/domain` for wiring.
- **J-03** A service MUST NOT depend on another `services/*` artifact (ADR-0015). It MAY depend on `platform/java/*` and on artifacts generated from `contracts/*` (OpenAPI clients, Avro specific records).
- **J-04** Versions come from `waver-platform-bom` (including Temporal SDK, Spring Data Redis, Spring Cloud Vault, the Avro serde and the Qdrant client). Declaring a version for a BOM-managed artifact is a build failure.

## 2. Package structure
- **J-05** Base package `br.com.waver.<context>` where `<context>` is the service name without `-service` and without hyphens (`contract-service` → `br.com.waver.contract`, `tool-runtime-service` → `br.com.waver.toolruntime`).

```text
br.com.waver.contract.domain.model          # Contract, ContractItem, ContractStatus (aggregate + VOs)
br.com.waver.contract.domain.event          # ContractActivated (domain event records)
br.com.waver.contract.domain.port           # ContractRepository (repository ports belong to domain)
br.com.waver.contract.application.port.in   # ActivateContractUseCase
br.com.waver.contract.application.port.out  # CatalogQueryPort, ApprovalPort, DomainEventPublisher, RenewalProcessPort
br.com.waver.contract.application.service   # ActivateContractService implements ActivateContractUseCase
br.com.waver.contract.adapters.in.web       # controllers implementing generated API interfaces, mappers
br.com.waver.contract.adapters.in.kafka     # consumers (Avro specific records → commands)
br.com.waver.contract.adapters.in.temporal  # workflow interfaces/implementations and activity implementations
br.com.waver.contract.adapters.out.persistence  # JPA entities, Spring Data repositories, repository adapters
br.com.waver.contract.adapters.out.events   # domain event → Avro mapping for the outbox
br.com.waver.contract.adapters.out.temporal # WorkflowClient-based adapter implementing RenewalProcessPort
br.com.waver.contract.adapters.out.redis    # Spring Data Redis adapters (idempotency, caches, locks)
br.com.waver.contract.adapters.out.http     # clients of other services (generated from contracts/openapi)
br.com.waver.contract.bootstrap             # ContractServiceApplication, @Configuration classes, worker registration
```
Services that own them add `adapters.out.vault` (connector-service and the lease holders, §11) and `adapters.out.vector` (knowledge-service, memory-service, ADR-0025).
- **J-06** Packages are organized by layer first, then by aggregate when a layer grows beyond ~15 types (`domain.model.contract`, `domain.model.quote`).

## 3. Domain modeling (AR-007)
- **J-07** The domain module MUST NOT import `org.springframework..`, `jakarta.persistence..`, `org.apache.kafka..`, `org.apache.avro..`, `io.temporal..`, `io.qdrant..`, `com.fasterxml..`/`tools.jackson..`, `org.slf4j..` or any vendor SDK.
- **J-08** Value objects are `record`s that validate in the compact constructor and are immutable.
- **J-09** Aggregates are classes with private state, intention-revealing methods and no public setters. State transitions are methods that enforce invariants and record domain events. A process orchestrated by Temporal still keeps its business status in an aggregate (e.g., `ProvisioningJob`); the workflow only sequences use cases.
- **J-10** IDs are typed value objects wrapping a UUIDv7 (`ContractId`), generated through a platform `IdGenerator` port, never `UUID.randomUUID()`.
- **J-11** Money is the platform `Money` value object (`BigDecimal` + ISO-4217 currency). `double`/`float` MUST NOT be used for money, quantities billed or rates.
- **J-12** Time is `Instant` (UTC) obtained from an injected `java.time.Clock`; `LocalDate` only for calendar dates (effective dates). `Instant.now()` without a clock is forbidden outside bootstrap; inside Temporal workflow code see J-32.

```java
public record ContractId(UUID value) {
  public ContractId { Objects.requireNonNull(value, "value"); }
}

public final class Contract {
  private final ContractId id;
  private final OrgId orgId;
  private ContractStatus status;
  private final List<DomainEvent> pendingEvents = new ArrayList<>();

  public void activate(LocalDate effectiveFrom, Clock clock) {
    if (status != ContractStatus.SIGNED) {
      throw new BusinessRuleViolation(ContractErrors.CONTRACT_NOT_SIGNED, id);
    }
    status = ContractStatus.ACTIVE;
    pendingEvents.add(new ContractActivated(id, orgId, effectiveFrom, clock.instant()));
  }
  public List<DomainEvent> pullEvents() { var e = List.copyOf(pendingEvents); pendingEvents.clear(); return e; }
}
```

## 4. Application layer, ports and transactions
- **J-13** One use case = one inbound port interface + one implementing service. Commands and queries are records in `application.port.in`.
- **J-14** The application module MAY use `spring-context` stereotype annotations and `spring-tx` `@Transactional`; it MUST NOT use Spring Web, Spring Data (JPA or Redis), JPA, Kafka, Avro, Temporal, Vault, Qdrant or HTTP client types. It talks to them only through outbound ports.
- **J-15** The transaction boundary is the application service method. One use case = one transaction. Domain events are written to the `outbox_event` table in the same transaction through the `DomainEventPublisher` port and published by Debezium (ADR-0024); the use case MUST NOT publish to Kafka directly.
- **J-16** No remote call (HTTP, model gateway, Kafka send, Redis, Vault, Qdrant, Temporal client) inside an open DB transaction. Read remote data before the transaction or react to events after it.
- **J-17** Authorization checks happen in the application service before loading or mutating aggregates (STD-009). The tenant context is read from the platform `TenantContext` port, never from command fields.

```java
@Service
class ActivateContractService implements ActivateContractUseCase {
  private final ContractRepository contracts;      // domain port
  private final DomainEventPublisher events;       // outbox-backed port (Avro serialized by the adapter)
  private final AccessPolicy access;               // platform authorization port
  private final Clock clock;

  @Override @Transactional
  public void handle(ActivateContract cmd) {
    access.require(Permission.CONTRACT_ACTIVATE, cmd.contractId());
    var contract = contracts.get(cmd.contractId());   // throws NotFound (tenant-filtered by RLS)
    contract.activate(cmd.effectiveFrom(), clock);
    contracts.save(contract);
    events.publish(contract.pullEvents());            // rows in outbox_event, same TX; Debezium publishes
  }
}
```

## 5. Adapters
- **J-18** Web adapters implement interfaces generated by OpenAPI Generator (`interfaceOnly=true`) from `contracts/openapi/<service>/v<N>.yaml`. Hand-written controllers without a contract are forbidden.
- **J-19** Persistence uses Spring Data JPA for aggregates with invariants and `JdbcClient` for hot paths, bulk writes, read models and full-text queries (RFC-0007 §5). JPA entities live only in `adapters.out.persistence` with suffix `JpaEntity`; they never leave that package. Domain ↔ entity and DTO ↔ command/view mapping uses **MapStruct** mappers (`componentModel = "spring"`, `unmappedTargetPolicy = ERROR`).
- **J-20** Outbound HTTP clients are generated from the target service contract (`br.com.waver.contracts:<service>-client-v<N>`), configured by the starter `client` module (auth, deadline, Resilience4j defaults of RFC-0005) and wrapped by an adapter implementing an application port (AR-014).
- **J-44** Event payloads use the Avro **specific records** generated at build time from `contracts/events/**/*.avsc` (package `br.com.waver.events.<context>.v<N>`, STD-004). Generated Avro classes appear only in `adapters.in.kafka` and `adapters.out.events`; they are mapped to and from domain events and commands with MapStruct. The domain never sees them.
- **J-45** Vector search (knowledge-service, memory-service) goes through a `VectorIndexPort` implemented in `adapters.out.vector` with the Qdrant Java client. Every query and delete is built by the platform tenant-filter helper from `TenantContext` (mandatory `org_id` filter plus workspace/domain/ACL filters, ADR-0025); filters are never taken from request bodies. Results are re-checked against PostgreSQL before use (STD-005 D-36).

```java
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ContractWebMapper {
  ActivateContract toCommand(UUID contractId, ActivateContractRequest body);
  ContractResponse toResponse(ContractView view);
}
```

## 6. Nulls, immutability, exceptions, logging
- **J-21** Public methods MUST NOT return `null`; use `Optional<T>` for "may be absent" query results only. `Optional` MUST NOT be used for fields, parameters or collections. Empty collections, never `null` collections.
- **J-22** Prefer `final` fields, `List.copyOf`/`Map.copyOf` at boundaries, and records for commands, queries, views and events.
- **J-23** Domain/application code throws the platform exception types of STD-008 (`ValidationFailed`, `BusinessRuleViolation`, `ResourceNotFound`, `ConflictDetected`, `AccessDenied`, `DependencyFailure`) carrying an `ErrorCode`. Catching `Exception`/`Throwable` is allowed only at adapter edges that translate it.
- **J-24** Checked exceptions from libraries are wrapped at the adapter that calls them; they MUST NOT cross into application code.
- **J-25** Logging uses SLF4J in application, adapters and bootstrap only, with parameterized messages (`log.info("Contract activated contractId={}", id)`), never string concatenation. Temporal workflow code uses `Workflow.getLogger(...)` (J-32). Content rules: STD-006.

## 7. Concurrency and virtual threads
- **J-26** Services run with `spring.threads.virtual.enabled=true`. Do not create platform thread pools for request work; use `Executors.newVirtualThreadPerTaskExecutor()` when explicit fan-out is required.
- **J-27** Virtual threads do not raise downstream capacity: concurrency towards PostgreSQL and other services is bounded by the connection pool (STD-005) and Resilience4j bulkheads. Never size a pool "because threads are cheap". Temporal worker concurrency (`maxConcurrentActivityExecutionSize`, `maxConcurrentWorkflowTaskExecutionSize`) is sized within the same pool budget.
- **J-28** Do not store request-scoped data in custom `ThreadLocal`s; use the platform `TenantContext`/MDC integration, which propagates correctly across virtual threads, Micrometer context propagation and Temporal context propagators (§9).
- **J-29** Shared mutable state is avoided; when needed, use `java.util.concurrent` types. Long blocking calls inside `synchronized` blocks are forbidden in hot paths. Cross-pod coordination uses the `DistributedLockPort` (§10), never JVM locks.

## 8. Style and tooling
- **J-30** Formatting is automated (google-java-format via Spotless); PRs with formatting diffs fail. No wildcard imports. Lombok is not part of the standard (see Open Questions).
- **J-31** Every service contains the shared ArchUnit suite from `waver-platform-test` plus service-specific rules:

```java
@AnalyzeClasses(packages = "br.com.waver.contract")
class ArchitectureTest {
  @ArchTest static final ArchRule domainIsPure = noClasses().that().resideInAPackage("..domain..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "org.springframework..", "jakarta.persistence..", "org.apache.kafka..", "org.apache.avro..",
          "io.temporal..", "io.qdrant..", "org.slf4j..", "br.com.waver.events..");
  @ArchTest static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
      .layer("Domain").definedBy("..domain..").layer("Application").definedBy("..application..")
      .layer("Adapters").definedBy("..adapters..").layer("Bootstrap").definedBy("..bootstrap..")
      .whereLayer("Adapters").mayOnlyBeAccessedByLayers("Bootstrap")
      .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Bootstrap")
      .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters", "Bootstrap");
  @ArchTest static final ArchRule jpaStaysInPersistence = classes().that().areAnnotatedWith(Entity.class)
      .should().resideInAPackage("..adapters.out.persistence..").andShould().haveSimpleNameEndingWith("JpaEntity");
  @ArchTest static final ArchRule noRandomUuid = noClasses().should().callMethod(UUID.class, "randomUUID");
  @ArchTest static final ArchRule workflowsOnlyOrchestrate = classes().that().areAnnotatedWith(WorkflowInterface.class)
      .or().implement(Predicates.annotatedWith(WorkflowInterface.class))   // platform helper predicate
      .should().onlyDependOnClassesThat().resideInAnyPackage(
          "java..", "io.temporal.workflow..", "io.temporal.activity..", "io.temporal.common..", "io.temporal.failure..",
          "..adapters.in.temporal..", "br.com.waver.platform.kernel..");
  @ArchTest static final ArchRule redisInAdapterOnly = noClasses().that().resideOutsideOfPackages("..adapters.out.redis..", "..bootstrap..")
      .should().dependOnClassesThat().resideInAPackage("org.springframework.data.redis..");
  @ArchTest static final ArchRule vaultInAdapterOnly = noClasses().that().resideOutsideOfPackages("..adapters.out.vault..", "..bootstrap..")
      .should().dependOnClassesThat().resideInAnyPackage("org.springframework.vault..", "org.springframework.cloud.vault..");
}
```

## 9. Temporal workflows and activities (ADR-0023)
Temporal runs long-running and multi-step processes for the owning service (process → service table in ADR-0023). It is not used on the conversational hot path: agent-runtime starts a workflow only when a run must wait (`WAITING_USER` beyond the short window, `WAITING_APPROVAL`, `WAITING_HANDOFF`, Flow `Delay`).

**Placement and ownership**
- **J-32** Workflow interfaces and implementations and activity implementations live in `adapters.in.temporal`; starting, signalling and querying a service's **own** workflows goes through an application outbound port (`RenewalProcessPort`) implemented in `adapters.out.temporal` with `WorkflowClient`. Workers are registered in `bootstrap`, one task queue per workflow type named `<service>.<workflow>` (e.g., `provisioning-service.provisioning-job`) in the namespace of the service's plane (`waver-commercial`, `waver-agent`, `waver-capability`, `waver-automation`, `waver-platform`). A service MUST NOT start, signal, query or cancel another service's workflows or poll its task queues (AR-003/AR-004); it calls that service's API or reacts to its events.
- **J-33** Workflows **only orchestrate**: sequence activities, wait for signals/updates and timers, compensate. Business rules stay in the domain and are executed by use cases called from activities. A workflow carries IDs and minimal state; it never holds aggregates.

**Determinism (workflow code)**
- **J-34** Workflow code MUST be deterministic. Forbidden inside workflow classes: any I/O or Spring bean (repositories, HTTP/Kafka/Redis/Vault/Qdrant clients, `TenantContext`), `Clock`/`Instant.now()`/`System.currentTimeMillis()`, `Thread.sleep`, `UUID.randomUUID()`/`Random`, native threads, executors, `CompletableFuture`, `synchronized`, mutable static state, and iteration over unordered collections (`HashMap`, `HashSet`) when the order affects commands. Use instead: `Workflow.currentTimeMillis()`, `Workflow.sleep`/`Workflow.newTimer`/`Workflow.await(timeout, condition)`, `Async`/`Promise`, `Workflow.newCancellationScope`, `LinkedHashMap`/sorted collections, `Workflow.getLogger(...)` (suppressed during replay). Business IDs (UUIDv7) are generated in activities through `IdGenerator`, or with `Workflow.sideEffect` when the workflow itself needs one.
- **J-35** Changes to workflow code that alter the sequence of commands for executions already running MUST use `Workflow.getVersion(changeId, DEFAULT_VERSION, n)` or be shipped under a new worker build ID with the old build's workers kept until its executions drain (worker versioning, RFC-0014 §9). Patches are removed only after no open execution can take the old path. Every workflow change ships with replay tests (STD-010 T-28).
- **J-36** Workflow inputs, outputs, signal/update/query payloads and activity arguments are versioned Java records with IDs and minimal state, ≤ 64 KiB serialized. They MUST NOT contain secrets, credentials, message content, prompts, tool inputs/outputs or PII: Temporal histories are persisted and visible in Temporal Web (ADR-0023, STD-009). Long-lived loops (e.g., renewal reminders, dunning stages) call `Workflow.continueAsNew` before the history exceeds 10 000 events.
- **J-37** Workflow IDs are deterministic business keys (`<aggregate-kebab>-<id>`, e.g., `provisioning-job-019a…`) started with `WorkflowIdReusePolicy.REJECT_DUPLICATE` and `WorkflowIdConflictPolicy.USE_EXISTING`, so a start is idempotent. Starts are never made inside a DB transaction (J-16): when a start must follow a state change, the use case commits the aggregate and its outbox event first, and the start is triggered from that committed event by the service's own consumer (inbox-deduplicated), never fire-and-forget after commit.
- **J-38** Signals carry decisions from the owning service's own adapters (e.g., an `ApprovalGranted` consumer signalling the waiting workflow); `@UpdateMethod` with an `@UpdateValidatorMethod` is used when the caller needs a synchronous result. Workflow code catches `ActivityFailure` explicitly and compensates; any other exception is left to fail the **workflow task** (Temporal retries it and the alert fires, STD-008 E-20) instead of silently failing the workflow.

**Activities**
- **J-39** An activity implementation is a thin adapter that sets the tenant context from the workflow input (platform activity interceptor), then calls **one** application inbound port; one activity = one use case = one transaction (J-15); events are written to the outbox, never sent to Kafka from an activity.
- **J-40** Every activity is idempotent (AR-006): state-changing use cases are no-ops when the transition was already applied (aggregate `version`/status check), and outbound calls use `Idempotency-Key = <workflowId>:<activityId>` (`Activity.getExecutionContext().getInfo()`).
- **J-41** Every activity stub sets `StartToCloseTimeout`; activities that can run longer than 60 s also set `HeartbeatTimeout` and heartbeat progress. `ScheduleToCloseTimeout` bounds the business deadline. Temporal `RetryOptions` are the **single retry layer** for activity work: Resilience4j retries are disabled inside activities (circuit breakers, bulkheads and deadlines stay). Non-retryable platform error categories are listed in `setDoNotRetry(...)` and raised as `ApplicationFailure` with the `ErrorCode` as type (STD-008 E-20).

```java
@WorkflowInterface
public interface ProvisioningJobWorkflow {
  @WorkflowMethod void run(ProvisioningJobInput input);          // record: jobId, orgId, contractId (no PII)
  @SignalMethod void approvalDecided(ApprovalDecision decision);
}

public final class ProvisioningJobWorkflowImpl implements ProvisioningJobWorkflow {
  private final ProvisioningActivities activities = Workflow.newActivityStub(ProvisioningActivities.class,
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(30))
          .setRetryOptions(RetryOptions.newBuilder()
              .setInitialInterval(Duration.ofSeconds(1)).setBackoffCoefficient(2.0)
              .setMaximumInterval(Duration.ofMinutes(5)).setMaximumAttempts(20)
              .setDoNotRetry(PlatformFailures.NON_RETRYABLE_TYPES).build())
          .build());
  private ApprovalDecision decision;

  @Override public void run(ProvisioningJobInput in) {
    activities.createWorkspace(in.jobId());
    if (in.requiresApproval() && !Workflow.await(Duration.ofDays(7), () -> decision != null)) {
      activities.fail(in.jobId(), "PROVISIONING_APPROVAL_EXPIRED");   // use case: status FAILED + ProvisioningFailed in outbox
      return;
    }
    try {
      activities.grantEntitlements(in.jobId());
    } catch (ActivityFailure f) {
      activities.compensateWorkspace(in.jobId());
      activities.fail(in.jobId(), PlatformFailures.codeOf(f));
      return;
    }
    activities.succeed(in.jobId());                                    // ProvisioningSucceeded via outbox
  }
  @Override public void approvalDecided(ApprovalDecision d) { this.decision = d; }
}
```

## 10. Redis (ADR-0020)
- **J-42** Redis is accessed only through Spring Data Redis (`StringRedisTemplate`/`RedisTemplate` on the Lettuce client, configured by `waver-platform-starter` with TLS and the service's ACL user) in `adapters.out.redis`, behind application ports: `IdempotencyStorePort`, `QuotaCounterPort` (usage-metering-service), `DistributedLockPort`, `RunStreamPort` (agent-runtime producer, BFF consumers), cache ports. Domain and application never see Redis types. BFF sessions use Spring Session Data Redis configured by the starter; session attributes hold tokens and session metadata only.
- **J-43** Keys are built only with the platform `RedisKeys` helper, which enforces the service prefix `waver:<prefix>:` and the key/TTL rules of STD-005 §9; every write sets a TTL. Values are strings or JSON produced by the platform `ObjectMapper` with a `v` (schema version) field; `JdkSerializationRedisSerializer` and any Java native serialization are forbidden (STD-009 S-11). Multi-step atomic operations (quota check-and-increment, rate counters) are Lua scripts in `src/main/resources/redis/*.lua` loaded as `RedisScript` and unit-tested.
- **J-46** Redis is never the source of truth (ADR-0020): every adapter implements the documented fallback — cache → miss and load from the owner; quota → PostgreSQL counter and local snapshot; rate limiting → local limiter (fail-open); idempotency and locks → `TransientFailure` (503, retryable) rather than executing without protection (STD-008). Shared caches use the starter's two-level cache (Caffeine L1, Redis L2) with TTL plus event-driven eviction; `@Cacheable` is allowed only on adapter methods, and cached values are views, never aggregates to be mutated.
- **J-47** `DistributedLockPort` (starter implementation on Redis, ADR-0020) is for short in-process coordination only: lease ≤ 30 s, always released in `finally`, and never the only correctness guarantee — writes stay protected by optimistic locking (`version`). Waits longer than the lease belong in Temporal (§9).

## 11. Vault (ADR-0022)
- **J-48** Spring Cloud Vault (`spring-cloud-starter-vault-config`) is configured only in `bootstrap` (`spring.config.import`, Kubernetes auth with the service account's Vault role `<service>-app`, database secrets backend for dynamic PostgreSQL credentials, STD-005 D-29). Static platform secrets arrive as files synced by the Vault Secrets Operator and imported with `configtree` (RFC-0014 §3, STD-014). Application code reads secrets only through typed `@ConfigurationProperties` bound in `bootstrap`; Vault paths, tokens and role names never appear in domain, application or adapter code.
- **J-49** Programmatic Vault use (Transit encrypt/decrypt/sign/verify through Spring Vault `VaultTransitOperations`) is allowed only in `adapters.out.vault`, behind ports (`CredentialCipherPort`, `SigningPort`). Only connector-service encrypts and decrypts with the `tenant-credentials` key; model-gateway-service and channel-service decrypt only the credential kinds of their lease (`LLM_PROVIDER_KEY`, `CHANNEL_PROVIDER_TOKEN`) as allowed by their Vault policy (ADR-0014 ownership, ADR-0022). Plaintext is held in `Sensitive<T>` for the duration of the call, never cached, logged or returned through APIs (STD-006 L-12, STD-009 S-26).

## Enforcement
| Rule | Enforced by |
|---|---|
| J-01..J-03 module graph, no cross-service deps | Maven module structure + Maven Enforcer (`bannedDependencies` on artifacts of other `services/*` projects, ADR-0015) |
| J-04 BOM versions | Maven Enforcer rule in parent POM; CI fails on overrides (ADR-0007) |
| J-07, J-14, J-19, J-31, J-44, J-45 layering and purity | ArchUnit suite from `waver-platform-test`, run in `mvn verify` (incl. Avro, Temporal, Qdrant packages banned outside adapters) |
| J-10, J-12 UUIDv7 / clock | ArchUnit (`noRandomUuid`, no `Instant.now()` outside bootstrap) |
| J-15, J-16 outbox / no remote call in TX | ArchUnit (no `KafkaTemplate`/`Producer` outside the platform messaging module; no Temporal/Redis/Vault/Qdrant client calls from `@Transactional` methods — platform rule); code review; integration tests (STD-010) |
| J-18 contract-first | OpenAPI Generator in build; CI rejects `@RestController` not implementing a generated interface (ArchUnit) |
| J-19 MapStruct `ERROR` policy | Compiler fails on unmapped fields |
| J-30 formatting | Spotless `check` in CI |
| J-32..J-38 Temporal | ArchUnit (`workflowsOnlyOrchestrate`, bans on `Clock`, `Instant.now`, `Thread.sleep`, `UUID.randomUUID`, `Random`, `java.util.concurrent` in workflow classes); Temporal replay tests in CI (STD-010 T-28); worker registration check at startup (task-queue naming) |
| J-39..J-41 activities | Platform activity interceptor (tenant context, MDC, tracing); review checklist; activity tests with `TestActivityEnvironment` |
| J-42, J-43, J-46, J-47 Redis | ArchUnit (`redisInAdapterOnly`); `RedisKeys` is the only key builder (ArchUnit bans string-built keys passed to `RedisTemplate`); TTL assertion in the Redis test kit (STD-010 T-30); chaos test with Redis down (ADR-0020) |
| J-48, J-49 Vault | ArchUnit (`vaultInAdapterOnly`); Vault policies as code reviewed per service (ADR-0022); secret scanning (STD-014) |
| J-21..J-25 | Code review checklist (STD-011); static analysis in CI (STD-012) |

## Open Questions
1. **Lombok**: not allowed as mandatory and not in TECH_STACK. Records cover most cases; if teams want Lombok for JPA entities only, it must be requested through STD-013.
2. ~~Build tooling (Spotless/google-java-format, Maven Enforcer, JaCoCo) not in TECH_STACK~~ — listed as build tooling in TECH_STACK; Checkstyle is not adopted.
3. Nullness annotations (e.g., JSpecify) would strengthen J-21 but are not on the allowed list.
4. J-02 assumes a framework-free `waver-platform-domain-kernel` module (UUIDv7 IDs, `Money`, `ErrorCode`, `DomainEvent`). ADR-0015 lists only bom/starter/test/connector-sdk, and the starter (Spring) cannot be a domain dependency; ADR-0015 should add this module or each service duplicates these types.
5. Confirm that `spring-context`/`spring-tx` annotations in the application module (J-14) are acceptable under AR-007, which only constrains the domain layer.
6. Temporal payload encryption: J-36 keeps sensitive data out of workflow payloads; whether a Temporal payload codec backed by Vault Transit is additionally required (defense in depth for histories) is a security decision pending RFC-0010.
