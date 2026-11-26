import test from 'node:test';import assert from 'node:assert/strict';import {readFile} from 'node:fs/promises';
import {execute} from '../web/engine/js/engine.js';
import {packQrr,unpackQrr} from '../web/qrr.js';
const run=r=>{const result=JSON.parse(execute(r));if(result.error)throw new Error(result.error);return result;};
const recover=(m,settings={})=>run({action:'recover',matrix:m,settings:{version:(m.text.split('\n').length-17)/4,list:0,...settings}});
test('first Finder search ignores the provisional grid pitch until orientation is assigned',()=>{
 const width=300,pixels=new Uint32Array(width*width).fill(0xffffff);
 for(let row=0;row<42;row++)for(let column=0;column<42;column++){
  const moduleX=Math.floor(column/6),moduleY=Math.floor(row/6);
  if(moduleX===0||moduleX===6||moduleY===0||moduleY===6||(moduleX>=2&&moduleX<=4&&moduleY>=2&&moduleY<=4))pixels[(row+60)*width+column+60]=0;
 }
 const request={action:'finder',width,height:width,pixels,settings:{version:1,quad:[30,30,270,30,270,270,30,270],assigned:true,automatic:false,black:96,white:160},x:80.5,y:80.5,alignGrid:true,role:0,gridAssigned:false};
 const found=run(request);assert.equal(found.confirmed,true);assert.ok(Math.hypot(found.x-80.5,found.y-80.5)<1);assert.equal(found.needsConfirmation,false);
 assert.equal(run({...request,gridAssigned:true}).confirmed,false);
});
test('QRR preserves and clears unmeasured Finder roles across re-export',()=>{
 const matrix=run({action:'demo',damage:false}),version=(matrix.text.split('\n').length-17)/4;
 for(const role of [0,1,3]){
  const settings={version,assigned:true,quad:[10,10,210,10,210,210,10,210],visibleFinderRole:role,finders:[1,100,20]};
  const raw=run({action:'qrrEncode',id:'unmeasured-role',history:JSON.stringify([{label:'Role',matrix,source:matrix,settings}]),cursor:0});
  const session=run({action:'qrrDecode',...raw});assert.equal(session.history[0].settings.visibleFinderRole,role);
  session.history[0].settings.visibleFinderRole=null;
  const updated=run({action:'qrrEncode',...session,history:JSON.stringify(session.history)});
  assert.equal(run({action:'qrrDecode',...updated}).history[0].settings.visibleFinderRole,null);
 }
});
for(const [text,encoding] of [['日本語のQR復元テスト','UTF-8'],['漢字東京','Shift_JIS'],['QRevy · proof','ISO-8859-1'],['中文测试','GB2312'],['中文𠮷😀','GB18030'],['HELLO WORLD','UTF-8'],['1234567890','UTF-8']])test(`damaged QR round trip: ${text} (${encoding})`,()=>{
 const clean=run({action:'demo',text,encoding,damage:false}),damaged=run({action:'demo',text,encoding,damage:true});
 const r=recover(damaged);assert.ok(r.candidates.some(c=>c.text===text));assert.equal(r.candidates.find(c=>c.text===text).matrix.text,clean.text);assert.ok(Object.keys(r.candidates[0].trace).length>0);
});
test('strict text parsing rejects malformed input',()=>{for(const text of ['','?'.repeat(21),'0'.repeat(21)+'\n','1'.repeat(21)+'\r'+Array(20).fill('1'.repeat(21)).join('\n')])assert.throws(()=>run({action:'parse',text}));});
test('hard manual pins reject a contradictory correction',()=>{
 const m=run({action:'demo',damage:false,text:'FIXED PIN'}),rows=m.text.split('\n'),n=rows.length;
 const x=n-1,y=n-1,i=y*n+x;rows[y]=rows[y].slice(0,x)+(rows[y][x]==='1'?'0':'1');m.text=rows.join('\n');m.sources=m.sources.slice(0,i)+'1'+m.sources.slice(i+1);
 assert.equal(recover(m).candidates.length,0);assert.ok(recover(m,{lockManual:false}).candidates.some(c=>c.text==='FIXED PIN'));
});
test('explicit ECC and mask are enforced',()=>{const m=run({action:'demo',damage:false}),r=recover(m),c=r.candidates[0];assert.ok(recover(m,{ecc:c.ecc,mask:c.mask}).candidates.length);assert.equal(recover(m,{ecc:c.ecc,mask:(c.mask+1)%8,formatLimitMode:'EXACT',dataLimitMode:'EXACT'}).candidates.length,0);});
for(const [name,text] of [['bullet','QRevy app: recovered QR.'],['priority','QRevy repaired.']])test(`upstream ${name} regression`,{timeout:180000},async()=>{
 const input=await readFile(new URL(`./fixtures/${name}-before.txt`,import.meta.url),'utf8'),expected=(await readFile(new URL(`./fixtures/${name}-after.txt`,import.meta.url),'utf8')).trim();
 const m=run({action:'parse',text:input});const r=recover(m,{single:true,list:1});const c=r.candidates.find(c=>c.text===text);assert.ok(c,JSON.stringify(r).slice(0,1000));assert.equal(c.matrix.text,expected);
});
test('QRR 42 round trip and checksum tampering',async()=>{
 const m=run({action:'demo',damage:false}),history=[{label:'Imported fixture',matrix:m,source:m,settings:{version:(m.text.split('\n').length-17)/4}}];
 const raw=run({action:'qrrEncode',id:'test-session',history:JSON.stringify(history),cursor:0});
 assert.equal(raw.version,42);
 const blob=await packQrr(raw),decoded=await unpackQrr(blob),session=run({action:'qrrDecode',...decoded});assert.equal(session.history[0].matrix.text,m.text);assert.equal(session.id,'test-session');
 const bytes=new Uint8Array(await blob.arrayBuffer());bytes[20]^=1;await assert.rejects(()=>unpackQrr(new Blob([bytes])));
});
test('QRR re-export retains edited color clusters and photographic source in new history',()=>{
 const matrix=run({action:'demo',damage:false}),version=(matrix.text.split('\n').length-17)/4;
 const initial={version,colorUnknown:false,colorClusters:[5000,1000,1000,1000],photographicSource:true};
 const raw=run({action:'qrrEncode',id:'color-settings',history:JSON.stringify([{label:'Original',matrix,source:matrix,settings:initial}]),cursor:0});
 const session=run({action:'qrrDecode',...raw});
 const changed=structuredClone(session.history[0]);
 Object.assign(changed.settings,{colorUnknown:true,colorClusters:[4000,500,500,500],photographicSource:false});
 changed.label='Changed color settings';session.history.push(changed);session.cursor=1;
 const encoded=run({action:'qrrEncode',...session,history:JSON.stringify(session.history)});
 const restored=run({action:'qrrDecode',...encoded});
 assert.equal(restored.cursor,1);
 for(const [index,expected] of [initial,changed.settings].entries()){
  const actual=restored.history[index].settings;
  assert.equal(actual.colorUnknown,expected.colorUnknown);
  assert.deepEqual(actual.colorClusters,expected.colorClusters);
  assert.equal(actual.photographicSource,expected.photographicSource);
 }
});
test('unlocked imported QRR retains neighborhood search history through re-export',async()=>{
 const decoded=await unpackQrr(new Blob([await readFile(new URL('./fixtures/unlocked-corners.qrr',import.meta.url))]));
 const session=run({action:'qrrDecode',...decoded});
 for(const neighborhood of [true,false]){
  const h=structuredClone(session.history.at(-1));h.settings.neighborhood=neighborhood;
  session.history.push(h);session.cursor=session.history.length-1;
 }
 const encoded=run({action:'qrrEncode',...session,history:JSON.stringify(session.history)});
 const restored=run({action:'qrrDecode',...await unpackQrr(await packQrr(encoded))});
 assert.equal(restored.cursor,session.cursor);
 assert.deepEqual(restored.history.map(h=>h.settings.neighborhood),session.history.map(h=>h.settings.neighborhood));
 assert.deepEqual(restored.history.map(h=>h.matrix),session.history.map(h=>h.matrix));
});
test('source image sampling uses the original geometry and thresholds',()=>{
 const m=run({action:'demo',damage:false,text:'SAMPLE'}),rows=m.text.split('\n'),n=rows.length,unit=6,w=(n+8)*unit,pixels=new Uint32Array(w*w).fill(0xffffff);
 for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(rows[y][x]==='1')for(let py=0;py<unit;py++)for(let px=0;px<unit;px++)pixels[((y+4)*unit+py)*w+(x+4)*unit+px]=0;
 const a=4*unit+(unit-1)/2,b=(n+3)*unit+(unit-1)/2;
 const r=run({action:'sample',width:w,height:w,pixels,settings:{version:(n-17)/4,quad:[a,a,b,a,b,b,a,b],automatic:true}});assert.equal(r.matrix.text,m.text);assert.equal(recover(r.matrix).candidates[0].text,'SAMPLE');
});
for(let version=23;version<=34;version++)test(`QRR schema ${version}: retain image-only snapshots without inventing observations`,async()=>{
 const chunks=[];const i32=n=>{const b=Buffer.alloc(4);b.writeInt32BE(n);chunks.push(b);};const i64=n=>{const b=Buffer.alloc(8);b.writeBigInt64BE(BigInt(n));chunks.push(b);};const utf=s=>{const b=Buffer.from(s);const n=Buffer.alloc(2);n.writeUInt16BE(b.length);chunks.push(n,b);};
 utf('image-only');i64(123);i32(1);i32(0);i32(1);utf('history');i64(123);i32(0);utf('Image only');utf('');chunks.push(Buffer.from([0,0,0]));i32(-1);chunks.push(Buffer.from(version>=27?[0,0,0,0]:[0,0,0]));
 const raw=Buffer.concat(chunks).toString('base64');const session=run({action:'qrrDecode',raw,version});assert.equal(session.history[0].imageOnly,true);assert.match(session.history[0].matrix.text,/^\?{21}\n/);
 const encoded=run({action:'qrrEncode',id:session.id,cursor:0,history:JSON.stringify(session.history),qrrRaw:raw,qrrVersion:version});const decoded=run({action:'qrrDecode',...encoded});assert.equal(decoded.history[0].imageOnly,true);
});

