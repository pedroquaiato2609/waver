-- catalog-service (RFC-0017 §4): Product, SKU, PriceBook with its entries, PricingRule, DiscountPolicy.
-- The catalog belongs to WAVER, so the tables have no org_id. Row-Level Security (ADR-0010):
--   WAVER staff (waver.platform_access = 'on') see and write everything;
--   a reseller reads published products, the active and retired price books of the global scope, of its tier
--   and of itself, and the pricing rules and discount policies of the same scopes;
--   customers read nothing (RFC-0017 §1: workspace-bff has no catalog client).
-- Amounts: numeric(19,4) in the book currency (CONVENTIONS). Percentages: numeric(9,4).

-- Projection of the reseller tier (reseller-service, ResellerTierChanged). Empty until tiers exist there.
create table reseller_tier (
    org_id      uuid        primary key,
    tier_key    text        not null,
    updated_at  timestamptz not null default now()
);

create table product (
    id             uuid        primary key,
    product_key    text        not null unique check (product_key ~ '^[a-z][a-z0-9-]{2,62}$'),
    name           text        not null check (length(name) between 2 and 120),
    item_type      text        not null check (item_type in ('PRODUCT', 'TOOL', 'CONNECTOR', 'SOLUTION_PACK', 'CHANNEL', 'CAPACITY', 'SERVICE')),
    technical_ref  text,
    status         text        not null check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    published_at   timestamptz,
    version        bigint      not null default 0,
    created_at     timestamptz not null default now(),
    created_by     text,
    updated_at     timestamptz not null default now(),
    updated_by     text
);
create index product_list on product (status, id desc);

create table sku (
    id              uuid        primary key,
    product_id      uuid        not null references product (id),
    sku_code        text        not null unique,
    unit            text        not null check (unit in ('WORKSPACE', 'CONNECTION', 'NUMBER', 'SEAT', 'RUN', 'TOKEN', 'HOUR')),
    charge_type     text        not null check (charge_type in ('RECURRING', 'USAGE', 'ONE_TIME')),
    billing_period  text        check (billing_period in ('MONTHLY', 'YEARLY')),
    meter_key       text,
    created_at      timestamptz not null default now(),
    created_by      text
);
create index sku_product on sku (product_id);

create table price_book (
    id            uuid        primary key,
    name          text        not null check (length(name) between 2 and 120),
    currency      char(3)     not null,
    scope         text        not null check (scope in ('GLOBAL', 'TIER', 'RESELLER')),
    scope_ref     text,
    valid_from    timestamptz not null,
    valid_to      timestamptz,
    status        text        not null check (status in ('DRAFT', 'ACTIVE', 'RETIRED')),
    activated_at  timestamptz,
    activated_by  text,
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    created_by    text,
    updated_at    timestamptz not null default now(),
    updated_by    text,
    check ((scope = 'GLOBAL') = (scope_ref is null)),
    check (valid_to is null or valid_to > valid_from)
);
-- One active book per scope, reference and currency; activating a new one retires the old one first.
create unique index price_book_one_active on price_book (scope, coalesce(scope_ref, ''), currency) where status = 'ACTIVE';
create index price_book_list on price_book (status, id desc);

create table price_entry (
    price_book_id     uuid          not null references price_book (id),
    sku_id            uuid          not null references sku (id),
    charge_model      text          not null check (charge_model in ('FLAT', 'PER_UNIT')),
    wholesale_amount  numeric(19,4) not null check (wholesale_amount >= 0),
    pricing_model     text          not null check (pricing_model in ('WHOLESALE_PLUS_MARGIN', 'SUGGESTED_RETAIL', 'FIXED_RETAIL')),
    suggested_amount  numeric(19,4),
    fixed_amount      numeric(19,4),
    floor_amount      numeric(19,4),
    ceiling_amount    numeric(19,4),
    primary key (price_book_id, sku_id)
);
create index price_entry_sku on price_entry (sku_id);

create table pricing_rule (
    id              uuid         primary key,
    scope           text         not null check (scope in ('GLOBAL', 'TIER', 'RESELLER')),
    scope_ref       text,
    sku_id          uuid         references sku (id),
    min_margin_pct  numeric(9,4) not null check (min_margin_pct >= 0),
    max_margin_pct  numeric(9,4) check (max_margin_pct is null or max_margin_pct >= min_margin_pct),
    valid_from      timestamptz  not null,
    valid_to        timestamptz,
    created_at      timestamptz  not null default now(),
    created_by      text,
    check ((scope = 'GLOBAL') = (scope_ref is null))
);

