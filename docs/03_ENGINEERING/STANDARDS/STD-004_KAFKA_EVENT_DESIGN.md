# STD-004 - Kafka Event Design Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro with Apicurio Registry, CloudEvents binary mode, Debezium Outbox Event Router on Kafka Connect; Temporal replaces state-machine sagas).
Version: 1.3
Date: 2026-10-19
Applies to: every event produced or consumed through Kafka
Related: ADR-0004, ADR-0012, ADR-0023, ADR-0024, AR-004, AR-006, AR-008, AR-013, AR-015, CONVENTIONS (Events), KAFKA_BASELINE, EVENT_CATALOG, RFC-0006, RFC-0007, RFC-0014, STD-001, STD-005, STD-008, STD-009, STD-010

Keywords MUST, MUST NOT, SHOULD follow RFC 2119. Kafka carries facts between bounded contexts; it does not replace synchronous APIs (ADR-0004). Architecture and defaults are in RFC-0006; this standard lists the rules every producer, consumer and schema is checked against. ADR-0024 replaced the JSON payload, JSON Schema files, structured-mode envelope and polling relay of ADR-0012; topic/type naming, the inbox, retry topics and DLQs are unchanged.

## 1. Naming
- **K-01** Topic: `waver.<context>.<aggregate>.v<major>`; one topic per aggregate type, several event types per topic. `<context>` = owning service name without `-service`, kebab-case preserved; `<aggregate>` kebab-case: `waver.contract.contract.v1`, `waver.tool-runtime.tool-execution.v1`, `waver.agent-runtime.agent-run.v1`. Compacted state topics add `-snapshot` or `-state` (`waver.entitlement.entitlement-snapshot.v1`, `waver.usage-metering.quota-state.v1`; CONVENTIONS).
- **K-02** CloudEvents `type`: `waver.<context>.<EventName>.v<major>`, past-tense PascalCase: `waver.contract.ContractActivated.v1`. Commands (`ActivateContract`) MUST NOT be published as events.
- **K-03** Schemas: `contracts/events/<context>/<EventName>.v<N>.avsc` (Avro, describing the event `data`; source of truth, reviewed like code), shared named types in `contracts/events/common/*.avsc`, examples in `contracts/events/<context>/examples/<EventName>.v<N>.<case>.json`. Topics are declared in `deploy/kafka/topics/<context>.yaml`; auto-creation is disabled.
- **K-04** Consumer group: `<consumer-service>.<handler>` kebab-case (`entitlement-service.contract-events`). Retry topics `<topic>-retry-<n>`, DLQ `<topic>.dlq`.
- **K-24** Registry subjects use the **`TopicRecordNameStrategy`**: `<topic>-<fully.qualified.RecordName>` (e.g., `waver.contract.contract.v1-br.com.waver.events.contract.v1.ContractActivated`), because a topic carries several event types. Compatibility is set per subject to `BACKWARD_TRANSITIVE` (ADR-0024). The Kafka key is the aggregate ID as a plain UTF-8 string (`StringSerializer`), not Avro, so it has no subject.

## 2. Envelope (CloudEvents 1.0, binary mode, ADR-0024, RFC-0006)
The CloudEvents attributes and WAVER extensions travel as **Kafka headers**; the Kafka value is only the Avro-encoded `data` in the registry wire format (magic byte + 4-byte schema ID + Avro binary). Header values are UTF-8 strings.

