import {test,expect} from '@playwright/test';
test.use({serviceWorkers:'block'});
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();});
const latest=page=>page.evaluate(async()=>{const s=(await(await import('/storage.js')).listSessions())[0];return s.history[s.cursor];});
async function historyStep(page,direction){
 await page.locator('.steps [data-tab="edit"]').click();
 await expect(page.locator('#progress')).toBeHidden();
 await page.locator('#'+direction).click();
}
// Independent reference for Android's bounds translation and prepared-size cap.
function nativeRotation(w,h,angle,x,y){const a=angle*Math.PI/180,c=Math.cos(a),s=Math.sin(a),left=Math.min(0,w*c,-h*s,w*c-h*s),top=Math.min(0,w*s,h*c,w*s+h*c),rw=Math.ceil(Math.abs(w*c)+Math.abs(h*s)),rh=Math.ceil(Math.abs(w*s)+Math.abs(h*c)),scale=Math.min(1,1800/Math.max(rw,rh)),ow=Math.round(rw*scale),oh=Math.round(rh*scale);return {size:[ow,oh],point:[((x+.5)*c-(y+.5)*s-left)*ow/rw-.5,((x+.5)*s+(y+.5)*c-top)*oh/rh-.5]};}
async function installFixtureHelpers(page){await page.evaluate(async()=>{
 const {execute}=await import('/engine/js/engine.js');window.run=r=>{const v=JSON.parse(execute(r));if(v.error)throw new Error(v.error);return v;};
 window.fixture=(w=1800,h=1800,ox=500,oy=500)=>{const matrix=run({action:'demo',text:'PARITY AUDIT',damage:false}),rows=matrix.text.split('\n'),n=rows.length,version=(n-17)/4,pitch=20;const image=document.createElement('canvas');image.width=w;image.height=h;const cx=image.getContext('2d');cx.fillStyle='white';cx.fillRect(0,0,w,h);cx.fillStyle='black';rows.forEach((r,y)=>[...r].forEach((v,x)=>{if(v==='1')cx.fillRect(ox+x*pitch,oy+y*pitch,pitch,pitch);}));const x=ox+9.5,y=oy+9.5,last=(n-1)*pitch;return {image,matrix,settings:{version,quad:[x,y,x+last,y,x+last,y+last,x,y+last],lmEnabled:false,assigned:true,automatic:false,black:64,white:192,quietZone:false}};};
 window.sample=(image,settings)=>{const rgba=image.getContext('2d').getImageData(0,0,image.width,image.height).data,pixels=Array.from({length:image.width*image.height},(_,i)=>(rgba[i*4]<<16)|(rgba[i*4+1]<<8)|rgba[i*4+2]);return run({action:'sample',width:image.width,height:image.height,pixels,settings}).matrix.text;};
});}
test('untransformed legacy QRR retains a large source image and its grid through rotation reset',async({page})=>{
 await installFixtureHelpers(page);
 const f=await page.evaluate(async()=>{
  const {packQrr}=await import('/qrr.js'),{image,matrix,settings}=fixture(2400,1200,1300,200);
  const encoded=run({action:'qrrEncode',id:'large-zero',history:JSON.stringify([{matrix,source:matrix,settings:{...settings,imageOps:[]}}]),cursor:0});
  // With no perspective tag or reference angle, the payload is also schema 41.
  const qrr=await packQrr({...encoded,version:41}),png=await new Promise(r=>image.toBlob(r,'image/png'));
  return {expected:matrix.text,quad:settings.quad,qrr:[...new Uint8Array(await qrr.arrayBuffer())],png:[...new Uint8Array(await png.arrayBuffer())]};
 });
 await page.locator('#sessionFile').setInputFiles({name:'source-v41.qrr',mimeType:'application/octet-stream',buffer:Buffer.from(f.qrr)});await expect.poll(()=>page.locator('#attachImage').evaluate(el=>el.hidden)).toBe(false);
 await page.locator('#companionImageFile').setInputFiles({name:'source.png',mimeType:'image/png',buffer:Buffer.from(f.png)});await expect(page.locator('#toast')).toContainText('元画像を追加しました');
 await expect.poll(async()=>(await latest(page)).matrix.text).toBe(f.expected);expect((await latest(page)).settings.quad).toEqual(f.quad);
 expect(await page.locator('#canvas').getAttribute('data-image-width')).toBe('2400');expect(await page.locator('#canvas').getAttribute('data-image-height')).toBe('1200');
 await page.locator('#imageAngle').evaluate(el=>el.value='90');await page.locator('#rotateImage').evaluate(el=>el.onclick());
 expect((await latest(page)).matrix.text).toBe(f.expected);
 await page.locator('#resetImageRotation').evaluate(el=>el.onclick());
 const reset=await latest(page);expect(reset.matrix.text).toBe(f.expected);reset.settings.quad.forEach((v,i)=>expect(v).toBeCloseTo(f.quad[i],6));
 expect(await page.locator('#canvas').getAttribute('data-image-width')).toBe('2400');
});
test('empty operations and explicit zero transforms have identical sizes and coordinate maps',async({page})=>{
 const result=await page.evaluate(async()=>{const {prepareImage,imageTransform}=await import('/image-tools.js'),image=document.createElement('canvas');image.width=2400;image.height=1200;return [[],[{type:'source',angle:0,quad:null,version:1,legacyAngle:null}]].map(ops=>{const raster=prepareImage(image,ops),layout=imageTransform(2400,1200,ops);return {size:[raster.width,raster.height],layoutSize:[layout.w,layout.h],point:layout.fromSource(2000,900),inverse:layout.toSource(2000,900)};});});
 for(const r of result)expect(r).toEqual({size:[2400,1200],layoutSize:[2400,1200],point:[2000,900],inverse:[2000,900]});
});
test('Android legacy QRR uses Android bounds and scaling, including small oblique rotations',async({page})=>{
 const cases=[[1800,1800,45,509.5,509.5],[137,103,17.3,20,30],[1800,1200,-37,750,300]];
 const results=await page.evaluate(async cases=>{const {imageTransform,prepareImage}=await import('/image-tools.js');return cases.map(([w,h,angle,x,y])=>{const image=document.createElement('canvas');image.width=w;image.height=h;const op={type:'source',angle,quad:null,version:2,legacyAngle:angle,legacyRaster:'android'},t=imageTransform(w,h,[op]),r=prepareImage(image,[op]);return {size:[r.width,r.height],layoutSize:[t.w,t.h],point:t.fromSource(x,y)};});},cases);
 results.forEach((actual,i)=>{const expected=nativeRotation(...cases[i]);expect(actual.size).toEqual(expected.size);expect(actual.layoutSize).toEqual(expected.size);actual.point.forEach((v,j)=>expect(v).toBeCloseTo(expected.point[j],3));});
});
test('Android legacy rotation and perspective preserve every synthetic QR module after QRR decoding',async({page})=>{
 await installFixtureHelpers(page);
 const result=await page.evaluate(async()=>{
  const {prepareImage,imageTransform,mapPoints}=await import('/image-tools.js'),{image,matrix,settings}=fixture();
  // Golden coordinates from Android's pixel-bounds formula, not the PWA layout.
  const c=Math.SQRT1_2,rw=2546,scale=1800/rw,map=(x,y)=>[((x+.5)*c-(y+.5)*c+1800*c)*scale-.5,((x+.5)*c+(y+.5)*c)*scale-.5];
  const rotatedQuad=mapPoints(settings.quad,map),checks=[];
  for(const perspective of [false,true])for(const angle of [45,62]){
   const op={type:'source',angle,quad:perspective?rotatedQuad:null,version:settings.version,legacyAngle:45,legacyRaster:'android'};
   const t=imageTransform(1800,1800,[op]),quad=mapPoints(settings.quad,t.fromSource);
   const encodedOp=angle===45&&!perspective?{...op,legacyAngle:null}:op;
   const encoded=run({action:'qrrEncode',id:'android-legacy',history:JSON.stringify([{matrix,source:matrix,settings:{...settings,quad,imageOps:[encodedOp]}}]),cursor:0});
   const decoded=run({action:'qrrDecode',...encoded,...(angle===45?{version:41}:{})}).history[0].settings;
   if(angle===45&&!perspective)quad.forEach((v,i)=>{if(Math.abs(v-rotatedQuad[i])>.001)throw new Error('Android coordinate mismatch');});
   checks.push({op:decoded.imageOps[0],text:sample(prepareImage(image,decoded.imageOps),decoded)});
  }
  return {checks,expected:matrix.text};
 });
 for(const check of result.checks){expect(check.op.legacyRaster).toBe('android');expect(check.text).toBe(result.expected);}
});
test('PWA legacy backups keep their raster, while QRR exports remap observations and saved corrections',async({page})=>{
 await installFixtureHelpers(page);
 const result=await page.evaluate(async()=>{
  const {prepareImage,imageTransform,sourceTransform,settingsForQrr,mapPoints}=await import('/image-tools.js'),{image,matrix,settings}=fixture(),checks=[];
  const pixelHash=c=>{const a=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let h=0;for(const v of a)h=(h*31+v)|0;return [c.width,c.height,h];};
  for(const perspective of [false,true])for(const angle of [45,62]){
   const rotate=[{type:'rotate',angle:45}],quad=mapPoints(settings.quad,imageTransform(1800,1800,rotate).fromSource);
   const originalOps=perspective?[...rotate,{type:'rectify',quad,version:settings.version}]:rotate,originalInfo=sourceTransform(originalOps),ops=angle===45?originalOps:[{...originalInfo,angle}],info=sourceTransform(ops),layout=imageTransform(1800,1800,ops),q=mapPoints(settings.quad,layout.fromSource),finder=layout.fromSource(569.5,569.5);
   const s={...settings,curved:true,lmEnabled:true,imageOps:ops,quad:q,lmBaseQuad:q,lmAppliedQuad:q,previewQuad:q,finders:[0,...finder],warp:[0,0,2,3,.1,1,0,2,3,.1,0,1,2,3,.1]};
   const before=JSON.stringify(s),converted=settingsForQrr(image,s),encoded=run({action:'qrrEncode',id:'pwa-conversion',history:JSON.stringify([{matrix,source:matrix,settings:converted}]),cursor:0}),decoded=run({action:'qrrDecode',...encoded}).history[0].settings;
   const t=imageTransform(1800,1800,decoded.imageOps),center=t.toSource(decoded.finders[1],decoded.finders[2]);
   const oldPoint=[q[0]+2,q[1]+3],expected=t.fromSource(...layout.toSource(...oldPoint)),actual=[decoded.previewQuad[0]+decoded.warp[2],decoded.previewQuad[1]+decoded.warp[3]];
   checks.push({hashes:[pixelHash(prepareImage(image,ops)),pixelHash(prepareImage(image,[info])),pixelHash(prepareImage(image,[{...info,legacyRaster:undefined}]))],center,displacedError:Math.hypot(actual[0]-expected[0],actual[1]-expected[1]),unmutated:before===JSON.stringify(s),text:sample(prepareImage(image,decoded.imageOps),{...decoded,warp:null,previewQuad:null})});
  }
  return {checks,expected:matrix.text};
 });
 for(const c of result.checks){expect(c.hashes[1]).toEqual(c.hashes[0]);expect(c.hashes[2]).toEqual(c.hashes[0]);expect(c.unmutated).toBe(true);c.center.forEach(v=>expect(v).toBeCloseTo(569.5,5));expect(c.displacedError).toBeLessThan(1e-6);expect(c.text).toBe(result.expected);}
});
for(const oldRepresentation of ['source','operations'])test(`previously saved QRR ${oldRepresentation} imports acquire the Android raster tag when reopened`,async({page})=>{
 await installFixtureHelpers(page);
 await page.evaluate(async oldRepresentation=>{
  const {saveSession,digest}=await import('/storage.js'),{mapPoints}=await import('/image-tools.js'),{image,matrix,settings}=fixture();
  const c=Math.SQRT1_2,scale=1800/2546,map=(x,y)=>[((x+.5)*c-(y+.5)*c+1800*c)*scale-.5,((x+.5)*c+(y+.5)*c)*scale-.5];
  const op={type:'source',angle:45,quad:null,version:settings.version,legacyAngle:45};
  const encoded=run({action:'qrrEncode',id:'saved-import',history:JSON.stringify([{matrix,source:matrix,settings:{...settings,quad:mapPoints(settings.quad,map),imageOps:[op]}}]),cursor:0}),s=run({action:'qrrDecode',...encoded});
  // Reproduce the metadata written before legacy raster kinds were distinguished.
  delete s.history[0].settings.imageOps[0].legacyRaster;if(oldRepresentation==='operations')s.history[0].settings.imageOps=[{type:'rotate',angle:45}];s.image=await new Promise(r=>image.toBlob(r,'image/png'));s.imageHash=await digest(await s.image.arrayBuffer());await saveSession(s);window.expectedMatrix=matrix.text;
 },oldRepresentation);
 await page.locator('#sessionsButton').evaluate(el=>el.onclick());await page.locator('#sessionList .session-item button').first().click();await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('#colorUnknown').evaluate(el=>el.onchange());
 const h=await latest(page);expect(h.settings.imageOps[0].legacyRaster).toBe('android');expect(h.matrix.text).toBe(await page.evaluate(()=>window.expectedMatrix));
});
for(const representation of ['source','operations'])for(const [importedAngle,retainedLmBase] of [[0,false],[45,false],[45,true]])test(`QRR imported at ${importedAngle} degrees retains PWA-edited ${representation} history and checkpoint${retainedLmBase?' with retained LM base':''}`,async({page})=>{
 await installFixtureHelpers(page);
 const expected=await page.evaluate(async({representation,importedAngle,retainedLmBase})=>{
  const {saveSession,digest}=await import('/storage.js'),{androidRotationLayout,rotatedLayout,mapPoints}=await import('/image-tools.js'),{image,matrix,settings}=fixture();
  const originalQuad=mapPoints(settings.quad,androidRotationLayout(1800,1800,importedAngle).map);
  const native={type:'source',angle:importedAngle,quad:null,version:settings.version,legacyAngle:importedAngle||null};
  const encoded=run({action:'qrrEncode',id:'imported-edited',history:JSON.stringify([{matrix,source:matrix,settings:{...settings,quad:originalQuad,imageOps:[native]}}]),cursor:0}),s=run({action:'qrrDecode',...encoded});
  delete s.history[0].settings.imageOps[0].legacyRaster;
  if(representation==='operations')s.history[0].settings.imageOps=importedAngle?[{type:'rotate',angle:importedAngle}]:[];
  const h=structuredClone(s.history[0]),layout=rotatedLayout(1800,1800,45),quad=mapPoints(settings.quad,layout.map),finders=[0,...layout.map(569.5,569.5)];
  // qrrIndex/qrrRaw remain inherited even after a PWA transform. A later tone
  // edit (or trimmed history) need not retain an imageOps change record.
  // Manual corner edits while LM was already disabled retained the imported
  // lmBaseQuad in the old PWA. It is not the current sampling geometry.
  const lmBaseQuad=retainedLmBase?h.settings.lmBaseQuad:null;
  h.settings={...h.settings,quad,lmBaseQuad,lmAppliedQuad:null,lmEnabled:false,finders,assigned:false,imageOps:representation==='operations'?[{type:'rotate',angle:45}]:[{type:'source',angle:45,quad:null,version:settings.version,legacyAngle:45}]};
  h.kind='SETTINGS';h.changes=[{key:'black',before:63,after:64}];h.timestamp++;h.matrix.text=h.matrix.text.slice(0,-1)+'?';h.matrix.sources=h.matrix.sources.slice(0,-1)+'1';
  s.history.push(h);s.cursor=1;s.checkpoint=structuredClone(h);s.checkpointCursor=1;
  s.image=await new Promise(r=>image.toBlob(r,'image/png'));s.imageHash=await digest(await s.image.arrayBuffer());await saveSession(s);
  return {text:h.matrix.text,sources:h.matrix.sources,quad,finders,originalQuad,lmBaseQuad};
 },{representation,importedAngle,retainedLmBase});
 await page.locator('#sessionsButton').evaluate(el=>el.onclick());await page.locator('#sessionList .session-item button').first().click();await expect(page.locator('#progress')).toBeHidden();await expect(page.locator('#recover')).toBeDisabled();await expect(page.locator('#orientationNotice')).toBeVisible();
 await page.locator('#colorUnknown').evaluate(el=>el.onchange());
 await expect.poll(async()=>(await latest(page)).matrix.text).toBe(expected.text);
 const s=await page.evaluate(async()=>{const s=(await(await import('/storage.js')).listSessions())[0];return {history:s.history,checkpoint:s.checkpoint,cursor:s.cursor};});
 for(const h of [s.history[1],s.history[s.cursor],s.checkpoint]){
  expect(h.settings.imageOps[0].legacyRaster).toBe('pwa');expect(h.settings.quad).toEqual(expected.quad);expect(h.settings.finders).toEqual(expected.finders);expect(h.matrix.text).toBe(expected.text);expect(h.matrix.sources.at(-1)).toBe('1');
  expect(h.settings.lmBaseQuad).toEqual(expected.lmBaseQuad);
 }
 if(importedAngle)expect(s.history[0].settings.imageOps[0].legacyRaster).toBe('android');expect(s.history[0].settings.quad).toEqual(expected.originalQuad);
 // Undo, reopening, and QRPWA backup restoration must preserve both bases.
 await historyStep(page,'undo');await historyStep(page,'undo');
 await expect.poll(async()=>(await latest(page)).settings.quad).toEqual(expected.originalQuad);
 if(importedAngle)expect((await latest(page)).settings.imageOps[0].legacyRaster).toBe('android');
 await historyStep(page,'redo');await historyStep(page,'redo');
 await expect.poll(async()=>(await latest(page)).settings.quad).toEqual(expected.quad);
 expect((await latest(page)).settings.imageOps[0].legacyRaster).toBe('pwa');
 const backup=await page.evaluate(async()=>{const store=await import('/storage.js'),s=(await store.listSessions())[0];const file=await store.exportWorkspace(s);return [...new Uint8Array(await file.arrayBuffer())];});
 await page.locator('#sessionFile').setInputFiles({name:'mixed-bases.qrpwa',mimeType:'application/json',buffer:Buffer.from(backup)});await expect(page.locator('#progress')).toBeHidden();await expect(page.locator('#recover')).toBeDisabled();await expect(page.locator('#orientationNotice')).toBeVisible();
 await page.locator('#colorUnknown').evaluate(el=>el.onchange());expect((await latest(page)).matrix.text).toBe(expected.text);expect((await latest(page)).settings.imageOps[0].legacyRaster).toBe('pwa');
 if(retainedLmBase){
  // Old inactive LM coordinates must not replace the edited grid in later
  // operations. Undo restores that same legacy state before each action.
  for(const action of ['rotate','reset','rectify','brightness'])await test.step(`after migration: ${action}`,async()=>{
   if(action==='rotate'){await page.locator('#imageAngle').evaluate(el=>el.value='90');await page.locator('#rotateImage').evaluate(el=>el.onclick());}
   else if(action==='reset')await page.locator('#resetImageRotation').evaluate(el=>el.onclick());
   else if(action==='rectify')await page.locator('#rectifyImage').evaluate(el=>el.onclick());
   else await page.locator('#brightness').evaluate(async el=>{el.value='1';await el.onchange();});
   await expect.poll(async()=>(await latest(page)).settings.lmBaseQuad).toBeNull();
   const h=await latest(page);expect(h.matrix.text).toBe(expected.text);expect(h.matrix.sources.at(-1)).toBe('1');expect(h.settings.lmEnabled).toBe(false);expect(h.settings.lmAppliedQuad).toBeNull();
   if(action==='brightness')expect(h.settings.quad).toEqual(expected.quad);
   await historyStep(page,'undo');
   await expect.poll(async()=>(await latest(page)).settings.lmBaseQuad).toEqual(expected.lmBaseQuad);
   expect((await latest(page)).settings.quad).toEqual(expected.quad);
  });
 }
});

