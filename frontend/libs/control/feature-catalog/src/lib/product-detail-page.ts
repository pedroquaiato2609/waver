import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { BillingPeriod, CatalogStore, ChargeType, SkuUnit } from '@waver/control-data-access-api';
import { WvButton, WvSelect, WvStatusBadge, WvTextField } from '@waver/shared-ui-kit';
import { catalogProblemMessage, CHARGE, ITEM_TYPE, options, PERIOD, PRODUCT_STATUS, skuCharge, UNIT } from './labels';

/**
 * One catalog product (RFC-0017 §4): its SKUs while it is a draft, then publication. A published product can be
 * listed in the marketplace once each SKU it offers has a price in an active book.
 */
@Component({
  selector: 'wv-product-detail-page',
  imports: [DatePipe, ReactiveFormsModule, RouterLink, WvButton, WvSelect, WvStatusBadge, WvTextField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="back" routerLink="/catalogo">Catálogo</a>
    @if (product(); as p) {
      <header class="head">
        <div>
          <h1>{{ p.name }}</h1>
          <p class="sub"><span>{{ itemType[p.itemType] }}</span><code>{{ p.productKey }}</code>
            @if (p.technicalRef) { <span>ref. {{ p.technicalRef }}</span> }</p>
        </div>
        <wv-status-badge [tone]="status[p.status].tone">{{ status[p.status].label }}</wv-status-badge>
      </header>

      <section class="next" [class.done]="p.status === 'PUBLISHED'" aria-labelledby="next-title">
        <h2 id="next-title">Próximo passo</h2>
        @if (p.status === 'DRAFT') {
          @if (p.skus.length === 0) {
            <p>Cadastre os SKUs: a unidade que a revenda vende e como ela é cobrada.</p>
          } @else {
            <p>Ao publicar, o produto fica visível às revendas e pode ir para o marketplace. Os SKUs não mudam depois.</p>
            <div class="actions"><wv-button [disabled]="store.saving()" (pressed)="publish(p.id)">Publicar produto</wv-button></div>
          }
        } @else {
          <p>Publicado em {{ p.publishedAt | date: 'dd/MM/yyyy HH:mm' }}.
            @if (unpriced() > 0) { {{ unpriced() }} SKU(s) ainda sem preço ativo: cadastre-os em uma tabela de preço. }
            @else { Todos os SKUs têm preço ativo. Crie a oferta no marketplace. }</p>
          <div class="actions">
            <a class="link" routerLink="/catalogo" [queryParams]="{ secao: 'tabelas' }">Tabelas de preço</a>
            <a class="link" routerLink="/marketplace">Marketplace</a>
          </div>
        }
        @if (errorMessage()) { <p class="error" role="alert">{{ errorMessage() }}</p> }
      </section>

      <section aria-labelledby="skus-title">
        <h2 id="skus-title">SKUs</h2>
        @if (p.skus.length) {
          <table class="wv-table">
            <thead><tr><th scope="col">Código</th><th scope="col">Cobrança</th><th scope="col">Medidor</th><th scope="col">Preço ativo</th></tr></thead>
            <tbody>
              @for (s of p.skus; track s.id) {
                <tr>
                  <td><code>{{ s.skuCode }}</code></td>
                  <td>{{ charge(s) }}</td>
                  <td>{{ s.meterKey ?? '—' }}</td>
                  <td>{{ s.priced ? 'Sim' : 'Ainda não' }}</td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <p class="state">Nenhum SKU ainda.</p>
        }

        @if (p.status === 'DRAFT') {
          <form class="panel" [formGroup]="form" (ngSubmit)="addSku(p.id)" aria-label="Novo SKU">
            <div class="grid">
              <wv-text-field label="Código do SKU" formControlName="skuCode" [maxLength]="60" hint="Ex.: WV-AGENTE-MENSAL" />
              <wv-select label="Unidade" formControlName="unit" [options]="units" />
              <wv-select label="Cobrança" formControlName="chargeType" [options]="charges" />
              @if (form.controls.chargeType.value === 'RECURRING') {
                <wv-select label="Período" formControlName="billingPeriod" [options]="periods" />
              }
              @if (form.controls.chargeType.value === 'USAGE') {
                <wv-text-field label="Medidor" formControlName="meterKey" [maxLength]="80" hint="Ex.: agent.runs" />
              }
            </div>
            <div class="actions"><wv-button type="submit" variant="secondary" [disabled]="store.saving()">Adicionar SKU</wv-button></div>
          </form>
        }
      </section>
    } @else if (store.loadError()) {
      <p class="state" role="alert">Produto não encontrado.</p>
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
    code { font-size: var(--wv-font-size-sm); }
    .next {
      padding: var(--wv-space-5) var(--wv-space-6); border-radius: var(--wv-radius-lg); max-width: 760px; display: grid; gap: var(--wv-space-3);
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-left: 3px solid var(--wv-color-accent-highlight);
    }
    .next.done { border-left-color: var(--wv-color-status-success); }
    .next h2, .next p { margin: 0; }
    .actions { display: flex; gap: var(--wv-space-4); align-items: center; flex-wrap: wrap; }
    .link { color: var(--wv-color-text-link); text-underline-offset: 3px; }
    .panel {
      margin-top: var(--wv-space-4); display: grid; gap: var(--wv-space-3); padding: var(--wv-space-4); max-width: 860px;
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-radius: var(--wv-radius-lg);
    }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--wv-space-3); align-items: start; }
    .error { margin: 0; color: var(--wv-color-status-danger); font-size: var(--wv-font-size-sm); }
    .state { color: var(--wv-color-text-secondary); margin: 0; }
  `,
})
export class ProductDetailPage {
  /** Route parameter (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly store = inject(CatalogStore);
  protected readonly itemType = ITEM_TYPE;
  protected readonly status = PRODUCT_STATUS;
  protected readonly units = options(UNIT);
  protected readonly charges = options(CHARGE);
  protected readonly periods = options(PERIOD);
  private readonly localError = signal<string | null>(null);

  protected readonly form = new FormGroup({
    skuCode: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    unit: new FormControl<string>('WORKSPACE', { nonNullable: true }),
    chargeType: new FormControl<string>('RECURRING', { nonNullable: true }),
    billingPeriod: new FormControl<string>('MONTHLY', { nonNullable: true }),
    meterKey: new FormControl('', { nonNullable: true }),
  });

  protected readonly product = computed(() => this.store.products().find((p) => p.id === this.id()) ?? null);
  protected readonly unpriced = computed(() => (this.product()?.skus ?? []).filter((s) => s.priced === false).length);
  protected readonly errorMessage = computed(() => {
    const p = this.store.saveError();
    return this.localError() ?? (p ? catalogProblemMessage(p, 'salvar o produto') : null);
  });

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => {
        this.store.clearSaveError();
        void this.store.refreshProduct(id);
      });
    });
  }

  protected charge(s: { chargeType: ChargeType; billingPeriod: BillingPeriod | null; unit: SkuUnit }): string {
    return skuCharge(s);
  }

  protected async addSku(productId: string): Promise<void> {
    const v = this.form.getRawValue();
    if (!v.skuCode.trim()) {
      this.localError.set('Informe o código do SKU.');
      return;
    }
    this.localError.set(null);
    const chargeType = v.chargeType as ChargeType;
    const added = await this.store.addSku(productId, {
      skuCode: v.skuCode.trim(), unit: v.unit as SkuUnit, chargeType,
      billingPeriod: chargeType === 'RECURRING' ? (v.billingPeriod as BillingPeriod) : null,
      meterKey: chargeType === 'USAGE' ? v.meterKey.trim() || null : null,
    });
    if (added) {
      this.form.reset();
    }
  }

  protected async publish(productId: string): Promise<void> {
    await this.store.publishProduct(productId);
  }
}
