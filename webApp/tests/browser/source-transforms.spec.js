import {test,expect} from '@playwright/test';
import {openAppMenu} from './input-helpers.js';
test.use({serviceWorkers:'block'});
const latest=page=>page.evaluate(async()=>{const s=(await(await import('/storage.js')).listSessions())[0];return s.history[s.cursor];});
const click=async(page,id)=>{await expect(page.locator('#'+id)).toBeEnabled();await page.locator('#'+id).evaluate(el=>el.onclick());};
async function photo(page){await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await click(page,'cropApply');await expect(page.locator('#recover')).toBeEnabled();}
async function angle(page,value){await page.locator('#imageAngle').evaluate((el,v)=>el.value=v,value);await click(page,'rotateImage');await expect(page.locator('#recover')).toBeEnabled();}
test('source transformations preserve logical edits, Finder observations and correction preferences',async({page})=>{
 await photo(page);
 // Pin a module and request both corrections in a saved workspace.
 await page.evaluate(async()=>{const store=await import('/storage.js'),s=(await store.listSessions())[0],h=s.history[s.cursor];h.matrix.text=h.matrix.text.replace(/^[01?]/,'?');h.matrix.sources='1'+h.matrix.sources.slice(1);h.settings.curved=true;s.history=[h];s.cursor=0;await store.saveSession(s);});
 await openAppMenu(page);await click(page,'sessionsButton');await page.locator('#sessionList .session-item button').first().click();await expect(page.locator('#recover')).toBeEnabled();
 await page.evaluate(()=>{window.transformRequests=[];const send=Worker.prototype.postMessage;Worker.prototype.postMessage=function(data,...rest){if(['refine','curve'].includes(data.request?.action))window.transformRequests.push(data.request.action);return send.call(this,data,...rest);};});
 const initial=await latest(page);expect(initial.settings.assigned).toBe(true);expect(initial.settings.finders.length).toBeGreaterThan(0);
 await click(page,'rectifyImage');await expect.poll(async()=>(await latest(page)).settings.imageOps[0]?.quad).toBeTruthy();
 const rectified=await latest(page);
 await angle(page,37);await expect.poll(async()=>(await latest(page)).settings.imageOps[0].angle).toBe(37);
 const rotated=await latest(page);expect(rotated.settings.imageOps[0].quad).toEqual(rectified.settings.imageOps[0].quad);
 expect(rotated.settings.finders).not.toEqual(rectified.settings.finders);
 await click(page,'resetImageRotation');await expect.poll(async()=>(await latest(page)).settings.imageOps[0].angle).toBe(0);
 const reset=await latest(page);
 for(const h of [rectified,rotated,reset]){expect(h.settings.lmEnabled).toBe(true);expect(h.settings.curved).toBe(true);expect(h.settings.assigned).toBe(true);expect(h.matrix.sources[0]).toBe('1');expect(h.matrix.text[0]).toBe('?');expect(h.settings.finders.length).toBe(initial.settings.finders.length);}
 reset.settings.finders.forEach((v,i)=>expect(v).toBeCloseTo(rectified.settings.finders[i],6));
 expect(await page.evaluate(()=>window.transformRequests.filter(x=>x==='curve').length)).toBeGreaterThanOrEqual(3);
 await angle(page,19);await click(page,'clearPerspective');await expect.poll(async()=>(await latest(page)).settings.imageOps[0].quad).toBeNull();expect((await latest(page)).settings.imageOps[0].angle).toBe(19);
});
test('legacy rasters remain identical and source transforms round-trip source coordinates',async({page})=>{
 await page.goto('/');
 const result=await page.evaluate(async()=>{
  const {prepareImage,sourceTransform,imageTransform}=await import('/image-tools.js');
  const image=document.createElement('canvas');image.width=137;image.height=103;const c=image.getContext('2d');c.fillStyle='white';c.fillRect(0,0,137,103);c.fillStyle='black';c.fillRect(20,25,60,45);
  const pixels=out=>{const d=document.createElement('canvas');d.width=out.width;d.height=out.height;const cx=d.getContext('2d');cx.drawImage(out,0,0);return [...cx.getImageData(0,0,d.width,d.height).data];};
  const ops=[{type:'rotate',angle:17.3},{type:'rectify',version:1,quad:[20,20,100,25,105,95,15,90]}],source=sourceTransform(ops);
  const old=pixels(prepareImage(image,ops)),restored=pixels(prepareImage(image,[source]));
  let error=0;
  for(const legacyAngle of [null,17.3])for(const angle of [0,17.3,90,180,37]){
   const t=imageTransform(137,103,[{...source,angle,legacyAngle}]);
   for(const p of [[25,30],[70,50],[105,90]]){const r=t.toSource(...t.fromSource(...p));error=Math.max(error,Math.hypot(r[0]-p[0],r[1]-p[1]));}
  }
  return {same:JSON.stringify(old)===JSON.stringify(restored),error};
 });expect(result.same).toBe(true);expect(result.error).toBeLessThan(1e-8);
});
test('Finder alignment retains other assigned centers and rejects unsupported placements',async({page})=>{
 await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();
 const result=await page.evaluate(async()=>{
  const {execute}=await import('/engine/js/engine.js'),{quadMap}=await import('/image-tools.js');
  const settings={version:1,lmEnabled:false,quad:[10,10,210,10,210,210,10,210],finders:[1,180,40,3,40,180]};
  const width=280,pixels=Array(width*width).fill(0xffffff);
  for(const [left,top] of [[10,8],[195,195]])for(let row=0;row<70;row++)for(let column=0;column<70;column++){
   const moduleRow=Math.floor(row/10),moduleColumn=Math.floor(column/10);
   if(moduleRow===0||moduleRow===6||moduleColumn===0||moduleColumn===6||(moduleRow>=2&&moduleRow<=4&&moduleColumn>=2&&moduleColumn<=4))pixels[(top+row)*width+left+column]=0;
  }
  const request={action:'finder',width,height:width,pixels,settings,alignGrid:true,gridAssigned:true,role:0};
  const fitted=JSON.parse(execute({...request,x:45,y:43})),bad=JSON.parse(execute({...request,x:230,y:230})),empty=JSON.parse(execute({...request,pixels:Array(width*width).fill(0xffffff),x:45,y:43}));
  return {fitted,centers:fitted.quad?[[3,3],[17,3],[3,17]].map(([x,y])=>quadMap(fitted.quad,x/20,y/20)):[],bad,empty};
 });expect(result.fitted.confirmed).toBe(true);expect(Math.hypot(result.fitted.x-44.5,result.fitted.y-42.5)).toBeLessThanOrEqual(.5);for(const [index,expected]of [[0,[result.fitted.x,result.fitted.y]],[1,[180,40]],[2,[40,180]]])result.centers[index].forEach((value,axis)=>expect(value).toBeCloseTo(expected[axis],6));expect(result.fitted.quad.slice(4,6)).toEqual([210,210]);
 expect(result.bad).toMatchObject({confirmed:false,quad:null,outcome:'REJECTED'});expect(result.empty).toMatchObject({confirmed:false,quad:null,outcome:'NOT_FOUND'});
});
test('mirrored logical orientation survives repeated perspective application and QRR restoration',async({page})=>{
 await photo(page);
 await click(page,'mirror');await click(page,'rectifyImage');await angle(page,90);await click(page,'rectifyImage');await click(page,'resetImageRotation');
 const h=await latest(page),q=h.settings.quad;
 expect((q[2]-q[0])*(q[5]-q[3])-(q[3]-q[1])*(q[4]-q[2])).toBeLessThan(0);
 const restored=await page.evaluate(async h=>{
  const {execute}=await import('/engine/js/engine.js');
  const encoded=JSON.parse(execute({action:'qrrEncode',id:'mirrored-transforms',history:JSON.stringify([h]),cursor:0}));
  const decoded=JSON.parse(execute({action:'qrrDecode',...encoded}));return {version:encoded.version,entry:decoded.history[0]};
 },h);
 expect(restored.version).toBe(42);expect(restored.entry.settings.imageOps).toEqual(h.settings.imageOps);expect(restored.entry.settings.finders).toEqual(h.settings.finders);expect(restored.entry.matrix.text).toEqual(h.matrix.text);
});
