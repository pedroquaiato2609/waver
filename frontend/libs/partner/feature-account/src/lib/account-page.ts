import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { OwnReseller, OwnResellerStore } from '@waver/partner-data-access-api';
import { WvButton, WvPageHeader, WvStatusBadge, WvTone } from '@waver/shared-ui-kit';

const STATUS: Record<OwnReseller['onboardingStatus'], { label: string; tone: WvTone; text: string }> = {
  LEAD: { label: 'Em cadastro', tone: 'neutral', text: 'A WAVER está cadastrando sua revenda.' },
  UNDER_REVIEW: { label: 'Em análise', tone: 'info', text: 'A WAVER está analisando sua revenda.' },
  APPROVED: { label: 'Em ativação', tone: 'warning', text: 'Sua revenda foi aprovada. A WAVER está concluindo contrato e treinamento para ativá-la.' },
  ACTIVE: { label: 'Ativa', tone: 'success', text: 'Sua revenda está ativa e pode cadastrar e atender clientes.' },
  REJECTED: { label: 'Não aprovada', tone: 'danger', text: 'Fale com o seu contato comercial na WAVER.' },
  SUSPENDED: { label: 'Suspensa', tone: 'danger', text: 'Novas vendas estão bloqueadas. Sua carteira e o suporte continuam disponíveis.' },
  TERMINATED: { label: 'Encerrada', tone: 'neutral', text: 'O contrato de revenda foi encerrado.' },
};

const CONTACT: Partial<Record<string, string>> = {
  OWNER: 'Dono da revenda',
  COMMERCIAL: 'Contato comercial',
  FINANCIAL: 'Contato financeiro',
  TECHNICAL: 'Contato técnico',
  LEGAL: 'Contato jurídico',
};

/** The reseller's own record at WAVER (RFC-0017 §1: own profile, contacts and tier, read-only in v1). */
@Component({
  selector: 'wv-account-page',
  imports: [WvButton, WvPageHeader, WvStatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <wv-page-header title="Minha revenda" description="Os dados da sua empresa como a WAVER os registrou. Para alterar algo, fale com o seu contato comercial." />

    @if (store.profile(); as r) {
      <section class="identity">
        <div>
          <h2>{{ r.tradeName || r.legalName }}</h2>
          @if (r.tradeName) { <p class="muted">{{ r.legalName }}</p> }
          <p class="num muted">CNPJ {{ r.taxId }}</p>
        </div>
        <div class="status">
          <wv-status-badge [tone]="status[r.onboardingStatus].tone">{{ status[r.onboardingStatus].label }}</wv-status-badge>
          <p>{{ status[r.onboardingStatus].text }}</p>
        </div>
      </section>

      <section aria-labelledby="contacts-title">
        <h2 id="contacts-title">Contatos</h2>
        <table class="wv-table">
          <thead><tr><th scope="col">Papel</th><th scope="col">Nome</th><th scope="col">E-mail</th><th scope="col">Telefone</th></tr></thead>
          <tbody>
            @for (c of r.contacts; track c.type) {
              <tr><td>{{ contact[c.type] ?? c.type }}</td><td>{{ c.name }}</td><td>{{ c.email }}</td><td class="num">{{ c.phone ?? '' }}</td></tr>
            }
          </tbody>
        </table>
      </section>
    } @else if (store.error()) {
      <div class="state" role="alert">Não foi possível carregar os dados da revenda.
        <wv-button variant="secondary" (pressed)="store.load()">Tentar de novo</wv-button></div>
    } @else {
      <p class="state">Carregando...</p>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--wv-space-8); }
    h2 { font-size: var(--wv-font-size-lg); margin-bottom: var(--wv-space-2); }
    .identity {
      display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: var(--wv-space-8); max-width: 960px;
      padding: var(--wv-space-6); background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle);
      border-radius: var(--wv-radius-lg);
    }
    .identity p { margin: 0; }
    .status { display: grid; gap: var(--wv-space-2); justify-items: start; align-content: start; }
    .status p { color: var(--wv-color-text-secondary); font-size: var(--wv-font-size-sm); }
    .muted { color: var(--wv-color-text-secondary); }
    .num { font-variant-numeric: tabular-nums; }
    .state { color: var(--wv-color-text-secondary); display: flex; align-items: center; gap: var(--wv-space-4); margin: 0; }
    @media (max-width: 760px) { .identity { grid-template-columns: 1fr; } }
  `,
})
export class AccountPage implements OnInit {
  protected readonly store = inject(OwnResellerStore);
  protected readonly status = STATUS;
  protected readonly contact = CONTACT;

  ngOnInit(): void {
    void this.store.load();
  }
}
