import {continueAfterOrdinaryReading} from './input-helpers.js';
import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
import {runRecovery} from './input-helpers.js';
test.beforeEach(async({page})=>{await page.addInitScript(()=>{localStorage.setItem('qrevy-automatic-repair','false');localStorage.setItem('qrevy-language','ja');});});
async function photo(page){
 await page.addInitScript(()=>{window.clippedFinderLabels=[];const fill=CanvasRenderingContext2D.prototype.fillText;CanvasRenderingContext2D.prototype.fillText=function(text,x,y,...args){if(this.canvas.id==='canvas'&&['左上','右上','右下','左下','TL','TR','BR','BL'].includes(text)){const width=this.measureText(text).width,m=this.getTransform();if(m.a*x+m.e<0||m.a*(x+width)+m.e>this.canvas.width+.1)window.clippedFinderLabels.push(text);}return fill.call(this,text,x,y,...args);};});
 await page.goto('/');
 if(process.env.QREVY_QA_FONT){const bytes=await readFile(process.env.QREVY_QA_FONT);await page.evaluate(async b=>{const f=new FontFace('QRevyQA',`url(data:font/ttf;base64,${b})`);await f.load();document.fonts.add(f);const descriptor=Object.getOwnPropertyDescriptor(CanvasRenderingContext2D.prototype,'font');Object.defineProperty(CanvasRenderingContext2D.prototype,'font',{...descriptor,set(value){descriptor.set.call(this,value.replace(/sans-serif/g,'QRevyQA, sans-serif'));}});},bytes.toString('base64'));await page.addStyleTag({content:'body,button,input,select,textarea{font-family:QRevyQA,sans-serif}pre{font-family:monospace,QRevyQA,sans-serif}.cell-tools small{font-family:QRevyQA,sans-serif}'});}
 await expect(page.locator('#imageImport')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();
}
async function capture(page,info,name){await page.locator('#toast').waitFor({state:'hidden'});await page.screenshot({path:info.outputPath(name+'.png')});}
for(const viewport of [{width:320,height:568},{width:390,height:844},{width:844,height:390}])test(`preview information follows the displayed task at ${viewport.width}x${viewport.height}`,async({page},info)=>{
 await page.setViewportSize(viewport);await photo(page);await expect(page.locator('#compactPreview')).toHaveCount(0);await expect(page.locator('.viewer-footer')).toHaveCount(0);
 for(const tab of ['input','edit']){
  await page.locator(`[data-tab="${tab}"]`).click();await page.locator('#cellSummary').scrollIntoViewIfNeeded();await expect(page.locator('#cellSummary')).toContainText('判定:');await expect(page.locator('#reasonLegend')).toBeHidden();
  expect(await page.locator('#canvasScroll').evaluate(el=>el.nextElementSibling.id)).toBe('cellSummary');const preview=await page.locator('#canvasScroll').boundingBox(),summary=await page.locator('#cellSummary').boundingBox();expect(summary.y-preview.y-preview.height).toBeGreaterThanOrEqual(-1);expect(summary.y-preview.y-preview.height).toBeLessThan(2);
  expect(summary.y).toBeGreaterThanOrEqual(-1);expect(summary.y+summary.height).toBeLessThanOrEqual(viewport.height+1);await expect(page.locator('#cellSummary')).toBeInViewport({ratio:.98});await capture(page,info,tab);expect(await page.evaluate(()=>window.clippedFinderLabels)).toEqual([]);
 }
 await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');await expect(page.locator('#resultDataPanel > summary')).toHaveText('データ');await expect(page.locator('#cellSummary')).toBeHidden();await expect(page.locator('#reasonLegend > p')).toBeVisible();expect(await page.locator('#reasonLegend').evaluate(el=>el.parentElement.id)).toBe('previewDock');
 await page.locator('#reasonLegend').scrollIntoViewIfNeeded();await expect(page.locator('#reasonLegend')).toContainText('橙=RS誤り訂正');await capture(page,info,'result-legend');
 await page.locator('#candidateValidation > summary').click();await expect(page.locator('#inputAgreement p')).toHaveCount(2);await expect(page.locator('#inputAgreement p').nth(0)).toContainText('形式情報領域:');await expect(page.locator('#inputAgreement p').nth(1)).toContainText('データ・ECC領域:');await page.locator('#candidateValidation').scrollIntoViewIfNeeded();await capture(page,info,'region-agreement');
 await page.locator('#languageButton').click();await expect(page.locator('#resultDataPanel > summary')).toHaveText('Data');await expect(page.locator('#inputAgreement p').nth(0)).toContainText('Format information:');await expect(page.locator('#inputAgreement p').nth(1)).toContainText('Data/ECC region:');await expect(page.locator('#reasonLegend > summary')).toHaveText('Colors and symbols');await expect(page.locator('#reasonLegend > p')).toContainText('Borders: green=QR structure');
});
