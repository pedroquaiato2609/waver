import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CatalogStore, ChargeModel, PriceEntry, PricingModel, ResellersStore } from '@waver/control-data-access-api';
import { WvButton, WvOption, WvSelect, WvStatusBadge, WvTextField } from '@waver/shared-ui-kit';
import {
  BOOK_STATUS,
  catalogProblemMessage,
  CHARGE_MODEL,
  formatMoney,
  options,
  parseAmount,
  PRICING_MODEL,
  SCOPE,
  skuCharge,
} from './labels';

/**
 * One price book (RFC-0017 §4): wholesale and retail per SKU while a draft, then activation. Activation needs
 * BASE_PRICE_CHANGE; until approval-service exists WAVER Commercial activates directly and it is audited.
 */
@Component({
  selector: 'wv-price-book-detail-page',
  imports: [DatePipe, ReactiveFormsModule, RouterLink, WvButton, WvSelect, WvStatusBadge, WvTextField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="back" routerLink="/catalogo" [queryParams]="{ secao: 'tabelas' }">Tabelas de preço</a>
    @if (book(); as b) {
      <header class="head">
        <div>
          <h1>{{ b.name }}</h1>
          <p class="sub"><span>{{ scopeText() }}</span><span>{{ b.currency }}</span>
            <span>vigente desde {{ b.validFrom | date: 'dd/MM/yyyy' }}@if (b.validTo) { até {{ b.validTo | date: 'dd/MM/yyyy' }} }</span></p>
        </div>
        <wv-status-badge [tone]="status[b.status].tone">{{ status[b.status].label }}</wv-status-badge>
      </header>

      <section class="next" [class.done]="b.status !== 'DRAFT'" aria-labelledby="next-title">
        <h2 id="next-title">Próximo passo</h2>
        @switch (b.status) {
          @case ('DRAFT') {
            @if (b.entries.length === 0) {
              <p>Cadastre o preço de atacado e de varejo de cada SKU desta tabela.</p>
            } @else {
              <p>Ao ativar, estes preços passam a valer para {{ scopeText().toLowerCase() }} e a tabela ativa anterior do mesmo
                alcance e moeda é substituída. Preços ativos não mudam mais.</p>
              <div class="actions"><wv-button [disabled]="store.saving()" (pressed)="activate(b.id)">Ativar tabela</wv-button></div>
              <p class="fine">Enquanto o fluxo de aprovações não existe, a mudança de preço base (BASE_PRICE_CHANGE) é aprovada
                diretamente pela equipe comercial e fica na auditoria.</p>
            }
          }
          @case ('ACTIVE') { <p>Ativa desde {{ b.activatedAt | date: 'dd/MM/yyyy HH:mm' }}. Para mudar preços, crie uma nova tabela.</p> }
          @case ('RETIRED') { <p>Substituída por uma tabela mais nova. Continua consultável para cotações e contratos antigos.</p> }
        }
        @if (errorMessage()) { <p class="error" role="alert">{{ errorMessage() }}</p> }
      </section>

      <section aria-labelledby="entries-title">
        <h2 id="entries-title">Preços</h2>
        @if (b.entries.length) {
          <table class="wv-table">
            <thead><tr><th scope="col">SKU</th><th scope="col">Atacado</th><th scope="col">Varejo</th>
              <th scope="col">Piso</th><th scope="col">Teto</th>@if (b.status === 'DRAFT') { <th scope="col"><span class="sr">Ações</span></th> }</tr></thead>
            <tbody>
              @for (e of b.entries; track e.skuId) {
                <tr>
                  <td>{{ skuLabel(e.skuId) }}<span class="key">{{ skuChargeText(e.skuId) }}</span></td>
                  <td class="num">{{ money(e.wholesaleAmount) }}<span class="key">{{ chargeModel[e.chargeModel] }}</span></td>
                  <td class="num">{{ retailText(e) }}<span class="key">{{ pricingModel[e.pricingModel] }}</span></td>
                  <td class="num">{{ money(e.floorAmount) }}</td>
                  <td class="num">{{ money(e.ceilingAmount) }}</td>
                  @if (b.status === 'DRAFT') {
                    <td><wv-button variant="quiet" [disabled]="store.saving()" (pressed)="remove(b.id, e.skuId)">Remover</wv-button></td>
                  }
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <p class="state">Nenhum preço ainda.</p>
        }

        @if (b.status === 'DRAFT') {
          <form class="panel" [formGroup]="form" (ngSubmit)="save(b.id)" aria-label="Preço de um SKU">
            <div class="grid">
              <wv-select label="SKU" formControlName="skuId" [options]="skuOptions()" />
              <wv-select label="Atacado cobrado" formControlName="chargeModel" [options]="chargeModels" />
              <wv-text-field label="Atacado (a revenda paga)" formControlName="wholesale" [maxLength]="16" />
              <wv-select label="Preço ao cliente" formControlName="pricingModel" [options]="pricingModels" />
              @if (form.controls.pricingModel.value === 'FIXED_RETAIL') {
                <wv-text-field label="Valor fixo ao cliente" formControlName="fixed" [maxLength]="16" />
              } @else {
                <wv-text-field [label]="form.controls.pricingModel.value === 'SUGGESTED_RETAIL' ? 'Sugerido ao cliente' : 'Sugerido (opcional)'"
                               formControlName="suggested" [maxLength]="16" />
                <wv-text-field label="Piso (opcional)" formControlName="floor" [maxLength]="16" />
                <wv-text-field label="Teto (opcional)" formControlName="ceiling" [maxLength]="16" />
              }
            </div>
            <p class="fine">Valores por unidade, em {{ b.currency }}. Aceita vírgula decimal.</p>
            <div class="actions"><wv-button type="submit" variant="secondary" [disabled]="store.saving()">Salvar preço</wv-button></div>
          </form>
        }
      </section>
    } @else if (store.loadError()) {
      <p class="state" role="alert">Tabela não encontrada.</p>
    } @else {
      <p class="state">Carregando...</p>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--wv-space-6); }
    .back { color: var(--wv-color-text-link); font-size: var(--wv-font-size-sm); text-underline-offset: 3px; align-self: flex-start; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--wv-space-6); }
    h1 { font-size: var(--wv-font-size-2xl); line-height: var(--wv-line-height-tight); }
    h2 { font-size: var(--wv-font-size-md); font-weight: var(--wv-font-weight-semibold); margin: 0 0 var(--wv-space-3); }
    .sub { margin: var(--wv-space-1) 0 0; color: var(--wv-color-text-secondary); display: flex; gap: var(--wv-space-4); flex-wrap: wrap; }
    .next {
      padding: var(--wv-space-5) var(--wv-space-6); border-radius: var(--wv-radius-lg); max-width: 760px; display: grid; gap: var(--wv-space-3);
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-left: 3px solid var(--wv-color-accent-highlight);
    }
    .next.done { border-left-color: var(--wv-color-status-success); }
    .next h2, .next p { margin: 0; }
    .fine { margin: 0; font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); }
    .actions { display: flex; gap: var(--wv-space-2); }
    .panel {
      margin-top: var(--wv-space-4); display: grid; gap: var(--wv-space-3); padding: var(--wv-space-4); max-width: 960px;
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-radius: var(--wv-radius-lg);
    }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--wv-space-3); align-items: start; }
    .num { font-variant-numeric: tabular-nums; white-space: nowrap; }
    .key { display: block; font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); white-space: normal; }
    .sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
    .error { margin: 0; color: var(--wv-color-status-danger); font-size: var(--wv-font-size-sm); }
    .state { color: var(--wv-color-text-secondary); margin: 0; }
  `,
})
export class PriceBookDetailPage {
  /** Route parameter (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly store = inject(CatalogStore);
  private readonly resellers = inject(ResellersStore);
  protected readonly status = BOOK_STATUS;
  protected readonly chargeModel = CHARGE_MODEL;
  protected readonly pricingModel = PRICING_MODEL;
  protected readonly chargeModels = options(CHARGE_MODEL);
  protected readonly pricingModels = options(PRICING_MODEL);
  private readonly localError = signal<string | null>(null);

  protected readonly form = new FormGroup({
    skuId: new FormControl('', { nonNullable: true }),
    chargeModel: new FormControl<string>('PER_UNIT', { nonNullable: true }),
    wholesale: new FormControl('', { nonNullable: true }),
    pricingModel: new FormControl<string>('WHOLESALE_PLUS_MARGIN', { nonNullable: true }),
    suggested: new FormControl('', { nonNullable: true }),
    fixed: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    ceiling: new FormControl('', { nonNullable: true }),
  });

  protected readonly book = computed(() => this.store.priceBooks().find((b) => b.id === this.id()) ?? null);
  /** SKUs of published and draft products; a book may be prepared before the product is published. */
  protected readonly skuOptions = computed<WvOption[]>(() =>
    this.store.products().flatMap((p) => p.skus.map((s) => ({ value: s.id, label: `${s.skuCode} · ${p.name}` }))));
  protected readonly scopeText = computed(() => {
    const b = this.book();
    if (!b || b.scope === 'GLOBAL') {
      return SCOPE.GLOBAL;
    }
    if (b.scope === 'TIER') {
      return `Tier ${b.scopeRef}`;
    }
    const r = this.resellers.items().find((x) => x.resellerOrgId === b.scopeRef);
    return r ? `Revenda ${r.tradeName ?? r.legalName}` : 'Uma revenda';
  });
  protected readonly errorMessage = computed(() => {
    const p = this.store.saveError();
    return this.localError() ?? (p ? catalogProblemMessage(p, 'salvar a tabela') : null);
  });

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => {
        this.store.clearSaveError();
        void this.store.refreshPriceBook(id);
        if (this.store.products().length === 0) {
          void this.store.load();
        }
        if (this.resellers.items().length === 0) {
          void this.resellers.load();
        }
      });
    });
  }

  protected money(amount: number | null): string {
    return formatMoney(amount, this.book()?.currency ?? 'BRL');
  }

  protected retailText(e: PriceEntry): string {
    const amount = e.pricingModel === 'FIXED_RETAIL' ? e.fixedAmount : e.suggestedAmount;
    return amount === null ? 'pela margem' : this.money(amount);
  }

  protected skuLabel(skuId: string): string {
    const found = this.store.skuIndex().get(skuId);
    return found ? `${found.sku.skuCode} · ${found.product.name}` : skuId.slice(0, 8);
  }

  protected skuChargeText(skuId: string): string {
    const found = this.store.skuIndex().get(skuId);
    return found ? skuCharge(found.sku) : '';
  }

  protected async save(bookId: string): Promise<void> {
    const v = this.form.getRawValue();
    const amounts = { wholesale: parseAmount(v.wholesale), suggested: parseAmount(v.suggested), fixed: parseAmount(v.fixed),
      floor: parseAmount(v.floor), ceiling: parseAmount(v.ceiling) };
    if (!v.skuId || amounts.wholesale === null || Object.values(amounts).some((a) => Number.isNaN(a))) {
      this.localError.set('Escolha o SKU e informe os valores em números (ex.: 199,90).');
      return;
    }
    this.localError.set(null);
    const fixedModel = v.pricingModel === 'FIXED_RETAIL';
    const saved = await this.store.putEntry(bookId, v.skuId, {
      wholesale: { chargeModel: v.chargeModel as ChargeModel, amount: amounts.wholesale },
      retail: {
        pricingModel: v.pricingModel as PricingModel,
        suggestedAmount: fixedModel ? null : amounts.suggested,
        fixedAmount: fixedModel ? amounts.fixed : null,
        floorAmount: fixedModel ? null : amounts.floor,
        ceilingAmount: fixedModel ? null : amounts.ceiling,
      },
    });
    if (saved) {
      this.form.reset();
    }
  }

  protected async remove(bookId: string, skuId: string): Promise<void> {
    await this.store.removeEntry(bookId, skuId);
  }

  protected async activate(bookId: string): Promise<void> {
    await this.store.activatePriceBook(bookId);
  }
}
