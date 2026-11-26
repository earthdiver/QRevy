import {test,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';

// Place the clean fixture symbol on a white canvas of the given side, in the page's own encoder.
async function paddedFixture(page,side){
 const fixture=readFileSync('tests/fixtures/clean.png').toString('base64');
 const url=await page.evaluate(async({fixture,side})=>{
  const img=new Image();img.src='data:image/png;base64,'+fixture;await img.decode();
  const c=document.createElement('canvas');c.width=c.height=side;const cx=c.getContext('2d');cx.fillStyle='#fff';cx.fillRect(0,0,side,side);
  const s=side>=2000?4:1;cx.imageSmoothingEnabled=false;cx.drawImage(img,(side-img.width*s)/2,(side-img.height*s)/2,img.width*s,img.height*s);
  return c.toDataURL('image/png');
 },{fixture,side});
 return {name:`padded-${side}.png`,mimeType:'image/png',buffer:Buffer.from(url.split(',')[1],'base64')};
}
async function cropValues(page){return Promise.all(['cropX','cropY','cropW','cropH'].map(async id=>Number(await page.locator('#'+id).inputValue())));}

test.beforeEach(async({page})=>{
 await page.addInitScript(()=>localStorage.setItem('qrevy-automatic-repair','false'));
 await page.goto('/');
 await expect(page.locator('#imageImport')).toBeEnabled();
});

test('crop starts around the symbol and Select all still selects the whole image',async({page})=>{
 await page.locator('#imageFile').setInputFiles(await paddedFixture(page,900));
 await expect(page.locator('#cropApply')).toBeEnabled();
 const [x,y,w,h]=await cropValues(page);
 // The 232-pixel fixture is centered at 334..566; the crop keeps a quiet zone without the full margin.
 expect(x).toBeGreaterThan(200);expect(x).toBeLessThan(334);
 expect(y).toBeGreaterThan(200);expect(y).toBeLessThan(334);
 expect(x+w).toBeGreaterThan(566);expect(x+w).toBeLessThan(700);
 expect(y+h).toBeGreaterThan(566);expect(y+h).toBeLessThan(700);
 await page.locator('#cropAll').click();
 expect(await cropValues(page)).toEqual([0,0,900,900]);
});

test('an image filled by the symbol starts with the whole image selected',async({page})=>{
 await page.locator('#imageFile').setInputFiles('tests/fixtures/clean.png');
 await expect(page.locator('#cropApply')).toBeEnabled();
 expect(await cropValues(page)).toEqual([0,0,232,232]);
});

test('added margin does not lower the analysis resolution of the initial crop',async({page})=>{
 const widths=[];
 // Both sources stay within every device's decoding limit; only the white margin differs.
 for(const side of [2000,2600]){
  await page.locator('#imageFile').setInputFiles(await paddedFixture(page,side));
  await expect(page.locator('#cropApply')).toBeEnabled();
  await page.locator('#cropApply').click();
  await expect(page.locator('#cropApply')).not.toBeVisible();
  await expect(page.locator('#canvas')).toHaveAttribute('data-image-width',/\d+/);
  widths.push(Number(await page.locator('#canvas').getAttribute('data-image-width')));
  await expect(page.locator('#recover')).toBeEnabled({timeout:60000});
 }
 // The 928-pixel symbol and its quiet zone fit the analysis size, so neither crop is reduced.
 expect(widths[0]).toBeLessThan(1800);
 expect(Math.abs(widths[0]-widths[1])).toBeLessThanOrEqual(widths[0]*.01);
});

test('all browsers decode large images at most 2600 pixels on the long side',async({page})=>{
 await page.locator('#imageFile').setInputFiles(await paddedFixture(page,3600));
 await expect(page.locator('#cropApply')).toBeEnabled();
 await page.locator('#cropAll').click();
 expect((await cropValues(page))[2]).toBe(1800);
});

test('fractional crop bounds and output dimensions match Android rounding',async({page})=>{
 const layouts=await page.evaluate(async()=>{
  const {analysisCropLayout}=await import('/image-tools.js');
  return [analysisCropLayout(2400,1597,[0,0,2400,1597],[0,0,2400,1597]),
   analysisCropLayout(2400,1597,[10.8,20.2,100.4,200.9],[0,0,2400,1597])];
 });
 expect(layouts[0]).toEqual({left:0,top:0,width:2400,height:1597,outputWidth:1800,outputHeight:1198});
 expect(layouts[1]).toEqual({left:10,top:20,width:101,height:201,outputWidth:76,outputHeight:151});
 const url=await page.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=2400;canvas.height=1597;const context=canvas.getContext('2d');context.fillStyle='#fff';context.fillRect(0,0,2400,1597);return canvas.toDataURL('image/png');});
 await page.locator('#imageFile').setInputFiles({name:'crop-rounding.png',mimeType:'image/png',buffer:Buffer.from(url.split(',')[1],'base64')});
 await page.locator('#cropApply').click();await expect(page.locator('#canvas')).toHaveAttribute('data-image-width','1800');
 await expect(page.locator('#canvas')).toHaveAttribute('data-image-height','1198');
});
