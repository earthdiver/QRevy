import {changeEvidenceControl,runRecovery,setAutomaticRepair} from './input-helpers.js';
import {test,expect} from '@playwright/test';

test.use({serviceWorkers:'block'});
const fixture=name=>'tests/fixtures/'+name;
const idle=async page=>{await page.waitForFunction(()=>window.review&&!window.review.busy);};
const state=page=>page.evaluate(()=>structuredClone(review.state));
async function reveal(page,id){await page.locator('#'+id).evaluate(e=>{for(let p=e.parentElement;p;p=p.parentElement)if(p.tagName==='DETAILS')p.open=true;});}
async function click(page,id){await reveal(page,id);await page.locator('#'+id).click();await idle(page);}
async function textInput(page,name){await page.locator('#textFile').setInputFiles(fixture(name));await page.waitForFunction(()=>review.state.matrix&&!review.busy);}
async function photo(page){await page.locator('#imageFile').setInputFiles(fixture('clean.png'));await click(page,'cropApply');await page.waitForFunction(()=>review.state.image&&review.state.matrix&&!review.busy);}
async function demo(page){await click(page,'demo');await page.waitForFunction(()=>review.state.matrix&&!review.busy);}
async function repaired(page){await demo(page);await click(page,'recover');await expect(page.locator('#payload')).not.toHaveValue('');}
async function compare(page){await repaired(page);await click(page,'resultEvidenceTab');await changeEvidenceControl(page,'#resultStage','selectOption','compare');}
async function imageFromDemo(page){await demo(page);await click(page,'matrixToImage');await click(page,'detect');}

test.beforeEach(async({page})=>{
 await page.addInitScript(()=>{localStorage.setItem('qrevy-language','en');localStorage.setItem('qrevy-automatic-repair','false');});
 // Read-only probes: all inputs and mutations still use the product UI.
 await page.route('**/app.js',async route=>{const response=await route.fetch();await route.fulfill({response,body:(await response.text())+'\nwindow.review={get state(){return state},get result(){return result},get curves(){return curve.candidates},get busy(){return !!recoveryWorker.pending}};'});});
 await page.goto('/');await idle(page);
});

test('one-pixel-high images keep a nonzero crop preview and can be imported',async({page})=>{
 const png=await page.evaluate(()=>{const c=document.createElement('canvas');c.width=4000;c.height=1;return c.toDataURL().split(',')[1];});
 await page.locator('#imageFile').setInputFiles({name:'thin.png',mimeType:'image/png',buffer:Buffer.from(png,'base64')});
 expect(await page.locator('#cropCanvas').evaluate(c=>c.width>0&&c.height>0)).toBe(true);
 await click(page,'cropApply');await page.waitForFunction(()=>!!review.state.matrix);await expect(page.locator('#toast')).not.toContainText('getImageData');
});

test('selecting a different automatic candidate updates the displayed assumptions',async({page})=>{
 await setAutomaticRepair(page,true);await textInput(page,'ambiguous-assumption.txt');
 await expect(page.locator('#automaticDialog')).toBeVisible();await click(page,'automaticClose');await click(page,'browseCandidates');
 await page.locator('#candidates .candidate').filter({hasText:'á'}).click();await idle(page);
 await expect(page.locator('#payload')).toHaveValue('á');await expect(page.locator('#automaticStatus')).not.toContainText('assumes UTF-8');
});

for(const action of ['clearFinders','removeFinder'])test(`${action} is exactly one undoable history entry`,async({page})=>{
 await imageFromDemo(page);const before=await state(page);if(action==='removeFinder'){await reveal(page,'finderRole');await page.locator('#finderRole').selectOption('0');}
 await click(page,action);const after=await state(page);expect(after.history.length).toBe(before.history.length+1);expect(after.settings.finders).not.toEqual(before.settings.finders);
 await click(page,'openCellEditor');await click(page,'undo');expect((await state(page)).settings.finders).toEqual(before.settings.finders);
});

test('failure guidance opens and focuses the format settings',async({page})=>{
 await textInput(page,'invalid-format.txt');await click(page,'recover');await click(page,'nextResultAction');
 await expect(page.locator('#ecc')).toBeVisible();await expect(page.locator('#ecc')).toBeFocused();
});