for(const lmEnabled of [true,false])test(`saved QRR retains Android LM geometry with LM ${lmEnabled?'enabled':'disabled'}`,async({page})=>{
 await installFixtureHelpers(page);
 const expected=await page.evaluate(async lmEnabled=>{
  const {saveSession,digest}=await import('/storage.js'),{androidRotationLayout,mapPoints,prepareImage}=await import('/image-tools.js'),{image,matrix,settings}=fixture();
  const quad=mapPoints(settings.quad,androidRotationLayout(1800,1800,45).map),base=quad.map((v,i)=>v+(i%2?-3:4));
  const imageOps=[{type:'source',angle:45,quad:null,version:settings.version,legacyAngle:45,legacyRaster:'android'}];
  const encoded=run({action:'qrrEncode',id:'saved-native-lm',history:JSON.stringify([{matrix,source:matrix,settings:{...settings,quad,lmBaseQuad:base,lmAppliedQuad:quad,lmEnabled:true,imageOps}}]),cursor:0}),s=run({action:'qrrDecode',...encoded}),h=s.history[0];
  // Disabling LM restores the original manual quad but retains the saved LM
  // result. Both states still use Android image coordinates.
  if(!lmEnabled){h.settings.lmEnabled=false;h.settings.quad=structuredClone(h.settings.lmBaseQuad);}
  // Compare against sampling the native geometry before migration. The
  // unrefined manual grid need not read as accurately as the LM result.
  const raster=prepareImage(image,h.settings.imageOps),text=sample(raster,h.settings),withoutLm=sample(raster,{...h.settings,lmEnabled:false,quad:h.settings.lmBaseQuad});
  delete h.settings.imageOps[0].legacyRaster;
  s.image=await new Promise(r=>image.toBlob(r,'image/png'));s.imageHash=await digest(await s.image.arrayBuffer());await saveSession(s);
  return {text,withoutLm,quad:h.settings.quad,base:h.settings.lmBaseQuad,applied:h.settings.lmAppliedQuad};
 },lmEnabled);
 await page.locator('#sessionsButton').evaluate(el=>el.onclick());await page.locator('#sessionList .session-item button').first().click();await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('#colorUnknown').evaluate(el=>el.onchange());
 await expect.poll(async()=>(await latest(page)).matrix.text).toBe(expected.text);
 const h=await latest(page);expect(h.settings.imageOps[0].legacyRaster).toBe('android');expect(h.settings.lmEnabled).toBe(lmEnabled);expect(h.settings.quad).toEqual(expected.quad);expect(h.settings.lmBaseQuad).toEqual(expected.base);expect(h.settings.lmAppliedQuad).toEqual(expected.applied);
 if(lmEnabled){
  await page.locator('#refine').evaluate(async el=>{el.checked=false;await el.onchange();});
  await expect.poll(async()=>(await latest(page)).settings.lmEnabled).toBe(false);
  const off=await latest(page);expect(off.settings.quad).toEqual(expected.base);expect(off.matrix.text).toBe(expected.withoutLm);expect(off.settings.lmBaseQuad).toBeNull();expect(off.settings.lmAppliedQuad).toBeNull();
  await historyStep(page,'undo');await expect.poll(async()=>(await latest(page)).settings.lmEnabled).toBe(true);
  // Tone changes rerun LM from its manual grid, not from the previous result.
  await page.locator('#brightness').evaluate(async el=>{el.value='1';await el.onchange();});
  await expect.poll(async()=>(await latest(page)).settings.brightness).toBe(1);
  const adjusted=await latest(page);expect(adjusted.settings.lmEnabled).toBe(true);expect(adjusted.settings.lmBaseQuad).toEqual(expected.base);expect(adjusted.settings.lmAppliedQuad).toEqual(adjusted.settings.quad);
 }else{
  // Editing corners while LM is already off must clear stale correction data.
  await page.locator('#fineCorners').evaluate(el=>{el.onclick();});
  await page.locator('#cornerRight').evaluate(el=>el.onclick());await page.locator('#cornerApply').evaluate(el=>el.onclick());
  await expect.poll(async()=>(await latest(page)).settings.quad).not.toEqual(expected.quad);
  const edited=await latest(page);expect(edited.settings.quad[0]).toBeGreaterThan(expected.quad[0]);expect(edited.settings.quad.slice(1)).toEqual(expected.quad.slice(1));expect(edited.settings.lmBaseQuad).toBeNull();expect(edited.settings.lmAppliedQuad).toBeNull();
  await page.locator('#brightness').evaluate(async el=>{el.value='1';await el.onchange();});
  await expect.poll(async()=>(await latest(page)).settings.brightness).toBe(1);expect((await latest(page)).settings.quad).toEqual(edited.settings.quad);
 }
});

