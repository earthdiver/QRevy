import {assignFinderOrientation} from './input-helpers.js';
import {openRecoverySettings,changeRecoverySetting,clickWorkspaceAction,openAppMenu,releaseFinderConstraints,runRecovery} from './input-helpers.js';
import {test,expect} from '@playwright/test';

// These scenarios exercise manual analysis; automatic imports have their own suite.
test.beforeEach(async({page})=>{await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));});
import {readFile} from 'node:fs/promises';
import {nativeStrings} from '../../web/native-strings.js';

// Capture rendered frames so expected guides can be checked independently of QR pixels.
async function observeCanvas(page){
  await page.addInitScript(()=>{
    window.parityFrames={};
    const proto=CanvasRenderingContext2D.prototype,paths=new WeakMap();
    for(const name of ['beginPath','moveTo','lineTo','arc','fill','stroke','strokeRect','fillRect','fillText']){
      const original=proto[name];
      proto[name]=function(...args){
        const id=this.canvas.id;
        if(id==='canvas'||id==='cornerCanvas'){
          if(name==='fillRect'&&args[0]===0&&args[1]===0&&args[2]===this.canvas.width&&args[3]===this.canvas.height)window.parityFrames[id]=[];
          if(name==='beginPath')paths.set(this,[]);
          if(name==='moveTo'||name==='lineTo')paths.get(this)?.push(args.slice(0,2));
          if(['arc','fill','stroke','strokeRect','fillText'].includes(name)){
            (window.parityFrames[id]??=[]).push({name,args,path:paths.get(this)?.map(p=>[...p])??[],fill:this.fillStyle,stroke:this.strokeStyle,dash:this.getLineDash()});
          }
        }
        return original.apply(this,args);
      };
    }
  });
}
async function photo(page){await page.goto('/');await expect(page.locator('#imageImport')).toBeEnabled();await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');await page.locator('#cropApply').click();await expect(page.locator('#recover')).toBeEnabled();}
async function settings(page){
  await page.evaluate(()=>navigator.canShare=()=>false);await openAppMenu(page);await page.locator('#workMenuButton').click();
  const event=page.waitForEvent('download');await clickWorkspaceAction(page,'#exportSession');
  const envelope=JSON.parse(await readFile(await(await event).path(),'utf8')),session=JSON.parse(envelope.payload);
  if(await page.locator('#workMenu').isVisible())await page.locator('#closeWorkMenu').click();
  return session.history[session.cursor].settings;
}
async function finder(page,role,x,y){
  await page.locator('#finderRole').selectOption(String(role));const c=page.locator('#canvas'),b=await c.boundingBox();
  await c.click({position:{x:x/232*b.width,y:y/232*b.height}});await expect(page.locator('#recover')).toBeEnabled();
}
async function guides(page,id='canvas'){
  return page.evaluate(id=>(window.parityFrames[id]||[]).filter(o=>o.name==='fill'&&o.path.length>=4&&o.fill.includes('57, 255, 136')) .map(o=>o.path),id);
}
function contains(paths,x,y){return paths.some(p=>x>=Math.min(...p.map(p=>p[0]))&&x<=Math.max(...p.map(p=>p[0]))&&y>=Math.min(...p.map(p=>p[1]))&&y<=Math.max(...p.map(p=>p[1])));}

test('image alignment guides show Finder timing and provisional orientation without changing samples',async({page},info)=>{
  await observeCanvas(page);await photo(page);const s=await settings(page);
  expect(s.photographicSource).toBe(true);
  const point=(x,y)=>s.grid[y*21+x];
  let shapes=await guides(page);
  for(const cell of [[0,0],[3,3],[20,0],[0,20],[8,6],[6,8],[8,13]])expect(contains(shapes,...point(...cell)),`guide at ${cell}`).toBe(true);
  expect(contains(shapes,...point(17,17))).toBe(false);
  await page.locator('#clearFinders').click();await expect.poll(async()=>contains(await guides(page),...point(17,17))).toBe(true);shapes=await guides(page);
  for(const cell of [[8,14],[14,8]])expect(contains(shapes,...point(...cell))).toBe(true);
  await expect(page.locator('#cellSummary')).toContainText('向き未確定');
  await page.screenshot({path:info.outputPath('provisional-alignment-guides.png')});
  await assignFinderOrientation(page);const confirmed=await settings(page);await page.locator('#fineCorners').click();await page.locator('#cornerZoom').fill('2');
  expect((await guides(page,'cornerCanvas')).length).toBeGreaterThan(20);
  await page.locator('#cornerRight').click();await page.locator('#cornerCancel').click();
  const after=await settings(page);expect(after.quad).toEqual(confirmed.quad);expect(after.grid).toEqual(confirmed.grid);
  await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});

test('image labels retain sampled and edited values with distinct tentative and fixed provenance',async({page},info)=>{
  await observeCanvas(page);await photo(page);
  const labels=()=>page.evaluate(()=>(window.parityFrames.canvas||[]).filter(o=>o.name==='fillText').map(o=>String(o.args[0])));
  expect(await labels()).toContain('0');expect(await labels()).toContain('1');
  await page.locator('[data-tab="edit"]').click();await page.locator('#imageView').click();const c=page.locator('#canvas'),b=await c.boundingBox();await c.click({position:{x:b.width*.5,y:b.height*.5}});
  await page.locator('[data-value="1"]').click();await page.locator('#editKind').selectOption('5');
  const marks=()=>page.evaluate(()=>(window.parityFrames.canvas||[]).filter(o=>['strokeRect','stroke'].includes(o.name)&&['#00ffff','rgb(0, 255, 255)'].includes(o.stroke)));
  await expect.poll(async()=>(await marks()).some(o=>o.dash.length>0)).toBe(true);
  await page.locator('#editKind').selectOption('1');await expect.poll(async()=>(await marks()).some(o=>o.dash.length===0)).toBe(true);
  await page.locator('[data-value="?"]').click();await expect.poll(labels).toContain('?');
  await page.locator('[data-value="*"]').click();await expect.poll(labels).toContain('*');
  await page.locator('#manualFocus').check();expect((await guides(page)).length).toBe(0);await expect.poll(async()=>(await marks()).some(o=>o.dash.length>0)).toBe(true);
  await page.screenshot({path:info.outputPath('manual-cell-provenance.png')});
  await page.locator('#matrixView').click();await expect.poll(async()=>(await marks()).some(o=>o.dash.length>0)).toBe(true);
  await page.locator('#restoreCell').click();await expect(page.locator('#editKind')).toHaveValue('0');
});

test('mirroring transposes Finder roles while preserving observed image centers',async({page})=>{
  await photo(page);for(const [role,x,y]of [[0,60,60],[1,172,60],[3,60,172]])await finder(page,role,x,y);
  const before=await settings(page);await page.locator('#mirror').click();const mirrored=await settings(page);
  const entries=s=>Array.from({length:s.finders.length/3},(_,i)=>s.finders.slice(i*3,i*3+3));
  expect(entries(mirrored).sort((a,b)=>a[0]-b[0])).toEqual(entries(before).map(([role,x,y])=>[role===1?3:role===3?1:role,x,y]).sort((a,b)=>a[0]-b[0]));
  await page.locator('#mirror').click();expect((await settings(page)).finders).toEqual(before.finders);
  await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});

test('clearing all Finder constraints returns to unassigned orientation like clearing the last role',async({page})=>{
  await photo(page);await finder(page,0,60,60);await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();
  await page.locator('#clearFinders').click();await expect(page.locator('#orientationNotice')).toBeVisible();
  const s=await settings(page);expect(s.finders).toEqual([]);expect(s.confirmedPolarity).toBe('UNKNOWN');
  await expect(page.locator('#recover')).toBeDisabled();
  await expect(page.locator('#orientationNotice')).toContainText('Finder');
});

test('a new Finder role cannot be assigned to the opposite physical corner after orientation is confirmed',async({page})=>{
  await photo(page);await finder(page,0,60,60);
  await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();await page.locator('#finderRole').selectOption('1');await page.locator('#removeFinder').click();await page.locator('.image-adjustment-body > details > summary').click();await page.locator('#imageSettings > summary').click();
  const before=await settings(page);
  await finder(page,1,60,172);const after=await settings(page);
  expect(after.finders).toEqual(before.finders);expect(after.quad).toEqual(before.quad);expect(after.assigned).toBe(true);
  await expect(page.locator('#finderRole')).toHaveValue('');
});

test('alignment references follow rotated mirrored higher-version and curved sampling geometry',async({page})=>{
  await observeCanvas(page);await photo(page);
  const check=async()=>{
    const s=await settings(page),n=17+4*s.version,shapes=await guides(page);
    for(const [x,y]of [[0,0],[3,3],[n-1,0],[0,n-1],[8,6],[6,8],[8,n-8]])expect(contains(shapes,...s.grid[y*n+x]),`V${s.version} guide ${x},${y}`).toBe(true);
  };
  await page.locator('#rotate').click();await check();await page.locator('#mirror').click();await check();
  page.once('dialog',d=>d.accept());await page.locator('#version').selectOption('7');await expect(page.locator('#recover')).toBeEnabled();await check();
  await photo(page);await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();
  await page.locator('#curved').check();await expect(page.locator('#curveChoice')).toBeVisible();await expect(page.locator('#recover')).toBeEnabled();await check();
  const count=await page.locator('#curveChoice option').count();await page.locator('#curveChoice').selectOption(String(count-1));await expect(page.locator('#recover')).toBeEnabled();await check();
});

test('crop numeric adjustments and resets preserve the chosen rotation and crop rectangle',async({page})=>{
  await page.goto('/');await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');
  await page.locator('#cropHelp > summary').click();for(const key of ['crop_help','image_adjustment_help'])await expect(page.locator(`#cropHelp [data-native-key="${key}"]`)).toHaveText(nativeStrings[key].ja);await page.locator('#cropHelp > summary').click();
  await page.locator('#cropRotate').click();await page.locator('#cropX').fill('8');await page.locator('#cropX').press('Tab');
  const rect=await Promise.all(['cropX','cropY','cropW','cropH'].map(id=>page.locator('#'+id).inputValue()));
  await page.locator('#cropBrightnessNumber').fill('23');await page.locator('#cropBrightnessNumber').press('Tab');await expect(page.locator('#cropBrightness')).toHaveValue('23');
  await page.locator('#cropContrastNumber').fill('-12');await page.locator('#cropContrastNumber').press('Tab');await expect(page.locator('#cropContrast')).toHaveValue('-12');
  await page.locator('#cropBefore').check();await page.locator('#cropInvert').check();await page.locator('#cropResetBrightness').click();await expect(page.locator('#cropBrightnessNumber')).toHaveValue('0');await expect(page.locator('#cropContrastNumber')).toHaveValue('-12');
  await page.locator('#cropResetContrast').click();await expect(page.locator('#cropContrastNumber')).toHaveValue('0');await expect(page.locator('#cropInvert')).toBeChecked();
  await page.locator('#cropBrightnessNumber').fill('14');await page.locator('#cropBrightnessNumber').press('Tab');await page.locator('#cropResetAdjustments').click();
  await expect(page.locator('#cropBrightnessNumber')).toHaveValue('0');await expect(page.locator('#cropContrastNumber')).toHaveValue('0');await expect(page.locator('#cropInvert')).not.toBeChecked();
  await expect(page.locator('#cropAngle')).toHaveValue('90');expect(await Promise.all(['cropX','cropY','cropW','cropH'].map(id=>page.locator('#'+id).inputValue()))).toEqual(rect);
  await page.locator('#cropBefore').uncheck();await page.locator('#cropAngle').fill('0');await page.locator('#cropAngle').press('Tab');await page.locator('#cropApply').click();await expect(page.locator('#recover')).toBeEnabled();
  await runRecovery(page);await expect(page.locator('#payload')).toHaveValue('QRevy repaired.');
});

test('native input explanations remain readable beside the associated controls in both languages',async({page})=>{
  await photo(page);
  const input=[['utf8Help','assume_utf8_note'],['partialHelp','partial_bit_search_limit_note'],['knownHelp','known_bit_note'],['listHelp','list_decoding_limit_note'],['mirrorHelp','mirrored_input_note']];
  const edit=[['cellEditHelp','cell_edit_help'],['cellSourceHelp','cell_source_legend']];
  for(const lang of ['ja','en']){
    if(lang==='en')await page.locator('#languageButton').click();
    await page.locator('[data-tab="input"]').click();
    if(!await page.locator('#imageSettings').evaluate(el=>el.open))await page.locator('#imageSettings > summary').click();
    if(!await page.locator('.image-adjustment-body > details').first().evaluate(el=>el.open))await page.locator('.image-adjustment-body > details > summary').first().click();
    await page.locator('#imageAdjustmentHelp > summary').click();for(const key of ['image_adjustment_help']){await expect(page.locator(`#imageAdjustmentHelp [data-native-key="${key}"]`)).toHaveText(nativeStrings[key][lang]);await expect(page.locator(`#imageAdjustmentHelp [data-native-key="${key}"]`)).toBeVisible();}await page.locator('#imageAdjustmentHelp > summary').click();
    await openRecoverySettings(page);
    for(const [id,key]of input){const section=page.locator('#'+id);await section.locator('summary').click();await expect(section.locator('[data-native-key]')).toHaveText(nativeStrings[key][lang]);await expect(section.locator('[data-native-key]')).toBeVisible();await section.locator('summary').click();await expect(section.locator('[data-native-key]')).toBeHidden();}
    await page.locator('#samplingOptions > summary').click();await expect(page.locator('#geometryHelp')).toHaveText(nativeStrings.geometry_search_note[lang]);await expect(page.locator('#geometryHelp')).toBeVisible();await page.locator('#samplingOptions > summary').click();
    await page.locator('[data-tab="edit"]').click();
    for(const [id,key]of edit){await expect(page.locator('#'+id)).toHaveText(nativeStrings[key][lang]);await expect(page.locator('#'+id)).toBeVisible();}
  }
});

test('corner drags keep the finger offset resample after cancellation and protect confirmed Finder corners',async({page})=>{
  await photo(page);const before=await settings(page),c=page.locator('#canvas');await c.scrollIntoViewIfNeeded();let b=await c.boundingBox();
  const sx=b.x+before.quad[4]/232*b.width+8,sy=b.y+before.quad[5]/232*b.height+8;
  await page.mouse.move(sx,sy);await page.mouse.down();await page.mouse.move(sx+10,sy,{steps:4});await page.mouse.up();await expect(page.locator('#recover')).toBeEnabled();
  const moved=await settings(page);expect(moved.quad[4]-before.quad[4]).toBeCloseTo(10*232/b.width,1);expect(moved.quad[5]).toBeCloseTo(before.quad[5],1);
  await c.scrollIntoViewIfNeeded();b=await c.boundingBox();const cx=b.x+moved.quad[4]/232*b.width,cy=b.y+moved.quad[5]/232*b.height;
  await page.mouse.move(cx,cy);await page.mouse.down();await page.mouse.move(cx-15,cy,{steps:4});await c.dispatchEvent('pointercancel',{pointerId:1,pointerType:'mouse'});await page.mouse.up();await expect(page.locator('#recover')).toBeEnabled();
  const cancelled=await settings(page);expect(cancelled.grid).toHaveLength(441);expect(cancelled.grid[440][0]).toBeCloseTo(cancelled.quad[4],3);expect(cancelled.grid[440][1]).toBeCloseTo(cancelled.quad[5],3);
  await photo(page);await finder(page,0,60,60);const locked=await settings(page);await c.scrollIntoViewIfNeeded();b=await c.boundingBox();const lx=b.x+locked.quad[0]/232*b.width,ly=b.y+locked.quad[1]/232*b.height;
  await page.mouse.move(lx,ly);await page.mouse.down();await page.mouse.move(lx+20,ly+20,{steps:4});await page.mouse.up();expect((await settings(page)).quad).toEqual(locked.quad);
  await page.locator('#fineCorners').click();await expect(page.locator('#cornerIndex option[value="0"]')).toBeDisabled();await page.locator('#cornerCancel').click();
});

test('editing summaries show manual-cell counts undo redo and priority combinations',async({page})=>{
  await photo(page);await expect(page.locator('#formatSearchCount')).toHaveText('探索対象: 32形式');await changeRecoverySetting(page,'#ecc','selectOption','L');await expect(page.locator('#formatSearchCount')).toHaveText('探索対象: 8形式');await changeRecoverySetting(page,'#mask','selectOption','0');await expect(page.locator('#formatSearchCount')).toHaveText('探索対象: 1形式');await changeRecoverySetting(page,'#ecc','selectOption','');await expect(page.locator('#formatSearchCount')).toHaveText('探索対象: 4形式');await changeRecoverySetting(page,'#mask','selectOption','-1');
  await page.locator('[data-tab="edit"]').click();await expect(page.locator('#editCounts')).toContainText('変更セル: 0セル');
  const c=page.locator('#canvas'),b=await c.boundingBox();await c.click({position:{x:b.width*.5,y:b.height*.5}});await page.locator('[data-value="*"]').click();
  await expect(page.locator('#editCounts')).toContainText('変更セル: 1セル');await expect(page.locator('#priorityCount')).toContainText('2通り');
  await page.locator('#undo').click();await expect(page.locator('#editCounts')).toContainText('変更セル: 0セル');await expect(page.locator('#editCounts')).toContainText('Redo可能: 1');await expect(page.locator('#priorityCount')).toContainText('1通り');
  await page.locator('#redo').click();await expect(page.locator('#editCounts')).toContainText('変更セル: 1セル');await expect(page.locator('#editCounts')).toContainText('Redo可能: 0');
  await page.locator('#languageButton').click();await expect(page.locator('#editCounts')).toContainText('Changed cells: 1');await expect(page.locator('#priorityCount')).toContainText('2 combinations');await page.locator('[data-tab="input"]').click();await expect(page.locator('#formatSearchCount')).toHaveText('Formats to search: 32');
});

test('automatically assigned Finder constraints show locked corners and retain their observed centers',async({page},info)=>{
  await observeCanvas(page);await photo(page);const initial=await settings(page),c=page.locator('#canvas');
  expect(initial.finders.filter((_,i)=>i%3===0).sort()).toEqual([0,1,3]);
  const colors=async s=>c.evaluate((canvas,s)=>[0,1,2,3].map(i=>Array.from(canvas.getContext('2d').getImageData(Math.round(s.quad[i*2]*canvas.width/Number(canvas.dataset.imageWidth)),Math.round(s.quad[i*2+1]*canvas.height/Number(canvas.dataset.imageHeight)),1,1).data).slice(0,3)),s);
  expect(await colors(initial)).toEqual([[0,200,83],[0,200,83],[255,140,0],[0,200,83]]);
  const circles=await page.evaluate(()=>(window.parityFrames.canvas||[]).filter(o=>o.name==='arc'&&o.stroke==='#ffff00'));
  for(let i=0;i<initial.finders.length;i+=3)expect(circles.some(o=>Math.abs(o.args[0]-initial.finders[i+1])<.01&&Math.abs(o.args[1]-initial.finders[i+2])<.01)).toBe(true);
  await page.screenshot({path:info.outputPath('assigned-finder-constraints.png')});
  await page.locator('#fineCorners').click();for(const role of [0,1,3])await expect(page.locator(`#cornerIndex option[value="${role}"]`)).toBeDisabled();await expect(page.locator('#cornerIndex option[value="2"]')).toBeEnabled();await page.locator('#cornerCancel').click();
  await page.locator('#imageSettings > summary').click();await page.locator('.image-adjustment-body > details > summary').click();await page.locator('#finderRole').selectOption('1');await page.locator('#removeFinder').click();
  const released=await settings(page);expect(released.finders.filter((_,i)=>i%3===0).sort()).toEqual([0,3]);expect((await colors(released))[1]).toEqual([255,140,0]);
  await page.locator('#detect').click();await expect(page.locator('#recover')).toBeEnabled();expect((await settings(page)).finders.filter((_,i)=>i%3===0).sort()).toEqual([0,1,3]);
  await page.locator('.image-adjustment-body > details > summary').click();await page.locator('#imageSettings > summary').click();await releaseFinderConstraints(page);const cleared=await settings(page);expect(cleared.finders).toEqual([]);expect(await colors(cleared)).toEqual(Array(4).fill([255,140,0]));
  await page.locator('#fineCorners').click();for(const role of [0,1,2,3])await expect(page.locator(`#cornerIndex option[value="${role}"]`)).toBeEnabled();await page.locator('#cornerIndex').selectOption('0');await page.locator('#cornerRight').click();await page.locator('#cornerApply').click();await expect(page.locator('#progress')).toBeHidden();await expect(page.locator('#recover')).toBeDisabled();expect((await settings(page)).quad).not.toEqual(cleared.quad);
});

test('switching a moved corner drag to two-finger zoom commits matching samples',async({page,context},info)=>{
  test.skip(info.project.use.browserName!=='chromium','CDP provides native multi-touch input');
  await page.setViewportSize({width:390,height:844});await photo(page);
  const before=await settings(page),c=page.locator('#canvas');await c.scrollIntoViewIfNeeded();
  const b=await c.boundingBox(),x=b.x+before.quad[4]/232*b.width,y=b.y+before.quad[5]/232*b.height,cdp=await context.newCDPSession(page);
  const first={x:x-15,y:y-15,id:1},second={x:x-70,y:y-15,id:2};
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y,id:1}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[first]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[first,second]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{...first,x:x-5},{...second,x:x-90}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await expect(page.locator('#recover')).toBeEnabled();
  const after=await settings(page);
  expect(after.quad[4]).toBeCloseTo(before.quad[4]-15*232/b.width,1);
  expect(after.quad[5]).toBeCloseTo(before.quad[5]-15*232/b.height,1);
  expect(after.grid).toHaveLength(441);
  expect(after.grid[440][0]).toBeCloseTo(after.quad[4],3);
  expect(after.grid[440][1]).toBeCloseTo(after.quad[5],3);
  expect(Number((await page.locator('#zoomLabel').textContent()).replace('%',''))).toBeGreaterThan(100);
  await page.screenshot({path:info.outputPath('corner-drag-to-pinch-resampled.png')});
});
