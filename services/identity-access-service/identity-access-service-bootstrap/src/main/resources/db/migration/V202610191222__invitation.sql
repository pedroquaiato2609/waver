-- Invitations (RFC-0004 §Invitation). Visible to the invited organization and to WAVER staff.
create table invitation (
    id          uuid        primary key,
    org_id      uuid        not null,
    org_type    text        not null check (org_type in ('RESELLER', 'CUSTOMER')),
    email       text        not null,             -- personal data (STD-009): never in events or logs
    first_name  text        not null,
    last_name   text        not null,
    role_key    text        not null,
    user_id     text        not null,             -- Keycloak user ID
    status      text        not null check (status in ('INVITED', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    invited_at  timestamptz not null,
    expires_at  timestamptz not null,
    created_by  text        default current_setting('waver.org_id', true)
);
create index invitation_org_email on invitation (org_id, email, invited_at desc);

alter table invitation enable row level security;
alter table invitation force row level security;
create policy invitation_access on invitation
    using (current_setting('waver.platform_access', true) = 'on'
           or org_id::text = current_setting('waver.org_id', true))
    with check (current_setting('waver.platform_access', true) = 'on'
                or org_id::text = current_setting('waver.org_id', true));

grant select, insert, update on invitation to identity_access_app;
