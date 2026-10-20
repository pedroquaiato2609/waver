# Runbook - Kafka Consumer Lag and DLQ
Service: any Kafka-consuming service (worked example uses entitlement-service consuming `waver.contract.contract.v1`)
Owner: owning team of the consuming service (CODEOWNERS); platform team for the Kafka cluster
SLO: consumer-group lag drains within 60 s at p99 for T0/T1 consumers, 5 min for T2; DLQ receives 0 messages in steady state
Alerts: `KafkaConsumerLagHigh`, `KafkaConsumerLagGrowing`, `KafkaDlqMessages`
Tier: per consuming service (RFC-0014 §11)
Last reviewed: 2026-10-19
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Avro payloads with Apicurio Registry, CloudEvents binary-mode headers, Debezium outbox relay); DLQ inspection and replay are registry-aware.

> Worked example of RUNBOOK_TEMPLATE. Names of consumer groups and admin endpoints are illustrative until fixed by RFC-0006; commands use the Apache Kafka CLI, kubectl and the registry-aware tools in `tooling/` only. Event values are Avro binary (Confluent-compatible wire format: magic byte + schema ID, ADR-0024) and CloudEvents attributes travel as `ce_*` headers; plain console consumers show headers but not a readable value. `$BOOTSTRAP`, `$REGISTRY` (Apicurio Registry, Confluent-compatible API of the same environment) and the client config file come from the on-call access procedure (credentials from Vault); never paste credentials into the incident channel.

## Symptoms
- `KafkaConsumerLagHigh`: lag of group `entitlement-service` above 1,000 records for 5 min, or oldest unprocessed record older than the SLO.
- `KafkaConsumerLagGrowing`: lag increasing for 10 min while producer rate is normal.
- `KafkaDlqMessages`: any message in `waver.contract.contract.v1.dlq` (or another `<topic>.dlq`).
- Business symptoms: contracts activated but entitlements not granted, provisioning delayed, Workspace features not appearing.

## Impact
- Downstream effects are delayed, not lost (outbox + at-least-once). Lag on entitlement, provisioning or billing consumers delays customer activation and invoicing; lag on agent-runtime or channel-service delays conversations (SEV1 if > 5 min for all tenants).
- DLQ messages are effects that will not happen until replayed: treat as data-integrity risk.
- If only one partition lags, only the aggregates hashed to it (a subset of tenants) are affected.

## Dashboards / Alerts
- Consumer lag per group and partition; consumption vs production rate per topic.
- Service golden signals filtered by version label (did a deployment just happen?).
- Retry-topic (`<topic>-retry-<n>`) and DLQ message counts; inbox duplicate counter (AR-006).
- Database dashboards of the consuming service (connections, lock waits, slow queries).
- Deserialization error count per consumer (unknown schema ID, incompatible reader schema) and Apicurio Registry availability/latency.
- Producer side: Debezium connector status and replication-slot lag of the producing database (RUNBOOK_DEBEZIUM_REPLICATION_SLOT).

## Immediate mitigation
1. If a deployment of the consumer happened in the last hour and errors rose: run the pipeline `rollback` job for the service (RFC-0014 §7).
2. If the consumer is healthy but slow (CPU-bound, no errors): scale out, up to the partition count.
   ```bash
   kubectl -n waver-commercial scale deployment/entitlement-service --replicas=6
   ```
   Undo: let the HPA reconcile (`kubectl -n waver-commercial get hpa entitlement-service`).
3. If messages are failing and flooding retry topics, leave them to flow to the DLQ; do not disable retries. The partition keeps moving because poison messages are parked (AR-014).
4. If a downstream dependency (e.g., its database) is saturated, reduce pressure by scaling back to the previous replica count rather than adding consumers.

## Diagnosis
1. Lag shape per partition:
   ```bash
   kafka-consumer-groups.sh --bootstrap-server $BOOTSTRAP --command-config client.properties \
     --describe --group entitlement-service
   ```
   - All partitions lagging → capacity, dependency or code issue. One partition lagging → hot key or a blocking message on that partition.
   - `CONSUMER-ID` empty → no active member: pods crashing or rebalancing; go to step 2.
2. Pod health and rebalances:
   ```bash
   kubectl -n waver-commercial get pods -l app.kubernetes.io/name=entitlement-service
   kubectl -n waver-commercial logs deploy/entitlement-service --since=15m | grep -E "rebalanc|Revoked|ERROR"
   ```
   Frequent rebalances → processing exceeds `max.poll.interval.ms`, or OOM kills (check `kubectl describe pod` for `OOMKilled`).