| Kafka header | Rule |
|---|---|
| `ce_specversion` | `1.0` (added by the connector configuration) |
| `ce_id` | UUIDv7; the inbox dedup key |
| `ce_source` | `/waver/<service>` |
| `ce_type` | `waver.<context>.<EventName>.v<N>`; consumers dispatch on it **before** deserializing |
| `ce_time` | occurrence time, RFC 3339 UTC with `Z` |
| `ce_subject` | aggregate ID; equals the Kafka key |
| `content-type` | `application/avro` (CloudEvents `datacontenttype` in Kafka binary mode) |
| `ce_dataschema` | `urn:waver:events:<context>:<EventName>:v<N>` (the exact schema is identified by the wire-format schema ID) |
| `ce_orgid` | owning Organization; required for tenant-scoped data; PLATFORM org ID for platform-wide events |
| `ce_workspaceid`, `ce_domainid` | when the aggregate is scoped |
| `ce_correlationid`, `ce_causationid` | always; `causationid` = triggering event `id` or HTTP request ID |
| `ce_actorid`, `ce_actortype` | always; `actortype` ∈ `USER`, `SERVICE_ACCOUNT`, `API_KEY`, `SYSTEM` |
| `ce_supportgrantid` | when acting under a support grant |
| `ce_aggregateversion` | required for state-bearing aggregates: aggregate `version` after the change (decimal string) |
| `ce_traceparent`, `ce_tracestate` and W3C `traceparent`, `tracestate` | distributed tracing extension plus the plain W3C headers read by Micrometer (STD-007 O-07) |

Kafka record: key = aggregate ID (never `orgId`); headers = above; value = Avro `data`. PII never goes in keys or headers (K-07).

```text
topic   waver.contract.contract.v1
key     019a0c41-22d0-7a9e-8c11-0d6b9e2f4a77
headers ce_specversion=1.0  ce_id=019a0c5e-8f3a-7c41-9b2e-5f1d8a7c3e10  ce_source=/waver/contract-service
        ce_type=waver.contract.ContractActivated.v1  ce_time=2026-10-19T14:03:00Z
        ce_subject=019a0c41-22d0-7a9e-8c11-0d6b9e2f4a77  content-type=application/avro
        ce_dataschema=urn:waver:events:contract:ContractActivated:v1  ce_orgid=019a0b10-3c4d-7e5f-a6b7-c8d9e0f1a2b3
        ce_correlationid=019a0c5e-8e01-7d20-b3c4-1a2b3c4d5e6f  ce_causationid=019a0c5e-8d99-7aa0-9f10-aa22bb33cc44
        ce_actorid=019a0a01-1111-7222-8333-444455556666  ce_actortype=USER  ce_aggregateversion=3
        traceparent=00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01  (also ce_traceparent)
value   [0x00][schema id][Avro binary of br.com.waver.events.contract.v1.ContractActivated]
```

## 3. Payload design
- **K-05** Events state facts that already happened, in business terms. They carry IDs plus the minimal state consumers need (status, effective dates, quantities, references). Full snapshots are allowed only on `-state`/`-snapshot` topics (RFC-0006 §11).
- **K-06** Target size < 64 KiB, hard limit 1 MiB (value plus headers). Large content (documents, transcripts, files) is referenced by ID / `objectRef`, never embedded.
- **K-07** No PII and no restricted content (names, e-mails, phones, CPF/CNPJ of persons, message text, prompts, completions, tool inputs/outputs) by default. Exceptions are allowed only on topics classified `RESTRICTED`, only in fields carrying the Avro field property `"x-waver-pii": true`, and only when every consumer is listed as entitled in the event catalog. PII never goes in keys, headers or envelope extensions; state topics never hold PII.
- **K-08** Every Avro field declares the field property `"x-waver-classification"` (`PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED`) and a `doc`.
- **K-09** Commercial-confidential values (prices, wholesale, margins) are omitted from events consumed by data-plane or customer-facing services; consumers needing them call the owning API (RFC-0006 §6).

### 3.1 Avro schema rules
- **K-25** One `.avsc` per event type and major version. The top-level type is a `record` named `<EventName>` in namespace `br.com.waver.events.<context-without-hyphens>.v<N>` (e.g., `br.com.waver.events.usagemetering.v1.QuotaExceeded`), which is also the Java package of the generated specific record (STD-001 J-44). Field names are `camelCase`; enum symbols `UPPER_SNAKE_CASE`.
- **K-26** Logical types are mandatory for these values:

