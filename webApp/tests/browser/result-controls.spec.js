import {continueAfterOrdinaryReading} from './input-helpers.js';
import {assignFinderOrientation,openResultData} from './input-helpers.js';
import {changeEvidenceControl,changeRecoverySetting,clickInputAction,clickWorkspaceAction,openAppMenu,openWorkspaceMenu,runRecovery,showDiagnostics} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
import {readFile} from 'node:fs/promises';

async function demo(page){await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();}
async function recover(page){await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');}
async function exported(page,id){await page.evaluate(()=>navigator.canShare=()=>false);const event=page.waitForEvent('download');if(['#exportSession','#exportQrr','#exportText'].includes(id))await clickWorkspaceAction(page,id);else {if(id==='#exportPng')await page.locator(id).click();}return readFile(await(await event).path());}

test('result image compares the same input and repaired cells without changing the payload',async({page})=>{
 await demo(page);await recover(page);
 const after=await page.locator('#canvas').evaluate(el=>el.toDataURL());
 await changeEvidenceControl(page,'#resultStage','selectOption','before');
 const before=await page.locator('#canvas').evaluate(el=>el.toDataURL());expect(before).not.toBe(after);
 await changeEvidenceControl(page,'#unmask','check');expect(await page.locator('#canvas').evaluate(el=>el.toDataURL())).not.toBe(before);
 await changeEvidenceControl(page,'#resultStage','selectOption','after');await changeEvidenceControl(page,'#unmask','uncheck');
 expect(await page.locator('#canvas').evaluate(el=>el.toDataURL())).toBe(after);
 await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await page.locator('#reasonLegend > summary').click();await expect(page.locator('#reasonLegend')).toContainText('枠: 緑=QRの固定構造 / 黄土色=形式情報 / 橙=RS誤り訂正 / 紫=データ構造の条件 / 赤紫=ビット候補探索 / 青=手動指定');await page.locator('#reasonLegend > summary').click();
 await changeEvidenceControl(page,'#resultStage','selectOption','compare');await expect(page.locator('#beforeCanvas')).toBeVisible();const b=await page.locator('#beforeCanvas').boundingBox();await page.locator('#beforeCanvas').click({position:{x:b.width*.5,y:b.height*.5}});await expect(page.locator('#selectedCell')).toHaveText('選択: (16,16) / データ・ECC / 値=1');
});

test('editing recovery settings marks the previous result stale and prevents exporting it as current',async({page})=>{
 await demo(page);await recover(page);await page.locator('[data-tab="input"]').click();
 await changeRecoverySetting(page,'#utf8','check');await page.locator('[data-tab="results"]').click();
 await expect(page.locator('#resultStatus')).toContainText(/変更|古い|再修復|再実行/);
 await expect(page.locator('#copyPayload')).toBeDisabled();await expect(page.locator('#exportPng')).toBeDisabled();
 await page.locator('.steps [data-tab="results"]').click();await page.locator('#resultEvidenceTab').click();await page.locator('#resultDataPanel').locator(':scope > summary').click();await openResultData(page);await expect(page.locator('#hex')).toContainText('Binary');await expect(page.locator('#copyPayload')).toBeDisabled();await expect(page.locator('#exportPng')).toBeDisabled();
 await recover(page);await expect(page.locator('#copyPayload')).toBeEnabled();await expect(page.locator('#exportPng')).toBeEnabled();
});

test('data inspection switches before and after, mask state, and partial interpretation',async({page})=>{
 await demo(page);await recover(page);await page.locator('#resultEvidenceTab').click();await page.locator('#resultDataPanel').locator(':scope > summary').click();
 await page.locator('#dataStage').selectOption('before');await page.locator('#dataUnmask').check();await openResultData(page);await expect(page.locator('#hex')).toContainText(/Binary/);
 const before=await page.locator('#hex').textContent();await page.locator('#dataStage').selectOption('after');await expect(page.locator('#hex')).not.toHaveText(before);
 const unmasked=await page.locator('#hex').textContent();await page.locator('#dataUnmask').uncheck();await expect(page.locator('#hex')).not.toHaveText(unmasked);
 await page.locator('#dataUnmask').check();await page.locator('#dataStage').selectOption('before');await page.locator('#dataPartial').check();await expect(page.locator('#hex')).toContainText(/部分|Partial/);
 await page.locator('[data-tab="edit"]').click();await page.locator('#openInputData').click();await expect(page.locator('#inputDataReport')).toContainText('Binary');await page.locator('#inputPartial').check();await expect(page.locator('#inputDataReport')).toContainText(/部分|Partial/);
});

test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('candidate contents and selected payload retain complete long text including changed suffixes',async({page})=>{
 // UI-only comparison fixture: all matrix/provenance comes from the real engine.
 await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';self.onmessage=({data})=>{const result=JSON.parse(execute(data.request));if(data.request.action==='recover'&&result.candidates?.length){const base=result.candidates[0];result.candidates=[{...base,text:'first '+('complete '.repeat(80))+'END-A'},{...base,text:'second '+('complete '.repeat(80))+'END-B'}];}self.postMessage({id:data.id,result});};self.postMessage({ready:true});`}));
 await demo(page);await runRecovery(page);await expect(page.locator('#resultCount')).toHaveText('2');
 await expect(page.locator('#payload')).toHaveValue('first '+('complete '.repeat(80))+'END-A');await page.locator('#browseCandidates').click();await expect(page.locator('#candidates .candidate')).toHaveCount(2);await expect(page.locator('#candidates .candidate').first()).toContainText('complete '.repeat(80));await expect(page.locator('#candidates .candidate').last()).toContainText('END-B');await page.locator('#candidates .candidate').last().click();await expect(page.locator('#payload')).toHaveValue('second '+('complete '.repeat(80))+'END-B');
});
});

