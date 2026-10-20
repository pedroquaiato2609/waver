# RFC-0021 - Frontend Architecture and Design System
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Nx, NgRx SignalStore + @ngrx/store, Angular Material M3 themed by `--wv-*` tokens, Keycloak login, Redis-backed BFF sessions and run streams, Istio).
Owners: WAVER Architecture (named owner TBD)
Reviewers: TBD
Date: 2026-10-19
Version: 1.3

## Summary
Defines the WAVER frontend: one **Nx** workspace (ADR-0027) with three apps — **WAVER Control** (`control`), **WAVER Partner** (`partner`) and **WAVER Workspace** (`workspace`, including the Builder) — each served on its own host and talking only to its own BFF (ADR-0009). It specifies the Nx library types and scope tags with their module-boundary rules, state management with **NgRx SignalStore** (and `@ngrx/store` only for session, tenant context and notifications), the design system built on **Angular Material (Material 3)** themed exclusively from the brand tokens (`--wv-*`) behind a `wv-*` wrapper layer, the custom components that remain (Flow Builder canvas, KPI tiles, status badges, charts and a few layout pieces), how the apps avoid a default "Material look", theming (light/dark) and density, the Builder UX (Objective Builder and Flow Builder as separate screens, Playground side panel), the "feature not contracted" experience, authentication through the BFF session cookie (login on Keycloak, ADR-0021), error/empty/loading patterns, performance budgets, accessibility, i18n and testing. It keeps the v1.2 decision on how static bundles are served (`frontend-<app>` images).

## Context / Problem
- THREE_SYSTEMS and Master §8 define three audiences with strict visibility rules: customers never see the marketplace, wholesale prices or reseller margins (ADR-0006). ADR-0009 enforces this on the backend with one BFF per audience; the frontend must make the same separation structural.
- Scope §20 asks for a clear, technical, dense but organized UI with fine, compact controls, progressive disclosure, screens separated by responsibility, a creation wizard and separate Objective/Flow builders. Its warm orange/red/yellow palette is **superseded** by the green brand (BRAND_GUIDELINES v1.2 §9, ERRATA v1.2).
- BRAND_GUIDELINES v1.2 defines semantic tokens (`palette/tokens.json` → `tokens.css`, `--wv-*`), dark theme via `[data-theme="dark"]`, compact density via `[data-density="compact"]`, per-app themes (Control dark by default, Partner light, Workspace light with Forest areas) and separate badges for commercial, financial and technical states.
- On 2026-10-19 the product owner adopted Nx, NgRx and Angular Material (ADR-0027), superseding the v1.2 position (Angular CLI-only workspace, Signals without a store library, fully custom `ui` on CDK). Login, MFA and federation moved to Keycloak (ADR-0021); BFF sessions and agent-output fan-out moved to Redis (ADR-0020); all pod-to-pod traffic runs in the Istio ambient mesh (ADR-0026).
- Without a shared architecture, three apps would duplicate components, drift from the brand (a ready-made UI kit makes this drift the default) and leak audience-specific data through shared code.

## Goals
- Audience isolation by construction in the frontend, mirroring ADR-0009, enforced by Nx scope tags.
- One design system (`ui-*` libraries) used by all apps; Angular Material only behind `wv-*` wrappers; no hardcoded colors; brand changes ship by regenerating tokens.
- A product that looks like WAVER, not like a default Material app.
- Dense, keyboard-friendly, WCAG 2.2 AA interfaces in pt-BR (default) and en-US.
- Builder UX that keeps the two strategies separate (ADR-0005) and testing beside, not mixed with, configuration.
- Predictable state management: one pattern (SignalStore) for feature state, a small, closed set of app-wide slices.
- Fast first load and predictable performance through enforced budgets.

## Non-goals
- Native mobile apps; the embeddable Web Chat widget/SDK for customers' websites (channel-service RFC).
- Login/MFA/organization-selection pages: they are served by **Keycloak** on the `auth.` host with the WAVER login theme (ADR-0021, RFC-0004). The theme reuses `tokens.css` but is not an Nx app.
- Visual design of individual screens (Figma/design files) and brand assets (BRAND_GUIDELINES).
- White-labeling (out of scope by default, Scope §18).

## Requirements / ARs
| Requirement | How this RFC meets it |
|---|---|
| ADR-0006 / ADR-0009 audience isolation | Each app's generated BFF client lives in a `scope:<app>` library; Nx `enforce-module-boundaries` forbids any other scope from importing it; `workspace` has no marketplace/pricing code paths; CI dependency-graph check per app; per-audience E2E (STD-010 T-18) |
| ADR-0021 / ADR-0009 tokens never in browser | Authorization Code + PKCE performed by the BFF against Keycloak; tokens stay in the BFF session (Spring Session Data Redis, ADR-0020); browser holds only `__Host-wv-session` (RFC-0004 §11); no tokens in JS-readable storage or NgRx state (STD-002 A-28) |
| ADR-0027 Nx / NgRx / Material | §2 workspace layout and boundaries, §3 state, §6 design system and theming |
| AR-008 tenant context | UI never sends tenant IDs as authority; org switch is a re-authorization (RFC-0004) |
| AR-010 versioned production | Builder edits only drafts; publish creates a version and a deployment through release gates (RFC-0016) |
| AR-011 entitlements | Navigation and actions driven by entitlement-derived `features` from the BFF (held in the app-wide session slice); "feature not contracted" pattern |
| AR-013 plane isolation | Workspace chat and agent operation screens depend only on data-plane BFF routes; Builder/admin failures do not block them |
| AR-015 compatibility | BFF APIs compatible with current and previous frontend release (RFC-0008, RFC-0014) |
| Scope §20 | Compact density default for data screens, progressive disclosure, wizard, separate builders, Playground side panel |

## Proposed Design

### 1. Topology: apps, BFFs, hosts, serving
| App | Host (domain open) | BFF | Audience (`org_type`) | Default theme |
|---|---|---|---|---|
| `control` | `control.<domain>` | `control-bff` | PLATFORM | dark (Forest) |
| `partner` | `partner.<domain>` | `partner-bff` | RESELLER | light |
| `workspace` | `app.<domain>` | `workspace-bff` | CUSTOMER | light, Forest areas for chat/agent execution |

