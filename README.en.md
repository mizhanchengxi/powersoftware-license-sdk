# PowerSoftware License SDK

Official SDKs for the PowerSoftware license system, covering **Node.js / Python / Java** with a consistent machine-code algorithm, HMAC signing, endpoint wrappers, and a local verification cache.

<p align="center">
  <b>🌐 Language</b> &nbsp;·&nbsp; <a href="./README.en.md">English</a> &nbsp;|&nbsp; <a href="./README.md">中文</a>
  <br/>
  <sub>All integration docs below are also bilingual — see <a href="#docs">Docs ↓</a>.</sub>
</p>

---

## Repository layout

```text
node/     Node.js SDK (ESM, zero-dep, single file)
python/   Python SDK (py3, zero-dep, 3 files)
java/     Java SDK (Java 8+, zero-dep, 3 files)
docs/     Specs & integration guides (中文 + English, one .md / .en.md pair per topic)
```

## Installation

All three SDKs are zero-dependency. **Copy the source files** straight into your project — no package manager required.

### Node.js

Copy into your project:

```
node/src/
└── index.js    everything (machineCode / sign / LicenseClient)
```

### Python

Copy into your project (recommended layout: `ps_license_sdk/`):

```
python/ps_license_sdk/
├── __init__.py   exports
├── client.py     core client (activate / verify / claim_trial / generate_for_software / purchase_url, …)
└── machine.py    machine-code generation (3-level fallback)
```

### Java

Copy into your project (recommended package: `app.powersoftware.sdk`):

```
java/src/main/java/app/powersoftware/sdk/
├── LicenseClient.java    core client (activate / verify / claimTrial / generateForSoftware / purchaseUrl, …)
├── MachineCode.java      machine-code generation (3-level fallback)
└── Json.java             minimal JSON helper (internal)
```

After copying, rename the `package` declaration to your own (default `app.powersoftware.sdk`).

## Quickstart

### Node.js

```js
import { LicenseClient, machineCode } from './index.js';

const client = new LicenseClient({ productUniqueCode: 'PRO-2026-001', apiSecret: process.env.LICENSE_API_SECRET });
const mc = machineCode();

const trial = await client.claimTrial(mc);                                    // claim trial
const ok = await client.verifyCached(trial.licenseCode, mc, trial.activationToken); // 60s cached verify
const url = client.purchaseUrl(mc);                                           // not licensed → purchase page
const lic = await client.generateForSoftware({ machineCode: mc, edition: 'PRO', clientOrderId: 'ord-123' });
```

### Python

```python
from ps_license_sdk import LicenseClient, machine_code

client = LicenseClient(product_unique_code="PRO-2026-001", api_secret="your software-side secret")
mc = machine_code()

trial = client.claim_trial(mc)
ok = client.verify_cached(trial["licenseCode"], mc, trial["activationToken"])
url = client.purchase_url(mc)
lic = client.generate_for_software(mc, edition="PRO", client_order_id="ord-123")
```

### Java

```java
import app.powersoftware.sdk.LicenseClient;

LicenseClient client = new LicenseClient("PRO-2026-001", "your software-side secret");
String mc = LicenseClient.machineCode();

Map<String, Object> trial = client.claimTrial(mc);
Map<String, Object> ok = client.verifyCached((String) trial.get("licenseCode"), mc, (String) trial.get("activationToken"));
String url = client.purchaseUrl(mc);
Map<String, Object> lic = client.generateForSoftware(mc, "PRO", 0, "ord-123");
```

## Features

- **Machine code** — identical across the three languages on the same machine (3-level fallback: hardware serial → system machine ID → `hostname|os|arch`).
- **Signing** — `software/generate` and `software/upgrade` are auto-signed with HMAC-SHA256 + timestamp to prevent replay.
- **Endpoints** — `activate` / `verify` / `deactivate` / `claimTrial` / `generateForSoftware` / `upgradeForSoftware`.
- **Local credential** — only `licenseCode + activationToken + last verify result` are persisted, with a 60s verify cache; no decryptable full license payload is stored.
- **Purchase page redirect** — `/product/license/purchase?productUniqueCode=&machineCode=`.

## Docs

> Every document is available in both Chinese and English — click a language to switch.

| Document | 🇬🇧 English | 🇨🇳 中文 |
|---|---|---|
| License SDK spec | [授权SDK规范_v3.en.md](docs/授权SDK规范_v3.en.md) | [授权SDK规范_v3.md](docs/授权SDK规范_v3.md) |
| Client software integration guide | [客户端软件授权接入指南_v3.en.md](docs/客户端软件授权接入指南_v3.en.md) | [客户端软件授权接入指南_v3.md](docs/客户端软件授权接入指南_v3.md) |
| Browser extension integration guide | [浏览器插件授权接入指南_v3.en.md](docs/浏览器插件授权接入指南_v3.en.md) | [浏览器插件授权接入指南_v3.md](docs/浏览器插件授权接入指南_v3.md) |
| Open-API reference | [授权接口文档_v3.en.md](docs/授权接口文档_v3.en.md) | [授权接口文档_v3.md](docs/授权接口文档_v3.md) |

Platform help center (server-side API reference): https://www.powersoftware.app/doc/detail/LICENSE_API_DOC

## License

MIT
