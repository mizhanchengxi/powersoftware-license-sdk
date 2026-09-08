import { test } from 'node:test';
import assert from 'node:assert/strict';
import { machineCode, LicenseClient } from '../src/webextension.js';

/** 模拟 chrome.storage（Promise 风格，MV3 行为） */
function memArea() {
  const data = new Map();
  return {
    data,
    async get(keys) {
      const r = {};
      for (const k of keys) if (data.has(k)) r[k] = data.get(k);
      return r;
    },
    async set(obj) {
      for (const [k, v] of Object.entries(obj)) data.set(k, v);
    },
  };
}

function mockChrome() {
  const storage = { local: memArea(), sync: memArea() };
  globalThis.chrome = { storage };
  return storage;
}

test('machineCode returns EXT- prefixed id and persists to local + sync', async () => {
  const storage = mockChrome();
  const id = await machineCode();
  assert.match(id, /^EXT-/);
  assert.ok(id.length >= 8);
  assert.equal(await machineCode(), id, 'stable across calls');
  assert.equal(storage.local.data.get('psInstallId'), id);
  assert.equal(storage.sync.data.get('psInstallId'), id);
});

test('sync id wins over conflicting local id (follows browser account)', async () => {
  const storage = mockChrome();
  storage.sync.data.set('psInstallId', 'EXT-sync-user-id');
  storage.local.data.set('psInstallId', 'EXT-local-device-id');
  const id = await machineCode();
  assert.equal(id, 'EXT-sync-user-id');
});

test('regenerates when stored id lacks EXT- prefix', async () => {
  const storage = mockChrome();
  storage.local.data.set('psInstallId', 'not-a-plugin-id');
  const id = await machineCode();
  assert.match(id, /^EXT-/);
});

test('storage unavailable throws with guidance', async () => {
  delete globalThis.chrome;
  await assert.rejects(() => machineCode(), /WebExtension storage unavailable/);
});

test('LicenseClient (webextension) purchaseUrl carries params', () => {
  mockChrome();
  const c = new LicenseClient({ productUniqueCode: 'PRO-2026-001' });
  const url = c.purchaseUrl('EXT-abc', { base: 'https://www.powersoftware.app' });
  assert.match(url, /productUniqueCode=PRO-2026-001/);
  assert.match(url, /machineCode=EXT-abc/);
});
