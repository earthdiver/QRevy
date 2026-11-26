import {openResultData} from './input-helpers.js';
import {changeRecoverySetting,clickInputAction,openAppMenu,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
import {nativeStrings} from '../../web/native-strings.js';

for(const language of ['ja','en'])test(`saved ${language} language initializes the complete result workflow`,async({page})=>{
 const errors=[];page.on('pageerror',error=>errors.push(error.message));
 await page.addInitScript(language=>localStorage.setItem('qrevy-language',language),language);
 await page.goto('/');
 await expect(page.locator('#resultDiagnosticSection')).toHaveCount(1);
 await expect(page.locator('#executionReport')).toHaveCount(1);
 await expect(page.locator('#resultNavigation')).toHaveCount(0);
 const legend=page.locator('#reasonLegend p'),key=await legend.getAttribute('data-native-key');
 await expect(legend).toHaveText(nativeStrings[key][language]);
 await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();
 await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 expect(errors).toEqual([]);
});

test('native control labels match both upstream languages without translating payloads',async({page})=>{
 await page.goto('/');
 const checks={imageImport:'select_image',recover:'repair',exportText:'save_pre_recovery_text_matrix',exportPng:'save_png',undo:'undo',redo:'redo'};
 for(const lang of ['ja','en','ja']){
  if(await page.locator('html').getAttribute('lang')!==lang)await page.locator('#languageButton').click();
  for(const [id,key] of Object.entries(checks))await expect(page.locator('#'+id)).toContainText(nativeStrings[key][lang]);
  await expect(page.locator('#cellTool option[value="brush0"]')).toHaveText(nativeStrings.zero_brush[lang]);
  await expect(page.locator('#editKind option[value="5"]')).toHaveText(nativeStrings.cell_source_tentative[lang]);
  await expect(page.locator('label').filter({has:page.locator('#utf8')})).toContainText(nativeStrings.assume_utf8_byte_segments[lang]);
  await expect(page.locator('#candidatePicker')).toHaveAttribute('aria-label',nativeStrings.select_result[lang]);
 }
 await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await page.locator('#languageButton').click();await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await expect(page.locator('#copyPayload')).toHaveText(nativeStrings['inline:内容をコピー'].en);
 await expect(page.locator('#candidatePicker')).not.toBeVisible();await expect(page.locator('#browseCandidates')).not.toBeVisible();
});

test('small-phone result content and inspection remain usable in both languages',async({page},testInfo)=>{
 await page.setViewportSize({width:320,height:568});await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();await page.locator('#textFile').setInputFiles('tests/fixtures/two-candidates.txt');await expect(page.locator('#recover')).toBeEnabled();await page.locator('#repairDetails > summary').click();await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('A');
 for(const lang of ['ja','en']){
  if(await page.locator('html').getAttribute('lang')!==lang)await page.locator('#languageButton').click();
  await expect(page.locator('#payload')).toBeVisible();await expect(page.locator('#sameCodeword')).toBeVisible();
  await page.locator('#resultEvidenceTab').click();await expect(page.locator('#sameCodeword')).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);
  await page.screenshot({path:testInfo.outputPath(`small-result-${lang}.png`)});
 }
});

test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('memory-limited curved-grid notice uses the upstream text in both languages',async({page})=>{
 // Exercise the UI branch without forcing the browser to run out of memory.
 await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`import {execute} from './engine/js/engine.js';self.onmessage=({data})=>{const result=JSON.parse(execute(data.request));if(data.request.action==='curve')result.memoryLimited=true;self.postMessage({id:data.id,result});};self.postMessage({ready:true});`}));
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();await page.locator('#curved').check();await expect(page.locator('#recover')).toBeEnabled();
 const notice=nativeStrings['inline:\nメモリ上限により補正探索の一部を省略しました'];await expect(page.locator('#curveStatus')).toContainText(notice.ja.trim());await page.locator('#languageButton').click();await expect(page.locator('#curveStatus')).toContainText(notice.en.trim());expect(await page.locator('#curveStatus').textContent()).not.toMatch(/[\u3040-\u30ff]/);
});
});

// Inspect collapsed panels as well as visible controls. This fixture's decoded
// text is English; Japanese user data is covered separately below. License
// documents retain their original language links (for example, 日本語).
async function expectEnglishUI(page){
 await expect.poll(()=>page.evaluate(()=>{
  const japanese=/[\u3040-\u30ff\u3400-\u9fff]/u,remaining=[];
  const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
  while(walker.nextNode()){
   const n=walker.currentNode,p=n.parentElement;
   if(p&&!p.closest('script,style,textarea,#languageButton,#licenseText')&&japanese.test(n.textContent))remaining.push(`${p.id||p.tagName}: ${n.textContent.trim()}`);
  }
  for(const el of document.querySelectorAll('[aria-label],[title],[placeholder]'))for(const name of ['aria-label','title','placeholder'])if(japanese.test(el.getAttribute(name)||''))remaining.push(`${el.id} ${name}: ${el.getAttribute(name)}`);
  return remaining;
 })).toEqual([]);
}

