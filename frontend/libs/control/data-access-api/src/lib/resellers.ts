import { HttpClient } from '@angular/common/http';
import { computed, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { Problem, toProblem } from '@waver/shared-data-access-app-state';
import { firstValueFrom } from 'rxjs';
import { Page } from './models';

/** Shapes of reseller-service as relayed by control-bff (RFC-0017 §2). */
export type OnboardingStatus = 'LEAD' | 'UNDER_REVIEW' | 'APPROVED' | 'ACTIVE' | 'REJECTED' | 'SUSPENDED' | 'TERMINATED';

export type ChecklistItemKey =
  | 'DUE_DILIGENCE'
  | 'COMMERCIAL_APPROVAL'
  | 'AGREEMENT_SIGNED'
  | 'ORGANIZATION_CREATED'
  | 'CATALOG_ASSIGNED'
  | 'PRICING_RULES_SET'
  | 'ADMIN_INVITED'
  | 'TRAINING_DONE';

export interface ChecklistItem {
  item: ChecklistItemKey;
  automatic: boolean;
  requiredForActivation: boolean;
  done: boolean;
  doneAt: string | null;
  doneBy: string | null;
  evidenceRef: string | null;
}

export interface ResellerContact {
  type: 'OWNER' | 'COMMERCIAL' | 'FINANCIAL' | 'TECHNICAL' | 'LEGAL';
  name: string;
  email: string;
  phone: string | null;
}

export interface Reseller {
  id: string;
  legalName: string;
  tradeName: string | null;
  taxIdType: string;
  taxId: string;
  onboardingStatus: OnboardingStatus;
  resellerOrgId: string | null;
  contacts: ResellerContact[];
  checklist: ChecklistItem[];
  decidedBy: string | null;
  decidedAt: string | null;
  decisionReason: string | null;
  workflowId: string;
  version: number;
  createdAt: string;
}

export interface NewResellerLead {
  legalName: string;
  tradeName: string;
  taxId: string;
  owner: { name: string; email: string };
  commercialContact: { name: string; email: string; phone: string };
}

/** Progress of the items that gate activation. */
export function activationProgress(r: Reseller): { done: number; total: number } {
  const required = r.checklist.filter((i) => i.requiredForActivation);
  return { done: required.filter((i) => i.done).length, total: required.length };
}

/** The workflow still has automatic steps to run (organization, owner invitation). */
export function awaitsAutomaticSteps(r: Reseller): boolean {
  return r.onboardingStatus === 'APPROVED' && r.checklist.some((i) => i.automatic && !i.done);
}

interface ResellersState {
  items: Reseller[];
  loading: boolean;
  loadError: string | null;
  saving: boolean;
  saveError: Problem | null;
  lastCreatedId: string | null;
}

/** Reseller onboarding pipeline of WAVER Control (NgRx SignalStore, ADR-0027). */
export const ResellersStore = signalStore(
  { providedIn: 'root' },
  withState<ResellersState>({ items: [], loading: false, loadError: null, saving: false, saveError: null, lastCreatedId: null }),
  withComputed(({ items }) => ({
    counts: computed(() => {
      const by = (s: OnboardingStatus[]) => items().filter((r) => s.includes(r.onboardingStatus)).length;
      return { inReview: by(['LEAD', 'UNDER_REVIEW']), approved: by(['APPROVED']), active: by(['ACTIVE']) };
    }),
  })),
  withMethods((store, http = inject(HttpClient)) => {
    const replace = (updated: Reseller) => {
      const exists = store.items().some((r) => r.id === updated.id);
      patchState(store, {
        items: exists ? store.items().map((r) => (r.id === updated.id ? updated : r)) : [updated, ...store.items()],
      });
    };
    const command = async (request: Promise<Reseller>): Promise<Reseller | null> => {
      patchState(store, { saving: true, saveError: null });
      try {
        const updated = await request;
        replace(updated);
        patchState(store, { saving: false });
        return updated;
      } catch (e) {
        patchState(store, { saving: false, saveError: toProblem(e) });
        return null;
      }
    };
    return {
      async load(): Promise<void> {
        patchState(store, { loading: true, loadError: null });
        try {
          const page = await firstValueFrom(http.get<Page<Reseller>>('/api/resellers', { params: { limit: 200 } }));
          patchState(store, { items: page.items, loading: false });
        } catch (e) {
          patchState(store, { loading: false, loadError: toProblem(e).code });
        }
      },
      async refresh(id: string): Promise<Reseller | null> {
        try {
          const r = await firstValueFrom(http.get<Reseller>(`/api/resellers/${id}`));
          replace(r);
          return r;
        } catch (e) {
          patchState(store, { loadError: toProblem(e).code });
          return null;
        }
      },
      byId(id: string): Reseller | undefined {
        return store.items().find((r) => r.id === id);
      },
      async registerLead(lead: NewResellerLead): Promise<Reseller | null> {
        const created = await command(firstValueFrom(http.post<Reseller>('/api/resellers', { ...lead, taxIdType: 'CNPJ' })));
        if (created) {
          patchState(store, { lastCreatedId: created.id });
        }
        return created;
      },
      startReview(id: string): Promise<Reseller | null> {
        return command(firstValueFrom(http.post<Reseller>(`/api/resellers/${id}/review`, {})));
      },
      completeItem(id: string, item: ChecklistItemKey, evidenceRef: string): Promise<Reseller | null> {
        return command(firstValueFrom(http.put<Reseller>(`/api/resellers/${id}/onboarding-checklist`, { item, evidenceRef: evidenceRef || null })));
      },
      decide(id: string, decision: 'APPROVE' | 'REJECT', reason: string): Promise<Reseller | null> {
        return command(firstValueFrom(http.post<Reseller>(`/api/resellers/${id}/approval-decision`, { decision, reason: reason || null })));
      },
      activate(id: string): Promise<Reseller | null> {
        return command(firstValueFrom(http.post<Reseller>(`/api/resellers/${id}/activation`, {})));
      },
      clearSaveError(): void {
        patchState(store, { saveError: null });
      },
    };
  }),
);
