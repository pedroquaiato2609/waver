-- Test-only equivalent of deploy/local/postgres/init/01-databases.sh for one service database.
create role customer_owner login password 'test';
create role customer_app login password 'test';
create role customer_cdc login replication password 'test';
alter schema public owner to customer_owner;
grant usage on schema public to customer_app, customer_cdc;
alter database waver_customer owner to customer_owner;
