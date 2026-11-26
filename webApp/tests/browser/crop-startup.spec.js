import {test,expect} from '@playwright/test';

for(const automatic of [false,true])test(`standalone startup and crop complete with automatic repair ${automatic}`,async({page})=>{
 const errors=[];
 page.on('pageerror',error=>errors.push(error.message));
 page.on('console',message=>{if(message.type()==='error')errors.push(message.text());});
 await page.addInitScript(automatic=>{
  Object.defineProperty(navigator,'standalone',{value:true,configurable:true});
  localStorage.setItem('qrevy-automatic-repair',String(automatic));
 },automatic);
 await page.goto('/');
 await expect(page.locator('#imageImport')).toBeEnabled();
 expect(errors).toEqual([]);
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');
 await page.locator('#cropApply').click();
 await expect(page.locator('#cropApply')).not.toBeVisible();
 if(automatic){
  await expect(page.locator('#automaticDialog')).toBeVisible();
  await page.locator('#automaticDetailed').click();
 }
 await expect(page.locator('#recover')).toBeEnabled();
 await expect(page.locator('#history button').first()).toBeAttached();
 await page.locator('.steps [data-tab="edit"]').click();
 await page.locator('#matrixView').click();
 const before=await page.locator('#canvas').evaluate(canvas=>canvas.toDataURL());
 await page.locator('#codewordOverlay').check();
 await expect.poll(()=>page.locator('#canvas').evaluate(canvas=>canvas.toDataURL())).not.toBe(before);
 expect(errors).toEqual([]);
});
