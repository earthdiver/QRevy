import {assignFinderOrientation} from './input-helpers.js';
import {changeEvidenceControl,changeRecoverySetting,clickInputAction,clickWorkspaceAction,openAppMenu,releaseFinderConstraints,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
import {readFile} from 'node:fs/promises';
test('repair, edit history, session restore, offline restart, export',async({page,context},testInfo)=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/');
 await expect(page.getByRole('heading',{name:/QRコードの救世主/})).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 await clickInputAction(page,'#demo');await expect(page.locator('#dimensions')).toContainText('V4');
 await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await page.screenshot({path:`test-results/${testInfo.project.name}-result.png`,fullPage:true});
 await page.locator('[data-tab="edit"]').click();
 const c=page.locator('#canvas'),box=await c.boundingBox();await c.click({position:{x:box.width*.5,y:box.height*.5}});
 await page.locator('[data-value="?"]').click();await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*\?/);
 await page.locator('#undo').click();await page.locator('#redo').click();await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*\?/);
 const downloadEvent=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportText');const download=await downloadEvent;expect(download.suggestedFilename()).toBe('qrevy-matrix.txt');
 await clickWorkspaceAction(page,'#save');await expect(page.locator('#saveStatus')).toContainText('保存済み');
 await page.evaluate(()=>navigator.serviceWorker.ready);await context.setOffline(true);await page.reload();
 await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList button').filter({hasText:'損傷サンプル'}).click();
 await expect(page.locator('#dimensions')).toContainText('V4');await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 expect(errors).toEqual([]);
});
test('upstream damaged matrix, fixed cells, QRR export and import',async({page})=>{
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/priority-before.txt');await expect(page.locator('#dimensions')).toContainText('V1');
 await changeRecoverySetting(page,'#single','check');await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
 await page.locator('[data-tab="edit"]').click();
 // Disable share for a deterministic file download in mobile emulation.
 await page.evaluate(()=>{navigator.canShare=()=>false;});
 const downloadEvent=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportQrr');const download=await downloadEvent;
 const path=await download.path();await page.locator('[data-tab="input"]').click();await page.locator('#sessionFile').setInputFiles({name:'session.qrr',mimeType:'application/octet-stream',buffer:await readFile(path)});
 await expect(page.locator('#viewTitle')).toHaveText('QRR');await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});
test('photo import, tone adjustment, full workspace backup and restore',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/');
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await expect(page.locator('#dimensions')).toContainText('V1');
 await expect(page.locator('#recover')).toBeEnabled();await assignFinderOrientation(page);await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
 await page.locator('[data-tab="input"]').click();await page.locator('#imageSettings > summary').click();await page.locator('#brightness').fill('10');await page.locator('#brightness').dispatchEvent('change');await expect(page.locator('#recover')).toBeEnabled();
 await page.evaluate(()=>{navigator.canShare=()=>false;});const event=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportSession');const download=await event;
 await page.locator('#sessionFile').setInputFiles({name:'work.qrpwa',mimeType:'application/json',buffer:await readFile(await download.path())});
 await expect(page.locator('#brightness')).toHaveValue('10');await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');expect(errors).toEqual([]);
});
test('language changes leave recovered text unchanged and selection restores',async({page})=>{
 await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await page.locator('#languageButton').click();await expect(page.locator('#recover')).toHaveText(/Repair/);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await page.reload();await expect(page.locator('#recover')).toHaveText(/Repair/);await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList button').first().click();await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
});

test('crop draft, enlarged corner nudges, perspective and image rotation retain work history',async({page})=>{
 await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#dimensions')).toContainText('V4');
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await expect(page.locator('#cropCanvas')).toBeVisible();await page.locator('#cropCancel').click();await expect(page.locator('#dimensions')).toContainText('V4');
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropX').fill('10');await page.locator('#cropX').dispatchEvent('change');await page.locator('#cropApply').click();await expect(page.locator('#dimensions')).toContainText('V1');await expect(page.locator('#recover')).toBeEnabled();
 await releaseFinderConstraints(page);
 await page.locator('#fineCorners').click();const before=await page.locator('#cornerPosition').textContent();await page.locator('#cornerRight').click();await expect(page.locator('#cornerPosition')).not.toHaveText(before);await page.locator('#cornerCancel').click();await page.locator('#fineCorners').click();await expect(page.locator('#cornerPosition')).toHaveText(before);await page.locator('#cornerStep').selectOption('0.05');await page.locator('#cornerRight').click();await page.locator('#cornerApply').click();await expect(page.locator('#progress')).toBeHidden();await expect(page.locator('#recover')).toBeDisabled();await assignFinderOrientation(page);
 await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();await page.locator('#rectifyImage').click();await expect(page.locator('#recover')).toBeEnabled();await page.locator('#imageAngle').fill('0.1');await page.locator('#rotateImage').click();await expect(page.locator('#recover')).toBeEnabled();
 await assignFinderOrientation(page);await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});
