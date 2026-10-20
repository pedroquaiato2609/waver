import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OrganizationsStore, OrganizationStatus, OrgType } from '@waver/control-data-access-api';
import { WvButton, WvPageHeader, WvStatusBadge, WvTone } from '@waver/shared-ui-kit';

const TYPE_LABEL: Record<OrgType, string> = { PLATFORM: 'Grupo WAVER', RESELLER: 'Revenda', CUSTOMER: 'Cliente' };
const STATUS: Record<OrganizationStatus, { label: string; tone: WvTone }> = {
  PENDING: { label: 'Aguardando ativação', tone: 'warning' },
  ACTIVE: { label: 'Ativa', tone: 'success' },
  SUSPENDED: { label: 'Suspensa', tone: 'danger' },
  TERMINATED: { label: 'Encerrada', tone: 'neutral' },
};

/**
 * The tenancy structure (tenant-service): who exists in the network and whether it can operate. Read-only:
 * resellers enter through the onboarding, customers through the resellers' WAVER Partner.
 */
@Component({
  selector: 'wv-organizations-page',
  imports: [DatePipe, RouterLink, WvButton, WvPageHeader, WvStatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <wv-page-header title="Organizações"
      description="A estrutura de acesso da rede: cada revenda e cada cliente é uma organização. Revendas novas entram pelo onboarding; clientes, pelo WAVER Partner." >
      <a class="link" routerLink="/revendas">Ir para o onboarding de revendas</a>
    </wv-page-header>

    <section class="ledger" aria-label="Resumo da rede">
      <div><strong>{{ store.counts().resellers }}</strong><span>revendas</span></div>
      <div><strong>{{ store.counts().customers }}</strong><span>clientes</span></div>
      <div><strong>{{ store.counts().pending }}</strong><span>aguardando ativação</span></div>
    </section>

    <div class="filters" role="group" aria-label="Filtrar por tipo">
      @for (f of filters; track f.value) {
        <button type="button" [attr.aria-pressed]="store.filter() === f.value" (click)="store.setFilter(f.value)">{{ f.label }}</button>
      }
    </div>

    @if (store.loading() && store.items().length === 0) {
      <p class="state">Carregando organizações...</p>
    } @else if (store.loadError()) {
      <div class="state error" role="alert">
        Não foi possível carregar as organizações.
        <wv-button variant="secondary" (pressed)="store.load()">Tentar de novo</wv-button>
      </div>
    } @else if (store.visible().length === 0) {
      <p class="state">Nenhuma organização deste tipo ainda.</p>
    } @else {
      <table class="wv-table">
        <thead>
          <tr><th scope="col">Razão social</th><th scope="col">Tipo</th><th scope="col">Acesso</th><th scope="col">Criada em</th></tr>
        </thead>
        <tbody>
          @for (o of store.visible(); track o.id) {
            <tr>
              <td class="name">{{ o.legalName }}</td>
              <td>{{ typeLabel[o.orgType] }}</td>
              <td><wv-status-badge [tone]="status[o.status].tone">{{ status[o.status].label }}</wv-status-badge></td>
              <td>{{ o.createdAt | date: 'dd/MM/yyyy HH:mm' }}</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--wv-space-6); }
    .link { color: var(--wv-color-text-link); font-size: var(--wv-font-size-sm); text-underline-offset: 3px; }
    .ledger { display: flex; gap: var(--wv-space-10); flex-wrap: wrap; }
    .ledger div { display: flex; align-items: baseline; gap: var(--wv-space-2); }
    .ledger strong { font-size: var(--wv-font-size-2xl); font-weight: var(--wv-font-weight-semibold); font-variant-numeric: tabular-nums; }
    .ledger span { color: var(--wv-color-text-secondary); }
    .filters { display: flex; gap: 2px; border-bottom: 1px solid var(--wv-color-border-subtle); }
    .filters button {
      font: inherit; font-size: var(--wv-font-size-sm); color: var(--wv-color-text-secondary); background: none; border: 0;
      padding: var(--wv-space-2) var(--wv-space-3); border-bottom: 2px solid transparent; cursor: pointer; margin-bottom: -1px;
    }
    .filters button[aria-pressed='true'] { color: var(--wv-color-text-primary); border-color: var(--wv-color-accent-highlight); font-weight: 600; }
    .state { color: var(--wv-color-text-secondary); display: flex; align-items: center; gap: var(--wv-space-4); margin: 0; }
    .name { font-weight: var(--wv-font-weight-medium); }
  `,
})
export class OrganizationsPage implements OnInit {
  protected readonly store = inject(OrganizationsStore);
  protected readonly typeLabel = TYPE_LABEL;
  protected readonly status = STATUS;
  protected readonly filters: { value: OrgType | 'ALL'; label: string }[] = [
    { value: 'ALL', label: 'Todas' },
    { value: 'RESELLER', label: 'Revendas' },
    { value: 'CUSTOMER', label: 'Clientes' },
  ];

  ngOnInit(): void {
    void this.store.load();
  }
}
