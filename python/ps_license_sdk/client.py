"""PowerSoftware 授权客户端（激活/校验/解绑/软件内发码升级/试用领取）"""

import base64
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.parse
import urllib.request

from .machine import machine_code

DEFAULT_BASE_URL = "https://www.powersoftware.app/frontApi"
VERIFY_CACHE_TTL_MS = 60 * 1000


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("utf-8").rstrip("=")


def sign(api_secret: str, params: dict) -> str:
    """HMAC 签名：productId \\n machineCode \\n edition \\n expiryDays \\n clientOrderId \\n licenseCode \\n timestamp"""
    payload = "\n".join(
        [
            str(params.get("productId", "")),
            str(params.get("machineCode", "")),
            str(params.get("edition", "")),
            str(params.get("expiryDays", 0)),
            str(params.get("clientOrderId", "")),
            str(params.get("licenseCode", "")),
            str(params.get("timestamp", "")),
        ]
    )
    return _b64url(hmac.new(api_secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).digest())


class LicenseError(RuntimeError):
    def __init__(self, message: str, error_code: str = "REQUEST_FAILED"):
        super().__init__(message)
        self.error_code = error_code


class LicenseClient:
    def __init__(self, base_url: str = DEFAULT_BASE_URL, api_secret: str = "", product_id: int = None, cache_ttl_ms: int = VERIFY_CACHE_TTL_MS):
        self.base_url = base_url.rstrip("/")
        self.api_secret = api_secret
        self.product_id = product_id
        self.cache_ttl_ms = cache_ttl_ms
        self._verify_cache = None

    def request(self, path: str, body: dict = None, signed: bool = False, timeout: float = 15.0):
        payload = dict(body or {})
        if signed:
            payload["timestamp"] = int(time.time() * 1000)
            payload["signature"] = sign(self.api_secret, payload)
        req = urllib.request.Request(
            self.base_url + path,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            try:
                data = json.loads(exc.read().decode("utf-8"))
            except Exception:
                data = {}
        except Exception as exc:  # 网络/超时
            raise LicenseError(str(exc), "NETWORK_ERROR") from exc
        if not data.get("success"):
            raise LicenseError(data.get("tip") or "request failed", data.get("code") or "REQUEST_FAILED")
        return data.get("content")

    def activate(self, license_code: str, machine_code_value: str = None):
        return self.request("/license/activate", {"licenseCode": license_code, "machineCode": machine_code_value or machine_code()})

    def verify(self, license_code: str, machine_code_value: str = None, activation_token: str = ""):
        return self.request(
            "/license/verify",
            {"licenseCode": license_code, "machineCode": machine_code_value or machine_code(), "activationToken": activation_token},
        )

    def deactivate(self, license_code: str, machine_code_value: str = None):
        return self.request("/license/deactivate", {"licenseCode": license_code, "machineCode": machine_code_value or machine_code()})

    def claim_trial(self, machine_code_value: str = None):
        if not self.product_id:
            raise LicenseError("productId required", "PRODUCT_ID_REQUIRED")
        return self.request("/license/trial/claim", {"productId": self.product_id, "machineCode": machine_code_value or machine_code()})

    def generate_for_software(self, machine_code_value: str, edition: str = "", expiry_days: int = 0, client_order_id: str = ""):
        if not self.product_id:
            raise LicenseError("productId required", "PRODUCT_ID_REQUIRED")
        return self.request(
            "/license/software/generate",
            {
                "productId": self.product_id,
                "machineCode": machine_code_value or machine_code(),
                "edition": edition,
                "expiryDays": expiry_days,
                "clientOrderId": client_order_id,
            },
            signed=True,
        )

    def upgrade_for_software(self, license_code: str, edition: str, machine_code_value: str = None, expiry_days: int = 0, client_order_id: str = ""):
        if not self.product_id:
            raise LicenseError("productId required", "PRODUCT_ID_REQUIRED")
        return self.request(
            "/license/software/upgrade",
            {
                "productId": self.product_id,
                "licenseCode": license_code,
                "machineCode": machine_code_value or machine_code(),
                "edition": edition,
                "expiryDays": expiry_days,
                "clientOrderId": client_order_id,
            },
            signed=True,
        )

    def verify_cached(self, license_code: str, machine_code_value: str = None, activation_token: str = ""):
        now = int(time.time() * 1000)
        cache = self._verify_cache
        if cache and now - cache["at"] < self.cache_ttl_ms:
            return cache["data"]
        data = self.verify(license_code, machine_code_value, activation_token)
        self._verify_cache = {"at": now, "data": data}
        return data

    def purchase_url(self, machine_code_value: str = None, base: str = "https://www.powersoftware.app") -> str:
        if not self.product_id:
            raise LicenseError("productId required", "PRODUCT_ID_REQUIRED")
        params = urllib.parse.urlencode({"productId": self.product_id, "machineCode": machine_code_value or machine_code()})
        return f"{base}/product/license/purchase?{params}"
