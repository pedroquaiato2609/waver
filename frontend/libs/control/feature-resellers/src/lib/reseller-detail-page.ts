import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  activationProgress,
  awaitsAutomaticSteps,
  ChecklistItem,
  ChecklistItemKey,
  Reseller,
  ResellersStore,
} from '@waver/control-data-access-api';
import { WvButton, WvStatusBadge, WvTextField } from '@waver/shared-ui-kit';
import { ITEM, itemOpenNow, resellerProblemMessage, STAGE } from './labels';

/**
 * One reseller's onboarding (RFC-0017 §2). The checklist is a real sequence, so it is numbered. The panel on top
 * always says what the next step is; automatic steps (organization, owner invitation) are run by the onboarding
 * workflow and the page follows them until they finish.
 */
@Component({
  selector: 'wv-reseller-detail-page',
  imports: [DatePipe, ReactiveFormsModule, RouterLink, WvButton, WvStatusBadge, WvTextField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="back" routerLink="/revendas">Revendas</a>

    @if (reseller(); as r) {
      <header class="head">
        <div>
          <h1>{{ r.legalName }}</h1>
          <p class="sub">
            <span class="num">CNPJ {{ r.taxId }}</span>
            @if (r.tradeName) { <span>{{ r.tradeName }}</span> }
          </p>
        </div>
        <wv-status-badge [tone]="stage[r.onboardingStatus].tone">{{ stage[r.onboardingStatus].label }}</wv-status-badge>
      </header>

      <section class="next" [class.done]="r.onboardingStatus === 'ACTIVE'" aria-labelledby="next-title">
        <h2 id="next-title">Próximo passo</h2>
        @switch (r.onboardingStatus) {
          @case ('LEAD') {
            <p>Inicie a análise para registrar a due diligence da empresa.</p>
            <div class="actions"><wv-button [disabled]="store.saving()" (pressed)="startReview(r)">Iniciar análise</wv-button></div>
          }
          @case ('UNDER_REVIEW') {
            @if (!isDone(r, 'DUE_DILIGENCE')) {
              <p>Registre a due diligence no checklist abaixo. Depois disso a revenda pode ser aprovada.</p>
            } @else {
              <p>Due diligence concluída. Aprove para criar a organização da revenda e convidar o dono, ou reprove com o motivo.</p>
            }
            @if (rejecting()) {
              <div class="reject">
                <wv-text-field label="Motivo da reprovação" [formControl]="reason" [maxLength]="1000" />
                <div class="actions">
                  <wv-button [disabled]="store.saving()" (pressed)="decide(r, 'REJECT')">Confirmar reprovação</wv-button>
                  <wv-button variant="quiet" (pressed)="rejecting.set(false)">Voltar</wv-button>
                </div>
              </div>
            } @else {
              <div class="actions">
                <wv-button [disabled]="store.saving() || !isDone(r, 'DUE_DILIGENCE')" (pressed)="decide(r, 'APPROVE')">Aprovar revenda</wv-button>
                <wv-button variant="secondary" [disabled]="store.saving()" (pressed)="rejecting.set(true)">Reprovar</wv-button>
              </div>
            }
            <p class="fine">Enquanto o fluxo de aprovações não existe, a decisão é registrada diretamente pela equipe comercial e fica na auditoria.</p>
          }
          @case ('APPROVED') {
            @if (automaticPending()) {
              <p class="working" aria-live="polite">Criando a organização da revenda e enviando o convite ao dono. Isso leva alguns segundos.</p>
            } @else if (progress().done < progress().total) {
              <p>{{ remainingText() }} Marque no checklist para liberar a ativação.</p>
            } @else {
              <p>Tudo pronto. Ao ativar, a revenda passa a poder cadastrar clientes no WAVER Partner.</p>
            }
            <div class="actions">
              <wv-button [disabled]="store.saving() || progress().done < progress().total" (pressed)="activate(r)">Ativar revenda</wv-button>
            </div>
          }
          @case ('ACTIVE') {
            <p>Revenda ativa. {{ owner(r)?.name }} recebeu o convite por e-mail e entra pelo WAVER Partner.</p>
          }
          @case ('REJECTED') {
            <p>Reprovada em {{ r.decidedAt | date: 'dd/MM/yyyy' }}. Motivo: {{ r.decisionReason }}</p>
          }
        }
        @if (errorMessage()) { <p class="error" role="alert">{{ errorMessage() }}</p> }
      </section>

      <div class="columns">
        <section aria-labelledby="checklist-title">
          <h2 id="checklist-title">Checklist de onboarding</h2>
          <ol class="steps">
            @for (i of r.checklist; track i.item; let n = $index) {
              <li [class.done]="i.done" [class.optional]="!i.requiredForActivation">
                <span class="mark" aria-hidden="true">{{ i.done ? '' : n + 1 }}</span>
                <div class="body">
                  <div class="line">
                    <strong>{{ item[i.item].label }}</strong>
                    @if (!i.requiredForActivation) { <span class="tag">Opcional nesta fase</span> }
                    @else if (i.automatic && !i.done) { <span class="tag">Automático</span> }
                  </div>
                  <p class="help">{{ item[i.item].help }}</p>
                  @if (i.done) {
                    <p class="meta">{{ doneText(i) }}</p>
                  } @else if (canMark(r, i)) {
                    @if (marking() === i.item) {
                      <div class="mark-form">
                        <wv-text-field label="Referência da evidência (opcional)" [formControl]="evidence" [maxLength]="500"
                                       hint="Número do documento, link interno ou chamado." />
                        <div class="actions">
                          <wv-button [disabled]="store.saving()" (pressed)="complete(r, i.item)">Marcar como concluído</wv-button>
                          <wv-button variant="quiet" (pressed)="marking.set(null)">Cancelar</wv-button>
                        </div>
                      </div>
                    } @else {
                      <wv-button variant="secondary" (pressed)="startMarking(i.item)">Concluir etapa</wv-button>
                    }
                  }
                </div>
              </li>
            }
          </ol>
        </section>

        <aside aria-labelledby="contacts-title">
          <h2 id="contacts-title">Contatos</h2>
          <dl class="contacts">
            @for (c of r.contacts; track c.type) {
              <div>
                <dt>{{ c.type === 'OWNER' ? 'Primeiro acesso (dono)' : 'Comercial' }}</dt>
                <dd>{{ c.name }}</dd>
                <dd class="muted">{{ c.email }}</dd>
                @if (c.phone) { <dd class="muted num">{{ c.phone }}</dd> }
              </div>
            }
          </dl>
          <h2>Rastreio</h2>
          <p class="trace">Workflow no Temporal<br /><code>{{ r.workflowId }}</code></p>
          @if (r.resellerOrgId) { <p class="trace">Organização<br /><code>{{ r.resellerOrgId }}</code></p> }
        </aside>
      </div>
    } @else if (store.loadError()) {
      <p class="state" role="alert">Não foi possível carregar esta revenda.</p>
    } @else {
      <p class="state">Carregando...</p>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--wv-space-6); }
    .back { color: var(--wv-color-text-link); font-size: var(--wv-font-size-sm); text-underline-offset: 3px; align-self: flex-start; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--wv-space-6); }
    h1 { font-size: var(--wv-font-size-2xl); line-height: var(--wv-line-height-tight); }
    h2 { font-size: var(--wv-font-size-md); font-weight: var(--wv-font-weight-semibold); margin-bottom: var(--wv-space-3); }
    .sub { margin: var(--wv-space-1) 0 0; color: var(--wv-color-text-secondary); display: flex; gap: var(--wv-space-4); flex-wrap: wrap; }
    .num { font-variant-numeric: tabular-nums; }

    .next {
      padding: var(--wv-space-5) var(--wv-space-6); border-radius: var(--wv-radius-lg);
      background: var(--wv-color-bg-surface); border: 1px solid var(--wv-color-border-subtle);
      border-left: 3px solid var(--wv-color-accent-highlight); display: grid; gap: var(--wv-space-3); max-width: 760px;
    }
    .next.done { border-left-color: var(--wv-color-status-success); }
    .next h2 { margin: 0; }
    .next p { margin: 0; }
    .fine { font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); }
    .working { color: var(--wv-color-text-secondary); }
    .working::before {
      content: ''; display: inline-block; width: 8px; height: 8px; margin-right: var(--wv-space-2); rotate: 45deg;
      background: var(--wv-color-accent-highlight); animation: wv-pulse 1.2s ease-in-out infinite;
    }
    @keyframes wv-pulse { 50% { opacity: 0.25; } }
    @media (prefers-reduced-motion: reduce) { .working::before { animation: none; } }
    .actions { display: flex; gap: var(--wv-space-2); flex-wrap: wrap; }
    .reject, .mark-form { display: grid; gap: var(--wv-space-3); max-width: 520px; }
    .error { color: var(--wv-color-status-danger); font-size: var(--wv-font-size-sm); }

    .columns { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: var(--wv-space-10); align-items: start; }
    .steps { list-style: none; margin: 0; padding: 0; display: grid; }
    .steps li { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: var(--wv-space-3); padding: var(--wv-space-3) 0; position: relative; }
    .steps li:not(:last-child)::after {
      content: ''; position: absolute; left: 13px; top: 38px; bottom: -6px; width: 1px; background: var(--wv-color-border-subtle);
    }
    .mark {
      width: 26px; height: 26px; border-radius: 50%; display: grid; place-items: center;
      border: 1px solid var(--wv-color-border-control); font-size: var(--wv-font-size-xs); font-variant-numeric: tabular-nums;
      color: var(--wv-color-text-secondary); background: var(--wv-color-bg-canvas);
    }
    li.done .mark { border-color: var(--wv-color-status-success); background: var(--wv-color-status-success-bg); position: relative; }
    li.done .mark::after {
      content: ''; width: 9px; height: 5px; border-left: 2px solid var(--wv-color-status-success);
      border-bottom: 2px solid var(--wv-color-status-success); rotate: -45deg; translate: 0 -1px;
    }
    li.optional:not(.done) { opacity: 0.72; }
    .body { display: grid; gap: var(--wv-space-1); justify-items: start; }
    .line { display: flex; gap: var(--wv-space-2); align-items: baseline; flex-wrap: wrap; }
    .tag { font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); }
    .help, .meta { margin: 0; font-size: var(--wv-font-size-sm); color: var(--wv-color-text-secondary); }

    .contacts { margin: 0 0 var(--wv-space-6); display: grid; gap: var(--wv-space-4); }
    dt { font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); margin-bottom: 2px; }
    dd { margin: 0; font-size: var(--wv-font-size-sm); overflow-wrap: anywhere; }
    .muted { color: var(--wv-color-text-secondary); }
    .trace { font-size: var(--wv-font-size-xs); color: var(--wv-color-text-secondary); margin: 0 0 var(--wv-space-3); }
    code { font-size: var(--wv-font-size-xs); color: var(--wv-color-text-primary); overflow-wrap: anywhere; }
    .state { color: var(--wv-color-text-secondary); }

    @media (max-width: 960px) { .columns { grid-template-columns: 1fr; } }
  `,
})
export class ResellerDetailPage {
  /** Route parameter (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly store = inject(ResellersStore);
  private readonly date = new DatePipe('pt-BR');
  protected readonly stage = STAGE;
  protected readonly item = ITEM;
  protected readonly rejecting = signal(false);
  protected readonly marking = signal<ChecklistItemKey | null>(null);
  protected readonly reason = new FormControl('', { nonNullable: true });
  protected readonly evidence = new FormControl('', { nonNullable: true });

  protected readonly reseller = computed(() => this.store.items().find((r) => r.id === this.id()) ?? null);
  protected readonly progress = computed(() => {
    const r = this.reseller();
    return r ? activationProgress(r) : { done: 0, total: 0 };
  });
  protected readonly automaticPending = computed(() => {
    const r = this.reseller();
    return r ? awaitsAutomaticSteps(r) : false;
  });
  protected readonly errorMessage = computed(() => {
    const p = this.store.saveError();
    return p ? resellerProblemMessage(p, 'concluir esta ação') : null;
  });

  constructor() {
    // Load on entry, then follow the workflow while its automatic steps run (short polling, stops by itself).
    let timer: ReturnType<typeof setInterval> | undefined;
    effect(() => {
      const id = this.id();
      untracked(() => {
        this.store.clearSaveError();
        void this.store.refresh(id);
      });
    });
    effect(() => {
      const pending = this.automaticPending();
      if (pending && !timer) {
        timer = setInterval(() => void this.store.refresh(this.id()), 2000);
      } else if (!pending && timer) {
        clearInterval(timer);
        timer = undefined;
      }
    });
    inject(DestroyRef).onDestroy(() => timer && clearInterval(timer));
  }

  protected readonly remainingText = computed(() => {
    const left = this.progress().total - this.progress().done;
    return left === 1 ? 'Falta 1 etapa obrigatória.' : `Faltam ${left} etapas obrigatórias.`;
  });

  protected doneText(i: ChecklistItem): string {
    const when = i.doneAt ? this.date.transform(i.doneAt, 'dd/MM/yyyy HH:mm') : '';
    const evidence = i.evidenceRef && !i.automatic ? `, evidência ${i.evidenceRef}` : '';
    return `Concluído em ${when} por ${this.who(i)}${evidence}`;
  }

  protected isDone(r: Reseller, key: ChecklistItemKey): boolean {
    return r.checklist.some((i) => i.item === key && i.done);
  }

  protected canMark(r: Reseller, i: ChecklistItem): boolean {
    return !i.automatic && itemOpenNow(i.item, r.onboardingStatus);
  }

  protected owner(r: Reseller) {
    return r.contacts.find((c) => c.type === 'OWNER');
  }

  protected who(i: ChecklistItem): string {
    return i.doneBy === 'reseller-service' ? 'plataforma (automático)' : 'equipe WAVER';
  }

  protected startMarking(key: ChecklistItemKey): void {
    this.store.clearSaveError();
    this.evidence.reset('');
    this.marking.set(key);
  }

  protected async startReview(r: Reseller): Promise<void> {
    await this.afterCommand(this.store.startReview(r.id), r.id);
  }

  protected async complete(r: Reseller, key: ChecklistItemKey): Promise<void> {
    if (await this.afterCommand(this.store.completeItem(r.id, key, this.evidence.value), r.id)) {
      this.marking.set(null);
    }
  }

  protected async decide(r: Reseller, decision: 'APPROVE' | 'REJECT'): Promise<void> {
    if (await this.afterCommand(this.store.decide(r.id, decision, this.reason.value), r.id)) {
      this.rejecting.set(false);
    }
  }

  protected async activate(r: Reseller): Promise<void> {
    await this.afterCommand(this.store.activate(r.id), r.id);
  }

  /** A stale page (another person moved the reseller) reloads instead of showing an outdated checklist. */
  private async afterCommand(command: Promise<Reseller | null>, id: string): Promise<boolean> {
    const result = await command;
    if (!result) {
      await this.store.refresh(id);
    }
    return result !== null;
  }
}
