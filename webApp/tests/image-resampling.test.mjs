import test from 'node:test';
import assert from 'node:assert/strict';
import {resample} from '../web/engine/js/engine.js';
import {loadNativeScanner} from '../web/native-boundaries.js';
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
 assert.equal(ordinary.ordinary.text,'NATIVE BOUNDARY');assert.equal(ordinary.ordinary.reader,'ZXing-C++');assert.equal(ordinary.automatic,undefined);assert.equal(calls,2);}
 finally{delete globalThis.qrevyNativeScan;}
 const fallback=JSON.parse(execute({action:'ordinaryRead',width,height:width,pixels}));assert.equal(fallback.ordinary.text,'NATIVE BOUNDARY');assert.equal(fallback.ordinary.reader,'ZXing Java');
 assert.deepEqual(detect(new Uint32Array(100).fill(0xffffff),10,10),{boundaries:[],decoded:null});
 assert.deepEqual(detect([],4097,1),{boundaries:[],decoded:null});
});


test('ordinary native text preserves Unicode, whitespace, and URLs without creating repair candidates',async()=>{
 const scan=await loadNativeScanner({wasmBinary:await readFile(new URL('../web/native/boundary.wasm',import.meta.url))});
 const text='  日本語 QR\nhttps://example.invalid/a?b=1&c=2\n';
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
