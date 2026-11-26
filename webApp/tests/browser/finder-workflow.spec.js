import {clickWorkspaceAction} from './input-helpers.js';
import {openAppMenu} from './input-helpers.js';
import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
import {nativeStrings} from '../../web/native-strings.js';

async function ready(page,automatic=false){
 await page.addInitScript(value=>{localStorage.setItem('qrevy-automatic-repair',String(value));localStorage.setItem('qrevy-language','ja');},automatic);
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
}
async function image(page,path){await page.locator('#imageFile').setInputFiles(path);await page.locator('#cropApply').click();}
async function settings(page){
 await page.evaluate(()=>navigator.canShare=()=>false);await openAppMenu(page);await page.locator('#workMenuButton').click();
 const event=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportSession');
 const outer=JSON.parse(await readFile(await(await event).path(),'utf8')),session=JSON.parse(outer.payload);
 if(await page.locator('#workMenu').isVisible())await page.locator('#closeWorkMenu').click();
 return session.history[session.cursor].settings;
}
async function detailed(page){
 await expect(page.locator('#automaticDialog')).toBeVisible({timeout:60000});await page.locator('#automaticDetailed').click();
 await expect(page.locator('body')).toHaveAttribute('data-active-tab','input');
}

test.describe('Finder placement rejection',()=>{
 test.use({serviceWorkers:'block'});
 for(const outcome of ['FOLDED','REJECTED','NOT_FOUND'])test(`${outcome} preserves the workspace and gives localized guidance`,async({page})=>{
  await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';self.onmessage=({data})=>{const result=data.request.action==='finder'?${JSON.stringify({confirmed:false,quad:null,outcome,placementRejected:outcome==='FOLDED'})}:JSON.parse(execute(data.request));self.postMessage({id:data.id,result});};self.postMessage({ready:true});`}));
  await ready(page);await image(page,'tests/fixtures/clean.png');await expect(page.locator('#recover')).toBeEnabled();
  await page.locator('#imageView').click();await page.locator('#fitView').click();
  const before=await settings(page);
  for(const language of ['ja','en']){
   if(language==='en')await page.locator('#languageButton').click();
   await page.locator('#finderRole').selectOption('0');
   const canvas=page.locator('#canvas'),bounds=await canvas.boundingBox();
   await canvas.click({position:{x:bounds.width*5.5/25,y:bounds.height*5.5/25}});
   const expected=outcome==='FOLDED'?(language==='ja'?'格子またはFinder役割を確認':'Check the grid or Finder roles'):outcome==='REJECTED'?(language==='ja'?'Finder候補は見つかりましたが':'Finder candidates were found'):(language==='ja'?'Finderを検出できませんでした':'No Finder was detected');
   await expect(page.locator('#toast')).toContainText(expected);
   await expect(page.locator('#toast')).not.toContainText('Failed requirement');
   await expect(page.locator('#recover')).toBeEnabled();
   expect(await settings(page)).toEqual(before);
  }
 });
});

test('a single Finder role selector controls assignment and selective removal',async({page})=>{
 await ready(page);await image(page,'tests/fixtures/clean.png');await expect(page.locator('#recover')).toBeEnabled();
 await expect(page.locator('#finderRole')).toHaveCount(1);await expect(page.locator('#removeFinderRole')).toHaveCount(0);
 await expect(page.locator('#removeFinder')).toBeDisabled();
 for(const language of ['ja','en','ja']){
  if(await page.locator('html').getAttribute('lang')!==language)await page.locator('#languageButton').click();
  await expect(page.locator('label').filter({has:page.locator('#finderRole')})).toContainText(nativeStrings.finder_manual[language]);
  await expect(page.locator('#finderRole option[value=""]')).toHaveText(language==='ja'?'役割を選択':'Select a role');
  for(const [value,key] of [['0','top_left'],['1','top_right'],['3','bottom_left']])await expect(page.locator(`#finderRole option[value="${value}"]`)).toHaveText(nativeStrings[key][language]);
  await page.locator('#finderRole').selectOption('1');
  await expect(page.locator('#finderHint')).toHaveText(nativeStrings.finder_placement_help[language].replace('%1$s',nativeStrings.top_right[language]));
  await page.locator('#finderRole').selectOption('');
  await expect(page.locator('#finderHint')).toContainText(language==='ja'?'四隅をドラッグ':'drag the corners');
 }
 expect((await settings(page)).finders.filter((_,i)=>i%3===0)).toEqual([0,1,3]);
 await page.locator('#finderRole').selectOption('1');await page.locator('#removeFinder').click();await expect(page.locator('#recover')).toBeEnabled();
 expect((await settings(page)).finders.filter((_,i)=>i%3===0)).toEqual([0,3]);
 await page.locator('#finderRole').selectOption('');await expect(page.locator('#removeFinder')).toBeDisabled();
 await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();await page.locator('#clearFinders').click();await expect(page.locator('#orientationNotice')).toBeVisible();
 expect((await settings(page)).finders).toEqual([]);
 await page.locator('#languageButton').click();await expect(page.locator('#removeFinder')).toHaveText('Clear selected Finder');await expect(page.locator('#clearFinders')).toHaveText('Clear all Finder assignments');
});

