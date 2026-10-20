import { HttpClient } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { Problem, toProblem } from '@waver/shared-data-access-app-state';
import { firstValueFrom } from 'rxjs';
import { Page } from './models';

/** Shapes of catalog-service as relayed by control-bff (RFC-0017 §4). Amounts are decimals in the book currency. */
export type ItemType = 'PRODUCT' | 'TOOL' | 'CONNECTOR' | 'SOLUTION_PACK' | 'CHANNEL' | 'CAPACITY' | 'SERVICE';
export type ProductStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';
export type SkuUnit = 'WORKSPACE' | 'CONNECTION' | 'NUMBER' | 'SEAT' | 'RUN' | 'TOKEN' | 'HOUR';
export type ChargeType = 'RECURRING' | 'USAGE' | 'ONE_TIME';
export type BillingPeriod = 'MONTHLY' | 'YEARLY';
export type PriceScope = 'GLOBAL' | 'TIER' | 'RESELLER';
export type PriceBookStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED';
export type ChargeModel = 'FLAT' | 'PER_UNIT';
export type PricingModel = 'WHOLESALE_PLUS_MARGIN' | 'SUGGESTED_RETAIL' | 'FIXED_RETAIL';

export interface Sku {
  id: string;
  productId: string;
  skuCode: string;
  unit: SkuUnit;
  chargeType: ChargeType;
  billingPeriod: BillingPeriod | null;
  meterKey: string | null;
  /** Has a price in an active, valid book. Only in the product detail. */
  priced: boolean | null;
}

export interface Product {
  id: string;
  productKey: string;
  name: string;
  itemType: ItemType;
  technicalRef: string | null;
  status: ProductStatus;
  publishedAt: string | null;
  skus: Sku[];
  version: number;
  createdAt: string;
}

export interface PriceEntry {
  skuId: string;
  chargeModel: ChargeModel;
  wholesaleAmount: number;
  pricingModel: PricingModel;
  suggestedAmount: number | null;
  fixedAmount: number | null;
  floorAmount: number | null;
  ceilingAmount: number | null;
}

export interface PriceBook {
  id: string;
  name: string;
  currency: string;
  scope: PriceScope;
  scopeRef: string | null;
  validFrom: string;
  validTo: string | null;
  status: PriceBookStatus;
  activatedAt: string | null;
  activatedBy: string | null;
  entries: PriceEntry[];
  version: number;
  createdAt: string;
}

export interface PolicyTarget {
  scope: PriceScope;
  scopeRef: string | null;
  skuId: string | null;
  validFrom: string;
  validTo: string | null;
}

export interface PricingRule {
  id: string;
  target: PolicyTarget;
  minMarginPct: number;
  maxMarginPct: number | null;
  createdAt: string;
}

export interface DiscountPolicy {
  id: string;
  target: PolicyTarget;
  maxDiscountWithoutApprovalPct: number;
  approvalPolicyKey: string;
  createdAt: string;
}

export interface PriceQuote {
  skuId: string;
  productId: string | null;
  skuCode: string | null;
  unit: SkuUnit | null;
  chargeType: ChargeType | null;
  billingPeriod: BillingPeriod | null;
  quantity: number;
  available: boolean;
  unavailableReason: string | null;
  priceBookId: string | null;
  currency: string | null;
  chargeModel: ChargeModel | null;
  wholesaleUnitAmount: number | null;
  wholesaleTotalAmount: number | null;
  pricingModel: PricingModel | null;
  retailUnitAmount: number | null;
  retailTotalAmount: number | null;
  retailFloorAmount: number | null;
  retailCeilingAmount: number | null;
  marginBand: { ruleId: string; minMarginPct: number; maxMarginPct: number | null } | null;
}

export interface DiscountEvaluation {
  outcome: 'WITHIN_POLICY' | 'APPROVAL_REQUIRED' | 'FORBIDDEN';
  approvalPolicyKey: string | null;
  reasons: string[];
  proposedUnitPrice: number;
  discountPct: number;
  marginPct: number | null;
  quote: PriceQuote;
}

export interface NewProduct {
  productKey: string;
  name: string;
  itemType: ItemType;
  technicalRef: string | null;
}

export interface NewSku {
  skuCode: string;
  unit: SkuUnit;
  chargeType: ChargeType;
  billingPeriod: BillingPeriod | null;
  meterKey: string | null;
}

export interface NewPriceBook {
  name: string;
  currency: string;
  scope: PriceScope;
  scopeRef: string | null;
}

export interface PriceEntryInput {
  wholesale: { chargeModel: ChargeModel; amount: number };
  retail: {
    pricingModel: PricingModel;
    suggestedAmount: number | null;
    fixedAmount: number | null;
    floorAmount: number | null;
    ceilingAmount: number | null;
  };
}

export interface NewTarget {
  scope: PriceScope;
  scopeRef: string | null;
  skuId: string | null;
}

interface CatalogState {
  products: Product[];
  priceBooks: PriceBook[];
  pricingRules: PricingRule[];
  discountPolicies: DiscountPolicy[];
  loading: boolean;
  loadError: string | null;
  saving: boolean;
  saveError: Problem | null;
}

