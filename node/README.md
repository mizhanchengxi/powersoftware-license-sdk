# PowerSoftware License SDK (Node.js)

零依赖 ESM 单文件：激活 / 校验 / 解绑 / 软件内发码与升级 / 试用领取，机器码与 HMAC 签名跨语言一致（Node / Python / Java）。

## 安装

直接拷贝源码到你的项目中，无需 npm 安装：

```
node/src/
└── index.js    全部功能（machineCode / sign / LicenseClient）
```

## 快速开始

```js
import { LicenseClient, machineCode } from './index.js';

const client = new LicenseClient({
  productUniqueCode: 'PRO-2026-001',
  apiSecret: process.env.LICENSE_API_SECRET, // 仅软件服务端保存
});

const mc = machineCode();

// 先用后付：领取试用
const trial = await client.claimTrial(mc);

// 付费功能点击：先查 60s 缓存，失效再联网校验
const ok = await client.verifyCached(trial.licenseCode, mc, trial.activationToken);

// 未激活/过期 → 跳转购买页
window.location.href = client.purchaseUrl(mc);

// 软件内支付成功后发码（HMAC 签名，clientOrderId 幂等）
const lic = await client.generateForSoftware({ machineCode: mc, edition: 'PRO', expiryDays: 0, clientOrderId: 'ord-123' });
```

## 接口

见 [授权 SDK 规范 v3](../../ps-help/v3/doc/授权SDK规范_v3.md) 与帮助中心 LICENSE_API_DOC。
