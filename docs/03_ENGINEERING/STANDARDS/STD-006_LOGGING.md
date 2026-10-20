# STD-006 - Logging Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Temporal, HashiCorp Vault, Keycloak, Istio ambient, Redis, Debezium).
Version: 1.3
Date: 2026-10-19
Applies to: every Java service, BFF, edge-gateway, batch job and Temporal worker; log configuration of the platform components (Keycloak, Vault audit device, Istio, Kafka Connect/Debezium, Temporal); browser apps only for client error reporting through their BFF
Related: AR-008, AR-009, ADR-0021, ADR-0022, ADR-0023, ADR-0024, ADR-0026, RFC-0004, RFC-0007, RFC-0009 (§2, §9, §10), STD-001, STD-007, STD-008, STD-009, SECURITY_BASELINE (LGPD)

Keywords MUST, MUST NOT, SHOULD follow RFC 2119. Logs are for operating and diagnosing the system. They are not an audit trail, not a metrics store and not a data store for conversations.

## 1. Format and transport
- **L-01** Logs are structured JSON, one object per line, UTF-8, written to stdout only. File appenders, multi-line output and `System.out`/`printStackTrace()` are forbidden.
- **L-02** The format is produced by Spring Boot structured logging configured in `waver-platform-starter`; services MUST NOT ship their own logging configuration except log-level overrides.
- **L-03** Logging API is SLF4J with parameterized messages. The domain module does not log (STD-001 J-07, J-25).

## 2. Mandatory fields
| JSON key | Source | Notes |
|---|---|---|
| `timestamp` | logger | ISO-8601 UTC with milliseconds |
| `level` | logger | `ERROR`, `WARN`, `INFO`, `DEBUG` |
| `logger`, `thread` | logger | class name; thread name (virtual threads included) |
| `message` | code | short, constant text + key=value pairs |
| `service` | `spring.application.name` | e.g., `contract-service` |
| `version` | build info | service release version |
| `env` | deployment | `dev`, `staging`, `production`, `preview-<n>` |
| `traceId`, `spanId` | Micrometer Tracing (MDC) | present even when the trace is not sampled |
| `correlationId` | `X-Correlation-Id` or event `correlationid` | generated at edge-gateway when absent |
| `orgId`, `orgType` | validated token or event `orgid` | empty only for platform-scoped work (startup, platform jobs) |
| `workspaceId`, `domainId` | token/event when scoped | |
| `principalId`, `actorType`, `supportGrantId` | token `sub`, `act`, `support_grant` / envelope | pseudonymous IDs only, never names or e-mails |
| `eventId`, `eventType` | event envelope | when handling an event |
| `agentRunId`, `toolExecutionId`, `modelCallId` | runtime context | when applicable (agent plane) |
| `workflowId`, `workflowRunId`, `workflowType`, `activityType`, `attempt` | Temporal context (platform worker interceptor) | in workflow and activity execution (ADR-0023) |
| `errorCode`, `exception` | STD-008 | on errors: `code`; exception class + sanitized stack trace only at `ERROR` |

- **L-04** The platform starter fills MDC for HTTP requests (security filter), Kafka records (consumer wrapper) and Temporal activities (worker interceptor, using the tenant/correlation context propagated in Temporal headers) and clears it afterwards. Context propagation to virtual threads and async executors uses Micrometer context propagation; custom `ThreadLocal` copying is forbidden. Workflow code logs only through `Workflow.getLogger(...)`, which suppresses duplicate output during replay (STD-001 J-34).

```json
{"timestamp":"2026-10-19T14:03:00.412Z","level":"INFO","logger":"br.com.waver.contract.application.service.ActivateContractService",
 "thread":"virtual-1873","message":"Contract activated contractId=01929f3e-6b1c-7c11-9a55-0d1f1c2a7b10 items=3",
 "service":"contract-service","version":"1.4.2","env":"production","traceId":"4bf92f3577b34da6a3ce929d0e0e4736",
 "spanId":"00f067aa0ba902b7","correlationId":"c0a8012e-5f3d-4e2b-9a1f-7d3e2c1b0a99",
 "orgId":"01928a00-0000-7000-8000-000000000101","actorType":"USER"}
```

