import {openResultData} from './input-helpers.js';
import {openWorkspaceMenu,changeEvidenceControl,changeRecoverySetting,clickInputAction,clickWorkspaceAction,openRecoverySettings,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
import {readFile} from 'node:fs/promises';

async function loadTailFailure(page){
  await page.goto('/');
  await expect(page.locator('#textImport')).toBeEnabled();
  await page.locator('#textFile').setInputFiles('tests/fixtures/tail-alternatives.txt');
  await expect(page.locator('#recover')).toBeEnabled();
  await changeRecoverySetting(page,'#ecc','selectOption','L');
  await changeRecoverySetting(page,'#mask','selectOption','0');
  await changeRecoverySetting(page,'#list','selectOption','0');
  await runRecovery(page);
  await expect(page.locator('#resultCount')).toHaveText('0');
  await expect(page.locator('#diagnostic')).toContainText('RS訂正後の本文');
}

async function workspace(page){
  await page.evaluate(()=>navigator.canShare=()=>false);
  const event=page.waitForEvent('download');
  await clickWorkspaceAction(page,'#exportSession');
  const bytes=await readFile(await(await event).path());
  return {bytes,session:JSON.parse(JSON.parse(bytes.toString()).payload)};
}

test('failed recovery retains inspectable best-attempt data after draft settings change',async({page})=>{
  await loadTailFailure(page);
  await page.locator('#resultEvidenceTab').click();
  await expect(page.locator('#resultDataPanel')).toBeVisible();
  await page.locator('#resultDataPanel > summary').click();
  await expect(page.locator('#dataStage')).toHaveValue('before');
  await expect(page.locator('#dataUnmask')).toBeChecked();
  await expect(page.locator('#dataPartial')).toBeChecked();
  await expect(page.locator('#hex')).toContainText('部分情報');
  await page.locator('#dataPartial').uncheck();
  await openResultData(page);
  await expect(page.locator('#hex')).toContainText('Binary');
  await expect(page.locator('#hex')).toContainText('ECC L / mask 0');
  const before=await page.locator('#hex').textContent();
  await page.locator('#dataUnmask').uncheck();
  await expect(page.locator('#hex')).not.toHaveText(before);
  await page.locator('#dataUnmask').check();
  await page.locator('#dataPartial').check();
  await expect(page.locator('#hex')).toContainText(/部分|Partial/);
  await page.locator('#dataPartial').uncheck();
  await page.locator('#dataStage').selectOption('after');
  await expect(page.locator('#hex')).toContainText(/検証済み.*候補.*ありません|No payload-validated repaired candidate/);
  await page.locator('#dataStage').selectOption('before');
  await page.locator('button[data-tab="input"]').click();
  await changeRecoverySetting(page,'#mask','selectOption','1');
  await page.locator('button[data-tab="results"]').click();
  await page.locator('#resultEvidenceTab').click();
  await openResultData(page);
  await expect(page.locator('#hex')).toHaveText(before);
  await expect(page.locator('#copyPayload')).not.toBeVisible();
  await expect(page.locator('#exportPng')).not.toBeVisible();
});

test('invalid manual error limits cannot poison saved or exported work',async({page})=>{
  await page.goto('/');
  await expect(page.locator('#textImport')).toBeEnabled();
  await page.locator('#textFile').setInputFiles('tests/fixtures/priority-before.txt');
  await expect(page.locator('#recover')).toBeEnabled();
await openRecoverySettings(page);  for(const id of ['formatLimit','dataLimit']){
    await page.locator('#'+id+'Mode').selectOption('MANUAL');
    const previous=await page.locator('#'+id).inputValue();
    for(const invalid of ['-1','1.5',id==='formatLimit'?'31':'209','']){
      await page.locator('#'+id).fill(invalid);
      await page.locator('#'+id).press('Tab');
      await expect(page.locator('#'+id)).toHaveValue(previous);
    }
  }
  const {bytes,session}=await workspace(page);
  for(const history of session.history){
    expect(history.settings.formatLimit).toBeGreaterThanOrEqual(0);
    expect(history.settings.formatLimit).toBeLessThanOrEqual(30);
    expect(Number.isInteger(history.settings.formatLimit)).toBe(true);
    expect(history.settings.dataLimit).toBeGreaterThanOrEqual(0);
    expect(history.settings.dataLimit).toBeLessThanOrEqual(208);
    expect(Number.isInteger(history.settings.dataLimit)).toBe(true);
  }
  await page.locator('#sessionFile').setInputFiles({name:'validated-settings.qrpwa',mimeType:'application/json',buffer:bytes});
  await expect(page.locator('#toast')).toContainText(/再開|resumed/i);
  await expect(page.locator('#dimensions')).toContainText('V1');
});

test('history identifies settings and source-only changes before choosing a restore point',async({page})=>{
  await page.goto('/');
  await clickInputAction(page,'#demo');
  await expect(page.locator('#recover')).toBeEnabled();
  await changeRecoverySetting(page,'#utf8','check');
  await changeRecoverySetting(page,'#utf8','uncheck');
  await page.locator('button[data-tab="edit"]').click();
  const canvas=page.locator('#canvas'),box=await canvas.boundingBox();
  await canvas.click({position:{x:(20+2.5)/37*box.width,y:(20+2.5)/37*box.height}});
  await page.locator('[data-value="1"]').click();
  await page.locator('#editKind').selectOption('5');
  await openWorkspaceMenu(page);
  await page.locator('details').filter({has:page.locator('#history')}).locator(':scope > summary').click();
  const history=page.locator('#history');
  await expect(history).toContainText(/\d{2}:\d{2}:\d{2}/);
  await expect(history).toContainText(/UTF-8|utf8/i);
  await expect(history.locator('button').last()).toContainText(/Δ\s*1\s*(セル|cells)/);
  await page.locator('#closeWorkMenu').click();await page.locator('#languageButton').click();await openWorkspaceMenu(page);
  await expect(history).toContainText('Assume UTF-8 text in Byte mode');
  await expect(history.locator('button').last()).toContainText(/Δ\s*1\s*cells/);
  await page.locator('#closeWorkMenu').click();await page.locator('#languageButton').click();await openWorkspaceMenu(page);
  await expect(history).toContainText('バイトモードの内容を UTF-8 文字列と仮定する');
  const {bytes}=await workspace(page);
  await page.locator('#sessionFile').setInputFiles({name:'history-details.qrpwa',mimeType:'application/json',buffer:bytes});
  await page.locator('button[data-tab="edit"]').click();
  await expect(history).toContainText(/UTF-8|utf8/i);
  await expect(history).toContainText(/Δ\s*1\s*(セル|cells)/);
  const time=(await history.textContent()).match(/\d{2}:\d{2}:\d{2}/)[0];
  await page.evaluate(()=>navigator.canShare=()=>false);
  const download=page.waitForEvent('download');
  await clickWorkspaceAction(page,'#exportQrr');
  const qrr=await readFile(await(await download).path());
  await page.locator('#sessionFile').setInputFiles({name:'history-details.qrr',mimeType:'application/octet-stream',buffer:qrr});
  await expect(page.locator('#viewTitle')).toHaveText('QRR');
  await expect(page.locator('#recover')).toBeEnabled();
  await page.locator('button[data-tab="edit"]').click();
  await expect(history).toContainText(time);
  await expect(history).toContainText(/UTF-8|utf8/i);
  await expect(history).toContainText(/Δ\s*1\s*(セル|cells)/);
  // Saving the imported QRR creates new metadata even when matrix/settings are unchanged.
  await clickWorkspaceAction(page,'#save');
  const savedEntry=await history.locator('button').last().textContent();
  const again=page.waitForEvent('download');
  await clickWorkspaceAction(page,'#exportQrr');
  const rewritten=await readFile(await(await again).path());
  await page.locator('#sessionFile').setInputFiles({name:'history-resaved.qrr',mimeType:'application/octet-stream',buffer:rewritten});
  await expect(page.locator('#toast')).toContainText(/再開|resumed/i);
  await page.locator('button[data-tab="edit"]').click();
  await expect(history.locator('button').last()).toHaveText(savedEntry);
});

test('same-codeword inspection explains the selected bit and RS block in both languages',async({page})=>{
  await page.goto('/');
  await clickInputAction(page,'#demo');
  await expect(page.locator('#recover')).toBeEnabled();
  await runRecovery(page);
  await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
  const canvas=page.locator('#canvas'),box=await canvas.boundingBox();
  await canvas.click({position:{x:(32+2.5)/37*box.width,y:(32+2.5)/37*box.height}});
  await changeEvidenceControl(page,'#calculationDetails','check');
  await changeEvidenceControl(page,'#sameCodeword','check');
  const detail=page.locator('#resultCellDetail');
  await expect(detail).toContainText('コード語1');
  await expect(detail).toContainText('RSブロック');
  await expect(detail).toContainText('マスク解除後：');
  await expect(detail).toContainText('選択中：左から1番目のbit');
  await page.locator('#languageButton').click();
  await expect(detail).toContainText('Codeword 1');
  await expect(detail).toContainText('RS block');
  await expect(detail).toContainText('Selected: bit 1 from the left');
  await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
});

test('discarding a branched draft returns to the explicit saved state',async({page})=>{
  await page.goto('/');
  await clickInputAction(page,'#demo');
  await expect(page.locator('#recover')).toBeEnabled();
  await changeRecoverySetting(page,'#utf8','check');
  await clickWorkspaceAction(page,'#save');
  await expect(page.locator('#saveStatus')).toContainText(/保存済み|Saved on this device/);
  await page.locator('button[data-tab="edit"]').click();
  await openWorkspaceMenu(page);
  await page.locator('details').filter({has:page.locator('#history')}).locator(':scope > summary').click();
  await openWorkspaceMenu(page);
  const section=page.locator('details').filter({has:page.locator('#history')});
  if(!await section.evaluate(el=>el.open))await section.locator(':scope > summary').click();
  await page.locator('#history button').first().click();
  await page.locator('#closeWorkMenu').click();
  await page.locator('button[data-tab="input"]').click();
  await expect(page.locator('#utf8')).not.toBeChecked();
  await changeRecoverySetting(page,'#known','selectOption','1');
  page.once('dialog',dialog=>dialog.accept());
  await clickWorkspaceAction(page,'#discardDraft');
  await expect(page.locator('#utf8')).toBeChecked();
  await expect(page.locator('#known')).toHaveValue('0');
  await page.locator('button[data-tab="edit"]').click();
  await openWorkspaceMenu(page);
  if(!await section.evaluate(el=>el.open))await section.locator(':scope > summary').click();
  await page.locator('#history button').first().click();
  await page.locator('#closeWorkMenu').click();
  await page.locator('button[data-tab="input"]').click();
  await changeRecoverySetting(page,'#known','selectOption','2');
  const {bytes}=await workspace(page);
  await page.locator('#sessionFile').setInputFiles({name:'saved-checkpoint.qrpwa',mimeType:'application/json',buffer:bytes});
  await expect(page.locator('#toast')).toContainText(/再開|resumed/i);
  await expect(page.locator('#known')).toHaveValue('2');
  page.once('dialog',dialog=>dialog.accept());
  await clickWorkspaceAction(page,'#discardDraft');
  await expect(page.locator('#utf8')).toBeChecked();
  await expect(page.locator('#known')).toHaveValue('0');
});
