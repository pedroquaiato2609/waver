-- customer-service (RFC-0017 §3): CustomerProfile, CustomerContact, CustomerOwnership.
-- org_id is the customer's own organization; reseller_org_id is the current owner (D-01: the reseller that
-- registered it). Row-Level Security (ADR-0010):
--   a reseller sees and registers customers of its own portfolio (reseller_org_id = token org);
--   the customer itself sees its own row (Workspace, later); WAVER staff have audited oversight.

create table customer_profile (
    id                uuid        primary key,
    org_id            uuid        not null unique,
    reseller_org_id   uuid        not null,
    legal_name        text        not null check (length(legal_name) between 2 and 200),
    trade_name        text,
    tax_id_type       text        not null check (tax_id_type in ('CNPJ', 'CPF', 'FOREIGN')),
    tax_id            text        not null,
    segment           text,
    lifecycle_stage   text        not null check (lifecycle_stage in ('PROSPECT', 'CONTRACTING', 'PROVISIONING', 'ACTIVE', 'TERMINATED')),
    access_status     text        not null check (access_status in ('NORMAL', 'RESTRICTED', 'SUSPENDED')),
    financial_status  text        not null check (financial_status in ('CURRENT', 'PAST_DUE', 'GRACE_PERIOD', 'IN_COLLECTION')),
    idempotency_key   text,
    version           bigint      not null default 0,
    created_at        timestamptz not null default now(),
    created_by        text,
    updated_at        timestamptz not null default now()
);
-- One customer per tax ID inside a portfolio. Not global: a global constraint would reveal to a reseller
-- that another reseller already serves that company (channel conflicts are handled by WAVER, D-01).
create unique index customer_tax_id_per_portfolio on customer_profile (reseller_org_id, tax_id_type, tax_id);
create unique index customer_idempotency_key on customer_profile (reseller_org_id, idempotency_key) where idempotency_key is not null;
create index customer_profile_portfolio on customer_profile (reseller_org_id, id desc);

create table customer_contact (
    id            bigint      generated always as identity primary key,
    customer_id   uuid        not null references customer_profile (id),
    contact_type  text        not null check (contact_type in ('ADMIN', 'FINANCIAL', 'TECHNICAL', 'LEGAL')),
    name          text        not null,   -- personal data (STD-009)
    email         text        not null,
    phone         text,
    created_at    timestamptz not null default now()
);
create index customer_contact_customer on customer_contact (customer_id);

-- Commercial ownership history (RFC-0017 §3). Exactly one open row (valid_to null) per customer.
create table customer_ownership (
    id               uuid        primary key,
    customer_id      uuid        not null references customer_profile (id),
    customer_org_id  uuid        not null,
    reseller_org_id  uuid        not null,
    valid_from       timestamptz not null,
    valid_to         timestamptz,
    transfer_id      uuid
);
create unique index customer_ownership_open on customer_ownership (customer_org_id) where valid_to is null;

alter table customer_profile enable row level security;
alter table customer_profile force row level security;
create policy customer_profile_read on customer_profile for select
    using (reseller_org_id::text = current_setting('waver.org_id', true)
           or org_id::text = current_setting('waver.org_id', true)
           or current_setting('waver.platform_access', true) = 'on');
create policy customer_profile_insert on customer_profile for insert
    with check (reseller_org_id::text = current_setting('waver.org_id', true)
                or current_setting('waver.platform_access', true) = 'on');
create policy customer_profile_update on customer_profile for update
    using (reseller_org_id::text = current_setting('waver.org_id', true)
           or current_setting('waver.platform_access', true) = 'on');

-- Child rows follow the visibility of their customer (the profile policy decides).
alter table customer_contact enable row level security;
alter table customer_contact force row level security;
create policy customer_contact_access on customer_contact
    using (exists (select 1 from customer_profile p where p.id = customer_id))
    with check (exists (select 1 from customer_profile p where p.id = customer_id));

alter table customer_ownership enable row level security;
alter table customer_ownership force row level security;
create policy customer_ownership_read on customer_ownership for select
    using (exists (select 1 from customer_profile p where p.id = customer_id));
create policy customer_ownership_insert on customer_ownership for insert
    with check (reseller_org_id::text = current_setting('waver.org_id', true)
                or current_setting('waver.platform_access', true) = 'on');

grant select, insert, update on customer_profile, customer_ownership to customer_app;
grant select, insert, update on customer_contact to customer_app;
