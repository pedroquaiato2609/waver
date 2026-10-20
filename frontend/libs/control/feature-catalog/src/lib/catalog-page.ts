import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  CatalogStore,
  DiscountEvaluation,
  ItemType,
  NewTarget,
  PolicyTarget,
  PriceScope,
  ResellersStore,
} from '@waver/control-data-access-api';
import { WvButton, WvOption, WvPageHeader, WvSelect, WvStatusBadge, WvTextField } from '@waver/shared-ui-kit';
import {
  BOOK_STATUS,
  catalogProblemMessage,
  formatMoney,
  formatPct,
  ITEM_TYPE,
  options,
  parseAmount,
  PRODUCT_STATUS,
  SCOPE,
} from './labels';

type Tab = 'produtos' | 'tabelas' | 'regras';

const text = (max: number, required = true) =>
  new FormControl('', { nonNullable: true, validators: required ? [Validators.required, Validators.maxLength(max)] : [Validators.maxLength(max)] });

const OUTCOME: Record<DiscountEvaluation['outcome'], string> = {
  WITHIN_POLICY: 'Dentro da política',
  APPROVAL_REQUIRED: 'Exige aprovação',
  FORBIDDEN: 'Não permitido',
};

const REASON: Record<string, string> = {
  RETAIL_PRICE_FIXED: 'o preço ao cliente é fixo',
  BELOW_WHOLESALE: 'abaixo do atacado',
  BELOW_FLOOR: 'abaixo do piso',
  ABOVE_CEILING: 'acima do teto',
  MARGIN_ABOVE_MAXIMUM: 'margem acima do máximo',
  MARGIN_BELOW_MINIMUM: 'margem abaixo do mínimo',
  DISCOUNT_ABOVE_LIMIT: 'desconto acima do limite',
  NO_DISCOUNT_POLICY: 'não há política de desconto para esta revenda',
};

/**
 * Catalog of WAVER Control (RFC-0017 §4): products and SKUs (Product Ops), price books, margin bands and
 * discount limits (Commercial). D-04 is a provisional default: every number shown here comes from these records.
 */
