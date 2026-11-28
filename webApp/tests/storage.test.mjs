import test from 'node:test';
import assert from 'node:assert/strict';
import {validateSession,exportWorkspace,importWorkspace} from '../web/storage.js';
function workspace(){
 const matrix={text:Array.from({length:21},(_,y)=>y===0?'*'.repeat(15)+'?'.repeat(6):'?'.repeat(21)).join('\n'),sources:'0'.repeat(441)};
 const settings={version:1,priorityLimit:4,partial:12,list:1,known:0,ecc:'',mask:-1,black:96,white:160,formatLimitMode:'AUTOMATIC',dataLimitMode:'AUTOMATIC',formatLimit:6,dataLimit:20,polarity:'NORMAL'};
 return {image:null,id:'automatic-history',name:'Automatic',updated:1,cursor:0,history:[{matrix,source:structuredClone(matrix),settings,automaticScanAssumption:'UTF8',automaticScanListExtension:2,samplingInverted:false,automatic:{mode:'extended',firstRun:false,input:{matrix:structuredClone(matrix),settings:structuredClone(settings)},reports:[{mode:'initial',report:'time limit'},{mode:'extended',report:'completed'}]}}]};
}
test('workspace backup preserves automatic conditions and over-limit original input',async()=>{
 const value=workspace();value.history[0].settings.list=0;
 const restored=await importWorkspace(await exportWorkspace(value));
 assert.deepEqual(restored,value);
 assert.equal(restored.history[0].automatic.input.settings.list,1);
 assert.equal(restored.history[0].automaticScanListExtension,2);
});
test('workspace rejects invalid automatic execution or original input metadata',()=>{
 for(const corrupt of [h=>h.automaticScanAssumption='UNKNOWN',h=>h.automaticScanListExtension=3,h=>h.samplingInverted='false',h=>h.automatic.mode='again',h=>h.automatic.input.matrix.text='bad',h=>h.automatic.input.settings.version=2,h=>h.automatic.reports=[{mode:'initial',report:3}]]){
  const value=workspace();corrupt(value.history[0]);assert.throws(()=>validateSession(value));
 }
});
test('workspace preserves valid unmeasured roles and rejects invalid ones',async()=>{
 for(const role of [null,0,1,3]){const value=workspace();Object.assign(value.history[0].settings,{visibleFinderRole:role,assigned:true,quad:[10,10,210,10,210,210,10,210]});assert.deepEqual(await importWorkspace(await exportWorkspace(value)),value);}
 for(const role of [-1,2,4,'0',false,{},[]]){const value=workspace();value.history[0].settings.visibleFinderRole=role;assert.throws(()=>validateSession(value));}
 for(const assigned of [true,false]){const value=workspace();Object.assign(value.history[0].settings,{visibleFinderRole:0,assigned});assert.throws(()=>validateSession(value));}
});
test('pre-automatic workspaces remain readable',()=>{
 const value=workspace(),h=value.history[0];delete h.automatic;delete h.automaticScanAssumption;delete h.automaticScanListExtension;delete h.samplingInverted;
 assert.doesNotThrow(()=>validateSession(value));
});

test('workspace backups preserve grid reference state in history and checkpoints',async()=>{
 for(const gridReference of [false,true]){
  const value=workspace();value.history[0].settings.gridReference=gridReference;
  value.checkpoint=structuredClone(value.history[0]);
  assert.deepEqual(await importWorkspace(await exportWorkspace(value)),value);
 }
 for(const invalid of [0,1,'false',{},[]]){
  const value=workspace();value.history[0].settings.gridReference=invalid;
  assert.throws(()=>validateSession(value));
 }
});

test('workspace rejects HTML and invalid tone values while allowing legacy defaults',async()=>{
 for(const key of ['brightness','contrast'])for(const value of ['"><img src=x onerror=alert(1)>',NaN,Infinity,-101,101,{},[]]){
  const s=workspace();s.history[0].settings[key]=value;assert.throws(()=>validateSession(s));
 }
 for(const value of [-100,0,100]){const s=workspace();s.history[0].settings.brightness=value;s.history[0].settings.contrast=value;assert.deepEqual(await importWorkspace(await exportWorkspace(s)),s);}
});

test('source transforms survive backups and reject invalid geometry or mixed operations',async()=>{
 const s=workspace(),op={type:'source',angle:25,quad:[10,10,200,10,200,200,10,200],version:1,legacyAngle:null};
 s.history[0].settings.imageOps=[op];assert.deepEqual(await importWorkspace(await exportWorkspace(s)),s);
 for(const legacyRaster of ['pwa','android']){s.history[0].settings.imageOps=[{...op,legacyRaster,legacyAngle:10}];assert.deepEqual(await importWorkspace(await exportWorkspace(s)),s);}
 for(const bad of [{...op,angle:NaN},{...op,legacyAngle:361},{...op,quad:[1,2]},{...op,version:41},{...op,legacyRaster:'unknown'}]){
  s.history[0].settings.imageOps=[bad];assert.throws(()=>validateSession(s));
 }
 s.history[0].settings.imageOps=[op,{type:'rotate',angle:10}];assert.throws(()=>validateSession(s));
});

test('pre-analysis image state has a distinct validated schema',()=>{
 const regular=workspace();const value={...regular,kind:'image-import',settings:regular.history[0].settings,
  image:'data:image/png;base64,AA==',imageHash:'a'.repeat(64),history:[],cursor:-1,
  ordinary:{text:'\uFEFFComplete text',reader:'ZXing-C++',payloadBytes:[0,255]}};
 assert.doesNotThrow(()=>validateSession(value));
 for(const corrupt of [s=>s.image=null,s=>s.imageHash='bad',s=>s.cursor=0,s=>s.history=regular.history,
  s=>s.settings.version=0,s=>s.ordinary.text=42,s=>s.ordinary.payloadBytes=[256],s=>s.checkpoint=regular.history[0],s=>s.qrrRaw='raw']){
  const invalid=structuredClone(value);corrupt(invalid);assert.throws(()=>validateSession(invalid));
 }
 const invalid=workspace();invalid.history=[];invalid.cursor=-1;
 assert.throws(()=>validateSession(invalid));
});
