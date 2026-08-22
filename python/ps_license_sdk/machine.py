"""机器码：跨语言一致算法（见 powersoftware-license-sdk/docs/授权SDK规范_v3.md）

fingerprint 优先级：
1. 硬件序列号（BIOS SN，重装系统不变）
2. 系统机器 ID（MachineGuid / machine-id / IOPlatformUUID）
3. 兜底 hostname | os | arch
"""

from __future__ import annotations

import base64
import hashlib
import platform
import re
import socket
import subprocess
import sys


def machine_code() -> str:
    fingerprint = _get_fingerprint()
    digest = hashlib.sha256(fingerprint.encode("utf-8")).digest()
    b64 = base64.urlsafe_b64encode(digest).decode("utf-8").rstrip("=")
    return "M" + b64[:32]


def _get_fingerprint() -> str:
    is_win = sys.platform == "win32"
    is_mac = sys.platform == "darwin"
    is_linux = sys.platform.startswith("linux")

    # 1. 硬件序列号
    hw = None
    if is_win:
        hw = _read_windows_bios_serial()
    elif is_mac:
        hw = _read_mac_serial()
    elif is_linux:
        hw = _read_linux_hardware_serial()
    if _is_meaningful(hw):
        return hw.lower()

    # 2. 系统机器 ID
    sys_id = None
    if is_win:
        sys_id = _read_windows_machine_guid()
    elif is_mac:
        sys_id = _read_mac_platform_uuid()
    elif is_linux:
        sys_id = _read_linux_machine_id()
    if _is_meaningful(sys_id):
        return sys_id.lower()

    # 3. 兜底
    return "|".join([socket.gethostname(), sys.platform, platform.machine()]).lower()


def _is_meaningful(value) -> bool:
    """过滤厂商占位值"""
    if not value:
        return False
    s = value.strip().lower()
    if not s or s in ("none", "0", "default"):
        return False
    if "to be filled" in s or "o.e.m" in s:
        return False
    if "system serial" in s or "not available" in s or "not specified" in s:
        return False
    return True


# ---- Windows ----

def _read_windows_bios_serial() -> str | None:
    """BIOS 序列号（普通用户可读，无需管理员）"""
    # 尝试 wmic（快，新版 Windows 可能已移除）
    out = _exec(["wmic", "bios", "get", "serialnumber"], timeout=5)
    if out:
        for line in out.splitlines():
            t = line.strip()
            if t and t.lower() != "serialnumber" and _is_meaningful(t):
                return t
    # 降级 PowerShell
    out = _exec(
        ["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_BIOS).SerialNumber"],
        timeout=10,
    )
    if out and _is_meaningful(out.strip()):
        return out.strip()
    return None


def _read_windows_machine_guid() -> str | None:
    """注册表 MachineGuid（普通用户可读）"""
    out = _exec(
        ["reg", "query", r"HKLM\SOFTWARE\Microsoft\Cryptography", "/v", "MachineGuid"],
        timeout=5,
    )
    if out:
        m = re.search(r"MachineGuid\s+REG_SZ\s+(.+)", out)
        if m:
            return m.group(1).strip()
    return None


# ---- macOS ----

def _read_mac_serial() -> str | None:
    out = _exec(["system_profiler", "SPHardwareDataType"], timeout=10)
    if out:
        m = re.search(r"Serial Number.*?:\s*(.+)", out)
        if m:
            return m.group(1).strip()
    return None


def _read_mac_platform_uuid() -> str | None:
    out = _exec(["ioreg", "-d2", "-c", "IOPlatformPlatformDevice"], timeout=10)
    if out:
        m = re.search(r'"IOPlatformUUID"\s*=\s*"([^"]+)"', out)
        if m:
            return m.group(1).strip()
    return None


# ---- Linux ----

def _read_linux_hardware_serial() -> str | None:
    """硬件序列号（需要 root，非 root 通常拿不到）"""
    for path in ("/sys/class/dmi/id/product_serial", "/sys/class/dmi/id/board_serial"):
        content = _read_file(path)
        if _is_meaningful(content):
            return content.strip()
    out = _exec(["dmidecode", "-s", "system-serial-number"], timeout=5)
    if out and _is_meaningful(out.strip()):
        return out.strip()
    return None


def _read_linux_machine_id() -> str | None:
    for path in ("/etc/machine-id", "/var/lib/dbus/machine-id"):
        content = _read_file(path)
        if content and content.strip():
            return content.strip()
    return None


# ---- 通用工具 ----

def _exec(cmd: list[str], timeout: int = 5) -> str | None:
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.stdout
    except Exception:
        return None


def _read_file(path: str) -> str | None:
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return None
