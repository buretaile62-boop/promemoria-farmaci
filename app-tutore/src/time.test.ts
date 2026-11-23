import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ago, parseTimes } from './time.ts';

test('parseTimes normalizza e ordina gli orari', () => {
  assert.deepEqual(parseTimes('20:00, 8.00 13:30'), ['08:00', '13:30', '20:00']);
  assert.deepEqual(parseTimes('8:00,8:00'), ['08:00']);
  assert.equal(parseTimes('25:00'), null);
  assert.equal(parseTimes('otto'), null);
  assert.equal(parseTimes(''), null);
});

test('ago descrive il tempo trascorso', () => {
  const now = Date.parse('2026-11-23T12:00:00Z');
  assert.equal(ago(null, now), 'mai');
  assert.equal(ago('2026-11-23T11:59:30Z', now), 'adesso');
  assert.equal(ago('2026-11-23T11:30:00Z', now), '30 minuti fa');
  assert.equal(ago('2026-11-23T11:00:00Z', now), '1 ora fa');
  assert.equal(ago('2026-11-20T12:00:00Z', now), '3 giorni fa');
});
