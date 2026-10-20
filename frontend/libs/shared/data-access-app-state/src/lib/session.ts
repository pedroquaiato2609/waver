import { HttpClient } from '@angular/common/http';
import { EnvironmentProviders, inject, makeEnvironmentProviders } from '@angular/core';
import { Actions, createEffect, ofType, provideEffects } from '@ngrx/effects';
import { createActionGroup, createFeature, createReducer, emptyProps, on, props, provideState } from '@ngrx/store';
import { catchError, map, of, switchMap } from 'rxjs';

/**
 * App-wide session state (ADR-0027: @ngrx/store is used only for session, tenant context and notifications).
 * The session itself lives in the BFF (HttpOnly cookie); the SPA only learns who is signed in.
 */
export interface SessionUser {
  name: string;
  email: string;
  orgId: string;
  orgType: 'PLATFORM' | 'RESELLER' | 'CUSTOMER';
  roles: string[];
}

export interface SessionState {
  status: 'unknown' | 'loading' | 'signed-in' | 'signed-out' | 'error';
  user: SessionUser | null;
}

interface SessionResponse extends Partial<SessionUser> {
  authenticated: boolean;
}

export const SessionActions = createActionGroup({
  source: 'Session',
  events: {
    'Load': emptyProps(),
    'Loaded': props<{ user: SessionUser | null }>(),
    'Load failed': emptyProps(),
  },
});

export const sessionFeature = createFeature({
  name: 'session',
  reducer: createReducer<SessionState>(
    { status: 'unknown', user: null },
    on(SessionActions.load, (s) => ({ ...s, status: 'loading' as const })),
    on(SessionActions.loaded, (_, { user }) => ({ status: user ? ('signed-in' as const) : ('signed-out' as const), user })),
    on(SessionActions.loadFailed, () => ({ status: 'error' as const, user: null })),
  ),
});

export const loadSession = createEffect(
  (actions = inject(Actions), http = inject(HttpClient)) =>
    actions.pipe(
      ofType(SessionActions.load),
      switchMap(() =>
        http.get<SessionResponse>('/api/session').pipe(
          map((r) => SessionActions.loaded({
            user: r.authenticated
              ? { name: r.name ?? '', email: r.email ?? '', orgId: r.orgId ?? '', orgType: r.orgType ?? 'CUSTOMER', roles: r.roles ?? [] }
              : null,
          })),
          catchError(() => of(SessionActions.loadFailed())),
        ),
      ),
    ),
  { functional: true },
);

export function provideAppState(): EnvironmentProviders {
  return makeEnvironmentProviders([provideState(sessionFeature), provideEffects({ loadSession })]);
}

/** Starts the browser login at the BFF (Authorization Code + PKCE with Keycloak, ADR-0021). */
export function startSignIn(): void {
  window.location.assign('/oauth2/authorization/keycloak');
}

/** RP-initiated logout: a real form POST so the BFF can redirect to Keycloak's end-session endpoint. */
export function signOut(): void {
  const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '';
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = '/logout';
  const csrf = document.createElement('input');
  csrf.type = 'hidden';
  csrf.name = '_csrf';
  csrf.value = decodeURIComponent(token);
  form.appendChild(csrf);
  document.body.appendChild(form);
  form.submit();
}
