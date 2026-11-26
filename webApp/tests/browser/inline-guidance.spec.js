import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
import {nativeStrings} from '../../web/native-strings.js';

test.beforeEach(async({page})=>{
 await page.addInitScript(()=>{localStorage.setItem('qrevy-automatic-repair','false');localStorage.setItem('qrevy-language','ja');});
 await page.goto('/');
 await expect(page.locator('#imageImport')).toBeEnabled();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');
 await page.locator('#cropApply').click();
 await expect(page.locator('#recover')).toBeEnabled();
});

test('short editor guidance remains beside its controls without disclosures in either language',async({page},info)=>{
 await page.setViewportSize({width:320,height:700});
 await page.locator('[data-tab="edit"]').click();
 for(const language of ['ja','en']){
  if(language==='en')await page.locator('#languageButton').click();
  for(const [id,key] of [['cellEditHelp','cell_edit_help'],['cellSourceHelp','cell_source_legend']]){
   const note=page.locator('#'+id);
   await expect(note).toHaveText(nativeStrings[key][language]);
   await expect(note).toBeVisible();
   expect(await note.evaluate(element=>element.tagName==='P'&&!element.closest('details'))).toBe(true);
  }
  await expect(page.locator('#editKind')).toHaveAttribute('aria-describedby','cellSourceHelp');
  expect(await page.locator('#cellSourceHelp').evaluate(element=>element.previousElementSibling.contains(document.getElementById('editKind')))).toBe(true);
  expect(await page.locator('#cellEditHelp').evaluate(element=>element.parentElement.id==='editorTools'&&element.parentElement.firstElementChild===element)).toBe(true);
  await page.locator('#editKind').selectOption('5');
  await expect(page.locator('#cellSourceHelp')).toBeVisible();
  await page.locator('#cellEditHelp').scrollIntoViewIfNeeded();
  await page.screenshot({path:info.outputPath(`editor-guidance-${language}.png`)});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 }
 await page.locator('[data-tab="input"]').click();
 await expect(page.locator('#cellEditHelp')).toBeHidden();
 await expect(page.locator('#cellSourceHelp')).toBeHidden();
});

test('sampling guidance shares a single disclosure and preserves the option across reopening',async({page})=>{
 const disclosure=page.locator('#samplingOptions');
 for(const language of ['ja','en']){
  if(language==='en')await page.locator('#languageButton').click();
  await disclosure.locator(':scope > summary').click();
  await expect(page.locator('#geometryHelp')).toHaveText(nativeStrings.geometry_search_note[language]);
  await expect(page.locator('#geometryHelp')).toBeVisible();
  await expect(disclosure.locator('details')).toHaveCount(0);
  await expect(page.locator('#neighborhood')).toHaveAttribute('aria-describedby','geometryHelp');
  expect(await page.locator('#geometryHelp').evaluate(element=>element.previousElementSibling.contains(document.getElementById('neighborhood')))).toBe(true);
  await page.locator('#neighborhood').check();
  await disclosure.locator(':scope > summary').click();
  await expect(page.locator('#geometryHelp')).toBeHidden();
  await disclosure.locator(':scope > summary').click();
  await expect(page.locator('#neighborhood')).toBeChecked();
  await page.locator('#neighborhood').uncheck();
  await disclosure.locator(':scope > summary').click();
 }
});

test('analysis-image export needs only the additional-adjustments disclosure',async({page})=>{
 await expect(page.locator('#diagnosticOutput')).toHaveCount(0);
 await expect(page.locator('#exportSource')).toBeHidden();
 await page.locator('#imageSettings > summary').click();
 await expect(page.locator('#exportSource')).toBeVisible();
 expect(await page.locator('#exportSource').evaluate(element=>element.parentElement.classList.contains('image-adjustment-body'))).toBe(true);
 expect(await page.locator('.image-adjustment-body > details').evaluate(element=>element.open)).toBe(false);
 await page.evaluate(()=>navigator.canShare=()=>false);
 const pending=page.waitForEvent('download');
 await page.locator('#exportSource').click();
 const download=await pending;
 expect(download.suggestedFilename()).toBe('qrevy-sampling-source.png');
 expect((await readFile(await download.path())).subarray(0,8).toString('hex')).toBe('89504e470d0a1a0a');
 await page.locator('#imageSettings > summary').click();
 await expect(page.locator('#exportSource')).toBeHidden();
});
