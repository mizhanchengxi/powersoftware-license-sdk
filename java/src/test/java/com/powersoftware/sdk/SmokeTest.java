package com.powersoftware.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 冒烟测试（无 JUnit 依赖，直接 main 运行）：
 * java -cp target/classes com.powersoftware.sdk.SmokeTest
 */
public class SmokeTest {

    public static void main(String[] args) {
        String mc1 = LicenseClient.machineCode();
        String mc2 = LicenseClient.machineCode();
        check(mc1.equals(mc2), "machineCode stable");
        check(mc1.length() >= 8, "machineCode length");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("productId", 1);
        params.put("machineCode", "M123");
        params.put("edition", "PRO");
        params.put("expiryDays", 0);
        params.put("clientOrderId", "x");
        params.put("licenseCode", "");
        params.put("timestamp", 1000L);
        check(LicenseClient.sign("secret", params).equals(LicenseClient.sign("secret", params)), "sign deterministic");

        String url = new LicenseClient(88, "secret").purchaseUrl("MABC");
        check(url.contains("productId=88") && url.contains("machineCode=MABC"), "purchaseUrl params");
        String url2 = new LicenseClient(null, "secret").purchaseUrl("MABC", "https://www.powersoftware.cn", "PRO-2026-001");
        check(url2.contains("productUniqueCode=PRO-2026-001") && url2.contains("machineCode=MABC") && !url2.contains("productId="), "purchaseUrl productUniqueCode");
        System.out.println("SmokeTest OK");
    }

    private static void check(boolean cond, String name) {
        if (!cond) {
            throw new AssertionError("FAILED: " + name);
        }
        System.out.println("ok: " + name);
    }
}
