# PowerSoftware License SDK (Java)

Java 11+，零依赖（JDK 自带 HTTP/加密）。激活 / 校验 / 解绑 / 软件内发码与升级 / 试用领取，机器码与 HMAC 签名跨语言一致。

## 构建

```bash
cd ps-sdk/java
mvn -q package
```

## 快速开始

```java
import com.powersoftware.sdk.LicenseClient;

LicenseClient client = new LicenseClient(88, "你的软件发码密钥"); // 密钥仅存软件服务端
String mc = LicenseClient.machineCode();

Map<String, Object> trial = client.claimTrial(mc);                                  // 先用后付：领取试用
Map<String, Object> ok = client.verifyCached((String) trial.get("licenseCode"), mc, (String) trial.get("activationToken"));
String url = client.purchaseUrl(mc);                                                 // 未授权 → 购买页
Map<String, Object> lic = client.generateForSoftware(mc, "PRO", 0, "ord-123");      // 软件内发码（签名+幂等）
```

接口细节见 [授权 SDK 规范 v3](../../ps-help/v3/doc/授权SDK规范_v3.md)。
