// Confirmed Finder roles lock their corners. Release them through the UI before
// scenarios that deliberately reposition every corner, then confirm orientation.
export async function releaseFinderConstraints(page){
 await page.locator('[data-tab="input"]').click();
 await page.locator('#clearFinders').click();
}

// Assign a real role through the same image interaction available to users.
export async function assignFinderOrientation(page){
 await page.locator('[data-tab="input"]').click();
 if(!await page.locator('#orientationNotice').isVisible())return;
 if(await page.locator('#matrixToImage').isVisible())await page.locator('#matrixToImage').click();
 await page.locator('#imageView').click();await page.locator('#fitView').click();
 await page.locator('#finderRole').selectOption('0');
 const c=page.locator('#canvas'),b=await c.boundingBox();
 await c.click({position:{x:b.width*.26,y:b.height*.26}});
 await page.locator('#orientationNotice').waitFor({state:'hidden'});
}

export async function openResultData(page){
 const section=page.locator('#resultDataPanel');
 if(!await section.evaluate(el=>el.open))await section.locator(':scope > summary').click();
}

export async function openAppMenu(page){
 if(await page.locator('#appMenuButton').getAttribute('aria-expanded')!=='true')await page.locator('#appMenuButton').click();
}

export async function openWorkspaceMenu(page){
 if(!await page.locator('#workMenu').isVisible()){
  await openAppMenu(page);
  await page.locator('#workMenuButton').click();
 }
}

export async function clickWorkspaceAction(page,selector){
 await openWorkspaceMenu(page);
 await page.locator(selector).click();
}

export async function openRecoverySettings(page){
 await page.locator('.steps [data-tab="input"]').click();
 if(!await page.locator('#repairDetails').evaluate(el=>el.open))
  await page.locator('#repairDetails > summary').click();
}

export async function changeRecoverySetting(page,selector,method,...args){
 await openRecoverySettings(page);
 await page.locator(selector)[method](...args);
}

export async function setAutomaticRepair(page,enabled){
 await page.locator('.steps [data-tab="input"]').click();
 if(!await page.locator('#inputChoices').evaluate(el=>el.open))
  await page.locator('#inputChoices > summary').click();
 await page.locator('#automaticRepair').setChecked(enabled);
}

export async function openInputChoices(page){
 await page.locator('#inputChoices').waitFor({state:'attached'});
 if(!await page.locator('[data-panel="input"]').isVisible())
  await page.locator('.steps [data-tab="input"]').click();
 if(!await page.locator('#inputChoices').evaluate(el=>el.open))
  await page.locator('#inputChoices > summary').click();
}

export async function clickInputAction(page,selector){
 await openInputChoices(page);
 await page.locator(selector).click();
}

export async function showDiagnostics(page){
 const section=page.locator('#resultDiagnosticSection');
 if(!await section.evaluate(el=>el.open))await section.locator(':scope > summary').click();
}

export async function changeEvidenceControl(page,selector,method,...args){
 const control=page.locator(selector),details=control.locator('xpath=ancestor::details');
 for(let i=0;i<await details.count();i++){
  const section=details.nth(i);
  if(!await section.evaluate(el=>el.open))await section.locator(':scope > summary').click();
 }
 await control[method](...args);
}

export async function runRecovery(page){
 if(!await page.locator('#recover').isVisible())
  await page.locator('.steps [data-tab="input"]').click();
 await page.locator('#recover').click();
}

export async function openImageAdjustments(page){
 await page.locator('.steps [data-tab="input"]').click();
 for(const selector of ['#imageSettings','.image-adjustment-body > details']){
  const section=page.locator(selector).first();
  if(!await section.evaluate(el=>el.open))await section.locator(':scope > summary').click();
 }
}
