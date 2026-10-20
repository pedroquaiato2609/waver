-- reseller-service (RFC-0017 §2): ResellerProfile, ResellerContact, ResellerOnboarding checklist.
-- Owned by the PLATFORM organization (org_id). Row-Level Security (ADR-0010):
--   WAVER staff and the reseller-service principal (platform access) read and write everything;
--   the users of a reseller read only their own profile, through reseller_org_id (WAVER Partner).

create table reseller_profile (
    id                 uuid        primary key,
    org_id             uuid        not null,
    reseller_org_id    uuid        unique,
    legal_name         text        not null check (length(legal_name) between 2 and 200),
    trade_name         text,
    tax_id_type        text        not null check (tax_id_type in ('CNPJ', 'CPF', 'FOREIGN')),
    tax_id             text        not null,
    onboarding_status  text        not null check (onboarding_status in
                           ('LEAD', 'UNDER_REVIEW', 'APPROVED', 'ACTIVE', 'REJECTED', 'SUSPENDED', 'TERMINATED')),
    sales_restriction  text        not null default 'NONE' check (sales_restriction in ('NONE', 'NEW_SALES_BLOCKED', 'PROVISIONING_BLOCKED')),
    tier_id            uuid,
    decided_by         text,
    approval_ref       text,
    decision_reason    text,
    decided_at         timestamptz,
    version            bigint      not null default 0,
    created_at         timestamptz not null default now(),
    created_by         text,
    updated_at         timestamptz not null default now(),
    constraint reseller_tax_id_unique unique (tax_id_type, tax_id)
);
create index reseller_profile_status on reseller_profile (onboarding_status, id desc);

create table reseller_contact (
    id           bigint      generated always as identity primary key,
    reseller_id  uuid        not null references reseller_profile (id),
    org_id       uuid        not null,
    contact_type text        not null check (contact_type in ('OWNER', 'COMMERCIAL', 'FINANCIAL', 'TECHNICAL', 'LEGAL')),
    name         text        not null,   -- personal data (STD-009)
    email        text        not null,
    phone        text,
    created_at   timestamptz not null default now()
);
create index reseller_contact_reseller on reseller_contact (reseller_id);

create table reseller_onboarding_item (
    reseller_id  uuid        not null references reseller_profile (id),
    org_id       uuid        not null,
    item_key     text        not null check (item_key in ('DUE_DILIGENCE', 'COMMERCIAL_APPROVAL', 'AGREEMENT_SIGNED',
                     'ORGANIZATION_CREATED', 'CATALOG_ASSIGNED', 'PRICING_RULES_SET', 'ADMIN_INVITED', 'TRAINING_DONE')),
    done_at      timestamptz not null,
    done_by      text        not null,
    evidence_ref text,
    primary key (reseller_id, item_key)
);

alter table reseller_profile enable row level security;
alter table reseller_profile force row level security;
create policy reseller_profile_read on reseller_profile for select
    using (current_setting('waver.platform_access', true) = 'on'
           or reseller_org_id::text = current_setting('waver.org_id', true));
create policy reseller_profile_insert on reseller_profile for insert
    with check (current_setting('waver.platform_access', true) = 'on');
create policy reseller_profile_update on reseller_profile for update
    using (current_setting('waver.platform_access', true) = 'on');

-- Child rows are visible when their profile is visible (the profile policy decides).
alter table reseller_contact enable row level security;
alter table reseller_contact force row level security;
create policy reseller_contact_read on reseller_contact for select
    using (exists (select 1 from reseller_profile p where p.id = reseller_id));
create policy reseller_contact_insert on reseller_contact for insert
    with check (current_setting('waver.platform_access', true) = 'on');

alter table reseller_onboarding_item enable row level security;
alter table reseller_onboarding_item force row level security;
create policy reseller_onboarding_item_read on reseller_onboarding_item for select
    using (exists (select 1 from reseller_profile p where p.id = reseller_id));
create policy reseller_onboarding_item_insert on reseller_onboarding_item for insert
    with check (current_setting('waver.platform_access', true) = 'on');

grant select, insert, update on reseller_profile to reseller_app;
grant select, insert on reseller_contact, reseller_onboarding_item to reseller_app;
grant usage on all sequences in schema public to reseller_app;
