-- Test-only equivalent of deploy/local/postgres/init/01-databases.sh for one service database.
create role tenant_owner login password 'test';
create role tenant_app login password 'test';
create role tenant_cdc login replication password 'test';
alter schema public owner to tenant_owner;
grant usage on schema public to tenant_app, tenant_cdc;
alter database waver_tenant owner to tenant_owner;
