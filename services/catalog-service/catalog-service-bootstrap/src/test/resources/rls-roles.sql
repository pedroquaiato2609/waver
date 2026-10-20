-- Test-only equivalent of deploy/local/postgres/init/01-databases.sh for one service database.
create role catalog_owner login password 'test';
create role catalog_app login password 'test';
create role catalog_cdc login replication password 'test';
alter schema public owner to catalog_owner;
grant usage on schema public to catalog_app, catalog_cdc;
alter database waver_catalog owner to catalog_owner;
