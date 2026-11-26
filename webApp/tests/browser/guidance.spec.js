import {changeEvidenceControl,changeRecoverySetting,clickInputAction,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
import {execute} from '../../web/engine/js/engine.js';

async function load(page,text){
  await page.goto('/');
  await expect(page.locator('#textImport')).toBeEnabled();
  await page.locator('#textFile').setInputFiles({name:'synthetic-guidance.txt',mimeType:'text/plain',buffer:Buffer.from(text)});
  await expect(page.locator('#recover')).toBeEnabled();
}

function invalidModeWithUnknownTail(){
  const rows=Array.from({length:21},()=>Array(21).fill('?'));
  for(const [left,top] of [[0,0],[14,0],[0,14]]){
    for(let row=0;row<7;row++)for(let column=0;column<7;column++){
      rows[top+row][left+column]=row===0||row===6||column===0||column===6||(row>=2&&row<=4&&column>=2&&column<=4)?'1':'0';
    }
    const separatorColumn=left===0?7:left-1,separatorRow=top===0?7:top-1;
    for(let offset=0;offset<7;offset++){
      rows[top+offset][separatorColumn]='0';rows[separatorRow][left+offset]='0';
    }
    rows[separatorRow][separatorColumn]='0';
  }
  for(let offset=8;offset<=12;offset++)rows[6][offset]=rows[offset][6]=offset%2===0?'1':'0';
  // The first four unmasked bits are 1111, an unsupported QR mode.
  for(const [x,y,value] of [[20,20,'0'],[19,20,'1'],[20,19,'1'],[19,19,'0']])rows[y][x]=value;
  return rows.map(row=>row.join('')).join('\n');
}

test('repaired-cell calculations retain the original non-probabilistic assessment',async({page})=>{
  await page.goto('/');await clickInputAction(page,'#demo');
  await expect(page.locator('#recover')).toBeEnabled();
  await runRecovery(page);
  await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
  const canvas=page.locator('#canvas'),box=await canvas.boundingBox();
  await canvas.click({position:{x:11.5/37*box.width,y:11.5/37*box.height}});
  await changeEvidenceControl(page,'#calculationDetails','check');
  // The source cell is erased and the sole candidate fills it by RS, giving 0.82.
  await expect(page.locator('#resultCellDetail')).toContainText('候補の評価（確率ではありません）: 中 (0.82)');
  await expect(page.locator('#resultCellDetail')).toContainText('Reed–Solomon/復元候補から推定');
  await changeEvidenceControl(page,'#calculationDetails','uncheck');
  await expect(page.locator('#resultCellDetail')).not.toContainText('候補の評価');
  await changeEvidenceControl(page,'#calculationDetails','check');
  await page.locator('#languageButton').click();
  await expect(page.locator('#resultCellDetail')).toContainText('Candidate assessment (not a probability): Medium (0.82)');
  await expect(page.locator('#resultCellDetail')).toContainText('Inferred from Reed–Solomon/recovery candidates');
  await expect(page.locator('#nextResultAction')).toBeHidden();
});

test('conditional RS-space guidance preserves the original recommended coordinate and never edits input',async({page})=>{
  await load(page,invalidModeWithUnknownTail());
  await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');
  await changeRecoverySetting(page,'#list','selectOption','0');
  await changeRecoverySetting(page,'#formatLimitMode','selectOption','UNLIMITED');
  await changeRecoverySetting(page,'#dataLimitMode','selectOption','EXACT');
  await runRecovery(page);
  await expect(page.locator('body')).toHaveAttribute('data-active-tab','results');
  await expect(page.locator('#resultCount')).toHaveText('0');
  // Independently computed with the original JVM RecoveryEngine and RepairGuidance:
  // 204 unknown bits - rank 56 = 148 free bits; first independent cell is (19,17).
  await expect(page.locator('#nextResultGuide')).toContainText('セル (19,17)');
  await page.locator('#nextResultDetails > summary').click();
  await expect(page.locator('#nextResultDetails')).toContainText('148');
  await expect(page.locator('#nextResultDetails')).toContainText('仮定');
  await expect(page.locator('#nextResultDetails')).toContainText('検証済みQR候補の分割ではありません');
  await page.locator('#nextResultAction').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-tab','edit');
  await expect(page.locator('#selectedCell')).toHaveText(/^(セルを選択|画像上のセルをタップしてください。)$/);
  await page.locator('button[data-tab="input"]').click();
  await changeRecoverySetting(page,'#utf8','check');
  await page.locator('button[data-tab="results"]').click();
  await expect(page.locator('#resultStatus')).toContainText('前回実行');
  await expect(page.locator('#nextResultGuide')).toBeHidden();
  await expect(page.locator('#nextResultAction')).toBeHidden();
});

test('format-limit failure directs the user to format settings instead of a cell',async({page})=>{
  await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();
  await page.locator('#textFile').setInputFiles('tests/fixtures/tail-alternatives.txt');
  await expect(page.locator('#recover')).toBeEnabled();
  await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','1');
  await changeRecoverySetting(page,'#formatLimitMode','selectOption','EXACT');
  await changeRecoverySetting(page,'#list','selectOption','0');await runRecovery(page);
  await expect(page.locator('body')).toHaveAttribute('data-active-tab','results');
  await expect(page.locator('#resultCount')).toHaveText('0');
  await expect(page.locator('#nextResultGuide')).toContainText('ECC・マスク');
  await expect(page.locator('#nextResultAction')).toHaveText('形式設定を確認');
  await page.locator('#nextResultAction').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-tab','input');
  await expect(page.locator('#mask')).toHaveValue('1');
});

test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('worker restart cannot reuse cached calculation or codeword evidence for a different input',async({page})=>{
  // Only delay a second repair to make force-stop deterministic. All actual results
  // and inspection responses still come from the original engine.
  await page.addInitScript(()=>Object.defineProperty(globalThis,'SharedArrayBuffer',{value:undefined,configurable:true}));
  await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';let recoveries=0;self.onmessage=({data})=>{if(data.request.action==='recover'&&++recoveries===2)return;self.postMessage({id:data.id,result:JSON.parse(execute(data.request))});};self.postMessage({ready:true});`}));
  const clean=JSON.parse(execute({action:'demo',damage:false,text:'QRevy · Repair with evidence'}));
  await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();
  await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
  async function inspect(){const canvas=page.locator('#canvas'),box=await canvas.boundingBox();await canvas.click({position:{x:11.5/37*box.width,y:11.5/37*box.height}});await changeEvidenceControl(page,'#sameCodeword','check');await expect(page.locator('#recover')).toBeEnabled();await changeEvidenceControl(page,'#calculationDetails','check');}
  await inspect();await expect(page.locator('#resultCellDetail')).toContainText('中 (0.82)');
  await expect(page.locator('#resultCellDetail')).toContainText('マスク解除後：[?]');
  await runRecovery(page);await expect(page.locator('#progress')).toBeVisible();await page.locator('#cancel').click();await expect(page.locator('#progress')).toBeHidden();
  await page.locator('[data-tab="input"]').click();await page.locator('#textFile').setInputFiles({name:'clean-guidance.txt',mimeType:'text/plain',buffer:Buffer.from(clean.text)});await expect(page.locator('#recover')).toBeEnabled();
  await runRecovery(page);await expect(page.locator('body')).toHaveAttribute('data-active-tab','results');await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
  await inspect();await expect(page.locator('#resultCellDetail')).toContainText('このセルの追加の計算記録はありません。');
  await expect(page.locator('#resultCellDetail')).toContainText('マスク解除後：[0]1100011');
  await expect(page.locator('#resultCellDetail')).not.toContainText('候補の評価');await expect(page.locator('#resultCellDetail')).not.toContainText('マスク解除後：[?]');
});
});

