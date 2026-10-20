-- Organization aggregate (RFC-0003 §1) with Row-Level Security (ADR-0010).
-- Runs as tenant_owner (Flyway). The application connects as tenant_app, which is not the owner and has no BYPASSRLS.

create table organization (
    id             uuid        primary key,
    org_type       text        not null check (org_type in ('PLATFORM', 'RESELLER', 'CUSTOMER')),
    parent_org_id  uuid        references organization (id),
    legal_name     text        not null check (length(legal_name) between 2 and 200),
    status         text        not null check (status in ('PENDING', 'ACTIVE', 'SUSPENDED', 'TERMINATED')),
    version        bigint      not null default 0,
    created_at     timestamptz not null default now(),
    created_by     text,
    updated_at     timestamptz not null default now(),
    constraint platform_has_no_parent check ((org_type = 'PLATFORM') = (parent_org_id is null))
);

create unique index organization_single_platform on organization ((org_type)) where org_type = 'PLATFORM';
create index organization_parent on organization (parent_org_id);

alter table organization enable row level security;
alter table organization force row level security;

-- Visible: the caller's own organization, its direct children (reseller portfolio, WAVER -> resellers),
-- or everything for WAVER staff on audited oversight paths (waver.platform_access = 'on').
create policy organization_read on organization for select
    using (
        id::text = current_setting('waver.org_id', true)
        or parent_org_id::text = current_setting('waver.org_id', true)
        or current_setting('waver.platform_access', true) = 'on'
    );

-- New organizations are always created under the caller's own organization.
create policy organization_insert on organization for insert
    with check (
        parent_org_id::text = current_setting('waver.org_id', true)
        or current_setting('waver.platform_access', true) = 'on'
    );

create policy organization_update on organization for update
    using (
        parent_org_id::text = current_setting('waver.org_id', true)
        or current_setting('waver.platform_access', true) = 'on'
    );

grant select, insert, update on organization to tenant_app;

-- The single PLATFORM organization (Grupo WAVER). Its id is shared with Keycloak (org_id claim of WAVER staff).
-- FORCE RLS applies to the owner too, so the seed opens the oversight path for this transaction only.
select set_config('waver.platform_access', 'on', true);
insert into organization (id, org_type, parent_org_id, legal_name, status, created_by)
values ('${platformOrgId}', 'PLATFORM', null, 'Grupo WAVER', 'ACTIVE', 'migration');
