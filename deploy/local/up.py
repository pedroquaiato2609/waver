"""Start and prepare the WAVER local infrastructure.

    python deploy/local/up.py              # init secrets, start containers, databases, Keycloak, Vault, topics, schemas
    python deploy/local/up.py connectors   # register Debezium outbox connectors (after services ran their migrations)
    python deploy/local/up.py down         # stop containers (data kept)

Development only. Schema registration and connector config mirror what the CI pipeline does in real
environments (STD-012): services never register schemas themselves (ADR-0024).
"""
import hashlib
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
COMPOSE = ["docker", "compose", "-f", str(HERE / "docker-compose.yml"), "--env-file", str(HERE / ".env")]

TOPICS = [
    ("waver.tenant.organization.v1", 6),
    ("waver.tenant.organization.v1.dlq", 1),
    ("waver.audit.audit-record.v1", 6),
    ("waver.audit.audit-record.v1.dlq", 1),
    ("waver.reseller.reseller.v1", 6),
    ("waver.reseller.reseller.v1.dlq", 1),
    ("waver.customer.customer.v1", 6),
    ("waver.customer.customer.v1.dlq", 1),
    ("waver.identity.user.v1", 6),
    ("waver.identity.user.v1.dlq", 1),
    ("waver.catalog.product.v1", 6),
    ("waver.catalog.product.v1.dlq", 1),
    ("waver.catalog.price-book.v1", 6),
    ("waver.catalog.price-book.v1.dlq", 1),
    ("waver.marketplace.listing.v1", 6),
    ("waver.marketplace.listing.v1.dlq", 1),
]

# (topic, schema file, record full name). Apicurio TopicRecordIdStrategy coordinates (CONVENTIONS):
#   groupId = Avro namespace, artifactId = "<topic>-<EventName>"
SCHEMAS = [
    ("waver.tenant.organization.v1", "tenant/OrganizationCreated.v1.avsc"),
    ("waver.tenant.organization.v1", "tenant/OrganizationStatusChanged.v1.avsc"),
    ("waver.audit.audit-record.v1", "audit/AuditRecorded.v1.avsc"),
    ("waver.reseller.reseller.v1", "reseller/ResellerLeadRegistered.v1.avsc"),
    ("waver.reseller.reseller.v1", "reseller/ResellerReviewStarted.v1.avsc"),
    ("waver.reseller.reseller.v1", "reseller/ResellerApproved.v1.avsc"),
    ("waver.reseller.reseller.v1", "reseller/ResellerRejected.v1.avsc"),
    ("waver.reseller.reseller.v1", "reseller/ResellerActivated.v1.avsc"),
    ("waver.customer.customer.v1", "customer/CustomerRegistered.v1.avsc"),
    ("waver.identity.user.v1", "identity/UserInvited.v1.avsc"),
    ("waver.catalog.product.v1", "catalog/ProductPublished.v1.avsc"),
    ("waver.catalog.price-book.v1", "catalog/PriceBookActivated.v1.avsc"),
    ("waver.marketplace.listing.v1", "marketplace/ListingPublished.v1.avsc"),
    ("waver.marketplace.listing.v1", "marketplace/AvailabilityChanged.v1.avsc"),
]

# Services with an outbox table relayed by Debezium (ADR-0024).
OUTBOX_SERVICES = ["tenant", "identity_access", "reseller", "customer", "catalog", "marketplace"]


def env() -> dict:
    values = {}
    for line in (HERE / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            values[k] = v
    return values


def http(method: str, url: str, body=None, headers=None, ok=(200, 201, 204)):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode() or "{}"
            return resp.status, json.loads(raw) if raw.strip().startswith(("{", "[")) else raw
    except urllib.error.HTTPError as e:
        if e.code in ok:
            return e.code, {}
        return e.code, e.read().decode()


def wait_for(name: str, url: str, timeout: int = 240) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=5) as resp:
                if resp.status < 500:
                    print(f"  {name} ready")
                    return
        except Exception:
            pass
        time.sleep(3)
    raise SystemExit(f"{name} did not become ready at {url}")


