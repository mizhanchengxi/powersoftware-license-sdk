import crypto from 'node:crypto';
import os from 'node:os';
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const DEFAULT_BASE_URL = 'https://www.powersoftware.app/frontApi';
const VERIFY_CACHE_TTL_MS = 60 * 1000;

function toBase64Url(buf) {
  return Buffer.from(buf).toString('base64url');
}

/**
 * 机器码：跨语言一致算法（见 powersoftware-license-sdk/docs/授权SDK规范_v3.md）
 * fingerprint 优先级：硬件序列号 → 系统机器 ID → hostname|os|arch
 */
export function machineCode() {
  const fingerprint = getFingerprint();
  const digest = crypto.createHash('sha256').update(fingerprint).digest();
  return 'M' + toBase64Url(digest).slice(0, 32);
}

function getFingerprint() {
  const platform = os.platform();
  const isWin = platform === 'win32';
  const isMac = platform === 'darwin';
  const isLinux = platform === 'linux';

  // 1. 硬件序列号
  let hw = null;
  if (isWin) hw = readWindowsBiosSerial();
  else if (isMac) hw = readMacSerial();
  else if (isLinux) hw = readLinuxHardwareSerial();
  if (isMeaningful(hw)) return hw.toLowerCase();

  // 2. 系统机器 ID
  let sysId = null;
  if (isWin) sysId = readWindowsMachineGuid();
  else if (isMac) sysId = readMacPlatformUUID();
  else if (isLinux) sysId = readLinuxMachineId();
  if (isMeaningful(sysId)) return sysId.toLowerCase();

  // 3. 兜底
  return [os.hostname(), platform, os.arch()].join('|').toLowerCase();
}

/** 过滤厂商占位值（"To be filled by O.E.M." / "None" / "0" 等） */
function isMeaningful(value) {
  if (!value) return false;
  const s = value.trim().toLowerCase();
  if (!s || s === 'none' || s === '0' || s === 'default') return false;
  if (s.includes('to be filled') || s.includes('o.e.m')) return false;
  if (s.includes('system serial') || s.includes('not available') || s.includes('not specified')) return false;
  return true;
}

// ---- Windows ----

function readWindowsBiosSerial() {
  try {
    const out = execSync('wmic bios get serialnumber', { timeout: 5000, encoding: 'utf8' });
    for (const line of out.split(/\r?\n/)) {
      const t = line.trim();
      if (t && t.toLowerCase() !== 'serialnumber' && isMeaningful(t)) return t;
    }
  } catch { /* wmic 可能在新版 Windows 已移除 */ }
  try {
    const out = execSync('powershell -NoProfile -Command "(Get-CimInstance Win32_BIOS).SerialNumber"', { timeout: 10000, encoding: 'utf8' });
    const t = out.trim();
    if (isMeaningful(t)) return t;
  } catch { /* fall through */ }
  return null;
}

function readWindowsMachineGuid() {
  try {
    const out = execSync('reg query "HKLM\\SOFTWARE\\Microsoft\\Cryptography" /v MachineGuid', { timeout: 5000, encoding: 'utf8' });
    const m = out.match(/MachineGuid\s+REG_SZ\s+(.+)/);
    if (m) return m[1].trim();
  } catch { /* fall through */ }
  return null;
}

// ---- macOS ----

function readMacSerial() {
  try {
    const out = execSync('system_profiler SPHardwareDataType', { timeout: 10000, encoding: 'utf8' });
    const m = out.match(/Serial Number.*?:\s*(.+)/);
    if (m) return m[1].trim();
  } catch { /* fall through */ }
  return null;
}

function readMacPlatformUUID() {
  try {
    const out = execSync('ioreg -d2 -c IOPlatformPlatformDevice', { timeout: 10000, encoding: 'utf8' });
    const m = out.match(/"IOPlatformUUID"\s*=\s*"([^"]+)"/);
    if (m) return m[1].trim();
  } catch { /* fall through */ }
  return null;
}

// ---- Linux ----

function readLinuxHardwareSerial() {
  const paths = ['/sys/class/dmi/id/product_serial', '/sys/class/dmi/id/board_serial'];
  for (const p of paths) {
    try {
      const content = fs.readFileSync(p, 'utf8').trim();
      if (isMeaningful(content)) return content;
    } catch { /* 非root通常读不到 */ }
  }
  try {
    const out = execSync('dmidecode -s system-serial-number', { timeout: 5000, encoding: 'utf8' });
    const t = out.trim();
    if (isMeaningful(t)) return t;
  } catch { /* fall through */ }
  return null;
}

function readLinuxMachineId() {
  const paths = ['/etc/machine-id', '/var/lib/dbus/machine-id'];
  for (const p of paths) {
    try {
      const content = fs.readFileSync(p, 'utf8').trim();
      if (content) return content;
    } catch { /* fall through */ }
  }
  return null;
}