test('priority limit can be raised and lowering it blocks repair without deleting marks',async({page})=>{
 await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();await page.locator('[data-tab="edit"]').click();
 const n=33,canvas=page.locator('#canvas');
 async function mark(x,y){const b=await canvas.boundingBox();await canvas.click({position:{x:(x+2.5)/(n+4)*b.width,y:(y+2.5)/(n+4)*b.height}});await page.locator('[data-value="*"]').click();await expect(page.locator('#recover')).toBeEnabled();}
 for(let y=32;y>=29;y--)await mark(32,y);await expect(page.locator('#priorityCount')).toContainText('4 / 4');await mark(32,28);await expect(page.locator('#toast')).toContainText('4');await expect(page.locator('#priorityCount')).toContainText('4 / 4');
 await page.locator('[data-tab="input"]').click();await changeRecoverySetting(page,'#priorityLimit','selectOption','5');await page.locator('[data-tab="edit"]').click();await mark(32,28);await expect(page.locator('#priorityCount')).toContainText('5 / 5');
 await page.locator('[data-tab="input"]').click();await changeRecoverySetting(page,'#priorityLimit','selectOption','4');await runRecovery(page);await expect(page.locator('#toast')).toContainText('上限4');await page.locator('[data-tab="edit"]').click();await expect(page.locator('#priorityCount')).toContainText('5 / 4');
});

test('rectangle and stamp edits are single undoable transactions, source changes preserve known values',async({page})=>{
 await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();await page.locator('[data-tab="edit"]').click();await page.locator('#cellTool').selectOption('rect');
 const c=page.locator('#canvas');await c.scrollIntoViewIfNeeded();const b=await c.boundingBox(),at=(x,y)=>[b.x+(x+2.5)/37*b.width,b.y+(y+2.5)/37*b.height];
 await page.mouse.move(...at(15,15));await page.mouse.down();await page.mouse.move(...at(17,17),{steps:4});await page.mouse.up();await page.locator('[data-value="?"]').click();await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*\?/);await page.locator('#undo').click();await page.locator('#redo').click();await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*\?/);
 await expect(page.locator('#editKind option[value="1"]')).toBeDisabled();await page.locator('[data-value="1"]').click();await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*1/);await page.locator('#editKind').selectOption('5');await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*1/);await page.locator('#restoreCell').click();await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('#cellTool').selectOption('stamp3');await c.click({position:{x:(16+2.5)/37*b.width,y:(16+2.5)/37*b.height}});await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*\?/);await page.locator('#undo').click();
});


test('view controls stay next to the image and edits remain within reach',async({page},testInfo)=>{
 await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 const before=await page.locator('#canvas').evaluate(c=>c.toDataURL());await changeEvidenceControl(page,'#unmask','check');expect(await page.locator('#canvas').evaluate(c=>c.toDataURL())).not.toBe(before);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await expect(page.locator('#previewDock #unmask')).toBeVisible();await expect(page.locator('#previewDock #sameCodeword')).toBeVisible();
 const canvas=await page.locator('#canvas').boundingBox(),mask=await page.locator('#unmask').boundingBox();expect(canvas.y-mask.y).toBeLessThan(120);
 await page.screenshot({path:`test-results/${testInfo.project.name}-nearby-result-controls.png`,fullPage:true});
 await page.locator('[data-tab="edit"]').click();await expect(page.locator('#unmask')).toBeHidden();await expect(page.locator('#cellTool')).toBeVisible();
 const rect=await page.locator('#canvas').boundingBox();await page.locator('#canvas').click({position:{x:rect.width*.5,y:rect.height*.5}});await page.locator('[data-value="?"]').click();await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*\?/);
 expect(await page.locator('#undo').evaluate(el=>!!el.closest('.viewer'))).toBe(true);await expect(page.locator('#editorActions .cell-tools [data-value="?"]')).toBeInViewport();
 await page.screenshot({path:`test-results/${testInfo.project.name}-nearby-editor-controls.png`,fullPage:true});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});

test('phone preview remains pinned while scrolling settings and after zooming',async({page},testInfo)=>{
 test.skip(!testInfo.project.use.isMobile,'Phone workspace layout');
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await expect(page.locator('#recover')).toBeEnabled();
 await changeRecoverySetting(page,'#priorityLimit','selectOption','5');
 const dock=page.locator('#previewDock'),header=page.locator('body > header');
 const inset=async()=>{const d=await dock.boundingBox(),h=await header.boundingBox();return Math.abs(d.y-h.y-h.height);};await expect.poll(inset).toBeLessThan(2);
 const first=await dock.boundingBox();await page.evaluate(()=>window.scrollBy(0,120));const second=await dock.boundingBox();expect(Math.abs(second.y-first.y)).toBeLessThan(2);expect(second.height).toBeLessThan(844*.66);
 await page.locator('#zoomIn').click();await page.locator('#zoomIn').click();await page.locator('#panView').click();await page.locator('#canvasScroll').evaluate(el=>{el.scrollLeft=80;el.scrollTop=80;});expect(await page.locator('#canvasScroll').evaluate(el=>el.scrollLeft+el.scrollTop)).toBeGreaterThan(0);await page.locator('#priorityLimit').scrollIntoViewIfNeeded();await expect.poll(inset).toBeLessThan(2);
 await page.locator('#fitView').click();await page.locator('#panView').click();await page.locator('#priorityLimit').scrollIntoViewIfNeeded();await page.screenshot({path:`test-results/${testInfo.project.name}-pinned-preview.png`});
 await page.locator('[data-tab="edit"]').click();await page.locator('#cellSourceHelp').scrollIntoViewIfNeeded();await expect.poll(inset).toBeLessThan(2);
 const canvas=page.locator('#canvas'),b=await canvas.boundingBox();await canvas.click({position:{x:b.width*.5,y:b.height*.5}});await page.locator('[data-value="?"]').click();await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*\?/);
 await page.setViewportSize({width:320,height:568});await expect(dock).toHaveCSS('position','static');await page.locator('#cellSourceHelp').scrollIntoViewIfNeeded();await expect(page.locator('#cellSourceHelp')).toBeInViewport();await page.locator('#fitView').click();await expect(canvas).toBeInViewport();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