def ensure_databases() -> None:
    # The init script runs by itself only on a new volume; it is idempotent, so run it every time.
    subprocess.run(COMPOSE + ["exec", "-T", "postgres", "bash", "/docker-entrypoint-initdb.d/01-databases.sh"], check=True)
    print("  PostgreSQL: service databases ensured")


def keycloak_admin_token(values: dict) -> str:
    form = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli", "username": "kc-admin",
                                   "password": values["KEYCLOAK_ADMIN_PASSWORD"]}).encode()
    req = urllib.request.Request("http://localhost:8180/realms/master/protocol/openid-connect/token", data=form,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())["access_token"]


def sync_keycloak(values: dict) -> None:
    """Apply realm-as-code changes to an existing realm (--import-realm only runs when the realm is new).

    Adds missing roles and clients, keeps claim mappers and service-account users in line with the template,
    and sets SMTP. Human users are never touched, so passwords and MFA already configured stay as they are.
    """
    realm = json.loads((HERE / "keycloak" / "generated" / "realm-waver.json").read_text(encoding="utf-8"))
    auth = {"Authorization": f"Bearer {keycloak_admin_token(values)}"}
    base = "http://localhost:8180/admin/realms/waver"

    http("PUT", base, {"smtpServer": realm["smtpServer"], "emailTheme": realm["emailTheme"]}, auth)
    for role in realm["roles"]["realm"]:
        status, _ = http("GET", f"{base}/roles/{role['name']}", headers=auth, ok=(200, 404))
        if status == 404:
            http("POST", f"{base}/roles", role, auth)

    client_ids = {}
    for client in realm["clients"]:
        _, found = http("GET", f"{base}/clients?clientId={client['clientId']}", headers=auth)
        if not found:
            http("POST", f"{base}/clients", client, auth)
            _, found = http("GET", f"{base}/clients?clientId={client['clientId']}", headers=auth)
        cid = found[0]["id"]
        client_ids[client["clientId"]] = cid
        update = {k: v for k, v in client.items() if k != "protocolMappers"}
        http("PUT", f"{base}/clients/{cid}", {**found[0], **update}, auth)
        _, existing = http("GET", f"{base}/clients/{cid}/protocol-mappers/models", headers=auth)
        by_name = {m["name"]: m for m in existing or []}
        for mapper in client.get("protocolMappers", []):
            if mapper["name"] in by_name:
                mapper_id = by_name[mapper["name"]]["id"]
                http("PUT", f"{base}/clients/{cid}/protocol-mappers/models/{mapper_id}", {**mapper, "id": mapper_id}, auth)
            else:
                http("POST", f"{base}/clients/{cid}/protocol-mappers/models", mapper, auth)

    _, rm = http("GET", f"{base}/clients?clientId=realm-management", headers=auth)
    for user in realm["users"]:
        if "serviceAccountClientId" not in user:
            continue
        _, account = http("GET", f"{base}/clients/{client_ids[user['serviceAccountClientId']]}/service-account-user", headers=auth)
        if user.get("attributes"):
            _, full = http("GET", f"{base}/users/{account['id']}", headers=auth)
            full["attributes"] = {**(full.get("attributes") or {}), **user["attributes"]}
            http("PUT", f"{base}/users/{account['id']}", full, auth)
        roles = [http("GET", f"{base}/roles/{name}", headers=auth)[1] for name in user.get("realmRoles", [])]
        if roles:
            http("POST", f"{base}/users/{account['id']}/role-mappings/realm", roles, auth)
        names = (user.get("clientRoles") or {}).get("realm-management", [])
        if names:
            client_roles = [http("GET", f"{base}/clients/{rm[0]['id']}/roles/{n}", headers=auth)[1] for n in names]
            http("POST", f"{base}/users/{account['id']}/role-mappings/clients/{rm[0]['id']}", client_roles, auth)
    print(f"  Keycloak: realm waver in sync ({len(realm['clients'])} clients, SMTP to Mailpit)")


