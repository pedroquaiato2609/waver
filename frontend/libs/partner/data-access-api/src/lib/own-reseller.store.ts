import { HttpClient } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { toProblem } from '@waver/shared-data-access-app-state';
import { firstValueFrom } from 'rxjs';
import { OwnReseller } from './models';

interface OwnResellerState {
  profile: OwnReseller | null;
  loading: boolean;
  error: string | null;
}

/** The signed-in user's reseller. Partner shows whether it can already sell (ACTIVE). */
export const OwnResellerStore = signalStore(
  { providedIn: 'root' },
  withState<OwnResellerState>({ profile: null, loading: false, error: null }),
  withComputed(({ profile }) => ({
    canSell: computed(() => profile()?.onboardingStatus === 'ACTIVE'),
  })),
  withMethods((store, http = inject(HttpClient)) => ({
    async load(): Promise<void> {
      patchState(store, { loading: true, error: null });
      try {
        patchState(store, { profile: await firstValueFrom(http.get<OwnReseller>('/api/reseller')), loading: false });
      } catch (e) {
        patchState(store, { loading: false, error: toProblem(e).code });
      }
    },
  })),
);
