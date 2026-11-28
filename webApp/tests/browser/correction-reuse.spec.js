import {test,expect} from '@playwright/test';
import {clickInputAction,openAppMenu} from './input-helpers.js';

test.use({serviceWorkers:'block'});
const workspace=page=>page.evaluate(async()=>(await (await import('/storage.js')).listSessions()).find(s=>s.image));
const current=async page=>{const s=await workspace(page);return s.history[s.cursor];};
const requests=(page,action)=>page.evaluate(action=>window.correctionRequests.filter(r=>r.action===action),action);
async function change(page,id,value){
 await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('#'+id).evaluate(async(el,value)=>{if(el.type==='checkbox')el.checked=value;else el.value=String(value);await el.onchange();},value);
 await expect(page.locator('#recover')).toBeEnabled();
 await expect.poll(async()=>(await current(page)).settings[id==='refine'?'lmEnabled':id]).toBe(value);
}
async function reopen(page){
 await openAppMenu(page);await page.locator('#sessionsButton').click();
 await page.locator('#sessionList .session-item button').first().click();
 await expect(page.locator('#sessionsDialog')).not.toBeVisible();
 await expect(page.locator('#recover')).toBeEnabled();
}
async function fixture(page,{curved=false,confirmed=true,failedLm=false,curveFailure=false,planar=false}={}){
 await page.addInitScript(()=>{
  localStorage.setItem('qrevy-automatic-repair','false');window.correctionRequests=[];window.holdCorrections=false;
  const post=Worker.prototype.postMessage;
  Worker.prototype.postMessage=function(message,...args){
   if(message.request){
    message.request.reviewHold=window.holdCorrections;
    message.request.reviewFailure=window.failCorrections;
    message.request.reviewDetection=window.detectionResult;
   }
   if(message.request)window.correctionRequests.push({action:message.request.action,settings:structuredClone(message.request.settings),selected:message.request.selected});
   return post.call(this,message,...args);
  };
 });
 // Deterministic correction outcomes exercise UI state transitions; cell sampling
 // and provenance still run through the real engine on a generated QR image.
 await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`
 import {execute} from './engine/js/engine.js';
 const run=r=>JSON.parse(execute(r));
 const sampled=(r,quad)=>run({...r,action:'sample',settings:{...r.settings,quad,previewQuad:quad},refreshPolarity:false});
 let curveCalls=0;
 self.onmessage=async({data})=>{try{
  const r=data.request;let result;
  if(r.reviewHold&&['curve','refineCurve'].includes(r.action)){
   while(!r.stop||Atomics.load(r.stop,0)===0)await new Promise(resolve=>setTimeout(resolve,10));
   await new Promise(resolve=>setTimeout(resolve,250));
  }
  if(r.reviewFailure&&['curve','refineCurve'].includes(r.action))throw new Error('Synthetic curve failure');
  if(r.action==='detect'&&r.reviewDetection){result=r.reviewDetection;}else if(r.action==='refine'){
   const quad=r.settings.quad.map(v=>v+.25);result={...sampled(r,quad),quad,refined:${!failedLm}};
  }else if(r.action==='curve'){
   if(${curveFailure}&&curveCalls++===0)throw new Error('Synthetic curve failure');
   const warp=${planar}?null:[0,0,0,0,.1,1,0,0,0,.1,0,1,0,0,.1];
   const candidates=${curved}?[0,1,2].map((_,i)=>{const quad=r.settings.quad.map(v=>v+i*.1);return {quad,warp,grid:sampled({...r,settings:{...r.settings,warp}},quad).grid,refined:r.settings.lmEnabled&&i===0,unknownCount:3-i};}):[];
   result={candidates,memoryLimited:false};
  }else if(r.action==='refineCurve'){
   const quad=r.settings.previewQuad,warp=r.settings.warp;
   result={quad,warp,grid:sampled(r,quad).grid,refined:true,order:[r.selected,...r.candidates.map((_,i)=>i).filter(i=>i!==r.selected)]};
  }else result=run(r);
  self.postMessage({id:data.id,result});
 }catch(e){self.postMessage({id:data.id,error:e.message});}};self.postMessage({ready:true});
 `}));
 await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('.steps [data-tab="input"]').click();await page.locator('#inputChoices > summary').click();await clickInputAction(page,'#matrixToImage');
 await expect.poll(async()=>!!await workspace(page)).toBe(true);await expect(page.locator('#recover')).toBeEnabled();
 await page.evaluate(async confirmed=>{
  const {listSessions,saveSession}=await import('/storage.js');const s=(await listSessions()).find(s=>s.image),h=s.history[s.cursor];
  h.settings.confirmedPolarity=confirmed?'NORMAL':'UNKNOWN';h.settings.polarity='NORMAL';h.settings.polarityTone=[0,0,false];
  h.settings.colorClusters=[5000,0,0,1000];
  // A fixed manual cell must survive all reclassification and correction toggles.
  h.matrix.sources=h.matrix.sources.slice(0,-1)+'1';s.settings=h.settings;s.matrix=h.matrix;
  await saveSession(s);
 },confirmed);
 await reopen(page);await page.evaluate(()=>{window.correctionRequests=[];});
}

