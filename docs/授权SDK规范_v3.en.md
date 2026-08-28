# PowerSoftware License SDK Specification (v3)

This document defines the behavior that must be identical across the Node.js, Python and Java SDKs: machine code, signing, endpoints, caching and purchase-page redirect.

## 1. Machine code (cross-language consistent)

All three SDKs must produce the same `machineCode` on the same machine.

Fingerprint sources are selected by **priority** — stop at the first valid value:

```text
raw = persisted UUID → hardware serial → system machine ID → composite hardware signals → hostname | os | arch (last resort)
```

### Priority 0: Persisted UUID (computed once, then read from local file)

On first run, the SDK computes the fingerprint (priority 1–4), generates a UUID, and writes it to a local file. Subsequent calls read the file directly, skipping all hardware collection.

| Field | Description |
| --- | --- |
| File path | `$PS_LICENSE_HOME/.machine-id`, fallback `~/.powersoftware/.machine-id` |
| Generation | SHA-256(fingerprint) first 32 hex chars, formatted as UUID-style |
| Write policy | Write only if file does not exist; skip if already present |
| Stability | Machine code stays the same as long as user home / disk persists (survives rename, OS reinstall) |

### Priority 1: Hardware serial (BIOS SN, survives OS reinstall)

| OS | Source | Method | Privilege |
| --- | --- | --- | --- |
| Windows | BIOS SerialNumber | `wmic bios get serialnumber` (fallback PowerShell `Get-CimInstance Win32_BIOS`) | Standard user |
| macOS | Hardware serial | `system_profiler SPHardwareDataType` extract `Serial Number` | Standard user |
| Linux | Product serial | Read `/sys/class/dmi/id/product_serial` (fallback `dmidecode -s system-serial-number`) | **root required** |

### Priority 2: System machine ID (generated at install, stable per machine)

| OS | Source | Method | Privilege |
| --- | --- | --- | --- |
| Windows | MachineGuid | Registry `HKLM\SOFTWARE\Microsoft\Cryptography` | Standard user |
| macOS | IOPlatformUUID | `ioreg -d2 -c IOPlatformPlatformDevice` | Standard user |
| Linux | machine-id | Read `/etc/machine-id` (fallback `/var/lib/dbus/machine-id`) | All users |

### Priority 3: Composite hardware signals (MAC + platform + arch)

Used when both hardware serial and system machine ID fail. Combines multiple hardware signals so that a change in any single factor (e.g. hostname rename) does not alter the overall fingerprint.

| Signal | Node | Python | Java |
| --- | --- | --- | --- |
| MAC addresses (non-random, non-loopback) | `getmac /v` (Win) / `ifconfig` (Mac/Linux) | Same | Same |
| Platform | `os.platform()` | `sys.platform` | Mapped to `win32/darwin/linux` |
| Architecture | `os.arch()` | `platform.machine()` | `System.getProperty("os.arch")` |

MAC collection uses command-line tools uniformly (Windows: `getmac /v`, Mac/Linux: `ifconfig`) to ensure cross-language consistency.
MAC filtering: exclude all-zero, loopback (`0x02`), and locally-administered (`bit1 & 0x02`) MAC addresses.

> Note: CPU/memory signals are intentionally excluded because native APIs return different values across languages.

### Priority 4: Last resort (hostname | os | arch)

Used only when all above signals are unavailable (minimal containers, stripped systems).

| Field | Node | Python | Java |
| --- | --- | --- | --- |
| hostname | `os.hostname()` | `socket.gethostname()` | `InetAddress.getLocalHost().getHostName()` |
| os | `os.platform()` | `sys.platform` | `System.getProperty("os.name")` |
| arch | `os.arch()` | `platform.machine()` | `System.getProperty("os.arch")` |

### Normalization

- Trim and lowercase all fields;
- Filter vendor placeholder values (`To be filled by O.E.M.` / `None` / `0` / `Default` / `Not Available` / `Not Specified`);
- Join with `|`, then lowercase the whole string.

```text
machineCode = 'M' + base64url( sha256( fingerprint ) ).slice(0, 32)
```

`machineCode` is at least 8 chars, globally unique, and cached in-process by the SDK.

## 2. Signing (software/generate, software/upgrade)

Payload joined by `\n` in fixed order (empty string for missing `edition`/`licenseCode`, `0` for `expiryDays`):

```text
productUniqueCode 
 machineCode 
 edition 
 expiryDays 
 clientOrderId 
 licenseCode 
 timestamp
```

`signature = base64url( HMAC-SHA256( licenseApiSecret, payload ) )`; `timestamp` in milliseconds, platform rejects drift > 5 minutes.

## 3. Endpoints

Base: `https://www.powersoftware.app/frontApi` (overridable).

| Endpoint | Method | Auth | SDK method |
| --- | --- | --- | --- |
| `/license/activate` | POST | none | `activate` |
| `/license/verify` | POST | none | `verify` |
| `/license/deactivate` | POST | login | `deactivate` |
| `/license/software/generate` | POST | HMAC | `generateForSoftware` |
| `/license/software/upgrade` | POST | HMAC | `upgradeForSoftware` |
| `/license/trial/claim` | POST | none | `claimTrial` |

`generateForSoftware` / `upgradeForSoftware` add `timestamp` + `signature` automatically.

## 4. Local credential & verification cache

- Store only: `licenseCode`, `activationToken`, latest verify result (`{ valid, edition, expiryTime }`) with a timestamp.
- Do **not** store decryptable full license info locally (reverse engineering cannot be prevented anyway).
- Verification cache TTL 60s: check cache before each paid-feature click; on expiry or failure call the server.

## 5. Paid-feature gate & purchase page

1. `verifyCached(...)` returns valid & not expired → allow.
2. Invalid/expired/not activated → prompt "purchase & activate required".
3. Redirect with machine code (product identified by `productUniqueCode`):

```text
https://www.powersoftware.app/product/license/purchase?productUniqueCode={productUniqueCode}&machineCode={machineCode}
https://www.powersoftware.cn/product/license/purchase?productUniqueCode={productUniqueCode}&machineCode={machineCode}
```

(Multi-language sites prepend the locale prefix, e.g. `/en-US/product/license/purchase`; for the China site pass `base=https://www.powersoftware.cn`. All three SDKs implement `purchaseUrl(machineCode, { base })` consistently — `productUniqueCode` is passed to the constructor.)

## 6. Error codes

`codeNotFound`, `revoked`, `expired`, `machineLimit`, `tooManyAttempts`, `signatureInvalid`, `apiSecretMissing`, `productNotEnabled`, `trialNotEnabled`, `machineCodeInvalid`, `orderAlreadyUsed`, `editionRequired`, `productNotFound`, `trialFirstRequired`, `alreadyOwned`; network/timeout errors are `NETWORK_ERROR`.
