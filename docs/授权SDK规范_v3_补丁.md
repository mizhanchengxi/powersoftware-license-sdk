# 授权SDK规范_v3 补丁

> 将以下内容插入到 `授权SDK规范_v3.md` 5.1 节（计费周期与续费顺延）之后、错误码小节之前。

---

### 5.2 购买站选择：`.app`（国际站） vs `.cn`（国内站）

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

**方式二：用户设置项** — 设置界面提供"地区"选项，用户自行选择。

**方式三：硬编码** — 仅面向国内用户的软件直接写死 `base="https://www.powersoftware.cn"`。

> 详见 [语言与国家逻辑](语言与国家逻辑_v3.md) 第 2.4 节。