test('priority defaults to four, supports 4–14, preserves over-limit imports and blocks recovery',()=>{
 const source=run({action:'demo',text:'LIMIT',damage:false}),version=(source.text.split('\n').length-17)/4,settings={version,priorityLimit:4},allowed=run({action:'regions',matrix:source,settings}).allowed;
 const edit=(matrix,cells,settings)=>run({action:'edit',matrix,source,settings,cells,value:'*'});
 const four=edit(source,allowed.slice(0,4),settings);assert.equal((four.text.match(/\*/g)||[]).length,4);
 assert.throws(()=>edit(four,[allowed[4]],settings),/4/);assert.equal((four.text.match(/\*/g)||[]).length,4);
 const five=edit(four,[allowed[4]],{...settings,priorityLimit:5});assert.throws(()=>recover(five,{priorityLimit:4}),/4/);
 const history=[{label:'limit',matrix:five,source,settings:{...settings,priorityLimit:5}}];const encoded=run({action:'qrrEncode',id:'limit',history:JSON.stringify(history),cursor:0});assert.equal(run({action:'qrrDecode',...encoded}).history[0].settings.priorityLimit,5);
});
test('priority rejects unassigned orientation, remainder and mixed ranges atomically',()=>{
 const source=run({action:'demo',text:'A'.repeat(30),damage:false}),n=source.text.split('\n').length,settings={version:(n-17)/4},allowed=run({action:'regions',matrix:source,settings}).allowed;
 const edit=(cells,extra={})=>run({action:'edit',matrix:source,source,settings:{...settings,...extra},cells,value:'*'});
 assert.throws(()=>edit([allowed[0]],{assigned:false}));assert.throws(()=>edit([allowed[0],0]));
 // Version 2 remainder bits are outside the codeword coordinate list.
 if(n===25){assert.ok(!allowed.includes(13*n));assert.throws(()=>edit([13*n]));}
 assert.equal((source.text.match(/\*/g)||[]).length,0);
});
test('source switching retains values, unknowns stay tentative, restoring removes priority',()=>{
 const source=run({action:'demo',text:'SOURCE',damage:false}),n=source.text.split('\n').length,settings={version:(n-17)/4},i=n*n-1;
 const edit=(matrix,value,kind)=>run({action:'edit',matrix,source,settings,cells:[i],value,kind});
 const fixed=edit(source,'source','1');assert.equal(fixed.sources[i],'1');const soft=edit(fixed,'source','5');assert.equal(soft.sources[i],'5');assert.equal(soft.text,source.text);
 const unknown=edit(soft,'?');assert.equal(unknown.sources[i],'5');assert.throws(()=>edit(unknown,'source','1'));
 const priority=edit(unknown,'*');assert.equal(edit(priority,'source','0').text,source.text);
});
test('QRR preserves image tone, rotation, perspective, Finder points and priority settings',()=>{
 const m=run({action:'demo',damage:false}),version=(m.text.split('\n').length-17)/4,settings={version,quad:[10,10,200,10,200,200,10,200],curved:true,warp:[0,0,0,0,0.1,1,0,0,0,0.1,0,1,0,0,0.1],brightness:12,contrast:-20,toneInvert:true,priorityLimit:7,finders:[0,30,30],imageOps:[{type:'rotate',angle:1.2},{type:'rectify',quad:[10,10,200,10,200,200,10,200],version}]};
 const encoded=run({action:'qrrEncode',id:'settings',history:JSON.stringify([{label:'settings',matrix:m,source:m,settings}]),cursor:0}),s=run({action:'qrrDecode',...encoded}).history[0].settings;
 assert.equal(s.brightness,12);assert.equal(s.contrast,-20);assert.equal(s.toneInvert,true);assert.equal(s.priorityLimit,7);assert.equal(s.imageOps.length,1);assert.equal(s.imageOps[0].type,'source');assert.ok(Math.abs(s.imageOps[0].legacyAngle-1.2)<1e-6);assert.deepEqual(s.finders,[0,30,30]);assert.deepEqual(s.warp,settings.warp);
});
test('QRR re-export preserves a source-only edit and the one-time retry flag',()=>{
 const m=run({action:'demo',damage:false}),version=(m.text.split('\n').length-17)/4,settings={version},raw=run({action:'qrrEncode',id:'provenance',history:JSON.stringify([{label:'base',matrix:m,source:m,settings}]),cursor:0}),session=run({action:'qrrDecode',...raw});
 const h=session.history[0],i=m.sources.length-1;h.matrix=run({action:'edit',matrix:h.matrix,source:h.source,settings,cells:[i],value:'source',kind:'1'});h.oneTimeSingleSegment=true;
 const encoded=run({action:'qrrEncode',...session,history:JSON.stringify(session.history)}),restored=run({action:'qrrDecode',...encoded}).history[0];assert.equal(restored.matrix.sources[i],'1');assert.equal(restored.oneTimeSingleSegment,true);
});
test('sampling rounds luminance and classifies unknown colors from pre-adjustment RGB',()=>{
 const width=140,height=140,settings={version:1,quad:[10,10,110,10,110,110,10,110],automatic:false,black:0,white:1,quietZone:false};
 const rounded=run({action:'sample',width,height,pixels:new Uint32Array(width*height).fill(5),settings});assert.ok(rounded.matrix.text.replace(/\n/g,'').split('').every(c=>c==='0'));
 const colors=run({action:'sample',width,height,pixels:new Uint32Array(width*height).fill(0xffffff),originalPixels:new Uint32Array(width*height).fill(0xff0000),settings:{...settings,colorUnknown:true,colorClusters:[5324,8009,6720,500]}});assert.ok(colors.matrix.text.includes('?'));
});

