import { test } from 'node:test';
import assert from 'node:assert/strict';
import { machineCode, sign, LicenseClient } from '../src/index.js';

test('machineCode stable & long enough', () => {
  assert.equal(machineCode(), machineCode());
  assert.ok(machineCode().length >= 8);
});

test('sign deterministic', () => {
  const params = { productId: 1, machineCode: 'M123', edition: 'PRO', expiryDays: 0, clientOrderId: 'x', licenseCode: '', timestamp: 1000 };
  assert.equal(sign('secret', params), sign('secret', params));
});

test('purchaseUrl carries params', () => {
  const c = new LicenseClient({ productId: 88 });
  const url = c.purchaseUrl('MABC', { base: 'https://www.powersoftware.app' });
  assert.match(url, /productId=88/);
  assert.match(url, /machineCode=MABC/);
});

test('purchaseUrl supports productUniqueCode', () => {
  const c = new LicenseClient({});
  const url = c.purchaseUrl('MABC', { productUniqueCode: 'PRO-2026-001' });
  assert.match(url, /productUniqueCode=PRO-2026-001/);
  assert.match(url, /machineCode=MABC/);
  assert.doesNotMatch(url, /productId=/);
});
