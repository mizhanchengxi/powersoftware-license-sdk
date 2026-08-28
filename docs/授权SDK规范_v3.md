# 授权 SDK 规范（v3）

> 依据：`ps-help/v3/doc/售卖方式与授权购买链路_v3.md`、帮助中心 LICENSE_API_DOC（v3）。
> 目标：Node / Python / Java 三语言 SDK 行为一致（机器码、签名、接口、缓存、错误码、购买页跳转）。

## 1. 机器码算法（跨语言一致）

同一台机器上三语言 SDK 必须生成相同的 `machineCode`。

指纹来源按**优先级**选取，取到有效值即停止：

```text
raw = 持久化 UUID → 硬件序列号 → 系统机器 ID → 硬件信号组合 → hostname | os | arch（兜底）
```

### 优先级 0：本地持久化 UUID（首次计算后写入文件，后续直接读取）

首次运行时，按优先级 1–4 计算 fingerprint，生成 UUID 并写入本地文件；后续调用直接读取该文件，跳过所有硬件采集。

| 字段 | 说明 |
| --- | --- |
| 文件路径 | `$PS_LICENSE_HOME/.machine-id`，未设环境变量时回退 `~/.powersoftware/.machine-id` |
| 生成方式 | SHA-256(fingerprint) 前 32 位 hex，格式化为 UUID 样式 |
| 写入策略 | 仅文件不存在时写入，已有则跳过 |
| 稳定性 | 只要用户目录/磁盘不变，机器码不变（跨重启、跨改名、跨重装） |

### 优先级 1：硬件序列号（BIOS SN，重装系统不变）

| 系统 | 来源 | 获取方式 | 权限 |
| --- | --- | --- | --- |
| Windows | BIOS SerialNumber | `wmic bios get serialnumber`（降级 PowerShell `Get-CimInstance Win32_BIOS`） | 普通用户 |
| macOS | 硬件序列号 | `system_profiler SPHardwareDataType` 提取 `Serial Number` | 普通用户 |
| Linux | 产品序列号 | 读 `/sys/class/dmi/id/product_serial`（降级 `dmidecode -s system-serial-number`） | **需 root** |

### 优先级 2：系统机器 ID（安装时生成，同机不变）

| 系统 | 来源 | 获取方式 | 权限 |
| --- | --- | --- | --- |
| Windows | MachineGuid | 注册表 `HKLM\SOFTWARE\Microsoft\Cryptography` | 普通用户 |
| macOS | IOPlatformUUID | `ioreg -d2 -c IOPlatformPlatformDevice` | 普通用户 |
| Linux | machine-id | 读 `/etc/machine-id`（降级 `/var/lib/dbus/machine-id`） | 所有用户 |

### 优先级 3：硬件信号组合（MAC + 平台 + 架构）

当硬件序列号和系统机器 ID 均获取失败时，组合多个硬件信号生成指纹。单一因素变化（如改名）不会导致整体指纹变化。

| 信号 | Node | Python | Java |
| --- | --- | --- | --- |
| MAC 地址（非随机、非回环） | `getmac /v`（Win）/ `ifconfig`（Mac/Linux） | 同左 | 同左 |
| 平台 | `os.platform()` | `sys.platform` | 映射为 `win32/darwin/linux` |
| 架构 | `os.arch()` | `platform.machine()` | `System.getProperty("os.arch")` |

MAC 采集统一用命令行工具（Windows: `getmac /v`，Mac/Linux: `ifconfig`），确保跨语言结果一致。
MAC 过滤规则：去除全零、回环 (`0x02`)、本地位设置 (`bit1 & 0x02`) 的 MAC 地址。

> 注：不含 CPU/内存信号，因各语言原生 API 取值不同，无法保证跨语言一致。

### 优先级 4：兜底（hostname | os | arch）

当以上全部不可用时使用（极端精简系统、容器等）。

| 字段 | Node | Python | Java |
| --- | --- | --- | --- |
| hostname | `os.hostname()` | `socket.gethostname()` | `InetAddress.getLocalHost().getHostName()` |
| os | `os.platform()` | `sys.platform` | `System.getProperty("os.name")` |
| arch | `os.arch()` | `platform.machine()` | `System.getProperty("os.arch")` |

### 统一处理

- 所有字段去除首尾空白并转小写；
- 过滤厂商占位值（`To be filled by O.E.M.` / `None` / `0` / `Default` / `Not Available` / `Not Specified`）；
- 拼接符统一为 `|`，整体再 `toLowerCase()`。

```text
machineCode = 'M' + base64url( sha256( fingerprint ) ).slice(0, 32)
```

`machineCode` 至少 8 位，全平台唯一，且 SDK 内部缓存（进程内）。

## 2. 签名规则（software/generate、software/upgrade 必须）

签名串按固定顺序、以 `\n` 换行分隔（缺省填空值：`edition` 空串、`expiryDays` 0、`licenseCode` 空串）：

```text
productUniqueCode
machineCode
edition
expiryDays
clientOrderId
licenseCode
timestamp
```