test('quick repair retains its automatic policy through QRR 42 and replays the selected input',()=>{
 const matrix=run({action:'demo',text:'AUTOMATIC POLICY',damage:true});
 const r=run({action:'quickRecover',matrix,settings:{version:(matrix.text.split('\n').length-17)/4},firstRun:true});
 assert.ok(r.automatic.selectedIndex>=0);
 const c=r.candidates[r.automatic.selectedIndex];assert.equal(c.text,'AUTOMATIC POLICY');
 const h={label:'Quick automatic repair',kind:'AUTO_RECOVERY',matrix:c.input,source:c.input,settings:c.settings,automaticScanAssumption:c.automaticScanAssumption,automaticScanListExtension:c.automaticScanListExtension,samplingInverted:c.samplingInverted};
 const encoded=run({action:'qrrEncode',id:'quick-policy',history:JSON.stringify([h]),cursor:0});assert.equal(encoded.version,42);
 const restored=run({action:'qrrDecode',...encoded}).history[0];
 assert.equal(restored.automaticScanAssumption,h.automaticScanAssumption);assert.equal(restored.automaticScanListExtension,h.automaticScanListExtension);
 const replay=run({action:'recover',matrix:restored.matrix,settings:restored.settings,automaticScanAssumption:restored.automaticScanAssumption,automaticScanListExtension:restored.automaticScanListExtension,samplingInverted:restored.samplingInverted});
 assert.ok(replay.candidates.some(candidate=>candidate.text==='AUTOMATIC POLICY'));
});

