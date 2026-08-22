# PowerSoftware License SDK (Python)

零依赖 Python 3 包：激活 / 校验 / 解绑 / 软件内发码与升级 / 试用领取，机器码与 HMAC 签名跨语言一致。

## 安装

直接拷贝源码到你的项目中，无需 pip 安装。将以下文件放到项目目录（建议保留 `ps_license_sdk/` 子目录结构）：

```
python/ps_license_sdk/
├── __init__.py   导出入口
├── client.py     核心客户端（activate / verify / claim_trial / generate_for_software / purchase_url 等）
└── machine.py    机器码生成（三级降级策略）
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
cd python && python -m unittest discover tests
```

接口细节见 [授权 SDK 规范 v3](../../ps-help/v3/doc/授权SDK规范_v3.md)。
