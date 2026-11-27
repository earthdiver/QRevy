import {continueAfterOrdinaryReading} from './input-helpers.js';
import {test,expect} from '@playwright/test';
import {clickInputAction,changeRecoverySetting,runRecovery} from './input-helpers.js';

test.beforeEach(async({page})=>{
 await page.addInitScript(()=>{localStorage.setItem('qrevy-language','ja');localStorage.setItem('qrevy-automatic-repair','false');});
});
async function expectView(page,after,unmasked,partial){
 await expect(page.locator('#dataStage')).toHaveValue(after?'after':'before');
 await expect(page.locator('#dataUnmask')).toBeChecked({checked:unmasked});
 await expect(page.locator('#dataPartial')).toBeChecked({checked:partial});
}
async function showData(page){
 if(!await page.locator('#resultDataPanel').evaluate(el=>el.open))await page.locator('#resultDataPanel > summary').click();
 await expect(page.locator('#hex')).not.toBeEmpty();
}
test('successful data starts repaired and unmasked, preserves display choices, and resets on a new recovery',async({page})=>{
 await page.goto('/');await clickInputAction(page,'#demo');await runRecovery(page);
 await expect(page.locator('#payload')).toHaveValue('QRevy · Repair with evidence');
 await showData(page);await expectView(page,true,true,false);
 await expect(page.locator('#hex')).toContainText('制約・RS修復後 / マスク解除後');
 await page.locator('#dataStage').selectOption('before');await page.locator('#dataUnmask').uncheck();await page.locator('#dataPartial').check();
 await page.locator('[data-tab="edit"]').click();await page.locator('[data-tab="results"]').click();await showData(page);
 await expectView(page,false,false,true);
 await page.locator('#languageButton').click();await expect(page.locator('#hex')).toContainText('Before unmasking');await expectView(page,false,false,true);
 await runRecovery(page);await expect(page.locator('#recover')).toBeEnabled();await showData(page);await expectView(page,true,true,false);
});
test('failed data starts as unmasked partial input and a later success restores repaired data defaults',async({page})=>{
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();
 await page.locator('#textFile').setInputFiles('tests/fixtures/tail-alternatives.txt');
 await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');
 await runRecovery(page);await expect(page.locator('#resultCount')).toHaveText('0');await expect(page.locator('#recover')).toBeEnabled();
 await showData(page);await expectView(page,false,true,true);
 await expect(page.locator('#hex')).toContainText('制約・RS修復前 / マスク解除後');
 await expect(page.locator('#hex')).toContainText('部分情報');
 await page.locator('#dataPartial').uncheck();await page.locator('#dataUnmask').uncheck();await page.locator('#dataStage').selectOption('after');
 await page.locator('#languageButton').click();await expect(page.locator('#hex')).toContainText('Before unmasking');await expectView(page,true,false,false);
 await runRecovery(page);await expect(page.locator('#recover')).toBeEnabled();await showData(page);await expectView(page,false,true,true);
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
 await showData(page);await expectView(page,true,true,false);
});
test('automatic image recovery uses the same successful data defaults',async({page})=>{
 await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','true'));
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/ordinary-failure.png');await page.locator('#cropApply').click();
 await expect(page.locator('#automaticDialog')).toBeVisible({timeout:60000});await page.locator('#automaticDetailed').click();
 await page.locator('[data-tab="results"]').click();await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
 await showData(page);await expectView(page,true,true,false);
});
