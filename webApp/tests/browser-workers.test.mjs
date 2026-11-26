import test from 'node:test';
import assert from 'node:assert/strict';
import {browserWorkers} from '../scripts/browser-workers.mjs';

test('browser concurrency leaves CPU capacity and stays bounded on large hosts', () => {
  for (const [cpus, workers] of [[1, 1], [2, 1], [3, 1], [4, 2], [6, 3], [8, 4], [64, 4]]) {
    assert.equal(browserWorkers(cpus, ''), workers);
  }
});

test('explicit concurrency supports serial runs and Playwright percentages', () => {
  assert.equal(browserWorkers(8, '1'), 1);
  assert.equal(browserWorkers(2, '6'), 6);
  assert.equal(browserWorkers(8, '25%'), '25%');
});

test('invalid concurrency fails instead of silently choosing a different test run', () => {
  for (const override of ['0', '-1', '0%', '1.5', 'NaN', 'many', '9007199254740992']) {
    assert.throws(() => browserWorkers(8, override), /QREVY_BROWSER_WORKERS/);
  }
});