test('automatic TXT repair accepts priority observations beyond the manual limit',()=>{
 const matrix=run({action:'demo',text:'PRIORITY AUTO',damage:false}),n=matrix.text.split('\n').length,settings={version:(n-17)/4};
 const allowed=run({action:'regions',matrix,settings}).allowed;
 const flat=matrix.text.replace(/\n/g,'').split('');for(const i of allowed.slice(0,15))flat[i]='*';
 matrix.text=Array.from({length:n},(_,y)=>flat.slice(y*n,(y+1)*n).join('')).join('\n');
 const result=run({action:'quickRecover',matrix,settings,firstRun:true,timeLimitMillis:1000});
 assert.equal(typeof result.automatic.selectedIndex,'number');
 assert.ok(result.candidates.every(c=>c.input&&c.settings));
 assert.throws(()=>recover(matrix),/4/);
});

test('old workspace LM geometry is discarded when Finder orientation is unassigned',()=>{
 const matrix=run({action:'demo',damage:false,text:'LEGACY LM'}),n=matrix.text.split('\n').length;
 const base=[4,4,n+3,4,n+3,n+3,4,n+3],applied=base.map(v=>v+.25);
 for(const assigned of [false,true]){
  const settings={version:(n-17)/4,quad:applied,assigned,lmEnabled:true,lmBaseQuad:base,lmAppliedQuad:applied};
  const encoded=run({action:'qrrEncode',id:'legacy-lm',cursor:0,history:JSON.stringify([{matrix,source:matrix,settings,label:'LM snapshot'}])});
  const decoded=run({action:'qrrDecode',...encoded}).history[0].settings;
  assert.equal(encoded.version,42);assert.equal(decoded.lmEnabled,true);assert.equal(decoded.assigned,assigned);
  assert.deepEqual(decoded.lmAppliedQuad,assigned?applied:null);
  assert.deepEqual(decoded.quad,applied);
 }
});

