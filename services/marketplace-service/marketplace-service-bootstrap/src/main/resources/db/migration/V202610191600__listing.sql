-- marketplace-service (RFC-0017 §5): Listing, ListingVersion, AvailabilityRule. Only WAVER publishes in v1
-- (D-10: publisher PLATFORM; third-party publishers disabled). Row-Level Security (ADR-0010):
--   WAVER staff (waver.platform_access = 'on') see and write everything;
--   a reseller reads only PUBLISHED listings whose availability rule matches its organization or tier, and
--   only their current version; it never reads the rules themselves (they name other resellers);
--   customers read nothing (RFC-0017 §1: the customer never sees a marketplace).

-- Projection of the reseller tier (reseller-service, ResellerTierChanged). Empty until tiers exist there.
create table reseller_tier (
    org_id      uuid        primary key,
    tier_key    text        not null,
    updated_at  timestamptz not null default now()
);

create table listing (
    id                  uuid        primary key,
    listing_key         text        not null unique check (listing_key ~ '^[a-z][a-z0-9-]{2,62}$'),
    publisher_type      text        not null default 'PLATFORM' check (publisher_type in ('PLATFORM', 'PARTNER')),
    product_id          uuid        not null,
    category            text        not null,
    title               text        not null,
    summary             text,
    status              text        not null check (status in ('DRAFT', 'REVIEW', 'PUBLISHED')),
    current_version_id  uuid,
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          text,
    updated_at          timestamptz not null default now(),
    updated_by          text
);
create index listing_list on listing (status, id desc);

create table listing_version (
    id              uuid        primary key,
    listing_id      uuid        not null references listing (id),
    version_no      int         not null check (version_no >= 1),
    sku_ids         uuid[]      not null default '{}',
    requirements    text,
    docs_ref        text,
    support_policy  text,
    status          text        not null check (status in ('DRAFT', 'REVIEW', 'PUBLISHED', 'SUPERSEDED')),
    submitted_at    timestamptz,
    submitted_by    text,
    published_at    timestamptz,
    approved_by     text,
    return_reason   text,
    created_at      timestamptz not null default now(),
    unique (listing_id, version_no)
);
-- At most one version being prepared or reviewed, and one published, per listing.
create unique index listing_version_open on listing_version (listing_id) where status in ('DRAFT', 'REVIEW');
create unique index listing_version_published on listing_version (listing_id) where status = 'PUBLISHED';

create table availability_rule (
    listing_id    uuid        primary key references listing (id),
    mode          text        not null check (mode in ('ALL_RESELLERS', 'TIERS', 'RESELLERS')),
    include_refs  text[]      not null default '{}',
    exclude_refs  text[]      not null default '{}',
    valid_from    timestamptz,
    valid_to      timestamptz,
    updated_at    timestamptz not null default now(),
    updated_by    text,
    check (valid_to is null or valid_from is null or valid_to > valid_from)
);

-- ---- Row-Level Security ----

-- availability_rule and reseller_tier are not FORCEd: the owner role bypasses their policies, which lets the
-- SECURITY DEFINER function below answer "is this listing available to me?" without exposing the rules.
-- marketplace_app is never the owner, so for the service the policies always apply.
alter table availability_rule enable row level security;
create policy availability_rule_staff on availability_rule for all
    using (current_setting('waver.platform_access', true) = 'on')
    with check (current_setting('waver.platform_access', true) = 'on');

alter table reseller_tier enable row level security;
create policy reseller_tier_read on reseller_tier for select
    using (org_id::text = current_setting('waver.org_id', true) or current_setting('waver.platform_access', true) = 'on');
create policy reseller_tier_write on reseller_tier for all
    using (current_setting('waver.platform_access', true) = 'on')
    with check (current_setting('waver.platform_access', true) = 'on');

-- Mirrors AvailabilityRule.allows (marketplace-service-domain): exclude first, then window, then mode.
create function listing_available_to_caller(p_listing_id uuid) returns boolean
    language sql stable security definer set search_path = public, pg_temp as $$
    select current_setting('waver.org_type', true) = 'RESELLER'
       and exists (
           select 1 from availability_rule a
           where a.listing_id = p_listing_id
             and not (current_setting('waver.org_id', true) = any (a.exclude_refs))
             and (a.valid_from is null or a.valid_from <= now())
             and (a.valid_to is null or now() < a.valid_to)
             and (a.mode = 'ALL_RESELLERS'
                  or (a.mode = 'RESELLERS' and current_setting('waver.org_id', true) = any (a.include_refs))
                  or (a.mode = 'TIERS' and exists (select 1 from reseller_tier t
                                                   where t.org_id::text = current_setting('waver.org_id', true)
                                                     and t.tier_key = any (a.include_refs)))))
$$;
revoke all on function listing_available_to_caller(uuid) from public;
grant execute on function listing_available_to_caller(uuid) to marketplace_app;

alter table listing enable row level security;
alter table listing force row level security;
create policy listing_read on listing for select
    using (current_setting('waver.platform_access', true) = 'on'
           or (status = 'PUBLISHED' and listing_available_to_caller(id)));
create policy listing_insert on listing for insert with check (current_setting('waver.platform_access', true) = 'on');
create policy listing_update on listing for update using (current_setting('waver.platform_access', true) = 'on');

alter table listing_version enable row level security;
alter table listing_version force row level security;
create policy listing_version_read on listing_version for select
    using (current_setting('waver.platform_access', true) = 'on'
           or exists (select 1 from listing l where l.current_version_id = listing_version.id));
create policy listing_version_write on listing_version for all
    using (current_setting('waver.platform_access', true) = 'on')
    with check (current_setting('waver.platform_access', true) = 'on');

grant select, insert, update on listing, listing_version, availability_rule, reseller_tier to marketplace_app;
