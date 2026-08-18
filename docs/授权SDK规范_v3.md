# 授权 SDK 规范（v3）

> 依据：`ps-help/v3/doc/售卖方式与授权购买链路_v3.md`、帮助中心 LICENSE_API_DOC（v3）。
> 目标：Node / Python / Java 三语言 SDK 行为一致（机器码、签名、接口、缓存、错误码、购买页跳转）。

## 1. 机器码算法（跨语言一致）

同一台机器上三语言 SDK 必须生成相同的 `machineCode`。

指纹来源（按序拼接，统一小写、去除多余字符）：

```text
fingerprint = hostname | os | arch | primaryMac
```

| 字段 | Node | Python | Java |
| --- | --- | --- | --- |
| hostname | `os.hostname()` | `socket.gethostname()` | `InetAddress.getLocalHost().getHostName()` |
| os | `os.platform()` | `sys.platform` | `System.getProperty("os.name")` |
| arch | `os.arch()` | `platform.machine()` | `System.getProperty("os.arch")` |
| primaryMac | 首个非虚拟网卡 MAC（去 `:` 小写） | `uuid.getnode()` 转 12 位十六进制 | 首个非回环网卡 `getHardwareAddress()` 转十六进制 |

统一处理：

- hostname / os / arch 去除首尾空白并转小写；
- MAC 转小写、去掉分隔符（Node `aa:bb:cc:dd:ee:ff`、Java 大写十六进制字节数组 → 小写）；
- Python `uuid.getnode()` 可能返回多播位（第 40 位），需 `(node & 0xFFFFFFFFFFFF)` 掩码，且按 `%012x` 格式化；
- 拼接符统一为 `|`，整体再 `toLowerCase()`。

```text
machineCode = 'M' + base64url( sha256( fingerprint ) ).slice(0, 32)
```

`machineCode` 至少 8 位，全平台唯一，且 SDK 内部缓存（进程内）。

## 2. 签名规则（software/generate、software/upgrade 必须）

签名串按固定顺序、换行分隔（缺省填空值：`edition` 空串、`expiryDays` 0、`licenseCode` 空串）：

```text
productId \n machineCode \n edition \n expiryDays \n clientOrderId \n licenseCode \n timestamp
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
3. 生成 `machineCode`，跳转购买页：

```text
https://www.powersoftware.app/product/license/purchase?productId={productId}&machineCode={machineCode}
```

（多语言站点在路径前加语言前缀，如 `/en-US/product/license/purchase`。）

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
