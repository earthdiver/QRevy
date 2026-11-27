import {continueAfterOrdinaryReading} from './input-helpers.js';
import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
import {runRecovery,clickInputAction} from './input-helpers.js';
test.beforeEach(async({page})=>{await page.addInitScript(()=>{localStorage.setItem('qrevy-automatic-repair','false');localStorage.setItem('qrevy-language','ja');});});
async function photo(page){await page.goto('/');await installFont(page);await expect(page.locator('#imageImport')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();}
const size=page=>page.evaluate(()=>{const r=id=>{const e=document.getElementById(id),b=e.getBoundingClientRect();return {width:b.width,height:b.height,scrollLeft:e.scrollLeft,scrollTop:e.scrollTop};};return {canvas:r('canvas'),viewport:r('canvasScroll'),dock:r('previewDock'),zoom:document.getElementById('zoomLabel').textContent};});
test('image viewport and fitted size stay stable across repeated zoom and pan',async({page},info)=>{
 await photo(page);await page.locator('#imageView').click();await page.locator('#fitView').click();await page.waitForTimeout(100);const initial=await size(page);
 for(let i=0;i<8;i++){
  await page.locator('#zoomIn').click();await page.locator('#panView').click();const v=await page.locator('#canvasScroll').boundingBox();await page.mouse.move(v.x+v.width*.7,v.y+v.height*.7);await page.mouse.down();await page.mouse.move(v.x+v.width*.4,v.y+v.height*.4,{steps:5});await page.mouse.up();await page.locator('#panView').click();await page.locator('#fitView').click();
 }
 await page.waitForTimeout(100);const final=await size(page);await page.locator('#toast').waitFor({state:'hidden'});await page.screenshot({path:info.outputPath('repeated-image-gestures.png')});expect(final.viewport.height).toBeGreaterThanOrEqual(initial.viewport.height-1);expect(final.canvas.width).toBeCloseTo(initial.canvas.width,0);await expect(page.locator('#compactPreview')).toHaveCount(0);const tools=await page.locator('#viewTools').boundingBox();for(const id of ['imageView','matrixView','zoomIn','zoomOut','panView','fitView']){const b=await page.locator('#'+id).boundingBox();expect(b.x+b.width).toBeLessThanOrEqual(tools.x+tools.width+1);}
});
test('zooming changes the image size without resizing its viewport',async({page})=>{
 await photo(page);const initial=await size(page);await page.locator('#zoomOut').click();await page.waitForTimeout(100);const smaller=await size(page);expect(smaller.viewport.height).toBeCloseTo(initial.viewport.height,0);expect(smaller.canvas.width).toBeLessThan(initial.canvas.width);
});

async function installFont(page){if(!process.env.QREVY_QA_FONT)return;const bytes=await readFile(process.env.QREVY_QA_FONT);await page.evaluate(async b=>{const f=new FontFace('QRevyQA',`url(data:font/ttf;base64,${b})`);await f.load();document.fonts.add(f);},bytes.toString('base64'));await page.addStyleTag({content:'body,button,input,select,textarea{font-family:QRevyQA,sans-serif}pre{font-family:monospace,QRevyQA,sans-serif}'});}
for(const width of [320,390])test(`data uses a large dedicated reader and returns to the editor at width ${width}`,async({page},info)=>{
 await page.setViewportSize({width,height:844});await page.goto('/');await installFont(page);await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();await expect(page.locator('#repairSummary')).toHaveCount(0);await page.locator('[data-tab="edit"]').click();await page.locator('#openInputData').click();const panel=page.locator('#inputDataPanel'),report=page.locator('#inputDataReport');await expect(panel).toBeVisible();await expect(report).toContainText('Binary');
 const r=await report.boundingBox(),v=page.viewportSize();expect(r.height).toBeGreaterThan(v.height*.5);expect(r.width).toBeGreaterThan(v.width*.8);await expect(page.locator('#closeInputData')).toBeInViewport();await expect(page.locator('#inputUnmask')).toBeInViewport();
 await report.evaluate(el=>el.scrollTop=el.scrollHeight);await expect.poll(()=>report.evaluate(el=>el.scrollTop)).toBeGreaterThan(0);await expect(page.locator('#closeInputData')).toBeInViewport();await report.evaluate(el=>el.scrollTop=0);await page.screenshot({path:info.outputPath('data-reader.png')});
 await page.locator('#inputPartial').check();await expect(report).toContainText('部分情報');await page.locator('#closeInputData').click();await expect(panel).toBeHidden();await expect(page.locator('#openInputData')).toBeFocused();await expect(page.locator('#undo')).toBeVisible();
 await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');await expect(page.locator('#metrics')).not.toContainText('最小RS余裕');
});
test('native two-finger zoom preserves capture, viewport size and subsequent panning',async({page,context},info)=>{
 test.skip(info.project.use.browserName!=='chromium','Native multi-touch is available through CDP');await page.setViewportSize({width:390,height:844});await photo(page);await page.locator('#panView').click();await page.locator('#canvasScroll').scrollIntoViewIfNeeded();const initial=await size(page),cdp=await context.newCDPSession(page);
 await page.evaluate(()=>{window.lostDuringGesture=0;window.touchStarts=0;window.touchEnds=0;const active=new Set();document.addEventListener('pointerdown',e=>{if(e.pointerType==='touch'&&e.target.id==='canvas'){active.add(e.pointerId);window.touchStarts++;}},true);document.addEventListener('pointerup',e=>{if(active.delete(e.pointerId))window.touchEnds++;},true);document.addEventListener('lostpointercapture',()=>{if(active.size===2)window.lostDuringGesture++;},true);});
 for(let i=0;i<5;i++){
  const b=await page.locator('#canvasScroll').boundingBox(),x=b.x+b.width/2,y=b.y+b.height/2;
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:x-30,y,id:1},{x:x+30,y,id:2}]});
  for(const distance of [40,50,45,30])await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x-distance,y,id:1},{x:x+distance,y,id:2}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
 }
 const end=await size(page);expect(end.viewport.height).toBeCloseTo(initial.viewport.height,0);expect(end.canvas.width).toBeCloseTo(initial.canvas.width,0);expect(await page.evaluate(()=>window.lostDuringGesture)).toBe(0);expect(await page.evaluate(()=>[window.touchStarts,window.touchEnds])).toEqual([10,10]);
 await page.locator('#zoomIn').click();await page.locator('#zoomIn').click();const b=await page.locator('#canvasScroll').boundingBox();await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:b.x+b.width*.6,y:b.y+b.height*.6,id:1}]});await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:b.x+b.width*.3,y:b.y+b.height*.3,id:1}]});await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});expect((await size(page)).viewport.scrollLeft).toBeGreaterThan(0);
});
