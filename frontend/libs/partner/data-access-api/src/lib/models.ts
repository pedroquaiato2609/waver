/** Shapes returned by partner-bff (relayed from customer-service and reseller-service, RFC-0017). */
export type CustomerDisplayStatus =
  | 'PROSPECT'
  | 'CONTRACTING'
  | 'PROVISIONING'
  | 'ACTIVE'
  | 'PAST_DUE'
  | 'RESTRICTED'
  | 'SUSPENDED'
  | 'TERMINATED';

export type FinancialStatus = 'CURRENT' | 'PAST_DUE' | 'GRACE_PERIOD' | 'IN_COLLECTION';

export interface Contact {
  type: string;
  name: string;
  email: string;
  phone: string | null;
}

export interface Customer {
  id: string;
  customerOrgId: string;
  resellerOrgId: string;
  legalName: string;
  tradeName: string | null;
  taxIdType: string;
  taxId: string;
  segment: string | null;
  lifecycleStage: string;
  accessStatus: string;
  financialStatus: FinancialStatus;
  displayStatus: CustomerDisplayStatus;
  contacts: Contact[];
  version: number;
  createdAt: string;
}

export interface NewCustomer {
  legalName: string;
  tradeName: string;
  taxId: string;
  segment: string;
  adminContact: { name: string; email: string; phone: string };
}

/** The reseller's own profile (no internal onboarding decisions, RFC-0017 §1). */
export interface OwnReseller {
  id: string;
  legalName: string;
  tradeName: string | null;
  taxId: string;
  onboardingStatus: 'LEAD' | 'UNDER_REVIEW' | 'APPROVED' | 'ACTIVE' | 'REJECTED' | 'SUSPENDED' | 'TERMINATED';
  resellerOrgId: string | null;
  contacts: Contact[];
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}
