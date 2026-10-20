import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Customer, CustomerDisplayStatus, CustomersStore, FinancialStatus, OwnResellerStore } from '@waver/partner-data-access-api';
import { commonProblemMessage, Problem } from '@waver/shared-data-access-app-state';
import { WvButton, WvPageHeader, WvStatusBadge, WvTextField, WvTone } from '@waver/shared-ui-kit';

/** Commercial status (derived, RFC-0017 §3) and financial status are separate badges (BRAND_GUIDELINES §4.3). */
const COMMERCIAL: Record<CustomerDisplayStatus, { label: string; tone: WvTone }> = {
  PROSPECT: { label: 'Prospect', tone: 'neutral' },
  CONTRACTING: { label: 'Em contratação', tone: 'info' },
  PROVISIONING: { label: 'Em implantação', tone: 'info' },
  ACTIVE: { label: 'Ativo', tone: 'success' },
  PAST_DUE: { label: 'Ativo', tone: 'success' },
  RESTRICTED: { label: 'Restrito', tone: 'warning' },
  SUSPENDED: { label: 'Suspenso', tone: 'danger' },
  TERMINATED: { label: 'Encerrado', tone: 'neutral' },
};
const FINANCIAL: Record<FinancialStatus, { label: string; tone: WvTone }> = {
  CURRENT: { label: 'Em dia', tone: 'success' },
  PAST_DUE: { label: 'Em atraso', tone: 'danger' },
  GRACE_PERIOD: { label: 'Em carência', tone: 'warning' },
  IN_COLLECTION: { label: 'Em cobrança', tone: 'danger' },
};

function registerMessage(p: Problem): string {
  switch (p.code) {
    case 'TENANT_PARENT_NOT_ACTIVE':
      return 'Sua revenda ainda não foi ativada pela WAVER. Você poderá cadastrar clientes assim que a ativação terminar.';
    case 'CUSTOMER_TAX_ID_EXISTS':
      return 'Este cliente já está na sua carteira.';
    case 'TAX_ID_INVALID':
      return 'CNPJ inválido. Confira os 14 caracteres, com ou sem pontuação.';
    case 'CUSTOMER_LEGAL_NAME_INVALID':
      return 'Informe a razão social com 2 a 200 caracteres.';
    case 'CUSTOMER_CONTACT_NAME_INVALID':
      return 'Informe o nome completo do administrador.';
    case 'CUSTOMER_CONTACT_EMAIL_INVALID':
      return 'Confira o e-mail do administrador.';
    case 'CUSTOMER_CONTACT_PHONE_INVALID':
      return 'Telefone inválido. Use DDD e número, por exemplo +55 11 4000-1234.';
    case 'CUSTOMER_REGISTER_FORBIDDEN':
    case 'TENANT_CREATE_FORBIDDEN':
      return 'Seu papel não permite cadastrar clientes. Peça ao dono da revenda.';
    case 'VALIDATION_FAILED':
      return 'Preencha os campos obrigatórios.';
    default:
      return commonProblemMessage(p, 'cadastrar o cliente');
  }
}

