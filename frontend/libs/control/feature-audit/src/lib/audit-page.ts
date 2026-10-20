import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { AuditEvent, AuditStore } from '@waver/control-data-access-api';
import { WvButton, WvPageHeader, WvStatusBadge } from '@waver/shared-ui-kit';

/** Human labels for audit action keys (stable keys from the services, AR-009). */
const ACTION_LABEL: Record<string, string> = {
  'tenant.organization.create': 'Organização criada',
  'identity.keycloak-organization.link': 'Organização criada no Keycloak',
  'tenant.organization.activate': 'Organização ativada',
  'identity.user.invite': 'Usuário convidado',
  'reseller.lead.register': 'Lead de revenda registrado',
  'reseller.review.start': 'Análise de revenda iniciada',
  'reseller.onboarding.item.complete': 'Etapa de onboarding concluída',
  'reseller.approve': 'Revenda aprovada',
  'reseller.reject': 'Revenda reprovada',
  'reseller.organization.link': 'Organização vinculada à revenda',
  'reseller.owner.invite': 'Dono da revenda convidado',
  'reseller.activate': 'Revenda ativada',
  'customer.register': 'Cliente cadastrado por revenda',
};

@Component({
  selector: 'wv-audit-page',
  imports: [DatePipe, WvButton, WvPageHeader, WvStatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <wv-page-header title="Auditoria"
      description="Registro imutável das ações sensíveis da plataforma. Cada entrada é encadeada à anterior, então uma alteração quebra a cadeia.">
      <wv-button variant="secondary" (pressed)="store.load()">Atualizar</wv-button>
    </wv-page-header>

    @if (store.error()) {
      <div class="state" role="alert">Não foi possível carregar a auditoria.
        <wv-button variant="secondary" (pressed)="store.load()">Tentar de novo</wv-button></div>
    } @else if (!store.loading() && store.items().length === 0) {
      <p class="state">Nenhuma ação registrada ainda. As entradas aparecem alguns segundos depois de cada ação.</p>
    } @else {
      <table class="wv-table">
        <thead>
          <tr><th scope="col">Quando</th><th scope="col">Ação</th><th scope="col">Por</th><th scope="col">Serviço</th><th scope="col">Integridade</th></tr>
        </thead>
        <tbody>
          @for (e of store.items(); track e.auditId) {
            <tr>
              <td class="when">{{ e.occurredAt | date: 'dd/MM/yyyy HH:mm:ss' }}</td>
              <td>{{ label(e) }}</td>
              <td>{{ actor(e) }}</td>
              <td class="muted">{{ e.service }}</td>
              <td>
                @if (e.chainValid) { <wv-status-badge tone="success">Íntegra</wv-status-badge> }
                @else { <wv-status-badge tone="danger">Alterada</wv-status-badge> }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--wv-space-6); }
    .state { color: var(--wv-color-text-secondary); display: flex; align-items: center; gap: var(--wv-space-4); margin: 0; }
    .when { white-space: nowrap; }
    .muted { color: var(--wv-color-text-secondary); }
  `,
})
export class AuditPage implements OnInit {
  protected readonly store = inject(AuditStore);

  ngOnInit(): void {
    void this.store.load();
  }

  protected label(e: AuditEvent): string {
    const base = ACTION_LABEL[e.action] ?? e.action;
    const type = e.details['orgType'];
    return type === 'RESELLER' ? `${base}: revenda` : type === 'CUSTOMER' ? `${base}: cliente` : base;
  }

  protected actor(e: AuditEvent): string {
    return e.actorType === 'SERVICE' ? 'Sistema' : e.actorType === 'SUPPORT' ? 'Suporte (acesso auditado)' : 'Usuário';
  }
}
