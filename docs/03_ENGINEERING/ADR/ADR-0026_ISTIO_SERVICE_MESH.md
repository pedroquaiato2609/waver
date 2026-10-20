# ADR-0026 - Service Mesh: Istio (Ambient Mode)
Status: PROPOSED
Date: 2026-10-19

## Context
v1.2 left the service mesh out of v1, with mTLS between services as an open point (RFC-0010, RFC-0015). The product owner decided (2026-10-19) to adopt a service mesh from the start.

## Decision
**Istio in ambient mode** (CNCF graduated, Apache 2.0) on every cluster.
- **mTLS everywhere** via ztunnel (L4), with workload identities derived from Kubernetes service accounts. `PeerAuthentication` STRICT in all `waver-*` namespaces.
- **Authorization**: `AuthorizationPolicy` per destination service lists the allowed source service accounts — generated from the allowed synchronous dependency graph (RFC-0002 §4). NetworkPolicies remain as a second, L3/L4 layer.
- **Waypoint proxies (L7)** only where L7 features are needed: canary/traffic splitting for data-plane services (RFC-0014), request-level authorization on sensitive services (connector-service, identity-access-service, model-gateway-service).
- **Egress**: an Istio egress gateway with `ServiceEntry` allow-lists per destination host for the services allowed to reach the internet (model-gateway, connector, channel) and the restricted ones (Keycloak → customer IdPs for SSO brokering, billing-service → payment/fiscal providers). Host-level allow-listing moves from application code to the mesh (application SSRF checks remain for connectors). Destinations supplied by tenants (Custom API connectors, customer webhook targets) cannot be listed in advance: they use a dedicated egress route on the egress gateway reachable only from connector-service, with SSRF validation in the application and egress logging (RFC-0018).
- **Resilience**: retries and timeouts stay in the application (Resilience4j, RFC-0005). Mesh retries are **disabled by default** to avoid retry amplification; mesh timeouts only as an outer safety net.
- **Observability**: mesh telemetry (L4/L7 metrics, access logs) exported to the same observability backend; tracing headers propagated by applications as before.

## Alternatives
- No mesh (v1.2 position): fewer components, no automatic mTLS.
- Linkerd: simpler, but stable releases now require a commercial license.
- Istio sidecar mode: more features per pod, higher resource cost; ambient chosen for lower overhead.

## Consequences
- Zero-trust transport between all services without application code.
- Additional platform components (istiod, ztunnel DaemonSet, waypoints, egress gateway) owned by the platform team; upgrade runbook.
- Debugging needs mesh-aware tooling (istioctl).

## Compliance / Verification
- CI check: every service chart ships its `AuthorizationPolicy` generated from declared dependencies.
- Staging test: a call outside the dependency graph is denied by the mesh.

## Supersedes / Superseded by
Closes "service mesh" in TECH_STACK; amends RFC-0010 and RFC-0015.
