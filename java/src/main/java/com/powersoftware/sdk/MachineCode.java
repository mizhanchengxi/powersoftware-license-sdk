package com.powersoftware.sdk;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;

/**
 * 机器码：跨语言一致算法（见 ps-help/v3/doc/授权SDK规范_v3.md）
 * fingerprint = hostname | os | arch | primaryMac，整体小写后 sha256
 */
public final class MachineCode {

    private MachineCode() {
    }

    public static String get() {
        String hostname = hostname();
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        String mac = primaryMac();
        String fingerprint = String.join("|", hostname, os, arch, mac).toLowerCase();
        byte[] digest = sha256(fingerprint.getBytes(StandardCharsets.UTF_8));
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return "M" + b64.substring(0, Math.min(32, b64.length()));
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }

    private static String primaryMac() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return "";
            }
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                    continue;
                }
                byte[] hw = ni.getHardwareAddress();
                if (hw != null && hw.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hw) {
                        sb.append(String.format("%02x", b));
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
            // 忽略取不到网卡的场景
        }
        return "";
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