test('removing the last measured Finder retains the separately assigned role',async({page})=>{
 await ready(page);
 const fixture=(await readFile('tests/fixtures/clean.png')).toString('base64');
 const url=await page.evaluate(async fixture=>{
  const image=new Image();image.src='data:image/png;base64,'+fixture;await image.decode();
  const canvas=document.createElement('canvas');canvas.width=image.width;canvas.height=image.height;
  const context=canvas.getContext('2d');context.drawImage(image,0,0);context.fillStyle='#fff';context.fillRect(32,32,56,56);
  return canvas.toDataURL('image/png');
 },fixture);
 await page.locator('#imageFile').setInputFiles({name:'unmeasured-role.png',mimeType:'image/png',buffer:Buffer.from(url.split(',')[1],'base64')});
 await page.locator('#cropApply').click();await expect(page.locator('#canvas')).toHaveAttribute('data-image-width','232');await expect(page.locator('#progress')).not.toBeVisible();
 await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();
 if(await page.locator('#refine').isEnabled()&&await page.locator('#refine').isChecked())await page.locator('#refine').uncheck();
 await page.locator('#clearFinders').click();await expect(page.locator('#orientationNotice')).toBeVisible();
 await page.locator('#imageView').click();await page.locator('#fitView').click();
 const tap=async(role,x,y)=>{await page.locator('#finderRole').selectOption(String(role));const canvas=page.locator('#canvas'),bounds=await canvas.boundingBox();await canvas.click({position:{x:bounds.width*x/232,y:bounds.height*y/232}});await expect(page.locator('#recover')).toBeEnabled();};
 await tap(0,60,60);expect((await settings(page)).visibleFinderRole).toBe(0);
 if(await page.locator('#refine').isChecked())await page.locator('#refine').uncheck();
 await tap(1,172,60);const measured=await settings(page);expect(measured.visibleFinderRole).toBe(0);expect(measured.finders).toHaveLength(3);
 await page.locator('#finderRole').selectOption('1');await page.locator('#removeFinder').click();
 const removed=await settings(page);expect(removed.finders).toEqual([]);expect(removed.visibleFinderRole).toBe(0);expect(removed.assigned).toBe(true);
 await page.locator('#clearVisibleFinder').click();const cleared=await settings(page);expect(cleared.visibleFinderRole).toBeNull();expect(cleared.assigned).toBe(false);
});

test('detailed analysis opens the image after quick image repair',async({page})=>{
 await ready(page,true);await image(page,'tests/fixtures/clean.png');await detailed(page);
 await expect(page.locator('#imageView')).toHaveClass(/selected/);await expect(page.locator('#matrixView')).not.toHaveClass(/selected/);
 await page.locator('[data-tab="results"]').click();await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});

test('detailed analysis retains module view for text input',async({page})=>{
 await ready(page,true);await page.locator('#textFile').setInputFiles('tests/fixtures/priority-before.txt');await detailed(page);
 await expect(page.locator('#matrixView')).toHaveClass(/selected/);await expect(page.locator('#imageView')).toBeDisabled();
});

