# PowerSoftware License SDK (Python)

零依赖 Python 3 包：激活 / 校验 / 解绑 / 软件内发码与升级 / 试用领取，机器码与 HMAC 签名跨语言一致。

## 安装

```bash
pip install ps-license-sdk
```

## 快速开始

```python
from ps_license_sdk import LicenseClient, machine_code

client = LicenseClient(product_unique_code="PRO-2026-001", api_secret="你的软件发码密钥")  # 密钥仅存软件服务端
mc = machine_code()

trial = client.claim_trial(mc)                              # 先用后付：领取试用
ok = client.verify_cached(trial["licenseCode"], mc, trial["activationToken"])  # 60s 缓存校验
url = client.purchase_url(mc)                               # 未授权 → 购买页
lic = client.generate_for_software(mc, edition="PRO", client_order_id="ord-123")  # 软件内发码（签名+幂等）
```

## 测试

```bash
cd ps-sdk/python && python -m unittest discover tests
```

接口细节见 [授权 SDK 规范 v3](../../ps-help/v3/doc/授权SDK规范_v3.md)。