- **Serving decision** (kept from v1.2; closes RFC-0001 open point 2 and RFC-0014 open question 4): each app is built by Nx into its own image `frontend-<app>` (RFC-0014 §10), a minimal Spring Boot static-resource server with no business code and no outbound calls, so no new runtime technology is introduced. edge-gateway routes `/api/**`, `/oauth2/**`, `/login/**`, `/logout` to the BFF and everything else on the host to `frontend-<app>`. Same origin means no CORS and cookie-only auth. In the mesh, the `AuthorizationPolicy` of `frontend-<app>` admits only edge-gateway's identity, and its egress is empty (ADR-0026).
- Hashed assets: `Cache-Control: public, max-age=31536000, immutable`; `index.html`: `no-cache`. One build per locale under `/pt-BR/` and `/en-US/`; `/` redirects by the user's saved locale (from `GET /api/session`), then `Accept-Language`, then `pt-BR`.
- Security headers for app hosts are set at edge-gateway (RFC-0008): CSP `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; require-trusted-types-for 'script'`, HSTS, `nosniff`, `Referrer-Policy`. Fonts (Inter, SIL OFL; D-28) and icons are self-hosted; no third-party CDNs, Google Fonts, Material Icons/Symbols font downloads, analytics or tag managers. Angular Material's documented default of loading Roboto and Material Symbols from Google is therefore not used (§6.4).
- Frontends and BFFs deploy independently; a BFF supports the frontend release in production and the previous one (N-1, RFC-0014 §9).

### 2. Nx workspace layout and module boundaries (ADR-0027, STD-002)
The frontend is one Nx workspace in `frontend/`; the Java services stay on Maven (ADR-0007). Apps are thin shells; almost all code lives in libraries classified by **type** and **scope**.

```text
frontend/
  nx.json  package.json  package-lock.json  tsconfig.base.json  eslint.config.mjs
  apps/
    control/  partner/  workspace/          # bootstrap, app.config.ts, root routes, shell composition, SESSION_API binding   [type:app, scope:<app>]
    control-e2e/  partner-e2e/  workspace-e2e/   # Playwright suites per audience                                     [type:e2e, scope:<app>]
    ui-catalog/                             # component catalogue, dev/preview only, never deployed to production         [type:app, scope:shared]
  libs/
    shared/
      ui-theme/              # tokens.css (copied from 02_BRAND at build), Material M3 theme mapped from --wv-*, icon registry, global styles
      ui-core/               # wv-button, field wrappers, select, checkbox/radio/switch, date fields, dialog, toast, menu, tooltip, tabs, stepper, disclosure
      ui-layout/             # wv-app-shell, side nav, top bar, page header, breadcrumb, side panel, banner, environment ribbon
      ui-data-grid/          # wv-data-grid on mat-table + cdk virtual scroll
      ui-feedback/           # wv-empty-state, wv-error-state, wv-skeleton, wv-spinner, wv-not-contracted (presentational)
      ui-status/             # wv-status-badge, wv-kpi-tile (custom)
      ui-charts/             # wv-chart (custom, in-house SVG)
      ui-rich-text/          # the one reviewed sanitizing renderer (custom)
      data-access-app-state/ # @ngrx/store slices session, tenantContext, notifications; guards, XSRF, 401/step-up, org switch
      util-i18n/             # locale registration, error-code messages, wvMoney/wvDate pipes
      util-signal-store/     # shared SignalStore features (withRequestStatus, withCursorPagination, withDraftAutosave)
      util-testing/          # test helpers: fake session, generated-client fakes, harness utilities
    control/   data-access-api/  data-access-<domain>/  feature-<area>/          # e.g. feature-resellers, feature-oversight
    partner/   data-access-api/  data-access-<domain>/  feature-<area>/          # e.g. feature-quotes, feature-contracts, feature-dunning
    workspace/ data-access-api/  data-access-<domain>/  feature-<area>/          # e.g. feature-chat, feature-knowledge
               feature-builder-core/  feature-builder-objectives/ [builder:objectives]  feature-builder-flow/ [builder:flow]
               feature-builder-capabilities/  feature-builder-testing/  feature-builder-delivery/
               data-access-builder/   # SignalStores: AgentDraftStore, DeploymentStore, PlaygroundStore (RFC-0016)
               ui-builder/            # Builder presentational components on the shared wv-* wrappers
               ui-flow-canvas/        # Flow Builder canvas (custom, Angular CDK), imported only by feature-builder-flow
               util-builder/          # graph validation helpers shared by the canvas and the validation panel
```
The Builder library names and tags are defined by RFC-0016; this RFC fixes their folder (`libs/workspace/`).
- Every project declares exactly one `type:*` tag (`app`, `e2e`, `feature`, `data-access`, `ui`, `util`) and one `scope:*` tag (`control`, `partner`, `workspace`, `shared`). Import aliases are `@waver/<scope>/<type>-<name>` (e.g., `@waver/shared/ui-core`, `@waver/partner/data-access-api`); only a library's public `index.ts` may be imported.
- `libs/<app>/data-access-api` holds the client generated by OpenAPI Generator (`typescript-angular`) from `contracts/openapi/<app>-bff/v<N>.yaml`; it is never edited by hand.
- Shared code that needs the BFF (session bootstrap) depends on a port, not on a client: `data-access-app-state` declares the `SESSION_API` injection token and each app binds it to its own generated client in `app.config.ts`.

**Module-boundary rules** (`@nx/enforce-module-boundaries`, error level, STD-002 A-01):

| Source | May depend on | Additional bans (`bannedExternalImports` / restricted imports) |
|---|---|---|
| `scope:control` / `scope:partner` / `scope:workspace` | same scope + `scope:shared` | — |
| `scope:shared` | `scope:shared` only | — |
| `type:app` | `feature`, `data-access`, `ui`, `util` | `@angular/material/*` (apps compose `ui-*` only) |
| `type:feature` | `data-access`, `ui`, `util` (never another feature) | `@angular/material/*`, `@angular/cdk/*` (except test harness code), `@ngrx/store`, `@ngrx/effects` |
| `type:data-access` | `data-access`, `util` | `@angular/material/*`, `@angular/cdk/*`; `@ngrx/store`/`@ngrx/effects` only in `data-access-app-state` |
| `type:ui` | `ui`, `util` | `@ngrx/*`, `@angular/common/http`, any `data-access-*` |
| `type:util` | `util` | `@angular/material/*`, `@ngrx/store`, `@ngrx/effects` |
| `type:e2e` | `util` (test helpers) | runs against deployed apps only |
| `builder:objectives` / `builder:flow` | mutually exclusive: `notDependOnLibsWithTags` the other tag (RFC-0016, mirroring ADR-0019) | — |

