import {test,expect} from '@playwright/test';

test.use({serviceWorkers:'block'});
test.beforeEach(async({page})=>{
 await page.addInitScript(()=>{
  const OriginalWorker=Worker;
  window.Worker=class extends OriginalWorker {
   constructor(...args){super(...args);this.addEventListener('message',e=>{if(e.data.result?.ordinary)window.ordinaryReader=e.data.result.ordinary.reader;});}
  };
 });
});
for(const automatic of [false,true])test(`ordinary reading precedes repair with automatic repair ${automatic}`,async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(automatic=>{
  localStorage.setItem('qrevy-automatic-repair',String(automatic));
  Object.defineProperty(navigator,'clipboard',{value:{writeText:async text=>{window.copiedOrdinary=text;}}});
 },automatic);
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();
 await expect(page.locator('#ordinaryDialog')).toBeVisible();await expect(page.locator('#ordinaryText')).toHaveText('QRevy repaired.');
 await expect.poll(()=>page.evaluate(()=>window.ordinaryReader)).toBe('ZXing-C++');
 await expect(page.locator('#automaticDialog')).not.toBeVisible();await expect(page.locator('#resultCount')).toHaveText('0');
 await page.locator('#ordinaryCopy').click();await expect.poll(()=>page.evaluate(()=>window.copiedOrdinary)).toBe('QRevy repaired.');
 await page.locator('#ordinaryClose').click();await page.locator('#reopenOrdinary').click();await expect(page.locator('#ordinaryText')).toHaveText('QRevy repaired.');
 await page.locator('#ordinaryDetailed').click();await expect(page.locator('#recover')).toBeEnabled();
 expect(errors).toEqual([]);
});

test('an unreadable synthetic image continues into automatic repair',async({page})=>{
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/ordinary-failure.png');await page.locator('#cropApply').click();
 await expect(page.locator('#automaticDialog')).toBeVisible();await expect(page.locator('#ordinaryDialog')).not.toBeVisible();
 await expect(page.locator('#automaticMessage')).toContainText('QRevy repaired.');
});

test('Java ordinary reading remains available if native initialization fails',async({page})=>{
 await page.route('**/native/boundary.wasm',r=>r.abort());
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();
 await expect(page.locator('#ordinaryDialog')).toBeVisible();await expect(page.locator('#ordinaryText')).toHaveText('QRevy repaired.');
 await expect.poll(()=>page.evaluate(()=>window.ordinaryReader)).toBe('ZXing Java');
});
