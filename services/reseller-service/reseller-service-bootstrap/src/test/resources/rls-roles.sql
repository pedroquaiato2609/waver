-- Test-only equivalent of deploy/local/postgres/init/01-databases.sh for one service database.
create role reseller_owner login password 'test';
create role reseller_app login password 'test';
create role reseller_cdc login replication password 'test';
alter schema public owner to reseller_owner;
grant usage on schema public to reseller_app, reseller_cdc;
alter database waver_reseller owner to reseller_owner;