/** Catalog of WAVER Control: products and SKUs (Product Ops), price books and pricing policies (Commercial). */
export const CatalogStore = signalStore(
  { providedIn: 'root' },
  withState<CatalogState>({
    products: [], priceBooks: [], pricingRules: [], discountPolicies: [], loading: false, loadError: null, saving: false, saveError: null,
  }),
  withComputed(({ products }) => ({
    /** Every SKU by ID, with its product, to label price entries and listings. */
    skuIndex: computed(() => {
      const index = new Map<string, { sku: Sku; product: Product }>();
      products().forEach((product) => product.skus.forEach((sku) => index.set(sku.id, { sku, product })));
      return index;
    }),
    publishedProducts: computed(() => products().filter((p) => p.status === 'PUBLISHED')),
  })),
  withMethods((store, http = inject(HttpClient)) => {
    const upsert = <T extends { id: string }>(list: T[], item: T): T[] =>
      list.some((x) => x.id === item.id) ? list.map((x) => (x.id === item.id ? item : x)) : [item, ...list];
    const command = async <T>(request: Promise<T>, apply: (value: T) => void): Promise<T | null> => {
      patchState(store, { saving: true, saveError: null });
      try {
        const value = await request;
        apply(value);
        patchState(store, { saving: false });
        return value;
      } catch (e) {
        patchState(store, { saving: false, saveError: toProblem(e) });
        return null;
      }
    };
    const putProduct = (p: Product) => patchState(store, { products: upsert(store.products(), p) });
    const putBook = (b: PriceBook) => patchState(store, { priceBooks: upsert(store.priceBooks(), b) });
    return {
      async load(): Promise<void> {
        patchState(store, { loading: true, loadError: null });
        try {
          const [products, books, rules, policies] = await Promise.all([
            firstValueFrom(http.get<Page<Product>>('/api/catalog/products', { params: { limit: 200 } })),
            firstValueFrom(http.get<Page<PriceBook>>('/api/catalog/price-books', { params: { limit: 200 } })),
            firstValueFrom(http.get<Page<PricingRule>>('/api/catalog/pricing-rules')),
            firstValueFrom(http.get<Page<DiscountPolicy>>('/api/catalog/discount-policies')),
          ]);
          patchState(store, {
            products: products.items, priceBooks: books.items, pricingRules: rules.items, discountPolicies: policies.items, loading: false,
          });
        } catch (e) {
          patchState(store, { loading: false, loadError: toProblem(e).code });
        }
      },
      async refreshProduct(id: string): Promise<Product | null> {
        try {
          const p = await firstValueFrom(http.get<Product>(`/api/catalog/products/${id}`));
          putProduct(p);
          return p;
        } catch (e) {
          patchState(store, { loadError: toProblem(e).code });
          return null;
        }
      },
      async refreshPriceBook(id: string): Promise<PriceBook | null> {
        try {
          const b = await firstValueFrom(http.get<PriceBook>(`/api/catalog/price-books/${id}`));
          putBook(b);
          return b;
        } catch (e) {
          patchState(store, { loadError: toProblem(e).code });
          return null;
        }
      },
      createProduct(p: NewProduct): Promise<Product | null> {
        return command(firstValueFrom(http.post<Product>('/api/catalog/products', p)), putProduct);
      },
      addSku(productId: string, sku: NewSku): Promise<Product | null> {
        return command(firstValueFrom(http.post<Product>(`/api/catalog/products/${productId}/skus`, sku)), putProduct);
      },
      publishProduct(productId: string): Promise<Product | null> {
        return command(firstValueFrom(http.post<Product>(`/api/catalog/products/${productId}/publication`, {})), putProduct);
      },
      createPriceBook(b: NewPriceBook): Promise<PriceBook | null> {
        return command(firstValueFrom(http.post<PriceBook>('/api/catalog/price-books', b)), putBook);
      },
      putEntry(bookId: string, skuId: string, entry: PriceEntryInput): Promise<PriceBook | null> {
        return command(firstValueFrom(http.put<PriceBook>(`/api/catalog/price-books/${bookId}/entries/${skuId}`, entry)), putBook);
      },
      removeEntry(bookId: string, skuId: string): Promise<PriceBook | null> {
        return command(firstValueFrom(http.delete<PriceBook>(`/api/catalog/price-books/${bookId}/entries/${skuId}`)), putBook);
      },
      /** Activation retires the active book of the same scope and currency, so the list is reloaded. */
      async activatePriceBook(bookId: string): Promise<PriceBook | null> {
        const book = await command(firstValueFrom(http.post<PriceBook>(`/api/catalog/price-books/${bookId}/activation`, {})), putBook);
        if (book) {
          const page = await firstValueFrom(http.get<Page<PriceBook>>('/api/catalog/price-books', { params: { limit: 200 } }));
          patchState(store, { priceBooks: page.items });
        }
        return book;
      },
      definePricingRule(target: NewTarget, minMarginPct: number, maxMarginPct: number | null): Promise<PricingRule | null> {
        return command(firstValueFrom(http.post<PricingRule>('/api/catalog/pricing-rules', { ...target, minMarginPct, maxMarginPct })),
          (r) => patchState(store, { pricingRules: [r, ...store.pricingRules()] }));
      },
      defineDiscountPolicy(target: NewTarget, maxDiscountWithoutApprovalPct: number, approvalPolicyKey: string | null): Promise<DiscountPolicy | null> {
        return command(firstValueFrom(http.post<DiscountPolicy>('/api/catalog/discount-policies',
          { ...target, maxDiscountWithoutApprovalPct, approvalPolicyKey })),
          (p) => patchState(store, { discountPolicies: [p, ...store.discountPolicies()] }));
      },
      evaluateDiscount(resellerOrgId: string, skuId: string, proposedUnitPrice: number): Promise<DiscountEvaluation | null> {
        return command(firstValueFrom(http.post<DiscountEvaluation>('/api/catalog/discount-policy-evaluations',
          { resellerOrgId, skuId, quantity: 1, proposedUnitPrice })), () => undefined);
      },
      clearSaveError(): void {
        patchState(store, { saveError: null });
      },
    };
  }),
);
