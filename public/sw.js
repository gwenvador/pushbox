// Pushbox service worker: offline app shell, Web Push notifications,
// and the Android share target (POST /share).
const CACHE = 'pushbox-v2';
const SHELL = ['/', '/index.html', '/style.css', '/app.js', '/manifest.webmanifest', '/icons/icon.svg', '/icons/icon-192.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k !== CACHE) await caches.delete(k);
    await self.clients.claim();
  })());
});

function idb() {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open('pushbox', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('kv');
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}
async function idbSet(key, val) {
  const db = await idb();
  return new Promise((res, rej) => {
    const tx = db.transaction('kv', 'readwrite');
    tx.objectStore('kv').put(val, key);
    tx.oncomplete = () => res();
    tx.onerror = () => rej(tx.error);
  });
}

async function handleShare(request) {
  try {
    const form = await request.formData();
    await idbSet('pendingShare', {
      title: form.get('title') || '',
      text: form.get('text') || '',
      url: form.get('url') || '',
      files: form.getAll('files').filter((f) => f && typeof f !== 'string' && f.size > 0),
    });
  } catch (err) {
    console.warn('share intake failed', err);
  }
  return Response.redirect('/?share=1', 303);
}

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return;
  if (e.request.method === 'POST' && url.pathname === '/share') {
    e.respondWith(handleShare(e.request));
    return;
  }
  if (e.request.method !== 'GET' || url.pathname.startsWith('/api/') || url.pathname === '/ws') return;
  // App shell: network first so updates land immediately, cache as offline fallback.
  e.respondWith((async () => {
    try {
      const res = await fetch(e.request);
      if (res.ok && SHELL.includes(url.pathname)) {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy));
      }
      return res;
    } catch {
      const cached = await caches.match(e.request, { ignoreSearch: true });
      return cached || (e.request.mode === 'navigate' ? caches.match('/index.html') : Response.error());
    }
  })());
});

self.addEventListener('push', (e) => {
  let data = {};
  try { data = e.data.json(); } catch { data = { body: e.data?.text() }; }
  e.waitUntil((async () => {
    // The open, focused app already shows the push live.
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (wins.some((w) => w.focused && w.visibilityState === 'visible')) return;
    if (data.kind === 'mirror') {
      await self.registration.showNotification(`${data.app}${data.title ? ` · ${data.title}` : ''}`, {
        body: (data.body || '').slice(0, 500),
        tag: `mirror-${data.id}`,
        icon: '/icons/icon-192.png',
        badge: '/icons/badge-96.png',
        data: { mirror: data.id },
      });
      return;
    }
    const kind = data.type === 'file' ? `📎 ${data.file_name}` : data.type === 'link' ? '🔗 Link' : 'Note';
    const title = data.title || `${kind} from ${data.from}`;
    const body = data.type === 'link' ? [data.body, data.url].filter(Boolean).join('\n') : (data.type === 'file' ? (data.body && data.body !== data.file_name ? data.body : '') : data.body) || '';
    await self.registration.showNotification(title, {
      body: body.slice(0, 500),
      tag: data.id,
      icon: '/icons/icon-192.png',
      badge: '/icons/badge-96.png',
      data: { id: data.id, url: data.url, type: data.type },
      actions: data.type === 'link' ? [{ action: 'open-link', title: 'Open link' }] : [],
    });
  })());
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const { url, type } = e.notification.data || {};
  e.waitUntil((async () => {
    if (url && (type === 'link' || e.action === 'open-link')) return self.clients.openWindow(url);
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const app = wins.find((w) => new URL(w.url).origin === location.origin);
    if (app) return app.focus();
    return self.clients.openWindow('/');
  })());
});
