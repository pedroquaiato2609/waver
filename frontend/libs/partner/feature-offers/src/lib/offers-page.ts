import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { Offer, OfferSku, OffersStore } from '@waver/partner-data-access-api';
import { WvButton, WvPageHeader } from '@waver/shared-ui-kit';

const UNIT: Record<NonNullable<OfferSku['unit']>, string> = {
  WORKSPACE: 'workspace', CONNECTION: 'conexão', NUMBER: 'número', SEAT: 'assento', RUN: 'execução', TOKEN: 'token', HOUR: 'hora',
};
const CHARGE: Record<NonNullable<OfferSku['chargeType']>, string> = { RECURRING: 'Recorrente', USAGE: 'Por uso', ONE_TIME: 'Cobrança única' };
const PERIOD: Record<NonNullable<OfferSku['billingPeriod']>, string> = { MONTHLY: 'mensal', YEARLY: 'anual' };

const money = new Map<string, Intl.NumberFormat>();
function formatMoney(amount: number | null, currency: string | null): string {
  if (amount === null || !currency) {
    return '—';
  }
  let f = money.get(currency);
  if (!f) {
    f = new Intl.NumberFormat('pt-BR', { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 4 });
    money.set(currency, f);
  }
  return f.format(amount);
}
const pct = (v: number) => `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 }).format(v)}%`;

/**
 * Ofertas disponíveis (RFC-0017 §5): what WAVER made available to this reseller, with the wholesale price the
 * reseller pays and the retail price to its customers. Only listings whose availability rule includes this
 * reseller ever reach this page; the services decide that, not the screen.
 */
