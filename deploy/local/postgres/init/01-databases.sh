#!/bin/bash
# Local development only. One database per service (ADR-0003) with three roles each (STD-005):
#   <svc>_owner  owns the schema, used only by Flyway migrations
#   <svc>_app    DML only, never owner, no BYPASSRLS -> Row-Level Security applies (ADR-0010)
#   <svc>_cdc    logical replication for Debezium (ADR-0024)
# In Kubernetes environments these credentials are issued dynamically by Vault (ADR-0022).
#
# Idempotent: runs on the first start of the container and again from deploy/local/up.py, so services
# added later get their database on an existing volume.
set -euo pipefail

role_exists() {
  [ "$(psql -tAq --username "$POSTGRES_USER" -c "SELECT 1 FROM pg_roles WHERE rolname = '$1'")" = "1" ]
}

db_exists() {
  [ "$(psql -tAq --username "$POSTGRES_USER" -c "SELECT 1 FROM pg_database WHERE datname = '$1'")" = "1" ]
}

create_role() {
  local role="$1" extra="${2:-}"
  if ! role_exists "$role"; then
    psql -v ON_ERROR_STOP=1 -q --username "$POSTGRES_USER" -c "CREATE ROLE ${role} LOGIN ${extra} PASSWORD '${WAVER_DB_PASSWORD}'"
  fi
}

create_service_db() {
  local svc="$1"            # e.g. tenant
  local db="waver_${svc}"
  create_role "${svc}_owner"
  create_role "${svc}_app"
  create_role "${svc}_cdc" REPLICATION
  if db_exists "$db"; then
    return
  fi
  psql -v ON_ERROR_STOP=1 -q --username "$POSTGRES_USER" <<-SQL
    CREATE DATABASE ${db} OWNER ${svc}_owner;
    REVOKE ALL ON DATABASE ${db} FROM PUBLIC;
    GRANT CONNECT ON DATABASE ${db} TO ${svc}_app, ${svc}_cdc;
SQL
  psql -v ON_ERROR_STOP=1 -q --username "$POSTGRES_USER" --dbname "$db" <<-SQL
    ALTER SCHEMA public OWNER TO ${svc}_owner;
    REVOKE CREATE ON SCHEMA public FROM PUBLIC;
    GRANT USAGE ON SCHEMA public TO ${svc}_app, ${svc}_cdc;
SQL
  echo "created database ${db}"
}

create_service_db tenant
create_service_db audit
create_service_db identity_access
create_service_db reseller
create_service_db customer
create_service_db catalog
create_service_db marketplace

# Platform components with their own databases (Keycloak, Apicurio Registry).
for component in keycloak apicurio; do
  create_role "$component"
  if ! db_exists "$component"; then
    psql -v ON_ERROR_STOP=1 -q --username "$POSTGRES_USER" -c "CREATE DATABASE ${component} OWNER ${component}"
  fi
done
