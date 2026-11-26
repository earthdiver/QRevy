import {changeRecoverySetting,clickWorkspaceAction,runRecovery,setAutomaticRepair} from './input-helpers.js';
import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';

async function ready(page){await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();}
async function savedWork(page){
  await page.evaluate(()=>navigator.canShare=()=>false);
  const event=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportSession');
  const bytes=await readFile(await(await event).path());
  return {bytes,session:JSON.parse(JSON.parse(bytes.toString()).payload)};
}
async function chooseDetailed(page){
  await expect(page.locator('#automaticDialog')).toBeVisible({timeout:60000});
  await page.locator('#automaticDetailed').click();
  await expect(page.locator('#automaticDialog')).not.toBeVisible();
  await expect(page.locator('#recover')).toBeEnabled();
}

test('quick automatic repair starts enabled and its preference persists for later imports',async({page})=>{
  await ready(page);
  await expect(page.locator('#automaticRepair')).toBeChecked();
  await setAutomaticRepair(page,false);

  await page.reload();await expect(page.locator('#textImport')).toBeEnabled();
  await expect(page.locator('#automaticRepair')).not.toBeChecked();

  await page.locator('#textFile').setInputFiles('tests/fixtures/priority-before.txt');
  await expect(page.locator('#recover')).toBeEnabled();
  await expect(page.locator('#automaticDialog')).not.toBeVisible();
  await page.locator('#inputChoices > summary').click();
  await setAutomaticRepair(page,true);

  await page.reload();await expect(page.locator('#textImport')).toBeEnabled();
  await expect(page.locator('#automaticRepair')).toBeChecked();
});

test('image LM preference starts enabled and survives saving and reopening the workspace',async({page})=>{
  await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));
  await ready(page);await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');
  await page.locator('#cropApply').click();await expect(page.locator('#recover')).toBeEnabled();
  const initial=await savedWork(page);expect(initial.session.history[initial.session.cursor].settings.lmEnabled).toBe(true);
  await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();
  await expect(page.locator('#refine')).toBeChecked();await page.locator('#refine').uncheck();
  await expect(page.locator('#recover')).toBeEnabled();const disabled=await savedWork(page);
  await page.locator('#sessionFile').setInputFiles({name:'lm-choice.qrpwa',mimeType:'application/json',buffer:disabled.bytes});
  await expect(page.locator('#recover')).toBeEnabled();await expect(page.locator('#refine')).not.toBeChecked();
  const restored=await savedWork(page);expect(restored.session.history[restored.session.cursor].settings.lmEnabled).toBe(false);
});

test('automatic text repair retains execution conditions across detailed edits and workspace reload',async({page})=>{
  await ready(page);await page.locator('#textFile').setInputFiles('tests/fixtures/priority-before.txt');
  await chooseDetailed(page);
  const original=await savedWork(page),auto=original.session.history.filter(h=>h.automatic).at(-1);
  expect(auto).toBeDefined();
  await page.locator('[data-tab="input"]').click();await changeRecoverySetting(page,'#known','selectOption','2');
  await changeRecoverySetting(page,'#utf8','check');
  const edited=await savedWork(page),retained=edited.session.history.filter(h=>h.automatic).at(-1);
  expect(retained.automatic).toEqual(auto.automatic);
  await page.locator('#sessionFile').setInputFiles({name:'automatic-history.qrpwa',mimeType:'application/json',buffer:edited.bytes});
  await expect(page.locator('#recover')).toBeEnabled();
  const reopened=await savedWork(page);expect(reopened.session.history.filter(h=>h.automatic).at(-1).automatic).toEqual(auto.automatic);
});

test('automatic TXT import accepts more than fourteen priority marks but detailed repair keeps its own limit',async({page})=>{
  // A real clean QR with format observations erased and fifteen data marks.
  // This prevents ordinary decoding while preserving a valid recoverable target.
  const {execute}=await import('../../web/engine/js/engine.js');
  const clean=JSON.parse(execute({action:'demo',text:'TXT AUTO 42',damage:false}));
  const n=clean.text.split('\n').length,rows=clean.text.split('\n').map(row=>[...row]);
  const allowed=JSON.parse(execute({action:'regions',matrix:clean,settings:{version:(n-17)/4}})).allowed;
  for(let i=0;i<n;i++){rows[8][i]='?';rows[i][8]='?';}
  for(const index of allowed.filter(i=>i%n!==8&&Math.floor(i/n)!==8).slice(-15))rows[Math.floor(index/n)][index%n]='*';
  const input={name:'automatic-priority.txt',mimeType:'text/plain',buffer:Buffer.from(rows.map(row=>row.join('')).join('\n'))};
  await ready(page);await page.locator('#textFile').setInputFiles(input);await chooseDetailed(page);
  await page.locator('[data-tab="edit"]').click();await expect(page.locator('#priorityCount')).toContainText('15 / 4');
  await runRecovery(page);await expect(page.locator('#toast')).toContainText(/上限4|limit 4/i);
  const retained=await savedWork(page);expect(retained.session.history[retained.session.cursor].matrix.text.match(/\*/g)).toHaveLength(15);
  await page.locator('[data-tab="input"]').click();await setAutomaticRepair(page,false);
  await page.locator('#textFile').setInputFiles(input);await expect(page.locator('#toast')).toContainText('14');
  await expect(page.locator('#automaticDialog')).not.toBeVisible();
});

