# PowerSoftware License SDK Specification (v3)

This document defines the behavior that must be identical across the Node.js, Python and Java SDKs: machine code, signing, endpoints, caching and purchase-page redirect.

## 1. Machine code (cross-language consistent)

All three SDKs must produce the same `machineCode` on the same machine.

```text
fingerprint = hostname | os | arch | primaryMac
```

- hostname: `os.hostname()` / `socket.gethostname()` / `InetAddress.getLocalHost().getHostName()`
- os: `os.platform()` / `sys.platform` / `System.getProperty("os.name")`
- arch: `os.arch()` / `platform.machine()` / `System.getProperty("os.arch")`
- primaryMac: first non-virtual network interface MAC, lowercased, separators removed (Python: `uuid.getnode() & 0xFFFFFFFFFFFF`, `%012x`)

Normalize: trim + lowercase all fields; join with `|`; lowercase the whole string.

```text
machineCode = 'M' + base64url( sha256( fingerprint ) ).slice(0, 32)
```

## 2. Signing (software/generate, software/upgrade)

Payload joined by `\n` in fixed order (empty string for missing `edition`/`licenseCode`, `0` for `expiryDays`):

```text
productId \n machineCode \n edition \n expiryDays \n clientOrderId \n licenseCode \n timestamp
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
3. Redirect with machine code (product identified by either `productId` or `productUniqueCode`):

```text
https://www.powersoftware.app/product/license/purchase?productId={productId}&machineCode={machineCode}
https://www.powersoftware.cn/product/license/purchase?productUniqueCode={productUniqueCode}&machineCode={machineCode}
```

(Multi-language sites prepend the locale prefix, e.g. `/en-US/product/license/purchase`; for the China site pass `base=https://www.powersoftware.cn`. All three SDKs implement `purchaseUrl(machineCode, { base, productUniqueCode })` consistently — since 2026-08-20 `productUniqueCode` can be used instead of the numeric `productId` so developers can jump with their product unique code.)

## 6. Error codes

`codeNotFound`, `revoked`, `expired`, `machineLimit`, `tooManyAttempts`, `signatureInvalid`, `apiSecretMissing`, `productNotEnabled`, `trialNotEnabled`, `machineCodeInvalid`, `orderAlreadyUsed`, `editionRequired`, `productNotFound`, `trialFirstRequired`, `alreadyOwned`; network/timeout errors are `NETWORK_ERROR`.