test('workspace cancellation, checksum rejection, deletion and backup restoration preserve work',async({page})=>{
 await demo(page);await recover(page);const backup=await exported(page,'#exportSession');
 await openAppMenu(page);await page.locator('#sessionsButton').click();page.once('dialog',d=>d.dismiss());await page.locator('#sessionList .session-item button').last().click();await expect(page.locator('#sessionList .session-item')).toHaveCount(1);await page.locator('[data-close="sessionsDialog"]').click();
 const broken=JSON.parse(backup.toString());broken.sha256='0'.repeat(64);await page.locator('#sessionFile').setInputFiles({name:'broken.qrpwa',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(broken))});await expect(page.locator('#toast')).toContainText('整合性');await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await openAppMenu(page);await page.locator('#sessionsButton').click();page.once('dialog',d=>d.accept());await page.locator('#sessionList .session-item button').last().click();await expect(page.locator('#sessionList .session-item')).toHaveCount(0);await page.locator('[data-close="sessionsDialog"]').click();
 await page.locator('#sessionFile').setInputFiles({name:'restored.qrpwa',mimeType:'application/json',buffer:backup});await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
});

test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('hard interruption is reachable and restores the worker without losing input',async({page})=>{
 await page.addInitScript(()=>Object.defineProperty(globalThis,'SharedArrayBuffer',{value:undefined,configurable:true}));
 await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';self.onmessage=({data})=>{if(data.request.action==='recover')return;self.postMessage({id:data.id,result:JSON.parse(execute(data.request))});};self.postMessage({ready:true});`}));
 await demo(page);const original=await exported(page,'#exportSession');await runRecovery(page);await expect(page.locator('#progress')).toBeVisible();await expect(page.locator('#languageButton')).toBeDisabled();await expect(page.locator('#helpButton')).toBeDisabled();await page.locator('#cancel').click();await expect(page.locator('#progress')).toBeHidden();await expect(page.locator('#recover')).toBeEnabled();await expect(page.locator('#languageButton')).toBeEnabled();await expect(page.locator('#helpButton')).toBeEnabled();
 const next=await exported(page,'#exportSession');const a=JSON.parse(JSON.parse(original.toString()).payload),b=JSON.parse(JSON.parse(next.toString()).payload);expect(b.history[b.cursor].matrix).toEqual(a.history[a.cursor].matrix);
});
});

test('help closes without navigation and language survives restart without rewriting data',async({page})=>{
 await demo(page);await recover(page);await openAppMenu(page);await page.locator('#helpButton').click();await expect(page.locator('#helpDialog')).toBeVisible();await expect(page.locator('#helpDialog a[href="./licenses/NOTICE.md"]')).toBeVisible();await page.locator('[data-close="helpDialog"]').click();await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await page.locator('#languageButton').click();await expect(page.locator('html')).toHaveAttribute('lang','en');await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');await page.reload();await expect(page.locator('html')).toHaveAttribute('lang','en');await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList button').first().click();await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
});

