package com.powersoftware.sdk;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 机器码：跨语言一致算法（见 powersoftware-license-sdk/docs/授权SDK规范_v3.md）
 * <p>
 * fingerprint 优先级：
 * 1. 硬件序列号（BIOS SN，重装系统不变）
 * 2. 系统机器 ID（MachineGuid / machine-id / IOPlatformUUID，安装时生成）
 * 3. 兜底 hostname | os | arch
 */
public final class MachineCode {

    private MachineCode() {
    }

    public static String get() {
        String fingerprint = getFingerprint();
        byte[] digest = sha256(fingerprint.getBytes(StandardCharsets.UTF_8));
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return "M" + b64.substring(0, Math.min(32, b64.length()));
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

        // 3. 兜底
        return String.join("|", hostname(), os, System.getProperty("os.arch", "")).toLowerCase();
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
}
