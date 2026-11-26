import {test, expect} from '@playwright/test';
import {openResultData, runRecovery} from './input-helpers.js';

test.use({serviceWorkers: 'block'});

test('forced stop preserves prior results and supports inspection and recovery after restart', async ({page}) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => localStorage.setItem('qrevy-automatic-repair', 'false'));
  // Let the first recovery finish, then hold the next one even after a cooperative stop.
  let workerLoads = 0;
  await page.route('**/worker.js', route => route.fulfill({
    contentType: 'text/javascript',
    headers: {'Cross-Origin-Embedder-Policy': 'require-corp'},
    body: `import {execute} from './engine/js/engine.js';
      let recoveries = 0;
      self.onmessage = ({data}) => {
        if (${workerLoads++ === 0} && data.request.action === 'recover' && ++recoveries === 2) {
          self.postMessage({progress: 'Waiting for forced stop'});
          return;
        }
        try { self.postMessage({id: data.id, result: JSON.parse(execute(data.request))}); }
        catch (error) { self.postMessage({id: data.id, error: String(error)}); }
      };
      self.postMessage({ready: true});`,
  }));
  await page.goto('/');
  const payload = 'Worker lifecycle';
  const matrix = await page.evaluate(async text => {
    const {execute} = await import('/engine/js/engine.js');
    return JSON.parse(execute({action: 'demo', text, damage: true})).text;
  }, payload);
  await page.locator('#textFile').setInputFiles({name: 'worker-lifecycle.txt', mimeType: 'text/plain', buffer: Buffer.from(matrix)});
  await expect(page.locator('#recover')).toBeEnabled();
  await runRecovery(page);
  await expect(page.locator('#payload')).toHaveValue(payload);
  const savedInput = await page.evaluate(async () => {
    const session = (await (await import('/storage.js')).listSessions())[0];
    return session.history[session.cursor].matrix;
  });
  await runRecovery(page);
  await expect(page.locator('#progressText')).toContainText('Waiting for forced stop');
  const cooperative = await page.evaluate(() => typeof SharedArrayBuffer !== 'undefined' && crossOriginIsolated);
  await page.locator('#cancel').click();
  if (cooperative) {
    await expect(page.locator('#forceStopHelp')).toBeVisible();
    await page.locator('#cancel').click();
  }
  await expect(page.locator('#progress')).toBeHidden();
  await expect(page.locator('#recover')).toBeEnabled();
  await page.locator('[data-tab="results"]').click();
  await expect(page.locator('#payload')).toHaveValue(payload);
  await expect(page.locator('#copyPayload')).toBeEnabled();
  // Inspection must rebuild the previous worker's candidate handles.
  await openResultData(page);
  await expect(page.locator('#hex')).not.toBeEmpty();
  await expect(page.locator('#progress')).toBeHidden();
  const restoredInput = await page.evaluate(async () => {
    const session = (await (await import('/storage.js')).listSessions())[0];
    return session.history[session.cursor].matrix;
  });
  expect(restoredInput).toEqual(savedInput);
  await runRecovery(page);
  await expect(page.locator('#progress')).toBeHidden();
  await expect(page.locator('#payload')).toHaveValue(payload);
  expect(errors).toEqual([]);
});
