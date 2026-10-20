# STD-002 - Angular Coding Standard
Status: PROPOSED
Revision 1.3 (2026-10-19): aligned with ADR-0020..0027 (adopted Nx with scope tags and enforce-module-boundaries, NgRx SignalStore + @ngrx/store for app-wide state, Angular Material M3 behind `ui-*` wrappers, Keycloak login via the BFF).
Version: 1.3
Date: 2026-10-19
Applies to: the Nx workspace in `frontend/` (apps `control`, `partner`, `workspace`, `ui-catalog` and their `-e2e` projects; libraries under `libs/<scope>/<type>-<name>`)
Related: ADR-0006, ADR-0007, ADR-0009, ADR-0020, ADR-0021, ADR-0027, RFC-0004, RFC-0008, RFC-0016, RFC-0021, CONVENTIONS, BRAND_GUIDELINES, STD-003, STD-008, STD-009, STD-010, STD-013

Keywords MUST, MUST NOT, SHOULD follow RFC 2119. Allowed libraries: TECH_STACK (Angular, Angular CDK, RxJS, Signals, Playwright, OpenAPI Generator) plus ADR-0027: **Nx** (`nx`, `@nx/angular`, `@nx/eslint`, `@nx/eslint-plugin`, `@nx/playwright`), **NgRx** (`@ngrx/signals`, `@ngrx/store`, `@ngrx/effects`, `@ngrx/entity`, `@ngrx/operators`, `@ngrx/store-devtools` in development builds only) and **Angular Material** (M3). Other UI kits (PrimeNG, etc.), other state libraries (including third-party SignalStore extensions) and runtime i18n libraries are **not** allowed; additions follow STD-013.

