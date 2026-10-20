import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { CatalogStore, Listing, MarketplaceStore } from '@waver/control-data-access-api';
import { WvButton, WvOption, WvPageHeader, WvSelect, WvStatusBadge, WvTextField } from '@waver/shared-ui-kit';
import { LISTING_STATUS, marketplaceProblemMessage, MODE } from './labels';

const text = (max: number, required = true) =>
  new FormControl('', { nonNullable: true, validators: required ? [Validators.required, Validators.maxLength(max)] : [Validators.maxLength(max)] });

/**
 * Marketplace of WAVER Control (RFC-0017 §5): offers built on published catalog products, reviewed and published
 * by WAVER Product Ops, and made available to all resellers, some tiers or chosen resellers.
 */
@Component({
  selector: 'wv-marketplace-page',
  imports: [ReactiveFormsModule, RouterLink, WvButton, WvPageHeader, WvSelect, WvStatusBadge, WvTextField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <wv-page-header title="Marketplace"
      description="As ofertas que as revendas podem vender. Cada oferta usa um produto publicado do catálogo, passa por revisão e só aparece para as revendas que a regra de disponibilidade incluir.">
      @if (!creating()) { <wv-button (pressed)="open()">Nova oferta</wv-button> }
    </wv-page-header>

    @if (creating()) {
      <form class="panel" [formGroup]="form" (ngSubmit)="submit()" aria-labelledby="new-listing">
        <h2 id="new-listing">Nova oferta</h2>
        @if (catalog.publishedProducts().length === 0) {
          <p class="hint">Nenhum produto publicado no catálogo ainda. Publique um produto antes de criar a oferta.</p>
        }
        <div class="grid">
          <wv-select label="Produto do catálogo" formControlName="productId" [options]="productOptions()" />
          <wv-text-field label="Título" formControlName="title" [maxLength]="120" />
          <wv-text-field label="Chave" formControlName="listingKey" [maxLength]="63" hint="Minúsculas e hífens" />
          <wv-text-field label="Categoria" formControlName="category" [maxLength]="60" hint="Ex.: Agentes, Canais, Conectores" />
          <wv-text-field class="wide" label="Resumo (opcional)" formControlName="summary" [maxLength]="600" />
          <wv-text-field class="wide" label="Requisitos (opcional)" formControlName="requirements" [maxLength]="2000"
                         hint="O que o cliente precisa ter para usar." />
        </div>
        @if (skusOfProduct().length) {
          <fieldset>
            <legend>SKUs oferecidos</legend>
            @for (s of skusOfProduct(); track s.id) {
              <label class="check"><input type="checkbox" [checked]="chosen().has(s.id)" (change)="toggle(s.id)" /> <code>{{ s.skuCode }}</code></label>
            }
          </fieldset>
        }
        @if (errorMessage()) { <p class="error" role="alert">{{ errorMessage() }}</p> }
        <div class="actions">
          <wv-button type="submit" [disabled]="store.saving()">Criar rascunho</wv-button>
          <wv-button variant="quiet" (pressed)="creating.set(false)">Cancelar</wv-button>
        </div>
      </form>
    }

    <ol class="track" aria-label="Ofertas por etapa">
      <li><strong>{{ store.counts().draft }}</strong><span>em rascunho</span></li>
      <li><strong>{{ store.counts().review }}</strong><span>em revisão</span></li>
      <li><strong>{{ store.counts().published }}</strong><span>publicadas</span></li>
    </ol>

    @if (store.loading() && store.items().length === 0) {
      <p class="state">Carregando ofertas...</p>
    } @else if (store.loadError()) {
      <div class="state" role="alert">Não foi possível carregar o marketplace.
        <wv-button variant="secondary" (pressed)="store.load()">Tentar de novo</wv-button></div>
    } @else if (store.items().length === 0) {
      <p class="state">Nenhuma oferta ainda.</p>
    } @else {
      <table class="wv-table">
        <thead><tr><th scope="col">Oferta</th><th scope="col">Produto</th><th scope="col">Versão</th>
          <th scope="col">Disponível para</th><th scope="col">Situação</th></tr></thead>
        <tbody>
          @for (l of store.items(); track l.id) {
            <tr>
              <td class="name"><a [routerLink]="['/marketplace', l.id]">{{ l.title }}</a><span class="key">{{ l.category }}</span></td>
              <td>{{ productName(l.productId) }}</td>
              <td class="num">{{ l.currentVersion ? 'v' + l.currentVersion.versionNo : '—' }}</td>
              <td>{{ availabilityText(l) }}</td>
              <td><wv-status-badge [tone]="status[l.status].tone">{{ status[l.status].label }}</wv-status-badge></td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--wv-space-6); }
    .panel {
      display: grid; gap: var(--wv-space-4); padding: var(--wv-space-6); max-width: 860px;
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-radius: var(--wv-radius-lg);
    }
    .panel h2 { font-size: var(--wv-font-size-lg); margin: 0; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--wv-space-3); }
    .wide { grid-column: 1 / -1; }
    fieldset { border: 0; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: var(--wv-space-4); }
    legend { font-weight: var(--wv-font-weight-semibold); font-size: var(--wv-font-size-sm); margin-bottom: var(--wv-space-2); padding: 0; }
    .check { display: inline-flex; gap: var(--wv-space-2); align-items: center; }
    .hint { margin: 0; color: var(--wv-color-text-secondary); font-size: var(--wv-font-size-sm); }
    .error { margin: 0; color: var(--wv-color-status-danger); font-size: var(--wv-font-size-sm); }
    .actions { display: flex; gap: var(--wv-space-2); }
    .track { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: var(--wv-space-10); }
    .track li { display: flex; align-items: baseline; gap: var(--wv-space-2); }
    .track strong { font-size: var(--wv-font-size-2xl); font-weight: var(--wv-font-weight-semibold); font-variant-numeric: tabular-nums; }
    .track span { color: var(--wv-color-text-secondary); }
    .state { color: var(--wv-color-text-secondary); display: flex; align-items: center; gap: var(--wv-space-4); margin: 0; }
    .name a { color: inherit; font-weight: var(--wv-font-weight-medium); text-decoration: none; }
    .name a:hover { text-decoration: underline; text-underline-offset: 3px; }
    .key { display: block; font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); }
    .num { font-variant-numeric: tabular-nums; }
  `,
})
export class MarketplacePage implements OnInit {
  protected readonly store = inject(MarketplaceStore);
  protected readonly catalog = inject(CatalogStore);
  private readonly router = inject(Router);
  protected readonly status = LISTING_STATUS;
  protected readonly creating = signal(false);
  protected readonly chosen = signal<Set<string>>(new Set());
  private readonly localError = signal<string | null>(null);

  protected readonly form = new FormGroup({
    productId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    title: text(120), listingKey: text(63), category: text(60), summary: text(600, false), requirements: text(2000, false),
  });
  private readonly productId = toSignal(this.form.controls.productId.valueChanges, { initialValue: '' });

  protected readonly productOptions = computed<WvOption[]>(() =>
    this.catalog.publishedProducts().map((p) => ({ value: p.id, label: p.name })));
  protected readonly skusOfProduct = computed(() => this.catalog.products().find((p) => p.id === this.productId())?.skus ?? []);
  protected readonly errorMessage = computed(() => {
    const p = this.store.saveError();
    return this.localError() ?? (p ? marketplaceProblemMessage(p, 'criar a oferta') : null);
  });

  ngOnInit(): void {
    void this.store.load();
    void this.catalog.load();
  }

  protected open(): void {
    this.store.clearSaveError();
    this.localError.set(null);
    this.form.reset();
    this.chosen.set(new Set());
    this.creating.set(true);
  }

  protected toggle(skuId: string): void {
    const next = new Set(this.chosen());
    if (next.has(skuId)) {
      next.delete(skuId);
    } else {
      next.add(skuId);
    }
    this.chosen.set(next);
  }

  protected async submit(): Promise<void> {
    if (this.form.invalid) {
      this.localError.set('Escolha o produto e informe título, chave e categoria.');
      return;
    }
    this.localError.set(null);
    const v = this.form.getRawValue();
    const created = await this.store.create({
      listingKey: v.listingKey.trim(), productId: v.productId, category: v.category, title: v.title, summary: v.summary || null,
      skuIds: [...this.chosen()], requirements: v.requirements || null,
    });
    if (created) {
      this.creating.set(false);
      void this.router.navigate(['/marketplace', created.id]);
    }
  }

  protected productName(productId: string): string {
    return this.catalog.products().find((p) => p.id === productId)?.name ?? '—';
  }

  protected availabilityText(l: Listing): string {
    const a = l.availability;
    if (!a) {
      return 'Ninguém ainda';
    }
    if (a.mode === 'ALL_RESELLERS') {
      return a.exclude.length ? `${MODE.ALL_RESELLERS}, exceto ${a.exclude.length}` : MODE.ALL_RESELLERS;
    }
    return a.mode === 'TIERS' ? `Tiers ${a.include.join(', ')}` : `${a.include.length} revenda(s)`;
  }
}
