import { HttpClient } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { Problem, toProblem } from '@waver/shared-data-access-app-state';
import { firstValueFrom } from 'rxjs';
import { Page } from './models';

/** Shapes of marketplace-service as relayed by control-bff (RFC-0017 §5). */
export type ListingStatus = 'DRAFT' | 'REVIEW' | 'PUBLISHED';
export type ListingVersionStatus = 'DRAFT' | 'REVIEW' | 'PUBLISHED' | 'SUPERSEDED';
export type AvailabilityMode = 'ALL_RESELLERS' | 'TIERS' | 'RESELLERS';

export interface ListingVersion {
  id: string;
  versionNo: number;
  skuIds: string[];
  requirements: string | null;
  docsRef: string | null;
  supportPolicy: string | null;
  status: ListingVersionStatus;
  submittedAt: string | null;
  submittedBy: string | null;
  publishedAt: string | null;
  approvedBy: string | null;
  returnReason: string | null;
}

export interface Availability {
  mode: AvailabilityMode;
  include: string[];
  exclude: string[];
  validFrom: string | null;
  validTo: string | null;
}

export interface Listing {
  id: string;
  listingKey: string;
  productId: string;
  category: string;
  title: string;
  summary: string | null;
  status: ListingStatus;
  currentVersion: ListingVersion | null;
  openVersion: ListingVersion | null;
  versions: ListingVersion[];
  availability: Availability | null;
  version: number;
  createdAt: string;
}

export interface NewListing {
  listingKey: string;
  productId: string;
  category: string;
  title: string;
  summary: string | null;
  skuIds: string[];
  requirements: string | null;
}

interface MarketplaceState {
  items: Listing[];
  loading: boolean;
  loadError: string | null;
  saving: boolean;
  saveError: Problem | null;
}

/** Listings of WAVER Control: draft, review, publication and availability (WAVER Product Ops). */
export const MarketplaceStore = signalStore(
  { providedIn: 'root' },
  withState<MarketplaceState>({ items: [], loading: false, loadError: null, saving: false, saveError: null }),
  withComputed(({ items }) => ({
    counts: computed(() => ({
      draft: items().filter((l) => l.status === 'DRAFT').length,
      review: items().filter((l) => l.status === 'REVIEW' || l.openVersion?.status === 'REVIEW').length,
      published: items().filter((l) => l.status === 'PUBLISHED').length,
    })),
  })),
  withMethods((store, http = inject(HttpClient)) => {
    const replace = (l: Listing) => patchState(store, {
      items: store.items().some((x) => x.id === l.id) ? store.items().map((x) => (x.id === l.id ? l : x)) : [l, ...store.items()],
    });
    const command = async (request: Promise<Listing>): Promise<Listing | null> => {
      patchState(store, { saving: true, saveError: null });
      try {
        const l = await request;
        replace(l);
        patchState(store, { saving: false });
        return l;
      } catch (e) {
        patchState(store, { saving: false, saveError: toProblem(e) });
        return null;
      }
    };
    return {
      async load(): Promise<void> {
        patchState(store, { loading: true, loadError: null });
        try {
          const page = await firstValueFrom(http.get<Page<Listing>>('/api/marketplace/listings', { params: { limit: 200 } }));
          patchState(store, { items: page.items, loading: false });
        } catch (e) {
          patchState(store, { loading: false, loadError: toProblem(e).code });
        }
      },
      async refresh(id: string): Promise<Listing | null> {
        try {
          const l = await firstValueFrom(http.get<Listing>(`/api/marketplace/listings/${id}`));
          replace(l);
          return l;
        } catch (e) {
          patchState(store, { loadError: toProblem(e).code });
          return null;
        }
      },
      create(l: NewListing): Promise<Listing | null> {
        return command(firstValueFrom(http.post<Listing>('/api/marketplace/listings', l)));
      },
      revise(id: string, skuIds: string[], requirements: string | null): Promise<Listing | null> {
        return command(firstValueFrom(http.post<Listing>(`/api/marketplace/listings/${id}/versions`, { skuIds, requirements })));
      },
      submit(id: string): Promise<Listing | null> {
        return command(firstValueFrom(http.post<Listing>(`/api/marketplace/listings/${id}/submission`, {})));
      },
      decide(id: string, decision: 'APPROVE' | 'RETURN', reason: string | null): Promise<Listing | null> {
        return command(firstValueFrom(http.post<Listing>(`/api/marketplace/listings/${id}/review-decision`, { decision, reason })));
      },
      setAvailability(id: string, mode: AvailabilityMode, include: string[], exclude: string[]): Promise<Listing | null> {
        return command(firstValueFrom(http.put<Listing>(`/api/marketplace/listings/${id}/availability-rules`, { mode, include, exclude })));
      },
      clearSaveError(): void {
        patchState(store, { saveError: null });
      },
    };
  }),
);