Consequences: a Workspace library can never import Control or Partner code or clients (scope rule); `feature-builder-objectives` and `feature-builder-flow` can never import each other (feature→feature is forbidden and their `builder:*` tags exclude each other; they are composed only by the `workspace` route table); Material is reachable only through `ui-*`. Because Nx uses one `tsconfig.base.json` with all path aliases, compile-time isolation per app no longer exists; the lint rule is therefore a **required, error-level CI gate**, complemented by a CI check that reads the Nx project graph and fails if the dependency closure of an app contains a project of another app's scope.

- CI runs `nx affected -t lint test build e2e` against the merge base; a change in a `scope:shared` library rebuilds all three apps (RFC-0014 §10). A self-hosted Nx remote cache is optional; caching never changes results (targets declare their inputs and outputs). Toolchain upgrades use `nx migrate`.

### 3. State management (NgRx, ADR-0027)
| State | Mechanism | Where | Lifetime |
|---|---|---|---|
| Feature and domain state (lists, details, drafts, Builder sections, Playground run view) | **NgRx SignalStore** (`@ngrx/signals`), `withEntities` for collections, `rxMethod` for async flows | Domain stores in `libs/<scope>/data-access-<domain>`; view-only stores may live in the feature library | Provided on the route (or component) that owns the screen; destroyed with it |
| App-wide cross-cutting state: **session** (user, org, orgType, roles, workspaces, locale, `features`), **tenantContext** (selected workspace and Domain), **notifications** (toasts, in-app notification badge) | `@ngrx/store` + functional effects, `createFeature`, `createActionGroup`, `@ngrx/entity` where a slice holds a collection | `libs/shared/data-access-app-state` only | Application lifetime |
| Streams (HTTP, SSE token streams, debounce, cancellation) | RxJS, converted to signals at the store boundary | Inside store methods | Per call |
| UI preferences (theme, density) | Small preferences service backed by `localStorage` | `data-access-app-state` | Persistent per browser |

