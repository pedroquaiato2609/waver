/** Shapes returned by control-bff (relayed from tenant-service and audit-service). */
export type OrgType = 'PLATFORM' | 'RESELLER' | 'CUSTOMER';
export type OrganizationStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED';

export interface Organization {
  id: string;
  orgType: OrgType;
  parentOrgId: string | null;
  legalName: string;
  status: OrganizationStatus;
  version: number;
  createdAt: string;
}

export interface AuditEvent {
  auditId: string;
  occurredAt: string;
  service: string;
  action: string;
  outcome: string;
  actorId: string;
  actorType: string;
  targetOrgId: string | null;
  resourceType: string;
  resourceId: string;
  correlationId: string | null;
  details: Record<string, string>;
  chainValid: boolean;
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

/** Problem is shared by every app (STD-008). */
export type { Problem } from '@waver/shared-data-access-app-state';
