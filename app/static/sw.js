// 离线壳缓存：只缓存静态资源，绝不缓存 /api（含卡密等敏感数据）
const SHELL = 'xc-shell-v1';
const ASSETS = ['/', 'https://cdn.tailwindcss.com', 'https://unpkg.com/lucide@latest'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(SHELL).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== SHELL).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws/')) return;
  // stale-while-revalidate
  e.respondWith(caches.open(SHELL).then(async cache => {
    const hit = await cache.match(e.request);
    const net = fetch(e.request).then(r => { if (r.ok || r.type === 'opaque') cache.put(e.request, r.clone()); return r; })
      .catch(() => hit);
    return hit || net;
  }));
});
