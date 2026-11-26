import {test,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
import {openAppMenu,clickWorkspaceAction,openImageAdjustments} from './input-helpers.js';

async function idle(page){await expect(page.locator('#progress')).toBeHidden();}
async function snapshot(page){
 await idle(page);
 return page.evaluate(async()=>{const {listSessions}=await import('/storage.js');const sessions=await listSessions();sessions.sort((left,right)=>right.updated-left.updated);const session=sessions[0];return session?.history[session.cursor];});
}
async function uniformImage(page){
 await page.addInitScript(()=>{localStorage.setItem('qrevy-automatic-repair','false');localStorage.setItem('qrevy-language','ja');});
 await page.goto('/');
 const url=await page.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=canvas.height=640;const context=canvas.getContext('2d');context.fillStyle='#808080';context.fillRect(0,0,640,640);return canvas.toDataURL();});
 await page.locator('#imageFile').setInputFiles({name:'uniform.png',mimeType:'image/png',buffer:Buffer.from(url.split(',')[1],'base64')});
 await page.locator('#cropAll').click();await page.locator('#cropApply').click();
 await expect.poll(async()=>!!(await snapshot(page))?.settings.grid).toBe(true);
 await page.locator('#imageView').click();await page.locator('#fitView').click();
}
async function tap(page,point){
 const canvas=page.locator('#canvas'),bounds=await canvas.boundingBox();
 await canvas.click({position:{x:point[0]*bounds.width/640,y:point[1]*bounds.height/640}});
}
async function assign(page,role,point,accepted){
 await page.locator('#toast').evaluate(element=>element.textContent='');
 await page.locator('#finderRole').selectOption(String(role));await tap(page,point);
 await expect(page.locator('#toast')).toContainText(accepted?'役割だけを指定しました':'四隅を調整してから役割を指定してください');
 await idle(page);
}
async function reopen(page){
 await page.reload();await openAppMenu(page);await page.locator('#sessionsButton').click();
 await page.locator('#sessionList .session-item button').first().click();
 await expect(page.locator('#sessionsDialog')).toBeHidden();await page.locator('#imageView').click();
}

test('projective Finder role assignment preserves the physical corners and logical orientation',async({page})=>{
 await uniformImage(page);
 const target=[20,20,620,20,120,620,20,620],canvas=page.locator('#canvas');
 for(const corner of [0,1,2,3]){
  const current=await snapshot(page),bounds=await canvas.boundingBox();
  const position=point=>[bounds.x+point[0]*bounds.width/640,bounds.y+point[1]*bounds.height/640];
  await page.mouse.move(...position(current.settings.quad.slice(corner*2,corner*2+2)));await page.mouse.down();
  await page.mouse.move(...position(target.slice(corner*2,corner*2+2)),{steps:5});await page.mouse.up();
  await expect.poll(async()=>Math.abs((await snapshot(page)).settings.quad[corner*2]-target[corner*2])).toBeLessThan(640/bounds.width+.1);
 }
 const adjusted=(await snapshot(page)).settings;
 for(const role of [0,1,3,0]){
  const point=await page.evaluate(async({settings,role})=>(await import('/image-tools.js')).moduleMap(settings)(role===1?17:3,role===3?17:3),{settings:adjusted,role});
  await assign(page,role,point,true);
  await expect.poll(async()=>(await snapshot(page)).settings.visibleFinderRole).toBe(role);
  const assigned=(await snapshot(page)).settings;
  expect(assigned.quad).toEqual(adjusted.quad);expect(assigned.finders).toEqual([]);expect(assigned.assigned).toBe(true);
  await openImageAdjustments(page);await page.locator('#clearVisibleFinder').click();await idle(page);
 }
});

test('undo redo and reopening preserve whether corners were adjusted',async({page})=>{
 await uniformImage(page);const original=(await snapshot(page)).settings;
 expect(original.gridReference).toBe(false);
 await assign(page,0,[140.8,140.8],false);
 await tap(page,original.quad.slice(0,2));await page.locator('#canvas').press('ArrowRight');
 await expect.poll(async()=>(await snapshot(page)).settings.gridReference).toBe(true);
 await page.locator('.steps [data-tab="edit"]').click();await page.locator('#undo').click();
 await expect.poll(async()=>(await snapshot(page)).settings.gridReference).toBe(false);
 expect((await snapshot(page)).settings.quad).toEqual(original.quad);
 await page.locator('.steps [data-tab="input"]').click();await page.locator('#imageView').click();
 await assign(page,0,[140.8,140.8],false);
 await page.locator('.steps [data-tab="edit"]').click();await page.locator('#redo').click();
 await expect.poll(async()=>(await snapshot(page)).settings.gridReference).toBe(true);
 await reopen(page);await assign(page,0,[140.8,140.8],true);
});

test('unadjusted outlines remain unassigned after reopening and workspace export',async({page})=>{
 await uniformImage(page);await reopen(page);await assign(page,0,[140.8,140.8],false);
 await page.evaluate(()=>navigator.canShare=()=>false);
 const download=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportSession');
 const file=await download;
 if(await page.locator('#workMenu').isVisible())await page.locator('#closeWorkMenu').click();
 await page.locator('#sessionFile').setInputFiles({name:'outline.qrpwa',mimeType:'application/json',buffer:await readFile(await file.path())});
 await expect(page.locator('#toast')).toContainText('作業を再開しました');
 await page.locator('#imageView').click();await assign(page,0,[140.8,140.8],false);
 expect((await snapshot(page)).settings.gridReference).toBe(false);
});
