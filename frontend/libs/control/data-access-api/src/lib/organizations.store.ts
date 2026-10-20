import { HttpClient } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { toProblem } from '@waver/shared-data-access-app-state';
import { firstValueFrom } from 'rxjs';
import { Organization, OrgType, Page } from './models';

interface OrganizationsState {
  items: Organization[];
  loading: boolean;
  loadError: string | null;
  filter: OrgType | 'ALL';
}

/**
 * Tenancy structure of the WAVER network as seen by Control (read-only). Reseller organizations are created by
 * the reseller onboarding (ResellersStore), customer organizations by the resellers in WAVER Partner.
 */
export const OrganizationsStore = signalStore(
  { providedIn: 'root' },
  withState<OrganizationsState>({ items: [], loading: false, loadError: null, filter: 'ALL' }),
  withComputed(({ items, filter }) => ({
    visible: computed(() => items().filter((o) => o.orgType !== 'PLATFORM' && (filter() === 'ALL' || o.orgType === filter()))),
    platform: computed(() => items().find((o) => o.orgType === 'PLATFORM') ?? null),
    counts: computed(() => ({
      resellers: items().filter((o) => o.orgType === 'RESELLER').length,
      customers: items().filter((o) => o.orgType === 'CUSTOMER').length,
      pending: items().filter((o) => o.status === 'PENDING').length,
    })),
  })),
  withMethods((store, http = inject(HttpClient)) => ({
    setFilter(filter: OrgType | 'ALL'): void {
      patchState(store, { filter });
    },
    async load(): Promise<void> {
      patchState(store, { loading: true, loadError: null });
      try {
        const page = await firstValueFrom(http.get<Page<Organization>>('/api/organizations', { params: { limit: 200 } }));
        patchState(store, { items: page.items, loading: false });
      } catch (e) {
        patchState(store, { loading: false, loadError: toProblem(e).code });
      }
    },
  })),
);
