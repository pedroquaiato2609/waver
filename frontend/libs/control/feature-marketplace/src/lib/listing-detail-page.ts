import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AvailabilityMode, CatalogStore, Listing, ListingVersion, MarketplaceStore, ResellersStore } from '@waver/control-data-access-api';
import { WvButton, WvSelect, WvStatusBadge, WvTextField } from '@waver/shared-ui-kit';
import { LISTING_STATUS, marketplaceProblemMessage, MODE } from './labels';

const VERSION_STATUS: Record<ListingVersion['status'], string> = {
  DRAFT: 'Rascunho', REVIEW: 'Em revisão', PUBLISHED: 'Publicada', SUPERSEDED: 'Substituída',
};

/**
 * One marketplace offer (RFC-0017 §5): submit for review, approve or return, then choose who may sell it. The
 * version a reseller sees never changes; a new version goes through review while the current one stays published.
 */
@Component({
  selector: 'wv-listing-detail-page',
  imports: [DatePipe, ReactiveFormsModule, RouterLink, WvButton, WvSelect, WvStatusBadge, WvTextField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="back" routerLink="/marketplace">Marketplace</a>
    @if (listing(); as l) {
      <header class="head">
        <div>
          <h1>{{ l.title }}</h1>
          <p class="sub"><span>{{ l.category }}</span><span>{{ productName() }}</span><code>{{ l.listingKey }}</code></p>
          @if (l.summary) { <p class="summary">{{ l.summary }}</p> }
        </div>
        <wv-status-badge [tone]="status[l.status].tone">{{ status[l.status].label }}</wv-status-badge>
      </header>

      <section class="next" [class.done]="l.status === 'PUBLISHED' && !l.openVersion && !!l.availability" aria-labelledby="next-title">
        <h2 id="next-title">Próximo passo</h2>
        @if (l.openVersion?.status === 'DRAFT') {
          <p>Envie a versão {{ l.openVersion?.versionNo }} para revisão. O produto precisa estar publicado e cada SKU com preço em tabela ativa.</p>
          @if (l.openVersion?.returnReason) { <p class="warn">Devolvida: {{ l.openVersion?.returnReason }}</p> }
          <div class="actions"><wv-button [disabled]="store.saving()" (pressed)="submit(l)">Enviar para revisão</wv-button></div>
        } @else if (l.openVersion?.status === 'REVIEW') {
          <p>Versão {{ l.openVersion?.versionNo }} em revisão. Ao aprovar, ela passa a ser a oferta que as revendas veem.</p>
          @if (returning()) {
            <div class="reason">
              <wv-text-field label="O que precisa mudar" [formControl]="reason" [maxLength]="500" />
              <div class="actions">
                <wv-button [disabled]="store.saving()" (pressed)="decide(l, 'RETURN')">Devolver para rascunho</wv-button>
                <wv-button variant="quiet" (pressed)="returning.set(false)">Voltar</wv-button>
              </div>
            </div>
          } @else {
            <div class="actions">
              <wv-button [disabled]="store.saving()" (pressed)="decide(l, 'APPROVE')">Aprovar e publicar</wv-button>
              <wv-button variant="secondary" [disabled]="store.saving()" (pressed)="returning.set(true)">Devolver</wv-button>
            </div>
          }
          <p class="fine">Enquanto o fluxo de aprovações não existe, a publicação (LISTING_PUBLICATION) é aprovada diretamente pela
            equipe de Product Ops e fica na auditoria.</p>
        } @else if (!l.availability) {
          <p>Publicada. Escolha abaixo quais revendas podem vender esta oferta; até lá, nenhuma revenda a vê.</p>
        } @else {
          <p>Publicada e disponível para {{ availabilityText(l) }}. As revendas a veem em "Ofertas disponíveis" no WAVER Partner.</p>
        }
        @if (errorMessage()) { <p class="error" role="alert">{{ errorMessage() }}</p> }
      </section>

      <div class="columns">
        <section aria-labelledby="versions-title">
          <h2 id="versions-title">Versões</h2>
          <ol class="versions">
            @for (v of versionsDesc(); track v.id) {
              <li [class.current]="v.id === l.currentVersion?.id">
                <div class="line"><strong>v{{ v.versionNo }}</strong><span>{{ versionStatus[v.status] }}</span>
                  @if (v.publishedAt) { <span class="muted">publicada em {{ v.publishedAt | date: 'dd/MM/yyyy HH:mm' }}</span> }</div>
                <div class="skus">@for (s of v.skuIds; track s) { <code>{{ skuCode(s) }}</code> } @empty { <span class="muted">sem SKU</span> }</div>
                @if (v.requirements) { <p class="muted">Requisitos: {{ v.requirements }}</p> }
              </li>
            }
          </ol>
          @if (!l.openVersion && l.status === 'PUBLISHED') {
            <wv-button variant="secondary" [disabled]="store.saving()" (pressed)="newVersion(l)">Preparar nova versão</wv-button>
          }
        </section>

        <section aria-labelledby="availability-title">
          <h2 id="availability-title">Disponível para</h2>
          <div class="panel">
            <wv-select label="Quem pode vender" [formControl]="mode" [options]="modes" />
            @if (mode.value === 'TIERS') {
              <wv-text-field label="Tiers" [formControl]="tiers" [maxLength]="200" hint="Separados por vírgula, ex.: GOLD, PLATINUM" />
            }
            @if (mode.value === 'RESELLERS' || mode.value === 'ALL_RESELLERS') {
              <fieldset>
                <legend>{{ mode.value === 'RESELLERS' ? 'Revendas incluídas' : 'Revendas excluídas (opcional)' }}</legend>
                @for (r of resellerChoices(); track r.orgId) {
                  <label class="check"><input type="checkbox" [checked]="picked().has(r.orgId)" (change)="pick(r.orgId)" /> {{ r.name }}</label>
                } @empty { <span class="muted">Nenhuma revenda com organização criada.</span> }
              </fieldset>
            }
            <div class="actions"><wv-button variant="secondary" [disabled]="store.saving()" (pressed)="saveAvailability(l)">Salvar disponibilidade</wv-button></div>
            <p class="fine">Tiers ainda não existem no cadastro de revendas; uma regra por tier só passa a valer quando existirem.</p>
          </div>
        </section>
      </div>
    } @else if (store.loadError()) {
      <p class="state" role="alert">Oferta não encontrada.</p>
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
    .summary { margin: var(--wv-space-2) 0 0; max-width: 680px; }
    .next {
      padding: var(--wv-space-5) var(--wv-space-6); border-radius: var(--wv-radius-lg); max-width: 760px; display: grid; gap: var(--wv-space-3);
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-left: 3px solid var(--wv-color-accent-highlight);
    }
    .next.done { border-left-color: var(--wv-color-status-success); }
    .next h2, .next p { margin: 0; }
    .warn { color: var(--wv-color-status-warning); }
    .fine { margin: 0; font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); }
    .actions { display: flex; gap: var(--wv-space-2); flex-wrap: wrap; }
    .reason { display: grid; gap: var(--wv-space-3); max-width: 520px; }
    .error { margin: 0; color: var(--wv-color-status-danger); font-size: var(--wv-font-size-sm); }
    .columns { display: grid; grid-template-columns: minmax(0, 1fr) minmax(280px, 380px); gap: var(--wv-space-10); align-items: start; }
    .versions { list-style: none; margin: 0 0 var(--wv-space-4); padding: 0; display: grid; gap: var(--wv-space-3); }
    .versions li { padding: var(--wv-space-3) var(--wv-space-4); border: 1px solid var(--wv-color-border-subtle); border-radius: var(--wv-radius-md); display: grid; gap: var(--wv-space-2); }
    .versions li.current { border-left: 3px solid var(--wv-color-status-success); }
    .versions p { margin: 0; font-size: var(--wv-font-size-sm); }
    .line { display: flex; gap: var(--wv-space-3); align-items: baseline; flex-wrap: wrap; }
    .skus { display: flex; gap: var(--wv-space-2); flex-wrap: wrap; }
    code { font-size: var(--wv-font-size-sm); }
    .muted { color: var(--wv-color-text-secondary); font-size: var(--wv-font-size-sm); }
    .panel {
      display: grid; gap: var(--wv-space-3); padding: var(--wv-space-4);
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-radius: var(--wv-radius-lg);
    }
    fieldset { border: 0; margin: 0; padding: 0; display: grid; gap: var(--wv-space-2); }
    legend { font-weight: var(--wv-font-weight-semibold); font-size: var(--wv-font-size-sm); margin-bottom: var(--wv-space-2); padding: 0; }
    .check { display: inline-flex; gap: var(--wv-space-2); align-items: center; }
    .state { color: var(--wv-color-text-secondary); margin: 0; }
    @media (max-width: 960px) { .columns { grid-template-columns: 1fr; } }
  `,
})
export class ListingDetailPage {
  /** Route parameter (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly store = inject(MarketplaceStore);
  private readonly catalog = inject(CatalogStore);
  private readonly resellers = inject(ResellersStore);
  protected readonly status = LISTING_STATUS;
  protected readonly versionStatus = VERSION_STATUS;
  protected readonly modes = (Object.keys(MODE) as AvailabilityMode[]).map((value) => ({ value, label: MODE[value] }));
  protected readonly returning = signal(false);
  protected readonly reason = new FormControl('', { nonNullable: true });
  protected readonly mode = new FormControl<string>('ALL_RESELLERS', { nonNullable: true });
  protected readonly tiers = new FormControl('', { nonNullable: true });
  protected readonly picked = signal<Set<string>>(new Set());

  protected readonly listing = computed(() => this.store.items().find((l) => l.id === this.id()) ?? null);
  protected readonly versionsDesc = computed(() => [...(this.listing()?.versions ?? [])].sort((a, b) => b.versionNo - a.versionNo));
  protected readonly productName = computed(() => this.catalog.products().find((p) => p.id === this.listing()?.productId)?.name ?? '');
  protected readonly resellerChoices = computed(() => this.resellers.items()
    .filter((r) => r.resellerOrgId)
    .map((r) => ({ orgId: r.resellerOrgId as string, name: r.tradeName ?? r.legalName })));
  protected readonly errorMessage = computed(() => {
    const p = this.store.saveError();
    return p ? marketplaceProblemMessage(p, 'concluir esta ação') : null;
  });

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(async () => {
        this.store.clearSaveError();
        if (this.catalog.products().length === 0) {
          void this.catalog.load();
        }
        if (this.resellers.items().length === 0) {
          void this.resellers.load();
        }
        const l = await this.store.refresh(id);
        if (l?.availability) {
          this.mode.setValue(l.availability.mode);
          this.tiers.setValue(l.availability.mode === 'TIERS' ? l.availability.include.join(', ') : '');
          this.picked.set(new Set(l.availability.mode === 'RESELLERS' ? l.availability.include : l.availability.exclude));
        }
      });
    });
    this.mode.valueChanges.subscribe(() => this.picked.set(new Set()));
  }

  protected skuCode(skuId: string): string {
    return this.catalog.skuIndex().get(skuId)?.sku.skuCode ?? skuId.slice(0, 8);
  }

  protected availabilityText(l: Listing): string {
    const a = l.availability;
    if (!a) {
      return 'ninguém';
    }
    if (a.mode === 'ALL_RESELLERS') {
      return a.exclude.length ? `todas as revendas, exceto ${a.exclude.length}` : 'todas as revendas';
    }
    return a.mode === 'TIERS' ? `os tiers ${a.include.join(', ')}` : `${a.include.length} revenda(s) escolhida(s)`;
  }

  protected pick(orgId: string): void {
    const next = new Set(this.picked());
    if (next.has(orgId)) {
      next.delete(orgId);
    } else {
      next.add(orgId);
    }
    this.picked.set(next);
  }

  protected async submit(l: Listing): Promise<void> {
    await this.after(this.store.submit(l.id), l.id);
  }

  protected async decide(l: Listing, decision: 'APPROVE' | 'RETURN'): Promise<void> {
    if (await this.after(this.store.decide(l.id, decision, decision === 'RETURN' ? this.reason.value : null), l.id)) {
      this.returning.set(false);
      this.reason.reset();
    }
  }

  protected async newVersion(l: Listing): Promise<void> {
    const current = l.currentVersion;
    await this.after(this.store.revise(l.id, current?.skuIds ?? [], current?.requirements ?? null), l.id);
  }

  protected async saveAvailability(l: Listing): Promise<void> {
    const mode = this.mode.value as AvailabilityMode;
    const include = mode === 'TIERS' ? this.tiers.value.split(',').map((t) => t.trim().toUpperCase()).filter(Boolean)
      : mode === 'RESELLERS' ? [...this.picked()] : [];
    const exclude = mode === 'ALL_RESELLERS' ? [...this.picked()] : [];
    await this.after(this.store.setAvailability(l.id, mode, include, exclude), l.id);
  }

  /** A stale page reloads instead of showing an outdated listing. */
  private async after(command: Promise<Listing | null>, id: string): Promise<boolean> {
    const result = await command;
    if (!result) {
      await this.store.refresh(id);
    }
    return result !== null;
  }
}
