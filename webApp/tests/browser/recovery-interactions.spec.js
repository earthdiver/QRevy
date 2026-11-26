import {changeEvidenceControl,clickInputAction,openAppMenu,runRecovery,setAutomaticRepair} from './input-helpers.js';
import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';

test.use({serviceWorkers:'block'});
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
const latest=page=>page.evaluate(async()=>{const s=(await (await import('/storage.js')).listSessions())[0];return s?.history[s.cursor];});
async function ready(page){await expect(page.locator('#recover')).toBeEnabled();}
async function workMenu(page){await openAppMenu(page);await page.locator('#workMenuButton').click();}

test('relocated output and evidence controls work after startup and recovery',async({page})=>{
 await page.goto('/');await clickInputAction(page,'#demo');await ready(page);await runRecovery(page);
 await expect(page.locator('#copyPayload')).toBeEnabled();await page.locator('#resultEvidenceTab').click();
 await changeEvidenceControl(page,'#resultStage','selectOption','before');await changeEvidenceControl(page,'#unmask','check');
 await changeEvidenceControl(page,'#resultStage','selectOption','compare');await expect(page.locator('#beforeCanvas')).toBeVisible();
 await page.evaluate(()=>navigator.canShare=()=>false);
 for(const [id,extension] of [['exportQrr','.qrr'],['exportText','.txt']]){
  await workMenu(page);const download=page.waitForEvent('download');await page.locator('#'+id).click();
  const file=await download;expect(file.suggestedFilename()).toMatch(new RegExp('\\'+extension+'$'));
  expect((await readFile(await file.path())).length).toBeGreaterThan(0);
 }
});

test('language changes retain the selected QR mask and saved selection',async({page})=>{
 await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/multiple-masks.txt');await ready(page);
 for(const [id,value] of [['ecc','H'],['list','0']]){
  await page.locator('#'+id).evaluate(async(el,value)=>{el.value=value;await el.onchange();},value);await ready(page);
 }
 await runRecovery(page);await expect(page.locator('#candidatePicker option')).toHaveCount(2);
 await page.locator('#candidatePicker').selectOption('1');await ready(page);
 await expect.poll(async()=>!!(await latest(page))?.selection).toBe(true);
 const selection=(await latest(page)).selection;
 for(let i=0;i<2;i++){
  await page.locator('#languageButton').click();await ready(page);
  await expect(page.locator('#candidatePicker')).toHaveValue('1');
  expect((await latest(page)).selection).toEqual(selection);
 }
});

for(const angle of [90,180,270])test(`crop ${angle} degrees preserves dimensions and binary pixels`,async({page})=>{
 await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();
 for(const [width,height] of [[400,200],[29,29]]){
  await page.evaluate(async({width,height})=>{
   const canvas=document.createElement('canvas');canvas.width=width;canvas.height=height;const cx=canvas.getContext('2d');
   cx.fillStyle='white';cx.fillRect(0,0,width,height);cx.fillStyle='black';cx.fillRect(0,0,Math.floor(width/2),Math.floor(height/2));
   const image=new Image();image.src=canvas.toDataURL();await image.decode();
   window.cropResult=(await import('/image-tools.js')).cropImage(image);
  },{width,height});
  for(let i=0;i<angle/90;i++)await page.locator('#cropRotate').click();
  await page.locator('#cropApply').click();
  const result=await page.evaluate(async()=>{
   const {blob}=await window.cropResult,image=await createImageBitmap(blob),c=document.createElement('canvas');c.width=image.width;c.height=image.height;
   const cx=c.getContext('2d');cx.drawImage(image,0,0);const data=cx.getImageData(0,0,c.width,c.height).data;let gray=0,black=0;
   for(let i=0;i<data.length;i+=4){if(data[i]===0)black++;else if(data[i]!==255)gray++;}
   return {width:c.width,height:c.height,gray,black};
  });
  expect(result).toEqual({width:angle===180?width:height,height:angle===180?height:width,gray:0,black:Math.floor(width/2)*Math.floor(height/2)});
 }
});

