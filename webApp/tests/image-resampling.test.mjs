import test from 'node:test';
import assert from 'node:assert/strict';
import {resample} from '../web/engine/js/engine.js';
import {loadNativeScanner,cacheNativeScanner} from '../web/native-boundaries.js';
import {execute} from '../web/engine/js/engine.js';
import {readFile} from 'node:fs/promises';

test('shared raster export preserves center alignment and alpha compositing',()=>{
 const result=resample({width:2,height:1,data:new Uint8ClampedArray([0,0,0,255,101,101,101,255]),method:'resize',outWidth:4,outHeight:1});
 assert.equal(result.width,4);assert.equal(result.height,1);
 assert.deepEqual(Array.from(result.data),[0,0,0,255,25,25,25,255,76,76,76,255,101,101,101,255]);
 const alpha=resample({width:2,height:1,data:new Uint8ClampedArray([255,0,0,0,0,0,0,255]),method:'resize',outWidth:1,outHeight:1});
 assert.deepEqual(Array.from(alpha.data),[128,128,128,255]);
});

test('shared import uses ceiling dimensions and integer means on an odd edge',()=>{
 const width=1,height=2601,data=new Uint8ClampedArray(width*height*4);
 for(let y=0;y<height;y++){data[y*4]=data[y*4+1]=data[y*4+2]=y%256;data[y*4+3]=255;}
 const result=resample({width,height,data,method:'import'});
 assert.equal(result.width,1);assert.equal(result.height,1301);
 assert.deepEqual(Array.from(result.data.slice(0,4)),[1,1,1,255]);
 assert.deepEqual(Array.from(result.data.slice(-4)),[40,40,40,255]);
});

test('bundled native reader provides geometry to the browser estimator',async()=>{
 const wasmBinary=await readFile(new URL('../web/native/boundary.wasm',import.meta.url));
 const detect=await loadNativeScanner({wasmBinary});
 const matrix=JSON.parse(execute({action:'demo',text:'NATIVE BOUNDARY',damage:false})).text.split('\n');
 const n=matrix.length,pitch=8,width=(n+8)*pitch,pixels=new Uint32Array(width*width).fill(0xffffff);
 for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(matrix[y][x]==='1')for(let dy=0;dy<pitch;dy++)for(let dx=0;dx<pitch;dx++)pixels[((y+4)*pitch+dy)*width+(x+4)*pitch+dx]=0;
 const scan=detect(pixels,width,width);const quads=scan.boundaries;
 assert.equal(scan.decoded.text,'NATIVE BOUNDARY');
 assert.equal(quads.length,8);assert.deepEqual(quads,[32,32,(n+4)*8,32,(n+4)*8,(n+4)*8,32,(n+4)*8]);
 let calls=0;globalThis.qrevyNativeScan=(...args)=>{calls++;return detect(...args);};
 try{const result=JSON.parse(execute({action:'detect',width,height:width,pixels}));assert.equal(result.error,undefined);assert.equal(result.assigned,true);assert.equal(calls,1);
 const ordinary=JSON.parse(execute({action:'quickRecoverImage',width,height:width,pixels,settings:{version:1},tryOrdinary:true}));
 assert.equal(ordinary.ordinary.text,'NATIVE BOUNDARY');assert.equal(ordinary.ordinary.reader,'ZXing-C++');assert.deepEqual(ordinary.ordinary.payloadBytes,Array.from(new TextEncoder().encode('NATIVE BOUNDARY')));assert.equal(ordinary.ordinary.dataCodewords,undefined);assert.equal(ordinary.automatic,undefined);assert.equal(calls,2);}
 finally{delete globalThis.qrevyNativeScan;}
 const fallback=JSON.parse(execute({action:'ordinaryRead',width,height:width,pixels}));assert.equal(fallback.ordinary.text,'NATIVE BOUNDARY');assert.equal(fallback.ordinary.reader,'ZXing Java');assert.ok(fallback.ordinary.dataCodewords.length);assert.equal(fallback.ordinary.payloadBytes,undefined);
 assert.deepEqual(detect(new Uint32Array(100).fill(0xffffff),10,10),{boundaries:[],decoded:null});
 assert.deepEqual(detect([],4097,1),{boundaries:[],decoded:null});
});


