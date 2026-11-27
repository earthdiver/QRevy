import {continueAfterOrdinaryReading} from './input-helpers.js';
import {openResultData} from './input-helpers.js';
import {changeRecoverySetting,clickWorkspaceAction,openAppMenu,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';
test.beforeEach(async({page})=>{await page.addInitScript(()=>{localStorage.setItem('qrevy-automatic-repair','false');localStorage.setItem('qrevy-language','ja');});});
test('basic image adjustments remain together and advanced settings are reachable',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();
 await expect(page.locator('#basicGeometry #version')).toBeVisible();await expect(page.locator('#basicGeometry #fineCorners')).toBeVisible();await expect(page.locator('#basicReading #blackRange')).toBeVisible();await expect(page.locator('#inputChoices')).not.toHaveAttribute('open','');await expect(page.locator('#repairDetails')).not.toHaveAttribute('open','');
 await page.locator('#black').fill('90');await page.locator('#black').blur();await expect(page.locator('#blackRange')).toHaveValue('90');await expect(page.locator('#recover')).toBeEnabled();await page.locator('#repairDetails > summary').click();await changeRecoverySetting(page,'#utf8','check');await expect(page.locator('#repairSummary')).toHaveCount(0);await expect(page.locator('#utf8')).toBeChecked();
 await page.locator('[data-tab="edit"]').click();await page.locator('#prioritySettings').click();await expect(page.locator('#priorityLimit')).toBeVisible();await expect(page.locator('body')).toHaveAttribute('data-active-tab','edit');await page.locator('#priorityLimit').selectOption('6');await page.locator('#closePriority').click();await page.locator('[data-tab="input"]').click();await expect(page.locator('#priorityLimit')).toHaveValue('6');await expect(page.locator('#utf8')).toBeChecked();await page.locator('#fineCorners').click();await page.locator('#cornerApply').click();await expect(page.locator('#recover')).toBeEnabled();expect(errors).toEqual([]);
});
test('results prioritize content, preserve candidates through inspection and offer common work actions',async({page},info)=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/two-candidates.txt');await expect(page.locator('#recover')).toBeEnabled();await page.locator('#repairDetails > summary').click();await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('A');
 await expect(page.locator('#resultInspection')).not.toBeVisible();await expect(page.locator('#sameCodeword')).toBeVisible();await page.locator('#candidatePicker').selectOption('1');await expect(page.locator('#payload')).toHaveValue('B');
 if(info.project.use.isMobile){await page.evaluate(()=>scrollTo(0,0));const payload=await page.locator('#payload').boundingBox();expect(payload.y).toBeLessThan(await page.evaluate(()=>innerHeight));}
 await openResultData(page);await expect(page.locator('#dataStage')).toHaveValue('after');await expect(page.locator('#hex')).not.toBeEmpty();await expect(page.locator('#sameCodeword')).toBeVisible();await expect(page.locator('#payload')).toHaveValue('B');await openAppMenu(page);await page.locator('#workMenuButton').click();await clickWorkspaceAction(page,'#save');await expect(page.locator('#workMenu')).not.toBeVisible();await page.locator('#languageButton').click();await expect(page.locator('#workMenuButton')).toHaveText('Current workspace');await expect(page.locator('#payload')).toHaveValue('B');expect(errors).toEqual([]);
});

test('controls follow the task and primary cell actions fit the phone viewport',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await expect(page.locator('#automaticDiagnostics')).toBeVisible();await expect(page.locator('#automaticOptions')).toHaveCount(0);
 await expect(page.locator('#quietZone')).toHaveCount(0);await expect(page.locator('#cropImage')).toHaveCount(0);
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();
 await expect(page.locator('#finderSettings #clearFinders')).toBeVisible();await expect(page.locator('#gridCorrection #refine')).toBeVisible();await expect(page.locator('#gridCorrection #curved')).toBeVisible();await expect(page.locator('#imageSettings .image-adjustment-body > #exportSource')).toHaveCount(1);
 await page.locator('[data-tab="edit"]').click();await page.evaluate(()=>scrollTo(0,0));
 for(const selector of ['[data-value="0"]','[data-value="*"]','#undo','#redo']){const box=await page.locator(selector).boundingBox();expect(box.y+box.height).toBeLessThanOrEqual(await page.evaluate(()=>innerHeight));}
 const last=await page.locator('#redo').boundingBox(),extra=await page.locator('#prioritySettings').boundingBox();expect(extra.y).toBeGreaterThan(last.y);
 await page.locator('#prioritySettings').click();await expect(page.locator('#priorityDialog')).toBeVisible();await page.locator('#closePriority').click();
 await page.locator('[data-tab="input"]').click();await expect(page.locator('#prioritySettings')).not.toBeVisible();expect(errors).toEqual([]);
});

for(const language of ['ja','en'])for(const appearance of ['classic','modern'])test(`narrow editor remains usable in ${language} ${appearance}`,async({page},info)=>{
 await page.addInitScript(({language,appearance})=>{localStorage.setItem('qrevy-language',language);localStorage.setItem('qrevy-appearance',appearance);},{language,appearance});
 await page.setViewportSize({width:320,height:700});await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();await page.locator('[data-tab="edit"]').click();await page.evaluate(()=>scrollTo(0,0));
 expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(321);
 const undo=await page.locator('#undo').boundingBox();expect(undo.y+undo.height).toBeLessThanOrEqual(700);for(const id of ['undo','redo','restoreCell']){const b=await page.locator('#'+id).boundingBox();expect(b.x+b.width).toBeLessThanOrEqual(320);}
 await page.locator('#fitView').click();const qr=await page.locator('#canvas').boundingBox(),viewport=await page.locator('#canvasScroll').boundingBox();expect(qr.height).toBeLessThanOrEqual(viewport.height);expect(qr.width).toBeLessThanOrEqual(viewport.width);await page.screenshot({path:info.outputPath('editor.png')});
 await page.setViewportSize({width:640,height:360});await page.locator('#prioritySettings').click();await expect(page.locator('#priorityLimit')).toBeVisible();await page.locator('#closePriority').click();
});
