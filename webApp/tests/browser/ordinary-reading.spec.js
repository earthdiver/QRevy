import {openAppMenu,clickWorkspaceAction,changeRecoverySetting} from './input-helpers.js';
import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';

test.use({serviceWorkers:'block'});
test.beforeEach(async({page})=>{
 await page.addInitScript(()=>{
  const OriginalWorker=Worker;
  window.Worker=class extends OriginalWorker {
   constructor(...args){super(...args);this.addEventListener('message',e=>{if(e.data.result?.ordinary)window.ordinaryReader=e.data.result.ordinary.reader;});}
  };
 });
});
for(const automatic of [false,true])test(`ordinary reading precedes repair with automatic repair ${automatic}`,async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(automatic=>{
  localStorage.setItem('qrevy-automatic-repair',String(automatic));
  Object.defineProperty(navigator,'clipboard',{value:{writeText:async text=>{window.copiedOrdinary=text;}}});
 },automatic);
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();
 await expect(page.locator('#ordinaryDialog')).toBeVisible();await expect(page.locator('#ordinaryText')).toHaveText('QRevy repaired.');
 await expect.poll(()=>page.evaluate(()=>window.ordinaryReader)).toBe('ZXing-C++');
 await expect(page.locator('#automaticDialog')).not.toBeVisible();await expect(page.locator('#resultCount')).toHaveText('0');
 await page.locator('#ordinaryCopy').click();await expect.poll(()=>page.evaluate(()=>window.copiedOrdinary)).toBe('QRevy repaired.');
 await page.locator('#ordinaryClose').click();await page.locator('#reopenOrdinary').click();await expect(page.locator('#ordinaryText')).toHaveText('QRevy repaired.');
 await page.locator('#ordinaryDetailed').click();await expect(page.locator('#recover')).toBeEnabled();
 expect(errors).toEqual([]);
});

test('an unreadable synthetic image continues into automatic repair',async({page})=>{
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/ordinary-failure.png');await page.locator('#cropApply').click();
 await expect(page.locator('#automaticDialog')).toBeVisible();await expect(page.locator('#ordinaryDialog')).not.toBeVisible();
 await expect(page.locator('#automaticMessage')).toContainText('QRevy repaired.');
});

for(const asset of ['boundary.js','boundary.wasm'])test(`Java ordinary reading remains available if native ${asset} fails`,async({page})=>{
 await page.route(`**/native/${asset}`,r=>r.abort());
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();
 await expect(page.locator('#ordinaryDialog')).toBeVisible();await expect(page.locator('#ordinaryText')).toHaveText('QRevy repaired.');
 await expect.poll(()=>page.evaluate(()=>window.ordinaryReader)).toBe('ZXing Java');
});

const savedSessions=page=>page.evaluate(async()=>(await import('/storage.js')).listSessions());
async function ordinaryPhoto(page){
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();
 await expect(page.locator('#ordinaryDialog')).toBeVisible();
}

test('ordinary results persist without a synthetic matrix and survive backup and reload',async({page})=>{
 await ordinaryPhoto(page);
 await expect.poll(async()=>(await savedSessions(page)).length).toBe(1);
 const saved=(await savedSessions(page))[0];
 expect(saved.kind).toBe('image-import');expect(saved.history).toEqual([]);expect(saved.cursor).toBe(-1);
 expect(saved.ordinary.text).toBe('QRevy repaired.');
 const roundTrip=await page.evaluate(async()=>{
  const {listSessions,exportWorkspace,importWorkspace}=await import('/storage.js');
  const original=(await listSessions())[0],backup=await exportWorkspace(original),restored=await importWorkspace(backup);
  const envelope=JSON.parse(await backup.text());
  const corrupt=JSON.parse(envelope.payload);corrupt.imageHash='0'.repeat(64);
  envelope.payload=JSON.stringify(corrupt);envelope.sha256=await (await import('/storage.js')).digest(new TextEncoder().encode(envelope.payload));
  let rejected=false;try{await importWorkspace(new Blob([JSON.stringify(envelope)]));}catch{rejected=true;}
  return {version:envelope.version,text:restored.ordinary.text,hash:restored.imageHash,bytes:restored.image.size,rejected};
 });
 expect(roundTrip.rejected).toBe(true);expect(roundTrip.version).toBe(2);expect(roundTrip.text).toBe(saved.ordinary.text);expect(roundTrip.hash).toBe(saved.imageHash);expect(roundTrip.bytes).toBeGreaterThan(0);
 await page.locator('#ordinaryClose').click();
 await changeRecoverySetting(page,'#known','selectOption','1');
 await expect.poll(async()=>(await savedSessions(page))[0].settings.known).toBe(1);
 await clickWorkspaceAction(page,'#save');
 await page.reload();await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList .session-item button').first().click();
 await expect(page.locator('#ordinaryDialog')).toBeVisible();await expect(page.locator('#ordinaryText')).toHaveText('QRevy repaired.');
 await page.locator('#ordinaryDetailed').click();await expect(page.locator('#recover')).toBeEnabled();
 await expect(page.locator('#known')).toHaveValue('1');
 await expect.poll(async()=>(await savedSessions(page))[0].history.length).toBeGreaterThan(0);
});

