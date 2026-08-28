"""机器码：跨语言一致算法（见 powersoftware-license-sdk/docs/授权SDK规范_v3.md）

fingerprint 优先级：
0. 本地持久化 UUID（首次计算后写入文件，后续直接读取，跨重启稳定）
1. 硬件序列号（BIOS SN，重装系统不变）
2. 系统机器 ID（MachineGuid / machine-id / IOPlatformUUID）
3. 硬件信号组合（MAC + 平台 + 架构，改名/重装系统不变）
4. 兜底 hostname | os | arch（仅当以上全部不可用时）

持久化策略：
- 写入多个位置（PS_LICENSE_HOME > ~/.powersoftware），任一文件在即可复用
- 文件内容带校验和（uuid:checksum），防止损坏导致重新计算
"""

from __future__ import annotations

import base64
import hashlib
import os
import platform
import re
import socket
import subprocess
import sys


def machine_code() -> str:
    raw = _resolve_raw()
    digest = hashlib.sha256(raw.encode("utf-8")).digest()
    b64 = base64.urlsafe_b64encode(digest).decode("utf-8").rstrip("=")
    return "M" + b64[:32]


def _resolve_raw() -> str:
    """优先读取持久化 UUID；未命中则计算 fingerprint 并回写。"""
    persisted = _read_persisted_uuid()
    if persisted:
        return persisted
    fp = _get_fingerprint()
    hex_digest = hashlib.sha256(fp.encode("utf-8")).hexdigest()
    uid = f"{hex_digest[:8]}-{hex_digest[8:12]}-{hex_digest[12:16]}-{hex_digest[16:20]}-{hex_digest[20:32]}"
    _write_persisted_uuid(uid)
    return uid


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

    # 3. 硬件信号组合（MAC + CPU + 内存 + 平台 + 架构）
    composite = _composite_fingerprint()
    if composite:
        return composite

    # 4. 兜底
    return "|".join([socket.gethostname(), sys.platform, platform.machine()]).lower()


def _composite_fingerprint() -> str:
    """组合多个硬件信号，单一因素变化不会导致整体指纹变化。
    仅使用跨语言采集一致的信号（MAC + 平台 + 架构），不含 CPU/内存（各语言取值不同）。
    """
    parts: list[str] = []
    # 非随机 MAC 地址（统一用命令行采集，确保跨语言一致）
    macs = _get_stable_mac_addresses()
    if macs:
        parts.append("mac:" + ",".join(sorted(macs)))
    parts.append("plat:" + sys.platform)
    parts.append("arch:" + platform.machine())
    return "|".join(parts).lower() if len(parts) > 2 else ""


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


# ---- 持久化 UUID ----

_FILE_NAME = ".machine-id"


def _persist_paths() -> list[str]:
    """返回所有持久化位置（按优先级排序）。

    优先级：
    1. $PS_LICENSE_HOME/.machine-id  （软件指定，如安装目录）
    2. ~/.powersoftware/.machine-id  （用户目录兜底）
    """
    paths = []
    env_home = os.environ.get("PS_LICENSE_HOME")
    if env_home:
        paths.append(os.path.join(env_home, _FILE_NAME))
    paths.append(os.path.join(os.path.expanduser("~"), ".powersoftware", _FILE_NAME))
    return paths


def _checksum(uid: str) -> str:
    """简单校验和，防止文件损坏导致重新计算。"""
    return hashlib.md5(uid.encode("utf-8")).hexdigest()[:8]


def _read_persisted_uuid() -> str | None:
    """从所有持久化位置中读取第一个有效的 UUID。

    文件格式: <uuid>:<checksum>，校验不通过视为无效。
    """
    for path in _persist_paths():
        try:
            with open(path, "r", encoding="utf-8") as f:
                content = f.read().strip()
            if ":" in content:
                uid, chk = content.rsplit(":", 1)
                if len(uid) >= 8 and _checksum(uid) == chk:
                    return uid
            elif content and len(content) >= 8:
                # 兼容旧版无校验和的文件
                return content
        except Exception:
            continue
    return None


def _write_persisted_uuid(uid: str) -> None:
    """将 UUID 写入所有持久化位置（多位置备份，任一文件在即可复用）。"""
    content = f"{uid}:{_checksum(uid)}"
    for path in _persist_paths():
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        except Exception:
            pass


# ---- MAC 地址采集 ----

def _get_stable_mac_addresses() -> list[str]:
    """采集非随机、非回环的 MAC 地址列表。
    统一用命令行采集，确保跨语言一致。
    Windows: getmac /v（MAC 格式不受系统语言影响）
    Mac/Linux: ifconfig（输出通常为英文）
    """
    macs: list[str] = []
    if sys.platform == "win32":
        out = _exec(["getmac", "/v"], timeout=5)
        if out:
            for m in re.finditer(r"([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}", out):
                hex_mac = m.group(0).replace("-", ":").lower()
                if _is_stable_mac(hex_mac):
                    macs.append(hex_mac)
    else:
        out = _exec(["ifconfig"], timeout=5)
        if out:
            for m in re.finditer(r"ether\s+([0-9a-fA-F:]{17})", out):
                hex_mac = m.group(1).lower()
                if _is_stable_mac(hex_mac):
                    macs.append(hex_mac)
    return list(dict.fromkeys(macs))  # 去重且保持顺序


def _is_stable_mac(mac: str) -> bool:
    """过滤回环、全零、随机/本地位设置的 MAC。"""
    s = mac.lower().replace(":", "").replace("-", "")
    if len(s) < 12 or s == "0" * 12:
        return False
    first_byte = int(s[:2], 16)
    # 回环
    if first_byte == 0x02:
        return False
    # 本地管理位 (I/G=0, U/L): 第二低位为 1 表示随机/本地分配
    if first_byte & 0x02:
        return False
    return True


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
