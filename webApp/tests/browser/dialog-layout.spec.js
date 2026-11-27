import {continueAfterOrdinaryReading} from './input-helpers.js';
import {test,expect} from '@playwright/test';
import {clickInputAction,openAppMenu,openWorkspaceMenu,changeRecoverySetting,runRecovery} from './input-helpers.js';

async function ready(page,language,appearance='classic',automatic=false){
 await page.addInitScript(({language,appearance,automatic})=>{localStorage.setItem('qrevy-language',language);localStorage.setItem('qrevy-appearance',appearance);localStorage.setItem('qrevy-automatic-repair',String(automatic));},{language,appearance,automatic});
 await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();
}
async function fits(dialog){
 await expect(dialog).toBeVisible();
 expect(await dialog.evaluate(d=>d.scrollWidth<=d.clientWidth+1)).toBe(true);
 const bounds=await dialog.boundingBox(),viewport=dialog.page().viewportSize();
 expect(bounds.x).toBeGreaterThanOrEqual(-1);expect(bounds.x+bounds.width).toBeLessThanOrEqual(viewport.width+1);
}
async function headingIsClear(dialog){
 await fits(dialog);const heading=dialog.locator('h2').first(),button=dialog.locator('button').first();
 await heading.scrollIntoViewIfNeeded();const h=await heading.boundingBox(),b=await button.boundingBox();
 // A positive gap also protects glyphs extending beyond the heading's line box.
 expect(Math.max(b.x-h.x-h.width,h.x-b.x-b.width,b.y-h.y-h.height,h.y-b.y-b.height)).toBeGreaterThanOrEqual(8);
 await expect(button).toBeVisible();
}
for(const language of ['ja','en']){
 test(`automatic result heading, payload and actions fit narrow and landscape screens in ${language}`,async({page},info)=>{
  const {execute}=await import('../../web/engine/js/engine.js'),payload='Layout text '.repeat(24)+'END';
  const matrix=JSON.parse(execute({action:'demo',text:payload,damage:false})).text;
  for(const appearance of ['classic','modern']){
   await ready(page,language,appearance,true);
   await page.locator('#textFile').setInputFiles({name:'layout.txt',mimeType:'text/plain',buffer:Buffer.from(matrix)});
   const dialog=page.locator('#automaticDialog');await expect(dialog).toBeVisible({timeout:60000});
   await expect(page.locator('#automaticMessage')).toContainText(payload);
   for(const [width,height] of [[320,568],[390,844],[640,360],[1280,800]]){
    await page.setViewportSize({width,height});await headingIsClear(dialog);
    expect(await page.locator('#automaticMessage').evaluate(e=>e.scrollWidth<=e.clientWidth+1)).toBe(true);
    await page.locator('#automaticDetailed').scrollIntoViewIfNeeded();await expect(page.locator('#automaticDetailed')).toBeInViewport();
    await page.locator('#automaticTitle').scrollIntoViewIfNeeded();
    if(width===320)await dialog.screenshot({path:info.outputPath(`automatic-${language}-${appearance}.png`)});
   }
   await page.locator('#automaticClose').click();await expect(dialog).not.toBeVisible();
  }
 });
 test(`workspace, help, candidate and image dialog headings remain separate from their actions in ${language}`,async({page})=>{
  await page.setViewportSize({width:320,height:568});await ready(page,language);
  await openAppMenu(page);await page.locator('#sessionsButton').click();await headingIsClear(page.locator('#sessionsDialog'));await page.locator('[data-close="sessionsDialog"]').click();
  await openWorkspaceMenu(page);await headingIsClear(page.locator('#workMenu'));await page.locator('#closeWorkMenu').click();
  await openAppMenu(page);await page.locator('#helpButton').click();await headingIsClear(page.locator('#helpDialog'));
  for(const [button,id] of [['versionButton','versionDialog'],['licensesButton','licensesDialog']]){
   await page.locator('#'+button).click();await headingIsClear(page.locator('#'+id));await page.locator(`[data-close="${id}"]`).click();
  }
  await page.locator('#appearanceButton').click();await headingIsClear(page.locator('#appearanceDialog'));await page.locator('[data-close="appearanceDialog"]').click();
  await page.locator('[data-close="helpDialog"]').click();
  await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await headingIsClear(page.locator('dialog.image-dialog'));await page.locator('#cropApply').click();await continueAfterOrdinaryReading(page);await expect(page.locator('#recover')).toBeEnabled();
  await page.locator('#fineCorners').click();await headingIsClear(page.locator('dialog.image-dialog'));await page.locator('#cornerCancel').click();
  await page.locator('#textFile').setInputFiles('tests/fixtures/two-candidates.txt');await expect(page.locator('#recover')).toBeEnabled();
  await changeRecoverySetting(page,'#ecc','selectOption','L');await changeRecoverySetting(page,'#mask','selectOption','0');await changeRecoverySetting(page,'#list','selectOption','0');await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('A');
  await page.locator('#browseCandidates').click();await headingIsClear(page.locator('#candidateDialog'));await page.locator('[data-close="candidateDialog"]').click();
 });
}
test('long workspace names wrap while their open and delete actions remain reachable',async({page})=>{
 await page.setViewportSize({width:320,height:568});await ready(page,'ja');await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();
 await expect.poll(()=>page.evaluate(async()=>{const {listSessions}=await import('/storage.js');return (await listSessions()).length;})).toBeGreaterThan(0);
 const name='workspace_'.repeat(19);
 const file=await page.evaluate(async name=>{const {listSessions,exportWorkspace}=await import('/storage.js');const session=(await listSessions())[0];session.name=name;delete session.nameKey;return (await exportWorkspace(session)).text();},name);
 await page.locator('#sessionFile').setInputFiles({name:'named.qrpwa',mimeType:'application/json',buffer:Buffer.from(file)});await expect(page.locator('#viewTitle')).toHaveText(name);expect(await page.locator('#viewTitle').evaluate(e=>{const r=e.getBoundingClientRect();return e.scrollWidth<=e.clientWidth+1&&r.right<=innerWidth+1;})).toBe(true);
 for(const language of ['ja','en']){
  if(await page.locator('html').getAttribute('lang')!==language)await page.locator('#languageButton').click();
  await openAppMenu(page);await page.locator('#sessionsButton').click();const dialog=page.locator('#sessionsDialog');await fits(dialog);
  const row=page.locator('.session-item').filter({hasText:name}),open=row.locator('button').first(),remove=row.locator('button').last();
  await expect(open).toContainText(name);expect(await open.evaluate(e=>e.scrollWidth<=e.clientWidth+1)).toBe(true);
  await remove.scrollIntoViewIfNeeded();await expect(remove).toBeInViewport();await open.click();await expect(dialog).not.toBeVisible();await expect(page.locator('#viewTitle')).toHaveText(name);expect(await page.locator('#viewTitle').evaluate(e=>{const r=e.getBoundingClientRect();return e.scrollWidth<=e.clientWidth+1&&r.right<=innerWidth+1;})).toBe(true);
 }
});

