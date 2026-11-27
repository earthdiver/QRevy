import {continueAfterOrdinaryReading} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
test.use({hasTouch:true});
test('consecutive image dialogs restore scrolling before resolving and ignore prior close events',async({page})=>{
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 const states=await page.evaluate(async()=>{
  const {cropImage,fineCorners}=await import('/image-tools.js');
  const spacer=document.createElement('div');spacer.style.height='3000px';document.body.append(spacer);
  document.body.style.paddingBottom='7px';window.scrollTo(0,200);
  const originalStyle=document.body.style.cssText,originalScroll=window.scrollY;
  const image=document.createElement('canvas');image.width=image.height=100;
  const settings={quad:[10,10,90,10,90,90,10,90],version:1,assigned:false,finders:[]},states=[];
  for(let cycle=0;cycle<3;cycle++){
   const crop=cropImage(image);document.querySelector('#cropCancel').click();await crop;
   states.push({phase:'closed',style:document.body.style.cssText,scroll:window.scrollY});
   const corners=fineCorners(image,settings);
   await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
   states.push({phase:'open',position:document.body.style.position,top:document.body.style.top});
   document.querySelector(cycle%2?'#cornerApply':'#cornerCancel').click();await corners;
   states.push({phase:'closed',style:document.body.style.cssText,scroll:window.scrollY});
  }
  await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
  states.push({phase:'closed',style:document.body.style.cssText,scroll:window.scrollY});
  return {states,originalStyle,originalScroll};
 });
 expect(states.originalScroll).toBe(200);
 for(const state of states.states){
  if(state.phase==='open'){expect(state.position).toBe('fixed');expect(state.top).toBe('-200px');}
  else{expect(state.style).toBe(states.originalStyle);expect(state.scroll).toBe(states.originalScroll);}
 }
 await page.evaluate(()=>window.scrollTo(0,500));await expect.poll(()=>page.evaluate(()=>window.scrollY)).toBe(500);
});

test('image dialogs contain gestures and restore background scrolling; rapid arrows do not zoom',async({page},info)=>{
 await page.setViewportSize({width:390,height:844});await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.evaluate(()=>window.scrollTo(0,200));const scroll=await page.evaluate(()=>scrollY);
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');
 await expect(page.locator('body')).toHaveCSS('position','fixed');await expect(page.locator('body')).toHaveCSS('top',`-${scroll}px`);
 await expect(page.locator('.image-dialog')).toHaveCSS('overscroll-behavior','contain');
 await page.locator('#cropCanvas').evaluate(c=>{const e=new Event('touchmove',{bubbles:true,cancelable:true});c.dispatchEvent(e);if(!e.defaultPrevented)throw Error('Canvas gesture was not contained');});
 await page.locator('#cropCancel').click();await expect(page.locator('body')).not.toHaveCSS('position','fixed');await expect.poll(()=>page.evaluate(()=>scrollY)).toBe(scroll);
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();await expect(page.locator('body')).not.toHaveCSS('position','fixed');
 await page.locator('#canvas').screenshot({path:info.outputPath('visible-main-guides.png')});
 page.once('dialog',d=>d.accept());await page.locator('#version').selectOption('2');await expect(page.locator('#dimensions')).toContainText('V2');await expect(page.locator('#recover')).toBeEnabled();await page.locator('#canvas').screenshot({path:info.outputPath('visible-version-mismatch.png')});
 page.once('dialog',d=>d.accept());await page.locator('#version').selectOption('1');await expect(page.locator('#dimensions')).toContainText('V1');await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('#fineCorners').click();await page.locator('#cornerRight').scrollIntoViewIfNeeded();
 const before=await page.locator('#cornerPosition').textContent(),scale=await page.evaluate(()=>visualViewport.scale);
 await expect(page.locator('#cornerRight')).toHaveCSS('touch-action','manipulation');
 for(let i=0;i<6;i++)await page.locator('#cornerRight').tap();
 await expect(page.locator('#cornerPosition')).not.toHaveText(before);await expect(page.locator('#cornerZoom')).toHaveValue('5');expect(await page.evaluate(()=>visualViewport.scale)).toBe(scale);
 await page.locator('#cornerZoom').fill('2');await page.locator('#cornerCanvas').scrollIntoViewIfNeeded();await page.locator('#cornerCanvas').screenshot({path:info.outputPath('visible-corner-guides.png')});
 await page.locator('#cornerCancel').click();await expect(page.locator('body')).not.toHaveCSS('position','fixed');
});

test('a real touch drag changes the crop without moving the page or dialog',async({page,context,browserName})=>{
 test.skip(browserName!=='chromium','Native touch drag uses Chromium CDP');
 await page.setViewportSize({width:390,height:844});await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.evaluate(()=>window.scrollTo(0,180));const scroll=await page.evaluate(()=>scrollY);
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');
 const c=page.locator('#cropCanvas');await c.scrollIntoViewIfNeeded();const b=await c.boundingBox();const top=await page.locator('.image-dialog').evaluate(d=>d.scrollTop);

 const client=await context.newCDPSession(page);await client.send('Emulation.setTouchEmulationEnabled',{enabled:true});
 const point={x:b.x+2,y:b.y+2,id:1};await client.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[point]});
 for(let i=1;i<=5;i++)await client.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{...point,x:point.x+6*i,y:point.y+6*i}]});
 await client.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
 expect(Number(await page.locator('#cropX').inputValue())).toBeGreaterThan(5);expect(Number(await page.locator('#cropY').inputValue())).toBeGreaterThan(5);
 expect(await page.locator('.image-dialog').evaluate(d=>d.scrollTop)).toBe(top);await expect(page.locator('body')).toHaveCSS('top',`-${scroll}px`);
 await page.locator('#cropCancel').click();await expect.poll(()=>page.evaluate(()=>scrollY)).toBe(scroll);
});