async function observeRequests(page){
  await page.addInitScript(()=>{
    window.recoveryRequests=[];
    const OriginalWorker=window.Worker;
    window.Worker=class extends OriginalWorker{
      postMessage(data,...rest){const r=data.request;if(r)window.recoveryRequests.push({action:r.action,settings:structuredClone(r.settings),matrix:structuredClone(r.matrix),automaticScanAssumption:r.automaticScanAssumption,automaticScanListExtension:r.automaticScanListExtension,timeLimitMillis:r.timeLimitMillis,unlimited:r.unlimited});return super.postMessage(data,...rest);}
    };
  });
}
async function controlledAutomatic(page,{failureReason=null,assumption='UTF8',extension=2,holdUntilStopped=false}={}){
  // Preserve real engine candidates; control completion to exercise otherwise
  // machine-speed-dependent timeout and retained-candidate UI paths.
  await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`
    import {execute} from './engine/js/engine.js';
    self.onmessage=async({data})=>{
      const r=data.request,quick=r.action==='quickRecover';
      if(quick&&${holdUntilStopped})while(!r.stop||Atomics.load(r.stop,0)===0)await new Promise(resolve=>setTimeout(resolve,10));
      const result=JSON.parse(execute(quick?{...r,action:'recover',settings:{...r.settings,list:0}}:r));
      if(quick){
        const n=r.matrix.text.split('\\n').length,last=35.5+(n-1)*8,quad=[35.5,35.5,last,35.5,last,last,35.5,last],settings={...r.settings,quad,assigned:true};
        result.candidates=result.candidates.map(c=>({...c,input:r.matrix,settings,quad,automaticScanAssumption:${JSON.stringify(assumption)},automaticScanListExtension:${extension},samplingInverted:false}));
        result.input=r.matrix;result.settings=settings;
        result.automatic={selectedIndex:${failureReason?'(-1)':'0'},failureReason:${JSON.stringify(failureReason)},report:'controlled automatic trial',timeLimitsEnabled:!r.unlimited};
      }
      self.postMessage({id:data.id,result});
    };self.postMessage({ready:true});`}));
}
async function automaticSample(page){
  const {execute}=await import('../../web/engine/js/engine.js');
  const matrix=JSON.parse(execute({action:'demo',text:'Automatic history 0702933',damage:true}));
  await page.locator('#textFile').setInputFiles({name:'auto-sample.txt',mimeType:'text/plain',buffer:Buffer.from(matrix.text)});
  await expect(page.locator('#automaticDialog')).toBeVisible({timeout:60000});
}

test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('automatic timeout retries use the same input at ten seconds then unlimited and keep candidates',async({page})=>{
  await observeRequests(page);await controlledAutomatic(page,{failureReason:'TIME_LIMIT'});await ready(page);await automaticSample(page);
  await expect(page.locator('#automaticRetry')).toContainText('10');
  await page.locator('#automaticRetry').click();await expect(page.locator('#automaticDialog')).toBeVisible();
  await expect(page.locator('#automaticRetry')).toContainText(/時間制限なし|without time limits/);
  await page.locator('#automaticRetry').click();await expect(page.locator('#automaticDialog')).toBeVisible();
  await expect(page.locator('#automaticRetry')).toBeHidden();await chooseDetailed(page);
  const requests=await page.evaluate(()=>window.recoveryRequests.filter(r=>r.action==='quickRecover'));
  expect(requests).toHaveLength(3);expect(requests[0].timeLimitMillis).toBeUndefined();
  expect(requests[1].timeLimitMillis).toBe(10000);expect(requests[2].unlimited).toBe(true);
  expect(requests[1].matrix).toEqual(requests[0].matrix);expect(requests[2].matrix).toEqual(requests[0].matrix);
  expect({...requests[1].settings,quad:null}).toEqual(requests[0].settings);expect(requests[1].settings.quad).not.toBeNull();expect(requests[2].settings).toEqual(requests[1].settings);
  await page.locator('[data-tab="results"]').click();await expect(page.locator('#payload')).toHaveValue('Automatic history 0702933');
  await expect(page.locator('#automaticStatus')).toContainText(/制限時間|time limit/);
});
});