test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('candidate comparison refreshes when a restarted worker reuses candidate IDs',async({page})=>{
  await page.addInitScript(()=>{
    Object.defineProperty(globalThis,'SharedArrayBuffer',{value:undefined,configurable:true});
    const NativeWorker=globalThis.Worker;globalThis.comparisonRequests=0;
    globalThis.Worker=class extends NativeWorker{postMessage(message,...rest){if(message.request?.action==='compare')globalThis.comparisonRequests++;return super.postMessage(message,...rest);}};
  });
  await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';let recoveries=0;self.onmessage=({data})=>{if(data.request.action==='recover'&&++recoveries===2)return;self.postMessage({id:data.id,result:JSON.parse(execute(data.request))});};self.postMessage({ready:true});`}));
  await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/two-candidates.txt');await expect(page.locator('#recover')).toBeEnabled();
  await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('A');
  await page.locator('#candidateComparison > summary').click();await expect(page.locator('#comparisonText')).toContainText('候補1と候補2の比較');
  const before=await page.evaluate(()=>globalThis.comparisonRequests);
  await runRecovery(page);await expect(page.locator('#progress')).toBeVisible();await page.locator('#cancel').click();await expect(page.locator('#progress')).toBeHidden();
  await runRecovery(page);await expect.poll(()=>page.evaluate(()=>globalThis.comparisonRequests)).toBeGreaterThan(before);
  await expect(page.locator('#comparisonText')).toContainText('データコード語列は異なります。');await expect(page.locator('#recover')).toBeEnabled();
});
});