/** The reseller's portfolio (RFC-0017 §3): register customers and follow their commercial and financial state. */
@Component({
  selector: 'wv-customers-page',
  imports: [DatePipe, ReactiveFormsModule, WvButton, WvPageHeader, WvStatusBadge, WvTextField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <wv-page-header title="Clientes"
      description="Sua carteira na WAVER. Cada cliente cadastrado ganha uma organização própria; o acesso ao Workspace é liberado depois do contrato.">
      @if (!creating()) {
        <wv-button [disabled]="!reseller.canSell()" (pressed)="openCreate()">Cadastrar cliente</wv-button>
      }
    </wv-page-header>

    @if (reseller.profile() && !reseller.canSell()) {
      <p class="notice" role="status">Sua revenda está em ativação pela WAVER. O cadastro de clientes é liberado quando a ativação terminar.</p>
    }

    @if (creating()) {
      <form class="register" [formGroup]="form" (ngSubmit)="submit()" aria-labelledby="register-title">
        <h2 id="register-title">Novo cliente</h2>
        <fieldset>
          <legend>Empresa</legend>
          <wv-text-field class="wide" label="Razão social" formControlName="legalName" [maxLength]="200" autocomplete="organization" />
          <wv-text-field label="Nome fantasia (opcional)" formControlName="tradeName" [maxLength]="200" />
          <wv-text-field label="CNPJ" formControlName="taxId" [maxLength]="18" />
          <wv-text-field label="Segmento (opcional)" formControlName="segment" [maxLength]="80" hint="Por exemplo: indústria, varejo, saúde." />
        </fieldset>
        <fieldset>
          <legend>Administrador do cliente</legend>
          <p class="hint">Quem vai administrar o WAVER Workspace do cliente. O convite sai quando o contrato for ativado.</p>
          <wv-text-field label="Nome completo" formControlName="adminName" [maxLength]="120" />
          <wv-text-field label="E-mail" formControlName="adminEmail" [maxLength]="254" />
          <wv-text-field label="Telefone (opcional)" formControlName="adminPhone" [maxLength]="20" />
        </fieldset>
        @if (errorMessage()) { <p class="error" role="alert">{{ errorMessage() }}</p> }
        <div class="form-actions">
          <wv-button type="submit" [disabled]="store.saving()">{{ store.saving() ? 'Cadastrando...' : 'Cadastrar cliente' }}</wv-button>
          <wv-button variant="quiet" (pressed)="creating.set(false)">Cancelar</wv-button>
        </div>
      </form>
    }

    <section class="ledger" aria-label="Resumo da carteira">
      <div><strong>{{ store.counts().total }}</strong><span>clientes</span></div>
      <div><strong>{{ store.counts().prospects }}</strong><span>em negociação</span></div>
      <div><strong>{{ store.counts().active }}</strong><span>ativos</span></div>
      <div [class.alert]="store.counts().attention > 0"><strong>{{ store.counts().attention }}</strong><span>pedem atenção</span></div>
    </section>

    @if (store.loading() && store.items().length === 0) {
      <p class="state">Carregando carteira...</p>
    } @else if (store.loadError()) {
      <div class="state" role="alert">Não foi possível carregar seus clientes.
        <wv-button variant="secondary" (pressed)="store.load()">Tentar de novo</wv-button></div>
    } @else if (store.items().length === 0) {
      <p class="state">Nenhum cliente na carteira ainda.
        @if (reseller.canSell()) { Cadastre o primeiro para começar a vender. }</p>
    } @else {
      <table class="wv-table">
        <thead>
          <tr><th scope="col">Cliente</th><th scope="col">CNPJ</th><th scope="col">Comercial</th><th scope="col">Financeiro</th>
              <th scope="col">Administrador</th><th scope="col">Cadastrado em</th></tr>
        </thead>
        <tbody>
          @for (c of store.items(); track c.id) {
            <tr [class.fresh]="c.id === store.lastCreatedId()">
              <td><span class="name">{{ c.tradeName || c.legalName }}</span>
                @if (c.tradeName) { <span class="legal">{{ c.legalName }}</span> }</td>
              <td class="num">{{ c.taxId }}</td>
              <td><wv-status-badge [tone]="commercial[c.displayStatus].tone">{{ commercial[c.displayStatus].label }}</wv-status-badge></td>
              <td><wv-status-badge [tone]="financial[c.financialStatus].tone">{{ financial[c.financialStatus].label }}</wv-status-badge></td>
              <td>{{ admin(c) }}</td>
              <td class="num">{{ c.createdAt | date: 'dd/MM/yyyy' }}</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--wv-space-6); }
    .notice {
      margin: 0; padding: var(--wv-space-3) var(--wv-space-4); border-radius: var(--wv-radius-md); max-width: 760px;
      background: var(--wv-color-status-warning-bg); color: var(--wv-color-status-warning); font-size: var(--wv-font-size-sm);
    }
    .register {
      display: grid; gap: var(--wv-space-5); max-width: 760px; padding: var(--wv-space-6);
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-radius: var(--wv-radius-lg);
    }
    .register h2 { font-size: var(--wv-font-size-lg); }
    fieldset { border: 0; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--wv-space-3); }
    legend { font-weight: var(--wv-font-weight-semibold); font-size: var(--wv-font-size-sm); margin-bottom: var(--wv-space-2); padding: 0; }
    .wide { grid-column: 1 / -1; }
    .hint { grid-column: 1 / -1; margin: 0; color: var(--wv-color-text-secondary); font-size: var(--wv-font-size-sm); }
    .error { margin: 0; color: var(--wv-color-status-danger); font-size: var(--wv-font-size-sm); }
    .form-actions { display: flex; gap: var(--wv-space-2); }
    .ledger { display: flex; gap: var(--wv-space-10); flex-wrap: wrap; }
    .ledger div { display: flex; align-items: baseline; gap: var(--wv-space-2); }
    .ledger strong { font-size: var(--wv-font-size-2xl); font-weight: var(--wv-font-weight-semibold); font-variant-numeric: tabular-nums; }
    .ledger span { color: var(--wv-color-text-secondary); }
    .ledger .alert strong { color: var(--wv-color-status-danger); }
    .state { color: var(--wv-color-text-secondary); display: flex; align-items: center; gap: var(--wv-space-4); margin: 0; }
    .name { display: block; font-weight: var(--wv-font-weight-medium); }
    .legal { display: block; font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); }
    .num { font-variant-numeric: tabular-nums; white-space: nowrap; }
    tr.fresh td:first-child { box-shadow: inset 3px 0 0 var(--wv-color-action-primary); }
  `,
})
export class CustomersPage implements OnInit {
  protected readonly store = inject(CustomersStore);
  protected readonly reseller = inject(OwnResellerStore);
  protected readonly commercial = COMMERCIAL;
  protected readonly financial = FINANCIAL;
  protected readonly creating = signal(false);
  protected readonly form = new FormGroup({
    legalName: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    tradeName: new FormControl('', { nonNullable: true }),
    taxId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    segment: new FormControl('', { nonNullable: true }),
    adminName: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    adminEmail: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    adminPhone: new FormControl('', { nonNullable: true }),
  });
  private readonly localError = signal<string | null>(null);
  protected readonly errorMessage = computed(() => {
    const p = this.store.saveError();
    return this.localError() ?? (p ? registerMessage(p) : null);
  });

  ngOnInit(): void {
    void this.store.load();
    void this.reseller.load();
  }

  protected openCreate(): void {
    this.store.startRegistration();
    this.localError.set(null);
    this.form.reset();
    this.creating.set(true);
  }

  protected async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.localError.set('Preencha a razão social, o CNPJ e o administrador do cliente.');
      return;
    }
    this.localError.set(null);
    const v = this.form.getRawValue();
    const created = await this.store.register({
      legalName: v.legalName, tradeName: v.tradeName, taxId: v.taxId, segment: v.segment,
      adminContact: { name: v.adminName, email: v.adminEmail, phone: v.adminPhone },
    });
    if (created) {
      this.creating.set(false);
    }
  }

  protected admin(c: Customer): string {
    return c.contacts.find((x) => x.type === 'ADMIN')?.name ?? '';
  }
}
