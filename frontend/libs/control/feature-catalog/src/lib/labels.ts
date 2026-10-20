import {
  BillingPeriod,
  ChargeModel,
  ChargeType,
  ItemType,
  PriceBookStatus,
  PriceScope,
  PricingModel,
  Problem,
  ProductStatus,
  Sku,
  SkuUnit,
} from '@waver/control-data-access-api';
import { commonProblemMessage } from '@waver/shared-data-access-app-state';
import { WvOption, WvTone } from '@waver/shared-ui-kit';

export const ITEM_TYPE: Record<ItemType, string> = {
  PRODUCT: 'Produto',
  TOOL: 'Ferramenta',
  CONNECTOR: 'Conector',
  SOLUTION_PACK: 'Pacote de solução',
  CHANNEL: 'Canal',
  CAPACITY: 'Capacidade',
  SERVICE: 'Serviço',
};

export const UNIT: Record<SkuUnit, string> = {
  WORKSPACE: 'workspace',
  CONNECTION: 'conexão',
  NUMBER: 'número',
  SEAT: 'assento',
  RUN: 'execução',
  TOKEN: 'token',
  HOUR: 'hora',
};

export const CHARGE: Record<ChargeType, string> = { RECURRING: 'Recorrente', USAGE: 'Por uso', ONE_TIME: 'Única' };
export const PERIOD: Record<BillingPeriod, string> = { MONTHLY: 'mensal', YEARLY: 'anual' };
export const CHARGE_MODEL: Record<ChargeModel, string> = { PER_UNIT: 'Por unidade', FLAT: 'Valor fixo' };
export const PRICING_MODEL: Record<PricingModel, string> = {
  WHOLESALE_PLUS_MARGIN: 'Atacado + margem da revenda',
  SUGGESTED_RETAIL: 'Preço sugerido ao cliente',
  FIXED_RETAIL: 'Preço fixo ao cliente',
};
export const SCOPE: Record<PriceScope, string> = { GLOBAL: 'Todas as revendas', TIER: 'Um tier', RESELLER: 'Uma revenda' };

export const PRODUCT_STATUS: Record<ProductStatus, { label: string; tone: WvTone }> = {
  DRAFT: { label: 'Rascunho', tone: 'neutral' },
  PUBLISHED: { label: 'Publicado', tone: 'success' },
  RETIRED: { label: 'Retirado', tone: 'neutral' },
};

export const BOOK_STATUS: Record<PriceBookStatus, { label: string; tone: WvTone }> = {
  DRAFT: { label: 'Rascunho', tone: 'neutral' },
  ACTIVE: { label: 'Ativa', tone: 'success' },
  RETIRED: { label: 'Substituída', tone: 'neutral' },
};

export function options<K extends string>(labels: Record<K, string>): WvOption[] {
  return (Object.keys(labels) as K[]).map((value) => ({ value, label: labels[value] }));
}

/** "Recorrente mensal por workspace", "Por uso por execução". */
export function skuCharge(s: Pick<Sku, 'chargeType' | 'billingPeriod' | 'unit'>): string {
  const period = s.billingPeriod ? ` ${PERIOD[s.billingPeriod]}` : '';
  return `${CHARGE[s.chargeType]}${period} por ${UNIT[s.unit]}`;
}

const money = new Map<string, Intl.NumberFormat>();

export function formatMoney(amount: number | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) {
    return '—';
  }
  let f = money.get(currency);
  if (!f) {
    f = new Intl.NumberFormat('pt-BR', { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 4 });
    money.set(currency, f);
  }
  return f.format(amount);
}

