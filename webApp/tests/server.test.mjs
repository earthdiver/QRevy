import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
const source=(await readFile(new URL('../scripts/serve.mjs',import.meta.url),'utf8')).replace(/^import .*;\n/gm,'');
for(const [platform,api,root] of [['Windows',path.win32,'C:\\project\\dist'],['POSIX',path.posix,'/project/dist']])test(`${platform} dev server serves assets and rejects traversal`,async()=>{
 let handler;const reads=[];
 const context={URL,process:{env:{}},console:{log(){}},...api,resolve:(...parts)=>parts[0]==='dist'?root:api.resolve(...parts),readFile:async file=>{reads.push(file);return 'asset';},http:{createServer(fn){handler=fn;return {listen(){}};}}};
 vm.runInNewContext(source,context);
 for(const url of ['/','/index.html','/app.js','/engine/js/engine.js','/..%2fprivate.txt',...(platform==='Windows'?['/..%5cprivate.txt']:[])]){
  let status,body;await handler({url},{writeHead(code){status=code;},end(data){body=data;}});
  assert.equal(status,url.includes('private')?403:200,url);if(status===200)assert.equal(body,'asset');
 }
 assert.equal(reads.length,4);assert.equal(reads[0],api.join(root,'index.html'));
});
