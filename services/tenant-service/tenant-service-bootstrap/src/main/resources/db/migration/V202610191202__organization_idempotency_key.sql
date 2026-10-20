-- Idempotent creation (STD-003 Idempotency-Key): a retried POST /v1/organizations returns the first result.
-- Unique per parent organization, so one caller's keys never collide with another caller's.
alter table organization add column idempotency_key text;
create unique index organization_idempotency_key on organization (parent_org_id, idempotency_key) where idempotency_key is not null;
