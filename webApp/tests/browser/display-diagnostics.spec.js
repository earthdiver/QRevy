import {changeRecoverySetting,runRecovery,showDiagnostics} from './input-helpers.js';
import {test,expect} from '@playwright/test';
test('search termination appears once with collapsed and expanded diagnostics',async({page})=>{
 await page.addInitScript(()=>{localStorage.setItem('qrevy-language','en');localStorage.setItem('qrevy-automatic-repair','true');});
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();
 await page.locator('#textFile').setInputFiles('tests/fixtures/priority-after.txt');
 await expect(page.locator('#automaticDialog')).toBeVisible({timeout:60000});await page.locator('#automaticClose').click();await showDiagnostics(page);
 await page.locator('#executionReport > summary').click();
 const sentence='Search completed within the specified conditions and search policy.';await expect(page.locator('#resultStatus')).toContainText(sentence);
 const count=async()=>((await page.locator('#resultStatus').innerText())+'\n'+await page.locator('#diagnostic').innerText()).split(sentence).length-1;
 expect(await count()).toBe(1);
 for(let i=0;i<30;i++){const closed=page.locator('#diagnostic button[aria-expanded="false"]:visible');if(!await closed.count())break;await closed.first().click();}
 expect(await count()).toBe(1);
 await page.locator('[data-tab="input"]').click();await changeRecoverySetting(page,'#utf8','check');await page.locator('[data-tab="results"]').click();await showDiagnostics(page);
 const warning='This is the previous repair result. Your current draft changes have not been applied.';
 await expect(page.locator('#resultStatus')).toContainText(warning);expect(await count()).toBe(1);
 await runRecovery(page);await expect(page.locator('#recover')).toBeEnabled();
 await expect(page.locator('#resultStatus')).not.toContainText(warning);await expect(page.locator('#resultStatus')).toContainText(sentence);expect(await count()).toBe(1);
});
test('crop before preview explicitly applies the adjusted image in both languages',async({page})=>{
 await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');
 await expect(page.locator('#cropApply')).toHaveText('クロップして解析');
 await page.locator('#cropContrastNumber').fill('20');await page.locator('#cropContrastNumber').blur();await page.locator('#cropBefore').check();
 await expect(page.locator('#cropApply')).toHaveText('調整後で解析');await page.locator('#cropBefore').uncheck();await expect(page.locator('#cropApply')).toHaveText('クロップして解析');
 await page.locator('#cropCancel').click();await page.locator('#languageButton').click();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropBefore').check();await expect(page.locator('#cropApply')).toHaveText('Analyze adjusted image');await page.locator('#cropCancel').click();
});