def bootstrap_vault(values: dict) -> None:
    token = {"X-Vault-Token": values["VAULT_DEV_ROOT_TOKEN"]}
    base = "http://localhost:8200/v1"
    http("POST", f"{base}/sys/mounts/transit", {"type": "transit"}, token, ok=(200, 204, 400))
    for key in ["tenant-credentials", "tenant-credentials-llm", "tenant-credentials-channel", "tenant-credentials-payment"]:
        http("POST", f"{base}/transit/keys/{key}", {"type": "aes256-gcm96", "derived": True}, token)
    http("POST", f"{base}/transit/keys/outbound-webhooks", {"type": "ed25519"}, token)
    # KV v2 is mounted at secret/ in dev mode. Path convention: secret/waver/<env>/<service> (CONVENTIONS).
    secrets = {
        "control-bff": {"keycloak.client-secret": values["CONTROL_BFF_CLIENT_SECRET"], "redis.password": values["REDIS_CONTROL_BFF_PASSWORD"]},
        "edge-gateway": {"redis.password": values["REDIS_EDGE_GATEWAY_PASSWORD"]},
        "identity-access-service": {"keycloak.client-secret": values["IDENTITY_SVC_CLIENT_SECRET"], "db.password": values["WAVER_DB_PASSWORD"]},
        "tenant-service": {"db.password": values["WAVER_DB_PASSWORD"]},
        "audit-service": {"db.password": values["WAVER_DB_PASSWORD"]},
        "reseller-service": {"db.password": values["WAVER_DB_PASSWORD"], "keycloak.client-secret": values["RESELLER_SVC_CLIENT_SECRET"]},
        "customer-service": {"db.password": values["WAVER_DB_PASSWORD"]},
        "catalog-service": {"db.password": values["WAVER_DB_PASSWORD"]},
        "marketplace-service": {"db.password": values["WAVER_DB_PASSWORD"]},
        "partner-bff": {"keycloak.client-secret": values["PARTNER_BFF_CLIENT_SECRET"], "redis.password": values["REDIS_PARTNER_BFF_PASSWORD"]},
    }
    for service, data in secrets.items():
        http("POST", f"{base}/secret/data/waver/local/{service}", {"data": data}, token)
    print("  Vault: transit keys and secret/waver/local/* written")


def create_topics() -> None:
    for topic, partitions in TOPICS:
        subprocess.run(COMPOSE + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "kafka:29092",
                                  "--create", "--if-not-exists", "--topic", topic, "--partitions", str(partitions),
                                  "--replication-factor", "1"], check=True, stdout=subprocess.DEVNULL)
    print(f"  Kafka: {len(TOPICS)} topics ensured")


def register_schemas() -> None:
    for topic, path in SCHEMAS:
        content = (ROOT / "contracts" / "events" / path).read_text(encoding="utf-8")
        schema = json.loads(content)
        namespace, name = schema["namespace"], schema["name"]
        base = f"http://localhost:8085/apis/registry/v3/groups/{namespace}/artifacts"
        artifact_id = f"{topic}-{name}"
        status, _ = http("GET", f"{base}/{artifact_id}", ok=(200, 404))
        if status == 404:
            http("POST", base, {"artifactId": artifact_id, "artifactType": "AVRO",
                                "firstVersion": {"content": {"content": content, "contentType": "application/json"}}})
            http("POST", f"{base}/{artifact_id}/rules", {"ruleType": "COMPATIBILITY", "config": "BACKWARD_TRANSITIVE"})
        else:
            http("POST", f"{base}/{artifact_id}/versions?ifExists=FIND_OR_CREATE_VERSION",
                 {"content": {"content": content, "contentType": "application/json"}})
    print(f"  Apicurio: {len(SCHEMAS)} schemas registered (BACKWARD_TRANSITIVE)")


