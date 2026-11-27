import {continueAfterOrdinaryReading} from './input-helpers.js';
import {clickWorkspaceAction,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
import {readFile,copyFile} from 'node:fs/promises';

async function workspace(page){
  await page.evaluate(()=>navigator.canShare=()=>false);
  const downloaded=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportSession');
  const envelope=JSON.parse(await readFile(await(await downloaded).path(),'utf8'));
  const session=JSON.parse(envelope.payload);return session.history[session.cursor];
}

test('image values remain readable at 800 percent while selection and exported source use original pixels',async({page},info)=>{
  // Observe the raster size of the actual glyphs, including the context transform.
  // The old source-sized canvas drew 1–2 px glyphs at 800%, producing square blobs.
  await page.addInitScript(()=>{
    window.imageGlyphs=[];
    const p=CanvasRenderingContext2D.prototype,fill=p.fillRect,text=p.fillText;
    p.fillRect=function(...args){if(this.canvas.id==='canvas'&&args[0]===0&&args[1]===0&&args[2]===this.canvas.width&&args[3]===this.canvas.height)window.imageGlyphs=[];return fill.apply(this,args);};
    p.fillText=function(value,...args){if(this.canvas.id==='canvas'&&/^[01]$/.test(String(value))){const m=this.getTransform();window.imageGlyphs.push({value:String(value),rasterHeight:parseFloat(this.font)*Math.hypot(m.c,m.d)});}return text.call(this,value,...args);};
  });
  await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
  await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();await page.locator('#imageView').click();
  const initial=await workspace(page),canvas=page.locator('#canvas');
  const raster=()=>canvas.evaluate(c=>({width:c.width,height:c.height,imageWidth:Number(c.dataset.imageWidth),imageHeight:Number(c.dataset.imageHeight),cssWidth:c.getBoundingClientRect().width,glyphs:window.imageGlyphs}));
  const before=await raster();await page.locator('#canvasScroll').screenshot({path:info.outputPath('image-values-100.png')});
  for(let i=0;i<6;i++)await page.locator('#zoomIn').click();await expect(page.locator('#zoomLabel')).toHaveText('800%');
  await page.locator('#canvasScroll').evaluate(e=>{e.scrollLeft=(e.scrollWidth-e.clientWidth)/2;e.scrollTop=(e.scrollHeight-e.clientHeight)/2;});
  const enlarged=await raster();await page.locator('#canvasScroll').screenshot({path:info.outputPath('image-values-800.png')});
  expect(enlarged.glyphs.length).toBe(441);expect(new Set(enlarged.glyphs.map(g=>g.value))).toEqual(new Set(['0','1']));
  expect(Math.min(...enlarged.glyphs.map(g=>g.rasterHeight))).toBeGreaterThanOrEqual(9.5);
  expect(enlarged.width).toBeGreaterThan(before.width);expect(enlarged.width*enlarged.height).toBeLessThanOrEqual(8_000_000);
  expect([enlarged.imageWidth,enlarged.imageHeight]).toEqual([232,232]);
  await info.attach('image-glyph-raster-sizes',{body:JSON.stringify({before,enlarged}),contentType:'application/json'});
  const afterZoom=await workspace(page);expect(afterZoom.matrix).toEqual(initial.matrix);expect(afterZoom.settings.grid).toEqual(initial.settings.grid);

  // The visible center still addresses source cell (10,10), not a backing-pixel cell.
  await page.locator('[data-tab="edit"]').click();await page.locator('#imageView').click();
  const point=initial.settings.grid[220],box=await canvas.boundingBox();
  await canvas.click({position:{x:point[0]/232*box.width,y:point[1]/232*box.height}});
  const value=initial.matrix.text.split('\n')[10][10]==='1'?'0':'1';await page.locator(`[data-value="${value}"]`).click();
  const edited=await workspace(page);expect(edited.matrix.text.split('\n')[10][10]).toBe(value);expect(edited.matrix.sources[220]).toBe('1');
  await page.locator('#undo').click();
  const restored=await workspace(page);expect(restored.matrix).toEqual(initial.matrix);
  await page.locator('[data-tab="input"]').click();await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();
  const downloaded=page.waitForEvent('download');await page.locator('#exportSource').click();const file=await(await downloaded).path();
  await copyFile(file,info.outputPath('sampling-source.png'));
  const exported=(await readFile(file)).toString('base64'),original=(await readFile('tests/fixtures/clean.png')).toString('base64');
  const pixels=await page.evaluate(async images=>Promise.all(images.map(async encoded=>{const image=new Image();image.src='data:image/png;base64,'+encoded;await image.decode();const c=document.createElement('canvas');c.width=image.width;c.height=image.height;const cx=c.getContext('2d');cx.drawImage(image,0,0);const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',cx.getImageData(0,0,c.width,c.height).data))).map(b=>b.toString(16).padStart(2,'0')).join('');return {width:c.width,height:c.height,hash};})),[original,exported]);
  expect(pixels[1]).toEqual(pixels[0]);
  await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});
