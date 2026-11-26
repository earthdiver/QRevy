import {openAppMenu,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';

test.beforeEach(async({page})=>{
 await page.addInitScript(()=>{
  localStorage.setItem('qrevy-automatic-repair','false');localStorage.setItem('qrevy-language','en');
  window.exitCalls=0;window.close=()=>{window.exitCalls++;};
 });
});
async function sample(page){
 await page.goto('/');await expect(page.locator('#textImport')).toBeEnabled();
 await page.locator('#textFile').setInputFiles('tests/fixtures/priority-after.txt');
 await expect(page.locator('#recover')).toBeEnabled();
}
test('banner menu fits a narrow screen and supports keyboard and outside dismissal',async({page})=>{
 await page.setViewportSize({width:320,height:720});await page.goto('/');
 const header=page.locator('body>header'),button=page.locator('#appMenuButton'),menu=page.locator('#appMenuItems');
 await expect(button).toBeVisible();expect((await header.boundingBox()).height).toBeLessThanOrEqual(64);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);
 await expect(page.locator('#appNavigation')).toHaveCount(0);await expect(page.locator('#homeButton')).not.toBeVisible();await expect(page.locator('#languageButton')).toBeVisible();
 const before=await page.locator('main').boundingBox();await button.focus();await button.press('ArrowDown');
 await expect(page.locator('#homeButton')).toBeFocused();expect((await page.locator('main').boundingBox()).y).toBe(before.y);
 await page.keyboard.press('End');await expect(page.locator('#exitButton')).toBeFocused();await page.keyboard.press('Escape');await expect(menu).not.toBeVisible();await expect(button).toBeFocused();
 await openAppMenu(page);await page.mouse.click(4,4);await expect(menu).not.toBeVisible();
 await openAppMenu(page);await page.locator('#helpButton').click();await expect(menu).not.toBeVisible();await expect(page.locator('#helpDialog')).toBeVisible();
 await page.locator('[data-close="helpDialog"]').click();await page.locator('#languageButton').click();await expect(button).toHaveText('メニュー ▾');
});
test('Home remains reachable from results and preserves the current workspace',async({page})=>{
 await sample(page);await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
 await page.evaluate(()=>scrollTo(0,document.body.scrollHeight));
 const home=await page.locator('#appMenuButton').boundingBox();expect(home.y).toBeGreaterThanOrEqual(0);expect(home.y).toBeLessThan(100);
 await openAppMenu(page);await page.locator('#homeButton').click();await expect(page.locator('#inputChoices')).toHaveAttribute('open','');await expect(page.locator('#imageImport')).toBeInViewport();
 await expect(page.locator('.workspace>.viewer')).not.toBeVisible();await page.locator('#homeReturn').click();await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
 await openAppMenu(page);await page.locator('#homeButton').click();
 await page.locator('[data-tab="results"]').click();await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
 await page.locator('#resultHome').click();await expect(page.locator('#imageImport')).toBeInViewport();
 await page.locator('#openSavedWork').click();await expect(page.locator('#sessionsDialog')).toBeVisible();await expect(page.locator('#sessionList .session-item')).toHaveCount(1);
 await page.locator('[data-close="sessionsDialog"]').click();await page.locator('#languageButton').click();await expect(page.locator('#homeButton')).toHaveText('ホーム');await expect(page.locator('#exitButton')).toHaveText('終了');
});
test('Exit waits for saving, gives installed-app guidance, and can resume processing',async({page})=>{
 await page.addInitScript(()=>Object.defineProperty(navigator,'standalone',{value:true,configurable:true}));
 await sample(page);await openAppMenu(page);await page.locator('#exitButton').click();await expect(page.locator('#exitMessage')).toContainText('Your workspace has been saved.');
 await expect(page.locator('#exitMessage')).toContainText('home screen');expect(await page.evaluate(()=>window.exitCalls)).toBe(1);
 const saved=await page.evaluate(async()=>{const {listSessions}=await import('/storage.js');const rows=await listSessions();return rows.map(r=>({history:r.history.length,matrix:r.history[r.cursor].matrix.text}));});
 expect(saved).toHaveLength(1);expect(saved[0].history).toBeGreaterThan(0);expect(saved[0].matrix.split('\n')).toHaveLength(21);
 await page.locator('#exitBack').click();await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
 await openAppMenu(page);await page.locator('#exitButton').click();await expect(page.locator('#exitMessage')).toContainText('saved');await page.locator('#exitBack').click();
 await page.locator('#resultEvidenceTab').click();await expect(page.locator('#canvas')).toBeVisible();
});
test('Exit does not close or report success when saving fails',async({page})=>{
 await sample(page);
 await page.evaluate(()=>{window.originalPut=IDBObjectStore.prototype.put;IDBObjectStore.prototype.put=function(){throw new DOMException('Storage full','QuotaExceededError');};});
 await openAppMenu(page);await page.locator('#exitButton').click();await expect(page.locator('#exitMessage')).toContainText('could not be saved');expect(await page.evaluate(()=>window.exitCalls)).toBe(0);
 await page.evaluate(()=>{IDBObjectStore.prototype.put=window.originalPut;});await page.locator('#exitBack').click();
 await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});