test('malformed UTF-8 preserves following ASCII and the final replacement character',async()=>{
 const text=await readFile(new URL('./fixtures/malformed-utf8.txt',import.meta.url),'utf8');
 const result=recover(run({action:'parse',text}));
 assert.ok(result.candidates.some(c=>c.text==='\uFFFDH\uFFFD\u0006\uFFFD'),JSON.stringify(result.candidates.map(c=>c.text)));
});

test('image detection leaves unknown polarity automatic',()=>{
 const width=100,height=100;const r=run({action:'detect',width,height,pixels:Array(width*height).fill(0xffffff),settings:{}});
 assert.equal(r.confirmedPolarity,'UNKNOWN');assert.equal(r.polarity,'AUTO');
});
test('automatic image repair tries inverted polarity when no polarity was specified',()=>{
 const matrix=run({action:'demo',text:'AUTO POLARITY',damage:false}),rows=matrix.text.split('\n'),n=rows.length,pitch=8,width=(n+8)*pitch,pixels=Array(width*width).fill(0);
 for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(rows[y][x]==='0')for(let dy=0;dy<pitch;dy++)for(let dx=0;dx<pitch;dx++)pixels[((y+4)*pitch+dy)*width+(x+4)*pitch+dx]=0xffffff;
 const first=4*pitch+(pitch-1)/2,last=first+(n-1)*pitch;
 const result=run({action:'quickRecoverImage',width,height:width,pixels,firstRun:true,settings:{version:(n-17)/4,quad:[first,first,last,first,last,last,first,last],assigned:false,automatic:false,black:64,white:192}});
 assert.ok(result.automatic.selectedIndex>=0);assert.equal(result.candidates[result.automatic.selectedIndex].text,'AUTO POLARITY');
});