test('image workspace is durably saved and can be reopened after reload',async({page})=>{
 const errors=[];page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
 await page.goto('/');await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();await clickWorkspaceAction(page,'#save');
 await expect(page.locator('#saveStatus')).toContainText(/端末内に保存済み|Saved on this device/);await page.reload();await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList .session-item button').first().click();await expect(page.locator('#imageView')).toBeEnabled();await assignFinderOrientation(page);await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');expect(errors).toEqual([]);
});

test('result action labels match the current Japanese and English interface',async({page})=>{
 await demo(page);await recover(page);
 const expected=[['#recover','修復する','Repair'],['#copyPayload','内容をコピー','Copy content'],['#exportPng','PNG保存','Save PNG'],['#browseCandidates','候補一覧','Candidates'],['#sessionsButton','保存した作業を開く','Open a saved workspace']];
 for(const [id,ja] of expected)await expect(page.locator(id)).toHaveText(ja);
 await page.locator('#languageButton').click();
 for(const [id,,en] of expected)await expect(page.locator(id)).toHaveText(en);
 await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
});

test('post-terminator analysis and input reports are cleared when their source changes',async({page})=>{
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/tail-alternatives.txt');await expect(page.locator('#recover')).toBeEnabled();await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');await runRecovery(page);await expect(page.locator('#resultCount')).toHaveText('0');await page.locator('#tailAnalysis > summary').click();await expect(page.locator('#inspectTail')).toHaveCount(0);await expect(page.locator('#tailReport')).not.toBeEmpty();
 await page.locator('[data-tab="input"]').click();await changeRecoverySetting(page,'#utf8','check');await expect(page.locator('#tailReport')).toBeEmpty();
 await page.locator('[data-tab="edit"]').click();await page.locator('#openInputData').click();await expect(page.locator('#inputDataReport')).not.toBeEmpty();await page.locator('#closeInputData').click();
 await page.locator('[data-tab="input"]').click();await changeRecoverySetting(page,'#utf8','uncheck');await expect(page.locator('#inputDataReport')).toBeEmpty();
});

test('deleting all saved work supports cancellation, clears the current saved input, and allows a new import',async({page})=>{
 await demo(page);await clickWorkspaceAction(page,'#save');await expect(page.locator('#saveStatus')).toContainText(/保存済み/);
 await page.locator('#textFile').setInputFiles('tests/fixtures/priority-before.txt');await expect(page.locator('#dimensions')).toContainText('V1');await clickWorkspaceAction(page,'#save');await openAppMenu(page);await page.locator('#sessionsButton').click();await expect(page.locator('#sessionList .session-item')).toHaveCount(2);
 page.once('dialog',d=>d.dismiss());await page.locator('#deleteAllSessions').click();await expect(page.locator('#sessionList .session-item')).toHaveCount(2);
 page.once('dialog',d=>d.accept());await page.locator('#deleteAllSessions').click();await expect(page.locator('#sessionList .session-item')).toHaveCount(0);await page.locator('[data-close="sessionsDialog"]').click();await expect(page.locator('#recover')).toBeDisabled();await page.reload();await expect(page.locator('#textImport')).toBeEnabled();await expect(page.locator('#recover')).toBeDisabled();await page.locator('#textFile').setInputFiles('tests/fixtures/priority-before.txt');await expect(page.locator('#dimensions')).toContainText('V1');await changeRecoverySetting(page,'#single','check');await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});

test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('one-time single-segment retry is not reused by the next ordinary repair',async({page})=>{
 // Expose retry deterministically and record the actual engine request; no payload validity claim.
 await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';self.onmessage=({data})=>{const result=JSON.parse(execute(data.request));if(data.request.action==='recover'){result.retryAvailable=true;result.diagnosticReport=null;result.diagnostic='request.single='+String(!!data.request.settings.single);}self.postMessage({id:data.id,result});};self.postMessage({ready:true});`}));
 await demo(page);await recover(page);await expect(page.locator('#diagnostic')).toHaveText('request.single=false');await page.locator('#retrySingle').click();await expect(page.locator('#diagnostic')).toHaveText('request.single=true');await recover(page);await expect(page.locator('#diagnostic')).toHaveText('request.single=false');
});
});

