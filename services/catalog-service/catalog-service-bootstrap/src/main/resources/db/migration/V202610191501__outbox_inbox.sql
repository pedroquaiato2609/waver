-- Transactional outbox relayed by Debezium (RFC-0006 §8, ADR-0024) and consumer inbox (ADR-0012).

create table outbox_event (
  seq                 bigint      generated always as identity primary key,
  id                  uuid        not null,
  org_id              uuid,
  aggregate_type      text        not null,
  aggregate_id        uuid        not null,
  topic               text        not null,
  payload             bytea,
  schema_id           int,
  content_type        text        not null default 'application/avro',
  ce_specversion      text        not null default '1.0',
  ce_type             text        not null,
  ce_source           text        not null,
  ce_subject          text        not null,
  ce_time             text        not null,
  ce_dataschema       text        not null,
  ce_orgid            text        not null,
  ce_workspaceid      text,
  ce_domainid         text,
  ce_correlationid    text        not null,
  ce_causationid      text        not null,
  ce_actorid          text        not null,
  ce_actortype        text        not null,
  ce_supportgrantid   text,
  ce_aggregateversion text,
  ce_traceparent      text,
  ce_tracestate       text,
  republish_of        bigint,
  created_at          timestamptz not null default now()
);
create index outbox_event_created on outbox_event (created_at);
create index outbox_event_id on outbox_event (id);

create table debezium_heartbeat (id int primary key, ts timestamptz not null);

create table processed_event (
  consumer_group text        not null,
  event_id       text        not null,
  processed_at   timestamptz not null default now(),
  primary key (consumer_group, event_id)
);

grant insert on outbox_event to catalog_app;
grant select, insert, delete on processed_event to catalog_app;
grant select on outbox_event, debezium_heartbeat to catalog_cdc;

create publication waver_outbox for table outbox_event, debezium_heartbeat;