test('Finder assignment after closing an ordinary result initializes analysis',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await ordinaryPhoto(page);await page.locator('#ordinaryClose').click();
 await page.locator('#finderRole').selectOption('0');
 const box=await page.locator('#canvas').boundingBox();
 await page.locator('#canvas').click({position:{x:box.width*60/232,y:box.height*60/232}});
 await expect(page.locator('#recover')).toBeEnabled();
 await expect.poll(async()=>(await savedSessions(page))[0].history.length).toBeGreaterThan(0);
 expect(errors).toEqual([]);await expect(page.locator('#toast')).not.toContainText('null');
});

test('requested detailed analysis takes precedence over a pending ordinary result',async({page})=>{
 await page.route('**/worker.js',async route=>{
  const response=await route.fetch();
  const body=(await response.text()).replace('queue=queue.then(()=>{',"queue=queue.then(async()=>{if(data.request.action==='quickRecoverImage')await new Promise(resolve=>setTimeout(resolve,1500));");
  await route.fulfill({response,body});
 });
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();
 await expect(page.locator('#cancel')).toHaveText('詳細解析');await page.locator('#cancel').click();
 await expect(page.locator('#recover')).toBeEnabled();await expect(page.locator('#ordinaryDialog')).not.toBeVisible();
 await expect(page.locator('#reopenOrdinary')).toBeVisible();
 await page.locator('#reopenOrdinary').click();await expect(page.locator('#ordinaryText')).toHaveText('QRevy repaired.');
});

