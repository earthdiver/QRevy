import {changeRecoverySetting,clickInputAction,clickWorkspaceAction,openAppMenu,openInputChoices,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
import {readFile} from 'node:fs/promises';

async function exported(page){
  await page.evaluate(()=>navigator.canShare=()=>false);
  const pending=page.waitForEvent('download');
  await clickWorkspaceAction(page,'#exportSession');
  const bytes=await readFile(await(await pending).path());
  const session=JSON.parse(JSON.parse(bytes.toString()).payload);
  return {bytes,session,current:session.history[session.cursor]};
}
async function selectCell(page,x,y){
  const canvas=page.locator('#canvas'),box=await canvas.boundingBox();
  await canvas.click({position:{x:(x+2.5)/25*box.width,y:(y+2.5)/25*box.height}});
}
async function importText(page,buffer){
  await page.goto('/');
  await expect(page.locator('#textImport')).toBeEnabled();
  await page.locator('#textFile').setInputFiles({name:'source-matrix.txt',mimeType:'text/plain',buffer});
  await expect(page.locator('#recover')).toBeEnabled();
}

test('text matrix image adjustment preserves original observations and manual provenance in a separate saved work',async({page},info)=>{
  const original=(await readFile('tests/fixtures/priority-after.txt','utf8')).trim().split('\n').map(row=>row.split(''));
  original[12][11]='?';
  await importText(page,Buffer.from(original.map(row=>row.join('')).join('\n')));
  await page.locator('[data-tab="edit"]').click();
  await selectCell(page,12,12);
  await page.locator(`[data-value="${original[12][12]==='1'?'0':'1'}"]`).click();
  await page.locator('#editKind').selectOption('1');
  await selectCell(page,13,12);
  await page.locator(`[data-value="${original[12][13]==='1'?'0':'1'}"]`).click();
  await page.locator('#editKind').selectOption('5');
  await selectCell(page,14,12);
  await page.locator('[data-value="*"]').click();
  const before=await exported(page);
  expect(before.session.image).toBeNull();
  await page.locator('[data-tab="input"]').click();
  await openInputChoices(page);await expect(page.locator('#matrixToImage')).toBeVisible();
  await clickInputAction(page,'#matrixToImage');
  await expect(page.locator('#recover')).toBeEnabled();
  await expect(page.locator('#imageView')).toBeEnabled();
  const after=await exported(page),settings=after.current.settings;
  expect(after.session.id).not.toBe(before.session.id);
  expect(after.current.matrix.text).toBe(before.current.matrix.text);
  expect(after.current.matrix.sources).toBe(before.current.matrix.sources);
  expect(settings.photographicSource).toBe(false);
  expect(settings.automatic).toBe(false);
  expect([settings.black,settings.white]).toEqual([64,192]);
  expect(settings.quad).toEqual([35.5,35.5,195.5,35.5,195.5,195.5,35.5,195.5]);
  expect(settings.finders.filter((_,i)=>i%3===0).sort()).toEqual([0,1,3]);
  // Inspect the exported image, not the preview's labels or edited overlay. A manual
  // change must not be baked into the source that "From image" will later restore.
  const pixels=await page.evaluate(async url=>{
    const img=new Image();img.src=url;await img.decode();
    const canvas=document.createElement('canvas');canvas.width=img.width;canvas.height=img.height;
    const ctx=canvas.getContext('2d');ctx.drawImage(img,0,0);
    const at=(x,y)=>Array.from(ctx.getImageData(x,y,1,1).data);
    return {width:img.width,height:img.height,quiet:at(0,0),unknown:at(4*8+11*8+3,4*8+12*8+3),fixed:at(4*8+12*8+3,4*8+12*8+3)};
  },after.session.image);
  expect([pixels.width,pixels.height]).toEqual([232,232]);
  expect(pixels.quiet).toEqual([255,255,255,255]);
  expect(pixels.unknown).toEqual([128,128,128,255]);
  expect(pixels.fixed).toEqual(Array(3).fill(original[12][12]==='1'?0:255).concat(255));
  await page.locator('[data-tab="edit"]').click();
  await page.locator('#matrixView').click();
  for(const x of [12,13,14]){await selectCell(page,x,12);await page.locator('#restoreCell').click();}
  const restored=await exported(page);
  expect(restored.current.matrix.text).toBe(original.map(row=>row.join('')).join('\n'));
  await runRecovery(page);
  await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
  await page.screenshot({path:info.outputPath('text-source-image-recovered.png')});
  await openAppMenu(page);await page.locator('#sessionsButton').click();
  await expect(page.locator('#sessionList .session-item')).toHaveCount(2);
  await page.locator('#sessionList .session-item').nth(1).locator('button').first().click();
  const old=await exported(page);
  expect(old.session.id).toBe(before.session.id);
  expect(old.session.image).toBeNull();
  expect(old.current.matrix.text).toBe(before.current.matrix.text);
  expect(old.current.matrix.sources).toBe(before.current.matrix.sources);
});

test('a text source can be resampled at another version and undo and workspace import preserve its image basis',async({page})=>{
  await importText(page,await readFile('tests/fixtures/priority-before.txt'));
  // The upstream damaged fixture needs its documented single-segment assumption.
  await changeRecoverySetting(page,'#single','check');
  await clickInputAction(page,'#matrixToImage');
  await expect(page.locator('#recover')).toBeEnabled();
  const before=await exported(page);
  page.once('dialog',dialog=>dialog.accept());
  await page.locator('#version').selectOption('2');
  await expect(page.locator('#recover')).toBeEnabled();
  const changed=await exported(page);
  expect(changed.current.matrix.text.split('\n')).toHaveLength(25);
  expect(changed.current.matrix.text.replace(/\n/g,'')).toMatch(/[01]/);
  await page.locator('[data-tab="edit"]').click();
  // Export keeps the cursor, so one undo restores the preceding version.
  await page.locator('#undo').click();
  const undone=await exported(page);
  expect(undone.current.settings.version).toBe(1);
  expect(undone.current.settings.quad).toEqual(before.current.settings.quad);
  expect(undone.current.settings.finders).toEqual(before.current.settings.finders);
  expect(undone.current.settings.photographicSource).toBe(false);
  expect(undone.current.matrix.text).toBe(before.current.matrix.text);
  expect(undone.current.matrix.sources).toBe(before.current.matrix.sources);
  await page.locator('#sessionFile').setInputFiles({name:'text-as-image.qrpwa',mimeType:'application/json',buffer:undone.bytes});
  await expect(page.locator('#imageView')).toBeEnabled();
  const reloaded=await exported(page);
  expect(reloaded.session.imageHash).toBe(undone.session.imageHash);
  expect(reloaded.current.settings.quad).toEqual(before.current.settings.quad);
  expect(reloaded.current.settings.photographicSource).toBe(false);
  expect(reloaded.current.matrix.text).toBe(before.current.matrix.text);
  expect(reloaded.current.matrix.sources).toBe(before.current.matrix.sources);
  await runRecovery(page);
  await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});
