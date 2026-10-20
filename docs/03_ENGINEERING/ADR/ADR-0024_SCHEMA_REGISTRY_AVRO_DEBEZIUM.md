# ADR-0024 - Schema Registry, Avro and Debezium Outbox
Status: PROPOSED
Date: 2026-10-19
Supersedes: ADR-0012 (schema format and outbox relay); the CloudEvents envelope and inbox rules are kept

## Context
ADR-0012 used JSON payloads with JSON Schema files checked in CI and a polling outbox relay. The product owner decided (2026-10-19) to adopt a runtime schema registry, Avro and Debezium.

## Decision
- **Schema registry: Apicurio Registry** (Apache 2.0), deployed per environment with its own PostgreSQL storage, exposing the Confluent-compatible API (so managed Kafka offerings and standard serializers work). Compatibility rule per subject: `BACKWARD_TRANSITIVE` within a major version.
- **Payload format: Avro.** Schemas (`.avsc`) live in the monorepo under `contracts/events/<context>/<EventName>.v<N>.avsc` (source of truth, reviewed like code) and are registered by the CI pipeline, never by services at runtime (`auto.register.schemas=false`).
- **Envelope: CloudEvents 1.0 in binary mode** on Kafka: CloudEvents attributes and the WAVER extensions (`orgid`, `correlationid`, `causationid`, `actorid`, `actortype`, conditional `workspaceid`, `domainid`, `supportgrantid`, plus `aggregateversion`, `traceparent`) travel as Kafka headers (`ce_*`); the Kafka value is the Avro-encoded `data`.
- **Outbox relay: Debezium** PostgreSQL connector running on **Kafka Connect** (distributed mode, per environment), with the **Outbox Event Router** SMT. Each producing service keeps its `outbox_event` table (now with the Avro payload and CloudEvents header columns); Debezium reads the WAL via logical replication and publishes to the topic named in the row. The polling relay is removed.
- **Outbox retention**: published rows stay in `outbox_event` for 7 days (default, configurable per service) and are then deleted by a scheduled job, so a lost or recreated replication slot can be recovered by re-publishing from the table (runbook `RUNBOOK_DEBEZIUM_REPLICATION_SLOT.md`; consumers are idempotent).
- **Consumers**: unchanged rules — inbox table `processed_event(consumer_group, event_id)`, Spring Kafka retry topics and DLQ; deserialization through the registry-aware Avro deserializer with specific records generated at build time.
- Topic and type naming (CONVENTIONS, EVENT_CATALOG) are unchanged.

## Alternatives
- Keep ADR-0012: fewer components, but weaker runtime compatibility enforcement and polling latency.
- Confluent Schema Registry: equivalent features; Confluent Community License restricts some uses. Apicurio chosen for its license and Confluent-compatible API; switching is a configuration change.
- Protobuf: good option; Avro chosen for its schema-evolution model and Kafka Connect ecosystem.

## Consequences
- Lower publish latency and no polling load on databases; logical replication slots must be monitored (WAL growth if Kafka Connect stops).
- New operational components: Apicurio Registry, Kafka Connect + Debezium.
- Event payloads are binary: tooling (DLQ replay, debugging) must use registry-aware tools.
- PostgreSQL `wal_level = logical` and one replication slot per producing database.

## Compliance / Verification
- CI: `.avsc` compatibility check against the registry before merge; registration at release.
- Alert on replication slot lag and Kafka Connect task failures (runbook update).

## Supersedes / Superseded by
Supersedes the format and relay parts of ADR-0012.