test('QRR correctable fixed cells become hard pins when corrections are disabled again',()=>{
 const matrix=run({action:'demo',damage:false,text:'QRR PIN'}),rows=matrix.text.split('\n'),n=rows.length,index=n*n-1;
 rows[n-1]=rows[n-1].slice(0,-1)+(rows[n-1].at(-1)==='1'?'0':'1');matrix.text=rows.join('\n');
 matrix.sources=matrix.sources.slice(0,index)+'6';
 const raw=run({action:'qrrEncode',id:'relocked-pin',cursor:0,history:JSON.stringify([{label:'Fixed but correctable',matrix,source:matrix,settings:{version:(n-17)/4,lockManual:false}}])});
 const session=run({action:'qrrDecode',...raw}),h=session.history[0];
 assert.equal(h.matrix.sources[index],'6');assert.equal(h.settings.lockManual,false);
 assert.ok(recover(h.matrix,{...h.settings,list:0,lockManual:false}).candidates.some(c=>c.text==='QRR PIN'));
 assert.equal(recover(h.matrix,{...h.settings,list:0,lockManual:true}).candidates.length,0);
 h.matrix.sources=h.matrix.sources.slice(0,index)+'5';
 assert.ok(recover(h.matrix,{...h.settings,list:0,lockManual:true}).candidates.some(c=>c.text==='QRR PIN'),'soft edits must remain correctable');
});

test('QRR export accepts long history descriptions without changing the history cursor',()=>{
 const matrix=run({action:'demo',damage:false}),settings={version:(matrix.text.split('\n').length-17)/4};
 for(const length of [1600,1601,16000]){
  const history=[{label:'Curved grid',detail:'格'.repeat(length),matrix,source:matrix,settings},{label:'Later edit',matrix,source:matrix,settings}];
  const encoded=run({action:'qrrEncode',id:'long-history',cursor:0,history:JSON.stringify(history)});
  const decoded=run({action:'qrrDecode',...encoded});
  assert.equal(decoded.history[0].detail,'格'.repeat(Math.min(length,1600)));
  assert.equal(decoded.history.length,2);assert.equal(decoded.cursor,0);
 }
});

test('automatic image repair returns cell hit-test coordinates matching each candidate geometry',()=>{
 const matrix=run({action:'demo',text:'GRID AUDIT',damage:false}),rows=matrix.text.split('\n'),n=rows.length,pitch=6,width=(n+8)*pitch,pixels=Array(width*width).fill(0xffffff);
 for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(rows[y][x]==='1')for(let dy=0;dy<pitch;dy++)for(let dx=0;dx<pitch;dx++)pixels[((y+4)*pitch+dy)*width+(x+4)*pitch+dx]=0;
 const result=run({action:'quickRecoverImage',width,height:width,pixels,firstRun:true,settings:{version:(n-17)/4,assigned:false}});
 assert.ok(result.automatic.selectedIndex>=0);
 for(const candidate of result.candidates){
  const size=17+4*candidate.version,grid=candidate.settings.grid;
  assert.equal(grid.length,size*size);assert.ok(grid.every(p=>p.length===2&&p.every(Number.isFinite)));
  for(const [index,corner] of [[0,0],[size-1,1],[size*size-1,2],[size*(size-1),3]]){
   assert.ok(Math.hypot(grid[index][0]-candidate.quad[corner*2],grid[index][1]-candidate.quad[corner*2+1])<1e-6);
  }
 }
 assert.equal(result.settings.grid.length,result.input.text.split('\n').length**2);
});

