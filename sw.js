const CACHE='qrevy-90328b20aeef38e5';
const ASSETS=["./","./app.js","./codeword-overlay.js","./diagnostic-view.css","./diagnostic-view.js","./engine/js/engine.js","./grid-overlay.js","./i18n.js","./icons/apple-touch-icon.png","./icons/icon-192.png","./icons/icon-512.png","./image-tools.js","./index.html","./licenses/LICENSE","./licenses/NOTICE.md","./licenses/PRIVACY.ja.md","./licenses/PRIVACY.md","./licenses/QREVY_NOTICE.md","./licenses/THIRD_PARTY_NOTICES.md","./manifest.webmanifest","./native-strings.js","./preferences.js","./qrr-format.js","./qrr.js","./recovery-worker.js","./session-migration.js","./storage.js","./style.css","./worker.js","./workflow.js","./workspace-history.js"];
self.addEventListener('install',event=>{event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(ASSETS)));});
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('qrevy-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
 if(event.request.method!=='GET'||new URL(event.request.url).origin!==self.location.origin)return;
 event.respondWith(caches.open(CACHE).then(async cache=>{
  const cached=await cache.match(event.request,{ignoreSearch:true});if(cached)return cached;
  if(event.request.mode==='navigate')return await cache.match('./index.html')||fetch(event.request);
  return fetch(event.request);
 }));
});
