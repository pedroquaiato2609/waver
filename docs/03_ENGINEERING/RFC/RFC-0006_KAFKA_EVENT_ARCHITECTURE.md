# RFC-0006 - Apache Kafka Event Architecture
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro + Apicurio Registry, CloudEvents binary mode, Debezium Outbox Event Router on Kafka Connect (ADR-0024); Vault-delivered Kafka/registry/CDC credentials (ADR-0022); Istio mTLS for in-cluster traffic (ADR-0026)).
Owners: WAVER Architecture (named owner TBD)
Reviewers: TBD
Date: 2026-10-19
Version: 1.3

## Summary
Defines how WAVER services publish and consume asynchronous events on Apache Kafka: topic naming and ownership, partitions, replication and retention defaults, keys and ordering, consumer groups, the CloudEvents envelope in **binary mode** (attributes as `ce_*` Kafka headers), **Avro** payloads governed by **Apicurio Registry** (subjects, `BACKWARD_TRANSITIVE` compatibility, CI registration), the transactional outbox published by the **Debezium Outbox Event Router on Kafka Connect**, the inbox for idempotent consumption with registry-aware deserializers, retries, DLQ and replay, compacted state topics for data-plane read models, PII and tenant-context rules, observability and Kafka security. The event list itself lives in `07_API_EVENTS/EVENT_CATALOG.md`.

## Context / Problem
ADR-0004 makes Kafka the async backbone (not a REST replacement). ADR-0012 fixed the CloudEvents envelope and the outbox/inbox pattern; ADR-0024 (2026-10-19) supersedes its format and relay parts: payloads are Avro with schemas in `contracts/events` registered in Apicurio Registry by CI, the envelope travels in binary mode as Kafka headers, and Debezium on Kafka Connect replaces the polling relay. CONVENTIONS fixes topic, type, key and DLQ naming (unchanged by ADR-0024). KAFKA_BASELINE still requires concrete decisions on partitioning, consumer groups, delivery semantics, retries, replay, retention, PII, observability and compatibility. Without them each team would invent its own and AR-006/AR-008/AR-015 could not be verified. Data-plane services also need local read models that survive control-plane outages (AR-013), which requires a rebuild source that does not depend on control-plane APIs.

## Goals
- One mechanical way to publish (outbox row → Debezium) and consume (inbox) events, provided by `waver-platform-starter` and the platform's Kafka Connect cluster.
- Per-aggregate ordering, at-least-once delivery, effectively-once processing.
- Runtime-enforced schema compatibility (registry) in addition to CI checks.
- Tenant context on every tenant-scoped event (AR-008), no PII leakage into the bus.
- Replayable and observable pipelines; poison messages never block a partition silently.
- Read models for the data plane that can be rebuilt from Kafka alone.

## Non-goals
- Choosing a managed Kafka offering (cloud provider is open, TECH_STACK). Apicurio Registry and Kafka Connect run per environment (ADR-0024); whether managed equivalents replace them later is a cloud-ADR question.
- Event sourcing as a persistence model (services persist state in PostgreSQL, RFC-0007).
- Kafka Streams/ksql processing topologies.
- Change data capture of domain tables. Debezium reads **only** the `outbox_event` table (and a heartbeat table); aggregates are never streamed as table changes.
- Commands over Kafka. Events are past-tense facts (CONVENTIONS); requests that need an answer use REST (RFC-0005, RFC-0008).

## Requirements / ARs
| AR | How this RFC satisfies it |
|---|---|
| AR-004 | Only versioned event types on versioned topics, with registered Avro schemas, cross service boundaries. |
| AR-006 | Inbox table + idempotent handlers; Kafka Connect producers are idempotent; outbox + CDC is at-least-once. |
| AR-008 | `ce_orgid` (and `ce_workspaceid`/`ce_domainid`) headers mandatory; consumers set DB tenant context from them. |
| AR-009 | Explicit audit records on `waver.audit.audit-record.v1` through every outbox (§13) plus audit-relevant domain topics; `ce_actorid`/`ce_actortype`/`ce_supportgrantid` on every event. |
| AR-013 | Compacted state topics let data-plane read models rebuild without control-plane APIs. |
| AR-014 | Outbox decouples DB commit from broker availability (WAL retained by the replication slot); Connect retries; consumer retries with backoff; DLQ. |
| AR-015 | Avro compatibility enforced twice: CI check against the registry before merge and `BACKWARD_TRANSITIVE` rule per subject in Apicurio; major versions get new types/subjects (and new topics only when keying changes). |

## Proposed Design