test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('previous candidate remains inspectable after a forced interruption',async({page})=>{
 await page.addInitScript(()=>Object.defineProperty(globalThis,'SharedArrayBuffer',{value:undefined,configurable:true}));
 await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';let recoveries=0;self.onmessage=({data})=>{if(data.request.action==='recover'&&++recoveries===2)return;self.postMessage({id:data.id,result:JSON.parse(execute(data.request))});};self.postMessage({ready:true});`}));
 await demo(page);await recover(page);await runRecovery(page);await expect(page.locator('#progress')).toBeVisible();await page.locator('#cancel').click();await expect(page.locator('#progress')).toBeHidden();await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await page.locator('.steps [data-tab="results"]').click();await page.locator('#resultEvidenceTab').click();await page.locator('#resultDataPanel').locator(':scope > summary').click();await openResultData(page);await expect(page.locator('#hex')).toContainText('Binary');
});
});

test('failed repair exposes separate tail interpretations and raw-bit offsets without presenting them as valid payload',async({page})=>{
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles({name:'synthetic-tail.txt',mimeType:'text/plain',buffer:await readFile('tests/fixtures/tail-alternatives.txt')});await expect(page.locator('#recover')).toBeEnabled();await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');await runRecovery(page);await expect(page.locator('#diagnostic')).toContainText(/RS/);await expect(page.locator('#resultCount')).toHaveText('0');await expect(page.locator('#diagnostic')).toContainText('RS訂正後の本文（未検証の候補）');await expect(page.locator('#payload')).toBeHidden();const masked=await page.locator('#canvas').evaluate(el=>el.toDataURL());await changeEvidenceControl(page,'#unmask','check');expect(await page.locator('#canvas').evaluate(el=>el.toDataURL())).not.toBe(masked);await changeEvidenceControl(page,'#unmask','uncheck');
 await showDiagnostics(page);await page.locator('#tailAnalysis > summary').click();await expect(page.locator('#inspectTail')).toHaveCount(0);await expect(page.locator('#tailSource')).toBeVisible();await expect(page.locator('#tailSource option')).toHaveCount(2);await page.locator('#tailSource').selectOption('0');await expect(page.locator('#tailInterpretation option')).toHaveCount(2);await expect(page.locator('#tailReport')).toContainText('ABR');const first=await page.locator('#tailReport').textContent();await page.locator('#tailAnalysis > summary').click();await page.locator('#tailAnalysis > summary').click();await expect(page.locator('#tailReport')).toHaveText(first);await page.locator('#tailInterpretation').selectOption('1');await expect(page.locator('#tailReport')).toContainText('A@');
 await page.locator('#tailRawPanel > summary').click();await expect(page.locator('#tailOffset')).toHaveValue('23');await page.locator('#tailOffsetMode').selectOption('byte');await expect(page.locator('#tailOffset')).toHaveValue('24');await expect(page.locator('#tailRaw')).toContainText('24:');const report=await page.locator('#tailReport').textContent();await page.locator('#tailOffsetMode').selectOption('custom');await page.locator('#tailOffset').fill('32');await page.locator('#tailRawShow').click();await expect(page.locator('#tailRaw')).toContainText('32:');await expect(page.locator('#tailReport')).toHaveText(report);await page.locator('#tailOffset').fill('1');await page.locator('#tailRawShow').click();expect(await page.locator('#tailOffset').evaluate(el=>el.checkValidity())).toBe(false);await expect(page.locator('#tailRaw')).toContainText('32:');
 await page.locator('#tailSource').selectOption('1');await expect(page.locator('#tailReport')).toContainText('通常の誤り訂正（RS）を適用したデータです。');await expect(page.locator('#payload')).toBeHidden();
});

test('format cells reveal their repair evidence and related format-role cells',async({page})=>{
 await demo(page);await recover(page);const c=page.locator('#canvas'),b=await c.boundingBox();await c.click({position:{x:(8+2.5)/37*b.width,y:2.5/37*b.height}});
 await expect(page.locator('#resultCellDetail')).not.toBeEmpty();const normal=await c.evaluate(el=>el.toDataURL());await changeEvidenceControl(page,'#sameCodeword','check');expect(await c.evaluate(el=>el.toDataURL())).not.toBe(normal);await changeEvidenceControl(page,'#calculationDetails','uncheck');const compact=await page.locator('#resultCellDetail').textContent();await changeEvidenceControl(page,'#calculationDetails','check');await expect(page.locator('#resultCellDetail')).not.toHaveText(compact);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
});

test('failure diagnostics switch language using native report text',async({page})=>{
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/tail-alternatives.txt');await expect(page.locator('#recover')).toBeEnabled();await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');await runRecovery(page);await expect(page.locator('#diagnostic')).toContainText('RS訂正後の本文（未検証の候補）');await page.locator('#languageButton').click();await expect(page.locator('#diagnostic')).toContainText('Body after RS correction (unverified candidate)');expect(await page.locator('#diagnostic').textContent()).not.toMatch(/[\u3040-\u30ff]/);await expect(page.locator('#resultCount')).toHaveText('0');
});