test('ambiguous repaired cell matches the original Android candidate assessment',async({page})=>{
  // QRevy v0.20.2, candidate 2, native UI Cell (0,9): Low (0.64), agreement 1/2.
  await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();
  await page.locator('#textFile').setInputFiles('tests/fixtures/two-candidates.txt');
  await expect(page.locator('#recover')).toBeEnabled();
  await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');
  await runRecovery(page);await expect(page.locator('#resultCount')).toHaveText('2');
  await page.locator('#candidatePicker').selectOption('1');await expect(page.locator('#payload')).toHaveValue('B');
  const canvas=page.locator('#canvas'),box=await canvas.boundingBox();
  await canvas.click({position:{x:2.5/25*box.width,y:11.5/25*box.height}});
  await changeEvidenceControl(page,'#calculationDetails','check');
  await expect(page.locator('#resultCellDetail')).toContainText('低 (0.64)');
  await expect(page.locator('#resultCellDetail')).toContainText('候補一致: 1 / 比較対象2候補（検証済み全2候補）');
  await page.locator('#languageButton').click();
  await expect(page.locator('#resultCellDetail')).toContainText('Candidate assessment (not a probability): Low (0.64)');
  await expect(page.locator('#resultCellDetail')).toContainText('Candidate agreement: 1 / 2 compared (2 validated candidates in total)');
});