### 1. Topic naming and ownership
- Domain event topic: `waver.<context>.<aggregate>.v<major>` (CONVENTIONS). `<context>` is the context token of the owning service, listed in EVENT_CATALOG §1. The default token is the service name without the `-service` suffix, in kebab-case (`contract`, `usage-metering`, `agent-runtime`, `model-gateway`). The one exception is `identity-access-service` → `identity`. `<aggregate>` is the aggregate type in kebab-case (`contract`, `price-book`, `agent-run`, `support-access-grant`). The same `<context>` is used in CloudEvents `type` and in `contracts/events/<context>/`. Java packages drop the hyphens (STD-001 J-05).
- One topic per aggregate type; several event types per topic (CONVENTIONS). Exactly one producing service per topic: the owner in MICROSERVICES_MAP. No other service may write to it (enforced by ACLs; Debezium connectors produce with the owner's principal, see Security & Privacy). The single exception is the audit transport topic `waver.audit.audit-record.v1` (§13).
- State topic (compacted, §11): `waver.<context>.<state-name>.v<major>`. The v1 state topics are the two listed in MICROSERVICES_MAP: `entitlement-snapshot` and `quota-state`. See Open Questions (CONVENTIONS addendum).
- Retry topics: `<topic>-retry-<n>`; DLQ: `<topic>.dlq` (CONVENTIONS).
- **Registry subjects** (ADR-0024): subject name strategy `TopicRecordNameStrategy`, because a topic carries several event types. Subject = `<topic>-<avro full name>`, where the Avro namespace is `br.com.waver.events.<contextpkg>.v<major>` (`<contextpkg>` = context token without hyphens, as in Java packages) and the record name is the event name. Example: `waver.contract.contract.v1-br.com.waver.events.contract.v1.ContractActivated`. A new major of an event type therefore always gets a new subject.
- Topics are declared as code in `deploy/kafka/topics/<context>.yaml` (partitions, retention, cleanup policy, owner, classification). `auto.create.topics.enable=false` on all clusters. A pipeline step applies them through the Kafka Admin API; the concrete tooling depends on the managed-Kafka decision (open).
- Debezium connectors are declared as code in `deploy/kafka-connect/connectors/<service>.yaml` (one per producing service database, §8) and applied by the pipeline through the Kafka Connect REST API. Kafka Connect's internal topics (`_waver-connect-configs`, `_waver-connect-offsets`, `_waver-connect-status`, compacted, RF 3) belong to the platform team.

### 2. Partitions, replication, retention (engineering defaults)
| Topic class | Examples | Partitions | RF / min.insync | Retention | Cleanup |
|---|---|---|---|---|---|
| Control-plane domain events | `waver.contract.contract.v1`, `waver.customer.customer.v1`, `waver.entitlement.entitlement.v1` | 6 | 3 / 2 | 14 days | delete |
| Data-plane high volume | `waver.agent-runtime.agent-run.v1`, `waver.model-gateway.model-call.v1`, `waver.tool-runtime.tool-execution.v1` | 24 | 3 / 2 | 7 days | delete |
| Restricted content | `waver.channel.channel-session.v1`, `waver.connector.webhook-endpoint.v1` | 24 | 3 / 2 | 3 days | delete |
| State (read-model source) | `waver.entitlement.entitlement-snapshot.v1`, `waver.usage-metering.quota-state.v1` | 12 | 3 / 2 | infinite (compacted) | compact |
| Audit transport | `waver.audit.audit-record.v1` | 12 | 3 / 2 | 14 days | delete |
| Retry | `<topic>-retry-<n>` | = source | 3 / 2 | 3 days | delete |
| DLQ | `<topic>.dlq` | = source | 3 / 2 | 14 days | delete |
| Kafka Connect internal | `_waver-connect-*` | 1 (configs) / 25 (offsets) / 5 (status) | 3 / 2 | infinite | compact |

- Partition counts are sized for 3x expected v1 peak; they can be increased but never decreased, and increasing breaks key-to-partition mapping for in-flight keys, so it is done only with a drained or version-tolerant consumer (§4).
- `max.message.bytes` 1 MiB. Target event size < 64 KiB (Avro is compact, but the target is unchanged). Larger payloads are not put on Kafka; the event carries a reference (document ID, object key through the `ObjectStoragePort`, RFC-0007).
- Kafka is not the system of record or the archive. Long-term history lives in the owning service DB and in audit-service.

### 3. Producer and consumer client defaults
The producer is the Kafka Connect worker running the service's Debezium connector (§8); services never produce domain events directly.

| Setting | Default | Reason |
|---|---|---|
| Connect producer `acks` / `enable.idempotence` | `all` / `true` (worker config, `producer.override.*` per connector) | No loss on leader failover; no broker-side duplicates. |
| Connect producer `max.in.flight.requests.per.connection` | 5 | Ordering preserved with idempotence. |
| Connect producer `compression.type` / `linger.ms` | `lz4` / 5 | Throughput on CDC batches. |
| Key / value converters | `StringConverter` (key = aggregate ID) / Debezium `BinaryDataConverter` (value = Avro bytes already encoded by the service) | The service serializes with the registry-aware serializer at outbox insert; Connect passes bytes through unchanged. |
| Header converter | `SimpleHeaderConverter` | CloudEvents binary-mode headers as UTF-8 strings. |
| Consumer `enable.auto.commit` | `false` | Offsets committed after the handler and inbox write commit. |
| Consumer value deserializer | Spring Kafka `ErrorHandlingDeserializer` → starter `CloudEventAvroDeserializer` (registry-aware Avro deserializer, `specific.avro.reader=true`) | Writer schema fetched by ID from Apicurio and cached; reader schema = generated class; failures become DLQ records instead of poison loops. |
| Serializer settings (outbox insert) | `auto.register.schemas=false`, `use.latest.version=false` | Schemas are registered only by CI (ADR-0024); the serializer looks up the ID of the exact generated schema. |
| Spring Kafka `AckMode` | `RECORD` (control plane), `BATCH` (data plane) | Simplicity vs throughput. |
| `max.poll.records` / `max.poll.interval.ms` | 100 / 300000 | Handlers must finish a batch well within the interval. |
| Kafka transactions / EOS (incl. Connect exactly-once source) | not used | Outbox + inbox give effectively-once without Kafka transactions. |

The Avro serde artifact (Apicurio SerDes in Confluent-compatible mode or a Confluent-compatible Apache-2.0 serializer) is fixed once in the platform BOM under STD-013. The wire format is always the Confluent-compatible one (magic byte `0`, 4-byte schema ID, Avro binary) so that standard tooling and managed Kafka offerings can read it (ADR-0024).

### 4. Keys and ordering
- Key = aggregate ID (UUIDv7 string, plain UTF-8, not Avro-encoded). Ordering is guaranteed per aggregate only (ADR-0012). Domain event topics are never keyed by `orgId`, because large tenants would create hot partitions. The only per-org key is the entitlement snapshot state topic, whose aggregate *is* the per-org EntitlementSet (RFC-0013).
- Every event of a state-bearing aggregate carries `ce_aggregateversion` (the aggregate's `version` column after the change). State topics carry their own monotonic version in the payload (`snapshotVersion`, RFC-0013). Consumers that maintain state ignore events at or below the stored version and detect gaps (`> stored + 1`).
- Commit order is preserved end to end: Debezium reads the WAL in commit order through a single connector task per database and produces with idempotence, so per-aggregate order equals commit order (§8).
- Where ordering across aggregates matters, the owning service chooses the key so that related facts share it. Examples: all facts of one suspension case on `waver.customer.suspension-case.v1` keyed by `suspensionCaseId` (RFC-0017); deployment activations and rollbacks keyed by `agentId` (RFC-0016); inbound and outbound messages of one conversation keyed by `channelSessionId`.

### 5. Consumer groups
- Name: `<consumer-service>.<handler>` in kebab-case, e.g., `entitlement-service.contract-events`, `agent-runtime.entitlement-snapshot-projector`, `audit-service.audit-record-ingest`.
- One group per handler purpose; never share a group across services. Group IDs are prefixed with the service name so ACLs can grant `GROUP` rights by prefix.
- Consumer profile is declared per handler:
  - **ORDERED** (projections, sagas, state machines): blocking retries in place (`DefaultErrorHandler`, exponential backoff 1 s → 60 s, max 10 attempts), then DLQ. Subsequent events for an aggregate with a detected version gap are also parked in the DLQ so state never applies out of order.
  - **UNORDERED** (notification, audit ingest, metering, analytics): non-blocking Spring Kafka retry topics `-retry-0` (10 s), `-retry-1` (1 min), `-retry-2` (10 min), then DLQ.
- Retry and DLQ topics are shared by all groups consuming the source topic (CONVENTIONS naming). The platform starter adds header `waver-consumer-group` on retry/DLQ publish and a `RecordFilterStrategy` so a group only processes its own retried records.
- **Type filtering before decoding**: the starter's `CloudEventAvroDeserializer` reads `ce_type` first. Event types the group does not handle are acknowledged without decoding, so a new event type on an existing topic never breaks existing consumers and never requires them to know the new schema.
- Consumers whose handlers start or signal Temporal workflows (ADR-0023) do so only for workflows owned by their own service; the inbox guarantees one start per event and the workflow ID follows CONVENTIONS (`<workflowType>:<aggregateId>`, idempotent start), so a redelivered event never starts a second workflow.

### 6. CloudEvents envelope (binary mode)
**Binary content mode** of the CloudEvents Kafka protocol binding (ADR-0024): every CloudEvents attribute and WAVER extension is a Kafka header named `ce_<attribute>`; `datacontenttype` maps to the `content-type` header; the Kafka value is only the Avro-encoded `data`. Header values are UTF-8 strings. Headers carry no PII (§12).

| Header | Required | Value |
|---|---|---|
| `ce_specversion` | yes | `1.0` |
| `ce_id` | yes | UUIDv7; also the inbox dedup key |
| `ce_source` | yes | `/waver/<service>` |
| `ce_type` | yes | `waver.<context>.<EventName>.v<major>` |
| `ce_time` | yes | RFC 3339 UTC (`2026-10-19T14:03:00Z`), set at outbox insert |
| `ce_subject` | yes | Aggregate ID (equals the Kafka key) |
| `ce_dataschema` | yes | `urn:waver:events:<context>:<EventName>:v<major>` → `contracts/events/<context>/<EventName>.v<N>.avsc` and its registry subject |
| `content-type` | yes | `application/avro` (Confluent-compatible wire format: magic byte + 4-byte schema ID + Avro binary) |
| `ce_orgid` | yes (ADR-0012/0024) | Owning Organization (`org_id` of the aggregate, ADR-0010). Platform-wide aggregates (catalog, marketplace, tool-registry, model catalog) use the PLATFORM Organization ID |
| `ce_workspaceid`, `ce_domainid` | conditional | Set when the aggregate is scoped to a Workspace / Domain |
| `ce_correlationid`, `ce_causationid` | yes | `causationid` = `id` of the triggering event, or the HTTP request ID |
| `ce_actorid`, `ce_actortype` | yes | `actortype` ∈ `USER`, `SERVICE_ACCOUNT`, `API_KEY`, `SYSTEM` |
| `ce_supportgrantid` | when acting under a support grant | WAVER extension (ADR-0024, RFC-0010) |
| `ce_traceparent`, `ce_tracestate` | yes / optional | CloudEvents distributed-tracing extension (RFC-0009) |
| `ce_aggregateversion` | yes for state-bearing aggregates | WAVER extension (ADR-0024, §4) |

The starter treats an absent or empty conditional header as "not set". Debezium also adds an `id` header (the outbox `id` column); consumers ignore it and use `ce_id`.

Full example: `ContractActivated` on `waver.contract.contract.v1`.

```text
topic     waver.contract.contract.v1
key       019a0c41-22d0-7a9e-8c11-0d6b9e2f4a77
headers   ce_specversion       1.0
          ce_id                019a0c5e-8f3a-7c41-9b2e-5f1d8a7c3e10
          ce_source            /waver/contract-service
          ce_type              waver.contract.ContractActivated.v1
          ce_subject           019a0c41-22d0-7a9e-8c11-0d6b9e2f4a77
          ce_time              2026-10-19T14:03:00Z
          ce_dataschema        urn:waver:events:contract:ContractActivated:v1
          content-type         application/avro
          ce_orgid             019a0a77-9e8d-7c6b-a5f4-e3d2c1b0a998
          ce_correlationid     019a0c5e-8e01-7d20-b3c4-1a2b3c4d5e6f
          ce_causationid       019a0c5e-8d99-7aa0-9f10-aa22bb33cc44
          ce_actorid           019a0a01-1111-7222-8333-444455556666
          ce_actortype         USER
          ce_traceparent       00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
          ce_aggregateversion  3
value     0x00 | <4-byte schema ID of subject
                  waver.contract.contract.v1-br.com.waver.events.contract.v1.ContractActivated>
               | Avro binary of the record below
```

Decoded `data` (shown as JSON for readability):

```json
{
  "contractId": "019a0c41-22d0-7a9e-8c11-0d6b9e2f4a77",
  "contractVersionId": "019a0c5e-7b10-7c00-8d00-112233445566",
  "contractNumber": "CT-2026-000123",
  "customerOrgId": "019a0b10-3c4d-7e5f-a6b7-c8d9e0f1a2b3",
  "resellerOrgId": "019a0a77-9e8d-7c6b-a5f4-e3d2c1b0a998",
  "effectiveFrom": "2026-11-01",
  "effectiveTo": "2027-10-31",
  "items": [
    { "contractItemId": "019a0c41-2300-7000-8000-000000000001", "skuId": "019a0900-aaaa-7bbb-8ccc-dddddddddddd", "quantity": 5 }
  ]
}
```

Schema `contracts/events/contract/ContractActivated.v1.avsc`:

```json
{
  "type": "record",
  "name": "ContractActivated",
  "namespace": "br.com.waver.events.contract.v1",
  "doc": "A ContractVersion became effective. Prices are deliberately absent.",
  "fields": [
    { "name": "contractId", "type": { "type": "string", "logicalType": "uuid" },
      "doc": "Aggregate ID (equals ce_subject).", "x-waver-classification": "CONFIDENTIAL", "x-waver-pii": false },
    { "name": "contractVersionId", "type": { "type": "string", "logicalType": "uuid" },
      "doc": "Activated ContractVersion.", "x-waver-classification": "CONFIDENTIAL", "x-waver-pii": false },
    { "name": "contractNumber", "type": "string",
      "doc": "Human-readable number issued by the seller.", "x-waver-classification": "CONFIDENTIAL", "x-waver-pii": false },
    { "name": "customerOrgId", "type": { "type": "string", "logicalType": "uuid" },
      "doc": "Customer Organization; counterparty tenant for customer-scoped consumers.",
      "x-waver-classification": "CONFIDENTIAL", "x-waver-pii": false, "x-waver-tenant-ref": true },
    { "name": "resellerOrgId", "type": { "type": "string", "logicalType": "uuid" },
      "doc": "Seller Organization (equals ce_orgid).", "x-waver-classification": "CONFIDENTIAL", "x-waver-pii": false },
    { "name": "effectiveFrom", "type": { "type": "int", "logicalType": "date" },
      "doc": "First effective day.", "x-waver-classification": "CONFIDENTIAL", "x-waver-pii": false },
    { "name": "effectiveTo", "type": ["null", { "type": "int", "logicalType": "date" }], "default": null,
      "doc": "Last effective day; null for open-ended contracts.", "x-waver-classification": "CONFIDENTIAL", "x-waver-pii": false },
    { "name": "items", "doc": "Activated items (IDs and quantities only).",
      "x-waver-classification": "CONFIDENTIAL", "x-waver-pii": false,
      "type": { "type": "array", "items": {
        "type": "record", "name": "ContractItemRef",
        "fields": [
          { "name": "contractItemId", "type": { "type": "string", "logicalType": "uuid" }, "doc": "Contract item." },
          { "name": "skuId", "type": { "type": "string", "logicalType": "uuid" }, "doc": "Catalog SKU." },
          { "name": "quantity", "type": "int", "doc": "Contracted quantity." }
        ] } } }
  ]
}
```
Prices are deliberately absent: consumers that need commercial values (billing-service) read them through the owning service API. `ce_orgid` is the seller (reseller) Organization, which is the Contract's `org_id` in RFC-0013, and the customer is carried as `customerOrgId`. Customer-scoped consumers (entitlement-service, provisioning-service) set their tenant context from `customerOrgId` through an explicit, documented mapping in their handler (`x-waver-tenant-ref`, §12).

### 7. Schemas, registry and evolution
**Source of truth and layout**
- `contracts/events/<context>/<EventName>.v<N>.avsc`, Avro, describing `data` only. The envelope is not part of the Avro schema: binary mode carries it in headers, whose contract is §6 (the former `cloudevents-envelope.v1.json` is retired). CODEOWNERS: the producing service team plus Architecture.
- Naming: namespace `br.com.waver.events.<contextpkg>.v<N>`, record name = event name, fields `camelCase`, enum symbols `UPPER_SNAKE_CASE`.
- Type rules: IDs `string` + `logicalType: uuid`; instants `long` + `timestamp-millis` (UTC); dates `int` + `date`; money is the shared record `Money { amount: string (decimal string), currency: string (ISO 4217) }` (CONVENTIONS: never float); optional fields are `["null", T]` with `"default": null`.
- Shared types (`Money`, small value records) are defined once in `contracts/events/common/` and **inlined** by the build into each event schema before registration; v1 does not use cross-subject schema references.
- Every field carries `doc`, `x-waver-classification` (RFC-0007 §8) and `x-waver-pii: true|false` as Avro custom field properties; counterparty tenant fields carry `x-waver-tenant-ref: true` (§12).
- Open enums declare an Avro enum `default` (by convention `UNKNOWN`), which replaces the former `x-waver-open-enum` marker. Enums without a default are closed.
- Java `SpecificRecord` classes are generated by the Avro Maven plugin and published as `br.com.waver.contracts:events-<context>` (ADR-0015). Frontends never consume Kafka, so no TypeScript package is produced.

**Registry (Apicurio, ADR-0024)**
- One Apicurio Registry per environment, with its own PostgreSQL database (RFC-0007), accessed through its Confluent-compatible API.
- Compatibility rule `BACKWARD_TRANSITIVE` on every subject (global default plus explicit per-subject rule set at creation). Subject naming per §1.
- Services never register schemas (`auto.register.schemas=false`). They hold the `sr-readonly` role; only the CI release identity holds `sr-developer`; `sr-admin` is restricted to platform operators (break-glass). Authentication is OIDC via Keycloak (ADR-0021) with client credentials.
- At startup the platform starter resolves the schema IDs of every event type the service produces (by exact schema content). A missing registration fails the readiness probe, so a producer can never be deployed ahead of its schema. Resolved IDs and fetched writer schemas are cached in-process for the pod lifetime, so running pods do not depend on the registry for known schemas.

**Evolution rules within a major**
- Allowed: add a field with a default; add a symbol to an enum that declares a `default`; add or change `doc`; add aliases for documentation only.
- Breaking (new major): remove or rename a field, change a field type (including Avro promotions such as `int` → `long`), make an optional field required, add a symbol to a closed enum, change meaning or units, change the key or the aggregate of an existing type.
- The registry enforces `BACKWARD_TRANSITIVE` (new readers read all old data). CI additionally checks **forward** compatibility against the latest version (offline with the Avro `SchemaValidator`), so consumers that have not upgraded yet keep reading new data; the rules above satisfy both.
- Consumers rely on Avro schema resolution: unknown fields are ignored; unknown symbols of open enums resolve to the enum default (`UNKNOWN`).

**CI and release flow**
1. PR touching `contracts/events/**`: Avro parse and lint (naming, namespace, `doc`, classification properties, logical types, no `float`/`double` for money); compatibility check against the registry of each environment through the Confluent-compatible compatibility endpoint (read-only CI identity) plus the offline forward check; PII/consumer check against EVENT_CATALOG; code generation and consumer compilation (monorepo).
2. Release pipeline: registers new schemas in `staging`, then in `production`, **before** deploying the producer (consumers first, then schema, then producer, §Deployment). Registration is idempotent (same content → same ID).
3. Subject or version deletion is disabled in staging/production; deprecated versions stay readable as long as any retained record (topic retention, DLQ) may reference them.

**New majors**
- New `type` (`...ContractActivated.v2`), new namespace (`...contract.v2`) and therefore a new subject, published on the same topic while the topic's key/ordering semantics hold. Both majors are dual-published from the outbox (two rows, same transaction) for a deprecation window (default 90 days or until the catalog shows no v1 consumers). A new topic major (`.v2` topic) is needed only when key, partitioning or ordering semantics change.
- CI fails if PII appears on a topic whose consumers are not listed as entitled in the catalog (`x-waver-pii`).

### 8. Transactional outbox with Debezium (Outbox Event Router)
DDL (in every producing service DB, created by the platform starter's baseline migration; replaces the v1.2 polling-relay table):

```sql
CREATE TABLE outbox_event (
  seq                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id                  uuid        NOT NULL,              -- = ce_id; repeated only by an audited republish (§10)
  org_id              uuid,                              -- null only for platform-wide events (RLS on insert, RFC-0007)
  aggregate_type      text        NOT NULL,
  aggregate_id        uuid        NOT NULL,              -- Kafka key (customer orgId for entitlement-snapshot)
  topic               text        NOT NULL,              -- routing target (route.by.field)
  payload             bytea,                             -- Avro data, Confluent wire format; NULL = tombstone (state topics only)
  schema_id           int,                               -- registry ID embedded in payload (diagnostics)
  content_type        text        NOT NULL DEFAULT 'application/avro',
  ce_specversion      text        NOT NULL DEFAULT '1.0',
  ce_type             text        NOT NULL,
  ce_source           text        NOT NULL,
  ce_subject          text        NOT NULL,
  ce_time             text        NOT NULL,              -- RFC 3339 UTC, set by the starter
  ce_dataschema       text        NOT NULL,
  ce_orgid            text        NOT NULL,
  ce_workspaceid      text,
  ce_domainid         text,
  ce_correlationid    text        NOT NULL,
  ce_causationid      text        NOT NULL,
  ce_actorid          text        NOT NULL,
  ce_actortype        text        NOT NULL,
  ce_supportgrantid   text,
  ce_aggregateversion text,
  ce_traceparent      text,
  ce_tracestate       text,
  republish_of        bigint,                            -- seq of the original row for audited republishes
  created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_event_created ON outbox_event (created_at);
CREATE INDEX outbox_event_id ON outbox_event (id);

CREATE TABLE debezium_heartbeat (id int PRIMARY KEY, ts timestamptz NOT NULL);  -- one row, see heartbeat below

-- created by the migrator role; the CDC role only reads through it
CREATE PUBLICATION waver_outbox FOR TABLE outbox_event, debezium_heartbeat;
```
- The domain transaction updates the aggregate row **first**, then inserts the outbox row(s), in the same transaction (the starter's `EventPublisher` serializes `data` with the registry-aware serializer and fills the `ce_*` columns from the tenant/actor/trace context). Nothing is written to Kafka by the service.
- **Debezium PostgreSQL connector** (one per producing database, running on the environment's **Kafka Connect** cluster in distributed mode, ≥ 3 workers) reads the WAL through logical replication (`pgoutput`, slot `waver_<svc>_outbox`, publication `waver_outbox`) and applies the **Outbox Event Router** SMT: the `topic` column selects the topic, `aggregate_id` becomes the key, `payload` the value, and every `ce_*`/`content_type` column is placed as a Kafka header. Only inserts are routed; deletes of old rows are ignored.
- Ordering: the PostgreSQL connector runs one task per database and reads changes in commit order; with an idempotent producer this keeps per-aggregate order equal to commit order. Because concurrent writers to the same aggregate serialize on the row lock (optimistic `version` check), commit order per aggregate is also version order.
- Published rows are deleted after 7 days by a starter batch job (high-volume services partition `outbox_event` by day and drop partitions instead; the partitioned table stays in the publication with `publish_via_partition_root = true`). The 7-day window serves `outbox republish` (§10).
- **Heartbeat**: on a shared cluster the WAL is cluster-wide, so a quiet database's slot would retain WAL produced by others. Each connector runs `heartbeat.action.query` every 10 s against `debezium_heartbeat`; those change records are dropped by a Kafka `Filter` SMT before the router, so no heartbeat reaches a domain topic.
- **WAL protection**: PostgreSQL runs with `wal_level = logical` and `max_slot_wal_keep_size` sized per cluster (RFC-0007); `max_replication_slots`/`max_wal_senders` are budgeted at one slot per producing database plus headroom. Slots must survive primary failover (PostgreSQL failover slots or the managed-service equivalent; see Open Questions). Slot lag is alerted (Observability).
- **Credentials**: the connector logs in as `<svc>_cdc` (RFC-0007: `LOGIN REPLICATION`, `SELECT` only on `outbox_event` and `debezium_heartbeat`), a Vault database static role whose password Vault rotates; Kafka credentials of the owner principal come from Vault KV. Both are delivered by the Vault Secrets Operator as files and referenced through Kafka's built-in `FileConfigProvider`, so connector configs and the Connect REST API never contain secret values (ADR-0022).
- **Initial position**: `snapshot.mode = no_data`; the pipeline creates the slot and the connector before the first release that writes outbox rows, so no row is ever missed.

Connector declaration (excerpt of `deploy/kafka-connect/connectors/contract-service.yaml`):

```yaml
name: outbox-contract-service
config:
  connector.class: io.debezium.connector.postgresql.PostgresConnector
  plugin.name: pgoutput
  database.hostname: ${file:/vault/secrets/contract-cdc.properties:host}
  database.dbname: waver_contract
  database.user: ${file:/vault/secrets/contract-cdc.properties:username}
  database.password: ${file:/vault/secrets/contract-cdc.properties:password}
  database.sslmode: verify-full
  topic.prefix: cdc.contract                 # internal name only; no topic is created from it
  slot.name: waver_contract_outbox
  publication.name: waver_outbox
  publication.autocreate.mode: disabled
  table.include.list: public.outbox_event,public.debezium_heartbeat
  snapshot.mode: no_data
  heartbeat.interval.ms: 10000
  heartbeat.action.query: UPDATE debezium_heartbeat SET ts = now() WHERE id = 1
  tombstones.on.delete: false
  key.converter: org.apache.kafka.connect.storage.StringConverter
  value.converter: io.debezium.converters.BinaryDataConverter
  header.converter: org.apache.kafka.connect.storage.SimpleHeaderConverter
  transforms: dropHeartbeat,outbox
  transforms.dropHeartbeat.type: org.apache.kafka.connect.transforms.Filter
  transforms.dropHeartbeat.predicate: isHeartbeat
  predicates: isHeartbeat,isOutbox
  predicates.isHeartbeat.type: org.apache.kafka.connect.transforms.predicates.TopicNameMatches
  predicates.isHeartbeat.pattern: cdc\.contract\.public\.debezium_heartbeat
  predicates.isOutbox.type: org.apache.kafka.connect.transforms.predicates.TopicNameMatches
  predicates.isOutbox.pattern: cdc\.contract\.public\.outbox_event
  transforms.outbox.type: io.debezium.transforms.outbox.EventRouter
  transforms.outbox.predicate: isOutbox
  transforms.outbox.table.field.event.id: id
  transforms.outbox.table.field.event.key: aggregate_id
  transforms.outbox.table.field.event.payload: payload
  transforms.outbox.table.expand.json.payload: false
  transforms.outbox.route.by.field: topic
  transforms.outbox.route.topic.replacement: ${routedByValue}
  transforms.outbox.route.tombstone.on.empty.payload: true
  transforms.outbox.table.fields.additional.placement: >-
    content_type:header:content-type,ce_specversion:header:ce_specversion,ce_type:header:ce_type,
    ce_source:header:ce_source,ce_subject:header:ce_subject,ce_time:header:ce_time,
    ce_dataschema:header:ce_dataschema,ce_orgid:header:ce_orgid,ce_workspaceid:header:ce_workspaceid,
    ce_domainid:header:ce_domainid,ce_correlationid:header:ce_correlationid,ce_causationid:header:ce_causationid,
    ce_actorid:header:ce_actorid,ce_actortype:header:ce_actortype,ce_supportgrantid:header:ce_supportgrantid,
    ce_aggregateversion:header:ce_aggregateversion,ce_traceparent:header:ce_traceparent,ce_tracestate:header:ce_tracestate
  producer.override.sasl.jaas.config: ${file:/vault/secrets/contract-kafka.properties:sasl.jaas.config}   # produces as svc-contract-service
  errors.tolerance: none                     # a bad row stops the task (alert) instead of being skipped
```
Exact option names follow the Debezium and Kafka versions pinned in the platform BOM; the connector template is generated per service from `deploy/environments/<env>/databases.yaml`.

```mermaid
sequenceDiagram
  participant UC as Use case (tx)
  participant DB as Service DB (WAL)
  participant KC as Kafka Connect (Debezium + Outbox Event Router)
  participant K as Kafka
  participant R as Apicurio Registry
  participant C as Consumer
  UC->>DB: BEGIN; set tenant context; UPDATE aggregate (version+1)
  UC->>DB: INSERT outbox_event (Avro payload with cached schema ID, ce_* columns); COMMIT
  KC->>DB: stream WAL via replication slot (pgoutput, publication waver_outbox)
  KC->>K: produce as svc-<owner> (key=aggregate_id, value=payload, ce_* headers, acks=all)
  KC->>DB: confirm flushed LSN (slot advances)
  K->>C: deliver
  C->>C: read ce_type; skip if not handled
  C->>R: fetch writer schema by ID (first time only, then cached)
  C->>C: BEGIN; INSERT processed_event; handle; COMMIT; commit offset
```

### 9. Inbox and idempotency
```sql
CREATE TABLE processed_event (
  consumer_group text        NOT NULL,
  event_id       uuid        NOT NULL,
  processed_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_group, event_id)
);
```
- The handler runs in one DB transaction: `INSERT ... ON CONFLICT DO NOTHING` into `processed_event` with `ce_id`; if no row was inserted the event is a duplicate and is acknowledged without effects; otherwise the handler applies changes and inserts its own outbox rows; commit; then commit the offset.
- Rows are kept 30 days (> max topic retention) and purged daily. The `(consumer_group, event_id)` key is the ADR-0024 form (it refines ADR-0012's `processed_event(event_id)` for services with several handlers).
- Side effects outside the DB (HTTP to another service) must carry an idempotency key derived from the event ID (`Idempotency-Key = ce_id`, RFC-0008). Starting a Temporal workflow from a handler uses the CONVENTIONS workflow ID `<workflowType>:<aggregateId>`, so duplicates are rejected by Temporal (ADR-0023).

### 10. Retries, DLQ and replay
- Classification: deserialization errors (Avro decode failure, schema ID unknown after registry retries, missing mandatory `ce_*` header) and `NonRetryableEventException` go straight to the DLQ; transient errors (timeouts, 5xx, lock failures, registry temporarily unavailable for an uncached ID) follow the consumer profile (§5).
- DLQ record = original key, value bytes (Avro wire format, schema ID intact) and **all original headers** (`ce_*` included), plus: original topic/partition/offset, `waver-consumer-group`, exception class, message (sanitized, no payload values), attempt count, first/last failure time.
- Replay tooling (`tooling/event-replay`, Java CLI using the platform starter and the registry-aware deserializer, run as a Kubernetes Job with an operator identity; every run is audited):
  - `dlq inspect --topic <t>` decodes records through the registry for display; `data` of RESTRICTED topics and fields marked `x-waver-pii` are masked.
  - `dlq redrive --topic <t> --group <g> [--event-id ...|--since ...]` republishes selected DLQ records (bytes and headers unchanged) to `<t>-retry-0` with the group header, so only that group reprocesses them.
  - `offsets reset --group <g> --to-timestamp <ts>` for projection rebuilds (group must be stopped). Safe because handlers are idempotent.
  - `outbox republish --service <s> (--from-seq <n> | --since <ts>)` calls the owning service's operator endpoint (platform starter), which copies selected rows still in the outbox (7 days) into new outbox rows with the **same `id`** and `republish_of` set; Debezium publishes them like any insert and consumer inboxes drop duplicates. Used after a lost topic or a lost replication slot.
- A DLQ record older than 14 days is lost from Kafka, so the DLQ alert (see Observability) also turns each DLQ item into an operational ticket within minutes. Operational procedure: `10_RUNBOOKS/RUNBOOK_KAFKA_CONSUMER_LAG_AND_DLQ.md`.

### 11. Compacted state topics for read models
- The data plane must keep serving during control-plane outages (AR-013). For that data, the owner also publishes the **current state** to a compacted topic. The value is a full Avro snapshot with the CloudEvents attributes in headers, so the same envelope rules apply.

| State topic | Key | Type | Owner spec | Consumers |
|---|---|---|---|---|
| `waver.entitlement.entitlement-snapshot.v1` (MICROSERVICES_MAP) | customer `orgId` | `waver.entitlement.EntitlementSnapshotUpdated.v1` (full EntitlementSet, `snapshotVersion`) | RFC-0013 §8 | agent-runtime, tool-runtime-service, channel-service, knowledge-service, usage-metering-service |
| `waver.usage-metering.quota-state.v1` (MICROSERVICES_MAP) | `quotaCounterId` | `waver.usage-metering.QuotaStateUpdated.v1` | RFC-0020 | agent-runtime, tool-runtime-service, channel-service |
| `waver.agent-builder.deployment-state.v1` (proposed, used by RFC-0011; not yet in MICROSERVICES_MAP) | `deploymentId` | `waver.agent-builder.DeploymentStateChanged.v1` | RFC-0016 | agent-runtime, channel-service |

- The snapshot is written through the same outbox transaction as the domain change. A revoked or ended item stays in the snapshot with its terminal status. A tombstone (null value) is written only when the owning Organization is purged (RFC-0003 purge, RFC-0007): the outbox row has `payload = NULL` and the router emits a tombstone (`route.tombstone.on.empty.payload`).
- `quota-state` remains the durable read-model source for the data plane. The near-exact, real-time quota check is done against Redis counters owned by usage-metering-service (ADR-0020, RFC-0020); the state topic and the local `quota_state` read model are the fallback when Redis is unavailable.
- Topic settings: `min.compaction.lag.ms` 1 h, `delete.retention.ms` 24 h, `segment.ms` 1 day. State topics carry no PII.
- Rebuild: a consumer resets its projector group to the earliest offset and rebuilds its read-model tables (RFC-0007). No control-plane API call is needed. Read models that have no state topic bootstrap through the owner's snapshot API and then catch up from the event topic (RFC-0005 §7). Their topic retention (≥ 14 days) must exceed the maximum rebuild time.

### 12. PII and tenant context in events
- Events carry IDs and the minimal state consumers need (CONVENTIONS). Names, e-mails, phone numbers, document numbers, message text, prompts and tool payloads are PII or restricted content and are allowed only on topics classified `RESTRICTED`, only in Avro fields marked `x-waver-pii: true`, and only when every consumer is entitled (catalog). PII is never placed in the key or in any `ce_*` header.
- Restricted topics have short retention (3 days), dedicated ACLs and are excluded from generic tooling output (replay CLI masks `data`).
- LGPD erasure: deletion propagates on Kafka through retention expiry; compacted topics never hold PII, so no tombstone hunt is needed for erasure. The registry holds schemas only, never data.
- Consumers derive tenant context **only** from the headers (`ce_orgid`/`ce_workspaceid`/`ce_domainid`) of an event produced by the topic's owner (guaranteed by ACLs: the owner's Debezium connector is the only writer with the owner's principal). They then set it transaction-locally before touching tenant tables (RFC-0007). Some facts span two Organizations, for example a reseller-issued contract for a customer. For those, the Avro schema declares which field (e.g., `customerOrgId`) is the counterparty tenant (`x-waver-tenant-ref: true`), and only that declared field may be used. Values in other payload fields are never trusted. An event whose `ce_orgid` contradicts known state (e.g., aggregate already owned by another org) goes to the DLQ and raises a security alert.
- Platform-wide events (catalog, marketplace, tool-registry) use the PLATFORM organization ID as `ce_orgid`.

### 13. Audit transport
audit-service is consumer-only (MICROSERVICES_MAP), so it is never called synchronously (RFC-0002). Audit data reaches it on two paths:
1. **Explicit audit records.** Every service writes audit records through its own outbox, using the starter `AuditRecorder` (RFC-0001), to the dedicated topic `waver.audit.audit-record.v1` with type `waver.audit.AuditRecorded.v1`. This covers sensitive actions that do not change an aggregate or need extra context: reads under a support grant, cross-tenant queries (RFC-0007), credential use and leases, data exports, denied authorization on `x-waver-audit` endpoints, break-glass and replay-tool runs.
2. **Domain events.** audit-service also consumes the domain topics marked "audit" in EVENT_CATALOG (contract, entitlement, customer suspension, billing, identity, connector credential, approval, ...). Each one becomes an AuditEvent with the actor headers.

| Aspect | Rule |
|---|---|
| Key | `auditRecordId` (UUIDv7). The hash chain is built by audit-service per Organization in ingestion order, so it does not depend on Kafka ordering |
| `ce_orgid` | Organization whose resource was acted on. `ce_actorid`, `ce_actortype` and `ce_supportgrantid` identify the actor |
| `ce_source` | Producing service (`/waver/<service>`). audit-service stores it as given |
| `data` | `action` (permission key `<context>:<resource>:<action>`, RFC-0004), `resourceType`, `resourceId`, `outcome` (`SUCCEEDED`/`DENIED`/`FAILED`), `reason`, `changes[]` (field, before, after; only for fields classified Confidential or below), `requestId` |
| Ownership | audit-service owns the schema (`contracts/events/audit/AuditRecorded.v1.avsc`, subject `waver.audit.audit-record.v1-br.com.waver.events.audit.v1.AuditRecorded`). This topic is the only one with many writers |
| ACL | WRITE for every service principal on this topic only (used by each service's own Debezium connector); READ for `svc-audit-service` only |
| Consumer profile | UNORDERED with retry topics; never dropped (DLQ page alert) |
| Retention | 14 days (audit-service is the system of record) |

The outbox gives at-least-once delivery, and audit-service deduplicates on `ce_id`. An audit record is therefore never lost when Kafka, Kafka Connect or audit-service is down (the WAL is retained by the slot), which a synchronous call would risk.

## APIs / Events / Data Ownership
- Event types, topics, producers and consumers: `07_API_EVENTS/EVENT_CATALOG.md` (owned by this RFC; changes go through PR review by the producing service and Architecture).
- Schemas: `contracts/events/<context>/<EventName>.v<N>.avsc` owned by the producing service; registry subjects are derived from them and written only by CI.
- Tables owned by the platform starter inside each service DB: `outbox_event`, `debezium_heartbeat` (producers), `processed_event` (consumers), plus publication `waver_outbox` and replication slot `waver_<svc>_outbox`. They follow the service's DB ownership (AR-002); no service reads another's outbox.
- Platform-owned runtime components (ADR-0024): Apicurio Registry (per environment), Kafka Connect cluster with the Debezium PostgreSQL connector plugin (per environment), connector declarations in `deploy/kafka-connect/connectors/`.
- Topic ownership = producing service in MICROSERVICES_MAP. Consumers own their groups, retry handling and read models.

## Security & Privacy
- Transport: TLS 1.2+ (prefer 1.3) for clients and inter-broker traffic, kept even inside the mesh because brokers may be a managed service outside it. Client authentication: SASL/SCRAM-SHA-512 or mTLS, depending on the managed Kafka chosen (open); one principal per service (`svc-<service>`). Inside the cluster, traffic between pods (services, Kafka Connect workers, Apicurio, PostgreSQL proxies) is additionally protected by Istio ambient STRICT mTLS and AuthorizationPolicies (ADR-0026).
- Credentials: Kafka principals, registry client credentials and `<svc>_cdc` passwords are issued and rotated by Vault and delivered by the Vault Secrets Operator (ADR-0022); no credential in Git, Helm values or connector configs.
- **Debezium produces as the owner**: Kafka Connect runs with `connector.client.config.override.policy=Principal`; each connector overrides its producer SASL credentials with the owning service's principal. The Connect workers' own principal (`svc-kafka-connect`) only has rights on the `_waver-connect-*` topics. The single-writer rule of §1 therefore holds at the ACL level.
- ACLs (declared in `deploy/kafka/acls/<service>.yaml`, deny by default):

| Principal | WRITE | READ | GROUP |
|---|---|---|---|
| `svc-<owner>` (used by its Debezium connector) | its own topics `waver.<context>.*` (prefix) + `waver.audit.audit-record.v1` | — | — |
| `svc-<consumer>` | `<topic>-retry-*` and `<topic>.dlq` of consumed topics | explicitly listed topics + their retry/DLQ | `<consumer-service>.` prefix |
| `svc-audit-service` | retry/DLQ of consumed topics | `waver.audit.audit-record.v1` + domain topics marked "audit" in EVENT_CATALOG | `audit-service.` |
| `svc-kafka-connect` | `_waver-connect-*` | `_waver-connect-*` | Connect group |
| `ops-replay` (Job) | retry topics | DLQ topics | `ops-replay.` |

- **CDC role**: logical decoding is not subject to RLS, so `<svc>_cdc` sees every tenant's outbox rows; it is limited to the publication's tables, used only by the connector, and any replication slot not declared in `databases.yaml` raises a security alert (RFC-0010).
- **Kafka Connect REST API** is reachable only by the deployment pipeline identity (Istio AuthorizationPolicy); it never exposes secret values because configs reference files.
- **Registry**: authentication through Keycloak; services read-only; writes only from the CI release identity; deletion disabled in staging/production; Apicurio audit log shipped to the security log stream (RFC-0009).
- No human principal has WRITE on domain topics in staging/production. Encryption at rest is provided by the managed platform (open).

## Reliability & Failure Modes
| Failure | Effect | Handling |
|---|---|---|
| Kafka unavailable | Outbox rows accumulate; WAL retained by the slot; no event loss | Connect retries; alert on CDC lag and slot WAL retained; services keep serving writes. |
| Kafka Connect worker or connector task down | Publishing paused; WAL retained | Distributed mode rebalances the task to another worker; task FAILED pages; on restart Debezium resumes from the slot's confirmed LSN; possible re-sends deduplicated by inboxes. |
| Connect crash after produce, before LSN confirm | Some events re-sent | Inbox dedup on consumers. |
| Replication slot lag grows (WAL accumulation) | Primary disk pressure on the shared cluster | Alert at 50% / page at 80% of `max_slot_wal_keep_size`; heartbeat prevents idle-slot growth; runbook: restore the connector or, as last resort, drop the slot (then republish). |
| Replication slot lost (failover without slot sync, or invalidated by `max_slot_wal_keep_size`) | Events committed after the last confirmed LSN are not published | Runbook: recreate slot and connector (`snapshot.mode=no_data`), then `outbox republish --since <last confirmed time − margin>`; consumers dedupe. |
| Apicurio Registry unavailable | Running producers/consumers unaffected (cached IDs and schemas); new pods fail readiness; consumers meeting an uncached schema ID retry; CI registration blocked | HA deployment; retries; page if unavailable > 5 min. |
| Schema not registered for a new producer version | Producer pods never become ready | Pipeline order (schema before producer); rollback keeps the previous version serving. |
| Consumer bug/poison message | Partition blocked (ORDERED) or record skipped (UNORDERED) | Bounded retries → DLQ → alert → fix → `dlq redrive`. |
| Version gap in ORDERED projection | Out-of-order state risk | Park in DLQ, alert, redrive in order after fix. |
| Consumer lag in data plane | Stale entitlements/deployments | Lag alert; staleness SLO (RFC-0009); data plane keeps last known state (AR-013). |
| Topic deleted/corrupted | Consumers miss events | Recreate from code; `outbox republish` (7 days); state topics re-seeded by owner `state republish` job. |
| DB restored to an earlier point (RFC-0007) | Consumers saw events the producer no longer has; slot position invalid | Recreate slot; owner runs reconciliation job and publishes compensating facts; never deletes events. |
| Schema-incompatible change | Would break consumers | Blocked by CI compatibility checks and by the registry rule at registration. |
| Malformed outbox row (e.g., missing header column) | Connector task stops (`errors.tolerance=none`) | Page; fix row through a service migration/operator endpoint; never skip silently. |

## Observability
- Metrics (Micrometer, RFC-0009): `waver.outbox.insert{type}` (starter), `waver.outbox.rows` (table size gauge), CDC publish lag per connector (Debezium `MilliSecondsBehindSource`, exported as `waver.outbox.publish.lag`), Debezium `Connected` and Connect task status per connector, replication slot lag in bytes and WAL retained per slot (`pg_replication_slots`, RFC-0007), consumer `records-lag-max` per group/partition, `waver.event.handle.duration{type,group,outcome}`, `waver.event.end_to_end.latency{type}` (consumer time − `ce_time`), `waver.event.dlq.published{topic,group}`, `waver.event.skipped{type,group}` (type filter), `waver.inbox.duplicates`, registry lookups and cache misses `waver.schema.registry.lookup{outcome}`.
- Default alerts: CDC publish lag > 5 s for 5 min (warn) / > 60 s (page for tier-0/1 producers); connector or task not RUNNING > 2 min (page); slot WAL retained > 50% (warn) / 80% (page) of `max_slot_wal_keep_size`; undeclared replication slot (security page); lag > 10 000 records or > 2 min for 10 min on data-plane groups; any DLQ write (ticket), > 10 DLQ writes in 5 min (page); state-topic projector lag > 60 s; registry unavailable > 5 min (page).
- Tracing: `ce_traceparent`/`ce_tracestate` are captured at outbox insert (the producer span `outbox.insert` in the use case) and copied to headers by the Event Router. Kafka Connect creates no WAVER spans; consumer spans are children of, or linked to, the producer context (RFC-0009).

## Testing Strategy
- Contract tests generated from schemas: producer serializes sample events with the generated classes and validates them against the `.avsc`; consumers run handlers against schema examples, including records written with older schema versions (backward) and newer ones (forward).
- CI compatibility checks on every change under `contracts/events` (rules §7) and PII/consumer check against the catalog.
- Testcontainers integration tests in the platform starter with PostgreSQL (`wal_level=logical`), Kafka, Kafka Connect with Debezium and Apicurio Registry: ordering per aggregate under concurrent writers, header mapping, tombstones, duplicate delivery, retry/DLQ paths, redrive, republish, state-topic rebuild, slot loss and recovery.
- Service-level tests use a starter **test publisher** that reads `outbox_event` and produces records with the same key/value/header mapping as the Event Router, so service suites do not need Kafka Connect; the starter suite proves the two mappings are identical.
- Chaos test in staging: broker restart, Kafka Connect worker kill and registry outage during load; assert no loss, bounded lag and no duplicate side effects.

## Deployment / Rollout / Rollback
- Topics, ACLs, schemas (registry) and the service's Debezium connector are applied by the pipeline before the service release that needs them; removal only after the catalog shows no producer/consumer.
- Order for a new event type: consumers (tolerant of the new type) → schema registration → producer. Producers dual-publish during major migrations.
- Rollback of a producer never removes an event type that has consumers and never deletes a registered schema; rollback of a consumer is safe (offsets and inbox are forward-compatible).
- Migration from the v1.2 polling relay (if any environment ran it): stop the relay with the outbox drained, apply the new outbox migration, create slot and connector, deploy; the `published_at`/`shard` columns are dropped in the contract phase (RFC-0007 expand/contract).
- Kafka Connect, Debezium and Apicurio upgrades follow the platform runbook (staging soak, one environment at a time).

## Alternatives Considered
- JSON payloads + JSON Schema in the repo + polling outbox relay (ADR-0012, v1.2 design): fewer components, but no runtime compatibility enforcement and polling latency/DB load; superseded by ADR-0024.
- Confluent Schema Registry: equivalent features; Apicurio chosen for its license and Confluent-compatible API (ADR-0024).
- Protobuf: good option; Avro chosen for its schema-evolution model and Kafka Connect ecosystem (ADR-0024).
- CloudEvents structured mode with an Avro envelope: every subject would embed the envelope and tooling would have to decode the value to filter; binary mode lets consumers and tools route on headers.
- `TopicNameStrategy` with one union schema per topic: couples all event types of a topic into one subject; `TopicRecordNameStrategy` chosen.
- Services producing directly to Kafka after commit (dual write): loses events on crash; outbox + CDC chosen.
- Key by `orgId`: gives tenant ordering but creates hot partitions and couples unrelated aggregates.
- Request/response over Kafka: rejected; REST covers synchronous needs (ADR-0004).
- Rebuilding read models through owner APIs: violates AR-013 when the control plane is down; compacted state topics chosen instead.

## Risks
- Replication slots retain WAL on the shared PostgreSQL cluster; a stopped connector can fill the primary's disk. Mitigated by heartbeat, `max_slot_wal_keep_size`, alerts and the slot-loss runbook.
- Slot behaviour on failover depends on the PostgreSQL version and the managed offering (open).
- Kafka Connect becomes tier 0 for data-plane events (e.g., `MessageReceived` on the conversational path); sized and monitored as such (RFC-0009).
- Binary payloads make ad-hoc debugging harder; only registry-aware tools may be used (replay CLI).
- Shared retry topics cause extra reads across groups (filtered by header).
- Partition increases are disruptive; initial sizing must be generous.
- Teams bypassing the outbox with a direct `KafkaTemplate.send`: ArchUnit rule forbids it outside the starter.

## Open Questions
1. ~~Context tokens differ across documents~~ — resolved in the v1.2 review: the EVENT_CATALOG §1 table is the standard (CONVENTIONS updated); RFC-0002 and all RFCs aligned.
2. Should retry/DLQ topics be scoped per consumer group (`<topic>.<group>.dlq`) instead of shared with a header filter? Requires a CONVENTIONS change.
3. ~~ADR-0012 amendment for `aggregateversion`, `supportgrantid`, `traceparent` and the inbox `consumer_group` column~~ — resolved by ADR-0024 (extensions listed as headers; inbox `processed_event(consumer_group, event_id)`). `tracestate` stays optional.
4. Managed Kafka offering and client auth mechanism (SASL/SCRAM vs mTLS) depend on the cloud decision, as does whether Kafka Connect and the registry stay self-hosted.
5. Add `waver.agent-builder.deployment-state.v1` (used by RFC-0011) to MICROSERVICES_MAP next to the two listed state topics, or replace it with snapshot-API bootstrap.
6. The audit topic accepts writes from every service. audit-service cannot cryptographically verify which service produced a record. Vault Transit (ADR-0022) now makes per-service signing practical (key `audit-<service>`, signature in an `x-waver-signature` field, verified by audit-service); decide whether the cost of one Transit call per audit record is justified (residual risk, RFC-0010 T21b).
7. Failover of logical replication slots: PostgreSQL failover slots require a recent major version and support from the managed offering; until confirmed, every primary failover triggers the slot-recreation runbook.

## Decision
Pending review. Proposed: adopt the conventions, defaults, registry rules, Debezium outbox configuration and platform-starter components above for all services; EVENT_CATALOG becomes the registry of record for topics and event types.
