import { AvailabilityMode, ListingStatus, Problem } from '@waver/control-data-access-api';
import { commonProblemMessage } from '@waver/shared-data-access-app-state';
import { WvTone } from '@waver/shared-ui-kit';

export const LISTING_STATUS: Record<ListingStatus, { label: string; tone: WvTone }> = {
  DRAFT: { label: 'Rascunho', tone: 'neutral' },
  REVIEW: { label: 'Em revisão', tone: 'warning' },
  PUBLISHED: { label: 'Publicada', tone: 'success' },
};

export const MODE: Record<AvailabilityMode, string> = {
  ALL_RESELLERS: 'Todas as revendas',
  TIERS: 'Revendas de alguns tiers',
  RESELLERS: 'Revendas escolhidas',
};

export function marketplaceProblemMessage(p: Problem, action: string): string {
  switch (p.code) {
    case 'MARKETPLACE_LISTING_KEY_INVALID':
      return 'Chave da oferta: 3 a 63 letras minúsculas, números ou hífens.';
    case 'MARKETPLACE_LISTING_KEY_EXISTS':
      return 'Já existe uma oferta com esta chave.';
    case 'MARKETPLACE_CATEGORY_INVALID':
      return 'Informe a categoria com 2 a 60 caracteres.';
    case 'MARKETPLACE_TITLE_INVALID':
      return 'Informe o título com 2 a 120 caracteres.';
    case 'MARKETPLACE_VERSION_WITHOUT_SKU':
      return 'Escolha pelo menos um SKU para a oferta.';
    case 'MARKETPLACE_PRODUCT_NOT_PUBLISHED':
      return 'Publique o produto no catálogo antes.';
    case 'MARKETPLACE_SKU_NOT_IN_PRODUCT':
      return 'Todos os SKUs da oferta precisam ser do produto escolhido.';
    case 'MARKETPLACE_SKU_WITHOUT_PRICE':
      return 'Há SKU sem preço em tabela ativa. Ative uma tabela de preço com esses SKUs.';
    case 'MARKETPLACE_RETURN_REASON_REQUIRED':
      return 'Explique em pelo menos 5 caracteres o que precisa mudar.';
    case 'MARKETPLACE_VERSION_IN_REVIEW':
      return 'Há uma versão em revisão. Devolva-a para rascunho antes de mudar.';
    case 'MARKETPLACE_AVAILABILITY_TIERS_INVALID':
      return 'Informe pelo menos um tier em maiúsculas, por exemplo GOLD.';
    case 'MARKETPLACE_AVAILABILITY_RESELLERS_REQUIRED':
      return 'Escolha pelo menos uma revenda.';
    case 'MARKETPLACE_INVALID_TRANSITION':
    case 'MARKETPLACE_NOTHING_TO_SUBMIT':
    case 'MARKETPLACE_NOTHING_IN_REVIEW':
    case 'MARKETPLACE_CONCURRENT_UPDATE':
      return 'A oferta mudou enquanto você trabalhava. Os dados foram recarregados.';
    case 'MARKETPLACE_COMMAND_FORBIDDEN':
      return 'Só a equipe de Product Ops da WAVER cuida do marketplace.';
    case 'CATALOG_PRODUCT_NOT_FOUND':
      return 'Produto não encontrado no catálogo.';
    case 'VALIDATION_FAILED':
      return 'Preencha os campos obrigatórios.';
    default:
      return commonProblemMessage(p, action);
  }
}