test('ordinary native text preserves Unicode, whitespace, and URLs without creating repair candidates',async()=>{
 const scan=await loadNativeScanner({wasmBinary:await readFile(new URL('../web/native/boundary.wasm',import.meta.url))});
 const text='\uFEFF  日本語 QR\nhttps://example.invalid/a?b=1&c=2\n';
 const matrix=JSON.parse(execute({action:'demo',text,damage:false})).text.split('\n');
 const n=matrix.length,width=(n+8)*8,pixels=new Uint32Array(width*width).fill(0xffffff);
 for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(matrix[y][x]==='1')for(let dy=0;dy<8;dy++)for(let dx=0;dx<8;dx++)pixels[((y+4)*8+dy)*width+(x+4)*8+dx]=0;
 let calls=0;globalThis.qrevyNativeScan=(...args)=>{calls++;return scan(...args);};
 try{
  const request={action:'quickRecoverImage',tryOrdinary:true,width,height:width,pixels,settings:{version:1},nativeScanResult:{decoded:{reader:'fake',text:'forged'},boundaries:[]}};
  const result=JSON.parse(execute(request));
  assert.equal(result.ordinary.text,text);assert.equal(result.ordinary.reader,'ZXing-C++');assert.equal(result.candidates,undefined);assert.equal(calls,1);
  request.action='ordinaryRead';request.pixels=new Uint32Array(width*width).fill(0xffffff);
  assert.equal(JSON.parse(execute(request)).ordinary,null);assert.equal(calls,2);
 }finally{delete globalThis.qrevyNativeScan;}
});

test('native scan cache reuses exact pixels and invalidates changed pixels or dimensions',()=>{
 let calls=0;
 const scan=cacheNativeScanner(()=>{calls++;return {boundaries:[calls],decoded:null};});
 const pixels=new Uint32Array([1,2,3,4]);
 const first=scan(pixels,2,2);first.boundaries[0]=100;
 assert.deepEqual(scan(pixels.slice(),2,2),{boundaries:[1],decoded:null});assert.equal(calls,1);
 pixels[3]=5;scan(pixels,2,2);assert.equal(calls,2);
 scan(pixels,1,4);assert.equal(calls,3);
});

test('aligned import tiles preserve whole-image means including transparency and odd edges',()=>{
 const width=2603,height=7,data=new Uint8ClampedArray(width*height*4);
 for(let i=0;i<data.length;i++)data[i]=(i*17+3)%256;
 const full=resample({width,height,data,method:'import'}),actual=new Uint8ClampedArray(full.data.length);
 for(let x=0;x<width;x+=1024){
  const w=Math.min(1024,width-x),tile=new Uint8ClampedArray(w*height*4);
  for(let y=0;y<height;y++)tile.set(data.subarray((y*width+x)*4,(y*width+x+w)*4),y*w*4);
  const result=resample({width:w,height,data:tile,method:'import',sample:2});
  for(let y=0;y<result.height;y++)actual.set(result.data.subarray(y*result.width*4,(y+1)*result.width*4),(y*full.width+x/2)*4);
 }
 assert.deepEqual(actual,full.data);
});


test('native and Java ordinary readers preserve inverted payloads and input pixels',async()=>{
 const scan=await loadNativeScanner({wasmBinary:await readFile(new URL('../web/native/boundary.wasm',import.meta.url))});
 const text='INVERTED QR 42';
 const rows=JSON.parse(execute({action:'demo',text,damage:false})).text.split('\n');
 const width=(rows.length+8)*8,pixels=new Uint32Array(width*width);
 for(let y=0;y<rows.length;y++)for(let x=0;x<rows.length;x++)if(rows[y][x]==='1')
  for(let dy=0;dy<8;dy++)for(let dx=0;dx<8;dx++)pixels[((y+4)*8+dy)*width+(x+4)*8+dx]=0xffffff;
 const original=pixels.slice();
 for(const native of [true,false]){
  if(native)globalThis.qrevyNativeScan=scan;else delete globalThis.qrevyNativeScan;
  try{
   const result=JSON.parse(execute({action:'ordinaryRead',width,height:width,pixels}));
   assert.equal(result.ordinary.text,text);assert.equal(result.ordinary.reader,native?'ZXing-C++':'ZXing Java');
   assert.deepEqual(pixels,original);
   const blank=JSON.parse(execute({action:'ordinaryRead',width:80,height:80,pixels:new Uint32Array(6400)}));
   assert.equal(blank.ordinary,null);
  }finally{delete globalThis.qrevyNativeScan;}
 }
});
