import {readFile,writeFile} from 'node:fs/promises';

export async function currentQrrVersion(){
 const source=await readFile(new URL('../../androidApp/src/main/java/io/github/earthdiver/qrrepair/session/RecoverySessionCodec.kt',import.meta.url),'utf8');
 const matches=[...source.matchAll(/\bconst\s+val\s+CURRENT_VERSION\s*=\s*(\d+)\b/g)];
 if(matches.length!==1)throw new Error('Expected one QRR CURRENT_VERSION definition');
 const version=Number(matches[0][1]);
 if(!Number.isSafeInteger(version)||version<23)throw new Error('Invalid QRR CURRENT_VERSION');
 return version;
}

export async function generateQrrFormat(){
 await writeFile(new URL('../web/qrr-format.js',import.meta.url),`export const CURRENT_QRR_VERSION=${await currentQrrVersion()};\n`);
}