/**
 * HMAC 签名（software/generate、software/upgrade 必须）
 * 签名串：productUniqueCode \n machineCode \n edition \n expiryDays \n clientOrderId \n licenseCode \n timestamp
 */
export function sign(apiSecret, params) {
  const payload = [
    params.productUniqueCode ?? '',
    params.machineCode ?? '',
    params.edition ?? '',
    params.expiryDays ?? 0,
    params.clientOrderId ?? '',
    params.licenseCode ?? '',
    params.timestamp ?? '',
  ].join('\n');
  return toBase64Url(crypto.createHmac('sha256', apiSecret).update(payload).digest());
}

/** 校验缓存条目：60s 内复用服务端 verify 结果，过期/失败再联网 */
function cacheHit(cache, now) {
  return cache && now - cache.at < VERIFY_CACHE_TTL_MS ? cache.data : null;
}

export class LicenseClient {
  constructor({ baseUrl = DEFAULT_BASE_URL, apiSecret = '', productUniqueCode, fetchImpl = fetch, cacheTtlMs = VERIFY_CACHE_TTL_MS } = {}) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.apiSecret = apiSecret;
    this.productUniqueCode = productUniqueCode;
    this.fetchImpl = fetchImpl;
    this.cacheTtlMs = cacheTtlMs;
    this.verifyCache = null;
  }

  async request(path, body, { signed = false, headers = {} } = {}) {
    let payload = body ?? {};
    if (signed) {
      payload = { ...body, timestamp: Date.now() };
      payload.signature = sign(this.apiSecret, payload);
    }
    const resp = await this.fetchImpl(this.baseUrl + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers },
      body: JSON.stringify(payload),
    });
    const json = await resp.json().catch(() => ({}));
    if (!resp.ok || json.success !== true) {
      const err = new Error(json?.tip || `request failed: ${resp.status}`);
      err.errorCode = json?.content?.errorCode || json?.code || 'REQUEST_FAILED';
      throw err;
    }
    return json.content;
  }

  /** 激活：授权码 + 机器码 → licenseCode + activationToken */
  activate(licenseCode, machineCodeValue) {
    return this.request('/license/activate', { licenseCode, machineCode: machineCodeValue });
  }

  /** 校验：有效返回 { valid, edition, expiryTime } */
  verify(licenseCode, machineCodeValue, activationToken) {
    return this.request('/license/verify', { licenseCode, machineCode: machineCodeValue, activationToken });
  }

  /** 解绑（换机）：通常需登录态，浏览器场景由个人中心调用 */
  deactivate(licenseCode, machineCodeValue) {
    return this.request('/license/deactivate', { licenseCode, machineCode: machineCodeValue });
  }

  /** 先用后付：领取试用授权（产品须 TRIAL_FIRST） */
  claimTrial(machineCodeValue) {
    if (!this.productUniqueCode) throw new Error('productUniqueCode required');
    return this.request('/license/trial/claim', { productUniqueCode: this.productUniqueCode, machineCode: machineCodeValue });
  }

  /** 软件内支付后发码（HMAC 签名，幂等：clientOrderId） */
  generateForSoftware(params) {
    const body = {
      productUniqueCode: params.productUniqueCode ?? this.productUniqueCode,
      machineCode: params.machineCode,
      edition: params.edition,
      expiryDays: params.expiryDays ?? 0,
      clientOrderId: params.clientOrderId,
    };
    return this.request('/license/software/generate', body, { signed: true });
  }

  /** 软件内升级/续费（HMAC 签名，幂等：clientOrderId） */
  upgradeForSoftware(params) {
    const body = {
      productUniqueCode: params.productUniqueCode ?? this.productUniqueCode,
      licenseCode: params.licenseCode,
      machineCode: params.machineCode,
      edition: params.edition,
      expiryDays: params.expiryDays ?? 0,
      clientOrderId: params.clientOrderId,
    };
    return this.request('/license/software/upgrade', body, { signed: true });
  }

  /** 带 60s 缓存的校验：付费功能点击前调用 */
  async verifyCached(licenseCode, machineCodeValue, activationToken) {
    const now = Date.now();
    const hit = cacheHit(this.verifyCache, now);
    if (hit) return hit;
    const data = await this.verify(licenseCode, machineCodeValue, activationToken);
    this.verifyCache = { at: now, data };
    return data;
  }

  /**
   * 付费功能未授权时的购买页跳转 URL。
   * 产品标识：构造器 productUniqueCode（开发者中心唯一编码）。
   */
  purchaseUrl(machineCodeValue, { base = 'https://www.powersoftware.app' } = {}) {
    if (!this.productUniqueCode) throw new Error('productUniqueCode required');
    const u = new URL(`${base}/product/license/purchase`);
    u.searchParams.set('productUniqueCode', String(this.productUniqueCode));
    u.searchParams.set('machineCode', machineCodeValue);
    return u.toString();
  }
}

export default { machineCode, sign, LicenseClient };
