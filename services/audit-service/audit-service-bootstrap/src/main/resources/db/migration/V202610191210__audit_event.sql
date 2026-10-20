-- Append-only, hash-chained audit trail (AR-009, MICROSERVICES_MAP: audit-service owns AuditEvent).

create table audit_event (
    audit_id       uuid        primary key,
    occurred_at    timestamptz not null,
    recorded_at    timestamptz not null default now(),
    service        text        not null,
    action         text        not null,
    outcome        text        not null,
    actor_id       text        not null,
    actor_type     text        not null,
    actor_org_id   uuid,
    target_org_id  uuid,
    resource_type  text        not null,
    resource_id    text        not null,
    correlation_id text,
    details        text        not null default '',
    chain_key      text        not null,
    previous_hash  text        not null,
    hash           text        not null unique
);
create index audit_event_target on audit_event (target_org_id, audit_id desc);

create table audit_chain (
    chain_key text primary key,
    last_hash text
);

create function audit_event_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'audit_event is append-only';
end $$;

create trigger audit_event_no_update before update or delete on audit_event
    for each row execute function audit_event_immutable();

alter table audit_event enable row level security;
alter table audit_event force row level security;

-- WAVER staff and the ingestion path see everything; others only entries about their own organization.
create policy audit_read on audit_event for select
    using (current_setting('waver.platform_access', true) = 'on'
           or target_org_id::text = current_setting('waver.org_id', true));
create policy audit_insert on audit_event for insert
    with check (current_setting('waver.platform_access', true) = 'on');

create table processed_event (
  consumer_group text        not null,
  event_id       text        not null,
  processed_at   timestamptz not null default now(),
  primary key (consumer_group, event_id)
);

grant select, insert on audit_event to audit_app;
grant select, insert, update on audit_chain to audit_app;
grant select, insert, delete on processed_event to audit_app;