test('reading controls preserve a successful LM fit, fixed cells and its OFF/ON result',async({page})=>{
 await fixture(page);await change(page,'refine',true);
 const fitted=await current(page);expect((await requests(page,'refine')).length).toBe(1);
 for(const [id,value] of [['black',100],['white',180],['automatic',true],['colorUnknown',true],['polarity','INVERTED']]){
  await change(page,id,value);const h=await current(page);
  expect(h.settings.quad).toEqual(fitted.settings.quad);expect(h.settings.grid).toEqual(fitted.settings.grid);
  expect(h.matrix.sources.at(-1)).toBe('1');expect(h.matrix.text.at(-1)).toBe(fitted.matrix.text.at(-1));
 }
 expect((await requests(page,'refine')).length).toBe(1);
 await change(page,'refine',false);expect((await current(page)).settings.quad).toEqual(fitted.settings.lmBaseQuad);
 await change(page,'refine',true);expect((await current(page)).settings.quad).toEqual(fitted.settings.quad);
 expect((await requests(page,'refine')).length).toBe(1);
 await change(page,'brightness',1);expect((await requests(page,'refine')).length).toBe(2);
 await page.locator('#fineCorners').evaluate(el=>{el.onclick();});
 await page.locator('#cornerRight').evaluate(el=>el.onclick());await page.locator('#cornerApply').evaluate(el=>el.onclick());
 await expect.poll(async()=>(await current(page)).settings.lmEnabled).toBe(false);
 await change(page,'refine',true);expect((await requests(page,'refine')).length).toBe(3);
});

test('an effective polarity change invalidates the fit',async({page})=>{
 await fixture(page,{confirmed:false});await change(page,'refine',true);
 await change(page,'polarity','INVERTED');expect((await requests(page,'refine')).length).toBe(2);
});

test('an empty curve search is reused across reading controls, undo and reopening',async({page})=>{
 await fixture(page);await change(page,'curved',true);
 expect((await requests(page,'curve')).length).toBe(1);
 for(const [id,value] of [['colorUnknown',true],['black',100],['white',180],['automatic',true]])await change(page,id,value);
 expect((await requests(page,'curve')).length).toBe(1);
 await page.locator('.steps [data-tab="edit"]').click();await page.locator('#undo').click();
 await expect.poll(async()=>(await current(page)).settings.automatic).toBe(false);
 await page.locator('#redo').click();await expect.poll(async()=>(await current(page)).settings.automatic).toBe(true);
 await reopen(page);await change(page,'colorUnknown',false);
 expect((await requests(page,'curve')).length).toBe(1);
 await change(page,'brightness',1);expect((await requests(page,'curve')).length).toBe(2);
});

test('curve selection survives threshold edits and refinement uses the original ranking profile',async({page})=>{
 await fixture(page,{curved:true});await change(page,'refine',true);await change(page,'curved',true);
 const original=await current(page);
 await page.locator('#curveChoice').evaluate(async el=>{el.value='1';await el.onchange();});
 await expect.poll(async()=>(await requests(page,'refineCurve')).length).toBe(1);
 const selected=await current(page);
 expect(await page.locator('#curveChoice').inputValue()).toBe('0');
 for(const [id,value] of [['black',100],['white',180],['automatic',true],['colorUnknown',true]]){
  await change(page,id,value);const h=await current(page);
  expect(h.settings.previewQuad).toEqual(selected.settings.previewQuad);expect(h.settings.warp).toEqual(selected.settings.warp);
  expect(h.settings.grid).toEqual(selected.settings.grid);expect(h.matrix.sources.at(-1)).toBe('1');
 }
 expect((await requests(page,'curve')).length).toBe(1);
 await page.locator('#curveChoice').evaluate(async el=>{el.value='2';await el.onchange();});
 await expect.poll(async()=>(await requests(page,'refineCurve')).length).toBe(2);
 const refinement=(await requests(page,'refineCurve')).at(-1);
 for(const key of ['black','white','automatic','colorUnknown'])expect(refinement.settings[key]).toEqual(original.settings[key]);
 expect(await page.locator('#curveChoice').inputValue()).toBe('0');
 // Returning to an already refined candidate must not fit it again after sorting.
 await page.locator('#curveChoice').evaluate(async el=>{el.value='1';await el.onchange();});
 expect((await requests(page,'refineCurve')).length).toBe(2);
});

