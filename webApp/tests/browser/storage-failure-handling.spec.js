import {changeRecoverySetting,clickInputAction,clickWorkspaceAction,openAppMenu,runRecovery,setAutomaticRepair,showDiagnostics} from './input-helpers.js';
import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';

test.use({serviceWorkers:'block'});
test.beforeEach(async({page})=>{
 await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));
});
const sessions=page=>page.evaluate(async()=> (await import('/storage.js')).listSessions());
async function demo(page){await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#dimensions')).toContainText('V4');await expect(page.locator('#recover')).toBeEnabled();await expect.poll(async()=>(await sessions(page)).length).toBe(1);}
async function edit(page){
 await page.locator('.steps [data-tab="edit"]').click();
 const canvas=page.locator('#canvas'),box=await canvas.boundingBox();
 await canvas.click({position:{x:box.width*.5,y:box.height*.5}});
 await page.locator('[data-value="?"]').click();await expect(page.locator('#undo')).toBeEnabled();
}
async function workMenu(page){await openAppMenu(page);await page.locator('#workMenuButton').click();}
async function backup(page){
 await page.evaluate(()=>{navigator.canShare=()=>false;});await workMenu(page);
 const event=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportSession');
 return readFile(await (await event).path());
}
async function showSessions(page){await openAppMenu(page);await page.locator('#sessionsButton').click();}

test('denied localStorage does not prevent startup or appearance dialog use',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{Storage.prototype.getItem=()=>{throw new DOMException('Denied','SecurityError');};Storage.prototype.setItem=()=>{throw new DOMException('Denied','SecurityError');};});
 await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();
 await clickInputAction(page,'#demo');await expect(page.locator('#dimensions')).toContainText('V4');
 await expect(page.locator('#recover')).toBeEnabled();
 await page.evaluate(()=>document.getElementById('appearanceButton').click());
 await expect(page.locator('#appearanceDialog')).toBeVisible();await page.locator('#saveAppearance').click();
 await expect(page.locator('#appearanceNoticeText')).toContainText('保存できません');expect(errors).toEqual([]);
});

test('backup preserves redo history and cursor both in memory and on disk',async({page})=>{
 await demo(page);await edit(page);await page.locator('#undo').click();
 await expect.poll(async()=>(await sessions(page))[0].cursor).toBe(0);
 const before=(await sessions(page))[0],file=await backup(page),exported=JSON.parse(JSON.parse(file.toString()).payload);
 expect(exported.history).toEqual(before.history);expect(exported.cursor).toBe(before.cursor);
 await page.locator('.steps [data-tab="edit"]').click();await expect(page.locator('#redo')).toBeEnabled();
 expect((await sessions(page))[0].history).toEqual(before.history);
 await page.locator('#redo').click();await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*\?/);
});

test('failed backup also preserves redo history',async({page})=>{
 await demo(page);await edit(page);await page.locator('#undo').click();
 await expect.poll(async()=>(await sessions(page))[0].cursor).toBe(0);const before=(await sessions(page))[0];
 await page.evaluate(()=>{crypto.subtle.digest=()=>Promise.reject(new Error('Simulated export failure'));});
 await workMenu(page);await clickWorkspaceAction(page,'#exportSession');await expect(page.locator('#toast')).toContainText('Simulated export failure');
 await page.locator('.steps [data-tab="edit"]').click();await expect(page.locator('#redo')).toBeEnabled();
 expect((await sessions(page))[0].history).toEqual(before.history);
});

test('opening an old backup creates a separate workspace and preserves newer edits',async({page})=>{
 await demo(page);const original=(await sessions(page))[0],file=await backup(page);await edit(page);
 await expect.poll(async()=>(await sessions(page))[0].history.length).toBeGreaterThan(original.history.length);
 const latest=(await sessions(page))[0];
 await page.locator('#sessionFile').setInputFiles({name:'backup.qrpwa',mimeType:'application/json',buffer:file});
 await expect.poll(async()=>(await sessions(page)).length).toBe(2);
 const stored=await sessions(page);expect(stored.find(s=>s.id===latest.id).history).toEqual(latest.history);
 expect(stored.find(s=>s.id!==latest.id).history).toEqual(original.history);
});

