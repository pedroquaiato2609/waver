# RFC Index (v1.3)

All RFCs are at revision 1.3 (aligned with ADR-0020..0027, see `00_START_HERE/CHANGELOG_v1.3.md`). All RFCs follow `11_TEMPLATES/RFC_TEMPLATE.md`. Status PROPOSED = complete design awaiting architecture review (STD-015). Each RFC ends with its own Open Questions; business questions are consolidated in `00_START_HERE/OPEN_DECISIONS.md`.

| RFC | Title | Plane / area | Key ADRs |
|---|---|---|---|
| RFC-0001 | WAVER Software Architecture | All | 0001–0027 |
| RFC-0002 | Domain & Service Boundaries (context map, glossary, sync graph) | All | 0002, 0015, 0019 |
| RFC-0003 | Multi-Tenant Architecture | Platform | 0010, 0020, 0023, 0025 |
| RFC-0004 | Identity & Access Management | Platform | 0021 |
| RFC-0005 | Inter-Service Communication | All | 0004, 0009, 0026 |
| RFC-0006 | Apache Kafka Event Architecture | All | 0012, 0024 |
| RFC-0007 | Data Ownership & Persistence | All | 0003, 0010, 0020, 0025 |
| RFC-0008 | API Architecture | Experience / all | 0009 |
| RFC-0009 | Observability Architecture | All | — |
| RFC-0010 | Security Architecture | All | 0010, 0021, 0022, 0026 |
| RFC-0011 | Agent Runtime Architecture | Agent (data) | 0017, 0019, 0020, 0023 |
| RFC-0012 | Tool Execution Architecture | Capability | 0014, 0016, 0022, 0023 |
| RFC-0013 | Contract & Entitlement Architecture | Commercial | 0006, 0023 |
| RFC-0014 | Deployment Architecture | DevOps | 0007, 0015, 0026 |
| RFC-0015 | Kubernetes Platform Architecture | DevOps | 0022, 0026 |
| RFC-0016 | Agent Builder Architecture | Agent (control) | 0005, 0019 |
| RFC-0017 | Commercial & Reseller Platform | Commercial | 0006, 0009, 0023 |
| RFC-0018 | Integration Hub and Connector SDK (**new**) | Capability | 0016, 0022, 0026 |
| RFC-0019 | Model Gateway (**new**) | Agent (data) | 0017, 0020, 0022 |
| RFC-0020 | Usage Metering & Billing (**new**) | Commercial / platform | 0020, 0023, 0024 |
| RFC-0021 | Frontend Architecture and Design System (**new**) | Experience | 0009, 0027 |

## Suggested reading paths
- **New engineer:** RFC-0001 → RFC-0002 → CONVENTIONS → RFC-0003 → RFC-0005 → RFC-0006 → STD-001/002.
- **"How do I add an integration?":** ADR-0016 → RFC-0018 (step-by-step guide) → RFC-0012 → RFC-0013.
- **Commercial flow:** RFC-0017 → RFC-0013 → RFC-0020 → EVENT_CATALOG §flows.
- **Agent platform:** RFC-0016 → RFC-0011 → RFC-0012 → RFC-0019.