## 3. Levels
| Level | Use | Examples |
|---|---|---|
| `ERROR` | A request, job step or message failed because of a server-side fault or an exhausted dependency; someone may need to act | unhandled exception → 500; record sent to DLQ; Temporal activity failed after its last retry; Vault lease renewal or Transit call failed |
| `WARN` | Degraded but handled; may become an error | retry attempt, circuit breaker opened, fallback model used, slow query, Redis unavailable and fallback active (ADR-0020) |
| `INFO` | Low-volume lifecycle facts | startup/shutdown, config summary (no secrets), aggregate state transitions, job start/end with counts |
| `DEBUG` | Diagnostics | branch decisions, payload **shapes** (sizes, field names), cache hits |

- **L-05** Client errors (4xx) are not `ERROR`: validation/business-rule rejections log at `DEBUG`, `403` and tenancy violations at `INFO` with `errorCode` (and an audit event when security relevant, STD-009).
- **L-06** Production runs at `INFO`. `DEBUG` may be enabled per logger, per service, for at most 24 h through a versioned, audited configuration change (RFC-0009), never by shipping code. `TRACE` is not used.
- **L-07** Per-request, per-token or per-message `INFO` logs are forbidden in data-plane hot paths (agent-runtime, channel, tool-runtime, model-gateway); use metrics (STD-007) and traces. edge-gateway and BFFs emit one access log per request (method, route template, status, duration, sizes) without query strings.

## 4. What must never be logged
- **L-08** Secrets and credentials: passwords, access/refresh/ID tokens, `Authorization`, `Cookie`, `Set-Cookie` headers, API keys, session IDs (including Redis session keys), OAuth codes, signing/encryption keys, connector credentials and their Transit plaintext (ADR-0014, ADR-0022), Vault tokens and lease IDs, Vault-issued database usernames/passwords, Keycloak client secrets, Redis ACL passwords, Qdrant API keys, webhook signatures. Transit ciphertext is not logged either (it is a credential reference, not diagnostic data).
- **L-09** Conversation and AI content by default: user messages, agent replies, prompts (including compiled system prompts), model inputs/outputs, retrieved knowledge chunks, memory entries, document contents, tool call arguments and results. Log IDs, sizes and counts instead. Diagnostic capture of content is a governed product feature of agent-runtime/evaluation (RFC-0011), stored in their databases with retention, never in logs. Temporal workflow payloads and Redis run streams are not a logging channel (STD-001 J-36).
- **L-10** Personal data (LGPD): names, e-mails, phone numbers, CPF/CNPJ, addresses, free-text fields, payment data, SQL bind values. Client IP addresses only at edge-gateway for security, with the retention of security logs. The starter's `SanitizingMessageConverter` masks known patterns (e-mail, phone, CPF/CNPJ, JWT, bearer tokens, key prefixes) as a safety net only; the rule is still "do not log it".
- **L-11** Full request/response bodies and full event payloads. Commercial confidential values (wholesale prices, margins) are not logged.
- **L-12** Records and classes that hold sensitive values MUST override `toString()` (records print all components by default) or use the platform `Sensitive<T>` wrapper whose `toString()` returns `***`.

```java
public record ConnectorCredentialInput(ConnectionId connectionId, Sensitive<String> apiKey) { }
log.info("Credential rotated connectionId={}", input.connectionId());   // never log input itself
log.warn("Model provider fallback used profile={} from={} to={} reason={}", profile, primary, fallback, reasonCode);
```

## 5. Audit vs log
| | Audit event (AR-009) | Log |
|---|---|---|
| Purpose | Who did what, to which resource, when, with which authorization | Operate and diagnose |
| Path | Domain event / outbox → Kafka → `audit-service` (append-only, hash-chained) | stdout → log pipeline |
| Reliability | Transactional with the change; never dropped | Best effort; may be sampled |
| Retention | Per audit policy (open; no automatic deletion until decided) | 30 days; security-relevant logs 90 days hot + 1 year archive (RFC-0009 §10) |
| Content | Actor, action, resource IDs, before/after of governed fields, reason | Technical context |

