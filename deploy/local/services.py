"""Run the WAVER services locally against the Docker Compose infrastructure.

    python deploy/local/services.py start                      # every service jar in the background (logs in deploy/local/logs)
    python deploy/local/services.py start reseller-service     # only the named services
    python deploy/local/services.py stop [names]
    python deploy/local/services.py status

Build first: ./mvnw install -DskipTests. Development only: the dev Vault root token stands in for
Kubernetes auth (ADR-0022), and services run as plain processes instead of pods.
"""
import os
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
LOGS = HERE / "logs"

SERVICES = [
    ("tenant-service", "services/tenant-service/tenant-service-bootstrap/target/tenant-service-bootstrap-0.1.0-SNAPSHOT.jar", 8091),
    ("audit-service", "services/audit-service/audit-service-bootstrap/target/audit-service-bootstrap-0.1.0-SNAPSHOT.jar", 8092),
    ("identity-access-service", "services/identity-access-service/identity-access-service-bootstrap/target/identity-access-service-bootstrap-0.1.0-SNAPSHOT.jar", 8093),
    ("reseller-service", "services/reseller-service/reseller-service-bootstrap/target/reseller-service-bootstrap-0.1.0-SNAPSHOT.jar", 8094),
    ("customer-service", "services/customer-service/customer-service-bootstrap/target/customer-service-bootstrap-0.1.0-SNAPSHOT.jar", 8095),
    ("catalog-service", "services/catalog-service/catalog-service-bootstrap/target/catalog-service-bootstrap-0.1.0-SNAPSHOT.jar", 8096),
    ("marketplace-service", "services/marketplace-service/marketplace-service-bootstrap/target/marketplace-service-bootstrap-0.1.0-SNAPSHOT.jar", 8097),
    ("control-bff", "services/control-bff/target/control-bff-0.1.0-SNAPSHOT.jar", 8081),
    ("partner-bff", "services/partner-bff/target/partner-bff-0.1.0-SNAPSHOT.jar", 8082),
    ("edge-gateway", "services/edge-gateway/target/edge-gateway-0.1.0-SNAPSHOT.jar", 8080),
]


def env() -> dict:
    values = dict(os.environ)
    for line in (HERE / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            values[k] = v
    values["VAULT_TOKEN"] = values["VAULT_DEV_ROOT_TOKEN"]
    values.setdefault("WAVER_CSP_DEV_CONNECT", "ws://localhost:8080")  # Angular dev-server live reload
    if os.name == "nt":
        # Shells like Git Bash export TMP=/tmp, which Java on Windows cannot use for its NIO loopback sockets.
        temp = str(Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "Temp")
        values["TMP"] = values["TEMP"] = temp
    return values


def java() -> str:
    home = os.environ.get("JAVA_HOME", r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot")
    return str(Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java"))


def healthy(port: int) -> bool:
    try:
        with urllib.request.urlopen(f"http://localhost:{port}/actuator/health", timeout=3) as r:
            return r.status == 200
    except Exception:
        return False


def selected(names: list) -> list:
    """All services, or only the ones named on the command line (e.g. services.py start reseller-service)."""
    return [s for s in SERVICES if not names or s[0] in names]


def start(names: list) -> None:
    LOGS.mkdir(exist_ok=True)
    values = env()
    for name, jar, port in selected(names):
        if healthy(port):
            print(f"  {name} already running on :{port}")
            continue
        log = open(LOGS / f"{name}.log", "w", encoding="utf-8")
        flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
        sock_dir = HERE / "run" / "sock"
        sock_dir.mkdir(parents=True, exist_ok=True)
        # Some Windows security policies block AF_UNIX sockets in %TEMP%; Java NIO selectors need them,
        # so point the JDK at a project folder instead.
        proc = subprocess.Popen([java(), "-Xms64m", "-Xmx320m", "-XX:+UseSerialGC",
                                 f"-Djdk.net.unixdomain.tmpdir={sock_dir}", "-jar", str(ROOT / jar)],
                                cwd=ROOT, env=values, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
        (LOGS / f"{name}.pid").write_text(str(proc.pid))
        print(f"  {name} starting (pid {proc.pid}, :{port})")
    deadline = time.time() + 180
    pending = {name: port for name, _, port in selected(names)}
    while pending and time.time() < deadline:
        for name, port in list(pending.items()):
            if healthy(port):
                print(f"  {name} healthy")
                pending.pop(name)
        time.sleep(3)
    for name in pending:
        print(f"  {name} NOT healthy - see deploy/local/logs/{name}.log")


def stop(names: list) -> None:
    for name, _, _ in selected(names):
        pid_file = LOGS / f"{name}.pid"
        if pid_file.exists():
            pid = int(pid_file.read_text())
            try:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
                else:
                    os.kill(pid, signal.SIGTERM)
                print(f"  {name} stopped")
            except OSError:
                pass
            pid_file.unlink()


def status(names: list) -> None:
    for name, _, port in selected(names):
        print(f"  {name:<26} :{port}  {'UP' if healthy(port) else 'down'}")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "status"
    {"start": start, "stop": stop, "status": status}[command](sys.argv[2:])