test('large photos are reduced using bounded canvas tiles',async({page})=>{
 await page.goto('/');
 const results=await page.evaluate(async()=>{
  const {resampleImage}=await import('/image-resampling.js');
  const results=[];
  for(const [width,height] of [[5712,4284],[8001,6001]]){
   const img=new Image();img.src='data:image/svg+xml,'+encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><path fill="#123456" d="M0 0H${width}V${height}H0Z"/></svg>`);await img.decode();
   const original=document.createElement.bind(document),canvases=[];
   document.createElement=(tag,...args)=>{const el=original(tag,...args);if(tag==='canvas')canvases.push(el);return el;};
   const getImageData=CanvasRenderingContext2D.prototype.getImageData;
   CanvasRenderingContext2D.prototype.getImageData=function(...args){if(this.canvas.width>1024||this.canvas.height>1024)throw new Error('Unbounded raster read');return getImageData.apply(this,args);};
   let result;
   try{result=await resampleImage(img);}finally{document.createElement=original;CanvasRenderingContext2D.prototype.getImageData=getImageData;}
   results.push({width:result.width,height:result.height,pixel:Array.from(result.getContext('2d').getImageData(result.width-1,result.height-1,1,1).data)});
  }
  return results;
 });
 expect(results).toEqual([{width:1428,height:1071,pixel:[18,52,86,255]},{width:2001,height:1501,pixel:[18,52,86,255]}]);
});

test('reopening ordinary text preserves a later automatic trial',async({page})=>{
 await ordinaryPhoto(page);await page.locator('#ordinaryDetailed').click();await expect(page.locator('#recover')).toBeEnabled();
 const record=await page.evaluate(async()=>{
  const storage=await import('/storage.js'),s=(await storage.listSessions())[0],h=s.history[s.cursor];
  h.automatic={mode:'initial',firstRun:false,input:{matrix:h.matrix,settings:h.settings},reports:[{mode:'initial',report:'Retained diagnostic'}],status:{selectedIndex:-1,failureReason:'TIME_LIMIT'}};
  await storage.saveSession(s);return s.id;
 });
 await page.reload();await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList .session-item button').first().click();
 await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('#reopenOrdinary').click();await expect(page.locator('#ordinaryDialog')).toBeVisible();await page.locator('#ordinaryDetailed').click();
 await expect(page.locator('#automaticReport')).toContainText('Retained diagnostic');
 await clickWorkspaceAction(page,'#save');
 await expect.poll(async()=>(await savedSessions(page)).find(s=>s.id===record)?.history.at(-1)?.automatic?.status?.failureReason).toBe('TIME_LIMIT');
});

test('saving while a new image version is sampled keeps the previous valid history',async({page})=>{
 await page.route('**/worker.js',async route=>{
  const response=await route.fetch();
  const body=(await response.text()).replace('queue=queue.then(()=>{',"queue=queue.then(async()=>{if(data.request.action==='sample'&&data.request.settings.version===2)await new Promise(resolve=>setTimeout(resolve,5000));");
  await route.fulfill({response,body});
 });
 await ordinaryPhoto(page);await page.locator('#ordinaryDetailed').click();await expect(page.locator('#recover')).toBeEnabled();
 await expect.poll(async()=>(await savedSessions(page))[0].kind).toBe('analysis');
 const before=(await savedSessions(page))[0].updated;
 page.on('dialog',dialog=>dialog.accept());
 await page.locator('#version').selectOption('2');await expect(page.locator('#progress')).toBeVisible();
 await page.evaluate(()=>window.dispatchEvent(new Event('pagehide')));
 expect((await savedSessions(page))[0].updated).toBe(before);
 const saved=await page.evaluate(async()=>{
  const storage=await import('/storage.js'),s=(await storage.listSessions())[0];
  storage.validateSession(s);return s;
 });
 expect(saved.kind).toBe('analysis');expect(saved.history.length).toBeGreaterThan(0);
 await expect(page.locator('#progress')).not.toBeVisible();
});

for(const native of [true,false])test(`inverted ordinary QR reads with native reader ${native}`,async({page})=>{
 if(!native)await page.route('**/native/boundary.js',route=>route.abort());
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 const originalPng=(await readFile('tests/fixtures/clean.png')).toString('base64');
 const png=await page.evaluate(async originalPng=>{
  const image=new Image();image.src='data:image/png;base64,'+originalPng;await image.decode();
  const canvas=document.createElement('canvas');canvas.width=image.width;canvas.height=image.height;
  const ctx=canvas.getContext('2d');ctx.drawImage(image,0,0);
  const rgba=ctx.getImageData(0,0,canvas.width,canvas.height);
  for(let i=0;i<rgba.data.length;i+=4)for(let c=0;c<3;c++)rgba.data[i+c]=255-rgba.data[i+c];
  ctx.putImageData(rgba,0,0);return canvas.toDataURL('image/png').split(',')[1];
 },originalPng);
 await page.locator('#imageFile').setInputFiles({name:'synthetic-inverted.png',mimeType:'image/png',buffer:Buffer.from(png,'base64')});
 await page.locator('#cropApply').click();
 await expect(page.locator('#ordinaryDialog')).toBeVisible();await expect(page.locator('#ordinaryText')).toHaveText('QRevy repaired.');
 await expect.poll(()=>page.evaluate(()=>window.ordinaryReader)).toBe(native?'ZXing-C++':'ZXing Java');
 await expect(page.locator('#automaticDialog')).not.toBeVisible();
});

test('opening and redisplaying an ordinary result leaves its saved date unchanged',async({page})=>{
 await ordinaryPhoto(page);
 await expect.poll(async()=>(await savedSessions(page)).length).toBe(1);
 const before=(await savedSessions(page))[0].updated;
 await page.locator('#ordinaryClose').click();await page.locator('#reopenOrdinary').click();
 await page.locator('#ordinaryClose').click();
 await page.reload();await openAppMenu(page);await page.locator('#sessionsButton').click();
 await page.locator('#sessionList .session-item button').first().click();
 await expect(page.locator('#ordinaryDialog')).toBeVisible();
 await page.locator('#ordinaryClose').click();
 await page.evaluate(()=>window.dispatchEvent(new Event('pagehide')));
 // A queued save transaction must have settled before checking the persisted timestamp.
 await openAppMenu(page);await page.locator('#sessionsButton').click();
 expect((await savedSessions(page))[0].updated).toBe(before);
});

test('ordinary imports have a separate retention limit and move to analysis under the same ID',async({page})=>{
 await ordinaryPhoto(page);await page.locator('#ordinaryDetailed').click();await expect(page.locator('#recover')).toBeEnabled();
 await expect.poll(async()=>(await savedSessions(page))[0].kind).toBe('analysis');
 const outcome=await page.evaluate(async()=>{
  const storage=await import('/storage.js'),base=(await storage.listSessions())[0],h=base.history[0];
  await storage.removeAllSessions();
  // Legacy workspaces without a kind still belong to the analyzed category.
  for(let i=0;i<20;i++){const s={...base,id:`analysis-${i}`,updated:i};delete s.kind;await storage.saveSession(s);}
  for(let i=0;i<21;i++)await storage.saveSession({...base,id:`ordinary-${i}`,kind:'image-import',settings:h.settings,history:[],cursor:-1,checkpoint:null,updated:100+i});
  const before=await storage.listSessions();
  await storage.saveSession({...base,id:'ordinary-20',kind:'analysis',updated:200});
  const after=await storage.listSessions();
  return {before:before.map(s=>({id:s.id,kind:s.kind})),after:after.map(s=>({id:s.id,kind:s.kind}))};
 });
 expect(outcome.before.filter(s=>s.kind!=='image-import')).toHaveLength(20);
 expect(outcome.before.filter(s=>s.kind==='image-import')).toHaveLength(20);
 expect(outcome.before.some(s=>s.id==='ordinary-0')).toBe(false);
 expect(outcome.before.some(s=>s.id==='analysis-0')).toBe(true);
 expect(outcome.after.filter(s=>s.kind!=='image-import')).toHaveLength(20);
 expect(outcome.after.filter(s=>s.kind==='image-import')).toHaveLength(19);
 expect(outcome.after.filter(s=>s.id==='ordinary-20')).toEqual([{id:'ordinary-20',kind:'analysis'}]);
 expect(outcome.after.some(s=>s.id==='analysis-0')).toBe(false);
});