`signature = base64url( HMAC-SHA256( licenseApiSecret, 签名串 ) )`；`timestamp` 为毫秒，平台校验与服务器时间差 ≤ 5 分钟（防重放）。

## 3. 接口清单

前缀：`https://www.powersoftware.app/frontApi`（可通过 `baseUrl` 覆盖）。

| 接口 | 路径 | 鉴权 | SDK 方法 |
| --- | --- | --- | --- |
| 激活 | `POST /license/activate` | 无 | `activate(licenseCode, machineCode)` |
| 校验 | `POST /license/verify` | 无 | `verify(licenseCode, machineCode, activationToken)` |
| 解绑 | `POST /license/deactivate` | 登录（浏览器场景） | `deactivate(licenseCode, machineCode)` |
| 软件内发码 | `POST /license/software/generate` | HMAC 签名 | `generateForSoftware({...})` |
| 软件内升级/续费 | `POST /license/software/upgrade` | HMAC 签名 | `upgradeForSoftware({...})` |
| 领取试用 | `POST /license/trial/claim` | 无 | `claimTrial(machineCode)` |

`generateForSoftware` / `upgradeForSoftware` 自动补 `timestamp` + `signature`；`claimTrial` 需产品为先用后付。

## 4. 本地凭证与校验缓存

- 本地**只存**：`licenseCode`、`activationToken`、最近一次 verify 结果（`{ valid, edition, expiryTime }` + 时间戳）。
- **不存**可解密的完整授权信息（防逆向无意义，只作缓存）。
- 校验缓存 TTL 60s：点击付费功能时先查缓存，未过期直接用；过期或失败再调服务端 `verify`。
- 服务端吊销/退款/升级会主动失效缓存（平台侧已实现），SDK 无需感知。

## 5. 付费功能拦截与购买页跳转

付费菜单按钮点击：

1. `verifyCached(licenseCode, machineCode, activationToken)` 返回有效且未过期 → 放行；
2. 返回无效/过期/未激活 → 弹窗提示"需要购买激活授权"；
3. 生成 `machineCode`，跳转购买页（产品标识为 `productUniqueCode`，携带机器码）：

```text
https://www.powersoftware.app/product/license/purchase?productUniqueCode={productUniqueCode}&machineCode={machineCode}
```

（多语言站点在路径前加语言前缀，如 `/en-US/product/license/purchase`；`productUniqueCode` 由构造器传入。）

### 5.1 购买站选择：`.app`（国际站） vs `.cn`（国内站）

幂栈网有两个站点，SDK 的 `purchaseUrl()` 通过 `base` 参数切换：

| | `powersoftware.app`（国际站） | `powersoftware.cn`（国内站） |
|---|---|---|
| 支付方式 | 支付宝 + PayPal | 仅支付宝 |
| 国家/币种 | Cloudflare 按 IP 自动识别（CN→CNY，其余→USD） | 固定 `country=CN`，人民币 |
| 语言 | 按 URL 前缀 / `Accept-Language` 自动检测 | 固定 `zh-CN` |
| 适用用户 | 国际用户 / 海外 | 中国大陆用户 |

**SDK 默认 `.app`，不做自动判断。** 客户端软件需自行决定传哪个 `base`。

#### 判断方式

**方式一：按系统语言（推荐）**

```python
import locale

def get_purchase_base():
    sys_lang = locale.getdefaultlocale()[0] or ""
    if sys_lang.startswith("zh"):
        return "https://www.powersoftware.cn"
    return "https://www.powersoftware.app"
```

```javascript
function getPurchaseBase() {
    const lang = process.env.LANG || "";
    return lang.toLowerCase().startsWith("zh")
        ? "https://www.powersoftware.cn"
        : "https://www.powersoftware.app";
}
```

```java
String getPurchaseBase() {
    return "zh".equals(Locale.getDefault().getLanguage())
        ? "https://www.powersoftware.cn"
        : "https://www.powersoftware.app";
}
```

**方式二：用户设置项** — 设置界面提供“地区”选项，用户自行选择。

**方式三：硬编码** — 仅面向国内用户的软件直接写死 `base="https://www.powersoftware.cn"`。

> 详见 [语言与国家逻辑](语言与国家逻辑_v3.md) 第 2.4 节。

## 6. 错误码（SDK 抛错统一携带 errorCode）

`codeNotFound`、`revoked`、`expired`、`machineLimit`、`tooManyAttempts`、`signatureInvalid`、`apiSecretMissing`、`productNotEnabled`、`trialNotEnabled`、`machineCodeInvalid`、`orderAlreadyUsed`、`editionRequired`、`productNotFound`、`trialFirstRequired`、`alreadyOwned` 等；网络/超时错误统一为 `NETWORK_ERROR`。

## 7. 包结构

```text
ps-sdk/
  node/     @mizhanchengxi/ps-license-sdk（ESM，零依赖）
  python/   ps-license-sdk（py3，零依赖）
  java/     com.powersoftware:sdk（Java 8+，JDK 自带 HTTP/加密）
```

三个包均提供：`machineCode()`、`sign()`、`LicenseClient`（上述 6 方法 + `verifyCached` + `purchaseUrl`）。
