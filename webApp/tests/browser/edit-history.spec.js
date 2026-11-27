import {continueAfterOrdinaryReading} from './input-helpers.js';
import {clickInputAction,openAppMenu} from './input-helpers.js';
import {test,expect} from '@playwright/test';
test.use({serviceWorkers:'block'});
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();});
const latest=page=>page.evaluate(async()=>{const s=(await (await import('/storage.js')).listSessions())[0];return s?.history[s.cursor];});
const execute=(page,request)=>page.evaluate(async r=>JSON.parse((await import('/engine/js/engine.js')).execute(r)),request);
async function importText(page,text,automatic=false){await page.evaluate(value=>document.getElementById('automaticRepair').checked=value,automatic);await page.locator('#textPaste').evaluate(el=>el.open=true);await page.locator('#matrixInput').fill(text);await page.locator('#pasteImport').click();if(automatic){await expect(page.locator('#automaticDialog')).toBeVisible();await page.locator('#automaticDetailed').click();}await expect(page.locator('#recover')).toBeEnabled();}
async function change(page,id,value){await page.locator('#'+id).evaluate((el,value)=>{el.value=value;el.dispatchEvent(new Event('change'));},value);}
async function cellPosition(page,x,y,n){const box=await page.locator('#canvas').boundingBox();return {x:box.x+(x+2.5)*box.width/(n+4),y:box.y+(y+2.5)*box.height/(n+4)};}
test('changed repair settings clear automatic replay conditions and survive reopening',async({page})=>{
 const m=await execute(page,{action:'demo',text:'POLICY',damage:true});await importText(page,m.text,true);
 expect((await latest(page)).automaticScanAssumption).toBeDefined();
 await change(page,'dataLimitMode','EXACT');
 await expect.poll(async()=>(await latest(page)).settings.dataLimitMode).toBe('EXACT');
 expect((await latest(page)).automaticScanAssumption).toBeUndefined();expect((await latest(page)).selection).toBeNull();
 await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList .session-item button').first().click();
 await expect(page.locator('#payload')).toHaveValue('');
});
test('automatic TXT repair retains an unmarked source for restoring priority cells',async({page})=>{
 const m=await execute(page,{action:'demo',text:'PRIORITY',damage:false}),n=m.text.split('\n').length;
 await importText(page,m.text.slice(0,-1)+'*',true);await page.locator('#openCellEditor').click();
 await page.locator('#fitView').click();const canvas=page.locator('#canvas'),box=await canvas.boundingBox();
 await canvas.click({position:{x:(n+1.5)*box.width/(n+4),y:(n+1.5)*box.height/(n+4)}});await page.locator('#restoreCell').click();
 await expect.poll(async()=>(await latest(page)).matrix.text.at(-1)).toBe('?');expect((await latest(page)).source.text).not.toContain('*');
});
test('a fast brush stroke edits every crossed cell and undo restores it',async({page})=>{
 await clickInputAction(page,'#demo');await expect(page.locator('#dimensions')).toContainText('V4');await page.locator('.steps [data-tab="edit"]').click();
 const before=await latest(page),n=before.matrix.text.split('\n').length;await page.locator('#cellTool').selectOption('brush?');
 const start=await cellPosition(page,10,20,n),end=await cellPosition(page,20,20,n);
 await page.mouse.move(start.x,start.y);await page.mouse.down();await page.mouse.move(end.x,end.y);await page.mouse.up();
 await expect.poll(async()=>(await latest(page)).matrix.text.split('\n')[20].slice(10,21)).toBe('?'.repeat(11));
 await page.locator('#undo').click();await expect.poll(async()=>(await latest(page)).matrix.text).toBe(before.matrix.text);
});
test('crop dialog treats imported tone values as values instead of HTML',async({page})=>{
 await page.evaluate(async()=>{const {cropImage}=await import('/image-tools.js');const image=new Image();image.src='/icons/icon-192.png';await image.decode();cropImage(image,{brightness:'"><img id="injected-markup" src=x>',contrast:'"><script>window.injected=true</script>'});});
 await expect(page.locator('#cropApply')).toBeVisible();await expect(page.locator('#injected-markup')).toHaveCount(0);await expect(page.locator('#cropBrightness')).toHaveValue('0');await expect(page.locator('#cropContrast')).toHaveValue('0');
});
test('rotation and rotation reset never remove an applied rectification',async({page})=>{
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('#rectifyImage').evaluate(el=>el.click());await expect.poll(async()=>(await latest(page))?.settings.imageOps.some(op=>op.type==='source'&&op.quad)).toBe(true);
 const before=await latest(page);await page.locator('#resetImageRotation').evaluate(el=>el.click());expect((await latest(page)).settings.imageOps).toEqual(before.settings.imageOps);
 await change(page,'imageAngle','90');await page.locator('#rotateImage').evaluate(el=>el.click());await expect.poll(async()=>(await latest(page)).settings.imageOps[0].angle).toBe(90);expect((await latest(page)).settings.imageOps[0].quad).toEqual(before.settings.imageOps[0].quad);
 await page.locator('#resetImageRotation').evaluate(el=>el.click());await expect.poll(async()=>(await latest(page)).settings.imageOps[0].angle).toBe(0);expect((await latest(page)).settings.imageOps[0].quad).toEqual(before.settings.imageOps[0].quad);
});
test('codeword overlay is recalculated after loading another matrix of the same version',async({page})=>{
 await page.evaluate(()=>{window.overlayRequests=[];const send=Worker.prototype.postMessage;Worker.prototype.postMessage=function(data,...rest){if(data.request?.action==='overlay')window.overlayRequests.push(data.request.matrix.text);return send.call(this,data,...rest);};});
 const m=await execute(page,{action:'demo',text:'OVERLAY',damage:false});await importText(page,m.text);await page.locator('.steps [data-tab="edit"]').click();await page.locator('#codewordOverlay').check();
 await expect.poll(()=>page.evaluate(()=>window.overlayRequests.length)).toBe(1);
 const regions=await execute(page,{action:'regions',matrix:m,settings:{version:(m.text.split('\n').length-17)/4}});
 const n=m.text.split('\n').length,flat=[...m.text.replace(/\n/g,'')];regions.kinds.forEach((kind,i)=>{if(kind==='cell_region_format')flat[i]='?';});
 const text=Array.from({length:n},(_,y)=>flat.slice(y*n,(y+1)*n).join('')).join('\n');expect(text).not.toBe(m.text);
 await page.locator('#textFile').setInputFiles({name:'updated.txt',mimeType:'text/plain',buffer:Buffer.from(text)});await expect(page.locator('#recover')).toBeEnabled();await page.locator('.steps [data-tab="edit"]').click();
 await expect.poll(()=>page.evaluate(()=>window.overlayRequests.at(-1))).toBe(text);
});
