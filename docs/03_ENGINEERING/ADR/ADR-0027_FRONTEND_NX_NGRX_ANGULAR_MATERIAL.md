# ADR-0027 - Frontend Tooling: Nx, NgRx and Angular Material
Status: PROPOSED
Date: 2026-10-19
Supersedes: the frontend parts of ADR-0007 (Angular CLI-only workspace, no store library, own `ui` library on CDK only)

## Context
ADR-0007 and RFC-0021 chose a plain Angular CLI workspace, Signals without a store library and a fully custom component library on Angular CDK. The product owner decided (2026-10-19) to adopt Nx, NgRx and a ready-made UI library.

## Decision
- **Nx** manages the frontend workspace (`frontend/`): apps `control`, `partner`, `workspace`; libraries organized by type (`feature-*`, `data-access-*`, `ui-*`, `util-*`) and scope tags (`scope:control`, `scope:partner`, `scope:workspace`, `scope:shared`). Nx module-boundary lint rules enforce that a Workspace library can never import Control/Partner data-access (complements ADR-0009). Nx `affected` drives frontend CI; Nx remote cache is optional (self-hosted). The Java services stay on Maven (ADR-0007).
- **NgRx**: **NgRx SignalStore** (`@ngrx/signals`) for feature and domain state; `@ngrx/store` + effects only for app-wide cross-cutting state where needed (session, tenant context, notifications); NgRx entity adapters for collections. Components stay standalone with OnPush/Signals.
- **UI library: Angular Material (Material 3)** with **Angular CDK**, fully themed with WAVER tokens (`--wv-*` mapped to Material system tokens: color roles, typography Inter, shape, density −2 for compact controls). The WAVER `ui-*` libraries wrap Material components (`wv-button`, `wv-data-table`, `wv-status-badge`...) so features never depend on Material APIs directly and the brand look stays consistent. Components Material does not provide (Flow Builder canvas, KPI tiles, status badges for commercial/financial states) remain custom on CDK.

## Alternatives
- PrimeNG: richer data grid; theming and long-term support model less aligned with first-party Angular.
- Keep Angular CLI + Signals only + custom `ui`: least dependencies, most custom work.

## Consequences
- Faster delivery of standard screens (forms, tables, dialogs) with accessibility built in.
- Risk of a "default Material look": mitigated by mandatory theming through tokens, the `wv-*` wrapper layer and design review against BRAND_GUIDELINES (the frontend-design skill guidance applies).
- Nx adds a layer to the toolchain; version upgrades follow `nx migrate`.

## Compliance / Verification
- Lint: Nx enforce-module-boundaries with scope tags; no feature imports `@angular/material` directly (only `ui-*` libs).
- Visual regression tests on `ui-*` components in light/dark and compact density.

## Supersedes / Superseded by
Supersedes the frontend parts of ADR-0007; amends ADR-0015 (frontend layout) and RFC-0021.
