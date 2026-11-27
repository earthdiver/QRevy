import {assignFinderOrientation,changeEvidenceControl,clickWorkspaceAction,openAppMenu,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';

test.use({serviceWorkers:'block'});
const sessions=page=>page.evaluate(async()=>(await import('/storage.js')).listSessions());
const latest=async page=>(await sessions(page))[0];
const current=s=>s.history[s.cursor];
async function ready(page){await expect(page.locator('#recover')).toBeEnabled();}
async function start(page,automatic=false){
 await page.addInitScript(value=>localStorage.setItem('qrevy-automatic-repair',String(value)),automatic);
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
}
async function photo(page,automatic=false){
 await start(page,automatic);await page.locator('#imageFile').setInputFiles(automatic?'tests/fixtures/ordinary-failure.png':'tests/fixtures/clean.png');await page.locator('#cropApply').click();
 if(!automatic){await expect(page.locator('#ordinaryDialog')).toBeVisible();await page.locator('#ordinaryDetailed').click();}
 if(automatic){await expect(page.locator('#automaticDialog')).toBeVisible();await page.locator('#automaticDetailed').click();}
 await ready(page);await expect.poll(async()=>!!(await latest(page))?.image).toBe(true);
}
async function selectCell(page,x,y){
 await page.locator('.steps [data-tab="edit"]').click();
 const n=current(await latest(page)).matrix.text.split('\n').length,c=page.locator('#canvas'),b=await c.boundingBox();
 await c.click({position:{x:(x+2.5)/(n+4)*b.width,y:(y+2.5)/(n+4)*b.height}});
}
async function workMenu(page){await openAppMenu(page);await page.locator('#workMenuButton').click();}
async function reopen(page){
 await page.reload();await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList .session-item button').first().click();
 await expect(page.locator('#sessionsDialog')).not.toBeVisible();await ready(page);
}
async function reveal(page,id){await page.locator('#'+id).evaluate(el=>{for(let p=el.parentElement;p;p=p.parentElement)if(p.tagName==='DETAILS')p.open=true;});}

test('unlocked QRR exports after enabling and disabling sampling fine-tuning',async({page})=>{
 await start(page);await page.locator('#sessionFile').setInputFiles('tests/fixtures/unlocked-corners.qrr');await ready(page);
 await page.locator('#companionImageFile').setInputFiles('tests/fixtures/clean.png');await expect.poll(async()=>!!(await latest(page))?.image).toBe(true);await expect(page.locator('#recover')).toBeDisabled();await assignFinderOrientation(page);await ready(page);
 await reveal(page,'neighborhood');await page.locator('#neighborhood').check();await expect.poll(async()=>current(await latest(page)).settings.neighborhood).toBe(true);
 await page.locator('#neighborhood').uncheck();await expect.poll(async()=>current(await latest(page)).settings.neighborhood).toBe(false);
 const before=await latest(page);expect(before.history.some(h=>h.settings.neighborhood)).toBe(true);
 await page.evaluate(()=>navigator.canShare=()=>false);await workMenu(page);
 const event=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportQrr');const file=await event;
 await page.locator('#sessionFile').setInputFiles({name:'fine-tuning.qrr',mimeType:'application/octet-stream',buffer:await readFile(await file.path())});await ready(page);
 await expect.poll(async()=>(await sessions(page)).length).toBe(2);
 const after=await latest(page);expect(after.history.map(h=>h.settings.neighborhood)).toEqual(before.history.map(h=>h.settings.neighborhood));
 expect(current(after).matrix.text).toBe(current(before).matrix.text);
});

test('image results show repaired modules and can switch to before/after comparison',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{const Original=window.Worker;window.Worker=class extends Original{constructor(...args){super(...args);this.addEventListener('message',e=>{if(e.data.result?.candidates?.length)window.testCandidate=e.data.result.candidates[0];});}};});
 await photo(page);await selectCell(page,20,20);await page.locator('[data-value="?"]').click();
 await expect.poll(async()=>current(await latest(page)).matrix.text.at(-1)).toBe('?');await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
 await page.locator('#resultEvidenceTab').click();await page.locator('#imageView').click();await changeEvidenceControl(page,'#resultStage','selectOption','before');
 const before=await page.locator('#canvas').evaluate(el=>el.toDataURL());await changeEvidenceControl(page,'#resultStage','selectOption','after');
 expect(errors).toEqual([]);
 expect(await page.evaluate(()=>({before:window.testCandidate.input.text.at(-1),after:window.testCandidate.matrix.text.at(-1)}))).toEqual({before:'?',after:'1'});
 expect(await page.locator('#canvas').evaluate((el,before)=>el.toDataURL()!==before,before)).toBe(true);
 await changeEvidenceControl(page,'#resultStage','selectOption','compare');await expect(page.locator('#beforeCanvas')).toBeVisible();
 await expect(page.locator('#imageView')).toHaveClass(/selected/);
 expect(await page.evaluate(()=>document.querySelector('#beforeCanvas').toDataURL()!==document.querySelector('#canvas').toDataURL())).toBe(true);
 const snapshot=await latest(page);
 for(const [id,x,y] of [['beforeCanvas',10,10],['canvas',19,19],['beforeCanvas',11,10]]){
  const images=await page.evaluate(()=>['beforeCanvas','canvas'].map(id=>document.getElementById(id).toDataURL()));
  const point=await page.evaluate(({x,y})=>{
   const c=window.testCandidate,n=17+4*c.version,[px,py]=c.grid[y*n+x],canvas=document.getElementById('canvas');
   return {x:px/Number(canvas.dataset.imageWidth),y:py/Number(canvas.dataset.imageHeight)};
  },{x,y});
  const target=page.locator('#'+id),box=await target.boundingBox();
  await target.click({position:{x:point.x*box.width,y:point.y*box.height}});
  await expect(page.locator('#selectedCell')).toContainText(`(${x},${y})`);
  expect(await page.evaluate(old=>['beforeCanvas','canvas'].map((id,i)=>document.getElementById(id).toDataURL()!==old[i]),images)).toEqual([true,true]);
 }
 expect((await latest(page)).history).toEqual(snapshot.history);
 expect(errors).toEqual([]);
});