test('rotated pixel-center grids sample a small QR without losing cells',async({page})=>{
 await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();
 const results=await page.evaluate(async()=>{
  const {execute}=await import('/engine/js/engine.js'),{prepareImage,rotatedLayout}=await import('/image-tools.js');
  const run=r=>JSON.parse(execute(r)),matrix=run({action:'demo',text:'A',damage:false}),rows=matrix.text.split('\n'),n=rows.length,pitch=2;
  const image=document.createElement('canvas');image.width=image.height=(n+8)*pitch;const cx=image.getContext('2d');
  cx.fillStyle='white';cx.fillRect(0,0,image.width,image.height);cx.fillStyle='black';
  rows.forEach((row,y)=>[...row].forEach((v,x)=>{if(v==='1')cx.fillRect((x+4)*pitch,(y+4)*pitch,pitch,pitch);}));
  const first=8.5,last=first+(n-1)*pitch,quad=[first,first,last,first,last,last,first,last],results=[];
  for(const angle of [90,180,270]){
   const output=prepareImage(image,[{type:'rotate',angle}]),layout=rotatedLayout(image.width,image.height,angle);
   const mapped=Array.from({length:4},(_,i)=>layout.map(quad[i*2],quad[i*2+1])).flat();
   const data=output.getContext('2d').getImageData(0,0,output.width,output.height).data;
   const pixels=Array.from({length:data.length/4},(_,i)=>(data[i*4]<<16)|(data[i*4+1]<<8)|data[i*4+2]);
   const sample=run({action:'sample',width:output.width,height:output.height,pixels,settings:{version:1,quad:mapped,automatic:false,black:64,white:192,lmEnabled:false}});
   const roundTrip=Array.from({length:4},(_,i)=>layout.unmap(mapped[i*2],mapped[i*2+1])).flat();
   results.push({text:sample.matrix?.text,expected:matrix.text,error:Math.max(...roundTrip.map((v,i)=>Math.abs(v-quad[i])))});
  }
  return results;
 });
 for(const result of results){expect(result.text).toBe(result.expected);expect(result.error).toBeLessThan(1e-9);}
});

test('orientation changes on an inverted image synchronize thresholds before recovery',async({page})=>{
 await page.goto('/');await setAutomaticRepair(page,true);
 const raw=await readFile('tests/fixtures/clean.png');
 const png=await page.evaluate(async source=>{
  const image=new Image();image.src=source;await image.decode();const c=document.createElement('canvas');c.width=image.width;c.height=image.height;
  const cx=c.getContext('2d');cx.drawImage(image,0,0);const d=cx.getImageData(0,0,c.width,c.height);
  for(let i=0;i<d.data.length;i+=4)for(let j=0;j<3;j++)d.data[i+j]=255-d.data[i+j];cx.putImageData(d,0,0);return c.toDataURL().split(',')[1];
 },'data:image/png;base64,'+raw.toString('base64'));
 await page.locator('#imageFile').setInputFiles({name:'inverted.png',mimeType:'image/png',buffer:Buffer.from(png,'base64')});
 await page.locator('#cropApply').click();await expect(page.locator('#automaticDialog')).toBeVisible();await page.locator('#automaticDetailed').click();
 await expect.poll(async()=>(await latest(page))?.settings.confirmedPolarity).toBe('INVERTED');
 await page.locator('#orientation').selectOption('1');await ready(page);
 expect(Number(await page.locator('#black').inputValue())).toBeLessThan(Number(await page.locator('#white').inputValue()));
 await runRecovery(page);await expect(page.locator('.steps [data-tab="results"]')).toHaveClass(/active/);
});