test('an unsuccessful LM fit is reused for reading edits but OFF/ON retries it',async({page})=>{
 await fixture(page,{failedLm:true});await change(page,'refine',true);
 expect((await current(page)).settings.lmFailed).toBe(true);
 await change(page,'black',100);expect((await requests(page,'refine')).length).toBe(1);
 await change(page,'refine',false);await change(page,'refine',true);
 expect((await requests(page,'refine')).length).toBe(2);
 expect((await current(page)).settings.lmFailed).toBe(true);
});

test('curve calculation errors are reused until an explicit retry',async({page})=>{
 await fixture(page,{curveFailure:true});await change(page,'curved',true);
 await expect(page.locator('#curveStatus')).toContainText('補正計算に失敗');
 const original=await current(page);
 await change(page,'black',100);await change(page,'colorUnknown',true);await reopen(page);
 expect((await requests(page,'curve')).length).toBe(1);await expect(page.locator('#curveStatus')).toContainText('補正計算に失敗');
 expect((await current(page)).matrix.sources.at(-1)).toBe('1');expect((await current(page)).matrix.text.at(-1)).toBe(original.matrix.text.at(-1));
 await change(page,'curved',false);await change(page,'curved',true);
 expect((await requests(page,'curve')).length).toBe(2);await expect(page.locator('#curveStatus')).not.toContainText('失敗');
});

for(const force of [false,true])for(const existing of [false,true])test(`${force?'forced':'cooperative'} correction stop restores ${existing?'the selected curve':'the manual grid'} and allows retry`,async({page})=>{
 await fixture(page,{curved:true});
 if(!force)test.skip(!await page.evaluate(()=>crossOriginIsolated&&typeof SharedArrayBuffer!=='undefined'),'Cooperative cancellation requires shared memory');
 if(existing)await change(page,'curved',true);
 const before=await current(page),id=existing?'refine':'curved',action=existing?'refineCurve':'curve';
 await page.evaluate(id=>{window.holdCorrections=true;const el=document.getElementById(id);el.checked=true;window.pendingCorrection=el.onchange();},id);
 await expect.poll(async()=>(await requests(page,action)).length).toBe(1);
 await page.evaluate(force=>{document.getElementById('cancel').onclick();if(force)document.getElementById('cancel').onclick();},force);
 await page.evaluate(()=>window.pendingCorrection);await expect(page.locator('#recover')).toBeEnabled();
 const after=await current(page);expect(after.settings).toEqual(before.settings);expect(after.matrix).toEqual(before.matrix);expect(after.source).toEqual(before.source);
 await expect(page.locator('#'+id)).not.toBeChecked();
 if(existing)await expect(page.locator('#curveChoice option')).toHaveCount(3);
 await page.evaluate(()=>window.holdCorrections=false);await change(page,id,true);
 expect((await requests(page,action)).length).toBe(2);expect((await current(page)).matrix.sources.at(-1)).toBe('1');
});

