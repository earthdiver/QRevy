const CACHE='qrevy-__VERSION__';
const ASSETS=__ASSETS__;
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
