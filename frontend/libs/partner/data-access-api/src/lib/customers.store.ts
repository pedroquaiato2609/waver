import { HttpClient } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { Problem, toProblem } from '@waver/shared-data-access-app-state';
import { firstValueFrom } from 'rxjs';
import { Customer, NewCustomer, Page } from './models';

interface CustomersState {
  items: Customer[];
  loading: boolean;
  loadError: string | null;
  saving: boolean;
  saveError: Problem | null;
  lastCreatedId: string | null;
  /** One key per open registration form: a double submit or a retry never registers the customer twice. */
  idempotencyKey: string;
}

/** The reseller's customer portfolio (NgRx SignalStore, ADR-0027). */
export const CustomersStore = signalStore(
  { providedIn: 'root' },
  withState<CustomersState>({
    items: [], loading: false, loadError: null, saving: false, saveError: null, lastCreatedId: null,
    idempotencyKey: crypto.randomUUID(),
  }),
  withComputed(({ items }) => ({
    counts: computed(() => ({
      total: items().length,
      prospects: items().filter((c) => c.displayStatus === 'PROSPECT' || c.displayStatus === 'CONTRACTING').length,
      active: items().filter((c) => c.displayStatus === 'ACTIVE' || c.displayStatus === 'PAST_DUE').length,
      attention: items().filter((c) => ['PAST_DUE', 'RESTRICTED', 'SUSPENDED'].includes(c.displayStatus)).length,
    })),
  })),
  withMethods((store, http = inject(HttpClient)) => ({
    async load(): Promise<void> {
      patchState(store, { loading: true, loadError: null });
      try {
        const page = await firstValueFrom(http.get<Page<Customer>>('/api/customers', { params: { limit: 200 } }));
        patchState(store, { items: page.items, loading: false });
      } catch (e) {
        patchState(store, { loading: false, loadError: toProblem(e).code });
      }
    },
    startRegistration(): void {
      patchState(store, { saveError: null, idempotencyKey: crypto.randomUUID() });
    },
    async register(customer: NewCustomer): Promise<Customer | null> {
      patchState(store, { saving: true, saveError: null });
      try {
        const created = await firstValueFrom(http.post<Customer>('/api/customers', { ...customer, taxIdType: 'CNPJ' }, {
          headers: { 'Idempotency-Key': store.idempotencyKey() },
        }));
        const others = store.items().filter((c) => c.id !== created.id);
        patchState(store, { saving: false, items: [created, ...others], lastCreatedId: created.id });
        return created;
      } catch (e) {
        patchState(store, { saving: false, saveError: toProblem(e) });
        return null;
      }
    },
  })),
);