## 1. Workspace structure (Nx, ADR-0027, RFC-0021 §2)
```text
frontend/
  nx.json  tsconfig.base.json  eslint.config.mjs  package-lock.json
  apps/control/  apps/partner/  apps/workspace/          # thin shells                       [type:app,  scope:<app>]
  apps/<app>-e2e/                                        # Playwright per audience           [type:e2e,  scope:<app>]
  apps/ui-catalog/                                       # dev/preview only                  [type:app,  scope:shared]
  libs/shared/ui-theme  ui-core  ui-layout  ui-data-grid  ui-feedback  ui-status  ui-charts  ui-rich-text   [type:ui]
  libs/shared/data-access-app-state                      # @ngrx/store: session, tenantContext, notifications
  libs/shared/util-i18n  util-signal-store  util-testing
  libs/<app>/data-access-api                             # generated BFF client, never edited by hand
  libs/<app>/data-access-<domain>                        # SignalStores + API facades of a domain
  libs/<app>/feature-<area>                              # routed screens (pages + feature components)
  libs/workspace/feature-builder-core  -objectives [builder:objectives]  -flow [builder:flow]  -capabilities  -testing  -delivery
  libs/workspace/data-access-builder                     # AgentDraftStore, DeploymentStore, PlaygroundStore (RFC-0016)
  libs/workspace/ui-builder                              # Builder presentational components on wv-* wrappers
  libs/workspace/ui-flow-canvas                          # Flow Builder canvas (custom, Angular CDK)
  libs/workspace/util-builder                            # graph validation helpers
```
- **A-01** Dependencies follow the Nx tags and are enforced by `@nx/enforce-module-boundaries` at error level: scope `control|partner|workspace` → same scope + `shared`; `shared` → `shared` only; `app` → `feature|data-access|ui|util`; `feature` → `data-access|ui|util` (never another feature); `data-access` → `data-access|util`; `ui` → `ui|util`; `util` → `util`. An app MUST NOT import from another app; libraries MUST NOT import from apps; deep imports past a library's `index.ts` are forbidden.
- **A-02** Apps are thin: `main.ts`, `app.config.ts` (providers, `SESSION_API` binding to the app's generated client), root `app.routes.ts`, shell composition. Screens live in `feature-*` libraries that export a `Routes` constant (`CONTRACTS_ROUTES`) with `pages/` (smart) and `components/` (presentational, feature-specific). The Builder areas of RFC-0016 are the libraries `feature-builder-core`, `-objectives`, `-flow`, `-capabilities`, `-testing`, `-delivery` (composed only by the `workspace` route table), with `data-access-builder`, `ui-builder`, `ui-flow-canvas` (imported only by `feature-builder-flow`) and `util-builder`. `feature-builder-objectives` (tag `builder:objectives`) and `feature-builder-flow` (tag `builder:flow`) carry mutually exclusive `notDependOnLibsWithTags` constraints and cannot import each other.
- **A-03** Component selectors use prefix `wv-` (`wv-contract-list-page`), files are kebab-case, one component per file. Libraries are named `libs/<scope>/<type>-<name>`, projects `<scope>-<type>-<name>`, import aliases `@waver/<scope>/<type>-<name>`. Every `project.json` declares exactly one `scope:*` and one `type:*` tag; libraries are created with the Nx generators so tags and lint config are present from the first commit.

```js
// eslint.config.mjs (excerpt)
'@nx/enforce-module-boundaries': ['error', {
  enforceBuildableLibDependency: true,
  depConstraints: [
    { sourceTag: 'scope:shared',    onlyDependOnLibsWithTags: ['scope:shared'] },
    { sourceTag: 'scope:control',   onlyDependOnLibsWithTags: ['scope:control', 'scope:shared'] },
    { sourceTag: 'scope:partner',   onlyDependOnLibsWithTags: ['scope:partner', 'scope:shared'] },
    { sourceTag: 'scope:workspace', onlyDependOnLibsWithTags: ['scope:workspace', 'scope:shared'] },
    { sourceTag: 'type:app',     onlyDependOnLibsWithTags: ['type:feature', 'type:data-access', 'type:ui', 'type:util'],
      bannedExternalImports: ['@angular/material', '@angular/material/*'] },
    { sourceTag: 'type:feature', onlyDependOnLibsWithTags: ['type:data-access', 'type:ui', 'type:util'],
      bannedExternalImports: ['@angular/material', '@angular/material/*', '@ngrx/store', '@ngrx/effects'] },
    { sourceTag: 'type:data-access', onlyDependOnLibsWithTags: ['type:data-access', 'type:util'],
      bannedExternalImports: ['@angular/material', '@angular/material/*'] },
    { sourceTag: 'type:ui',   onlyDependOnLibsWithTags: ['type:ui', 'type:util'],
      bannedExternalImports: ['@ngrx/*', '@angular/common/http'] },
    { sourceTag: 'type:util', onlyDependOnLibsWithTags: ['type:util'],
      bannedExternalImports: ['@angular/material', '@angular/material/*', '@ngrx/store', '@ngrx/effects'] },
    { sourceTag: 'builder:objectives', notDependOnLibsWithTags: ['builder:flow'] },        // RFC-0016
    { sourceTag: 'builder:flow',       notDependOnLibsWithTags: ['builder:objectives'] },
  ],
}],
```

## 2. Components
- **A-04** All components, directives and pipes are standalone. `NgModule` MUST NOT be created.
- **A-05** `changeDetection: ChangeDetectionStrategy.OnPush` on every component. New apps SHOULD run zoneless (`provideZonelessChangeDetection()`).
- **A-06** Use signal APIs: `input()`, `input.required()`, `output()`, `model()`, `viewChild()`; dependency injection with `inject()`. Templates use built-in control flow (`@if`, `@for` with `track`, `@switch`, `@defer`).
- **A-07** **Presentational** components receive data via inputs and emit outputs; they MUST NOT inject HTTP clients, stores or the router. **Smart** components (`pages/`) inject a store, read signals, call store methods and pass data down.
- **A-08** Experimental/developer-preview Angular, Material or NgRx APIs MUST NOT be used in production code without an approved exception.

```typescript
@Component({
  selector: 'wv-contract-list-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [WvDataGrid, WvEmptyState, WvErrorState, WvStatusBadge],   // from @waver/shared/ui-*, never @angular/material
  template: `
    @if (store.error(); as err) { <wv-error-state [problem]="err" (retry)="store.reload()" /> }
    @else if (store.entities().length === 0 && !store.isLoading()) { <wv-empty-state i18n="@@contracts.list.empty">Nenhum contrato ainda</wv-empty-state> }
    @else { <wv-data-grid [rows]="store.entities()" [loading]="store.isLoading()" (loadMore)="store.loadMore()" /> }`,
  providers: [ContractListStore],
})
export class ContractListPage { protected readonly store = inject(ContractListStore); }
```

## 3. State: NgRx SignalStore for feature state, RxJS for streams
- **A-09** Feature and domain state uses **NgRx SignalStore** (`signalStore` from `@ngrx/signals`). Domain stores live in `data-access-<domain>` libraries; stores that only serve one screen MAY live in the feature library. Stores are provided at route level (`providers` on the route) or on the smart component; feature stores MUST NOT be `providedIn: 'root'`.
- **A-10** State is protected (the `protectedState` default MUST NOT be disabled): it changes only inside the store's own methods via `patchState`. Components read signals and call methods; they MUST NOT write to a store or to another component's signals. Derived values are `withComputed`, never duplicated state.
- **A-11** RxJS is used for asynchronous streams (HTTP, SSE/chat token streams, debounce, cancellation). Async store methods use `rxMethod` with an explicit flattening operator — `switchMap` for queries and search, `exhaustMap` for load-more and submit, `concatMap` for ordered saves; `mergeMap` only with a written reason. Errors are handled with `tapResponse` **inside** the inner observable so the method stays alive. No manual `subscribe()` in components; any manual subscription elsewhere uses `takeUntilDestroyed()`.
- **A-36** Keyed collections use `withEntities` with an `entityConfig` whose `selectId` is the API's ID; collections are not kept as plain arrays searched with `find`.
- **A-37** Loading/error status, cursor pagination and draft autosave use the shared features of `@waver/shared/util-signal-store` (`withRequestStatus`, `withCursorPagination`, `withDraftAutosave`); stores MUST NOT re-implement them. Errors are stored as the typed `Problem` (STD-008), never as raw `HttpErrorResponse`.
- **A-38** `@ngrx/store` + effects are used **only** in `@waver/shared/data-access-app-state` for the slices `session`, `tenantContext` and `notifications` (RFC-0021 §3), built with `createFeature`, `createActionGroup` and functional effects; `@ngrx/entity` only where a slice holds a collection. Features read these slices only through the exported selectors (`store.selectSignal(...)`) and dispatch only the exported public actions. A new global slice requires an RFC-0021 amendment.
- **A-39** No state persistence: stores and slices live in memory; only the preferences service may use `localStorage` (A-28). `provideStoreDevtools` is registered only when `isDevMode()`.

```typescript
const contractConfig = entityConfig({ entity: type<ContractRow>(), selectId: (c) => c.contractId });

export const ContractListStore = signalStore(
  withEntities(contractConfig),
  withCursorPagination(),                       // cursor, hasMore, setPage, resetPage   (util-signal-store)
  withRequestStatus(),                          // isLoading, error, setLoading/Loaded/Error (util-signal-store)
  withMethods((store, api = inject(ContractsApi)) => {   // generated client from @waver/partner/data-access-api
    const loadMore = rxMethod<void>(pipe(
      filter(() => store.hasMore() && !store.isLoading()),
      tap(() => patchState(store, setLoading())),
      exhaustMap(() => api.listContracts({ limit: 50, cursor: store.cursor() ?? undefined }).pipe(
        tapResponse({
          next: (page) => patchState(store, addEntities(page.items, contractConfig),
                                     setPage(page.nextCursor), setLoaded()),
          error: (e: HttpErrorResponse) => patchState(store, setError(toProblem(e))),
        }))),
    ));
    return {
      loadMore,
      reload(): void { patchState(store, removeAllEntities(), resetPage()); loadMore(); },
    };
  }),
  withHooks({ onInit: (store) => store.loadMore() }),
);
```

```typescript
// reading app-wide state from a feature (A-38)
export class WorkspaceHomePage {
  private readonly store = inject(Store);
  protected readonly session = this.store.selectSignal(selectSession);          // exported by data-access-app-state
  protected readonly domainId = this.store.selectSignal(selectSelectedDomainId);
  switchDomain(id: string): void { this.store.dispatch(tenantContextActions.domainSelected({ domainId: id })); }
}
```

## 4. Routing and backend access
- **A-12** Every feature library is lazy loaded from the app route table (`loadChildren: () => import('@waver/partner/feature-contracts').then(m => m.CONTRACTS_ROUTES)`), with the feature's stores in the route `providers`. Heavy, below-the-fold widgets use `@defer`.
- **A-13** Route guards are functional (`CanActivateFn`), read the `session` slice and only improve UX; authorization is enforced by the BFF and services.
- **A-14** Each app talks **only to its own BFF** on the same origin (`/api/<screen-or-resource>`, RFC-0008) (ADR-0009). Calling domain services, Keycloak endpoints, edge-internal hosts or another app's BFF is forbidden.
- **A-15** HTTP calls use only clients generated by OpenAPI Generator from `contracts/openapi/<app>-bff/v<N>.yaml` into `libs/<app>/data-access-api` (`scope:<app>`, `type:data-access`). Hand-written `HttpClient` calls for BFF endpoints are forbidden. Shared libraries that need a BFF call depend on a port (`SESSION_API`) bound by the app.
- **A-16** Money arrives as `{ amount: string, currency }` and is displayed with `wvMoney` pipe (`Intl.NumberFormat` on the string); the UI MUST NOT do financial arithmetic.

## 5. Forms
- **A-17** Use typed reactive forms (`NonNullableFormBuilder`, `FormGroup<{...}>`); template-driven forms and untyped `FormGroup` are forbidden. Controls are the `ui-core` field wrappers (`wv-text-field`, `wv-select`, `wv-date-field`, …), which implement `ControlValueAccessor`; `matInput`/`mat-form-field` are never used in features.
- **A-18** Client validation mirrors the OpenAPI constraints for UX only; server `errors[]` from problem+json are mapped back to controls by `pointer` (helper in `util-i18n`).

```typescript
private readonly fb = inject(NonNullableFormBuilder);
readonly form = this.fb.group({
  name: this.fb.control('', [Validators.required, Validators.maxLength(120)]),
  strategy: this.fb.control<AgentStrategy>('OBJECTIVE_DRIVEN'),
});
// template: <wv-text-field formControlName="name" i18n-label="@@builder.wizard.name" label="Nome" />
```

## 6. i18n, locale and formatting
- **A-19** All user-visible strings are externalized with Angular built-in i18n (`i18n` attributes, `$localize`) using explicit IDs `@@<feature>.<screen>.<element>`. Source locale is `pt-BR`; `en-US` is the second locale. Hardcoded user-facing strings in TypeScript are forbidden. Material's internal strings are localized once in `ui-core` through the Material intl providers.
- **A-20** Backend error `code` values are mapped to messages in `@waver/shared/util-i18n` (`error-messages.ts`, STD-008); `detail` from the server is never shown verbatim to end users.
- **A-21** Dates and times are rendered in the user's time zone from UTC ISO strings; use Angular pipes / `Intl`, never manual string slicing. Plurals use ICU expressions.

## 7. Design tokens, Angular Material and styling
- **A-22** Feature code MUST NOT contain color literals (`#hex`, `rgb()`, `hsl()`, named colors). Use semantic CSS variables from the brand token set, e.g., `var(--wv-color-action-primary)`, `var(--wv-color-bg-surface)`, `var(--wv-color-text-primary)`, `var(--wv-space-2)`, `var(--wv-radius-sm)`, `var(--wv-control-height)`. Primitive tokens (`--wv-forest-900`, `--wv-mineral-600`), legacy `--waver-*` variables, Material tokens (`--mat-*`, `--mdc-*`) and Material classes (`.mat-*`, `.mdc-*`) MUST NOT be used outside `ui-*` libraries.
- **A-23** Feature code uses `ui-*` components instead of styling native controls; new generic visual patterns are added to a `ui-*` library, not to features.
- **A-24** No global CSS overrides of `ui-*` internals (`::ng-deep` forbidden outside `ui-*` libraries). The Material theme is defined only in `ui-theme`, which maps `--wv-*` onto Material system tokens (`mat.theme` + `mat.theme-overrides`, RFC-0021 §6.2); per-component Material overrides (`mat.<component>-overrides`) live only in the `ui-*` library that wraps that component. Theme and density are switched only through the token attributes on an element scope: `data-theme="dark"` (Control default; Workspace chat/agent-execution areas) and `data-density="compact"` (Material density −2 plus pinned heights). Components never branch on theme in TypeScript.
- **A-40** `@angular/material` is imported **only** by `type:ui` libraries. Features and apps never use Material components, directives, services (`MatDialog`, `MatSnackBar`) or harnesses directly; they use the wrappers (`wv-button`, `WvDialog`, `WvToast`, `WvDataGridHarness`). If a needed Material component has no wrapper, the wrapper is added to a `ui-*` library first (brand review applies, RFC-0021 §6.4).
- **A-41** Wrapper APIs expose WAVER vocabulary (`variant: 'primary' | 'secondary' | 'ghost' | 'danger'`, `size: 'sm' | 'md'`, status families) and MUST NOT pass Material-specific inputs (`color`, `appearance`, `disableRipple`) through. Ripples are disabled globally; Roboto and Material icon fonts are never loaded (icons come from the WAVER SVG set via `MatIconRegistry` in `ui-theme`).

```scss
/* the run panel is rendered inside a [data-theme="dark"] scope, so semantic tokens (and the Material
   system tokens mapped from them) resolve to Forest surfaces */
.wv-agent-run-panel { background: var(--wv-color-bg-surface); color: var(--wv-color-text-primary);
  padding: var(--wv-space-3); border-radius: var(--wv-radius-md); min-height: var(--wv-control-height); }
```

## 8. Accessibility (WCAG 2.2 AA)
- **A-25** Interactive elements are native elements or `ui-*` components (Angular Material and CDK a11y behavior inside: `FocusTrap`, `FocusMonitor`, `LiveAnnouncer`, `ListKeyManager`). `div`/`span` with `(click)` is forbidden.
- **A-26** Every control has an accessible name; icon-only buttons (`wv-icon-button`) require `ariaLabel` (i18n). Visible focus is never removed (Material strong focus indicators themed with `--wv-color-border-focus`). Pointer targets are at least 24×24 CSS px even in compact density (WCAG 2.5.8).
- **A-27** Status is never conveyed by color alone (badges carry text/icon). Async results (toast, streaming agent reply completion) are announced through `LiveAnnouncer` (exposed by `ui-core` as `WvAnnouncer`).

## 9. Security (ADR-0021, ADR-0009, STD-009)
- **A-28** Tokens never reach the browser: login happens at Keycloak through the BFF, and tokens stay in the BFF's Redis-backed session. The app MUST NOT store tokens, credentials or PII in `localStorage`, `sessionStorage`, IndexedDB, cookies readable by JS or NgRx state. Only UI preferences (density, theme) may use `localStorage`.
- **A-29** XSRF protection is on (`provideHttpClient(withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }))`).
- **A-30** `DomSanitizer.bypassSecurityTrust*`, `[innerHTML]` with unsanitized input, `eval`, `new Function` and dynamic script injection are forbidden in features. Agent/LLM output and user content are rendered as text unless passed through the reviewed `ui-rich-text` renderer. SVG icons are registered only from the bundled WAVER icon set (`MatIconRegistry` with trusted, build-time URLs in `ui-theme`).
- **A-31** Apps run under a strict CSP set at edge-gateway (`script-src 'self'`, no `unsafe-inline`/`unsafe-eval`, `require-trusted-types-for 'script'`); code MUST work with Trusted Types enabled. No external fonts, icon fonts or style sheets.
- **A-32** No feature-flag or entitlement logic is trusted client-side; the UI only reflects BFF answers (`features` in the `session` slice).

## 10. Testing, linting and CI
- **A-33** Unit/component tests run with `nx test <project>` using the Angular builder's unit-test runner for the pinned Angular version (no additional runner). Presentational components are tested through the component harnesses shipped by `ui-*` libraries (never Material harnesses directly). SignalStores are tested through `TestBed` with fakes of the generated clients (methods, computed signals, request status, error mapping); `@ngrx/store` reducers and selectors as pure functions, effects with `provideMockActions`, components reading app-wide state with `provideMockStore`.
- **A-34** E2E tests use Playwright per app/audience in `apps/<app>-e2e` (STD-010), with an axe-core accessibility check per page.
- **A-35** Lint runs in CI with zero warnings: `@nx/enforce-module-boundaries` (error), selector prefix `wv`, OnPush required, no `NgModule`, no `any` (`strict: true` and `strictTemplates: true`), restricted imports (`@angular/material` outside `type:ui`, `@ngrx/store`/`@ngrx/effects` outside `data-access-app-state` and app configs, other scopes' libraries, deep imports).
- **A-42** CI runs `nx affected -t lint test build e2e` against the merge base, plus the full graph nightly and on release branches. Targets declare `inputs`/`outputs` so cached results are correct; a self-hosted remote cache MAY be used and is written only by `main` pipelines. Toolchain upgrades use `nx migrate` in a dedicated PR.
- **A-43** Every `ui-*` component has visual-regression screenshots (Playwright on `apps/ui-catalog`) in light/dark × standard/compact; baselines change only with brand-owner approval in the PR.

## Enforcement
| Rule | Enforced by |
|---|---|
| A-01, A-02, A-14, A-15 boundaries / own BFF only | `@nx/enforce-module-boundaries` (error) with scope and type tags; CI graph check that no app's dependency closure contains another app's scope; ADR-0009 CI check on BFF clients |
| A-03 naming and tags | CI check that every `project.json` has exactly one `scope:*` and one `type:*` tag; Nx generators |
| A-04..A-06, A-35 | angular-eslint rules (`component-selector`, `prefer-on-push-component-change-detection`, `prefer-standalone`) |
| A-09..A-11, A-36..A-39 state | Lint restricted imports (`@ngrx/store`/`@ngrx/effects` location; `providedIn: 'root'` on `signalStore` flagged); review checklist; store tests (A-33) |
| A-12 lazy loading, budgets | `project.json` budgets per app (RFC-0021 §12) fail the build |
| A-19 externalized strings | `nx run <app>:extract-i18n` in CI; missing `en-US` translations fail the build |
| A-22..A-24, A-40, A-41 tokens and Material | CI script (`tooling/check-no-color-literals`) over `apps/**` and `libs/**` except token files; CI script check for `--mat-`, `.mat-`, `.mdc-`, `::ng-deep` outside `libs/shared/ui-*` and `libs/*/ui-*`; `bannedExternalImports` for `@angular/material`; CI check that every Material color role is overridden in `ui-theme`; brand review (RFC-0021 §6.4) |
| A-25..A-27 a11y | Template a11y lint rules; Playwright a11y checks; design review |
| A-28..A-31 security | Lint bans (`bypassSecurityTrust`, `localStorage` outside the preferences service); CSP report-only in staging, enforced in production; security review (STD-009) |
| A-33, A-34, A-42, A-43 | CI test stages (STD-012); required Nx affected job; visual-regression job on `ui-*` changes |
| Allowed libraries | `package.json` dependency allow-list check in CI (STD-013) |

## Open Questions
1. ~~Lint/format tooling and accessibility engine not in TECH_STACK~~ — angular-eslint, Prettier and axe-core are listed as build tooling in TECH_STACK; the Nx ESLint plugin comes with ADR-0027.
2. ~~`libs/flow-canvas` not listed in ADR-0007~~ — resolved by ADR-0027 and RFC-0016 (`libs/workspace/ui-flow-canvas` with `feature-builder-*`, `data-access-builder`, `ui-builder`, `util-builder`).
3. Angular built-in i18n produces one build per locale; switching language reloads the app. Confirm this is acceptable versus adding a runtime i18n library.
4. Chat rendering of agent Markdown needs a sanitizing renderer; no Markdown library is approved. Decide in-house minimal renderer in `ui-rich-text` vs. STD-013 request.
5. Unit test runner: confirm that the default runner of the pinned Angular builder (used through `nx test`) is covered by "Angular CLI" in TECH_STACK.
6. Material override coverage: confirm during Phase 0 that the `mat.*-overrides` mixins expose what is needed to reach the brand's compact heights for every wrapped component; components that cannot are implemented custom in `ui-*` (RFC-0021 Open Question 9).
