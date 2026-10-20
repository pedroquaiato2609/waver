"""Print the current TOTP code of a local test user (development only).

    python deploy/local/totp.py            # admin@waver.local (WAVER_ADMIN_TOTP_SECRET)
    python deploy/local/totp.py reseller   # the first invited Reseller Owner (WAVER_RESELLER_OWNER_TOTP_SECRET)

The secret is stored in deploy/local/.env (WAVER_ADMIN_TOTP_SECRET) after the first sign-in, so you can also add
it to an authenticator app (Microsoft/Google Authenticator, FreeOTP) as a manual key for admin@waver.local.
"""
import base64
import hashlib
import hmac
import struct
import sys
import time
from pathlib import Path

ENV = Path(__file__).resolve().parent / ".env"


def totp(secret: str, period: int = 30, digits: int = 6, at: float | None = None) -> str:
    key = base64.b32decode(secret.replace(" ", "").upper() + "=" * (-len(secret.replace(" ", "")) % 8))
    counter = int((at or time.time()) // period)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(code).zfill(digits)


def main() -> None:
    values = dict(l.split("=", 1) for l in ENV.read_text(encoding="utf-8").splitlines() if "=" in l and not l.startswith("#"))
    key = {"reseller": "WAVER_RESELLER_OWNER_TOTP_SECRET"}.get(sys.argv[1] if len(sys.argv) > 1 else "", "WAVER_ADMIN_TOTP_SECRET")
    secret = values.get(key)
    if not secret:
        raise SystemExit(f"{key} not set in deploy/local/.env")
    print(totp(secret))


if __name__ == "__main__":
    main()
