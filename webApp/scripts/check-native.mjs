import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const root=new URL('../web/native/',import.meta.url);
const manifest=JSON.parse(await readFile(new URL('manifest.json',root),'utf8'));
assert.equal(manifest.zxingCppVersion,'3.1.1');
for(const [name,expected] of Object.entries(manifest.files)){
 assert.match(name,/^[A-Za-z0-9_.-]+$/);
 assert.equal(createHash('sha256').update(await readFile(new URL(name,root))).digest('hex'),expected,`Review native asset ${name}`);
}
for(const [name,expected] of Object.entries(manifest.sources)){
 assert.ok(['native/boundary.cpp','native/CMakeLists.txt'].includes(name));
 assert.equal(createHash('sha256').update(await readFile(new URL('../'+name,import.meta.url))).digest('hex'),expected,`Rebuild native assets after editing ${name}`);
}
const android=await readFile(new URL('../../androidApp/build.gradle.kts',import.meta.url),'utf8');
assert.ok(android.includes(`io.github.zxing-cpp:android:${manifest.zxingCppVersion}`),'Native reader versions must match');
console.log('Bundled ZXing-C++ assets and Android version verified.');