test('one-time retry reports its condition and displays the repaired QR',async({page})=>{
 await textInput(page,'retry-single.txt');await click(page,'recover');await expect(page.locator('#resultStage')).toHaveValue('before');
 await click(page,'acceptSingleRetry');await page.waitForFunction(()=>review.result?.candidates.length>0);
 await expect(page.locator('#executedSettings')).toContainText(/single.segment/i);await expect(page.locator('#resultStage')).toHaveValue('after');
 expect((await state(page)).settings.single).toBe(false);
});

test('automatic binary results disable text copy',async({page})=>{
 await setAutomaticRepair(page,true);await textInput(page,'unknown-eci.txt');
 await expect(page.locator('#automaticDialog')).toBeVisible();await expect(page.locator('#automaticCopy')).toBeDisabled();
});

test('both comparison panes can pan the synchronized view',async({page})=>{
 await compare(page);for(let i=0;i<3;i++)await click(page,'zoomIn');await click(page,'panView');
 const read=()=>page.evaluate(()=>['beforePane','afterPane'].map(id=>{const e=document.getElementById(id);return [e.scrollLeft,e.scrollTop];}));
 for(const id of ['beforeCanvas','canvas']){
  const previous=await read();await page.locator('#canvasScroll').scrollIntoViewIfNeeded();const r=await page.locator('#'+id).boundingBox();const pane=await page.locator('#'+(id==='canvas'?'afterPane':'beforePane')).boundingBox();
  const x=pane.x+Math.min(140,pane.width-20),y=Math.max(pane.y,r.y)+80;
  await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x-60,y-30,{steps:8});await page.mouse.up();
  await expect.poll(async()=>(await read())[0][0]).toBeGreaterThan(previous[0][0]);
  // Scroll synchronization is applied by queued scroll events.
  await expect.poll(async()=>{const [before,after]=await read();return before.map((value,i)=>value-after[i]);}).toEqual([0,0]);
 }
});

test('the applied curved grid matches every point of the chosen candidate',async({page})=>{
 await photo(page);await reveal(page,'curved');await page.locator('#curved').check();await idle(page);
 const error=await page.evaluate(()=>{const s=review.state.settings,c=review.curves[Number(document.getElementById('curveChoice').value)||0];if(!c)throw Error('No curve candidate');return Math.max(...s.grid.map((p,i)=>Math.hypot(p[0]-c.grid[i][0],p[1]-c.grid[i][1])));});
 expect(error).toBeLessThan(1e-7);
});

test('progress and cancel remain usable after opening results during recovery',async({page})=>{
 await textInput(page,'long-recovery.txt');await runRecovery(page);await page.locator('.steps [data-tab="results"]').click();
 await expect(page.locator('#progress')).toBeVisible();await expect(page.locator('#cancel')).toBeVisible();await page.locator('#cancel').click();await idle(page);
});

test('fitting an image after comparison always produces a positive scale',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));await imageFromDemo(page);await click(page,'recover');await click(page,'resultEvidenceTab');
 await changeEvidenceControl(page,'#resultStage','selectOption','compare');await click(page,'imageView');await click(page,'fitView');
 await expect.poll(async()=>parseFloat(await page.locator('#zoomLabel').textContent())).toBeGreaterThan(0);expect(errors).toEqual([]);
});

test('zooming out never enlarges a fitted tall image',async({page})=>{
 const png=await page.evaluate(()=>{const c=document.createElement('canvas');c.width=100;c.height=500;return c.toDataURL().split(',')[1];});
 await page.locator('#imageFile').setInputFiles({name:'tall.png',mimeType:'image/png',buffer:Buffer.from(png,'base64')});await click(page,'cropApply');await click(page,'imageView');await click(page,'fitView');
 const width=await page.locator('#canvas').evaluate(c=>c.getBoundingClientRect().width);await click(page,'zoomOut');expect(await page.locator('#canvas').evaluate(c=>c.getBoundingClientRect().width)).toBeLessThan(width);
});

test('content comparison gives both QR previews the same size',async({page})=>{
 await compare(page);
 const sizes=await page.evaluate(()=>['beforeCanvas','canvas'].map(id=>{const r=document.getElementById(id).getBoundingClientRect();return [r.width,r.height];}));
 expect(sizes[0]).toEqual(sizes[1]);
 const panes=await page.evaluate(()=>['beforePane','afterPane'].map(id=>{const pane=document.getElementById(id);return [pane.clientWidth,pane.clientHeight];}));
 for(const [index,size] of sizes.entries())for(const [axis,dimension] of size.entries()){
  expect(dimension).toBeGreaterThan(0);expect(dimension).toBeLessThanOrEqual(panes[index][axis]);
 }
});