| Value | Avro type |
|---|---|
| IDs (UUIDv7) | `{"type": "string", "logicalType": "uuid"}` |
| Instants | `{"type": "long", "logicalType": "timestamp-millis"}` (UTC) |
| Calendar dates (effective dates) | `{"type": "int", "logicalType": "date"}` |
| Money | named record `br.com.waver.events.common.Money` = `amount: {"type": "bytes", "logicalType": "decimal", "precision": 19, "scale": 4}` + `currency: string` (ISO-4217); never `float`/`double` |
| Quantities and counters | `int`/`long`; billed fractional quantities use `decimal` (19,4) |
| Durations | `long` with the unit in the field name (`latencyMs`) |

- **K-27** Optional fields are unions `["null", T]` with `"default": null`. Every field added after v1 of a schema MUST have a default. Enums that may grow are **open enums**: they include the symbol `UNKNOWN` and declare `"default": "UNKNOWN"` from their first version; enums without a default are closed and adding a symbol to them is breaking.
- **K-28** Shared named types (`Money`, `ActorRef`) live in `contracts/events/common/` and are registered as schema references; a change to a shared type is checked as a change to every schema that references it. Maps with free-form keys, `bytes` blobs (other than `decimal`) and recursive types are forbidden.
- **K-29** Examples are written in "logical JSON" (UUID and ISO-8601 strings, decimals as strings) and converted into specific records by the platform test kit; they are not hand-written Avro JSON encoding.

```json
{
  "type": "record", "name": "ContractActivated", "namespace": "br.com.waver.events.contract.v1",
  "doc": "A contract became ACTIVE.",
  "fields": [
    { "name": "contractId",    "type": { "type": "string", "logicalType": "uuid" }, "x-waver-classification": "INTERNAL", "doc": "Contract ID" },
    { "name": "customerOrgId", "type": { "type": "string", "logicalType": "uuid" }, "x-waver-classification": "INTERNAL", "doc": "Customer organization" },
    { "name": "resellerOrgId", "type": { "type": "string", "logicalType": "uuid" }, "x-waver-classification": "INTERNAL", "doc": "Selling reseller" },
    { "name": "effectiveFrom", "type": { "type": "int", "logicalType": "date" }, "x-waver-classification": "INTERNAL", "doc": "First effective day" },
    { "name": "items", "x-waver-classification": "INTERNAL", "doc": "Activated items",
      "type": { "type": "array", "items": { "type": "record", "name": "ActivatedItem", "fields": [
        { "name": "contractItemId", "type": { "type": "string", "logicalType": "uuid" }, "x-waver-classification": "INTERNAL", "doc": "Item" },
        { "name": "skuId",          "type": { "type": "string", "logicalType": "uuid" }, "x-waver-classification": "INTERNAL", "doc": "SKU" },
        { "name": "quantity",       "type": "int", "x-waver-classification": "INTERNAL", "doc": "Contracted quantity" } ] } } },
    { "name": "renewalMode", "type": ["null", { "type": "enum", "name": "RenewalMode", "symbols": ["UNKNOWN", "AUTOMATIC", "MANUAL"], "default": "UNKNOWN" }],
      "default": null, "x-waver-classification": "INTERNAL", "doc": "Added in a later minor change: nullable with default" }
  ]
}
```

## 4. Schema evolution (AR-015, ADR-0024)
The registry enforces `BACKWARD_TRANSITIVE` per subject (new readers read all older data). Because producers and consumers deploy independently, WAVER additionally requires that **old consumers keep reading new data**; the CI schema linter therefore allows only changes that are compatible in both directions.

| Allowed within a major | Breaking (requires a new `...v<N+1>` type, namespace and `.avsc`) |
|---|---|
| Add a field with a default (nullable union with `default: null`, or a typed default) | Remove or rename a field (aliases are not used to rename) |
| Add a symbol to an **open** enum (has `default`) | Add a field without a default; make an optional field required |
| Add `doc`, `x-waver-*` properties (classification may only become stricter) | Change a type, including Avro promotions (`int`→`long`, `float`→`double`, `string`↔`bytes`) |
| Add a new event type (new subject) to the topic | Change a logical type, decimal precision/scale, unit or meaning of a field |
| Examples | Add a symbol to a closed enum; remove or reorder enum symbols; change a default value |
| | Change the record name/namespace, the key or the aggregate of an existing type |