for(const language of ['ja','en'])test(`progress changes keep the stop action stationary and reachable in ${language}`,async({page})=>{
 await ready(page,language);
 // Isolate layout with representative status text; real cooperative cancellation
 // and candidate retention are covered by the recovery interaction tests.
 await page.evaluate(()=>{document.getElementById('progress').hidden=false;document.getElementById('progressTitle').hidden=false;window.stopClicks=0;document.getElementById('cancel').addEventListener('click',()=>window.stopClicks++);});
 let clicks=0;
 for(const [width,height] of [[320,568],[640,360],[1280,800]]){
  await page.setViewportSize({width,height});
  await page.locator('#progressText').evaluate((e,text)=>e.textContent=text,language==='ja'?'復元を準備中':'Preparing recovery');
  const stop=page.locator('#cancel'),initial=await stop.boundingBox();
  for(const message of ['Candidate progress '.repeat(12),Array(20).fill('見つかった候補: 123件 / Candidate count: 123').join('\n')]){
   await page.locator('#progressText').evaluate((e,text)=>e.textContent=text,message);
   const current=await stop.boundingBox();expect(Math.abs(current.x-initial.x)).toBeLessThanOrEqual(1);expect(Math.abs(current.y-initial.y)).toBeLessThanOrEqual(1);
   await expect(stop).toBeInViewport();await stop.click();expect(await page.evaluate(()=>window.stopClicks)).toBe(++clicks);
   expect(await page.locator('#progress').evaluate(e=>e.scrollWidth<=e.clientWidth+1)).toBe(true);
  }
 }
});
