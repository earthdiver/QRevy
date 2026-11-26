import {clickWorkspaceAction,openAppMenu,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
import {readFile} from 'node:fs/promises';

async function savedState(page){
  await page.evaluate(()=>navigator.canShare=()=>false);
  await openAppMenu(page);await page.locator('#workMenuButton').click();
  const pending=page.waitForEvent('download');
  await clickWorkspaceAction(page,'#exportSession');
  const envelope=JSON.parse(await readFile(await(await pending).path(),'utf8'));
  const session=JSON.parse(envelope.payload);
  return session.history[session.cursor];
}

test('changing a rectified version preserves observed Finder positions and undo restores the image basis',async({page})=>{
  await page.goto('/');
  await expect(page.locator('#imageImport')).toBeEnabled();
  await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');
  await page.locator('#cropApply').click();
  await expect(page.locator('#recover')).toBeEnabled();
  await page.locator('#imageSettings > summary').click();
  await page.locator('.image-adjustment-body > details').first().locator(':scope > summary').click();
  await page.locator('#rectifyImage').click();
  await expect(page.locator('#recover')).toBeEnabled();
  await expect(page.locator('#orientationNotice')).toBeHidden();
  const rectified=(await savedState(page)).settings;
  expect(rectified.finders.length).toBeGreaterThan(0);
  await page.locator('#clearFinders').click();await expect(page.locator('#recover')).toBeDisabled();

  const [x,y]=rectified.grid[3*21+3],canvas=page.locator('#canvas');
  const intrinsic=await canvas.evaluate(el=>({width:Number(el.dataset.imageWidth),height:Number(el.dataset.imageHeight)}));
  await page.locator('#finderRole').selectOption('0');
  await canvas.scrollIntoViewIfNeeded();
  const box=await canvas.boundingBox();
  await canvas.click({position:{x:x/intrinsic.width*box.width,y:y/intrinsic.height*box.height}});
  await expect(page.locator('#recover')).toBeEnabled();
  await expect(page.locator('#orientationNotice')).toBeHidden();
  const before=await savedState(page);
  expect(before.settings.finders).toHaveLength(3);

  page.once('dialog',dialog=>dialog.accept());
  await page.locator('#version').selectOption('2');
  await expect(page.locator('#recover')).toBeEnabled();
  const after=await savedState(page),a=after.settings,b=before.settings;
  expect(a.imageOps.find(op=>op.type==='source').version).toBe(2);
  expect(a.quad).not.toEqual(b.quad);
  expect(a.finders[0]).toBe(b.finders[0]);
  // Finder observations retain their source-image position even when changing
  // version disables LM and replaces its fitted quad with the manual base.
  const sourcePoints=await page.evaluate(async({a,b})=>{
    const {imageTransform}=await import('/image-tools.js');
    return [a,b].map(s=>imageTransform(232,232,s.imageOps).toSource(s.finders[1],s.finders[2]));
  },{a,b});
  sourcePoints[0].forEach((v,i)=>expect(v).toBeCloseTo(sourcePoints[1][i],6));
  expect(a.finders.slice(1)).not.toEqual(b.finders.slice(1));
  await page.locator('[data-tab="edit"]').click();
  // Export does not change history; undo the version edit.
  await page.locator('#undo').click();
  const restored=await savedState(page);
  expect(restored.settings.version).toBe(1);
  expect(restored.settings.imageOps).toEqual(b.imageOps);
  expect(restored.settings.quad).toEqual(b.quad);
  expect(restored.settings.finders).toEqual(b.finders);
  expect(restored.matrix.text).toBe(before.matrix.text);
  await runRecovery(page);
  await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});