3. Errors: filter logs by the event `ce_id`/`ce_correlationid` from the DLQ headers; check traces for the slow span (database, downstream HTTP, lock wait).
4. DLQ content (read-only, a few records). Headers are readable with the plain console consumer:
   ```bash
   kafka-console-consumer.sh --bootstrap-server $BOOTSTRAP --consumer.config client.properties \
     --topic waver.contract.contract.v1.dlq --from-beginning --max-messages 5 \
     --property print.key=true --property print.headers=true --property print.value=false
   ```
   Decode values only with the registry-aware inspector (resolves the schema ID against `$REGISTRY`, prints the record as JSON, masks fields marked `x-waver-pii`):
   ```bash
   tooling/event-inspect --topic waver.contract.contract.v1.dlq --registry $REGISTRY --max-messages 5
   ```
   Classify: deserialization error (schema ID unknown in this environment's registry, or the consumer's reader schema incompatible with the writer schema → producer/registration bug or incompatible change), missing `ce_orgid` header (AR-008), business rule rejection, transient dependency failure that exhausted retries. The Spring Kafka DLQ headers (`kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`, original topic/partition/offset) tell which case applies.
5. Schema checks for deserialization errors:
   ```bash
   curl -s $REGISTRY/schemas/ids/<schemaId>                       # does the writer schema exist here?
   curl -s $REGISTRY/subjects/waver.contract.contract.v1-value/versions   # registered versions
   ```
   An ID that exists in `staging` but not in `production` means the producer was deployed without the registration step (RFC-0014 §2): stop and escalate to the producer owner.
6. Producer side: compare producer rate with normal; a burst (e.g., bulk contract migration) explains lag without a fault. If no events arrive at all, check the producer's Debezium connector (RUNBOOK_DEBEZIUM_REPLICATION_SLOT).

## Recovery
- Capacity: keep the scaled replica count, open a ticket to adjust HPA `maxReplicas`/partitions (RFC-0015 §5). Partition increases change key-to-partition mapping; plan them, never during an incident.
- Code bug: fix forward through the expedited pipeline; then replay the DLQ.
- Producer bug or incompatible event: roll back the producer; fix; replay. Check why the CI compatibility check missed it (AR-015).
- Transient dependency failure: once the dependency is healthy, replay the DLQ.
- Deserialization failure: never re-encode or hand-edit an Avro payload. Fix the consumer (reader schema/generated classes) or register the missing schema through the producer's pipeline, then replay.
- DLQ replay (registry-aware tool proposed in `tooling/`; until it exists, the owning team uses an equivalent reviewed script): republish each DLQ record to the original topic **byte for byte** (same key, same Avro value with its schema ID, same `ce_*` headers including `ce_id`), stripping only the `kafka_dlt-*` headers; idempotent consumers skip already-processed IDs (AR-006). Before publishing, the tool verifies that each record's schema ID exists in the target environment's registry and that the consumer group's current reader schema can read it; records that fail are reported and left in the DLQ.
  ```bash
  tooling/dlq-replay --topic waver.contract.contract.v1.dlq --target waver.contract.contract.v1 \
    --registry $REGISTRY --filter-type waver.contract.ContractActivated.v1 --dry-run   # then without --dry-run
  ```
  `--filter-type` matches the `ce_type` header. Replays run under the platform replay identity (Kafka ACL limited to republishing to the original topic) and are audited.
- Recovery is confirmed when lag stays under the SLO for 30 min and the DLQ count of replayed messages is zero.

## Rollback
- Service: pipeline `rollback` job to the previous digest. Consumer offsets are not reset by a rollback.
- Never reset consumer-group offsets backwards in production without the owning team and incident commander: replays rely on idempotency but may re-trigger external side effects of non-idempotent integrations.
- Never delete DLQ topics or records; they are evidence and the source for replay.

## Data integrity checks
- Every DLQ record replayed is processed exactly once: `processed_event` contains its `ce_id`; inbox duplicate counter rose by the number of already-processed records only.
- Reconcile a sample: for each replayed `ContractActivated`, the expected `Entitlement` rows exist for the same `org_id`.
- No cross-tenant writes: replayed events set tenant context from the `ce_orgid` header (AR-008); check that no event without `ce_orgid` was force-processed.
- Producer relay is back to normal: the producing database's Debezium connector is `RUNNING` and its replication-slot lag is under threshold (RUNBOOK_DEBEZIUM_REPLICATION_SLOT).

## Escalation
- Not mitigated within 30 min, or T0/T1 data-plane consumer affected for all tenants → page platform/SRE and the incident commander.
- Kafka broker issues (under-replicated partitions, broker down) → platform team owning the Kafka cluster (managed provider support once chosen).
- Apicurio Registry unavailable or schema IDs missing in an environment → platform team owning the registry and the producer's owner.
- Any sign of events processed under the wrong tenant → Security immediately.
- Activations or invoices delayed beyond reseller SLAs → commercial operations for reseller communication.

## Post-incident actions
- Incident record and blameless review (SEV1/SEV2) within 5 working days.
- Add a regression test (consumer contract test or duplicate-delivery test) for the failing event; for deserialization failures, find out why the CI Avro compatibility check or the registration step did not stop it (STD-012).
- Tune alert thresholds, HPA limits or partition count with a ticket and owner.
- Update this runbook with any new diagnosis branch.
