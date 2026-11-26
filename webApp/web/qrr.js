import {digest} from './storage.js';
import {CURRENT_QRR_VERSION} from './qrr-format.js';
const MAX_FILE=20*1024*1024,MAX_RAW=16*1024*1024;
export function base64(bytes){let s='';for(let i=0;i<bytes.length;i+=16384)s+=String.fromCharCode(...bytes.subarray(i,i+16384));return btoa(s);}
export function unbase64(text){return Uint8Array.from(atob(text),c=>c.charCodeAt(0));}
async function transform(bytes,stream,max){const reader=new Blob([bytes]).stream().pipeThrough(stream).getReader(),chunks=[];let size=0;try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>max){await reader.cancel();throw new Error('QRRの展開サイズが上限を超えました');}chunks.push(value);}}finally{reader.releaseLock();}const out=new Uint8Array(size);let i=0;for(const c of chunks){out.set(c,i);i+=c.length;}return out;}
export async function unpackQrr(file){
 if(file.size<48||file.size>MAX_FILE)throw new Error('QRRファイルのサイズが不正です');
 const bytes=new Uint8Array(await file.arrayBuffer()),v=new DataView(bytes.buffer),version=v.getInt32(4),rawSize=v.getInt32(8),compressedSize=v.getInt32(12);
 if(v.getInt32(0)!==0x5152523a||version<23||version>CURRENT_QRR_VERSION||rawSize<1||rawSize>MAX_RAW||compressedSize<1||compressedSize+48!==bytes.length)throw new Error(`QRRヘッダーが不正か、未対応の版です（対応: 23～${CURRENT_QRR_VERSION}）`);
 const raw=await transform(bytes.subarray(48),new DecompressionStream('gzip'),rawSize);
 if(raw.length!==rawSize||await digest(raw)!==[...bytes.subarray(16,48)].map(x=>x.toString(16).padStart(2,'0')).join(''))throw new Error('QRRのSHA-256整合性チェックに失敗しました');
 return {raw:base64(raw),version};
}
export async function packQrr({raw,version}){
 const bytes=unbase64(raw);if(bytes.length<1||bytes.length>MAX_RAW)throw new Error('QRRデータが大きすぎます');
 const compressed=await transform(bytes,new CompressionStream('gzip'),MAX_FILE-48),output=new Uint8Array(48+compressed.length),v=new DataView(output.buffer);
 v.setInt32(0,0x5152523a);v.setInt32(4,version);v.setInt32(8,bytes.length);v.setInt32(12,compressed.length);
 output.set((await digest(bytes)).match(/../g).map(x=>parseInt(x,16)),16);output.set(compressed,48);
 return new Blob([output],{type:'application/octet-stream'});
}