test('image rotation keeps the actual workspace grid aligned through a full turn',async({page})=>{
 await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();
 const fixture=await page.evaluate(async()=>{
  const {execute}=await import('/engine/js/engine.js'),matrix=JSON.parse(execute({action:'demo',text:'A',damage:false})),rows=matrix.text.split('\n');
  const c=document.createElement('canvas');c.width=c.height=(rows.length+8)*2;const cx=c.getContext('2d');cx.fillStyle='white';cx.fillRect(0,0,c.width,c.height);cx.fillStyle='black';
  rows.forEach((row,y)=>[...row].forEach((v,x)=>{if(v==='1')cx.fillRect((x+4)*2,(y+4)*2,2,2);}));
  return {png:c.toDataURL().split(',')[1],matrix};
 });
 await page.locator('#imageFile').setInputFiles({name:'small.png',mimeType:'image/png',buffer:Buffer.from(fixture.png,'base64')});
 await page.locator('#cropApply').click();await expect(page.locator('#cropCanvas')).not.toBeVisible();await expect(page.locator('#progress')).toBeHidden();await expect.poll(async()=>!!await latest(page)).toBe(true);
 // Reopen a valid workspace with exact pixel-center corners to isolate rotation
 // from the automatic grid estimator's subpixel choices.
 await page.evaluate(async matrix=>{
  const {listSessions,saveSession}=await import('/storage.js'),s=(await listSessions())[0],h=s.history[s.cursor];
  h.matrix=matrix;h.source=structuredClone(matrix);
  Object.assign(h.settings,{quad:[8.5,8.5,48.5,8.5,48.5,48.5,8.5,48.5],imageOps:[],finders:[],grid:null,warp:null,previewQuad:null,lmEnabled:false,lmBaseQuad:null,lmAppliedQuad:null,automatic:false,black:64,white:192,confirmedPolarity:'UNKNOWN',quietZone:false,photographicSource:false,assigned:true});
  s.history=[h];s.cursor=0;await saveSession(s);
 },fixture.matrix);
 await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList .session-item button').first().click();await expect(page.locator('#sessionsDialog')).not.toBeVisible();await ready(page);
 await page.locator('#imageAngle').evaluate(el=>{for(let p=el.parentElement;p;p=p.parentElement)if(p.tagName==='DETAILS')p.open=true;});
 await page.locator('#imageAngle').fill('90');
 const original=[8.5,8.5,48.5,8.5,48.5,48.5,8.5,48.5];let expected=original;
 for(let i=0;i<4;i++){
  await page.locator('#rotateImage').click();await ready(page);
  expected=Array.from({length:4},(_,k)=>[57-expected[k*2+1],expected[k*2]]).flat();
  await expect.poll(async()=>(await latest(page)).matrix.text).toBe(fixture.matrix.text);
  const actual=(await latest(page)).settings.quad;actual.forEach((v,k)=>expect(v).toBeCloseTo(expected[k],8));
 }
});

test('one-cell edits in an applied-recovery QRR preserve other recovered cells',async({page})=>{
 await page.goto('/');await expect(page.locator('#demo')).toBeEnabled();
 const file=await page.evaluate(async()=>{
  const {execute}=await import('/engine/js/engine.js'),{packQrr}=await import('/qrr.js'),run=r=>JSON.parse(execute(r));
  const clean=run({action:'demo',text:'A',damage:false}),source=run({action:'demo',text:'A',damage:true}),matrix={...clean,sources:'2'.repeat(clean.sources.length)};
  const raw=run({action:'qrrEncode',id:'applied',cursor:0,history:JSON.stringify([{label:'Applied recovery',kind:'APPLY_RECOVERY',matrix,source,settings:{version:1}}])});
  return {bytes:Array.from(new Uint8Array(await (await packQrr(raw)).arrayBuffer())),text:matrix.text};
 });
 await page.locator('#sessionFile').setInputFiles({name:'applied.qrr',mimeType:'application/octet-stream',buffer:Buffer.from(file.bytes)});await ready(page);
 await page.locator('.steps [data-tab="edit"]').click();const canvas=page.locator('#canvas'),box=await canvas.boundingBox();
 await canvas.click({position:{x:box.width*22.5/25,y:box.height*22.5/25}});await page.locator('[data-value="?"]').click();
 await expect.poll(async()=>(await latest(page))?.matrix.text).toBe(file.text.slice(0,-1)+'?');
 await page.locator('#undo').click();await expect.poll(async()=>(await latest(page)).matrix.text).toBe(file.text);
});