- **L-13** Sensitive actions (suspension, price override, credential use, support impersonation, purge, role changes) MUST produce audit events. Writing them only to logs is a violation. Logs MUST NOT be used as the source for audit reports or billing.

## 6. Volume and sampling
- **L-14** `ERROR` and `WARN` are never sampled. Repeated identical errors from the same cause SHOULD be rate-limited by the platform appender (first occurrence + periodic count).
- **L-15** `DEBUG` logs MAY be sampled by trace (only for sampled traces) when enabled in production.
- **L-16** A single log entry MUST NOT exceed 16 KB; the platform truncates longer messages and stack traces.
- **L-17** Each service has a log volume budget (default 50 GB/month in production) with an alert at 80% (RFC-0009).

## 7. Frontend
- **L-18** Browser apps do not ship logs to third parties. Unexpected client errors (including unhandled NgRx effect/`rxMethod` errors) are reported to their BFF (`POST /api/client-errors`) with route template, error type, `correlationId` and app version only (RFC-0021). No user input or page content is sent.

## 8. Platform components (ADR-0021..0026)
- **L-19** **Vault audit device** is enabled in every environment and shipped to the log backend with security-log retention (RFC-0009 §10). Vault's default HMAC of secret values in audit entries stays on; `log_raw` and disabling HMAC for request/response keys are forbidden. Vault audit entries are correlated with audit-service events by service identity and time, not by logging secrets.
- **L-20** **Keycloak** logs JSON to stdout; its login and admin event logging records user and client IDs, never passwords, OTP codes, tokens or full request bodies; usernames/e-mails are excluded from the log output where the event type allows. Identity events remain security logs (90 days hot + 1 year archive); they do not replace the audit events emitted by identity-access-service (L-13).
- **L-21** **Istio** (ztunnel and waypoint) access logs are infrastructure logs exported to the same backend with security-log retention; waypoint access-log formats omit query strings and request headers other than `traceparent`. Mesh denials (`AuthorizationPolicy`) are logged with source and destination identities so they can be alerted (STD-007).
- **L-22** **Kafka Connect/Debezium** and **Temporal** server logs are JSON to stdout and MUST NOT log record values or workflow payloads at `INFO` or above (Debezium value logging and Temporal payload logging stay disabled in staging and production).

## Enforcement
| Rule | Enforced by |
|---|---|
| L-01..L-04 format, MDC | `waver-platform-starter` logging auto-configuration; ArchUnit bans `System.out`, `System.err`, `printStackTrace`, direct Logback config classes |
| L-05..L-07 levels and volume | Code review; log-volume metric per service and alert on spikes (STD-007) |
| L-08..L-12 forbidden content | Log-capture test in `waver-platform-test` asserting no token/key/e-mail/CPF patterns during the service's integration tests; ArchUnit: no SLF4J logger in workflow classes; classes named `*Credential*`, `*Secret*`, `*Token*` must override `toString` or use `Sensitive`; security review triggers (STD-009) |
| L-13 audit separation | Review checklist for sensitive use cases; audit coverage tests (STD-010) |
| L-14..L-17 | Platform appender configuration; log-volume alert (STD-007) |
| L-18 frontend | BFF client-error endpoint schema allows only the listed fields |
| L-19..L-22 platform components | Platform configuration as code (`deploy/platform/*`) reviewed by security; config tests assert Vault audit HMAC on, Debezium/Temporal payload logging off, waypoint log format without query strings |

## Open Questions
1. Log backend is still open (TECH_STACK); retention values are RFC-0009 engineering defaults pending compliance confirmation.
2. Whether customers may enable time-boxed content logging for troubleshooting (with consent) is a product/LGPD decision; until decided, L-09 is absolute.
