-- Test-only equivalent of deploy/local/postgres/init/01-databases.sh for one service database.
create role marketplace_owner login password 'test';
create role marketplace_app login password 'test';
create role marketplace_cdc login replication password 'test';
alter schema public owner to marketplace_owner;
grant usage on schema public to marketplace_app, marketplace_cdc;
alter database waver_marketplace owner to marketplace_owner;