test('TXT version changes refresh the error-limit labels in both directions',async({page})=>{
 await textInput(page,'utf16-bom-be.txt');page.on('dialog',d=>d.accept());await reveal(page,'version');
 await page.locator('#version').selectOption('40');await idle(page);await expect(page.locator('#dataLimitMode')).toContainText('1483');
 await page.locator('#version').selectOption('1');await idle(page);await expect(page.locator('#dataLimitMode')).toContainText('11 bits');
});

test('right mouse clicks do not write brush cells but left clicks still do',async({page})=>{
 await demo(page);await page.locator('.steps [data-tab="edit"]').click();await page.locator('#cellTool').selectOption('brush0');const before=await state(page);
 const pos=await page.locator('#canvas').evaluate(c=>{const r=c.getBoundingClientRect(),n=review.state.matrix.text.split('\n').length;return {x:r.width*2.5/(n+4),y:r.height*2.5/(n+4)};});
 await page.locator('#canvas').click({button:'right',position:pos});await idle(page);expect((await state(page)).matrix).toEqual(before.matrix);expect((await state(page)).history.length).toBe(before.history.length);
 await page.locator('#canvas').click({button:'left',position:pos});await idle(page);expect((await state(page)).matrix.text[0]).toBe('0');expect((await state(page)).history.length).toBe(before.history.length+1);
});

test('priority editing an unlocked-corner QRR can be exported and reopened',async({page})=>{
 await page.locator('#sessionFile').setInputFiles(fixture('unlocked-corners.qrr'));await page.waitForFunction(()=>review.state.matrix&&!review.busy);
 await page.locator('.steps [data-tab="edit"]').click();const r=await page.locator('#canvas').boundingBox();await page.mouse.click(r.x+r.width*34.5/37,r.y+r.height*34.5/37);await page.locator('[data-value="*"]').click();await idle(page);
 const expected=(await state(page)).matrix.text;await click(page,'appMenuButton');await click(page,'workMenuButton');
 const download=page.waitForEvent('download');await click(page,'exportQrr');const file=await (await download).path();expect(file).toBeTruthy();
 await page.locator('#sessionFile').setInputFiles({name:'roundtrip.qrr',mimeType:'application/octet-stream',buffer:await(await import('node:fs/promises')).readFile(file)});await idle(page);expect((await state(page)).matrix.text).toBe(expected);
});

test('physical arrow keys move the selected editable image corner',async({page})=>{
 await photo(page);const before=(await state(page)).settings.quad;await page.locator('#canvas').scrollIntoViewIfNeeded();
 const pos=await page.locator('#canvas').evaluate((c,q)=>{const r=c.getBoundingClientRect();return {x:q[4]*r.width/Number(c.dataset.imageWidth),y:q[5]*r.height/Number(c.dataset.imageHeight)};},before);
 await page.locator('#canvas').click({position:pos});await page.keyboard.press('ArrowRight');await idle(page);
 const after=(await state(page)).settings.quad;expect(after[4]).toBeGreaterThan(before[4]);expect(after[5]).toBeCloseTo(before[5],8);
});

test('small-screen preview controls remain reachable without pinning the editing dock',async({page})=>{
 await page.setViewportSize({width:320,height:568});await demo(page);await page.locator('.steps [data-tab="edit"]').click();await page.evaluate(()=>window.scrollTo(0,600));
 await expect(page.locator('#previewDock')).toHaveCSS('position','static');
 await page.locator('#zoomIn').scrollIntoViewIfNeeded();
 await expect.poll(()=>page.evaluate(()=>{const h=document.querySelector('header').getBoundingClientRect(),b=document.getElementById('zoomIn'),r=b.getBoundingClientRect();return r.top>=h.bottom&&b.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2));})).toBe(true);
 await page.locator('#zoomIn').click();await expect(page.locator('#zoomLabel')).not.toHaveText('100%');
});

test('result disclosures support keyboard expansion',async({page})=>{
 await textInput(page,'invalid-format.txt');await click(page,'recover');await page.locator('#resultDiagnosticTab').focus();
 await page.keyboard.press('Enter');await expect(page.locator('#resultDiagnosticSection')).not.toHaveAttribute('open','');
 await page.keyboard.press('Enter');await expect(page.locator('#resultDiagnosticSection')).toHaveAttribute('open','');
});