def register_connectors(values: dict) -> None:
    for svc in OUTBOX_SERVICES:
        name = f"outbox-{svc.replace('_', '-')}"
        config = {
            "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
            "plugin.name": "pgoutput",
            "database.hostname": "postgres",
            "database.port": "5432",
            "database.user": f"{svc}_cdc",
            "database.password": values["WAVER_DB_PASSWORD"],
            "database.dbname": f"waver_{svc}",
            "topic.prefix": f"waver-cdc-{svc}",
            "slot.name": f"waver_{svc}_outbox",
            "publication.name": "waver_outbox",          # created by the service migration (RFC-0006 §8)
            "snapshot.mode": "no_data",
            "publication.autocreate.mode": "disabled",
            "table.include.list": "public.outbox_event",
            "tombstones.on.delete": "false",
            "key.converter": "org.apache.kafka.connect.storage.StringConverter",
            "value.converter": "org.apache.kafka.connect.converters.ByteArrayConverter",
            "transforms": "outbox",
            "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
            "transforms.outbox.table.field.event.id": "id",
            "transforms.outbox.table.field.event.key": "aggregate_id",
            "transforms.outbox.table.field.event.payload": "payload",
            "transforms.outbox.route.by.field": "topic",
            "transforms.outbox.route.topic.replacement": "${routedByValue}",
            "transforms.outbox.table.fields.additional.placement":
                "content_type:header:content-type,ce_specversion:header:ce_specversion,ce_type:header:ce_type,"
                "ce_source:header:ce_source,ce_subject:header:ce_subject,ce_time:header:ce_time,ce_orgid:header:ce_orgid,"
                "ce_correlationid:header:ce_correlationid,ce_causationid:header:ce_causationid,ce_actorid:header:ce_actorid,"
                "ce_actortype:header:ce_actortype,ce_aggregateversion:header:ce_aggregateversion,ce_traceparent:header:ce_traceparent",
        }
        status, body = http("PUT", f"http://localhost:8083/connectors/{name}/config", config)
        print(f"  Debezium connector {name}: HTTP {status}" + ("" if status in (200, 201) else f" {body}"))


def main() -> None:
    args = sys.argv[1:]
    if args[:1] == ["down"]:
        subprocess.run(COMPOSE + ["down"], check=True)
        return
    if args[:1] == ["connectors"]:
        register_connectors(env())
        return
    acl = HERE / "redis" / "generated" / "users.acl"
    acl_before = hashlib.sha256(acl.read_bytes()).hexdigest() if acl.exists() else None
    subprocess.run([sys.executable, str(HERE / "init.py")], check=True)
    values = env()
    subprocess.run(COMPOSE + ["up", "-d"], check=True)
    if acl_before and acl_before != hashlib.sha256(acl.read_bytes()).hexdigest():
        # New ACL users: Redis reads the ACL file only at start. Signed-in browser sessions are lost.
        subprocess.run(COMPOSE + ["restart", "redis"], check=True)
    print("Waiting for infrastructure...")
    wait_for("Vault", "http://localhost:8200/v1/sys/health")
    wait_for("Keycloak", "http://localhost:8180/realms/waver/.well-known/openid-configuration")
    wait_for("Apicurio", "http://localhost:8085/apis/registry/v3/system/info")
    wait_for("Kafka Connect", "http://localhost:8083/connectors")
    wait_for("Temporal", "http://localhost:8233/")
    ensure_databases()
    sync_keycloak(values)
    bootstrap_vault(values)
    create_topics()
    register_schemas()
    print("Infrastructure ready. Start the services, then run: python deploy/local/up.py connectors")


if __name__ == "__main__":
    main()
