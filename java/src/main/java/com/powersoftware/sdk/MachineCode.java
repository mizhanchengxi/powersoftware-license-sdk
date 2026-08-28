package com.powersoftware.sdk;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 机器码：跨语言一致算法（见 powersoftware-license-sdk/docs/授权SDK规范_v3.md）
 * <p>
 * fingerprint 优先级：
 * 0. 本地持久化 UUID（首次计算后写入文件，后续直接读取，跨重启稳定）
 * 1. 硬件序列号（BIOS SN，重装系统不变）
 * 2. 系统机器 ID（MachineGuid / machine-id / IOPlatformUUID，安装时生成）
 * 3. 硬件信号组合（MAC + CPU + 内存 + 平台，改名/重装系统不变）
 * 4. 兜底 hostname | os | arch（仅当以上全部不可用时）
 */
public final class MachineCode {

    private MachineCode() {
    }

    public static String get() {
        String raw = resolveRaw();
        byte[] digest = sha256(raw.getBytes(StandardCharsets.UTF_8));
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return "M" + b64.substring(0, Math.min(32, b64.length()));
    }

    /** 优先读取持久化 UUID；未命中则计算 fingerprint 并回写。 */
    private static String resolveRaw() {
        String persisted = readPersistedUuid();
        if (persisted != null) return persisted;
        String fp = getFingerprint();
        String hex = hexString(sha256(fp.getBytes(StandardCharsets.UTF_8)));
        String uid = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
        writePersistedUuid(uid);
        return uid;
    }

    private static String getFingerprint() {
        String os = System.getProperty("os.name", "").toLowerCase();

        // 1. 硬件序列号
        String hw = null;
        if (os.contains("win")) {
            hw = readWindowsBiosSerial();
        } else if (os.contains("mac") || os.contains("darwin")) {
            hw = readMacSerial();
        } else if (os.contains("linux") || os.contains("nix")) {
            hw = readLinuxHardwareSerial();
        }
        if (isMeaningful(hw)) {
            return hw.toLowerCase();
        }

        // 2. 系统机器 ID
        String sysId = null;
        if (os.contains("win")) {
            sysId = readWindowsMachineGuid();
        } else if (os.contains("mac") || os.contains("darwin")) {
            sysId = readMacPlatformUUID();
        } else if (os.contains("linux") || os.contains("nix")) {
            sysId = readLinuxMachineId();
        }
        if (isMeaningful(sysId)) {
            return sysId.toLowerCase();
        }

        // 3. 硬件信号组合（MAC + CPU + 内存 + 平台 + 架构）
        String composite = compositeFingerprint();
        if (!composite.isEmpty()) {
            return composite;
        }

        // 4. 兜底
        return String.join("|", hostname(), os, System.getProperty("os.arch", "")).toLowerCase();
    }

    /** 组合多个硬件信号，单一因素变化不会导致整体指纹变化。
     *  仅使用跨语言采集一致的信号（MAC + 平台 + 架构），不含 CPU/内存（各语言取值不同）。 */
    private static String compositeFingerprint() {
        List<String> parts = new ArrayList<>();
        List<String> macs = getStableMacAddresses();
        if (!macs.isEmpty()) {
            Collections.sort(macs);
            parts.add("mac:" + String.join(",", macs));
        }
        String osName = System.getProperty("os.name", "").toLowerCase();
        String plat = osName.contains("win") ? "win32" : (osName.contains("mac") || osName.contains("darwin")) ? "darwin" : "linux";
        parts.add("plat:" + plat);
        parts.add("arch:" + System.getProperty("os.arch", ""));
        return parts.size() > 2 ? String.join("|", parts).toLowerCase() : "";
    }

    /** 过滤 "To be filled by O.E.M." / "None" / "0" 等厂商占位值 */
    private static boolean isMeaningful(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String s = value.trim().toLowerCase();
        if (s.isEmpty() || s.equals("none") || s.equals("0") || s.equals("default")) {
            return false;
        }
        if (s.contains("to be filled") || s.contains("o.e.m")) {
            return false;
        }
        if (s.contains("system serial") || s.contains("not available") || s.contains("not specified")) {
            return false;
        }
        return true;
    }

    // ---- Windows ----