export function formatPct(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 }).format(value)}%`;
}

/** Accepts "1.234,56", "1234,56" and "1234.56". Empty → null; anything else → NaN. */
export function parseAmount(text: string): number | null {
  const t = text.trim().replace(/\s|R\$/g, '');
  if (!t) {
    return null;
  }
  const normalized = t.includes(',') ? t.replace(/\./g, '').replace(',', '.') : t;
  return /^\d+(\.\d+)?$/.test(normalized) ? Number(normalized) : Number.NaN;
}

export function catalogProblemMessage(p: Problem, action: string): string {
  switch (p.code) {
    case 'CATALOG_PRODUCT_KEY_INVALID':
      return 'Chave do produto: 3 a 63 letras minúsculas, números ou hífens, por exemplo waver-agentes.';
    case 'CATALOG_PRODUCT_KEY_EXISTS':
      return 'Já existe um produto com esta chave.';
    case 'CATALOG_PRODUCT_NAME_INVALID':
      return 'Informe o nome do produto com 2 a 120 caracteres.';
    case 'CATALOG_SKU_CODE_INVALID':
      return 'Código do SKU: 3 a 60 letras, números ou hífens, por exemplo WV-AGENTE-MENSAL.';
    case 'CATALOG_SKU_CODE_EXISTS':
      return 'Este código de SKU já está em uso.';
    case 'CATALOG_SKU_PERIOD_REQUIRED':
      return 'Um SKU recorrente precisa do período de cobrança.';
    case 'CATALOG_SKU_METER_REQUIRED':
      return 'Um SKU por uso precisa da chave do medidor, por exemplo agent.runs.';
    case 'CATALOG_SKU_METER_UNEXPECTED':
    case 'CATALOG_SKU_ONE_TIME_INVALID':
      return 'Só SKUs por uso têm medidor, e cobranças únicas não têm período.';
    case 'CATALOG_PRODUCT_WITHOUT_SKU':
      return 'Cadastre pelo menos um SKU antes de publicar.';
    case 'CATALOG_PRODUCT_NOT_DRAFT':
      return 'O produto já foi publicado e não muda mais.';
    case 'CATALOG_PRICE_BOOK_NAME_INVALID':
      return 'Informe o nome da tabela com 2 a 120 caracteres.';
    case 'CATALOG_CURRENCY_INVALID':
      return 'Informe a moeda no padrão ISO, por exemplo BRL.';
    case 'CATALOG_SCOPE_INVALID':
      return 'Confira o alcance: tier em maiúsculas (ex.: GOLD) ou o ID da organização da revenda.';
    case 'CATALOG_PRICE_BOOK_EMPTY':
      return 'Cadastre o preço de pelo menos um SKU antes de ativar.';
    case 'CATALOG_PRICE_BOOK_NOT_DRAFT':
      return 'Tabelas ativas não mudam. Crie uma nova tabela em rascunho.';
    case 'CATALOG_RETAIL_BELOW_WHOLESALE':
      return 'Os valores ao cliente não podem ficar abaixo do atacado.';
    case 'CATALOG_RETAIL_FIXED_REQUIRED':
    case 'CATALOG_RETAIL_SUGGESTED_REQUIRED':
      return 'Informe o valor ao cliente do modelo escolhido.';
    case 'CATALOG_RETAIL_FIXED_EXCLUSIVE':
    case 'CATALOG_RETAIL_FIXED_UNEXPECTED':
      return 'Preço fixo usa só o valor fixo; os outros modelos não usam valor fixo.';
    case 'CATALOG_RETAIL_BOUNDS_INVALID':
    case 'CATALOG_RETAIL_SUGGESTION_OUT_OF_BOUNDS':
      return 'O piso deve ficar abaixo do teto, e o sugerido entre os dois.';
    case 'CATALOG_PRICE_INCOMPLETE':
    case 'CATALOG_PRICE_NEGATIVE':
      return 'Informe valores de zero para cima.';
    case 'CATALOG_MARGIN_INVALID':
    case 'CATALOG_MARGIN_BAND_INVALID':
      return 'A margem mínima é zero ou mais, e a máxima não fica abaixo dela.';
    case 'CATALOG_DISCOUNT_LIMIT_INVALID':
      return 'O limite de desconto é um percentual entre 0 e 100.';
    case 'CATALOG_PRICE_NOT_AVAILABLE':
      return 'Este SKU não tem preço ativo para esta revenda.';
    case 'CATALOG_PRODUCT_FORBIDDEN':
      return 'Só a equipe de Product Ops da WAVER cuida de produtos.';
    case 'CATALOG_PRICE_FORBIDDEN':
      return 'Só a equipe comercial da WAVER cuida de preços.';
    case 'CATALOG_CONCURRENT_UPDATE':
      return 'O catálogo mudou enquanto você trabalhava. Os dados foram recarregados.';
    case 'VALIDATION_FAILED':
      return 'Preencha os campos obrigatórios.';
    default:
      return commonProblemMessage(p, action);
  }
}
