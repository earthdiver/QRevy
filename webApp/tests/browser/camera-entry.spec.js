import {clickInputAction,clickWorkspaceAction,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
import {readFile} from 'node:fs/promises';

async function workspace(page){
  await page.evaluate(()=>navigator.canShare=()=>false);
  const pending=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportSession');
  const session=JSON.parse(JSON.parse(await readFile(await(await pending).path(),'utf8')).payload);
  return {id:session.id,current:session.history[session.cursor]};
}
async function cameraFile(page,fileChoosers){
  await clickInputAction(page,'#cameraImport');
  await expect.poll(()=>fileChoosers.length).toBe(1);
  await fileChoosers.shift().setFiles('tests/fixtures/clean.png');
  await expect(page.locator('#cropApply')).toBeVisible();
}

// The browser's camera entry is exercised with a selected fixture. This proves the
// handoff and cancel/apply workflow, not physical camera permission or capture.
test('camera file entry opens the shared crop and cancellation preserves the existing work',async({page})=>{
  const fileChoosers=[];page.on('filechooser',chooser=>fileChoosers.push(chooser));
  await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();
  await page.locator('#textFile').setInputFiles('tests/fixtures/priority-after.txt');
  await expect(page.locator('#recover')).toBeEnabled();const before=await workspace(page);
  await cameraFile(page,fileChoosers);
  await page.locator('#cropBrightness').fill('40');
  await page.locator('#cropRotate').click();
  await page.locator('#cropCancel').click();
  const cancelled=await workspace(page);
  expect(cancelled.id).toBe(before.id);
  expect(cancelled.current.matrix).toEqual(before.current.matrix);
  expect(cancelled.current.settings).toEqual(before.current.settings);
  await cameraFile(page,fileChoosers);await page.locator('#cropApply').click();
  await expect(page.locator('#recover')).toBeEnabled();
  await expect(page.locator('#imageView')).toBeEnabled();
  await runRecovery(page);
  await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});