test('a tap outside a Finder asks before using the nearby center and cancelling keeps the previous grid',async({page})=>{
 await ready(page);await image(page,'tests/fixtures/clean.png');await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('[data-tab="input"]').click();await page.locator('#imageView').click();await page.locator('#fitView').click();
 const before=await settings(page);
 // The 21-module fixture has a two-module quiet zone; tap the data beside the top-left Finder.
 const tapOutside=async()=>{
  await page.locator('#finderRole').selectOption('0');
  const c=page.locator('#canvas'),b=await c.boundingBox();
  await c.click({position:{x:b.width*(7.6+2.5)/25,y:b.height*(3+2.5)/25}});
 };
 let message='';
 page.once('dialog',dialog=>{message=dialog.message();dialog.dismiss();});
 await tapOutside();
 await expect.poll(()=>message).toContain('Finderの外側');
 await expect(page.locator('#recover')).toBeEnabled();
 const cancelled=await settings(page);
 expect(cancelled.finders).toEqual(before.finders);expect(cancelled.quad).toEqual(before.quad);
 page.once('dialog',dialog=>dialog.accept());
 await tapOutside();
 await expect.poll(async()=>(await settings(page)).finders.slice(0,3)).not.toEqual([]);
 const accepted=await settings(page),i=accepted.finders.findIndex((v,k)=>k%3===0&&v===0),j=before.finders.findIndex((v,k)=>k%3===0&&v===0);
 expect(Math.hypot(accepted.finders[i+1]-before.finders[j+1],accepted.finders[i+2]-before.finders[j+2])).toBeLessThan(232/25*.25);
});

test('after clearing Finders on automatically estimated corners, an undetected Finder still names its role',async({page})=>{
 await ready(page,true);
 // A version 3 pattern at nine pixels per module with a two-module quiet zone, whose Finder cores are a
 // single module inside a two-module light ring. The detector does not recognize such Finders, while the
 // grid can still be estimated from the rest of the pattern.
 const n=29,quiet=2,total=n+2*quiet;
 const url=await page.evaluate(({n,quiet,total})=>{
  const unit=9,finders=[[0,0],[n-7,0],[0,n-7]];
  const dark=(x,y)=>{
   for(const [sx,sy] of finders){const dx=x-sx,dy=y-sy;if(dx>=-1&&dx<=7&&dy>=-1&&dy<=7){
    if(dx<0||dx>6||dy<0||dy>6)return false;
    return dx===0||dy===0||dx===6||dy===6||(dx===3&&dy===3);
   }}
   const ax=x-(n-7),ay=y-(n-7);if(Math.abs(ax)<=2&&Math.abs(ay)<=2)return Math.max(Math.abs(ax),Math.abs(ay))!==1;
   if(x===6)return y%2===0;if(y===6)return x%2===0;
   return (x*17+y*11+x*y)%7<3;
  };
  const c=document.createElement('canvas');c.width=c.height=total*unit;const cx=c.getContext('2d');cx.fillStyle='#fff';cx.fillRect(0,0,c.width,c.height);cx.fillStyle='#000';
  for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(dark(x,y))cx.fillRect((x+quiet)*unit,(y+quiet)*unit,unit,unit);
  return c.toDataURL('image/png');
 },{n,quiet,total});
 await page.locator('#imageFile').setInputFiles({name:'dotted-finders.png',mimeType:'image/png',buffer:Buffer.from(url.split(',')[1],'base64')});
 await page.locator('#cropApply').click();await detailed(page);
 await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();
 // Without LM correction the corners move only if a tap moves them.
 if(await page.locator('#refine').isChecked()&&await page.locator('#refine').isEnabled())await page.locator('#refine').uncheck();
 await page.locator('#clearFinders').click();await expect(page.locator('#orientationNotice')).toBeVisible();
 const before=await settings(page);expect(before.assigned).toBe(false);expect(before.lmEnabled).toBe(false);expect(before.version).toBe(3);
 await page.locator('#imageView').click();await page.locator('#fitView').click();
 const c=page.locator('#canvas');
 // Taps in modules from the top-left Finder center.
 const tap=async(dx,dy)=>{
  await page.locator('#toast').evaluate(el=>{el.textContent='';el.hidden=true;});
  await page.locator('#finderRole').selectOption('0');
  const b=await c.boundingBox();await c.click({position:{x:b.width*(quiet+3.5+dx)/total,y:b.height*(quiet+3.5+dy)/total}});
  await expect(page.locator('#toast')).toContainText('役割だけを指定しました');
 };
 await tap(0,0);
 const after=await settings(page);
 expect(after.assigned).toBe(true);expect(after.finders).toEqual([]);
 expect([...after.quad].sort((x,y)=>x-y)).toEqual([...before.quad].sort((x,y)=>x-y));
 // Tapping the same role again around the dot finds no Finder either: the light ring is not an inverted
 // Finder core, so nothing is offered and the grid stays where it is.
 const dialogs=[];page.on('dialog',dialog=>{dialogs.push(dialog.message());dialog.dismiss();});
 for(const [dx,dy] of [[0,1],[-1,2],[0,2],[1,0],[0,-1],[-1,0],[0,-2]]){
  await tap(dx,dy);
  expect(dialogs).toEqual([]);
  expect((await settings(page)).quad).toEqual(after.quad);
 }
});
