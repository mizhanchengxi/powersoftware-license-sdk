import crypto from 'node:crypto';
import os from 'node:os';

const DEFAULT_BASE_URL = 'https://www.powersoftware.app/frontApi';
const VERIFY_CACHE_TTL_MS = 60 * 1000;

function toBase64Url(buf) {
  return Buffer.from(buf).toString('base64url');
}

/**
 * 机器码：跨语言一致算法（见 ps-help/v3/doc/授权SDK规范_v3.md）
 * fingerprint = hostname | os | arch | primaryMac，整体小写后 sha256
 */
export function machineCode() {
  const ifaces = Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && !i.internal && i.mac && i.mac !== '00:00:00:00:00:00');
  const mac = (ifaces[0]?.mac ?? '').replace(/:/g, '').toLowerCase();
  const fingerprint = [os.hostname(), os.platform(), os.arch(), mac].join('|').toLowerCase();
  const digest = crypto.createHash('sha256').update(fingerprint).digest();
  return 'M' + toBase64Url(digest).slice(0, 32);
}

/**
 * HMAC 签名（software/generate、software/upgrade 必须）
 * 签名串：productId \n machineCode \n edition \n expiryDays \n clientOrderId \n licenseCode \n timestamp
 */
export function sign(apiSecret, params) {
  const payload = [
    params.productId ?? '',
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
  constructor({ baseUrl = DEFAULT_BASE_URL, apiSecret = '', productId, fetchImpl = fetch, cacheTtlMs = VERIFY_CACHE_TTL_MS } = {}) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.apiSecret = apiSecret;
    this.productId = productId;
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
    if (!this.productId) throw new Error('productId required');
    return this.request('/license/trial/claim', { productId: this.productId, machineCode: machineCodeValue });
  }

  /** 软件内支付后发码（HMAC 签名，幂等：clientOrderId） */
  generateForSoftware(params) {
    const body = {
      productId: params.productId ?? this.productId,
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
      productId: params.productId ?? this.productId,
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
   * 产品标识二选一：构造器 productId（数字）或本方法 productUniqueCode（开发者中心唯一编码）。
   */
  purchaseUrl(machineCodeValue, { base = 'https://www.powersoftware.app', productUniqueCode } = {}) {
    if (!this.productId && !productUniqueCode) throw new Error('productId or productUniqueCode required');
    const u = new URL(`${base}/product/license/purchase`);
    if (this.productId) u.searchParams.set('productId', String(this.productId));
    if (productUniqueCode) u.searchParams.set('productUniqueCode', String(productUniqueCode));
    u.searchParams.set('machineCode', machineCodeValue);
    return u.toString();
  }
}

export default { machineCode, sign, LicenseClient };
