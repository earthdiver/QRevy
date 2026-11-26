const imageBytes=new WeakMap();
function cachedImageBytes(blob){if(!imageBytes.has(blob))imageBytes.set(blob,blob.arrayBuffer());return imageBytes.get(blob);}
const dbPromise = new Promise((resolve, reject) => {
  const open = indexedDB.open('qrevy-pwa', 1);
  open.onupgradeneeded = () => open.result.createObjectStore('sessions', { keyPath: 'id' });
  open.onsuccess = () => resolve(open.result);
  open.onerror = () => reject(open.error);
});
// Attach a rejection handler immediately; callers still receive the original failure.
dbPromise.catch(() => {});
async function transaction(mode, action) {
  const db = await dbPromise;
  return new Promise((resolve, reject) => {
    const tx = db.transaction('sessions', mode);
    let result;
    action(tx.objectStore('sessions'), value => { result = value; });
    tx.oncomplete = () => resolve(result);
    tx.onerror = () => reject(tx.error || new Error('作業を保存できませんでした'));
    tx.onabort = () => reject(tx.error || new Error('保存を中断しました'));
  });
}
export const listSessions = () => transaction('readonly', (s, done) => {
  const r = s.getAll(); r.onsuccess = () => done(r.result.map(value=>{if(value.image?.bytes){const stored=value.image;value.image=new Blob([stored.bytes],{type:stored.type});imageBytes.set(value.image,Promise.resolve(stored.bytes));}return value;}).sort((a,b) => b.updated - a.updated));
});
export const removeAllSessions = () => transaction('readwrite', s => s.clear());
export const removeSession = id => transaction('readwrite', s => s.delete(id));
export async function saveSession(session) {
  const stored={...session,image:session.image instanceof Blob?{bytes:await cachedImageBytes(session.image),type:session.image.type}:session.image};
  await transaction('readwrite', s => {
    s.put(stored);
    const all = s.getAll();
    all.onsuccess = () => all.result.sort((a,b)=>b.updated-a.updated).slice(20).forEach(v=>s.delete(v.id));
  });
}
export async function digest(bytes) {
  const hash = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(hash)].map(x=>x.toString(16).padStart(2,'0')).join('');
}
export async function exportWorkspace(session) {
  const image = session.image ? await blobToDataURL(session.image) : null;
  const payload = JSON.stringify({ ...session, image });
  if(new TextEncoder().encode(payload).length>31*1024*1024)throw new Error('作業ファイルが大きすぎます。QRRと画像を別々に保存してください');
  return new Blob([JSON.stringify({ format:'QRevy-PWA', version:1, sha256:await digest(new TextEncoder().encode(payload)), payload })], {type:'application/json'});
}
export async function importWorkspace(file) {
  if (file.size > 32*1024*1024) throw new Error('作業ファイルは32MB以下にしてください');
  const envelope = JSON.parse(await file.text());
  if(envelope.format !== 'QRevy-PWA' || envelope.version !== 1 || typeof envelope.payload !== 'string') throw new Error('対応していない作業ファイルです');
  if(await digest(new TextEncoder().encode(envelope.payload)) !== envelope.sha256) throw new Error('作業ファイルの整合性チェックに失敗しました');
  const session=JSON.parse(envelope.payload);
  validateSession(session);
  if(session.image) {
    if(!/^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(session.image))throw new Error('画像の形式が不正です');
    const [header,body]=session.image.split(',');
    session.image=new Blob([Uint8Array.from(atob(body),c=>c.charCodeAt(0))],{type:header.slice(5,header.indexOf(';'))});
    if(await digest(await cachedImageBytes(session.image))!==session.imageHash)throw new Error('元画像の整合性チェックに失敗しました');
  }
  return session;
}
export function validateMatrix(matrix) {
  if(!matrix || typeof matrix.text!=='string' || matrix.text.length>65536)throw new Error('行列が不正です');
  const rows=matrix.text.split('\n'), n=rows.length;
  if(n<21||n>177||(n-17)%4||rows.some(r=>r.length!==n||/[^01?*]/.test(r)))throw new Error('QR行列の大きさまたは文字が不正です');
  if(typeof matrix.sources!=='string'||matrix.sources.length!==n*n||/[^0-6]/.test(matrix.sources))throw new Error('セルの出自が不正です');
  if(matrix.margins && (!Array.isArray(matrix.margins)||matrix.margins.length!==n*n||matrix.margins.some(x=>!Number.isInteger(x)||x < -1||x>254)))throw new Error('画像の測定値が不正です');
  if(matrix.confidences && (!Array.isArray(matrix.confidences)||matrix.confidences.length!==n*n||matrix.confidences.some(x=>!Number.isFinite(x)||x<0||x>1)))throw new Error('セルの信頼度が不正です');
  return n;
}
export function validateSession(s) {
  if(!s || typeof s.id!=='string'||!/^[-\w]{1,80}$/.test(s.id)||typeof s.name!=='string'||s.name.length>200||!Number.isFinite(s.updated)||s.updated<0)throw new Error('作業情報が不正です');
  if(!Array.isArray(s.history)||s.history.length<1||s.history.length>40||!Number.isInteger(s.cursor)||s.cursor<0||s.cursor>=s.history.length)throw new Error('操作履歴が不正です');
  const entries=[...s.history,...(s.checkpoint?[s.checkpoint]:[])];
  const snapshots=[...entries];
  for(const h of entries){
    if(!h||typeof h!=='object')throw new Error('操作履歴が不正です');
    if(h.automaticScanAssumption!=null&&!['NONE','UTF8','SINGLE_SEGMENT','EXACT_KNOWN_BITS','UTF8_SINGLE_SEGMENT'].includes(h.automaticScanAssumption))throw new Error('自動修復の実行条件が不正です');
    if(h.automaticScanListExtension!=null&&![1,2].includes(h.automaticScanListExtension))throw new Error('自動修復の探索条件が不正です');
    if(h.samplingInverted!=null&&typeof h.samplingInverted!=='boolean')throw new Error('自動修復の白黒判定が不正です');
    if(h.automatic!=null){
      const a=h.automatic;
      if(typeof a!=='object'||!['initial','extended','unlimited'].includes(a.mode)||typeof a.firstRun!=='boolean'||!a.input||!a.input.matrix||!a.input.settings)throw new Error('自動修復の履歴が不正です');
      if(a.reports!=null&&(!Array.isArray(a.reports)||a.reports.length>3||a.reports.some(r=>!r||!['initial','extended','unlimited'].includes(r.mode)||typeof r.report!=='string'||r.report.length>2*1024*1024)))throw new Error('自動修復の診断情報が不正です');
      snapshots.push({matrix:a.input.matrix,settings:a.input.settings});
      if(a.retrySettings!=null)validateSettings(a.retrySettings);
    }
  }
  for(const h of snapshots) {
    const n=validateMatrix(h.matrix);if(h.source)validateMatrix(h.source);
    validateSettings(h.settings,n);
  }
}
function validateSettings(p,n=null){
    if(p?.gridReference!=null&&typeof p.gridReference!=='boolean')throw new Error('設定が不正です');
    if(p?.visibleFinderRole!=null&&(![0,1,3].includes(p.visibleFinderRole)||p.assigned!==true||!p.quad))throw new Error('位置未測定のFinder役割が不正です');
    for(const key of ['brightness','contrast'])if(p?.[key]!=null&&(!Number.isFinite(p[key])||p[key]<-100||p[key]>100))throw new Error('画像調整値が不正です');
    if(!p)throw new Error('設定が不正です');if(p.priorityLimit==null)p.priorityLimit=4;if(!Number.isInteger(p.priorityLimit)||p.priorityLimit<4||p.priorityLimit>14)throw new Error('優先セル上限が不正です');if(!Number.isInteger(p.version)||p.version<1||p.version>40||(n!=null&&p.version!==(n-17)/4))throw new Error('設定と行列のバージョンが一致しません');
    if(p.photographicSource!=null&&typeof p.photographicSource!=='boolean')throw new Error('画像の出自が不正です');
    if(p.imageOps!=null){
      const angle=v=>Number.isFinite(v)&&Math.abs(v)<=360;
      const rect=o=>Array.isArray(o.quad)&&o.quad.length===8&&o.quad.every(x=>Number.isFinite(x)&&Math.abs(x)<=1e7)&&Number.isInteger(o.version)&&o.version>=1&&o.version<=40;
      if(!Array.isArray(p.imageOps)||p.imageOps.length>2||p.imageOps.some(o=>!o||
        (o.type==='source'?(p.imageOps.length!==1||(o.legacyRaster!=null&&!['android','pwa'].includes(o.legacyRaster))||!angle(o.angle)||(o.legacyAngle!=null&&!angle(o.legacyAngle))||(o.quad!=null&&!rect(o))):
         o.type==='rotate'?!angle(o.angle):o.type==='rectify'?!rect(o):true)))throw new Error('画像変換が不正です');
    }
    if(p.quad && (!Array.isArray(p.quad)||p.quad.length!==8||p.quad.some(x=>!Number.isFinite(x)||Math.abs(x)>1e7)))throw new Error('格子座標が不正です');
    if(![12,13,14].includes(p.partial)||![0,1,2,3].includes(p.list)||![0,1,2].includes(p.known)||!['','L','M','Q','H'].includes(p.ecc)||!Number.isInteger(p.mask)||p.mask < -1||p.mask>7)throw new Error('修復設定が不正です');
    if(!Number.isInteger(p.black)||!Number.isInteger(p.white)||p.black<0||p.white>255||p.black>=p.white)throw new Error('閾値が不正です');
    for(const key of ['formatLimitMode','dataLimitMode'])if(!['AUTOMATIC','EXACT','STRICT','RELAXED','MANUAL','UNLIMITED'].includes(p[key]))throw new Error('誤り上限が不正です');
    if(!Number.isInteger(p.formatLimit)||p.formatLimit<0||p.formatLimit>30||!Number.isInteger(p.dataLimit)||p.dataLimit<0||p.dataLimit>29648)throw new Error('誤り上限が不正です');
    if(!['NORMAL','INVERTED','AUTO'].includes(p.polarity))throw new Error('白黒の設定が不正です');
}
export function blobToDataURL(blob) {
  return new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.onerror=()=>reject(r.error);r.readAsDataURL(blob);});
}
