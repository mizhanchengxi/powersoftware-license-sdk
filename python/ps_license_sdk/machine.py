"""机器码：跨语言一致算法（见 ps-help/v3/doc/授权SDK规范_v3.md）"""

import base64
import hashlib
import platform
import socket
import sys
import uuid


def _primary_mac() -> str:
    node = uuid.getnode()
    # 清除多播位，保证 12 位十六进制
    node &= 0xFFFFFFFFFFFF
    return "%012x" % node


def machine_code() -> str:
    parts = [socket.gethostname(), sys.platform, platform.machine(), _primary_mac()]
    fingerprint = "|".join(parts).lower()
    digest = hashlib.sha256(fingerprint.encode("utf-8")).digest()
    b64 = base64.urlsafe_b64encode(digest).decode("utf-8").rstrip("=")
    return "M" + b64[:32]
