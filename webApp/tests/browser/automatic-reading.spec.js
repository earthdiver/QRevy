import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
import {changeRecoverySetting} from './input-helpers.js';
test.beforeEach(async({page})=>{await page.addInitScript(()=>{localStorage.setItem('qrevy-automatic-repair','false');localStorage.setItem('qrevy-language','ja');window.readingRequests=[];const post=Worker.prototype.postMessage;Worker.prototype.postMessage=function(message,...args){if(['sample','refine','inputData'].includes(message?.request?.action))window.readingRequests.push(structuredClone(message.request));return post.call(this,message,...args);};});});
async function photo(page,gray=false){
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 if(gray){
  const bytes=await readFile('tests/fixtures/clean.png');
  const png=await page.evaluate(async b=>{const image=new Image();image.src='data:image/png;base64,'+b;await image.decode();const c=document.createElement('canvas');c.width=image.width;c.height=image.height;const ctx=c.getContext('2d');ctx.drawImage(image,0,0);const pixels=ctx.getImageData(0,0,c.width,c.height);for(let i=0;i<pixels.data.length;i+=4)if(pixels.data[i]<128)for(let j=0;j<3;j++)pixels.data[i+j]=80;ctx.putImageData(pixels,0,0);return c.toDataURL().split(',')[1];},bytes.toString('base64'));
  await page.locator('#imageFile').setInputFiles({name:'synthetic-gray.png',mimeType:'image/png',buffer:Buffer.from(png,'base64')});
 }else await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');
 await page.locator('#cropApply').click();await expect(page.locator('#recover')).toBeEnabled();
}
const latest=page=>page.evaluate(async()=>{const s=(await(await import('/storage.js')).listSessions())[0];return s.history[s.cursor];});
async function selectCell(page,x=20,y=20){const canvas=page.locator('#canvas'),box=await canvas.boundingBox();await canvas.click({position:{x:box.width*(x+2.5)/25,y:box.height*(y+2.5)/25}});}
test('committing a sampling threshold updates observations and preserves fixed edits without a read button',async({page})=>{
 await photo(page,true);await expect(page.locator('#sample')).toHaveCount(0);await page.locator('[data-tab="edit"]').click();await selectCell(page);await page.locator('[data-value="0"]').click();await page.locator('#editKind').selectOption('1');
 await expect.poll(async()=>(await latest(page)).matrix.sources.at(-1)).toBe('1');const before=await latest(page);
 await page.locator('[data-tab="input"]').click();const count=await page.evaluate(()=>window.readingRequests.filter(r=>r.action==='sample'||r.action==='refine').length);
 await page.locator('#blackRange').evaluate(el=>{el.value='0';el.dispatchEvent(new Event('input',{bubbles:true}));});expect(await page.evaluate(()=>window.readingRequests.filter(r=>r.action==='sample'||r.action==='refine').length)).toBe(count);
 await page.locator('#blackRange').dispatchEvent('change');await expect.poll(async()=>(await latest(page)).settings.black).toBe(0);await expect(page.locator('#recover')).toBeEnabled();const after=await latest(page);
 expect(after.source.text).not.toBe(before.source.text);expect(after.matrix.text.at(-1)).toBe('0');expect(after.matrix.sources.at(-1)).toBe('1');
 await page.locator('[data-tab="edit"]').click();await page.locator('#undo').click();await expect.poll(async()=>(await latest(page)).matrix).toEqual(before.matrix);await page.locator('#redo').click();await expect.poll(async()=>(await latest(page)).matrix).toEqual(after.matrix);
});
test('opening pre-recovery data and editing cells refresh the display, including undo and redo',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));await photo(page);await page.locator('[data-tab="edit"]').click();await expect(page.locator('#inputData')).toHaveCount(0);
 expect(await page.evaluate(()=>window.readingRequests.filter(r=>r.action==='inputData').length)).toBe(0);await page.locator('#openInputData').click();const report=page.locator('#inputDataReport');await expect(report).toContainText('Binary');const original=await report.textContent();
 await page.locator('#closeInputData').click();await selectCell(page);await page.locator('[data-value="?"]').click();await page.locator('#openInputData').click();await expect(report).not.toHaveText(original);await expect(report).not.toBeEmpty();const edited=await report.textContent();
 await page.locator('#closeInputData').click();await page.locator('#undo').click();await page.locator('#openInputData').click();await expect(report).toHaveText(original);await page.locator('#closeInputData').click();await page.locator('#redo').click();await page.locator('#openInputData').click();await expect(report).toHaveText(edited);
 await page.locator('#inputPartial').check();await expect(report).toContainText('部分情報');await page.locator('#inputPartial').uncheck();await expect(report).toHaveText(edited);
 await page.locator('#inputUnmask').uncheck();await expect(report).not.toHaveText(edited);await expect(report).toContainText('Binary');await page.locator('#inputUnmask').check();await expect(report).toHaveText(edited);
 await page.locator('#closeInputData').click();await page.locator('#languageButton').click();await page.locator('#openInputData').click();await expect(report).toContainText('After unmasking');expect(errors).toEqual([]);
});
test('hidden input data stays idle and refreshes on return with current format settings',async({page})=>{
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles({name:'unknown.txt',mimeType:'text/plain',buffer:Buffer.from(Array(21).fill('?'.repeat(21)).join('\n'))});await page.locator('[data-tab="edit"]').click();await page.locator('#openInputData').click();await expect(page.locator('#inputDataReport')).not.toBeEmpty();
 await page.locator('#closeInputData').click();await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await expect(page.locator('#inputDataReport')).toBeEmpty();const count=await page.evaluate(()=>window.readingRequests.filter(r=>r.action==='inputData').length);
 await page.locator('[data-tab="edit"]').click();await page.locator('#openInputData').click();await expect(page.locator('#inputDataReport')).toContainText('Binary');await expect.poll(()=>page.evaluate(()=>window.readingRequests.filter(r=>r.action==='inputData').length)).toBeGreaterThan(count);
 const request=await page.evaluate(()=>window.readingRequests.filter(r=>r.action==='inputData').at(-1));expect(request.settings.ecc).toBe('L');expect(request.settings.mask).toBe(0);
});