@Component({
  selector: 'wv-offers-page',
  imports: [DatePipe, WvButton, WvPageHeader],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <wv-page-header title="Ofertas disponíveis"
      description="O que a WAVER liberou para sua revenda vender: o preço que você paga (atacado) e o preço ao seu cliente." />

    @if (store.categories().length > 1) {
      <div class="filters" role="group" aria-label="Filtrar por categoria">
        <button type="button" [class.on]="!category()" (click)="category.set(null)">Todas</button>
        @for (c of store.categories(); track c) {
          <button type="button" [class.on]="category() === c" (click)="category.set(c)">{{ c }}</button>
        }
      </div>
    }

    @if (store.loading() && !store.loaded()) {
      <p class="state">Carregando ofertas...</p>
    } @else if (store.error()) {
      <div class="state" role="alert">Não foi possível carregar as ofertas.
        <wv-button variant="secondary" (pressed)="store.load()">Tentar de novo</wv-button></div>
    } @else if (visible().length === 0) {
      <div class="empty">
        <p><strong>Nenhuma oferta liberada para sua revenda ainda.</strong></p>
        <p>Quando a WAVER disponibilizar produtos para você, eles aparecem aqui com os preços. Fale com seu gerente de canal.</p>
      </div>
    } @else {
      @for (o of visible(); track o.listingId) {
        <article class="offer" [attr.aria-labelledby]="'offer-' + o.listingId">
          <header>
            <span class="eyebrow">{{ o.category }}</span>
            <h2 [id]="'offer-' + o.listingId">{{ o.title }}</h2>
            @if (o.summary) { <p class="summary">{{ o.summary }}</p> }
          </header>
          <table class="wv-table">
            <thead>
              <tr><th scope="col">Item</th><th scope="col" class="amount">Você paga</th><th scope="col" class="amount">Preço ao cliente</th>
                <th scope="col">Sua margem</th></tr>
            </thead>
            <tbody>
              @for (s of o.skus; track s.skuId) {
                <tr>
                  <td><code>{{ s.skuCode }}</code><span class="sub">{{ charge(s) }}</span></td>
                  @if (s.available) {
                    <td class="amount">{{ money(s.wholesaleUnitAmount, s.currency) }}<span class="sub">{{ per(s) }}</span></td>
                    <td class="amount">{{ money(s.retailUnitAmount, s.currency) }}<span class="sub">{{ retailNote(s) }}</span></td>
                    <td>{{ marginText(s) }}</td>
                  } @else {
                    <td colspan="3" class="muted">Sem preço para sua revenda no momento.</td>
                  }
                </tr>
              }
            </tbody>
          </table>
          <footer>
            @if (o.requirements) { <p><span class="label">Requisitos</span> {{ o.requirements }}</p> }
            @if (o.publishedAt) { <p class="muted">Versão {{ o.versionNo }}, publicada em {{ o.publishedAt | date: 'dd/MM/yyyy' }}</p> }
          </footer>
        </article>
      }
      <p class="fine">Valores sem impostos. Descontos acima do limite da sua revenda passam por aprovação da WAVER.</p>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--wv-space-6); }
    .filters { display: flex; gap: var(--wv-space-2); flex-wrap: wrap; }
    .filters button {
      font: inherit; font-size: var(--wv-font-size-sm); padding: var(--wv-space-1) var(--wv-space-3); border-radius: 999px; cursor: pointer;
      border: 1px solid var(--wv-color-border-control); background: var(--wv-color-bg-surface); color: var(--wv-color-text-primary);
    }
    .filters button.on { background: var(--wv-color-action-primary); border-color: var(--wv-color-action-primary); color: var(--wv-color-text-on-action, #fff); }
    .filters button:focus-visible { outline: 2px solid var(--wv-color-accent-highlight); outline-offset: 2px; }
    .offer {
      display: grid; gap: var(--wv-space-4); padding: var(--wv-space-6); max-width: 980px;
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-radius: var(--wv-radius-lg);
    }
    .offer header { display: grid; gap: var(--wv-space-1); }
    .eyebrow { font-size: var(--wv-font-size-xs); text-transform: uppercase; letter-spacing: 0.06em; color: var(--wv-color-text-secondary); }
    h2 { font-size: var(--wv-font-size-lg); font-weight: var(--wv-font-weight-semibold); margin: 0; }
    .summary { margin: 0; color: var(--wv-color-text-secondary); max-width: 720px; }
    .amount { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
    td.amount { font-weight: var(--wv-font-weight-medium); }
    .sub { display: block; font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); font-weight: normal; white-space: normal; }
    code { font-size: var(--wv-font-size-sm); }
    footer { display: grid; gap: var(--wv-space-1); }
    footer p { margin: 0; font-size: var(--wv-font-size-sm); }
    .label { font-weight: var(--wv-font-weight-semibold); margin-right: var(--wv-space-1); }
    .muted { color: var(--wv-color-text-secondary); }
    .fine { margin: 0; font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); }
    .state { color: var(--wv-color-text-secondary); display: flex; align-items: center; gap: var(--wv-space-4); margin: 0; }
    .empty { max-width: 560px; }
    .empty p { margin: 0 0 var(--wv-space-2); }
  `,
})
export class OffersPage implements OnInit {
  protected readonly store = inject(OffersStore);
  protected readonly category = signal<string | null>(null);
  protected readonly visible = computed<Offer[]>(() => {
    const c = this.category();
    return c ? this.store.items().filter((o) => o.category === c) : this.store.items();
  });

  ngOnInit(): void {
    void this.store.load();
  }

  protected money(amount: number | null, currency: string | null): string {
    return formatMoney(amount, currency);
  }

  protected charge(s: OfferSku): string {
    if (!s.chargeType || !s.unit) {
      return '';
    }
    const period = s.billingPeriod ? ` ${PERIOD[s.billingPeriod]}` : '';
    return `${CHARGE[s.chargeType]}${period} por ${UNIT[s.unit]}`;
  }

  protected per(s: OfferSku): string {
    return s.chargeModel === 'FLAT' ? 'valor fixo' : s.unit ? `por ${UNIT[s.unit]}` : '';
  }

  protected retailNote(s: OfferSku): string {
    switch (s.pricingModel) {
      case 'FIXED_RETAIL':
        return 'preço fixo da WAVER';
      case 'SUGGESTED_RETAIL':
        return 'sugerido, você pode ajustar';
      default:
        return 'atacado + sua margem';
    }
  }

  protected marginText(s: OfferSku): string {
    const bounds: string[] = [];
    if (s.marginBand) {
      bounds.push(s.marginBand.maxMarginPct === null ? `mínimo ${pct(s.marginBand.minMarginPct)}`
        : `${pct(s.marginBand.minMarginPct)} a ${pct(s.marginBand.maxMarginPct)}`);
    }
    if (s.retailFloorAmount !== null || s.retailCeilingAmount !== null) {
      bounds.push(`preço entre ${formatMoney(s.retailFloorAmount ?? s.wholesaleUnitAmount, s.currency)} e ${
        s.retailCeilingAmount === null ? 'sem teto' : formatMoney(s.retailCeilingAmount, s.currency)}`);
    }
    if (s.pricingModel === 'FIXED_RETAIL') {
      return 'Preço fixo';
    }
    return bounds.length ? bounds.join(' · ') : 'Livre';
  }
}