- Components read signals and call store methods; they never `patchState` and never dispatch actions that belong to another slice. Features read app-wide state through the selectors exported by `data-access-app-state` (`store.selectSignal(selectSession)`), and may dispatch only its public actions (e.g., `sessionActions.organizationSwitchRequested`).
- Adding a new `@ngrx/store` slice requires an amendment of this RFC; everything else is a SignalStore.
- Shared SignalStore features in `util-signal-store` standardize request status (`idle | loading | loaded | error` with a `Problem`), cursor pagination (`items`, `nextCursor`, `hasMore`, keyed by the API's ID) and draft autosave with `ETag`/`If-Match` (§8).
- No state is persisted except UI preferences; tokens, credentials and PII never enter any store (STD-002 A-28). Store DevTools are enabled only in development builds.
- Chat and Playground streaming: the store opens the SSE stream from the BFF; the BFF reads the run's Redis Stream `waver:run-stream:<agentRunId>` (ADR-0020), so a reconnect sends `Last-Event-ID` and resumes on any BFF pod without losing tokens.

### 4. Authentication and session flow (ADR-0021, ADR-0020, RFC-0004)
```mermaid
sequenceDiagram
  participant B as Browser (app)
  participant G as edge-gateway
  participant F as *-bff
  participant K as Keycloak (auth host, realm waver)
  B->>G: GET / (index.html from frontend-<app>)
  B->>F: GET /api/session
  F-->>B: 401 UNAUTHENTICATED
  B->>F: navigate /oauth2/authorization/waver?returnTo=/contracts
  F-->>B: 302 to Keycloak (code + PKCE, state, nonce)
  B->>K: login (WAVER theme), MFA, organization selection, or brokered enterprise SSO
  K-->>B: 302 BFF callback?code
  F->>K: code exchange (private_key_jwt)
  F->>F: store tokens in Spring Session Data Redis; rotate session id
  F-->>B: Set-Cookie __Host-wv-session (HttpOnly, Secure, SameSite=Lax) + XSRF-TOKEN; 302 returnTo
  B->>F: GET /api/session -> user, org, orgType, roles, workspaces, locale, features
```
- `data-access-app-state` bootstraps the app with `GET /api/session` (through `SESSION_API`, `provideAppInitializer`) and fills the `session` slice. `features` is a list of entitlement-derived capability keys and permission flags computed by the BFF; the UI uses it only to show/hide navigation and actions.
- `returnTo` accepts only relative paths of the same app (open-redirect protection).
- 401 `UNAUTHENTICATED` → redirect to login keeping the current route; 401 `STEP_UP_REQUIRED` → BFF re-authorization at Keycloak requesting the required ACR level (`acr_values`, `max_age=0`), then the user retries the action; session revoked (`UserSessionsRevoked`, Keycloak session logout) → next call returns 401 and the app shows "session ended". If Redis loses the session (ADR-0020 fallback), the user simply logs in again.
- Org switch (users with several memberships): `POST /api/session/organization-switch` → BFF re-authorizes at Keycloak with `prompt=none` for the target organization (Keycloak Organizations, RFC-0004 §2); the app reloads and the `session` slice is rebuilt. Workspace and Domain switch inside an org update the `tenantContext` slice (client navigation within `ws_ids`).
- Logout: `POST /api/logout` (XSRF-protected) → BFF revokes the refresh token, deletes the Redis session and performs Keycloak RP-initiated logout.

### 5. Shell and navigation per app
All shells share `ui-layout` primitives: `wv-app-shell` (side navigation + top bar + content), collapsible side nav, breadcrumb, page header with primary action, user menu (profile, locale, theme, density, logout), org/workspace switcher where applicable, environment ribbon in non-production (`dev`, `staging`, `preview-pr-<n>`).

| App | Navigation (v1) | Surfaces |
|---|---|---|
| Control | Executive Dashboard; Resellers; Customer Oversight; Private Marketplace; Catalog & Pricing; Contracts; Billing & Receivables; Usage & Metering; Risk & Compliance; Support & Operations; Audit Center (Master §8.1) | `data-theme="dark"` by default (Forest nav and surfaces, Lime only for selection indicators and highlighted KPIs); light theme available |
| Partner | Portfolio Dashboard; Customers; Quotes; Contracts; Entitlements & Licenses; Billing; Dunning; Private Marketplace (authorized listings only); Usage; Support; Renewals (Master §8.2) | Light; Forest navigation; dense tables; commercial/financial badges |
| Workspace | Home; Chat; Agents (Builder); Knowledge; Integrations; Automations; Channels; Reports; Settings (Domains, Members, Usage vs limits); entitled native apps (e.g., agenda) appear only when contracted | Light; `data-theme="dark"` scopes on chat, agent-run timelines and the Playground panel |

- Workspace has a Domain selector in the top bar (backed by the `tenantContext` slice); lists and Builder screens are filtered by the selected Domain (Domain isolation, Scope §5).
- Control and Partner may show cross-customer views only where the BFF exposes them (audited oversight/portfolio paths, ADR-0010); Workspace never shows another organization.

### 6. Design system: Angular Material M3 behind `wv-*` (ADR-0027)

#### 6.1 Tokens
Components consume only semantic CSS variables from `02_BRAND/palette/tokens.css` (`--wv-color-action-primary`, `--wv-color-bg-surface`, `--wv-color-text-primary`, `--wv-color-border-control`, `--wv-color-status-success`, `--wv-color-chart-*`, `--wv-space-*`, `--wv-radius-*`, `--wv-font-*`, `--wv-shadow-*`, `--wv-duration-*`, `--wv-control-height`, `--wv-table-row-height`). The file is copied into `ui-theme` from the brand pack at build time (no hand edits); primitives and legacy `--waver-*` variables are not used by components. Financial numbers use `tabular-nums`. The tokens are the **only** source of color, type, shape, elevation and motion: Angular Material sees WAVER values exclusively through the mapping below.

#### 6.2 Material M3 theme mapped from `--wv-*` (`libs/shared/ui-theme`)
`ui-theme` calls `mat.theme(...)` once (it requires a palette and a type family to generate structure) and immediately overrides every Material **system token** (`--mat-sys-*`) with a `--wv-*` reference through `mat.theme-overrides(...)`. The generated palette is therefore never visible; a CI check asserts that every `--mat-sys-*` color role emitted by the theme is overridden.

| Material system tokens | Mapped from WAVER tokens |
|---|---|
| `primary`, `on-primary`, `primary-container`, `on-primary-container` | `--wv-color-action-primary`, `--wv-color-text-on-action`, `--wv-color-action-selected-bg`, `--wv-color-text-primary` |
| `secondary`, `on-secondary`, `tertiary` | `--wv-color-action-secondary`, `--wv-color-text-on-action`, `--wv-color-accent-highlight` |
| `surface`, `surface-container-lowest…highest`, `surface-variant`, `background` | `--wv-color-bg-canvas`, `--wv-color-bg-surface`, `--wv-color-bg-surface-muted` (M3 tonal surface steps collapse to the brand's two surfaces) |
| `on-surface`, `on-surface-variant` | `--wv-color-text-primary`, `--wv-color-text-secondary` |
| `outline`, `outline-variant` | `--wv-color-border-control`, `--wv-color-border-subtle` |
| `error`, `on-error`, `error-container` | `--wv-color-status-danger`, `--wv-color-text-on-action`, `--wv-color-status-danger-bg` |
| `inverse-surface`, `inverse-on-surface` | `--wv-color-bg-inverse`, `--wv-color-text-on-inverse` |
| `surface-tint` | `transparent` (no M3 tonal elevation tint) |
| Typography: `--mat-sys-*-font`, `body-*`, `label-*`, `title-*`, `headline-*` | `--wv-font-sans` (Inter), `--wv-font-size-*`, `--wv-font-weight-*`, `--wv-line-height-*`; `display-*` sizes are not used |
| Shape: `corner-extra-small…large`, `corner-full` | `--wv-radius-sm`, `--wv-radius-md`, `--wv-radius-lg`; `corner-full` → `--wv-radius-pill` only where the brand uses pills (badges, chips) |
| Elevation: `level0…level5` | none / `--wv-shadow-sm` / `--wv-shadow-md` / `--wv-shadow-lg` (menus, dialogs, side panel only) |
| Motion | component transition durations from `--wv-duration-*`, `--wv-easing-standard` |

- **Dark theme and scoped themes**: dark is driven solely by remapping `--wv-*` under `[data-theme="dark"]`; Material is not given a separate dark theme and `light-dark()`/`color-scheme` theming is not used. Because a custom property that references another (`--mat-sys-primary: var(--wv-color-action-primary)`) is resolved where it is declared, `ui-theme` emits the `--mat-sys-*` mapping on `html` **and** on every `[data-theme]` and `[data-density]` element, so region-scoped dark areas (Workspace chat, Playground) re-resolve Material tokens correctly.
- **Density**: standard scopes use Material density 0; `[data-density="compact"]` applies Material density **−2** (ADR-0027). Material densities alone do not reach the brand heights, so `ui-theme` pins control and row heights to `--wv-control-height` (compact 28 px, standard 32 px) and `--wv-table-row-height` (32 px / 40 px) through the component override mixins (`mat.form-field-overrides`, `mat.button-overrides`, `mat.select-overrides`, `mat.table-overrides`, …). Pointer targets stay ≥ 24×24 px (WCAG 2.5.8) even where Material's 48 px touch-target spacing is removed by density.
- **Focus**: Material strong focus indicators are enabled and themed with `--wv-color-border-focus` (2 px outline), identical in all components.
- Component-level Material overrides live only in the `ui-*` library that wraps that component; no other project may reference `--mat-*` variables or `.mat-*`/`.mdc-*` classes (STD-002 A-24).

#### 6.3 The `wv-*` wrapper layer
Features never use Material components, directives, services or harnesses directly (lint-enforced, §2). Each `ui-*` component exposes WAVER vocabulary (variants `primary | secondary | ghost | danger`, sizes `sm | md`, status families), not Material's (`appearance`, `color`), and does not pass arbitrary Material inputs through. Each ships its own component harness (built on the Material harness where one exists), documented inputs/outputs, keyboard map and catalogue page in both themes and densities.

Material's `mat-form-field` cannot host a control projected through a wrapper, so field wrappers are **complete controls** that implement `ControlValueAccessor` and render the label, the Material field, hint and errors internally (`<wv-text-field formControlName="name">`). Labels are rendered above the field (brand), not as Material floating labels.

| Component | Selector / API | Basis | Notes |
|---|---|---|---|
| Button, icon button | `wv-button` (`variant`, `size`, `type`, `busy`), `wv-icon-button` (`ariaLabel` required) | Material button | Rectangular brand radius, no ripple; busy state sets `aria-busy` and blocks double submit |
| Text, number, textarea fields | `wv-text-field`, `wv-number-field`, `wv-textarea` | `mat-form-field` (outline) + `matInput` | CVA wrappers; map server `errors[]` by `pointer` |
| Select, combobox, multiselect | `wv-select`, `wv-combobox`, `wv-multiselect` | `mat-select`, `mat-autocomplete`, `mat-chip-grid` | Keyboard and type-ahead; async search via input stream |
| Checkbox, radio, switch | `wv-checkbox`, `wv-radio-group`, `wv-switch` | `mat-checkbox`, `mat-radio-group`, `mat-slide-toggle` | |
| Date / period | `wv-date-field`, `wv-period-field` | `mat-datepicker`, `mat-date-range-input` with a WAVER ISO `DateAdapter` | Values as `YYYY-MM-DD`; renders in user time zone |
| Tabs | `wv-tabs`, `wv-tab-nav` | `mat-tab-group`, `mat-tab-nav-bar` | Route-driven tabs for Builder sections |
| Dialog, confirm dialog | `WvDialog` service | `MatDialog` | Focus trap; destructive confirms require typed confirmation for purge/suspension |
| Toast | `WvToast` service | `MatSnackBar` | Polite announcements; errors include correlation reference |
| Menu, tooltip | `wv-menu`, `[wvTooltip]` | `mat-menu`; `MatTooltip` as host directive | |
| Stepper (wizard) | `wv-stepper` | `mat-stepper` | Creation wizard |
| Disclosure | `wv-disclosure` | `mat-expansion-panel` | "Advanced" sections (Scope §20) |
| Progress | `wv-spinner`, `wv-progress-bar` | `mat-progress-spinner`, `mat-progress-bar` | |
| Card | `wv-card` | `mat-card` | Flat, border `--wv-color-border-subtle` |
| Data grid | `wv-data-grid` | `mat-table` (CdkTable) + `matSort` + `cdk/scrolling` virtual scroll + `SelectionModel` | Cursor "load more"/infinite scroll (no `mat-paginator`), server sort/filter, column chooser, row selection, sticky header, density aware, CSV export via BFF |
| App shell, side nav, side panel | `wv-app-shell`, `WvSidePanel` service | `mat-sidenav-container`, `mat-drawer` + CDK drag resize handle | Side panel used for Playground and inspectors |

**Custom components that remain** (Material has no equivalent, or it cannot meet the brand/a11y rules): `ui-flow-canvas` (§7), `wv-kpi-tile` (value, unit, delta, period, trend sparkline), `wv-status-badge` (text + icon + tone per family, never color only), `wv-chart` (line, bar, stacked bar, donut, sparkline; in-house SVG with `--wv-color-chart-*`, table alternative and text summary), `wv-breadcrumb`, `wv-page-header`, `wv-banner` (restriction/suspension, maintenance, preview environment), `wv-empty-state`, `wv-error-state`, `wv-skeleton`, `wv-not-contracted`, `wv-popover` (CDK Overlay), the environment ribbon and the rich-text renderer in `ui-rich-text`. They use the same tokens and CDK a11y primitives.

Status badge families (brand §4.3: separate badges, never one combined status):

| Family | States → tone (`--wv-color-status-*`) |
|---|---|
| Financial (customer/reseller, Master §7.1) | CURRENT → success; PAST_DUE, GRACE_PERIOD → warning; RESTRICTED, SUSPENDED → danger; REACTIVATING → info; TERMINATED → neutral |
| Contract (RFC-0013) | DRAFT, PENDING_APPROVAL → neutral/info; ACTIVE → success; EXPIRED, CANCELLED → neutral |
| Entitlement (RFC-0013) | ACTIVE → success; LIMIT_REACHED, RESTRICTED → warning; SUSPENDED, REVOKED → danger |
| Technical (deployment, connection, provisioning) | ACTIVE/HEALTHY/SUCCEEDED → success; DEGRADED/PENDING → warning; FAILED/REAUTH_REQUIRED → danger |

The component catalogue is the dev-only Nx app `apps/ui-catalog`, deployed to `dev` and preview environments only.

#### 6.4 Avoiding a default "Material look"
| Material default | WAVER rule |
|---|---|
| Palette generated from a seed color, tonal surfaces, surface tint | Every color role overridden from `--wv-*` (CI check); two brand surfaces; tint off |
| Pill-shaped buttons, large corner radii | `--wv-radius-sm`/`-md`; pills only for badges and chips |
| 56/48 px fields, generous spacing | Density −2 in compact scopes plus heights pinned to `--wv-control-height` and `--wv-table-row-height` |
| Floating labels, filled fields | Outline appearance, labels above fields, dynamic subscript sizing |
| Ripples and strong state layers | Ripples disabled globally (`MAT_RIPPLE_GLOBAL_OPTIONS`); hover/pressed/selected from `--wv-color-action-*-hover` / `--wv-color-action-selected-bg` |
| Roboto and Material Symbols from Google | Inter (`--wv-font-sans`) and the WAVER SVG icon set from `02_BRAND/icons`, registered in `MatIconRegistry` and self-hosted (CSP) |
| Elevated cards and shadows everywhere | Flat surfaces with subtle borders; shadows only on overlays |

- **Token-only theming**: only `ui-theme` and the wrapper libraries may style Material; features cannot import Material, cannot use `::ng-deep`, cannot reference `--mat-*` or `.mat-*`/`.mdc-*` (STD-002 A-22..A-24, A-41).
- **Brand review gate**: every new or changed `ui-*` component (and every Material major upgrade) is reviewed by the brand owner against BRAND_GUIDELINES using the catalogue in light/dark × standard/compact; approval is recorded in the PR. Visual-regression baselines of the catalogue are updated only with that approval.
- Material upgrades follow `nx migrate`/`ng update`; changes in Material's token API are absorbed in `ui-theme` and the wrappers, never in features.

### 7. `libs/workspace/ui-flow-canvas`
- Purpose-built editor for FlowNodes (Message, Question, Action, Condition, Delay, Transfer, Objective Group, End) and FlowTransitions (RFC-0016). A `type:ui, scope:workspace` library imported only by `feature-builder-flow`, which is lazy-loaded by the `builder/agents/:id/flow` route. It stays custom on Angular CDK (Material has no graph editor) and uses the same tokens; node inspectors use `ui-core` wrappers and `ui-builder` components; graph validation helpers come from `util-builder`.
- Rendering: SVG edges + HTML node cards, pan/zoom, snap-to-grid, CDK drag-drop for palette → canvas and node moves, simple auto-layout (layered, left-to-right). No third-party graph library.
- Inspector side panel per selected node/transition (typed reactive forms); validation markers from the draft `validation_report` (e.g., unreachable nodes, missing End, invalid conditions).
- Modes: `edit` (draft), `read-only` (AgentVersion view), `replay` (highlights the path of a FlowState from a Playground or production run).
- Accessibility: an **outline view** lists nodes and transitions as an editable tree/table with full keyboard support; every canvas operation is also available there (canvas alone cannot meet WCAG 2.2 AA).
- Model: the canvas edits a plain `FlowGraph` value (nodes, transitions, positions) via inputs/outputs; persistence is the job of the Builder SignalStore in `data-access-builder` (autosave with `If-Match`).

### 8. Builder UX (RFC-0016, ADR-0005)
- **Creation wizard** (`wv-stepper`): Domain → Identity → Strategy (OBJECTIVE_DRIVEN or FLOW_DRIVEN, with the explanation that it cannot change later) → Shared capabilities (model profile `fast`/`balanced`/`high-quality`, knowledge bases, **entitled** tools, memory, channels). Result: Agent + AgentDraft, then navigation to the strategy builder.
- **Separate screens**: `/builder/agents/:id/objectives` (Objective Builder, `feature-builder-objectives`: form/table of objectives with priority, dependencies, structured completion conditions, attempt policy) and `/builder/agents/:id/flow` (Flow Builder, `feature-builder-flow`: full-width canvas). Only the route matching the agent's strategy is reachable; the other returns the app's not-found page. The two feature libraries never import each other (Nx boundary rule).
- **Shared capabilities** (`/knowledge`, `/tools`, `/policies`, `/model`, `/memory`, `/channels`, `feature-builder-capabilities`) are tabs on the agent, identical for both strategies. LLM parameters appear only in an "Advanced" disclosure (Scope §20).
- **Draft state**: `AgentDraftStore` (one per open agent, `data-access-builder`, RFC-0016) holds the sections, `etag`, dirty/validation state and section-level conflict merge, autosaving with `ETag`/`If-Match` (`withDraftAutosave`); on 412/`CONCURRENT_MODIFICATION` the user sees a compare-and-reload dialog. A header chip shows "Draft — not in production" vs the active version per environment (AR-010).
- **Playground**: a resizable side panel (`WvSidePanel`, dark scope) opened from any Builder screen, so testing never mixes with configuration forms. Source selector Draft / Version; WRITE tools run in MOCK/DRY_RUN with a visible badge (RFC-0016). The panel shows the conversation (streamed via SSE through `workspace-bff`, resumable via Redis Streams, §3), and a collapsible run timeline: objective states or flow position, tool calls (status, latency, mocked/live), model profile, tokens and cost units. Content visibility follows permissions. Runs that wait (approval, user reply beyond the short window) are Temporal-backed in agent-runtime (ADR-0023); the panel shows the waiting state and resumes from the stream when the run continues.
- **Testing & delivery** (`feature-builder-testing`, `feature-builder-delivery`): `/testing` lists suites and evaluation runs (evaluation-service via BFF); `/versions`, `/deployments` show release gates, approvals and one-click rollback.

### 9. "Feature not contracted" and restricted-account UX (ADR-0006, AR-011)
- Capabilities the customer has never contracted are **hidden** from navigation and pickers by default; places where discovery matters (e.g., tool picker, channel list) show them as locked items only when the reseller enabled "show upgrade hints" (open question).
- A locked item or a 403 `ENTITLEMENT_REQUIRED` opens `wv-not-contracted`: neutral message ("Este recurso não está incluído no seu plano"), the reseller's commercial contact (from `GET /api/my-reseller`, served by `workspace-bff` from customer-service's read model of the reseller's public contact — never from reseller-service, for which it has no client, ADR-0009, MICROSERVICES_MAP), and an optional "Solicitar à revenda" action. **Never** prices, SKUs, listings, marketplace links or WAVER sales contacts.
- RESTRICTED / SUSPENDED (Master §7.1, RFC-0013 `suspensionBehavior`): a persistent `wv-banner` with neutral text and the reseller contact; blocked actions are disabled with an explanation; read-only data stays visible; inbound channels may keep working (`KEEP_INBOUND`). No financial details beyond what the reseller exposes.
- Partner: listings not authorized for the reseller do not appear; a reseller blocked by WAVER (`ResellerRestriction`) sees which actions are blocked and the WAVER partner-management contact.

### 10. Loading, empty and error patterns
| Situation | Pattern |
|---|---|
| Initial page load | `wv-skeleton` matching the layout after 300 ms; no full-page spinners |
| Action in progress | `wv-button` busy state (disabled, `aria-busy`), no double submit (`exhaustMap` in the store method); `Idempotency-Key` generated per user intent and reused on retry |
| Long-running operation (202) | Inline progress row polling the process resource with backoff (STD-003 R-17); toast on completion |
| Empty collection | `wv-empty-state`: what this is, why it is empty, primary action if permitted (otherwise a hint to ask an admin) |
| Filter with no results | Inline message + "clear filters" |
| Error | `wv-error-state` / toast with i18n message from `code` (STD-008), correlation reference to copy, "try again" only when retryable |
| Rate limited (429 `RATE_LIMITED`) | Inline message; automatic retry only for GETs after `Retry-After` |
| Partial screen failure | The BFF returns per-section results; failed widgets show a compact error tile while the rest renders |
| Session expired / offline | Banner with re-login / reconnect; unsent form drafts kept in memory (store state) |
| Not found / no permission | App-level not-found page (also used for other tenants' IDs, which the BFF answers with 404) |

Optimistic updates only for reversible, low-risk actions (e.g., pinning); never for commercial, financial or deployment actions.

### 11. Accessibility and i18n
- WCAG 2.2 AA for all apps: semantic HTML, Angular Material/CDK a11y behavior inside the wrappers, visible focus (themed strong focus indicators), focus not obscured by sticky headers, keyboard parity (including the flow-canvas outline view), status not by color alone, contrast verified by the brand generator for both themes (Lime never as text on light backgrounds; Sage 300 never for text/control borders on light backgrounds). Overriding Material colors with brand tokens does not exempt a component from contrast checks: the catalogue runs axe-core in both themes.
- Angular built-in i18n: source locale `pt-BR`, second `en-US`; IDs `@@<feature>.<screen>.<element>`; ICU plurals; dates/numbers/money via `util-i18n` pipes on `Intl` with the user's time zone. Material's own strings (paginator-free tables, datepicker, stepper labels) are localized through the Material intl providers (`MatDatepickerIntl`, `MatStepperIntl`, …) configured in `ui-core` with `$localize`. Agent content language follows the agent configuration, not the UI locale.

### 12. Performance budgets
| Budget | Warning | Error |
|---|---|---|
| Initial bundle per app (raw, `apps/<app>/project.json`) | 500 kB | 700 kB |
| Any lazy route chunk | 250 kB | 400 kB |
| `ui-flow-canvas` chunk (with `feature-builder-flow`) | 300 kB | 450 kB |
| Component styles | 6 kB | 10 kB |

- Material and NgRx are tree-shaken per standalone import; the wrappers import only the Material entry points they use, and `ui-theme` includes only the system tokens plus the override mixins of wrapped components. The budgets are unchanged from v1.2 and are re-validated after Phase 0 with the real Material footprint; raising them needs an amendment.
- Field targets (p75, measured through the BFF, §13): LCP ≤ 2.5 s, INP ≤ 200 ms, CLS ≤ 0.1. Data grids render 10 000 rows through virtual scrolling without dropping below 50 fps on a reference laptop; the Playground shows streamed tokens within 100 ms of their arrival at the BFF. `@defer` for below-the-fold widgets and charts; preloading only for the next most likely route.

### 13. Frontend observability
- Web vitals and client error counts are posted to `POST /api/client-telemetry` / `POST /api/client-errors` (route template, metric, app version, `correlationId`; no user input or page content) and recorded by the BFF as metrics (STD-007 O-16) and logs (STD-006 L-18). Unhandled NgRx effect and `rxMethod` errors are reported through the same path.
- Every BFF response carries `X-Correlation-Id`; error states display it. The browser does not create traces; the BFF starts them.

## APIs / Events / Data Ownership
- Specs: `contracts/openapi/control-bff/v1.yaml`, `partner-bff/v1.yaml`, `workspace-bff/v1.yaml` (`/api/...`, unversioned, N-1 compatible, RFC-0008). Common BFF endpoints: `GET /api/session`, `POST /api/session/organization-switch`, `POST /api/logout`, `POST /api/client-errors`, `POST /api/client-telemetry`; workspace-only `GET /api/my-reseller`.
- Generated clients: `libs/<app>/data-access-api` (OpenAPI Generator `typescript-angular`); never edited by hand; visible only to the same scope.
- Frontends own no data and consume no Kafka events. UI preferences (locale) are stored in the identity-access user profile via the BFF; theme and density in browser storage.
- Builder screens use agent-builder, evaluation and agent-runtime (sandbox) through `workspace-bff` only.

## Security & Privacy
- No tokens, credentials or PII in browser storage or NgRx state; session only via `__Host-wv-session` (session data in Redis on the BFF side, ADR-0020); XSRF on state-changing calls; strict CSP with Trusted Types; no third-party scripts, fonts or icon CDNs (STD-002 §9, STD-009).
- Agent/LLM output, documents and inbound messages are rendered as text; any rich rendering goes through the one reviewed sanitizing renderer in `ui-rich-text`.
- Audience isolation tested end-to-end: a customer session cannot reach marketplace/pricing/reseller routes or BFF endpoints (STD-010 T-18); statically, Nx scope rules prevent the code from being in the bundle at all.
- Destructive and high-impact actions (purge, suspension, credential replacement, production deploy) use confirm dialogs and step-up (Keycloak ACR) when required (RFC-0004).

## Reliability & Failure Modes
| Failure | Effect | Handling |
|---|---|---|
| `frontend-<app>` unavailable | App cannot load | ≥ 2 replicas, immutable assets; cached `index.html` revalidates |
| BFF unavailable | App loads but calls fail | Global banner, retry with backoff for GETs; no data loss for drafts (autosave retried) |
| Keycloak unavailable | New logins and step-up fail; existing sessions keep working until token refresh fails | Login page error with retry; app shows "session ended" only when the BFF answers 401 |
| Redis session loss (ADR-0020) | Users are logged out | Re-login preserving route; unsent input kept in memory |
| SSE stream interrupted | Playground/chat stops updating | Automatic reconnect with `Last-Event-ID`; the BFF resumes from the Redis Stream (15 min retention) |
| Control-plane service down | Builder/admin sections fail | Per-section errors; Workspace chat and agent operation keep working via data-plane routes (AR-013) |
| New frontend with old BFF during rollout | Missing fields | BFF N-1 compatibility; UI tolerates unknown/missing optional fields |
| Session revoked or expired | 401 | Redirect to login preserving route; unsent input kept in memory |
| Concurrent edit | 412 | Compare-and-reload dialog |

## Observability
Web vitals and client errors per app and route (§13); BFF RED metrics and traces (STD-007); synthetic probes per host (login through Keycloak, session, one read per app) from RFC-0009.

## Testing Strategy
- `ui-*` and `ui-flow-canvas`: harness-based component tests for keyboard, ARIA and states in both themes and densities; **visual regression** (Playwright screenshots of `apps/ui-catalog`) in light/dark × standard/compact for every `ui-*` component, including the themed Material components (ADR-0027); axe-core on every catalogue page.
- State: SignalStores tested through `TestBed` with fakes of the generated clients (methods, computed signals, request status, error mapping); `@ngrx/store` reducers and selectors as pure functions, effects with `provideMockActions`, components that read app-wide state with `provideMockStore` (STD-010 T-16).
- Apps: Playwright E2E per audience (`apps/<app>-e2e`) on preview environments (`preview-pr-<n>`) and staging, including per-audience access tests, feature-not-contracted and suspension journeys, Builder journeys for both strategies, login and step-up through Keycloak, and automated accessibility checks (STD-010 §8).
- Contract: apps compile against regenerated BFF clients; BFF contract tests run against N and N-1 frontend clients (RFC-0014).
- Budgets, module boundaries and i18n extraction enforced in the build (STD-002 Enforcement).

## Deployment / Rollout / Rollback
- Phase 0: Nx workspace with tags and boundary rules, `ui-theme` (Material M3 mapping from `--wv-*`) with brand review of the themed Material baseline, `ui-core`/`ui-layout`/`ui-data-grid`/`ui-feedback`/`ui-status` foundations, `data-access-app-state` (session, tenant context, notifications), `util-i18n`, `ui-catalog`, Control login slice through Keycloak (RFC-0004 first slice).
- Phase 1: Partner and Control commercial screens (customers, quotes, contracts, billing, dunning, suspension).
- Phase 2: Workspace (chat, agents, knowledge, integrations), Builder (wizard, Objective Builder, Playground), then `ui-flow-canvas` and Flow Builder.
- Every PR touching `frontend/` or a BFF gets a `preview-pr-<n>` environment; Nx `affected` decides which app images rebuild; releases promote the same image digest dev → staging → production; rollback = previous `frontend-<app>` image (BFF stays N-1 compatible).

## Alternatives Considered
- **Fully custom `ui` on Angular CDK, no UI kit (v1.2 position)**: total visual control and fewer dependencies, but much more to build (forms, tables, dialogs, date pickers) and to keep accessible. Replaced by Angular Material behind `wv-*` wrappers (ADR-0027); custom components remain only where Material has none.
- **PrimeNG**: richer data grid; theming and long-term support model less aligned with first-party Angular (ADR-0027).
- **Using Material directly in features (no wrappers)**: less code, but every screen would depend on Material APIs and drift toward the default look; rejected.
- **Signals-only feature stores without a store library (v1.2 position)**: workable, but every team re-invents loading/error/pagination patterns. Replaced by NgRx SignalStore with shared features.
- **`@ngrx/store` for all state**: one pattern everywhere, but heavy boilerplate for route-scoped screen state; restricted to three app-wide slices.
- **Angular CLI-only workspace with per-app `tsconfig` path aliases (v1.2 position)**: compile-time isolation, but no tags, no `affected` and no library-type rules. Replaced by Nx.
- **BFF serves the bundle**: one image less, but couples frontend and BFF releases and puts static traffic on tier-0 BFF pods. Rejected in favor of `frontend-<app>` (RFC-0014).
- **Generic web-server image for static files**: common practice, but a new runtime technology requiring an ADR. Rejected for v1.
- **Micro-frontends / module federation** (including Nx module federation): independent deploys per feature, but higher complexity and shared-dependency risk for three apps owned by one team. Rejected.
- **Chart or graph libraries**: would speed up charts and the flow canvas but are not approved; in-house SVG for v1, revisit through STD-013 if scope grows.

## Risks
- **Default Material look** creeping in through un-overridden tokens, new Material components or upgrades; mitigated by token-only theming, the CI override check, the wrapper layer, the brand review gate and visual regression (§6.4).
- Material upgrade coupling: Material's token and override-mixin API changes between majors; absorbed in `ui-theme` and wrappers, budgeted per upgrade.
- Wrapper layer cost and leakage: wrappers must be kept thin but complete; a missing wrapper tempts teams to import Material directly (lint blocks it; the fix is a new wrapper).
- Brand heights (28/32 px) below Material's supported densities rely on component overrides that Material may not expose for every component; exceptions are documented in `ui-theme`.
- Canvas accessibility and performance for large flows; mitigated by the outline view and virtualization of off-screen nodes.
- Brand tokens are being reworked in parallel; components and the Material mapping depend only on semantic names, so value changes are safe but renames are breaking (token names become a contract).
- Single `tsconfig.base.json` means audience isolation relies on lint and the graph check, not the compiler; both are mandatory CI gates.
- Per-locale builds double build time and require a reload to switch language.

## Open Questions
1. ~~Libraries outside the ADR-0007 list (`flow-canvas`, builder feature libraries, component catalogue)~~ — resolved by ADR-0027 and RFC-0016: the Nx layout of §2 (`feature-builder-*`, `data-access-builder`, `ui-builder`, `ui-flow-canvas`, `util-builder`, `apps/ui-catalog`).
2. ~~Chart colors~~ — `tokens.css` now defines `--wv-color-chart-1..6`; sequential/diverging scales are still requested from the brand owner if dashboards need them.
3. ~~Tooling outside TECH_STACK (angular-eslint, Prettier, Playwright accessibility engine)~~ — listed as build tooling in TECH_STACK; the Nx ESLint plugin comes with ADR-0027.
4. ~~Source of the reseller contact shown to customers~~ — resolved in MICROSERVICES_MAP: customer-service keeps a customer-safe read model of the owning reseller's public contact and serves it to `workspace-bff`.
5. Should customers see locked "upgrade hints" for capabilities they never contracted (reseller-controlled setting)? (The "request to reseller" item is a support-service case of type `EXPANSION_REQUEST`, MICROSERVICES_MAP.)
6. Rich text/Markdown rendering for agent replies and knowledge previews: minimal in-house sanitizing renderer in `ui-rich-text` vs. a Markdown parser approved under STD-013 (TECH_STACK lists this as open).
7. ~~Login/MFA pages on the `auth.` host~~ — resolved by ADR-0021: Keycloak with the WAVER login theme; no fourth Angular app.
8. Is runtime language switching without reload required? It would need a runtime i18n library (not approved).
9. Which Material override mixins are needed to reach the brand's compact heights for every wrapped component, and whether any component must fall back to a custom implementation; to be settled during Phase 0 brand review.

## Decision
Pending review. Proposed: adopt the topology (`frontend-<app>` static images behind edge-gateway, one BFF per app), the Nx workspace with type/scope tags and error-level module boundaries, NgRx SignalStore for feature state with `@ngrx/store` limited to session/tenant context/notifications, Angular Material M3 themed exclusively from `--wv-*` tokens behind the `wv-*` wrapper layer with the listed custom components, the brand review and visual-regression gates against a default Material look, theme/density attributes, the Builder and "feature not contracted" UX, and the budgets and testing rules above.
