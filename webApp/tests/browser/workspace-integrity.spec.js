import {continueAfterOrdinaryReading} from './input-helpers.js';
import {openAppMenu,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';
test.use({serviceWorkers:'block'});
const sessions=page=>page.evaluate(async()=>(await import('/storage.js')).listSessions());
const latest=async page=>{const s=(await sessions(page))[0];return s?.history[s.cursor];};
const execute=(page,request)=>page.evaluate(async r=>JSON.parse((await import('/engine/js/engine.js')).execute(r)),request);
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});

test('reopened image corner edits preserve the prior history and undo geometry',async({page})=>{
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();
 await expect.poll(async()=>(await sessions(page)).length).toBe(1);
 await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList .session-item button').first().click();await expect(page.locator('#sessionsDialog')).not.toBeVisible();
 const before=(await sessions(page))[0],h=before.history[before.cursor];await page.locator('#imageView').click();
 const canvas=page.locator('#canvas'),box=await canvas.boundingBox(),width=await canvas.evaluate(el=>Number(el.dataset.imageWidth)),q=h.settings.quad;
 const x=box.x+q[4]*box.width/width,y=box.y+q[5]*box.width/width;
 await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x-15,y-15,{steps:4});await page.mouse.up();
 await expect.poll(async()=>(await latest(page)).settings.quad).not.toEqual(q);
 const changed=(await sessions(page))[0];expect(changed.history[before.cursor]).toEqual(h);
 await page.locator('.steps [data-tab="edit"]').click();await page.locator('#undo').click();await expect.poll(async()=>(await latest(page)).settings.quad).toEqual(q);
});

test('stale automatic candidates cannot overwrite later edits',async({page})=>{
 // Duplicate a real automatic candidate to exercise the selection controls deterministically.
 await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';self.onmessage=({data})=>{const result=JSON.parse(execute(data.request));if(data.request.action==='quickRecover'&&result.candidates?.length){result.candidates=[result.candidates[0],structuredClone(result.candidates[0])];result.automatic.selectedIndex=0;}self.postMessage({id:data.id,result});};self.postMessage({ready:true});`}));
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();const m=await execute(page,{action:'demo',text:'STALE',damage:false});await page.evaluate(()=>document.getElementById('automaticRepair').checked=true);
 await page.locator('#textFile').setInputFiles({name:'matrix.txt',mimeType:'text/plain',buffer:Buffer.from(m.text)});await expect(page.locator('#automaticDialog')).toBeVisible();await page.locator('#automaticClose').click();
 await page.locator('#candidatePicker').selectOption('1');await expect.poll(async()=>(await latest(page)).label).toBe('候補を選択');await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('#dataLimitMode').evaluate(el=>{el.value='EXACT';el.dispatchEvent(new Event('change'));});await expect.poll(async()=>(await latest(page)).settings.dataLimitMode).toBe('EXACT');
 const before=(await sessions(page))[0];await expect(page.locator('#candidatePicker')).toBeDisabled();await expect(page.locator('#browseCandidates')).toBeDisabled();
 await page.locator('#candidatePicker').evaluate(el=>{el.value='0';el.dispatchEvent(new Event('change'));});
 expect((await sessions(page))[0].history).toEqual(before.history);
 expect((await latest(page)).settings.dataLimitMode).toBe('EXACT');
});

test('copy preserves CRLF and CR in the decoded payload',async({page})=>{
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();const text='BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Example\rEND:VCARD';const m=await execute(page,{action:'demo',text,damage:false});
 await page.locator('#textFile').setInputFiles({name:'vcard.txt',mimeType:'text/plain',buffer:Buffer.from(m.text)});await expect(page.locator('#recover')).toBeEnabled();await runRecovery(page);await expect(page.locator('#copyPayload')).toBeEnabled();
 await page.evaluate(()=>Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async text=>{window.copiedPayload=text;}}}));
 await page.locator('#copyPayload').click();expect(await page.evaluate(()=>window.copiedPayload)).toBe(text);
});