function auditImage(text,{inverted=false,missingFinders=false,color=false}={}){
 const matrix=run({action:'demo',text,damage:false}),rows=matrix.text.split('\n'),n=rows.length,pitch=8,width=(n+8)*pitch;
 const pixels=new Uint32Array(width*width).fill(inverted?0:0xffffff);
 for(let y=0;y<n;y++)for(let x=0;x<n;x++){
  let rgb=(rows[y][x]==='1')!==inverted?0:0xffffff;
  if(missingFinders&&(x>=n-8&&y<9||x<9&&y>=n-8))rgb=0x808080;
  if(color&&x>=10&&x<14&&y>=10&&y<14)rgb=0xff0000;
  for(let dy=0;dy<pitch;dy++)for(let dx=0;dx<pitch;dx++)pixels[((y+4)*pitch+dy)*width+(x+4)*pitch+dx]=rgb;
 }
 const first=4*pitch+(pitch-1)/2,last=first+(n-1)*pitch;
 return {matrix,n,pitch,request:{width,height:width,pixels},settings:{version:(n-17)/4,quad:[first,first,last,first,last,last,first,last],assigned:true,automatic:false,black:64,white:192,lmEnabled:false,curved:false,list:0}};
}
test('detailed AUTO repair includes inverted polarity and retains the winning input',()=>{
 const {matrix,request,settings}=auditImage('INVERTED DETAIL',{inverted:true,missingFinders:true});
 const auto=run({action:'recoverImage',...request,matrix,settings:{...settings,polarity:'AUTO',confirmedPolarity:'UNKNOWN'}});
 const normal=run({action:'recoverImage',...request,matrix,settings:{...settings,polarity:'NORMAL'}});
 assert.equal(normal.candidates.length,0);assert.ok(auto.candidates.some(c=>c.text==='INVERTED DETAIL'));
 assert.ok(auto.candidates.every(c=>c.input));
});
test('tone refresh rechecks a surviving manually assigned inverted Finder',()=>{
 const {request,settings,pitch}=auditImage('MANUAL POLARITY',{inverted:true,missingFinders:true});
 const center=settings.quad[0]+3*pitch;
 const pixels=Array.from(request.pixels,rgb=>{const r=Math.min(255,(rgb>>16)+13),g=Math.min(255,((rgb>>8)&255)+13),b=Math.min(255,(rgb&255)+13);return (r<<16)|(g<<8)|b;});
 const result=run({action:'sample',...request,pixels,refreshPolarity:true,settings:{...settings,polarity:'AUTO',confirmedPolarity:'INVERTED',finders:[0,center,center],brightness:5}});
 assert.equal(result.confirmedPolarity,'INVERTED');assert.equal(result.matrix.text[0],'1');
});
test('manual Finder alignment moves its grid center while preserving unassigned corners',()=>{
 const {request,settings,n,pitch}=auditImage('FINDER ALIGNMENT');
 const shifted={...settings,quad:settings.quad.map(v=>v-3)},center=settings.quad[0]+3*pitch;
 const before=run({action:'sample',...request,settings:shifted});
 const finder=run({action:'finder',...request,settings:shifted,x:center,y:center,alignGrid:true,role:0});
 assert.equal(finder.confirmed,true);assert.notDeepEqual(finder.quad,shifted.quad);
 const after=run({action:'sample',...request,settings:{...shifted,quad:finder.quad}});
 const point=after.grid[3*n+3];assert.ok(Math.hypot(point[0]-finder.x,point[1]-finder.y)<1e-6);
 for(const index of [n-1,(n-1)*n,n*n-1])for(let axis=0;axis<2;axis++)assert.ok(Math.abs(after.grid[index][axis]-before.grid[index][axis])<1e-6);
});
test('quick image color clusters use original RGB after tone adjustments',()=>{
 const {request}=auditImage('COLOR',{color:true});
 const pixels=Array.from(request.pixels,rgb=>{const r=Math.min(255,(rgb>>16)+77),g=Math.min(255,((rgb>>8)&255)+77),b=Math.min(255,(rgb&255)+77);return (r<<16)|(g<<8)|b;});
 const adjusted={...request,pixels,originalPixels:request.pixels};
 const detected=run({action:'detect',...adjusted});
 const quick=run({action:'quickRecoverImage',...adjusted,settings:{brightness:30,assigned:false},firstRun:true,timeLimitMillis:10000});
 assert.ok(detected.colorClusters.length>0);assert.deepEqual(quick.settings.colorClusters,detected.colorClusters);
 const s=quick.candidates[0]?.settings||quick.settings;
 const sampled=run({action:'sample',...adjusted,settings:{...s,colorUnknown:true}});
 assert.equal(sampled.matrix.text.split('\n')[11][11],'?');
});

