-- WAVER Organization <-> Keycloak organization mapping (ADR-0021). Platform-level data: platform access only.
create table identity_organization (
    org_id                   uuid        primary key,
    org_type                 text        not null,
    keycloak_organization_id text        not null unique,
    display_name             text        not null,
    linked_at                timestamptz not null
);

alter table identity_organization enable row level security;
alter table identity_organization force row level security;
create policy identity_organization_platform on identity_organization
    using (current_setting('waver.platform_access', true) = 'on'
           or org_id::text = current_setting('waver.org_id', true))
    with check (current_setting('waver.platform_access', true) = 'on');

grant select, insert on identity_organization to identity_access_app;
