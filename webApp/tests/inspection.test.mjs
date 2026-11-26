import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {execute} from '../web/engine/js/engine.js';
const run=request=>{const r=JSON.parse(execute(request));assert.equal(r.error,undefined);return r;};
test('unmasked display distinguishes function patterns from data for all QR versions',async()=>{
 const app=await readFile(new URL('../web/app.js',import.meta.url),'utf8');
 const definition=app.match(/^function isFunction\(.*$/m);
 assert.ok(definition,'display function classifier must be present');
 const isFunction=new Function(`${definition[0]}; return isFunction;`)();
 for(let version=1;version<=40;version++){
  const n=17+4*version;
  const matrix={text:Array(n).fill('?'.repeat(n)).join('\n'),sources:'0'.repeat(n*n)};
  const {kinds}=run({action:'regions',matrix,settings:{version}});
  for(let y=0;y<n;y++)for(let x=0;x<n;x++){
   const kind=kinds[y*n+x];
   const expected=kind!=='cell_region_codeword'&&kind!=='cell_region_remainder';
   assert.equal(isFunction(version,x,y),expected,`V${version} (${x},${y}): ${kind}`);
  }
 }
});
function fixture(language='ja'){const matrix=run({action:'demo',damage:true});const result=run({action:'recover',matrix,settings:{version:4,list:0},language});assert.ok(result.candidates.length);return {matrix,candidate:result.candidates[0]};}
test('native data inspector preserves unknown input and repaired bytes across masks',()=>{
 const {matrix,candidate}=fixture();const get=(after,unmasked,partial=false)=>run({action:'resultData',matrix,candidate,after,unmasked,partial}).text;
 const before=get(false,true),after=get(true,true);assert.match(before,/\?/);assert.match(after,/Binary/);assert.notEqual(before,after);assert.notEqual(after,get(true,false));assert.match(get(false,true,true),/部分情報/);assert.match(get(true,true,true),/データ検証済み/);
});
test('native candidate comparison and formatted diagnostics use the chosen language',()=>{
 const {candidate}=fixture('en');const r=run({action:'compare',candidate,other:candidate,firstNumber:1,secondNumber:2,language:'en'});assert.match(r.text,/Compare candidates 1 and 2/);assert.match(r.text,/identical/);
 const data=run({action:'resultData',candidate,after:true,language:'en'});assert.match(data.text,/After constraints\/RS/);
});
test('post-terminator inspector provides observed, conventional and recovered views',()=>{
 const {matrix,candidate}=fixture();const tail=run({action:'tail',matrix,candidate});assert.ok(tail.views.some(v=>v.label==='入力（補正前）'));assert.ok(tail.views.some(v=>v.label==='修復候補'));assert.ok(tail.views.every(v=>v.reports.every(r=>typeof r.text==='string'&&typeof r.tail==='string')));assert.match(tail.notice,/診断用/);
});
test('input partial mode and codeword overlay use native extraction',()=>{
 const {matrix}=fixture();const partial=run({action:'inputData',matrix,settings:{version:4},partial:true,unmasked:true});assert.match(partial.text,/部分情報/);const overlay=run({action:'overlay',matrix});assert.ok(Object.values(overlay.cells).some(c=>c.kind==='DATA'));assert.ok(Object.values(overlay.cells).some(c=>c.kind==='ECC'));
});
test('failed payload retains conventional RS tail alternatives without validating its body',async()=>{
 const matrix=run({action:'parse',text:await readFile(new URL('./fixtures/tail-alternatives.txt',import.meta.url),'utf8')});const result=run({action:'recover',matrix,settings:{version:1,list:0}});assert.equal(result.candidates.length,0);assert.equal(result.tailAvailable,true);assert.match(result.failure,/未検証の候補/);const tail=run({action:'tail',matrix});const conventional=tail.views.find(v=>v.label==='通常RSの訂正後');assert.ok(conventional);assert.ok(conventional.reports.length>1);assert.ok(conventional.reports.some(r=>r.text.includes('BR')));assert.ok(conventional.reports.some(r=>r.text.includes('A@')));
});
test('failed-result diagnostics relocalize without rerunning recovery',async()=>{
 const matrix=run({action:'parse',text:await readFile(new URL('./fixtures/tail-alternatives.txt',import.meta.url),'utf8')});const before=run({action:'recover',matrix,settings:{version:1,list:0},language:'ja'});const after=run({action:'localizeResult',language:'en'});assert.equal(after.candidates.length,before.candidates.length);assert.equal(after.failureFormat.mask,before.failureFormat.mask);assert.match(before.failure,/未検証の候補/);assert.match(after.failure,/unverified candidate/);assert.match(after.diagnostic,/Recovery result diagnostics/);
});
test('unchanged data and format cells retain related-group indexes',()=>{
 const {candidate}=fixture();assert.ok(candidate.codewordMap.some(n=>n>=0));assert.equal(candidate.formatCells['8,0'].formatRole,'PARITY');assert.ok(candidate.formatCells['8,0'].formatDetail.includes('コピー'));assert.ok(candidate.codewordMap.filter(n=>n>=0).length>500);
});

test('selected candidate diagnostics retain native sections and candidate numbering',async()=>{
 const matrix=run({action:'parse',text:await readFile(new URL('./fixtures/two-candidates.txt',import.meta.url),'utf8')});
 const result=run({action:'recover',matrix,settings:{version:1,list:0},language:'en'});
 assert.ok(result.candidates.length>=2);assert.equal(result.tailAvailable,false);assert.match(result.summary,/Candidates for the displayed reading have 2 distinct data codeword sequences\./);
 const selected=run({action:'diagnostic',candidate:result.candidates[1],language:'en'});
 assert.match(selected.diagnosticReport.scope,/candidate 2/);
 assert.deepEqual(selected.diagnosticReport.sections.map(s=>s.id),['SETTINGS','INPUT','PROCESSING','VERIFICATION']);
});
test('native retry fixture exposes a one-time single-segment repair without changing ordinary search',async()=>{
 const matrix=run({action:'parse',text:await readFile(new URL('./fixtures/retry-single.txt',import.meta.url),'utf8')});
 const settings={version:1,list:0,dataLimitMode:'MANUAL',dataLimit:1};
 const ordinary=run({action:'recover',matrix,settings});assert.equal(ordinary.candidates.length,0);assert.equal(ordinary.retryAvailable,true);assert.equal(ordinary.retryPromptEligible,true);
 const retry=run({action:'recover',matrix,settings:{...settings,single:true}});assert.ok(retry.candidates.some(c=>c.text==='qrevy-a'));assert.equal(retry.retryPromptEligible,false);
 const next=run({action:'recover',matrix,settings});assert.equal(next.candidates.length,0);assert.equal(next.retryAvailable,true);
});

test('synthetic text images never acquire photographic margins and QRR retains their source kind',()=>{
 const matrix=run({action:'demo',damage:false,text:'TEXT SOURCE'}),rows=matrix.text.split('\n'),n=rows.length,width=(n+8)*8;
 const pixels=new Uint32Array(width*width).fill(0xffffff);
 for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(rows[y][x]==='1')for(let py=0;py<8;py++)for(let px=0;px<8;px++)pixels[((y+4)*8+py)*width+(x+4)*8+px]=0;
 const last=35.5+(n-1)*8,base={version:(n-17)/4,quad:[35.5,35.5,last,35.5,last,last,35.5,last],assigned:true,automatic:false,black:64,white:192,quietZone:false,list:0};
 for(const photographicSource of [false,true]){
   const settings={...base,photographicSource};
   const sampled=run({action:'sample',width,height:width,pixels,settings}).matrix;
   assert.equal(sampled.text,matrix.text);
   assert.equal(sampled.margins.some(value=>value>=0),photographicSource);
   const recovered=run({action:'recoverImage',width,height:width,pixels,matrix:sampled,settings});
   const candidate=recovered.candidates.find(value=>value.text==='TEXT SOURCE');assert.ok(candidate);
   assert.equal(candidate.input.margins.some(value=>value>=0),photographicSource);
   const raw=run({action:'qrrEncode',id:'text-source',cursor:0,history:JSON.stringify([{label:'Image source kind',matrix:sampled,source:sampled,settings}])});
   assert.equal(run({action:'qrrDecode',...raw}).history[0].settings.photographicSource,photographicSource);
 }
});


test('data inspection defaults follow payload validation and explicit display choices remain available',async()=>{
 const {candidate}=fixture();
 assert.deepEqual(candidate.dataDisplay,{after:true,unmasked:true,partial:false});
 assert.match(run({action:'resultData',candidate,language:'en'}).text,/After constraints\/RS \/ After unmasking/);
 const matrix=run({action:'parse',text:await readFile(new URL('./fixtures/tail-alternatives.txt',import.meta.url),'utf8')});
 const failed=run({action:'recover',matrix,settings:{version:1,ecc:'L',mask:0,list:0},language:'en'});
 assert.equal(failed.candidates.length,0);
 assert.deepEqual(failed.failureFormat.dataDisplay,{after:false,unmasked:true,partial:true});
 const partial=run({action:'resultData',language:'en'}).text;
 assert.match(partial,/Before constraints\/RS \/ After unmasking/);assert.match(partial,/Partial information/);
 assert.match(run({action:'resultData',partial:false,language:'en'}).text,/Binary/);
});

test('region agreement separates format errors from data and ECC errors',()=>{
 const original=run({action:'demo',damage:false}),settings={version:4,list:0};
 const clean=run({action:'recover',matrix:original,settings}).candidates[0];assert.ok(clean);
 const bits=original.text.replace(/\n/g,'').split(''),n=original.text.split('\n').length;
 const format=Object.keys(clean.formatCells).map(key=>{const [x,y]=key.split(',').map(Number);return y*n+x;});
 const data=clean.codewordMap.indexOf(0),ecc=clean.codewordMap.indexOf(Math.max(...clean.codewordMap)),unknown=clean.codewordMap.indexOf(1);
 for(const i of [format[0],data,ecc])bits[i]=bits[i]==='1'?'0':'1';bits[format[1]]=bits[unknown]='?';
 const matrix={text:Array.from({length:n},(_,y)=>bits.slice(y*n,(y+1)*n).join('')).join('\n'),sources:'0'.repeat(n*n)};
 const result=run({action:'recover',matrix,settings:{...settings,ecc:clean.ecc,mask:clean.mask}}),c=result.candidates.find(c=>c.text===clean.text);assert.ok(c);
 assert.equal(c.formatMismatches,1);assert.equal(c.formatCompared,29);assert.equal(c.mismatches,2);assert.equal(c.compared,clean.compared-1);
 assert.equal(c.formatAgreement,'形式情報領域: 修復開始時の比較対象29bitとの不一致 1bit');assert.equal(c.dataAgreement,`データ・ECC領域: 修復開始時の比較対象${c.compared}bitとの不一致 2bit`);
 const localized=run({action:'localizeResult',language:'en'}).candidates.find(c=>c.text===clean.text);assert.match(localized.formatAgreement,/Format information: 1 mismatching bits out of 29 known bits/);assert.match(localized.dataAgreement,/Data\/ECC region: 2 mismatching bits/);
});
test('region agreement does not imply agreement when format bits are all unknown',()=>{
 const original=run({action:'demo',damage:false}),settings={version:4,list:0};const clean=run({action:'recover',matrix:original,settings}).candidates[0];assert.ok(clean);
 const bits=original.text.replace(/\n/g,'').split(''),n=original.text.split('\n').length;
 for(const key of Object.keys(clean.formatCells)){const [x,y]=key.split(',').map(Number);bits[y*n+x]='?';}
 const matrix={text:Array.from({length:n},(_,y)=>bits.slice(y*n,(y+1)*n).join('')).join('\n'),sources:'0'.repeat(n*n)};
 const c=run({action:'recover',matrix,settings:{...settings,ecc:clean.ecc,mask:clean.mask}}).candidates[0];assert.ok(c);assert.equal(c.formatCompared,0);assert.equal(c.formatAgreement,'形式情報領域: 修復開始時の比較対象0bit（一致を確認できません）');assert.equal(c.mismatches,0);assert.equal(c.compared,clean.compared);
});
