package com.powersoftware.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PowerSoftware 授权客户端（Java 11+，零依赖）。
 * 方法：activate / verify / deactivate / claimTrial / generateForSoftware / upgradeForSoftware / verifyCached / purchaseUrl。
 */
public class LicenseClient {

    public static final String DEFAULT_BASE_URL = "https://www.powersoftware.app/frontApi";
    private static final long VERIFY_CACHE_TTL_MS = 60_000L;

    private final String baseUrl;
    private final String apiSecret;
    private final Integer productId;
    private final HttpClient http;
    private final long cacheTtlMs;
    private VerifyCacheEntry verifyCache;

    public LicenseClient(Integer productId, String apiSecret) {
        this(DEFAULT_BASE_URL, productId, apiSecret, VERIFY_CACHE_TTL_MS);
    }

    public LicenseClient(String baseUrl, Integer productId, String apiSecret) {
        this(baseUrl, productId, apiSecret, VERIFY_CACHE_TTL_MS);
    }

    public LicenseClient(String baseUrl, Integer productId, String apiSecret, long cacheTtlMs) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.productId = productId;
        this.apiSecret = apiSecret == null ? "" : apiSecret;
        this.cacheTtlMs = cacheTtlMs;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public static String machineCode() {
        return MachineCode.get();
    }

    /** HMAC 签名：productId \\n machineCode \\n edition \\n expiryDays \\n clientOrderId \\n licenseCode \\n timestamp */
    public static String sign(String apiSecret, Map<String, Object> params) {
        String payload = String.join("\n",
                str(params.get("productId")),
                str(params.get("machineCode")),
                str(params.get("edition")),
                params.get("expiryDays") == null ? "0" : String.valueOf(params.get("expiryDays")),
                str(params.get("clientOrderId")),
                str(params.get("licenseCode")),
                str(params.get("timestamp")));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    public Map<String, Object> request(String path, Map<String, Object> body, boolean signed) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(body == null ? Map.of() : body);
        if (signed) {
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("signature", sign(apiSecret, payload));
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> json;
        try {
            json = Json.parseObject(resp.body());
        } catch (Exception e) {
            throw new LicenseException("invalid response", "BAD_RESPONSE");
        }
        if (!Boolean.TRUE.equals(json.get("success"))) {
            String tip = json.get("tip") == null ? "request failed" : String.valueOf(json.get("tip"));
            String code = json.get("code") == null ? "REQUEST_FAILED" : String.valueOf(json.get("code"));
            throw new LicenseException(tip, code);
        }
        return (Map<String, Object>) json.get("content");
    }

    public Map<String, Object> activate(String licenseCode, String machineCodeValue) throws Exception {
        return request("/license/activate", Map.of("licenseCode", licenseCode, "machineCode", machineCodeValue), false);
    }

    public Map<String, Object> verify(String licenseCode, String machineCodeValue, String activationToken) throws Exception {
        return request("/license/verify", Map.of(
                "licenseCode", licenseCode,
                "machineCode", machineCodeValue,
                "activationToken", activationToken == null ? "" : activationToken), false);
    }

    public Map<String, Object> deactivate(String licenseCode, String machineCodeValue) throws Exception {
        return request("/license/deactivate", Map.of("licenseCode", licenseCode, "machineCode", machineCodeValue), false);
    }

    public Map<String, Object> claimTrial(String machineCodeValue) throws Exception {
        requireProductId();
        return request("/license/trial/claim", Map.of("productId", productId, "machineCode", machineCodeValue), false);
    }

    public Map<String, Object> generateForSoftware(String machineCodeValue, String edition, int expiryDays, String clientOrderId) throws Exception {
        requireProductId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", productId);
        body.put("machineCode", machineCodeValue);
        body.put("edition", edition == null ? "" : edition);
        body.put("expiryDays", expiryDays);
        body.put("clientOrderId", clientOrderId == null ? "" : clientOrderId);
        return request("/license/software/generate", body, true);
    }

    public Map<String, Object> upgradeForSoftware(String licenseCode, String machineCodeValue, String edition, int expiryDays, String clientOrderId) throws Exception {
        requireProductId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", productId);
        body.put("licenseCode", licenseCode);
        body.put("machineCode", machineCodeValue);
        body.put("edition", edition);
        body.put("expiryDays", expiryDays);
        body.put("clientOrderId", clientOrderId == null ? "" : clientOrderId);
        return request("/license/software/upgrade", body, true);
    }

    /** 60s 校验缓存：付费功能点击前调用 */
    public Map<String, Object> verifyCached(String licenseCode, String machineCodeValue, String activationToken) throws Exception {
        long now = System.currentTimeMillis();
        if (verifyCache != null && now - verifyCache.at < cacheTtlMs) {
            return verifyCache.data;
        }
        Map<String, Object> data = verify(licenseCode, machineCodeValue, activationToken);
        verifyCache = new VerifyCacheEntry(now, data);
        return data;
    }

    public String purchaseUrl(String machineCodeValue) {
        requireProductId();
        return "https://www.powersoftware.app/product/license/purchase?productId=" + productId
                + "&machineCode=" + URLEncoder.encode(machineCodeValue, StandardCharsets.UTF_8);
    }

    private void requireProductId() {
        if (productId == null) {
            throw new LicenseException("productId required", "PRODUCT_ID_REQUIRED");
        }
    }

    public static class LicenseException extends RuntimeException {
        public final String errorCode;

        public LicenseException(String message, String errorCode) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    private static final class VerifyCacheEntry {
        final long at;
        final Map<String, Object> data;

        VerifyCacheEntry(long at, Map<String, Object> data) {
            this.at = at;
            this.data = data;
        }
    }
}
