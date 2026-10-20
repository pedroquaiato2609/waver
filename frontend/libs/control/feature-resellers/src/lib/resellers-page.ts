import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { activationProgress, Reseller, ResellersStore } from '@waver/control-data-access-api';
import { WvButton, WvPageHeader, WvStatusBadge, WvTextField } from '@waver/shared-ui-kit';
import { resellerProblemMessage, STAGE } from './labels';

const required = (max: number) => new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(max)] });

/** Reseller onboarding pipeline (RFC-0017 §2): from lead to active, one row per company. */
@Component({
  selector: 'wv-resellers-page',
  imports: [DatePipe, ReactiveFormsModule, RouterLink, WvButton, WvPageHeader, WvStatusBadge, WvTextField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <wv-page-header title="Revendas"
      description="Do primeiro contato à primeira venda. Cada revenda passa por análise, aprovação e ativação antes de vender pela WAVER.">
      @if (!creating()) {
        <wv-button (pressed)="openCreate()">Novo lead</wv-button>
      }
    </wv-page-header>

    @if (creating()) {
      <form class="lead" [formGroup]="form" (ngSubmit)="submit()" aria-labelledby="lead-title">
        <h2 id="lead-title">Novo lead de revenda</h2>
        <fieldset>
          <legend>Empresa</legend>
          <wv-text-field class="wide" label="Razão social" formControlName="legalName" [maxLength]="200" autocomplete="organization" />
          <wv-text-field label="Nome fantasia (opcional)" formControlName="tradeName" [maxLength]="200" />
          <wv-text-field label="CNPJ" formControlName="taxId" [maxLength]="18" hint="Aceita o formato numérico e o alfanumérico." />
        </fieldset>
        <fieldset>
          <legend>Quem recebe o primeiro acesso</legend>
          <p class="hint">Na aprovação, esta pessoa recebe o convite para o WAVER Partner como dona da revenda.</p>
          <wv-text-field label="Nome completo" formControlName="ownerName" [maxLength]="120" autocomplete="name" />
          <wv-text-field label="E-mail" formControlName="ownerEmail" [maxLength]="254" autocomplete="email" />
        </fieldset>
        <fieldset>
          <legend>Contato comercial</legend>
          <wv-text-field label="Nome completo" formControlName="commercialName" [maxLength]="120" />
          <wv-text-field label="E-mail" formControlName="commercialEmail" [maxLength]="254" />
          <wv-text-field label="Telefone (opcional)" formControlName="commercialPhone" [maxLength]="20" autocomplete="tel" />
        </fieldset>
        @if (errorMessage()) { <p class="error" role="alert">{{ errorMessage() }}</p> }
        <div class="form-actions">
          <wv-button type="submit" [disabled]="store.saving()">{{ store.saving() ? 'Registrando...' : 'Registrar lead' }}</wv-button>
          <wv-button variant="quiet" (pressed)="creating.set(false)">Cancelar</wv-button>
        </div>
      </form>
    }

    <ol class="track" aria-label="Revendas por etapa">
      <li><strong>{{ store.counts().inReview }}</strong><span>em análise</span></li>
      <li><strong>{{ store.counts().approved }}</strong><span>aprovadas, em ativação</span></li>
      <li><strong>{{ store.counts().active }}</strong><span>ativas</span></li>
    </ol>

    @if (store.loading() && store.items().length === 0) {
      <p class="state">Carregando revendas...</p>
    } @else if (store.loadError()) {
      <div class="state" role="alert">Não foi possível carregar as revendas.
        <wv-button variant="secondary" (pressed)="store.load()">Tentar de novo</wv-button></div>
    } @else if (store.items().length === 0) {
      <div class="state empty">
        <p>Nenhuma revenda ainda. Registre o primeiro lead para começar o onboarding.</p>
        @if (!creating()) { <wv-button variant="secondary" (pressed)="openCreate()">Novo lead</wv-button> }
      </div>
    } @else {
      <table class="wv-table">
        <thead>
          <tr><th scope="col">Razão social</th><th scope="col">CNPJ</th><th scope="col">Etapa</th>
              <th scope="col">Onboarding</th><th scope="col">Registrada em</th></tr>
        </thead>
        <tbody>
          @for (r of store.items(); track r.id) {
            <tr [class.fresh]="r.id === store.lastCreatedId()">
              <td class="name"><a [routerLink]="['/revendas', r.id]">{{ r.legalName }}</a></td>
              <td class="num">{{ r.taxId }}</td>
              <td><wv-status-badge [tone]="stage[r.onboardingStatus].tone">{{ stage[r.onboardingStatus].label }}</wv-status-badge></td>
              <td>
                <span class="progress" [attr.aria-label]="progressLabel(r)">
                  <span class="bar"><span [style.width.%]="progressPct(r)"></span></span>
                  <span class="num">{{ progress(r).done }}/{{ progress(r).total }}</span>
                </span>
              </td>
              <td>{{ r.createdAt | date: 'dd/MM/yyyy' }}</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--wv-space-6); }
    .lead {
      display: grid; gap: var(--wv-space-5); max-width: 760px; padding: var(--wv-space-6);
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle); border-radius: var(--wv-radius-lg);
    }
    .lead h2 { font-size: var(--wv-font-size-lg); }
    fieldset { border: 0; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--wv-space-3); }
    legend { font-weight: var(--wv-font-weight-semibold); font-size: var(--wv-font-size-sm); margin-bottom: var(--wv-space-2); padding: 0; }
    .wide { grid-column: 1 / -1; }
    .hint { grid-column: 1 / -1; margin: calc(-1 * var(--wv-space-1)) 0 0; color: var(--wv-color-text-secondary); font-size: var(--wv-font-size-sm); }
    .error { margin: 0; color: var(--wv-color-status-danger); font-size: var(--wv-font-size-sm); }
    .form-actions { display: flex; gap: var(--wv-space-2); }

    /* Stage track: the three counts read left to right as the onboarding moves forward. */
    .track { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 0; }
    .track li {
      display: flex; align-items: baseline; gap: var(--wv-space-2); padding: var(--wv-space-2) var(--wv-space-8) var(--wv-space-2) 0;
      margin-right: var(--wv-space-6); position: relative;
    }
    .track li:not(:last-child)::after {
      content: ''; position: absolute; right: 0; top: 50%; width: 10px; height: 18px; translate: 0 -50%;
      background: var(--wv-color-border-decorative); clip-path: polygon(0 0, 100% 50%, 0 100%, 35% 50%);
    }
    .track strong { font-size: var(--wv-font-size-2xl); font-weight: var(--wv-font-weight-semibold); font-variant-numeric: tabular-nums; }
    .track span { color: var(--wv-color-text-secondary); }

    .state { color: var(--wv-color-text-secondary); display: flex; align-items: center; gap: var(--wv-space-4); margin: 0; }
    .state.empty { flex-direction: column; align-items: flex-start; }
    .state.empty p { margin: 0; }
    .name a { color: inherit; font-weight: var(--wv-font-weight-medium); text-decoration: none; }
    .name a:hover { text-decoration: underline; text-underline-offset: 3px; }
    .num { font-variant-numeric: tabular-nums; white-space: nowrap; }
    .progress { display: inline-flex; align-items: center; gap: var(--wv-space-2); }
    .bar { width: 72px; height: 4px; border-radius: 2px; background: var(--wv-color-bg-surface-muted); overflow: hidden; }
    .bar span { display: block; height: 100%; background: var(--wv-color-action-primary); }
    tr.fresh td:first-child { box-shadow: inset 3px 0 0 var(--wv-color-accent-highlight); }
  `,
})
export class ResellersPage implements OnInit {
  protected readonly store = inject(ResellersStore);
  private readonly router = inject(Router);
  protected readonly stage = STAGE;
  protected readonly creating = signal(false);
  protected readonly form = new FormGroup({
    legalName: required(200),
    tradeName: new FormControl('', { nonNullable: true }),
    taxId: required(18),
    ownerName: required(120),
    ownerEmail: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    commercialName: required(120),
    commercialEmail: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    commercialPhone: new FormControl('', { nonNullable: true }),
  });
  private readonly localError = signal<string | null>(null);
  protected readonly errorMessage = computed(() => {
    const p = this.store.saveError();
    return this.localError() ?? (p ? resellerProblemMessage(p, 'registrar o lead') : null);
  });

  ngOnInit(): void {
    void this.store.load();
  }

  protected openCreate(): void {
    this.store.clearSaveError();
    this.localError.set(null);
    this.form.reset();
    this.creating.set(true);
  }

  protected async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.localError.set('Preencha a empresa, quem recebe o primeiro acesso e o contato comercial.');
      return;
    }
    this.localError.set(null);
    const v = this.form.getRawValue();
    const created = await this.store.registerLead({
      legalName: v.legalName,
      tradeName: v.tradeName,
      taxId: v.taxId,
      owner: { name: v.ownerName, email: v.ownerEmail },
      commercialContact: { name: v.commercialName, email: v.commercialEmail, phone: v.commercialPhone },
    });
    if (created) {
      this.creating.set(false);
      void this.router.navigate(['/revendas', created.id]);
    }
  }

  protected progress(r: Reseller): { done: number; total: number } {
    return activationProgress(r);
  }

  protected progressPct(r: Reseller): number {
    const p = activationProgress(r);
    return p.total === 0 ? 0 : Math.round((p.done / p.total) * 100);
  }

  protected progressLabel(r: Reseller): string {
    const p = activationProgress(r);
    return `${p.done} de ${p.total} etapas obrigatórias concluídas`;
  }
}