@Component({
  selector: 'wv-catalog-page',
  imports: [DatePipe, ReactiveFormsModule, RouterLink, WvButton, WvPageHeader, WvSelect, WvStatusBadge, WvTextField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <wv-page-header title="Catálogo"
      description="O que a WAVER vende pela rede: produtos e SKUs, tabelas de preço de atacado e varejo, e as faixas de margem e desconto de cada revenda." />

    <nav class="tabs" aria-label="Seções do catálogo">
      @for (t of tabs; track t.key) {
        <a [routerLink]="[]" [queryParams]="{ secao: t.key }" [class.active]="tab() === t.key"
           [attr.aria-current]="tab() === t.key ? 'page' : null">{{ t.label }}</a>
      }
    </nav>

    @if (store.loading() && store.products().length === 0) {
      <p class="state">Carregando o catálogo...</p>
    } @else if (store.loadError()) {
      <div class="state" role="alert">Não foi possível carregar o catálogo.
        <wv-button variant="secondary" (pressed)="store.load()">Tentar de novo</wv-button></div>
    } @else {
      @switch (tab()) {
        @case ('produtos') {
          <div class="bar">
            <p class="lead">Cada produto tem um ou mais SKUs. Depois de publicado, ele pode ir para o marketplace.</p>
            @if (!creating()) { <wv-button (pressed)="open('product')">Novo produto</wv-button> }
          </div>
          @if (creating() === 'product') {
            <form class="panel" [formGroup]="productForm" (ngSubmit)="createProduct()" aria-labelledby="new-product">
              <h2 id="new-product">Novo produto</h2>
              <div class="grid">
                <wv-text-field label="Nome" formControlName="name" [maxLength]="120" />
                <wv-text-field label="Chave" formControlName="productKey" [maxLength]="63" hint="Minúsculas e hífens, ex.: waver-agentes" />
                <wv-select label="Tipo de item" formControlName="itemType" [options]="itemTypes" />
                <wv-text-field label="Referência técnica (opcional)" formControlName="technicalRef" [maxLength]="120"
                               hint="toolId, connectorId ou tipo de canal" />
              </div>
              @if (errorMessage()) { <p class="error" role="alert">{{ errorMessage() }}</p> }
              <div class="actions">
                <wv-button type="submit" [disabled]="store.saving()">Criar rascunho</wv-button>
                <wv-button variant="quiet" (pressed)="creating.set(null)">Cancelar</wv-button>
              </div>
            </form>
          }
          @if (store.products().length === 0) {
            <p class="state">Nenhum produto ainda. Crie o primeiro para cadastrar SKUs e preços.</p>
          } @else {
            <table class="wv-table">
              <thead><tr><th scope="col">Produto</th><th scope="col">Tipo</th><th scope="col">SKUs</th>
                <th scope="col">Situação</th><th scope="col">Criado em</th></tr></thead>
              <tbody>
                @for (p of store.products(); track p.id) {
                  <tr>
                    <td class="name"><a [routerLink]="['/catalogo/produtos', p.id]">{{ p.name }}</a><span class="key">{{ p.productKey }}</span></td>
                    <td>{{ itemType[p.itemType] }}</td>
                    <td class="num">{{ p.skus.length }}</td>
                    <td><wv-status-badge [tone]="productStatus[p.status].tone">{{ productStatus[p.status].label }}</wv-status-badge></td>
                    <td>{{ p.createdAt | date: 'dd/MM/yyyy' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        }
        @case ('tabelas') {
          <div class="bar">
            <p class="lead">Preços de atacado (o que a revenda paga) e de varejo (o que ela cobra). Preços só mudam em rascunho;
              ao ativar, a tabela anterior do mesmo alcance e moeda é substituída e continua consultável.</p>
            @if (!creating()) { <wv-button (pressed)="open('book')">Nova tabela</wv-button> }
          </div>
          @if (creating() === 'book') {
            <form class="panel" [formGroup]="bookForm" (ngSubmit)="createBook()" aria-labelledby="new-book">
              <h2 id="new-book">Nova tabela de preço</h2>
              <div class="grid">
                <wv-text-field label="Nome" formControlName="name" [maxLength]="120" hint="Ex.: Tabela nacional 2026" />
                <wv-text-field label="Moeda" formControlName="currency" [maxLength]="3" />
                <wv-select label="Vale para" formControlName="scope" [options]="scopes" />
                @if (bookForm.controls.scope.value === 'TIER') {
                  <wv-text-field label="Tier" formControlName="scopeRef" [maxLength]="40" hint="Ex.: GOLD" />
                } @else if (bookForm.controls.scope.value === 'RESELLER') {
                  <wv-select label="Revenda" formControlName="scopeRef" [options]="resellerOptions()" />
                }
              </div>
              @if (errorMessage()) { <p class="error" role="alert">{{ errorMessage() }}</p> }
              <div class="actions">
                <wv-button type="submit" [disabled]="store.saving()">Criar rascunho</wv-button>
                <wv-button variant="quiet" (pressed)="creating.set(null)">Cancelar</wv-button>
              </div>
            </form>
          }
          @if (store.priceBooks().length === 0) {
            <p class="state">Nenhuma tabela ainda.</p>
          } @else {
            <table class="wv-table">
              <thead><tr><th scope="col">Tabela</th><th scope="col">Vale para</th><th scope="col">Moeda</th>
                <th scope="col">SKUs</th><th scope="col">Situação</th><th scope="col">Ativada em</th></tr></thead>
              <tbody>
                @for (b of store.priceBooks(); track b.id) {
                  <tr>
                    <td class="name"><a [routerLink]="['/catalogo/tabelas', b.id]">{{ b.name }}</a></td>
                    <td>{{ scopeText(b) }}</td>
                    <td>{{ b.currency }}</td>
                    <td class="num">{{ b.entries.length }}</td>
                    <td><wv-status-badge [tone]="bookStatus[b.status].tone">{{ bookStatus[b.status].label }}</wv-status-badge></td>
                    <td>{{ b.activatedAt ? (b.activatedAt | date: 'dd/MM/yyyy HH:mm') : '—' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        }
        @case ('regras') {
          <p class="lead">Padrão provisório D-04: atacado + margem com faixa mínima e máxima; desconto acima do limite exige aprovação.
            A regra mais específica vale: revenda, depois tier, depois todas; um SKU específico vence a regra geral.</p>
          <div class="two">
            <section aria-labelledby="rules-title">
              <h2 id="rules-title">Faixas de margem</h2>
              <form class="panel compact" [formGroup]="ruleForm" (ngSubmit)="createRule()">
                <div class="grid">
                  <wv-select label="Vale para" formControlName="scope" [options]="scopes" />
                  @if (ruleForm.controls.scope.value === 'TIER') { <wv-text-field label="Tier" formControlName="scopeRef" [maxLength]="40" /> }
                  @if (ruleForm.controls.scope.value === 'RESELLER') {
                    <wv-select label="Revenda" formControlName="scopeRef" [options]="resellerOptions()" />
                  }
                  <wv-select label="SKU" formControlName="skuId" [options]="skuOptions()" />
                  <wv-text-field label="Margem mínima (%)" formControlName="min" [maxLength]="8" />
                  <wv-text-field label="Margem máxima (%, opcional)" formControlName="max" [maxLength]="8" />
                </div>
                <div class="actions"><wv-button type="submit" variant="secondary" [disabled]="store.saving()">Adicionar faixa</wv-button></div>
              </form>
              <ul class="rules">
                @for (r of store.pricingRules(); track r.id) {
                  <li><strong>{{ formatPct(r.minMarginPct) }} a {{ r.maxMarginPct === null ? 'sem teto' : formatPct(r.maxMarginPct) }}</strong>
                    <span>{{ targetText(r.target) }}</span></li>
                } @empty { <li class="muted">Nenhuma faixa. Sem faixa, a margem não é verificada.</li> }
              </ul>
            </section>
            <section aria-labelledby="policies-title">
              <h2 id="policies-title">Limites de desconto</h2>
              <form class="panel compact" [formGroup]="policyForm" (ngSubmit)="createPolicy()">
                <div class="grid">
                  <wv-select label="Vale para" formControlName="scope" [options]="scopes" />
                  @if (policyForm.controls.scope.value === 'TIER') { <wv-text-field label="Tier" formControlName="scopeRef" [maxLength]="40" /> }
                  @if (policyForm.controls.scope.value === 'RESELLER') {
                    <wv-select label="Revenda" formControlName="scopeRef" [options]="resellerOptions()" />
                  }
                  <wv-select label="SKU" formControlName="skuId" [options]="skuOptions()" />
                  <wv-text-field label="Desconto sem aprovação até (%)" formControlName="max" [maxLength]="6" />
                </div>
                <div class="actions"><wv-button type="submit" variant="secondary" [disabled]="store.saving()">Adicionar limite</wv-button></div>
              </form>
              <ul class="rules">
                @for (p of store.discountPolicies(); track p.id) {
                  <li><strong>até {{ formatPct(p.maxDiscountWithoutApprovalPct) }}</strong>
                    <span>{{ targetText(p.target) }} · acima disso: {{ p.approvalPolicyKey }}</span></li>
                } @empty { <li class="muted">Nenhum limite. Sem limite, qualquer desconto exige aprovação.</li> }
              </ul>
            </section>
          </div>
          @if (errorMessage()) { <p class="error" role="alert">{{ errorMessage() }}</p> }

          <section class="panel sim" aria-labelledby="sim-title">
            <h2 id="sim-title">Simular um preço</h2>
            <p class="hint">Avalia um preço ao cliente para uma revenda, como o contrato fará (POST /v1/discount-policy-evaluations).</p>
            <form [formGroup]="simForm" (ngSubmit)="simulate()" class="grid">
              <wv-select label="Revenda" formControlName="resellerOrgId" [options]="resellerOptions()" />
              <wv-select label="SKU" formControlName="skuId" [options]="pricedSkuOptions()" />
              <wv-text-field label="Preço unitário ao cliente" formControlName="price" [maxLength]="16" />
              <div class="actions"><wv-button type="submit" variant="secondary" [disabled]="store.saving()">Avaliar</wv-button></div>
            </form>
            @if (evaluation(); as e) {
              <div class="verdict" [attr.data-outcome]="e.outcome" role="status">
                <strong>{{ outcome[e.outcome] }}</strong>
                <span>Atacado {{ money(e.quote.wholesaleUnitAmount, e.quote.currency) }} · lista {{ money(e.quote.retailUnitAmount, e.quote.currency) }}
                  · desconto {{ formatPct(e.discountPct) }} · margem {{ formatPct(e.marginPct) }}</span>
                @if (e.reasons.length) { <span>Motivo: {{ reasonsText(e.reasons) }}.</span> }
                @if (e.approvalPolicyKey) { <span>Política de aprovação: {{ e.approvalPolicyKey }}</span> }
              </div>
            }
          </section>
        }
      }
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--wv-space-6); }
    .tabs { display: flex; gap: var(--wv-space-6); border-bottom: 1px solid var(--wv-color-border-subtle); }
    .tabs a {
      padding: var(--wv-space-2) 0; color: var(--wv-color-text-secondary); text-decoration: none; font-weight: var(--wv-font-weight-medium);
      border-bottom: 2px solid transparent; margin-bottom: -1px;
    }
    .tabs a.active { color: var(--wv-color-text-primary); border-bottom-color: var(--wv-color-accent-highlight); }
    .bar { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--wv-space-6); }
    .lead { margin: 0; color: var(--wv-color-text-secondary); max-width: 720px; }
    .panel {
      display: grid; gap: var(--wv-space-4); padding: var(--wv-space-6); max-width: 860px;
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-radius: var(--wv-radius-lg);
    }
    .panel.compact { padding: var(--wv-space-4); }
    .panel h2, section h2 { font-size: var(--wv-font-size-md); font-weight: var(--wv-font-weight-semibold); margin: 0; }
    section h2 { margin-bottom: var(--wv-space-3); }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--wv-space-3); align-items: start; }
    .actions { display: flex; gap: var(--wv-space-2); align-items: center; }
    .error { margin: 0; color: var(--wv-color-status-danger); font-size: var(--wv-font-size-sm); }
    .hint { margin: 0; color: var(--wv-color-text-secondary); font-size: var(--wv-font-size-sm); }
    .state { color: var(--wv-color-text-secondary); display: flex; align-items: center; gap: var(--wv-space-4); margin: 0; }
    .name a { color: inherit; font-weight: var(--wv-font-weight-medium); text-decoration: none; }
    .name a:hover { text-decoration: underline; text-underline-offset: 3px; }
    .key { display: block; font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); }
    .num { font-variant-numeric: tabular-nums; }
    .two { display: grid; grid-template-columns: 1fr 1fr; gap: var(--wv-space-8); align-items: start; }
    .rules { list-style: none; margin: var(--wv-space-3) 0 0; padding: 0; display: grid; gap: var(--wv-space-2); }
    .rules li { display: grid; gap: 2px; padding: var(--wv-space-2) 0; border-bottom: 1px solid var(--wv-color-border-subtle); }
    .rules span, .muted { color: var(--wv-color-text-secondary); font-size: var(--wv-font-size-sm); }
    .rules strong { font-variant-numeric: tabular-nums; }
    .verdict {
      display: grid; gap: var(--wv-space-1); padding: var(--wv-space-3) var(--wv-space-4); border-radius: var(--wv-radius-md);
      border-left: 3px solid var(--wv-color-status-success); background: var(--wv-color-bg-canvas); font-variant-numeric: tabular-nums;
    }
    .verdict[data-outcome='APPROVAL_REQUIRED'] { border-left-color: var(--wv-color-status-warning); }
    .verdict[data-outcome='FORBIDDEN'] { border-left-color: var(--wv-color-status-danger); }
    .verdict span { font-size: var(--wv-font-size-sm); color: var(--wv-color-text-secondary); }
    @media (max-width: 960px) { .two { grid-template-columns: 1fr; } .bar { flex-direction: column; } }
  `,
})
export class CatalogPage implements OnInit {
  /** Query parameter ?secao= (withComponentInputBinding). */
  readonly secao = input<string>();

  protected readonly store = inject(CatalogStore);
  private readonly resellers = inject(ResellersStore);
  private readonly router = inject(Router);
  protected readonly tabs: { key: Tab; label: string }[] = [
    { key: 'produtos', label: 'Produtos' },
    { key: 'tabelas', label: 'Tabelas de preço' },
    { key: 'regras', label: 'Margem e desconto' },
  ];
  protected readonly tab = computed<Tab>(() => (['produtos', 'tabelas', 'regras'].includes(this.secao() ?? '') ? this.secao() as Tab : 'produtos'));
  protected readonly creating = signal<'product' | 'book' | null>(null);
  protected readonly evaluation = signal<DiscountEvaluation | null>(null);
  private readonly localError = signal<string | null>(null);

  protected readonly itemType = ITEM_TYPE;
  protected readonly productStatus = PRODUCT_STATUS;
  protected readonly bookStatus = BOOK_STATUS;
  protected readonly outcome = OUTCOME;
  protected readonly itemTypes = options(ITEM_TYPE);
  protected readonly scopes = options(SCOPE);
  protected readonly formatPct = formatPct;

  protected readonly productForm = new FormGroup({
    name: text(120), productKey: text(63), itemType: new FormControl<string>('PRODUCT', { nonNullable: true }), technicalRef: text(120, false),
  });
  protected readonly bookForm = new FormGroup({
    name: text(120), currency: new FormControl('BRL', { nonNullable: true }), scope: new FormControl<string>('GLOBAL', { nonNullable: true }),
    scopeRef: text(60, false),
  });
  protected readonly ruleForm = new FormGroup({
    scope: new FormControl<string>('GLOBAL', { nonNullable: true }), scopeRef: text(60, false), skuId: new FormControl('', { nonNullable: true }),
    min: text(8), max: text(8, false),
  });
  protected readonly policyForm = new FormGroup({
    scope: new FormControl<string>('GLOBAL', { nonNullable: true }), scopeRef: text(60, false), skuId: new FormControl('', { nonNullable: true }),
    max: text(6),
  });
  protected readonly simForm = new FormGroup({
    resellerOrgId: new FormControl('', { nonNullable: true }), skuId: new FormControl('', { nonNullable: true }), price: text(16),
  });

  protected readonly resellerOptions = computed<WvOption[]>(() => this.resellers.items()
    .filter((r) => r.resellerOrgId)
    .map((r) => ({ value: r.resellerOrgId as string, label: r.tradeName ?? r.legalName })));
  protected readonly skuOptions = computed<WvOption[]>(() => [
    { value: '', label: 'Todos os SKUs' },
    ...this.store.products().flatMap((p) => p.skus.map((s) => ({ value: s.id, label: `${s.skuCode} · ${p.name}` }))),
  ]);
  protected readonly pricedSkuOptions = computed<WvOption[]>(() => {
    const priced = new Set(this.store.priceBooks().filter((b) => b.status === 'ACTIVE').flatMap((b) => b.entries.map((e) => e.skuId)));
    return this.skuOptions().filter((o) => priced.has(o.value));
  });
  protected readonly errorMessage = computed(() => {
    const p = this.store.saveError();
    return this.localError() ?? (p ? catalogProblemMessage(p, 'salvar') : null);
  });

  ngOnInit(): void {
    void this.store.load();
    if (this.resellers.items().length === 0) {
      void this.resellers.load();
    }
  }

  protected open(what: 'product' | 'book'): void {
    this.store.clearSaveError();
    this.localError.set(null);
    this.productForm.reset();
    this.bookForm.reset();
    this.creating.set(what);
  }

  protected async createProduct(): Promise<void> {
    if (this.productForm.invalid) {
      this.localError.set('Informe o nome e a chave do produto.');
      return;
    }
    this.localError.set(null);
    const v = this.productForm.getRawValue();
    const p = await this.store.createProduct({ name: v.name, productKey: v.productKey.trim(), itemType: v.itemType as ItemType,
      technicalRef: v.technicalRef || null });
    if (p) {
      this.creating.set(null);
      void this.router.navigate(['/catalogo/produtos', p.id]);
    }
  }

  protected async createBook(): Promise<void> {
    if (this.bookForm.invalid) {
      this.localError.set('Informe o nome da tabela.');
      return;
    }
    this.localError.set(null);
    const v = this.bookForm.getRawValue();
    const b = await this.store.createPriceBook({ name: v.name, currency: v.currency.trim().toUpperCase(), scope: v.scope as PriceScope,
      scopeRef: v.scope === 'GLOBAL' ? null : v.scopeRef.trim() || null });
    if (b) {
      this.creating.set(null);
      void this.router.navigate(['/catalogo/tabelas', b.id]);
    }
  }

  protected async createRule(): Promise<void> {
    const v = this.ruleForm.getRawValue();
    const min = parseAmount(v.min);
    const max = parseAmount(v.max);
    if (min === null || Number.isNaN(min) || Number.isNaN(max)) {
      this.localError.set('Informe a margem mínima (e a máxima, se houver) em números.');
      return;
    }
    this.localError.set(null);
    if (await this.store.definePricingRule(this.target(v), min, max)) {
      this.ruleForm.reset();
    }
  }

  protected async createPolicy(): Promise<void> {
    const v = this.policyForm.getRawValue();
    const max = parseAmount(v.max);
    if (max === null || Number.isNaN(max)) {
      this.localError.set('Informe o limite de desconto em números.');
      return;
    }
    this.localError.set(null);
    if (await this.store.defineDiscountPolicy(this.target(v), max, null)) {
      this.policyForm.reset();
    }
  }

  protected async simulate(): Promise<void> {
    const v = this.simForm.getRawValue();
    const price = parseAmount(v.price);
    if (!v.resellerOrgId || !v.skuId || price === null || Number.isNaN(price)) {
      this.localError.set('Escolha a revenda, o SKU e informe o preço.');
      return;
    }
    this.localError.set(null);
    this.evaluation.set(await this.store.evaluateDiscount(v.resellerOrgId, v.skuId, price));
  }

  protected scopeText(b: { scope: PriceScope; scopeRef: string | null }): string {
    if (b.scope === 'GLOBAL') {
      return SCOPE.GLOBAL;
    }
    if (b.scope === 'TIER') {
      return `Tier ${b.scopeRef}`;
    }
    return this.resellerOptions().find((o) => o.value === b.scopeRef)?.label ?? `Revenda ${b.scopeRef?.slice(0, 8)}`;
  }

  protected targetText(t: PolicyTarget): string {
    const sku = t.skuId ? this.store.skuIndex().get(t.skuId)?.sku.skuCode ?? 'um SKU' : 'todos os SKUs';
    return `${this.scopeText(t)} · ${sku}`;
  }

  protected reasonsText(reasons: string[]): string {
    return reasons.map((r) => REASON[r] ?? r).join('; ');
  }

  protected money(amount: number | null, currency: string | null): string {
    return formatMoney(amount, currency ?? 'BRL');
  }

  private target(v: { scope: string; scopeRef: string; skuId: string }): NewTarget {
    return { scope: v.scope as PriceScope, scopeRef: v.scope === 'GLOBAL' ? null : v.scopeRef.trim() || null, skuId: v.skuId || null };
  }
}