test('stale candidate language change preserves its input and disables current-result export',async({page})=>{
 await demo(page);await recover(page);await page.locator('[data-tab="input"]').click();await changeRecoverySetting(page,'#utf8','check');await page.locator('[data-tab="results"]').click();await page.locator('#languageButton').click();await expect(page.locator('#resultStatus')).toContainText('This is the previous repair result. Your current draft changes have not been applied.');await expect(page.locator('#copyPayload')).toBeDisabled();await page.locator('#resultEvidenceTab').click();await expect(page.locator('#explanation')).not.toContainText('修復');await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
});

test('appearance changes are cancellable and apply on next launch while saved work remains available',async({page})=>{
 await demo(page);await recover(page);const current=await page.locator('body').getAttribute('data-appearance'),next=current==='classic'?'modern':'classic';await openAppMenu(page);await page.locator('#helpButton').click();await page.locator('#appearanceButton').click();await page.locator('#appearanceChoice').selectOption(next);await page.locator('[data-close="appearanceDialog"]').click();await expect(page.locator('body')).toHaveAttribute('data-appearance',current);await page.locator('#appearanceButton').click();await expect(page.locator('#appearanceChoice')).toHaveValue(current);await page.locator('#appearanceChoice').selectOption(next);await page.locator('#saveAppearance').click();await expect(page.locator('#appearanceNotice')).toBeVisible();await expect(page.locator('#appearanceNoticeText')).toHaveText('保存しました。アプリを終了して次回起動したときに反映されます。現在の作業はそのまま続けられます。');await expect(page.locator('body')).toHaveAttribute('data-appearance',current);await page.locator('[data-close="appearanceNotice"]').click();await page.locator('[data-close="helpDialog"]').click();await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');await page.reload();await expect(page.locator('body')).toHaveAttribute('data-appearance',next);await openAppMenu(page);await page.locator('#sessionsButton').click();await page.locator('#sessionList button').first().click();await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
});

test('version and complete license documents open and close without losing the current result',async({page})=>{
 await demo(page);await recover(page);await openAppMenu(page);await page.locator('#helpButton').click();await page.locator('#versionButton').click();await expect(page.locator('#versionText')).toContainText('QRevy PWA');await expect(page.locator('#versionText')).toContainText('0.2.0');await expect(page.locator('#versionText')).toContainText('0.21.1');await page.locator('[data-close="versionDialog"]').click();await page.locator('#licensesButton').click();await page.locator('#licenseChoice').selectOption('LICENSE');await expect(page.locator('#licenseText')).toContainText('END OF TERMS AND CONDITIONS');await page.locator('#licenseChoice').selectOption('THIRD_PARTY_NOTICES.md');await expect(page.locator('#licenseText')).toContainText('ZXing');await page.locator('#licenseChoice').selectOption('privacy');await expect(page.locator('#licenseText')).toContainText('ブラウザー');await page.locator('[data-close="licensesDialog"]').click();await page.locator('[data-close="helpDialog"]').click();await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
});

