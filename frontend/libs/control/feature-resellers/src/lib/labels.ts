import { ChecklistItemKey, OnboardingStatus, Problem } from '@waver/control-data-access-api';
import { commonProblemMessage } from '@waver/shared-data-access-app-state';
import { WvTone } from '@waver/shared-ui-kit';

export const STAGE: Record<OnboardingStatus, { label: string; tone: WvTone }> = {
  LEAD: { label: 'Lead', tone: 'neutral' },
  UNDER_REVIEW: { label: 'Em análise', tone: 'info' },
  APPROVED: { label: 'Aprovada', tone: 'warning' },
  ACTIVE: { label: 'Ativa', tone: 'success' },
  REJECTED: { label: 'Reprovada', tone: 'danger' },
  SUSPENDED: { label: 'Suspensa', tone: 'danger' },
  TERMINATED: { label: 'Encerrada', tone: 'neutral' },
};

/** Checklist wording for WAVER Commercial (RFC-0017 ResellerOnboarding). */
export const ITEM: Record<ChecklistItemKey, { label: string; help: string }> = {
  DUE_DILIGENCE: { label: 'Due diligence', help: 'Análise cadastral, fiscal e de crédito da empresa.' },
  COMMERCIAL_APPROVAL: { label: 'Aprovação comercial', help: 'Registrada quando a revenda é aprovada.' },
  AGREEMENT_SIGNED: { label: 'Contrato de revenda assinado', help: 'Informe a referência do documento assinado.' },
  ORGANIZATION_CREATED: { label: 'Organização criada', help: 'A plataforma cria a organização da revenda após a aprovação.' },
  CATALOG_ASSIGNED: { label: 'Catálogo atribuído', help: 'Ofertas do Marketplace disponíveis para esta revenda.' },
  PRICING_RULES_SET: { label: 'Regras de preço definidas', help: 'Faixa de margem e limite de desconto no Catálogo.' },
  ADMIN_INVITED: { label: 'Dono da revenda convidado', help: 'A plataforma envia o convite de acesso por e-mail.' },
  TRAINING_DONE: { label: 'Treinamento concluído', help: 'Capacitação comercial e de suporte de primeiro nível.' },
};

/** Manual items can be marked only in their phase (mirrors ResellerProfile.completeChecklistItem). */
export function itemOpenNow(item: ChecklistItemKey, status: OnboardingStatus): boolean {
  switch (item) {
    case 'DUE_DILIGENCE':
      return status === 'UNDER_REVIEW';
    case 'AGREEMENT_SIGNED':
      return status === 'UNDER_REVIEW' || status === 'APPROVED';
    case 'COMMERCIAL_APPROVAL':
    case 'ORGANIZATION_CREATED':
    case 'ADMIN_INVITED':
      return false;
    default:
      return status === 'APPROVED';
  }
}

export function resellerProblemMessage(p: Problem, action: string): string {
  switch (p.code) {
    case 'RESELLER_TAX_ID_EXISTS':
      return 'Já existe uma revenda cadastrada com este CNPJ.';
    case 'TAX_ID_INVALID':
      return 'CNPJ inválido. Confira os 14 caracteres, com ou sem pontuação.';
    case 'RESELLER_LEGAL_NAME_INVALID':
      return 'Informe a razão social com 2 a 200 caracteres.';
    case 'RESELLER_CONTACT_NAME_INVALID':
      return 'Informe o nome completo dos contatos.';
    case 'RESELLER_CONTACT_EMAIL_INVALID':
      return 'Confira os e-mails informados.';
    case 'RESELLER_CONTACT_PHONE_INVALID':
      return 'Telefone inválido. Use DDD e número, por exemplo +55 11 4000-1234.';
    case 'RESELLER_CONTACTS_INCOMPLETE':
      return 'Cadastre o contato comercial antes de iniciar a análise.';
    case 'RESELLER_DUE_DILIGENCE_PENDING':
      return 'Conclua a due diligence antes de aprovar.';
    case 'RESELLER_REJECTION_REASON_REQUIRED':
      return 'Explique o motivo da reprovação em pelo menos 5 caracteres.';
    case 'RESELLER_CHECKLIST_INCOMPLETE':
      return 'Ainda há etapas obrigatórias abertas. Conclua todas antes de ativar.';
    case 'RESELLER_INVALID_TRANSITION':
    case 'RESELLER_CONCURRENT_UPDATE':
    case 'RESELLER_CHECKLIST_ITEM_OUT_OF_PHASE':
      return 'A revenda mudou de etapa enquanto você trabalhava. Os dados foram recarregados.';
    case 'RESELLER_COMMAND_FORBIDDEN':
      return 'Só a equipe comercial da WAVER pode conduzir o onboarding.';
    case 'VALIDATION_FAILED':
      return 'Preencha os campos obrigatórios.';
    default:
      return commonProblemMessage(p, action);
  }
}