test('migration preserves explicit raster tags and falls back to PWA without a matching source snapshot',async({page})=>{
 const result=await page.evaluate(async()=>{
  const {migrateLegacyImages,needsLegacyImageMigration}=await import('/session-migration.js');
  const q=[10,10,100,10,100,100,10,100],op={type:'source',angle:45,quad:null,version:1,legacyAngle:45};
  const entry=(qrrIndex,legacyRaster)=>({qrrIndex,settings:{version:1,quad:q,imageOps:[{...op,...(legacyRaster?{legacyRaster}:{})}]}});
  const session={qrrRaw:'retained',history:[entry(0,'pwa'),entry(0,'android'),entry(-1),entry(99)]};
  session.checkpoint=entry(0);session.history[0].automatic={input:{settings:entry(-1).settings},retrySettings:entry(-1).settings};
  const needs=needsLegacyImageMigration(session);migrateLegacyImages(session,[entry(0,'android')]);
  const missing={qrrRaw:'unavailable',history:[entry(0)]};migrateLegacyImages(missing);
  return {needs,again:needsLegacyImageMigration(session),tags:session.history.map(h=>h.settings.imageOps[0].legacyRaster),checkpoint:session.checkpoint.settings.imageOps[0].legacyRaster,input:session.history[0].automatic.input.settings.imageOps[0].legacyRaster,retry:session.history[0].automatic.retrySettings.imageOps[0].legacyRaster,missing:missing.history[0].settings.imageOps[0].legacyRaster};
 });
 expect(result).toEqual({needs:true,again:false,tags:['pwa','android','pwa','pwa'],checkpoint:'android',input:'android',retry:'android',missing:'pwa'});
});