test('English labels cover image adjustments, history, results, dialogs and accessible names',async({page})=>{
 await page.addInitScript(()=>localStorage.setItem('qrevy-language','en'));
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();await expectEnglishUI(page);
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await expect(page.locator('#cropApply')).toBeVisible();await expectEnglishUI(page);
 await page.locator('#cropApply').click();await expect(page.locator('#recover')).toBeEnabled();await expect(page.locator('#viewTitle')).toHaveText('Image recovery');
 await page.locator('#fineCorners').click();await expectEnglishUI(page);await page.locator('#cornerApply').click();await expect(page.locator('#recover')).toBeEnabled();
 await page.locator('[data-tab="edit"]').click();await expectEnglishUI(page);
 const canvas=page.locator('#canvas');await canvas.scrollIntoViewIfNeeded();const box=await canvas.boundingBox();await canvas.click({position:{x:box.width/2,y:box.height/2}});await page.locator('[data-value="?"]').click();await expectEnglishUI(page);await page.locator('#undo').click();
 await runRecovery(page);await expect(page.locator('#payload')).not.toHaveValue('');await expectEnglishUI(page);
 for(const lang of ['ja','en']){
  await page.locator('#languageButton').click();await expect(page.locator('html')).toHaveAttribute('lang',lang);await expect(page.locator('#recover')).toBeEnabled();
  await expect(page.locator('#viewTitle')).toHaveText(lang==='ja'?'画像からの修復':'Image recovery');
 }
 await expectEnglishUI(page);
 await page.locator('#resultEvidenceTab').click();await page.locator('#resultDataPanel > summary').click();await openResultData(page);await expect(page.locator('#hex')).not.toBeEmpty();await expectEnglishUI(page);
 await openAppMenu(page);await page.locator('#sessionsButton').click();await expect(page.locator('#sessionsDialog')).toBeVisible();await expectEnglishUI(page);await page.locator('[data-close="sessionsDialog"]').click();
 await openAppMenu(page);await page.locator('#helpButton').click();await page.locator('#licensesButton').click();await expect(page.locator('#licenseText')).toContainText('Privacy');await expectEnglishUI(page);
});

test('language changes preserve Japanese QR content and imported workspace names',async({page})=>{
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();
 const payload='明るさをリセット'; // An exact UI translation key must remain user data.
 const matrix=await page.evaluate(async text=>{const {execute}=await import('/engine/js/engine.js');return JSON.parse(execute({action:'demo',text,encoding:'UTF-8',damage:false})).text;},payload);
 await page.locator('#textFile').setInputFiles({name:'japanese.txt',mimeType:'text/plain',buffer:Buffer.from(matrix)});await expect(page.locator('#recover')).toBeEnabled();
 await runRecovery(page);await expect(page.locator('#payload')).toHaveValue(payload);
 await page.locator('#languageButton').click();await expect(page.locator('#recover')).toBeEnabled();await expect(page.locator('#payload')).toHaveValue(payload);await expect(page.locator('#viewTitle')).toHaveText('TXT matrix');
 // A name from an imported workspace is not an application label, even when
 // it happens to be identical to a built-in name.
 const file=await page.evaluate(async()=>{const {listSessions,exportWorkspace}=await import('/storage.js');const s=(await listSessions())[0];s.name='画像からの修復';delete s.nameKey;return (await exportWorkspace(s)).text();});
 await page.locator('#sessionFile').setInputFiles({name:'named.qrpwa',mimeType:'application/json',buffer:Buffer.from(file)});await expect(page.locator('#viewTitle')).toHaveText('画像からの修復');await expect(page.locator('#recover')).toBeEnabled();await expect(page.locator('#payload')).toHaveValue(payload);
 await page.locator('#languageButton').click();await expect(page.locator('#viewTitle')).toHaveText('画像からの修復');await expect(page.locator('#payload')).toHaveValue(payload);
});


test('image adjustment labels address their inputs and corner step units follow the selected language',async({page})=>{
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await expect(page.locator('#recover')).toBeEnabled();
 for(const language of ['ja','en']){
  if(await page.locator('html').getAttribute('lang')!==language)await page.locator('#languageButton').click();
  for(const id of ['brightness','contrast'])expect(await page.locator('label[for="'+id+'"]').evaluate(el=>el.control.id)).toBe(id);
  await page.locator('#fineCorners').click();
  await expect(page.locator('#cornerStep option[value="0.25"]')).toHaveText(language==='ja'?'0.25セル幅':'0.25 modules');
  await expect(page.locator('#cornerIndex option[value="2"]')).toHaveText(language==='ja'?'右下':'Bottom right');
  await page.locator('#cornerApply').click();await expect(page.locator('#recover')).toBeEnabled();
 }
});