test('real ambiguous input supports native candidate comparison and exports the selected masked QR',async({page})=>{
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/two-candidates.txt');await expect(page.locator('#recover')).toBeEnabled();await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');await runRecovery(page);await expect(page.locator('#resultCount')).toHaveText('2');await expect(page.locator('#payload')).toHaveValue('A');await page.locator('#candidateComparison > summary').click();await expect(page.locator('#comparisonText')).toContainText('候補1と候補2の比較');await expect(page.locator('#comparisonText')).toContainText('データコード語列は異なります。');await page.locator('#candidatePicker').selectOption('1');await expect(page.locator('#payload')).toHaveValue('B');await expect(page.locator('#comparisonText')).toContainText('候補2と候補1の比較');await showDiagnostics(page);await expect(page.locator('#diagnostic')).toContainText('候補2');await changeEvidenceControl(page,'#unmask','check');const png=await exported(page,'#exportPng');
 const text=await page.evaluate(async data=>{const img=new Image();img.src='data:image/png;base64,'+data;await img.decode();const canvas=document.createElement('canvas');canvas.width=img.width;canvas.height=img.height;const ctx=canvas.getContext('2d');ctx.drawImage(img,0,0);const rows=[];for(let y=0;y<21;y++){let row='';for(let x=0;x<21;x++)row+=ctx.getImageData((x+4.5)*12,(y+4.5)*12,1,1).data[0]<128?'1':'0';rows.push(row);}const {execute}=await import('./engine/js/engine.js');const matrix=JSON.parse(execute({action:'parse',text:rows.join('\n')}));const result=JSON.parse(execute({action:'recover',matrix,settings:{version:1,list:0,ecc:'L',mask:0}}));return result.candidates.map(c=>c.text);},png.toString('base64'));expect(text).toContain('B');
});

 test('diagnostic stages keep summaries visible while native details expand independently',async({page})=>{
 await demo(page);await recover(page);await showDiagnostics(page);
 const sections=page.locator('#diagnostic .diagnostic-section');await expect(sections).toHaveCount(4);
 for(let i=0;i<4;i++){await expect(sections.nth(i).locator(':scope > h3')).toBeVisible();await expect(sections.nth(i).locator(':scope > button')).toHaveAttribute('aria-expanded','false');}
 await sections.nth(0).locator(':scope > button').click();await expect(sections.nth(0).locator('.diagnostic-groups')).toBeVisible();await expect(sections.nth(1).locator('.diagnostic-groups')).not.toBeVisible();
 await sections.nth(2).locator(':scope > button').click();const calc=sections.nth(2).locator('[data-group="CALCULATIONS"] > button');if(await calc.count()){await calc.click();await expect(calc).toHaveAttribute('aria-expanded','true');}
 await page.locator('#languageButton').click();await expect(sections.nth(0).locator(':scope > h3')).toHaveText('1. Settings for this run');await expect(sections.nth(0).locator(':scope > button')).toHaveAttribute('aria-expanded','true');
 await expect(page.locator('#tailAnalysis')).not.toBeVisible();
 });

test('resuming an earlier history entry branches from that input and discards later draft changes',async({page})=>{
 await demo(page);const initial=JSON.parse(JSON.parse((await exported(page,'#exportSession')).toString()).payload);await page.locator('[data-tab="edit"]').click();const c=page.locator('#canvas'),box=await c.boundingBox();await c.click({position:{x:box.width*.5,y:box.height*.5}});await page.locator('[data-value="0"]').click();await page.locator('[data-value="1"]').click();await openWorkspaceMenu(page);await page.locator('details').filter({has:page.locator('#history')}).locator(':scope > summary').click();await page.locator('#history button').first().click();const restored=JSON.parse(JSON.parse((await exported(page,'#exportSession')).toString()).payload);expect(restored.history[restored.cursor].matrix).toEqual(initial.history[initial.cursor].matrix);await page.locator('[data-value="?"]').click();await expect(page.locator('#redo')).toBeDisabled();await expect(page.locator('#selectedCell')).toContainText('値=?');
});

test('native single-segment retry prompt accepts once and ordinary repair clears the assumption',async({page})=>{
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/retry-single.txt');await expect(page.locator('#recover')).toBeEnabled();await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');await changeRecoverySetting(page,'#dataLimitMode','selectOption','MANUAL');await changeRecoverySetting(page,'#dataLimit','fill','1');await page.locator('#dataLimit').dispatchEvent('change');await runRecovery(page);await expect(page.locator('#singleRetryDialog')).toBeVisible();await expect(page.locator('#resultCount')).toHaveText('0');await page.locator('#acceptSingleRetry').click();await expect(page.locator('#payload')).toHaveValue('qrevy-a');await runRecovery(page);await expect(page.locator('#resultCount')).toHaveText('0');await expect(page.locator('#singleRetryDialog')).not.toBeVisible();await expect(page.locator('#single')).not.toBeChecked();
});

test('declining native single-segment retry keeps the failure and does not repeat the prompt for unchanged input',async({page})=>{
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/retry-single.txt');await expect(page.locator('#recover')).toBeEnabled();await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');await changeRecoverySetting(page,'#dataLimitMode','selectOption','MANUAL');await changeRecoverySetting(page,'#dataLimit','fill','1');await page.locator('#dataLimit').dispatchEvent('change');await runRecovery(page);await expect(page.locator('#singleRetryDialog')).toBeVisible();await page.locator('#declineSingleRetry').click();await expect(page.locator('#resultCount')).toHaveText('0');await runRecovery(page);await expect(page.locator('#recover')).toBeEnabled();await expect(page.locator('#singleRetryDialog')).not.toBeVisible();await expect(page.locator('#single')).not.toBeChecked();
});
