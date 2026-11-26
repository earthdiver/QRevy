import {clickInputAction,runRecovery,showDiagnostics} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});

// Synthetic UI load: retain the real engine's matrix/evidence, vary only test display text.
// This exercises navigation at volume, not candidate-validation correctness.
test.describe(() => {
 // Request-routing fixtures must bypass the offline cache.
 test.use({serviceWorkers:'block'});
test('hundreds of complete candidates do not push export and diagnostics down the page',async({page},testInfo)=>{
 await page.route('**/worker.js',route=>route.fulfill({contentType:'text/javascript',headers:{'Cross-Origin-Embedder-Policy':'require-corp'},body:`
 import {execute} from './engine/js/engine.js';let runs=0;
 self.onmessage=({data})=>{const result=JSON.parse(execute(data.request));if(data.request.action==='recover'&&result.candidates?.length){runs++;const base=result.candidates[0];result.candidates=Array.from({length:runs===1?401:2},(_,i)=>({...base,text:'Candidate '+String(i+1).padStart(3,'0')+' '+('complete payload '.repeat(120))+' END-'+(i+1)}));}self.postMessage({id:data.id,result});};self.postMessage({ready:true});
 `}));
 await page.goto('/');await clickInputAction(page,'#demo');await expect(page.locator('#recover')).toBeEnabled();await runRecovery(page);await expect(page.locator('#resultCount')).toHaveText('401');
 await expect(page.locator('#candidateDialog')).not.toBeVisible();await expect(page.locator('#copyPayload')).toBeVisible();await expect(page.locator('#exportPng')).toBeVisible();
 await page.locator('#browseCandidates').click();await expect(page.locator('#candidates .candidate')).toHaveCount(20);await expect(page.locator('#candidatePageStatus')).toHaveText('1–20 / 401');
 await page.locator('#candidateNext').click();await expect(page.locator('#candidatePageStatus')).toHaveText('21–40 / 401');
 await page.locator('#candidateSearch').fill('END-401');await expect(page.locator('#candidates .candidate')).toHaveCount(1);await expect(page.locator('#candidates .candidate')).toContainText('complete payload '.repeat(120));await page.locator('#candidates .candidate').click();await expect(page.locator('#candidateDialog')).not.toBeVisible();await expect(page.locator('#candidatePicker')).toHaveValue('400');await expect(page.locator('#payload')).toHaveValue(/END-401$/);
 await showDiagnostics(page);await expect(page.locator('#diagnostic')).toBeVisible();await expect(page.locator('#resultNavigation')).toHaveCount(0);await expect(page.locator('#payload')).toBeVisible();
 await page.evaluate(()=>{navigator.canShare=()=>false;Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async text=>window.copiedCandidate=text}});});await page.locator('#copyPayload').click();expect(await page.evaluate(()=>window.copiedCandidate)).toBe(await page.locator('#payload').inputValue());const download=page.waitForEvent('download');await page.locator('#exportPng').click();expect((await download).suggestedFilename()).toBe('qrevy-repaired.png');
 await page.screenshot({path:`test-results/${testInfo.project.name}-many-candidates.png`});
 const heightMany=await page.evaluate(()=>document.documentElement.scrollHeight);
 await runRecovery(page);await expect(page.locator('#resultCount')).toHaveText('2');const heightTwo=await page.evaluate(()=>document.documentElement.scrollHeight);
 expect(Math.abs(heightMany-heightTwo)).toBeLessThan(96);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
});