test('legacy planar preview is discarded before reopening and undoing manual history',async({page})=>{
 await fixture(page);
 const saved=await page.evaluate(async()=>{
  const {listSessions,saveSession}=await import('/storage.js');const s=(await listSessions()).find(s=>s.image),h=s.history[s.cursor];
  h.settings.curved=true;h.settings.previewQuad=h.settings.quad.map(v=>v+30);delete h.settings.previewGeometryVersion;h.settings.warp=null;
  const next=structuredClone(h);next.settings.quad=next.settings.quad.map(v=>v+1);s.history=[h,next];s.cursor=1;
  s.checkpoint=structuredClone(h);await saveSession(s);return {quad:next.settings.quad,olderQuad:h.settings.quad,matrix:next.matrix};
 });
 await reopen(page);expect((await requests(page,'curve')).length).toBe(1);
 await change(page,'colorUnknown',true);
 const sampled=(await requests(page,'sample')).at(-1);expect(sampled.settings.quad).toEqual(saved.quad);expect(sampled.settings.previewQuad).toBeNull();
 expect((await current(page)).matrix.sources.at(-1)).toBe('1');expect((await current(page)).matrix.text.at(-1)).toBe(saved.matrix.text.at(-1));
 await page.locator('.steps [data-tab="edit"]').click();await page.locator('#undo').click();await page.locator('#undo').click();
 await expect.poll(async()=>(await current(page)).settings.quad).toEqual(saved.olderQuad);
 await change(page,'colorUnknown',true);expect((await requests(page,'sample')).at(-1).settings.previewQuad).toBeNull();
});


async function fineCornerEdit(page){
 await page.locator('#fineCorners').evaluate(el=>{window.pendingEdit=el.onclick();});
 await page.locator('#cornerIndex').selectOption('2');
 await page.locator('#cornerRight').evaluate(el=>el.onclick());
 await page.locator('#cornerApply').evaluate(el=>el.onclick());
}
async function canvasPoint(page,point){
 await page.locator('#imageView').evaluate(el=>el.onclick());
 await page.locator('#fitView').evaluate(el=>el.onclick());
 const size=await page.evaluate(async()=>{const s=(await (await import('/storage.js')).listSessions()).find(s=>s.image),image=await createImageBitmap(s.image);const size=[image.width,image.height];image.close();return size;});
 const box=await page.locator('#canvas').boundingBox();
 return {x:box.x+point[0]*box.width/size[0],y:box.y+point[1]*box.height/size[1]};
}
const edits=['corner drag','fine corners','version','image rotation','perspective','brightness reset','contrast reset','adjustments reset','Finder assignment'];
for(const force of [false,true])for(const edit of edits)test(`${force?'forced':'cooperative'} curve stop preserves ${edit} as one saved undo step`,async({page})=>{
 await fixture(page,{curved:true});
 if(!force)test.skip(!await page.evaluate(()=>crossOriginIsolated&&typeof SharedArrayBuffer!=='undefined'),'Cooperative cancellation requires shared memory');
 if(edit==='brightness reset'||edit==='adjustments reset')await change(page,'brightness',5);
 if(edit==='contrast reset'||edit==='adjustments reset')await change(page,'contrast',5);
 await change(page,'curved',true);
 const before=await workspace(page),previous=before.history[before.cursor],count=(await requests(page,'curve')).length;
 await page.evaluate(()=>{window.holdCorrections=true;});
 if(edit==='fine corners')await fineCornerEdit(page);
 else if(edit==='corner drag'){
  const point=await canvasPoint(page,previous.settings.quad.slice(4,6));
  await page.mouse.move(point.x,point.y);await page.mouse.down();await page.mouse.move(point.x+12,point.y,{steps:3});await page.mouse.up();
 }else if(edit==='Finder assignment'){
  const point=await canvasPoint(page,previous.settings.finders.slice(1,3));
  await page.locator('#finderRole').selectOption('0');await page.mouse.click(point.x,point.y);
 }else if(edit==='version'){
  page.on('dialog',dialog=>dialog.accept());
  await page.locator('#version').evaluate(el=>{el.value='2';window.pendingEdit=el.onchange();});
 }else if(edit==='image rotation'){
  await page.locator('#imageAngle').evaluate(el=>{el.value='5';});
  await page.locator('#rotateImage').evaluate(el=>{window.pendingEdit=el.onclick();});
 }else{
  const selector={'perspective':'#rectifyImage','brightness reset':'[data-reset="brightness"]','contrast reset':'[data-reset="contrast"]','adjustments reset':'#resetImageAdjustments'}[edit];
  await page.locator(selector).evaluate(el=>{window.pendingEdit=el.onclick();});
 }
 await expect.poll(async()=>(await requests(page,'curve')).length).toBe(count+1);
 await page.evaluate(force=>{document.getElementById('cancel').onclick();if(force)document.getElementById('cancel').onclick();},force);
 await page.evaluate(()=>window.pendingEdit);await expect(page.locator('#recover')).toBeEnabled();
 await expect.poll(async()=>(await workspace(page)).history.length).toBe(before.history.length+1);
 const after=await workspace(page),changed=after.history[after.cursor];
 expect(changed.settings.previewQuad).toBeNull();expect(changed.settings.warp).toBeNull();
 if(edit==='version')expect(changed.settings.version).toBe(2);
 else {expect(changed.matrix.sources.at(-1)).toBe('1');expect(changed.matrix.text.at(-1)).toBe(previous.matrix.text.at(-1));}
 if(edit==='corner drag'||edit==='fine corners')expect(changed.settings.quad).not.toEqual(previous.settings.quad);
 if(edit==='image rotation'||edit==='perspective')expect(changed.settings.imageOps).not.toEqual(previous.settings.imageOps);
 if(edit.includes('brightness')||edit==='adjustments reset')expect(changed.settings.brightness).toBe(0);
 if(edit.includes('contrast')||edit==='adjustments reset')expect(changed.settings.contrast).toBe(0);
 await page.evaluate(()=>{window.holdCorrections=false;});
 await page.locator('.steps [data-tab="edit"]').click();await page.locator('#undo').click();
 await expect.poll(async()=>await current(page)).toEqual(previous);
 await page.locator('#redo').click();await expect.poll(async()=>await current(page)).toEqual(changed);
 await reopen(page);expect(await current(page)).toEqual(changed);
});

