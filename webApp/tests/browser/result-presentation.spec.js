import {test,expect} from '@playwright/test';
import {execute} from '../../web/engine/js/engine.js';
import {changeRecoverySetting,runRecovery} from './input-helpers.js';

test.beforeEach(async({page})=>{
 await page.addInitScript(()=>{localStorage.setItem('qrevy-automatic-repair','false');localStorage.setItem('qrevy-language','ja');});
});

async function loadContent(page,text){
 const matrix=JSON.parse(execute({action:'demo',text,damage:false}));
 expect(matrix.error).toBeUndefined();
 await page.goto('/');
 await expect(page.locator('#textImport')).toBeEnabled();
 await page.locator('#textFile').setInputFiles({name:'result-presentation.txt',mimeType:'text/plain',buffer:Buffer.from(matrix.text)});
 await expect(page.locator('#recover')).toBeEnabled();
 return matrix.text;
}

for(const [name,text,fields] of [
 ['URL','https://example.com/qr?value=complete#fragment',['ホスト: example.com','Host: example.com']],
 ['vCard','BEGIN:VCARD\nVERSION:3.0\nFN:QR Test\nTEL:0123456789\nEND:VCARD',['電話番号: 0123456789','Phone: 0123456789']],
 ['location','geo:35.68,139.76',['35.68','35.68']],
 ['plain text','  <script>window.untrustedPayload=true</script>\n\n全文 😀 '+ '0123456789'.repeat(24)+'  ',['読み取れた内容','Read content']],
])test(`${name} descriptions and byte cautions remain visible without changing copied content`,async({page})=>{
 await loadContent(page,text);
 await page.evaluate(()=>Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async value=>window.copiedResult=value}}));
 await runRecovery(page);
 for(const [index,caution,method] of [[0,'文字コードによって表示が変わります','使用した方法'],[1,'the display depends on the character encoding','Methods used']]){
  if(index)await page.locator('#languageButton').click();
  await expect(page.locator('#payload')).toHaveValue(text);
  await expect(page.locator('#contentBefore')).toBeVisible();
  await expect(page.locator('#resultContentPane')).toContainText(fields[index]);
  await expect(page.locator('#contentAfter')).toBeVisible();
  await expect(page.locator('#contentAfter')).toContainText(caution);
  await expect(page.locator('#explanation')).toBeVisible();
  await expect(page.locator('#explanation')).toContainText(method);
  expect(await page.locator('#resultEvidencePanel').evaluate(element=>element.open)).toBe(false);
  await page.locator('#copyPayload').click();
  expect(await page.evaluate(()=>window.copiedResult)).toBe(text);
  expect(await page.evaluate(()=>window.untrustedPayload)).toBeUndefined();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 }
});

test('manual disagreement is visible with evidence collapsed and clears after another recovery',async({page})=>{
 const text='https://example.com/manual-warning',matrix=await loadContent(page,text);
 await page.locator('.steps [data-tab="edit"]').click();
 await page.locator('#fitView').click();
 const dimension=matrix.split('\n').length,canvas=page.locator('#canvas'),box=await canvas.boundingBox();
 await canvas.click({position:{x:(dimension+1.5)/(dimension+4)*box.width,y:(dimension+1.5)/(dimension+4)*box.height}});
 await page.locator('#editKind').selectOption('1');
 await page.locator(`[data-value="${matrix.at(-1)==='1'?'0':'1'}"]`).click();
 await changeRecoverySetting(page,'#lockManual','check');
 await runRecovery(page);
 await expect(page.locator('#payload')).toHaveValue(text);
 await expect(page.locator('#explanation')).toBeVisible();
 await expect(page.locator('#explanation')).toContainText('手動指定と異なる値: 1bit');
 expect(await page.locator('#resultEvidencePanel').evaluate(element=>element.open)).toBe(false);
 await page.locator('#resultEvidenceTab').click();
 await expect(page.locator('#methods')).toBeVisible();
 await expect(page.locator('#methods')).not.toContainText('手動指定と異なる値');
 await page.locator('#resultEvidenceTab').click();
 await page.locator('#languageButton').click();
 await expect(page.locator('#explanation')).toContainText('Values differing from manual entries: 1 bits');
 await page.locator('.steps [data-tab="input"]').click();
 await page.locator('#textFile').setInputFiles({name:'clean-result.txt',mimeType:'text/plain',buffer:Buffer.from(matrix)});
 await expect(page.locator('#contentBefore')).toHaveText('');
 await expect(page.locator('#contentAfter')).toHaveText('');
 await expect(page.locator('#explanation')).toBeHidden();
 await runRecovery(page);
 await expect(page.locator('#explanation')).toBeVisible();
 await expect(page.locator('#explanation')).not.toContainText('Values differing from manual entries');
});