test.describe('inspection restoration response contract',()=>{
 test.use({serviceWorkers:'block'});
 test('a candidate omitted during inspection restoration never uses another candidate with its old ID',async({page},testInfo)=>{
  // Response-contract test: after force-stop, retain only genuine candidate B and
  // assign it ID 1. This models retention limits without claiming that this small
  // fixture naturally exceeds a limit. Candidate payloads and evidence are real.
  await page.addInitScript(()=>{
    Object.defineProperty(globalThis,'SharedArrayBuffer',{value:undefined,configurable:true});
    const NativeWorker=globalThis.Worker;globalThis.restorationInspectionRequests=[];globalThis.restorationWorkers=0;globalThis.restorationResponses=[];
    globalThis.Worker=class extends NativeWorker{constructor(url,options){
      const target=new URL(url,location.href);target.searchParams.set('restorationGeneration',String(++globalThis.restorationWorkers));super(target,options);
      this.addEventListener('message',({data})=>{if(data.result?.restorationContract)globalThis.restorationResponses.push(data.result.restorationContract);});
    }postMessage(message,...rest){
      if(['resultData','compare','localizeResult','diagnostic'].includes(message.request?.action))globalThis.restorationInspectionRequests.push(message.request.action);
      return super.postMessage(message,...rest);
    }};
  });
  const routed=[];
  await page.route('**/worker.js?restorationGeneration=*',route=>{
    const generation=Number(new URL(route.request().url()).searchParams.get('restorationGeneration'));routed.push(generation);
    const restored=generation>1;
    return route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`
      import {execute} from './engine/js/engine.js';let recoveries=0,retainedId=null;
      self.onmessage=({data})=>{
        const request=data.request;
        if(request.action==='recover'&&++recoveries===2&&!${restored})return;
        if(retainedId!=null)for(const key of ['candidate','other'])if(request[key]?.inspectionId===1)request[key].inspectionId=retainedId;
        const result=JSON.parse(execute(request));
        if(${restored}&&request.action==='recover'){
          const retained=result.candidates.find(candidate=>candidate.text==='B');
          retainedId=retained.inspectionId;result.candidates=[{...retained,inspectionId:1}];
          result.restorationContract={generation:${generation},retained:retained.text,returnedId:1,engineId:retainedId};
        }
        self.postMessage({id:data.id,result});
      };self.postMessage({ready:true});`});
  });
  await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/two-candidates.txt');await expect(page.locator('#recover')).toBeEnabled();
  await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');
  await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('A');
  await runRecovery(page);await expect(page.locator('#progress')).toBeVisible();await page.locator('#cancel').click();await expect(page.locator('#progress')).toBeHidden();
  await page.locator('.steps [data-tab="results"]').click();
  await page.locator('#resultEvidenceTab').click();await page.locator('#resultDataPanel > summary').click();
  await expect.poll(()=>page.evaluate(()=>globalThis.restorationResponses)).toEqual([{generation:2,retained:'B',returnedId:1,engineId:2}]);
  await expect(page.locator('#recover')).toBeEnabled();
  await page.locator('#dataStage').selectOption('after');await expect(page.locator('#recover')).toBeEnabled();
  await testInfo.attach('restoration-response-contract',{body:JSON.stringify({routed,observed:await page.evaluate(()=>({workers:globalThis.restorationWorkers,responses:globalThis.restorationResponses,inspections:globalThis.restorationInspectionRequests})),payload:await page.locator('#payload').inputValue(),hex:await page.locator('#hex').textContent()},null,2),contentType:'application/json'});
  expect(routed).toEqual([1,2]);
  expect(await page.evaluate(()=>globalThis.restorationInspectionRequests)).toEqual([]);
  await expect(page.locator('#toast')).toContainText('以前の候補を再計算で再現できない');
  await expect(page.locator('#hex')).toHaveText('');await expect(page.locator('#payload')).toHaveValue('A');
  expect(await page.evaluate(()=>globalThis.restorationInspectionRequests)).toEqual([]);
  await page.locator('#languageButton').click();await expect(page.locator('#toast')).toContainText('previous candidate could not be reproduced');
  await expect(page.locator('#payload')).toHaveValue('A');await expect(page.locator('#resultCount')).toHaveText('2');
  await page.locator('#candidateComparison > summary').click();
  await expect(page.locator('#toast')).toContainText('previous candidate could not be reproduced');await expect(page.locator('#comparisonText')).toHaveText('');
  expect(await page.evaluate(()=>globalThis.restorationInspectionRequests)).toEqual([]);
  await page.locator('#candidateComparison > summary').click();await page.locator('#candidatePicker').selectOption('1');await expect(page.locator('#payload')).toHaveValue('B');
  await page.locator('#resultEvidenceTab').click();await expect(page.locator('#hex')).toContainText('Binary');
  expect(await page.evaluate(()=>globalThis.restorationInspectionRequests)).toContain('resultData');
});
});