for(const found of [false,true])test(`grid redetection refreshes corrections when ${found?'the same corners are found':'no corners are found'}`,async({page})=>{
 await fixture(page,{curved:true});await change(page,'curved',true);
 const before=await current(page),count=(await requests(page,'curve')).length;
 // Both outcomes retain the current base geometry and therefore the cache key.
 await page.evaluate(async({settings,found})=>{
  const {listSessions,saveSession}=await import('/storage.js');const s=(await listSessions()).find(s=>s.image);
  s.history[s.cursor].settings.finders=[];await saveSession(s);
  window.detectionResult={quad:found?settings.quad:null,version:settings.version,assigned:true,autoAssignedFinders:[],polarity:'NORMAL',confirmedPolarity:'NORMAL',black:settings.provisionalBlack,white:settings.provisionalWhite,colorClusters:settings.colorClusters};
 },{settings:before.settings,found});
 await reopen(page);
 await page.locator('#detect').evaluate(el=>el.onclick());await expect(page.locator('#recover')).toBeEnabled();
 await expect.poll(async()=>(await requests(page,'curve')).length).toBe(count+1);
 await expect.poll(async()=>(await current(page)).settings.previewGeometryVersion).toBe(1);
 expect((await current(page)).settings.previewQuad).not.toBeNull();
 await expect(page.locator('#curveChoice option')).toHaveCount(3);
});

test('a failed search after moving corners never offers candidates from the old grid',async({page})=>{
 await fixture(page,{curved:true});await change(page,'curved',true);
 const before=await current(page);
 await page.evaluate(()=>{window.failCorrections=true;});await fineCornerEdit(page);
 await page.evaluate(()=>window.pendingEdit);await expect(page.locator('#recover')).toBeEnabled();
 await expect(page.locator('#curveStatus')).toContainText('補正計算に失敗');
 await expect(page.locator('#curveChoice option')).toHaveCount(0);
 const after=await current(page);expect(after.settings.quad).not.toEqual(before.settings.quad);expect(after.settings.previewQuad).toBeNull();
 await reopen(page);await expect(page.locator('#curveChoice option')).toHaveCount(0);
 await page.evaluate(()=>{window.failCorrections=false;});await change(page,'curved',false);await change(page,'curved',true);
 await expect(page.locator('#curveChoice option')).toHaveCount(3);
});

test('a retained planar correction is reported as saved correction after a search failure',async({page})=>{
 await fixture(page,{curved:true,planar:true});await change(page,'curved',true);
 const before=await current(page);
 await page.evaluate(async()=>{
  const {listSessions,saveSession}=await import('/storage.js');const s=(await listSessions()).find(s=>s.image),h=s.history[s.cursor];
  h.settings.confirmedPolarity='INVERTED';h.settings.polarityTone=null;await saveSession(s);
  window.failCorrections=true;
 });
 await reopen(page);await change(page,'colorUnknown',true);
 await expect(page.locator('#curveStatus')).toContainText('再計算に失敗: 保存した補正を使用');
 expect((await current(page)).settings.previewQuad).toEqual(before.settings.previewQuad);
 expect((await current(page)).settings.warp).toBeNull();
});
