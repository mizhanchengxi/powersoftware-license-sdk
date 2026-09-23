# PowerSoftware License SDK

PowerSoftware（幂栈网）授权码开放能力的官方 SDK，覆盖 **Node.js / Python / Java** 三种语言，提供一致的机器码算法、HMAC 签名、接口封装与本地校验缓存。

Official SDKs for the PowerSoftware license system, covering **Node.js / Python / Java** with a consistent machine-code algorithm, HMAC signing, endpoint wrappers, and a local verification cache.

<p align="center">
  <b>🌐 语言 / Language</b> &nbsp;·&nbsp; <a href="./README.md">中文</a> &nbsp;|&nbsp; <a href="./README.en.md">English</a>
  <br/>
  <sub>下方接入文档均为中英双份，点击对应语言切换 / Every doc below is bilingual — click a language to switch. <a href="#docs--文档">Docs ↓</a></sub>
</p>

---

## 目录结构 / Repository layout

```text
node/     Node.js SDK (ESM, zero-dep, single file)
python/   Python SDK (py3, zero-dep, 3 files)
java/     Java SDK (Java 8+, zero-dep, 3 files)
docs/     Specs & integration guides (中文 + English, one .md / .en.md pair per topic)
```

## 安装 / Installation

三语言均为零依赖、**直接拷贝源码**到你的项目中，无需任何包管理器。

### Node.js

将以下文件拷贝到项目中：

```
node/src/
└── index.js    全部功能（machineCode / sign / LicenseClient）
```

### Python

将以下文件拷贝到项目中（建议放在 `ps_license_sdk/` 目录下）：

```
python/ps_license_sdk/
├── __init__.py   导出入口
├── client.py     核心客户端（activate / verify / claim_trial / generate_for_software / purchase_url 等）
└── machine.py    机器码生成（三级降级策略）
```

### Java

将以下文件拷贝到项目中（建议放在 `app/powersoftware/sdk/` 包路径下）：

```
java/src/main/java/app/powersoftware/sdk/
├── LicenseClient.java    核心客户端（activate / verify / claimTrial / generateForSoftware / purchaseUrl 等）
├── MachineCode.java      机器码生成（三级降级策略）
└── Json.java             极简 JSON 工具（内部使用）
```

拷贝后把 `package` 声明改为你的包名即可（默认 `app.powersoftware.sdk`）。

## 三语言用法 / Quickstart

### Node.js

```js
import { LicenseClient, machineCode } from './index.js';

const client = new LicenseClient({ productUniqueCode: 'PRO-2026-001', apiSecret: process.env.LICENSE_API_SECRET });
const mc = machineCode();

const trial = await client.claimTrial(mc);                                    // 试用领取
const ok = await client.verifyCached(trial.licenseCode, mc, trial.activationToken); // 60s 缓存校验
const url = client.purchaseUrl(mc);                                           // 未授权 → 购买页
const lic = await client.generateForSoftware({ machineCode: mc, edition: 'PRO', clientOrderId: 'ord-123' });
```

### Python

```python
from ps_license_sdk import LicenseClient, machine_code

client = LicenseClient(product_unique_code="PRO-2026-001", api_secret="你的软件发码密钥")
mc = machine_code()

trial = client.claim_trial(mc)
ok = client.verify_cached(trial["licenseCode"], mc, trial["activationToken"])
url = client.purchase_url(mc)
lic = client.generate_for_software(mc, edition="PRO", client_order_id="ord-123")
```

### Java

```java
import app.powersoftware.sdk.LicenseClient;

LicenseClient client = new LicenseClient("PRO-2026-001", "你的软件发码密钥");
String mc = LicenseClient.machineCode();

Map<String, Object> trial = client.claimTrial(mc);
Map<String, Object> ok = client.verifyCached((String) trial.get("licenseCode"), mc, (String) trial.get("activationToken"));
String url = client.purchaseUrl(mc);
Map<String, Object> lic = client.generateForSoftware(mc, "PRO", 0, "ord-123");
```

## 能力 / Features

- 机器码：同一台机器三语言生成一致（三级降级：硬件序列号 → 系统机器 ID → hostname|os|arch）
- 签名：`software/generate`、`software/upgrade` 自动 HMAC-SHA256 签名 + 时间戳防重放
- 接口：`activate` / `verify` / `deactivate` / `claimTrial` / `generateForSoftware` / `upgradeForSoftware`
- 本地凭证：只存 `licenseCode + activationToken + 最近校验结果`，60s 缓存，不存可解密的完整授权信息
- 购买页跳转：`/product/license/purchase?productUniqueCode=&machineCode=`

English:

- **Machine code** — identical across the three languages on the same machine (3-level fallback: hardware serial → system machine ID → `hostname|os|arch`).
- **Signing** — `software/generate` and `software/upgrade` are auto-signed with HMAC-SHA256 + timestamp to prevent replay.
- **Endpoints** — `activate` / `verify` / `deactivate` / `claimTrial` / `generateForSoftware` / `upgradeForSoftware`.
- **Local credential** — only `licenseCode + activationToken + last verify result` are persisted, with a 60s verify cache; no decryptable full license payload is stored.
- **Purchase page redirect** — `/product/license/purchase?productUniqueCode=&machineCode=`.

## Docs / 文档

> 每篇文档均提供中文与 English 两个版本，点击对应语言切换。  
> Every document is available in both Chinese and English — click a language to switch.

| Document / 文档 | 🇨🇳 中文 | 🇬🇧 English |
|---|---|---|
| License SDK spec / 授权 SDK 规范 | [授权SDK规范_v3.md](docs/授权SDK规范_v3.md) | [授权SDK规范_v3.en.md](docs/授权SDK规范_v3.en.md) |
| Client software guide / 客户端软件授权接入指南 | [客户端软件授权接入指南_v3.md](docs/客户端软件授权接入指南_v3.md) | [客户端软件授权接入指南_v3.en.md](docs/客户端软件授权接入指南_v3.en.md) |
| Browser extension guide / 浏览器插件授权接入指南 | [浏览器插件授权接入指南_v3.md](docs/浏览器插件授权接入指南_v3.md) | [浏览器插件授权接入指南_v3.en.md](docs/浏览器插件授权接入指南_v3.en.md) |
| Open-API reference / 授权开放接口文档 | [授权接口文档_v3.md](docs/授权接口文档_v3.md) | [授权接口文档_v3.en.md](docs/授权接口文档_v3.en.md) |

Platform-side help center / 帮助中心（平台端接口说明）LICENSE_API_DOC：https://www.powersoftware.app/doc/detail/LICENSE_API_DOC

## License

MIT