    /** Windows BIOS 序列号（普通用户可读，无需管理员权限） */
    private static String readWindowsBiosSerial() {
        // 尝试 wmic（快，新版 Windows 可能已移除）
        String out = exec(new String[]{"wmic", "bios", "get", "serialnumber"}, 5);
        if (out != null) {
            for (String line : out.split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.equalsIgnoreCase("SerialNumber") && isMeaningful(t)) {
                    return t;
                }
            }
        }
        // 降级 PowerShell
        out = exec(new String[]{"powershell", "-NoProfile", "-Command",
                "(Get-CimInstance Win32_BIOS).SerialNumber"}, 10);
        if (out != null) {
            String t = out.trim();
            if (isMeaningful(t)) {
                return t;
            }
        }
        return null;
    }

    /** Windows MachineGuid（注册表，普通用户可读） */
    private static String readWindowsMachineGuid() {
        String out = exec(new String[]{"reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid"}, 5);
        if (out != null) {
            Matcher m = Pattern.compile("MachineGuid\\s+REG_SZ\\s+(.+)").matcher(out);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }

    // ---- macOS ----

    /** macOS 硬件序列号（无需 root） */
    private static String readMacSerial() {
        String out = exec(new String[]{"system_profiler", "SPHardwareDataType"}, 10);
        if (out != null) {
            Matcher m = Pattern.compile("Serial Number.*?:\\s*(.+)").matcher(out);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }

    /** macOS IOPlatformUUID（无需 root） */
    private static String readMacPlatformUUID() {
        String out = exec(new String[]{"ioreg", "-d2", "-c", "IOPlatformPlatformDevice"}, 10);
        if (out != null) {
            Matcher m = Pattern.compile("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"").matcher(out);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }

    // ---- Linux ----

    /** Linux 硬件序列号（需要 root，非 root 通常拿不到） */
    private static String readLinuxHardwareSerial() {
        String[] paths = {"/sys/class/dmi/id/product_serial", "/sys/class/dmi/id/board_serial"};
        for (String path : paths) {
            String content = readFile(path);
            if (isMeaningful(content)) {
                return content.trim();
            }
        }
        // dmidecode 需要 root
        String out = exec(new String[]{"dmidecode", "-s", "system-serial-number"}, 5);
        if (out != null && isMeaningful(out)) {
            return out.trim();
        }
        return null;
    }

    /** Linux /etc/machine-id（所有用户可读） */
    private static String readLinuxMachineId() {
        String[] paths = {"/etc/machine-id", "/var/lib/dbus/machine-id"};
        for (String path : paths) {
            String content = readFile(path);
            if (content != null && !content.isEmpty()) {
                return content.trim();
            }
        }
        return null;
    }

    // ---- 通用工具 ----

    private static String exec(String[] cmd, int timeoutSec) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFile(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ---- 持久化 UUID ----

    private static String persistPath() {
        String base = System.getenv("PS_LICENSE_HOME");
        if (base == null || base.isEmpty()) {
            base = System.getProperty("user.home") + java.io.File.separator + ".powersoftware";
        }
        return base + java.io.File.separator + ".machine-id";
    }

    private static String readPersistedUuid() {
        try {
            String val = new String(Files.readAllBytes(Paths.get(persistPath())), StandardCharsets.UTF_8).trim();
            if (val.length() >= 8) return val;
        } catch (Exception e) { /* 文件不存在或读取失败 */ }
        return null;
    }

    private static void writePersistedUuid(String uid) {
        try {
            java.nio.file.Path p = Paths.get(persistPath());
            Files.createDirectories(p.getParent());
            Files.write(p, uid.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { /* 写入失败静默忽略 */ }
    }

    // ---- MAC 地址采集 ----

    /** 采集非随机、非回环的 MAC 地址列表。
     *  统一用命令行采集，确保跨语言一致。
     *  Windows: getmac /v（MAC 格式不受系统语言影响）
     *  Mac/Linux: ifconfig（输出通常为英文） */
    private static List<String> getStableMacAddresses() {
        List<String> macs = new ArrayList<>();
        String osName = System.getProperty("os.name", "").toLowerCase();
        String out = null;
        if (osName.contains("win")) {
            out = exec(new String[]{"getmac", "/v"}, 5);
            if (out != null) {
                Matcher m = Pattern.compile("([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}").matcher(out);
                while (m.find()) {
                    String mac = m.group().replace("-", ":").toLowerCase();
                    if (isStableMac(mac)) macs.add(mac);
                }
            }
        } else {
            out = exec(new String[]{"ifconfig"}, 5);
            if (out != null) {
                Matcher m = Pattern.compile("ether\\s+([0-9a-fA-F:]{17})").matcher(out);
                while (m.find()) {
                    String mac = m.group(1).toLowerCase();
                    if (isStableMac(mac)) macs.add(mac);
                }
            }
        }
        // 去重
        return new ArrayList<>(new LinkedHashSet<>(macs));
    }

    /** 过滤回环、全零、随机/本地位设置的 MAC。 */
    private static boolean isStableMac(String mac) {
        String s = mac.replace(":", "").replace("-", "").toLowerCase();
        if (s.length() < 12 || s.equals(repeat('0', 12))) return false;
        int firstByte = Integer.parseInt(s.substring(0, 2), 16);
        // 回环
        if (firstByte == 0x02) return false;
        // 本地管理位：第二低位为 1 表示随机/本地分配
        if ((firstByte & 0x02) != 0) return false;
        return true;
    }

    private static String repeat(char c, int count) {
        char[] arr = new char[count];
        Arrays.fill(arr, c);
        return new String(arr);
    }
}
