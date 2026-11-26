import test from 'node:test';
import assert from 'node:assert/strict';
import {codewordEdges,codewordCellLabel,drawCodewordOverlay} from '../web/codeword-overlay.js';
import {execute} from '../web/engine/js/engine.js';
const run=r=>{const out=JSON.parse(execute(r));assert.equal(out.error,undefined);return out;};
test('codeword contours omit shared edges and retain boundaries between words',()=>{
 const cells={'0,0':{raw:0},'1,0':{raw:0},'0,1':{raw:1}};
 assert.deepEqual(codewordEdges(cells,0,0),[true,false,true,true]);
});
test('trusted format metadata exposes Android block and per-kind indices',()=>{
 const matrix=run({action:'demo',damage:false});const {cells}=run({action:'overlay',matrix});
 const entries=Object.values(cells);assert.ok(entries.some(c=>c.kind==='DATA'));assert.ok(entries.some(c=>c.kind==='ECC'));
 for(const cell of entries){assert.ok(Number.isInteger(cell.block));assert.ok(Number.isInteger(cell.kindIndex));assert.ok(cell.bit>=0&&cell.bit<8);assert.match(codewordCellLabel(cell,true),/Block \d+ \/ (Data|ECC) #\d+ \/ bit \d/);}
 const n=21,unknown={text:Array(n).fill('?'.repeat(n)).join('\n'),sources:'0'.repeat(n*n)};
 for(const cell of Object.values(run({action:'overlay',matrix:unknown}).cells)){assert.equal(cell.kind,'UNCLASSIFIED');assert.equal(cell.block,null);assert.match(codewordCellLabel(cell,true),/分類未確定/);}
});
test('overlay uses screen pitch for boundaries and raw codeword labels',()=>{
 const calls=[];const context=new Proxy({}, {get:(_,key)=>(...args)=>calls.push([key,...args]),set:()=>true});const cells={'0,0':{raw:7,kind:'DATA',bit:0}};
 drawCodewordOverlay(context,cells,(x,y)=>[x*5,y*5],1);assert.equal(calls.filter(c=>c[0]==='fill').length,0);
 drawCodewordOverlay(context,cells,(x,y)=>[x*10,y*10],1);assert.equal(calls.filter(c=>c[0]==='stroke').length,4);assert.equal(calls.filter(c=>c[0]==='fillText').length,0);
 drawCodewordOverlay(context,cells,(x,y)=>[x*24,y*24],1);assert.ok(calls.some(c=>c[0]==='fillText'&&c[1]==='#7'));
});