test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('automatic QRR history retains the selected execution assumptions',async({page},info)=>{
  await observeRequests(page);await controlledAutomatic(page,{assumption:'UTF8_SINGLE_SEGMENT',extension:2});await ready(page);await automaticSample(page);await chooseDetailed(page);
  await page.locator('[data-tab="results"]').click();
  await page.locator('#languageButton').click();await expect(page.locator('#automaticStatus')).toContainText('UTF-8 and a single segment');
  await page.screenshot({path:info.outputPath('automatic-result-conditions-en.png'),fullPage:true});
  await page.locator('#languageButton').click();await expect(page.locator('#automaticStatus')).toContainText('UTF-8と単一セグメント');
  await page.screenshot({path:info.outputPath('automatic-result-conditions.png'),fullPage:true});
  const before=await savedWork(page),expected=before.session.history[before.session.cursor].settings;
  await page.locator('[data-tab="edit"]').click();await page.evaluate(()=>navigator.canShare=()=>false);
  const download=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportQrr');const bytes=await readFile(await(await download).path());
  await page.evaluate(()=>window.recoveryRequests=[]);
  await page.locator('#sessionFile').setInputFiles({name:'automatic.qrr',mimeType:'application/octet-stream',buffer:bytes});
  await expect(page.locator('#payload')).toHaveValue('Automatic history 0702933',{timeout:60000});
  await expect.poll(()=>page.evaluate(()=>window.recoveryRequests.filter(r=>r.action==='recover'||r.action==='recoverImage').length)).toBe(1);
  await expect(page.locator('#recover')).toBeEnabled();
  const requests=await page.evaluate(()=>window.recoveryRequests.filter(r=>r.action==='recover'||r.action==='recoverImage'));
  expect(requests).toHaveLength(1);expect(requests[0].action).toBe('recover');
  expect(requests[0].automaticScanAssumption).toBe('UTF8_SINGLE_SEGMENT');expect(requests[0].automaticScanListExtension).toBe(2);
  await page.locator('[data-tab="input"]').click();await expect(page.locator('#known')).toHaveValue(String(expected.known));expect(await page.locator('#utf8').isChecked()).toBe(expected.utf8);expect(await page.locator('#single').isChecked()).toBe(expected.single);
});
});

test('assigning a Finder again preserves the enabled LM preference',async({page})=>{
  await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));
  await ready(page);await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await expect(page.locator('#recover')).toBeEnabled();
  await expect(page.locator('#refine')).toBeChecked();await page.locator('#finderRole').selectOption('0');
  const canvas=page.locator('#canvas'),box=await canvas.boundingBox();await canvas.click({position:{x:60/232*box.width,y:60/232*box.height}});
  await expect(page.locator('#recover')).toBeEnabled();await expect(page.locator('#refine')).toBeChecked();
  const saved=await savedWork(page);expect(saved.session.history[saved.session.cursor].settings.lmEnabled).toBe(true);
});

test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('Detailed analysis interrupts automatic processing and opens settings with the input retained',async({page})=>{
  await controlledAutomatic(page,{failureReason:'INTERRUPTED',holdUntilStopped:true});await ready(page);
  const {execute}=await import('../../web/engine/js/engine.js');const matrix=JSON.parse(execute({action:'demo',text:'Automatic history 0702933',damage:true}));
  await page.locator('#textFile').setInputFiles({name:'interrupt-auto.txt',mimeType:'text/plain',buffer:Buffer.from(matrix.text)});
  await expect(page.locator('#progress')).toBeVisible();await expect(page.locator('#cancel')).toHaveText(/詳細解析|Detailed analysis/);
  await page.locator('#cancel').click();await expect(page.locator('#progress')).not.toBeVisible();
  await expect(page.locator('body')).toHaveAttribute('data-active-tab','input');await expect(page.locator('#automaticDialog')).not.toBeVisible();await expect(page.locator('#recover')).toBeEnabled();
  const saved=await savedWork(page);expect(saved.session.history[saved.session.cursor].matrix.text).toBe(matrix.text);
});
});

test('image imports run automatic repair and retain the image and selected content for detailed analysis',async({page})=>{
  await ready(page);await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();
  await chooseDetailed(page);await expect(page.locator('#imageView')).toBeEnabled();
  const saved=await savedWork(page);expect(saved.session.image).toMatch(/^data:image\//);
  expect(saved.session.history.some(h=>h.automaticScanAssumption)).toBe(true);
  await page.locator('[data-tab="results"]').click();await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});
