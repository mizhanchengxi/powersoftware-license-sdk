# PowerSoftware License SDK

PowerSoftware（幂栈网）授权码开放能力的官方 SDK，覆盖 **Node.js / Python / Java** 三种语言，提供一致的机器码算法、HMAC 签名、接口封装与本地校验缓存。

Official SDKs for the PowerSoftware license system, covering **Node.js / Python / Java** with a consistent machine-code algorithm, HMAC signing, endpoint wrappers, and a local verification cache.

---

## 目录结构 / Repository layout

```text
node/     Node.js SDK（ESM，零依赖）
python/   Python SDK（py3，零依赖）
java/     Java SDK（Java 8+，零依赖）
docs/     SDK 规范（中文 / English）
```

## 三语言用法 / Quickstart

### Node.js

```js
import { LicenseClient, machineCode } from '@mizhanchengxi/ps-license-sdk';

const client = new LicenseClient({ productId: 88, apiSecret: process.env.LICENSE_API_SECRET });
const mc = machineCode();

const trial = await client.claimTrial(mc);                                    // 试用领取
const ok = await client.verifyCached(trial.licenseCode, mc, trial.activationToken); // 60s 缓存校验
const url = client.purchaseUrl(mc);                                           // 未授权 → 购买页
const lic = await client.generateForSoftware({ machineCode: mc, edition: 'PRO', clientOrderId: 'ord-123' });
```

### Python

```python
from ps_license_sdk import LicenseClient, machine_code

client = LicenseClient(product_id=88, api_secret="你的软件发码密钥")
mc = machine_code()

trial = client.claim_trial(mc)
ok = client.verify_cached(trial["licenseCode"], mc, trial["activationToken"])
url = client.purchase_url(mc)
lic = client.generate_for_software(mc, edition="PRO", client_order_id="ord-123")
```

### Java

```java
import com.powersoftware.sdk.LicenseClient;

LicenseClient client = new LicenseClient(88, "你的软件发码密钥");
String mc = LicenseClient.machineCode();

Map<String, Object> trial = client.claimTrial(mc);
Map<String, Object> ok = client.verifyCached((String) trial.get("licenseCode"), mc, (String) trial.get("activationToken"));
String url = client.purchaseUrl(mc);
Map<String, Object> lic = client.generateForSoftware(mc, "PRO", 0, "ord-123");
```

## 能力 / Features

- 机器码：同一台机器三语言生成一致（`hostname|os|arch|primaryMac` → SHA-256 → Base64URL）
- 签名：`software/generate`、`software/upgrade` 自动 HMAC-SHA256 签名 + 时间戳防重放
- 接口：`activate` / `verify` / `deactivate` / `claimTrial` / `generateForSoftware` / `upgradeForSoftware`
- 本地凭证：只存 `licenseCode + activationToken + 最近校验结果`，60s 缓存，不存可解密的完整授权信息
- 购买页跳转：`/product/license/purchase?productId=&machineCode=`

Machine code is identical across the three languages on the same machine; signed requests include a timestamp to prevent replay; only the credential and a 60s verification cache are stored locally.

## 文档 / Docs

- 规范（中文）：[docs/授权SDK规范_v3.md](docs/授权SDK规范_v3.md)
- Spec (English): [docs/授权SDK规范_v3.en.md](docs/授权SDK规范_v3.en.md)
- 帮助中心 LICENSE_API_DOC（平台端接口说明）：https://www.powersoftware.app/doc/detail/LICENSE_API_DOC

## License

MIT
