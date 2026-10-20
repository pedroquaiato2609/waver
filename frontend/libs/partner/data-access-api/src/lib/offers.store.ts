import { HttpClient } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { toProblem } from '@waver/shared-data-access-app-state';
import { firstValueFrom } from 'rxjs';

/** Price of one SKU for the signed-in reseller (catalog-service quote, relayed by partner-bff). */
export interface OfferSku {
  skuId: string;
  skuCode: string | null;
  unit: 'WORKSPACE' | 'CONNECTION' | 'NUMBER' | 'SEAT' | 'RUN' | 'TOKEN' | 'HOUR' | null;
  chargeType: 'RECURRING' | 'USAGE' | 'ONE_TIME' | null;
  billingPeriod: 'MONTHLY' | 'YEARLY' | null;
  available: boolean;
  unavailableReason: string | null;
  currency: string | null;
  chargeModel: 'FLAT' | 'PER_UNIT' | null;
  wholesaleUnitAmount: number | null;
  pricingModel: 'WHOLESALE_PLUS_MARGIN' | 'SUGGESTED_RETAIL' | 'FIXED_RETAIL' | null;
  retailUnitAmount: number | null;
  retailFloorAmount: number | null;
  retailCeilingAmount: number | null;
  marginBand: { minMarginPct: number; maxMarginPct: number | null } | null;
}

/** A marketplace listing available to the reseller (RFC-0017 §5), with its prices. */
export interface Offer {
  listingId: string;
  listingKey: string;
  productId: string;
  category: string;
  title: string;
  summary: string | null;
  versionNo: number | null;
  requirements: string | null;
  docsRef: string | null;
  supportPolicy: string | null;
  publishedAt: string | null;
  skus: OfferSku[];
}

interface OffersState {
  items: Offer[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
}

/** "Ofertas disponíveis": only what WAVER made available to this reseller (NgRx SignalStore, ADR-0027). */
export const OffersStore = signalStore(
  { providedIn: 'root' },
  withState<OffersState>({ items: [], loading: false, loaded: false, error: null }),
  withComputed(({ items }) => ({
    categories: computed(() => [...new Set(items().map((o) => o.category))].sort((a, b) => a.localeCompare(b, 'pt-BR'))),
  })),
  withMethods((store, http = inject(HttpClient)) => ({
    async load(): Promise<void> {
      patchState(store, { loading: true, error: null });
      try {
        const page = await firstValueFrom(http.get<{ items: Offer[] }>('/api/offers'));
        patchState(store, { items: page.items, loading: false, loaded: true });
      } catch (e) {
        patchState(store, { loading: false, error: toProblem(e).code });
      }
    },
  })),
);
