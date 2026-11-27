import {clickInputAction,openAppMenu,setAutomaticRepair} from './input-helpers.js';
import {test,expect} from '@playwright/test';

test.use({serviceWorkers:'block'});
const sessions=page=>page.evaluate(async()=>(await import('/storage.js')).listSessions());
const imageSession=async page=>(await sessions(page)).find(s=>s.image);
const current=s=>s.history[s.cursor];

async function imageWorkspace(page){
 await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));
 await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('.steps [data-tab="input"]').click();await page.locator('#inputChoices > summary').click();await clickInputAction(page,'#matrixToImage');
 await expect.poll(async()=>!!await imageSession(page)).toBe(true);
 await expect(page.locator('#recover')).toBeEnabled();
 return imageSession(page);
}

test('failed automatic grid detection still permits manual corners and Finder assignment',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('/');await setAutomaticRepair(page,true);
 const png=await page.evaluate(()=>{const c=document.createElement('canvas');c.width=c.height=200;const cx=c.getContext('2d');cx.fillStyle='#fff';cx.fillRect(0,0,200,200);return c.toDataURL().split(',')[1];});
 await page.locator('#imageFile').setInputFiles({name:'no-grid.png',mimeType:'image/png',buffer:Buffer.from(png,'base64')});
 await page.locator('#cropApply').click();await expect(page.locator('#automaticDialog')).toBeVisible();
 await expect(page.locator('#automaticMessage')).toContainText('四隅・バージョンを推定できませんでした');
 await page.locator('#automaticDetailed').click();
 // Without a Finder or an estimated or adjusted grid, a role tap assigns nothing.
 await expect(page.locator('#finderRole')).toBeEnabled();await page.locator('#finderRole').selectOption('0');
 const unadjusted=page.locator('#canvas'),area=await unadjusted.boundingBox();
 await unadjusted.click({position:{x:area.width*.2,y:area.height*.2}});
 await expect(page.locator('#toast')).toContainText('四隅を調整してから');
 expect(current(await imageSession(page)).settings.assigned).toBe(false);
 await page.locator('#fineCorners').click();
 await expect(page.locator('#cornerCanvas')).toBeVisible();
 const before=current(await imageSession(page)).settings.quad;
 await page.locator('#cornerRight').click();await page.locator('#cornerApply').click();
 await expect.poll(async()=>current(await imageSession(page)).settings.quad).not.toEqual(before);
 await expect(page.locator('#finderRole')).toBeEnabled();await page.locator('#finderRole').selectOption('0');
 const canvas=page.locator('#canvas'),box=await canvas.boundingBox();
 await canvas.click({position:{x:box.width*.2,y:box.height*.2}});
 // No Finder is detected in the blank image, so the tap orients the adjusted grid without moving it.
 await expect.poll(async()=>current(await imageSession(page)).settings.assigned).toBe(true);
 expect(current(await imageSession(page)).settings.finders).toEqual([]);
 expect(errors).toEqual([]);
});

for(const [key,value] of [['brightness','100'],['black','150'],['toneInvert',true],['curved',true]])test(`${key} adjustment is one consistent undo/redo step and survives reopening`,async({page})=>{
 const before=await imageWorkspace(page);
 await page.locator('#'+key).evaluate((el,value)=>{if(el.type==='checkbox')el.checked=value;else el.value=value;el.dispatchEvent(new Event('change'));},value);
 await expect.poll(async()=>current(await imageSession(page)).settings[key]).toBe(typeof value==='boolean'?value:Number(value));
 await expect(page.locator('#recover')).toBeEnabled();
 const changed=await imageSession(page);
 expect(changed.cursor).toBe(before.cursor+1);expect(changed.history).toHaveLength(before.history.length+1);
 // Finder polarity detection can compensate for a tone inversion.
 if(key==='brightness')expect(current(changed).matrix.text).not.toBe(current(before).matrix.text);
 await page.locator('.steps [data-tab="edit"]').click();await page.locator('#undo').click();
 await expect.poll(async()=>current(await imageSession(page))).toEqual(current(before));
 await expect(page.locator('#'+key)).toHaveJSProperty(key==='toneInvert'||key==='curved'?'checked':'value',typeof value==='boolean'?current(before).settings[key]:String(current(before).settings[key]));
 await page.locator('#redo').click();await expect.poll(async()=>current(await imageSession(page))).toEqual(current(changed));
 await page.reload();await openAppMenu(page);await page.locator('#sessionsButton').click();
 await page.locator('#sessionList .session-item button').first().click();await expect(page.locator('#sessionsDialog')).not.toBeVisible();
 await page.locator('.steps [data-tab="edit"]').click();await page.locator('#undo').click();
 await expect.poll(async()=>current(await imageSession(page))).toEqual(current(before));
});

test('failed image resampling restores settings without adding an inconsistent history entry',async({page})=>{
 await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';import {loadNativeScanner} from './native-boundaries.js';const nativeReady=loadNativeScanner().then(scan=>{globalThis.qrevyNativeScan=scan;});self.onmessage=async({data})=>{await nativeReady;if(['sample','refine'].includes(data.request.action)&&data.request.settings.brightness===100){self.postMessage({id:data.id,error:'Simulated sampling failure'});return;}self.postMessage({id:data.id,result:JSON.parse(execute(data.request))});};self.postMessage({ready:true});`}));
 const before=await imageWorkspace(page);
 await page.locator('#brightness').evaluate(el=>{el.value='100';el.dispatchEvent(new Event('change'));});
 await expect(page.locator('#toast')).toContainText('Simulated sampling failure');
 await expect(page.locator('#brightness')).toHaveValue('0');
 const after=await imageSession(page);expect(after.history).toEqual(before.history);expect(after.cursor).toBe(before.cursor);
});
