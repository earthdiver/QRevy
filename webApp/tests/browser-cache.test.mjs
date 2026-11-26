import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,readFileSync,rmSync,writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {cacheAllowed,fingerprint,successfulReport,validate} from '../scripts/check-browser.mjs';

const report={stats:{expected:1280,unexpected:0,flaky:0,skipped:15},errors:[],
 config:{projects:['iphone-webkit','android-chromium','desktop-chromium','desktop-firefox','desktop-webkit'].map(name=>({name}))}};
function fixture(t) {
 const directory=mkdtempSync(join(tmpdir(),'qrevy-browser-cache-'));
 t.after(()=>rmSync(directory,{recursive:true,force:true}));
 let runs=0,key='initial',clock=1000000,result={status:0,report};
 return {directory,get runs(){return runs;},set key(value){key=value;},set clock(value){clock=value;},set result(value){result=value;},
  check:options=>validate({directory,key:()=>key,now:()=>clock,log:()=>{},run:()=>{runs++;return result;},...options})};
}
test('only identical, recent, complete successes can be reused',t=>{
 const f=fixture(t);assert.equal(f.check(),0);assert.equal(f.check(),0);assert.equal(f.runs,1);
 f.key='changed';f.check();assert.equal(f.runs,2);
 f.clock=999999;f.check();assert.equal(f.runs,3);
 f.clock=999999+12*60*60*1000;f.check();assert.equal(f.runs,4);
 f.check({reuse:false});assert.equal(f.runs,5);
});
test('failure, cancellation, flaky, empty, incomplete and malformed reports cannot publish success',t=>{
 const f=fixture(t);
 for(const result of [{status:9,report},{status:1},{status:0},
  {status:0,report:{...report,stats:{...report.stats,flaky:1}}},
  {status:0,report:{...report,stats:{...report.stats,expected:0}}},
  {status:0,report:{...report,errors:[{message:'error'}]}},
  {status:0,report:{...report,config:{projects:[]}}}]) {
  f.result=result;f.check({reuse:false});const before=f.runs;f.check();assert.equal(f.runs,before+1);
 }
 assert.equal(successfulReport(report),true);
});
test('a failed forced rerun invalidates the earlier success',t=>{
 const f=fixture(t);f.check();f.result={status:5};assert.equal(f.check({reuse:false}),5);
 assert.equal(f.check(),5);assert.equal(f.runs,3);
});
test('inputs changed during execution and corrupt receipts trigger fresh execution',t=>{
 const f=fixture(t);let version=0;
 f.check({key:()=>String(version++ )});f.check();assert.equal(f.runs,2);
 writeFileSync(join(f.directory,'success.json'),'invalid');f.check();assert.equal(f.runs,3);
});
test('a concurrent invocation cannot reuse or overwrite the owner receipt',t=>{
 const f=fixture(t);f.check();const receipt=readFileSync(join(f.directory,'success.json'),'utf8');
 mkdirSync(join(f.directory,'lock'));f.result={status:9};assert.equal(f.check(),9);assert.equal(f.runs,2);
 assert.equal(readFileSync(join(f.directory,'success.json'),'utf8'),receipt);
});
test('CI, filtered runs, overrides and unsupported hosts bypass caching',()=>{
 assert.equal(cacheAllowed([],{},'linux'),true);
 for(const env of [{CI:'true'},{QREVY_BROWSER_CACHE:'off'},{QREVY_QA_FONT:'/font'},
  {QREVY_WEBKIT_EXECUTABLE:'/browser'},{LD_PRELOAD:'/library'},{LD_LIBRARY_PATH:'/libraries'},{NODE_OPTIONS:'--inspect'}])
  assert.equal(cacheAllowed([],env,'linux'),false);
 for(const arg of ['--list','--project=desktop-chromium','workflow.spec.js','--workers=1'])assert.equal(cacheAllowed([arg],{},'linux'),false);
 assert.equal(cacheAllowed([],{},'darwin'),false);
});
test('input bytes, file additions/deletions and runtime context invalidate fingerprints',t=>{
 const f=fixture(t),input=join(f.directory,'inputs');mkdirSync(input);
 const file=join(input,'fixture');writeFileSync(file,'first');const initial=fingerprint([input],{runtime:'one'});
 assert.equal(fingerprint([input],{runtime:'one'}),initial);
 writeFileSync(file,'other');assert.notEqual(fingerprint([input],{runtime:'one'}),initial);
 writeFileSync(file,'first');writeFileSync(join(input,'added'),'new');assert.notEqual(fingerprint([input],{runtime:'one'}),initial);
 rmSync(join(input,'added'));assert.equal(fingerprint([input],{runtime:'one'}),initial);
 assert.notEqual(fingerprint([input],{runtime:'two'}),initial);
 rmSync(file);assert.notEqual(fingerprint([input],{runtime:'one'}),initial);
});