create table discount_policy (
    id                                 uuid         primary key,
    scope                              text         not null check (scope in ('GLOBAL', 'TIER', 'RESELLER')),
    scope_ref                          text,
    sku_id                             uuid         references sku (id),
    max_discount_without_approval_pct  numeric(9,4) not null check (max_discount_without_approval_pct between 0 and 100),
    approval_policy_key                text         not null,
    valid_from                         timestamptz  not null,
    valid_to                           timestamptz,
    created_at                         timestamptz  not null default now(),
    created_by                         text,
    check ((scope = 'GLOBAL') = (scope_ref is null))
);

-- ---- Row-Level Security ----

alter table reseller_tier enable row level security;
alter table reseller_tier force row level security;
create policy reseller_tier_read on reseller_tier for select
    using (org_id::text = current_setting('waver.org_id', true) or current_setting('waver.platform_access', true) = 'on');
create policy reseller_tier_write on reseller_tier for all
    using (current_setting('waver.platform_access', true) = 'on')
    with check (current_setting('waver.platform_access', true) = 'on');

alter table product enable row level security;
alter table product force row level security;
create policy product_read on product for select
    using (current_setting('waver.platform_access', true) = 'on'
           or (current_setting('waver.org_type', true) = 'RESELLER' and status = 'PUBLISHED'));
create policy product_insert on product for insert with check (current_setting('waver.platform_access', true) = 'on');
create policy product_update on product for update using (current_setting('waver.platform_access', true) = 'on');

alter table sku enable row level security;
alter table sku force row level security;
create policy sku_read on sku for select using (exists (select 1 from product p where p.id = product_id));
create policy sku_insert on sku for insert with check (current_setting('waver.platform_access', true) = 'on');

-- A scope matches the caller: global, its own reseller ID, or its tier (looked up in its own projection row).
create function caller_in_scope(p_scope text, p_scope_ref text) returns boolean
    language sql stable as $$
    select current_setting('waver.org_type', true) = 'RESELLER'
       and (p_scope = 'GLOBAL'
            or (p_scope = 'RESELLER' and p_scope_ref = current_setting('waver.org_id', true))
            or (p_scope = 'TIER' and p_scope_ref = (select t.tier_key from reseller_tier t
                                                    where t.org_id::text = current_setting('waver.org_id', true))))
$$;

alter table price_book enable row level security;
alter table price_book force row level security;
create policy price_book_read on price_book for select
    using (current_setting('waver.platform_access', true) = 'on'
           or (status in ('ACTIVE', 'RETIRED') and caller_in_scope(scope, scope_ref)));
create policy price_book_insert on price_book for insert with check (current_setting('waver.platform_access', true) = 'on');
create policy price_book_update on price_book for update using (current_setting('waver.platform_access', true) = 'on');

alter table price_entry enable row level security;
alter table price_entry force row level security;
create policy price_entry_read on price_entry for select using (exists (select 1 from price_book b where b.id = price_book_id));
create policy price_entry_write on price_entry for all
    using (current_setting('waver.platform_access', true) = 'on')
    with check (current_setting('waver.platform_access', true) = 'on');

alter table pricing_rule enable row level security;
alter table pricing_rule force row level security;
create policy pricing_rule_read on pricing_rule for select
    using (current_setting('waver.platform_access', true) = 'on' or caller_in_scope(scope, scope_ref));
create policy pricing_rule_insert on pricing_rule for insert with check (current_setting('waver.platform_access', true) = 'on');

alter table discount_policy enable row level security;
alter table discount_policy force row level security;
create policy discount_policy_read on discount_policy for select
    using (current_setting('waver.platform_access', true) = 'on' or caller_in_scope(scope, scope_ref));
create policy discount_policy_insert on discount_policy for insert with check (current_setting('waver.platform_access', true) = 'on');

grant select, insert, update on reseller_tier, product, sku, price_book, pricing_rule, discount_policy to catalog_app;
grant select, insert, update, delete on price_entry to catalog_app;
grant execute on function caller_in_scope(text, text) to catalog_app;