- **K-10** Consumers resolve every record with their own reader schema (specific records): unknown writer fields are ignored by Avro resolution, unknown open-enum symbols become `UNKNOWN`, and unknown event types (unknown `ce_type`) are skipped **before** deserialization and counted by a metric.
- **K-11** A new major of an event type is a new `type`, record namespace (`.v2`) and subject on the same topic, dual-published from the outbox (two rows, one transaction) for the deprecation window (default 90 days or until the catalog shows no v1 consumers). A new topic major is needed only when key, partitioning or ordering semantics change (RFC-0014 §9).
- **K-30** Schemas are registered **only by CI** (compatibility check against the target environment's Apicurio Registry before merge, registration as a release step before the service rolls out, RFC-0014). Services run with `auto.register.schemas=false` and `use.latest.version=false`; producers resolve the schema ID of each record type they emit at startup and are not ready until all are resolved.

## 5. Producer rules
- **K-12** Events are produced **only** through the transactional outbox (`outbox_event`, STD-005 §11) inserted in the same DB transaction as the state change, after the aggregate update. `KafkaTemplate.send` from business code and any DB-then-Kafka dual write are forbidden.
- **K-13** Business code uses the `DomainEventPublisher` port; the platform outbox adapter serializes `data` with the registry-aware Avro serializer and writes the header columns. **Debezium** (PostgreSQL connector on Kafka Connect, Outbox Event Router SMT, one replication slot per producing database) publishes the row to the topic named in it, with the aggregate ID as key, the header columns as `ce_*` headers and the payload bytes as value (`ByteArrayConverter`, no re-encoding). Kafka Connect producers run with `acks=all` and `enable.idempotence=true`. Per-key order follows commit order because Debezium reads the WAL in commit order. Delivery is at-least-once (connector restarts can re-send); consumers deduplicate (K-16).
- **K-14** Exactly one producing service per topic: the aggregate owner in MICROSERVICES_MAP (enforced by Kafka ACLs on the connector's principal per database and by the router configuration).
- **K-15** Producers emit `aggregateversion` equal to the persisted `version`; they never publish facts that were rolled back (a rolled-back transaction never reaches the WAL) and never delete published facts (compensate with new facts). Business failures of a Temporal-orchestrated process are published as failure events by the activity's use case through the outbox (e.g., `ProvisioningFailed`, ADR-0023).

## 6. Consumer rules
- **K-16** Idempotency through the inbox `processed_event(consumer_group, event_id)` keyed by `ce_id`: `INSERT ... ON CONFLICT DO NOTHING`, apply the side effect and own outbox rows in the same transaction, commit, then commit the offset. Outbound HTTP side effects use the event `id` as `Idempotency-Key`; Temporal starts triggered by events use a deterministic workflow ID (STD-001 J-37).
- **K-17** Ordering exists per key only. Consumers MUST NOT assume ordering across aggregates or topics; state-keeping consumers ignore `aggregateversion <= stored` and treat gaps (`> stored + 1`) per their profile.
- **K-18** Tenant context comes only from `ce_orgid`/`ce_workspaceid`/`ce_domainid` of an event from the topic owner and is set (`set_config('waver.org_id', ..., true)`) before any tenant table access. An event whose `orgid` contradicts known state goes to the DLQ and raises a security alert.
- **K-19** Each handler declares a profile: **ORDERED** (projections, read models, workflow starters/signallers) uses blocking retries (backoff 1 s → 60 s, max 10 attempts) then DLQ, and parks later events of an aggregate with a version gap; **UNORDERED** (notifications, audit ingest, metering) uses retry topics (10 s, 1 min, 10 min) then DLQ. Non-retryable errors go straight to the DLQ (STD-008 E-11). Registry unavailability is a transient error, not a poison record.
- **K-20** Every DLQ has an alert, a runbook and a redrive procedure (`tooling/event-replay`, registry-aware, audited). DLQ records keep the original Avro bytes and headers. Consumers never call the producer synchronously to "complete" an event; missing data means a design change (K-05) or a local read model (`rm_*`, AR-013).

```java
@WaverEventHandler(group = "billing-service.contract-lifecycle", profile = ORDERED)
void on(WaverEvent event) {                          // headers parsed; data not yet deserialized
  switch (event.type()) {
    case "waver.contract.ContractActivated.v1" -> {
      ContractActivated data = event.data(ContractActivated.class);   // registry-aware Avro, specific record
      startBilling.handle(mapper.toCommand(data, event.aggregateVersion()));
    }
    default -> unknownTypes.increment();             // K-10: ignore, do not fail, never deserialize
  }
}   // inbox, tenant context, retries and DLQ are applied by the platform wrapper
```

## 7. Testing events (STD-010)
- **K-21** Producer tests validate every emitted event: the `data` against its `.avsc` from the `contracts/events` artifact, and the header columns against section 2 (required headers present, formats valid).
- **K-22** Consumer tests run each handler against every `examples/` file of each subscribed version, and against a record written with the newest schema of each subscribed subject (old-reader check).
- **K-23** Integration tests with Testcontainers (PostgreSQL with `wal_level=logical`, Kafka, Apicurio Registry, Kafka Connect with Debezium): state change → outbox → Debezium → Kafka → consumer; nothing published on rollback; duplicate delivery processed once; stale `aggregateversion` ignored; poison record lands in `.dlq`; unknown `ce_type` skipped; tenant context applied.

## Enforcement
| Rule | Enforced by |
|---|---|
| K-01..K-04, K-14, K-24 naming/ownership | CI check of `contracts/events/**` and `deploy/kafka/**` against `07_API_EVENTS/EVENT_CATALOG.md`; Kafka ACLs (RFC-0006); Outbox Event Router configuration review |
| K-07..K-09, K-25..K-29 schema content | CI schema linter: namespace/name, logical types, every field has `x-waver-classification` and `doc`, `x-waver-pii` only on RESTRICTED topics with entitled consumers, open-enum rules; security review (STD-009) |
| K-10, K-11, K-30 compatibility and registration | Apicurio `BACKWARD_TRANSITIVE` per subject; CI compatibility check against the registry plus the WAVER two-way linter (section 4); registration only by the release pipeline; `auto.register.schemas=false` asserted by the starter |
| K-12, K-13 outbox only | ArchUnit: `KafkaTemplate`/`Producer` used only by the platform messaging module (retry/DLQ); Debezium connector config generated from the platform template |
| K-16..K-19 consumers | Platform `messaging` wrapper; tenancy test kit; consumer-profile declaration required at startup |
| K-20 DLQ operations | Alert rules (STD-007); runbook presence check in production readiness review |
| K-21..K-23 | `mvn verify` suites; contract tests generated from schemas |

## Open Questions
1. ~~CONVENTIONS does not cover hyphenated contexts and `-state` topics~~ — resolved: CONVENTIONS (Events) now covers both.
2. ~~`07_API_EVENTS/EVENT_CATALOG.md` not present~~ — resolved: the catalog exists and is the registry of record for topics, types and consumers.
3. ~~`domainid`, `supportgrantid`, `aggregateversion` not in the ADR-0012 extension list~~ — resolved by ADR-0024.
4. Avro serde library: the registry-aware serializer/deserializer used by the platform messaging module (Apicurio serdes in Confluent-compatible mode or the Apache-licensed Confluent serializers) is chosen under STD-013; the wire format above is the same for both.
5. Time precision: `timestamp-millis` is proposed for all instants; confirm no consumer (billing, metering) needs microsecond precision before the first schemas are registered.
