# ADR-0017 - Model Gateway for LLM Provider Abstraction
Status: PROPOSED
Date: 2026-10-19

## Context
Scope §22 requires provider abstraction for multiple LLM vendors, cost control and fallback; §24 leaves model routing and customer-provided keys open. The current service map has no owner for this, so every agent-plane service would call providers directly.

## Decision
Add **model-gateway-service** (Agent Plane):
- Single egress point to LLM and embedding providers; provider adapters behind ports (`ChatModelPort`, `EmbeddingPort`). Spring AI may be used inside adapters only, never in domain code.
- Responsibilities: model catalog (models, capabilities, prices), routing and fallback rules, timeouts and retries, streaming, token counting, cost calculation, per-tenant budgets (with usage-metering-service), redaction hooks and **BYOK** (customer keys stored as connector-service credentials of kind `LLM_PROVIDER_KEY`, obtained through the short-lived, audited credential lease defined in ADR-0014).
- Emits `ModelCallCompleted` events (tokens, cost, latency, model, provider) — the source of truth for AI cost of goods sold.
- Agent definitions reference **model profiles** (`fast`, `balanced`, `high-quality`, plus advanced overrides) instead of raw vendor model IDs, matching the "hide LLM parameters" product principle.

## Alternatives
- Each service calls providers directly: duplicated keys, no cost control, no fallback.

## Consequences
- One more in-cluster hop on the hot path, mitigated with streaming passthrough.
- Changing a model becomes configuration, not code.

## Compliance / Verification
- NetworkPolicy: only model-gateway-service has egress to LLM provider endpoints.

## Supersedes / Superseded by
Closes the architecture part of "Model routing".