for(const all of [true,false])test(`deleting ${all?'all workspaces':'the active workspace'} survives pagehide and Exit`,async({page,context})=>{
 await demo(page);page.on('dialog',d=>d.accept());await showSessions(page);
 if(all)await page.locator('#deleteAllSessions').click();else await page.locator('#sessionList .session-item button').last().click();
 await expect.poll(async()=>(await sessions(page)).length).toBe(0);
 await page.locator('[data-close="sessionsDialog"]').click();
 await page.evaluate(()=>window.dispatchEvent(new Event('pagehide')));
 await openAppMenu(page);
 // Some browsers close the tab; others show guidance for closing it manually.
 const closed=new Promise(resolve=>page.once('close',()=>resolve('closed')));
 const guidance=page.locator('#exitBack').waitFor({state:'visible'}).then(()=> 'guidance').catch(error=>{if(page.isClosed())return 'closed';throw error;});
 await page.locator('#exitButton').click().catch(error=>{if(!page.isClosed())throw error;});
 if(await Promise.race([closed,guidance])==='closed'){
  page=await context.newPage();await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();
 }else{
  await expect(page.locator('#exitBack')).toBeEnabled();await page.locator('#exitBack').click();
 }
 expect(await sessions(page)).toEqual([]);
 await clickInputAction(page,'#demo');
 await expect.poll(async()=>(await sessions(page)).length).toBe(1);
});

test('input data reflects undo when reopened and another workspace clears analysis',async({page})=>{
 await demo(page);const file=await backup(page);await edit(page);
 await page.locator('#openInputData').click();
 await expect(page.locator('#inputDataReport')).not.toBeEmpty();await expect(page.locator('#undo')).toBeEnabled();
 const edited=await page.locator('#inputDataReport').textContent();await page.locator('#closeInputData').click();await page.locator('#undo').click();await page.locator('#openInputData').click();
 await expect(page.locator('#inputDataReport')).not.toBeEmpty();await expect(page.locator('#inputDataReport')).not.toHaveText(edited);await page.locator('#closeInputData').click();
 await page.locator('#sessionFile').setInputFiles({name:'backup.qrpwa',mimeType:'application/json',buffer:file});
 await expect(page.locator('#inputDataReport')).toBeEmpty();
 await page.locator('#textFile').setInputFiles('tests/fixtures/tail-alternatives.txt');await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('#repairDetails > summary').click();await changeRecoverySetting(page,'#list','selectOption','0');
 await runRecovery(page);await expect(page.locator('#tailAnalysis')).toBeVisible();
 await showDiagnostics(page);await page.locator('#tailAnalysis > summary').click();
 await expect(page.locator('#tailReport')).not.toBeEmpty();
 await page.locator('#sessionFile').setInputFiles({name:'backup.qrpwa',mimeType:'application/json',buffer:file});
 await expect(page.locator('#tailReport')).toBeEmpty();await expect(page.locator('#tailRaw')).toBeEmpty();
 await expect(page.locator('#tailControls')).toBeHidden();await expect(page.locator('#tailRawPanel')).toBeHidden();
 await page.locator('#tailSource').dispatchEvent('change');await expect(page.locator('#tailReport')).toBeEmpty();
});

test('automatic image import supports selecting and editing cells on the image',async({page})=>{
 await page.goto('/');await setAutomaticRepair(page,true);
 await page.locator('#imageFile').setInputFiles('tests/fixtures/ordinary-failure.png');await page.locator('#cropApply').click();
 await expect(page.locator('#automaticDialog')).toBeVisible();await page.locator('#automaticDetailed').click();
 await page.locator('.steps [data-tab="edit"]').click();await page.locator('#imageView').click();
 const canvas=page.locator('#canvas'),box=await canvas.boundingBox();await canvas.click({position:{x:box.width*.5,y:box.height*.5}});
 await expect(page.locator('#selectedCell')).toContainText(/値[=:]/);
 await page.locator('[data-value="?"]').click();await expect(page.locator('#selectedCell')).toContainText(/値[=:]\s*\?/);
});