for(const text of ['😀','𠮷','A😀','😀A','日本語😀','日本語😀x'])test(`UTF-16BE preserves the complete payload: ${text}`,()=>{
 const matrix=run({action:'demo',text,encoding:'UTF-16BE',damage:false});
 assert.ok(recover(matrix).candidates.some(c=>c.text===text));
});

test('editing a restored QRR preserves recovered cells outside the selection',()=>{
 const clean=run({action:'demo',text:'RESTORED',damage:false}),source=run({action:'demo',text:'RESTORED',damage:true});
 const matrix={...clean,sources:'2'.repeat(clean.sources.length)},version=(matrix.text.split('\n').length-17)/4;
 const raw=run({action:'qrrEncode',id:'restored-edit',cursor:0,history:JSON.stringify([{label:'Applied recovery',kind:'APPLY_RECOVERY',matrix,source,settings:{version}}])});
 const snapshot=run({action:'qrrDecode',...raw}).history[0],target=matrix.sources.length-1;
 const edit=(m,value,kind)=>run({action:'edit',matrix:m,source:snapshot.source,settings:snapshot.settings,cells:[target],value,kind});
 const changed=edit(snapshot.matrix,'?'),before=snapshot.matrix.text.replaceAll('\n',''),after=changed.text.replaceAll('\n','');
 assert.equal(after.slice(0,target),before.slice(0,target));assert.equal(after[target],'?');
 assert.equal(changed.sources.slice(0,target),snapshot.matrix.sources.slice(0,target));
 const restored=edit(changed,'source','0');
 assert.equal(restored.text.replaceAll('\n','')[target],source.text.replaceAll('\n','')[target]);
 assert.equal(restored.text.replaceAll('\n','').slice(0,target),before.slice(0,target));
 const fixed=edit(snapshot.matrix,'source','1');
 assert.equal(fixed.text,snapshot.matrix.text);assert.equal(fixed.sources[target],'1');
 const unchanged=edit(snapshot.matrix,before[target]);assert.deepEqual(unchanged,snapshot.matrix);
});

test('malformed Shift_JIS payload retains the following ASCII space',async()=>{
 // ECI 20, BYTE payload 82 20 48 69, with valid QR error correction.
 const text=await readFile(new URL('./fixtures/malformed-shift-jis.txt',import.meta.url),'utf8');
 const matrix=run({action:'parse',text});
 assert.equal(recover(matrix).candidates[0].text,'\uFFFD Hi');
});

test('QRR source transforms preserve perspective, correction preferences and legacy reference angles',()=>{
 const matrix=run({action:'demo',damage:false}),version=(matrix.text.split('\n').length-17)/4;
 for(const legacyAngle of [null,15,-30])for(const quad of [null,[10,20,200,10,210,220,20,200]]){
  const imageOps=[{type:'source',angle:35,quad,version,legacyAngle}];
  const settings={version,imageOps,lmEnabled:true,curved:true,finders:[0,35,40]};
  const encoded=run({action:'qrrEncode',id:'source-transform',history:JSON.stringify([{matrix,source:matrix,settings}]),cursor:0});
  const decoded=run({action:'qrrDecode',...encoded}).history[0].settings;
  assert.deepEqual(decoded.imageOps,imageOps.map(op=>({...op,...(legacyAngle!=null?{legacyRaster:'android'}:{})})));assert.equal(decoded.lmEnabled,true);assert.equal(decoded.curved,true);assert.deepEqual(decoded.finders,settings.finders);
 }
});

for(const order of ['be','le'])test(`BOM-detected UTF-16 ${order} preserves a final supplementary character`,async()=>{
 const text=await readFile(new URL(`./fixtures/utf16-bom-${order}.txt`,import.meta.url),'utf8');
 const matrix=run({action:'parse',text});assert.ok(recover(matrix).candidates.some(c=>c.text==='😀'));
});