for(const change of ['version','cell'])test(`automatic success is invalidated by ${change} changes and stays invalid after reopening`,async({page})=>{
 await photo(page,true);await expect(page.locator('#automaticStatus')).toContainText('候補が1件');
 if(change==='version'){
  page.once('dialog',d=>d.accept());await page.locator('#version').selectOption('5');await expect.poll(async()=>current(await latest(page)).settings.version).toBe(5);
 }else{
  await selectCell(page,20,14);await page.locator('[data-value="?"]').click();await expect.poll(async()=>current(await latest(page)).matrix.text.replaceAll('\n','')[314]).toBe('?');
 }
 await page.locator('.steps [data-tab="results"]').click();await expect(page.locator('#automaticStatus')).toBeHidden();
 await reopen(page);await page.locator('.steps [data-tab="results"]').click();await expect(page.locator('#automaticStatus')).toBeHidden();await expect(page.locator('#resultCount')).toHaveText('0');
});

test('saving after Undo preserves Redo in memory, backup, and reopened work',async({page})=>{
 await photo(page);await selectCell(page,20,20);await page.locator('[data-value="?"]').click();await expect.poll(async()=>current(await latest(page)).matrix.text.at(-1)).toBe('?');
 const edited=current(await latest(page));await page.locator('#undo').click();await ready(page);await expect(page.locator('#redo')).toBeEnabled();
 await expect.poll(async()=>current(await latest(page)).matrix.text.at(-1)).not.toBe('?');const before=await latest(page);
 await workMenu(page);await clickWorkspaceAction(page,'#save');await expect.poll(async()=>(await latest(page)).checkpointCursor).toBe(before.cursor);
 const saved=await latest(page);expect(saved.history).toEqual(before.history);expect(saved.cursor).toBe(before.cursor);await expect(page.locator('#redo')).toBeEnabled();
 const backup=await page.evaluate(async s=>{const {exportWorkspace,importWorkspace}=await import('/storage.js');return importWorkspace(await exportWorkspace({...s,image:null,imageHash:null}));}, {...saved,image:null});
 expect(backup.history).toEqual(before.history);expect(backup.cursor).toBe(before.cursor);
 await reopen(page);await page.locator('.steps [data-tab="edit"]').click();await expect(page.locator('#redo')).toBeEnabled();await page.locator('#redo').click();
 await expect.poll(async()=>current(await latest(page)).matrix).toEqual(edited.matrix);
});

test('grid re-detection keeps a fixed edit on its physical module and remains undoable',async({page})=>{
 await photo(page);await selectCell(page,19,19);await page.locator('#editKind').selectOption('1');await expect.poll(async()=>current(await latest(page)).matrix.sources[418]).toBe('1');
 await page.locator('.steps [data-tab="input"]').click();await page.locator('#rotate').click();await expect.poll(async()=>current(await latest(page)).matrix.sources[400]).toBe('1');const rotated=current(await latest(page));
 await page.locator('#detect').click();await ready(page);await expect.poll(async()=>current(await latest(page)).matrix.sources[418]).toBe('1');
 const detected=current(await latest(page));expect(detected.matrix.sources[400]).not.toBe('1');expect(detected.matrix.text.replaceAll('\n','')[400]).toBe('0');
 await page.locator('.steps [data-tab="edit"]').click();await page.locator('#undo').click();await expect.poll(async()=>current(await latest(page)).matrix).toEqual(rotated.matrix);
 await page.locator('#redo').click();await expect.poll(async()=>current(await latest(page)).matrix).toEqual(detected.matrix);
 await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});

test('force-stopping the first automatic image repair produces reopenable history and backup',async({page})=>{
 // Hold only the initial automatic operation; fallback detection and sampling use the real engine.
 await page.route('**/worker.js',r=>r.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';import {loadNativeScanner} from './native-boundaries.js';const nativeReady=loadNativeScanner().then(scan=>{globalThis.qrevyNativeScan=scan;});self.onmessage=async({data})=>{await nativeReady;if(data.request.action==='quickRecoverImage')return;self.postMessage({id:data.id,result:JSON.parse(execute(data.request))});};self.postMessage({ready:true});`}));
 await start(page,true);await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await expect(page.locator('#progress')).toBeVisible();await page.locator('#cancel').click();
 if(await page.evaluate(()=>typeof SharedArrayBuffer!=='undefined'&&crossOriginIsolated))await page.locator('#cancel').click();
 await expect.poll(async()=>(await latest(page))?.history.at(-1)?.label).toBe('簡易自動修復');await ready(page);
 const validation=await page.evaluate(async()=>{const {listSessions,validateSession,exportWorkspace,importWorkspace}=await import('/storage.js');const s=(await listSessions())[0];try{validateSession(s);await importWorkspace(await exportWorkspace(s));return null;}catch(e){return e.message;}});
 expect(validation).toBeNull();const saved=await latest(page);await reopen(page);expect(current(await latest(page)).matrix).toEqual(current(saved).matrix);
 await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});
