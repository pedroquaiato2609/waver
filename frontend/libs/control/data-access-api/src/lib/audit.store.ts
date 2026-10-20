import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { firstValueFrom } from 'rxjs';
import { AuditEvent, Page } from './models';

interface AuditState {
  items: AuditEvent[];
  loading: boolean;
  error: string | null;
}

/** Audit Center trail (Commercial §3, AR-009). */
export const AuditStore = signalStore(
  { providedIn: 'root' },
  withState<AuditState>({ items: [], loading: false, error: null }),
  withMethods((store, http = inject(HttpClient)) => ({
    async load(): Promise<void> {
      patchState(store, { loading: true, error: null });
      try {
        const page = await firstValueFrom(http.get<Page<AuditEvent>>('/api/audit-events', { params: { limit: 100 } }));
        patchState(store, { items: page.items, loading: false });
      } catch (e) {
        const code = e instanceof HttpErrorResponse && e.error?.code ? e.error.code : 'INTERNAL_ERROR';
        patchState(store, { loading: false, error: code });
      }
    },
  })),
);
