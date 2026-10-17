// Pushbox client: login, live feed over WebSocket, composer with file upload,
// Web Push subscription and Android share-target intake.
// The session is the httpOnly pb_token cookie, shared with the browser extension:
// signing in on either one signs in both, as a single device.
const $ = (s) => document.querySelector(s);
const state = {
  signedIn: false,
  me: null,
  vapidKey: null,
  maxUploadMb: 1024,
  devices: [],
  online: new Set(),
  pushes: [],
  files: [],
  ws: null,
  oldest: null,
};

// ---------- API ----------
async function api(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (opts.json !== undefined) {
    headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(opts.json);
  }
  const res = await fetch(path, { ...opts, headers });
  if (res.status === 401 && !path.endsWith('/login')) { signedOut(); throw new Error('Signed out'); }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

function upload(file, target, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const qs = new URLSearchParams({ name: file.name || 'file', target, type: file.type || 'application/octet-stream' });
    xhr.open('POST', `/api/upload?${qs}`);
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
    xhr.onload = () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText); } catch {}
      xhr.status < 300 ? resolve(data) : reject(new Error(data.error || `HTTP ${xhr.status}`));
    };
    xhr.onerror = () => reject(new Error('Network error'));
    xhr.send(file);
  });
}

// ---------- Utils ----------
function toast(msg, ms = 2600) {
  const t = $('#toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.hidden = true), ms);
}

function fmtSize(n) {
  if (n == null) return '';
  const u = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(i && n < 10 ? 1 : 0)} ${u[i]}`;
}

function fmtTime(ts) {
  const d = new Date(ts);
  const diff = (Date.now() - ts) / 1000;
  if (diff < 45) return 'just now';
  if (diff < 3600) return `${Math.round(diff / 60)} min ago`;
  const sameDay = d.toDateString() === new Date().toDateString();
  return sameDay
    ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'class') e.className = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c != null) e.append(c);
  return e;
}

// Text with clickable http(s) links, built from DOM nodes (no innerHTML).
function linkify(text) {
  const frag = document.createDocumentFragment();
  const re = /https?:\/\/[^\s<>"']+/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    let url = m[0].replace(/[).,;:!?]+$/, '');
    frag.append(text.slice(last, m.index));
    frag.append(el('a', { href: url, target: '_blank', rel: 'noopener noreferrer' }, url));
    last = m.index + url.length;
    re.lastIndex = last;
  }
  frag.append(text.slice(last));
  return frag;
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast('Copied');
  } catch {
    const ta = el('textarea', { style: 'position:fixed;opacity:0' });
    ta.value = text;
    document.body.append(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
    toast('Copied');
  }
}

function guessDeviceName() {
  const ua = navigator.userAgent;
  const os = /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad' : /Android/.test(ua) ? 'Android'
    : /Mac OS X/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : 'Device';
  const br = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : '';
  const standalone = matchMedia('(display-mode: standalone)').matches;
  return standalone || !br ? os : `${os} ${br}`;
}

const isPhone = () => /Android|iPhone|iPad|Mobile/.test(navigator.userAgent);
const deviceName = (id) => state.devices.find((d) => d.id === id)?.name || (id ? 'Removed device' : 'API');

// ---------- Auth ----------
function signedOut() {
  state.signedIn = false;
  state.ws?.close();
  $('#main').hidden = true;
  $('#login').hidden = false;
  $('#loginName').value ||= localStorage.getItem('pb_device_name') || guessDeviceName();
  loadAuthConfig();
}

async function loadAuthConfig() {
  try {
    const cfg = await fetch('/auth/config').then((r) => r.json());
    const btn = $('#oidcBtn');
    btn.hidden = !cfg.oidc;
    if (cfg.oidc) btn.textContent = `Sign in with ${cfg.oidc.name}`;
    $('#passwordBox').hidden = !cfg.password;
    $('#loginPassword').required = !!cfg.password && !cfg.oidc;
  } catch {
    $('#loginError').textContent = 'Server unreachable';
  }
}

$('#oidcBtn').addEventListener('click', () => {
  const name = $('#loginName').value.trim() || guessDeviceName();
  localStorage.setItem('pb_device_name', name);
  location.href = `/auth/login?${new URLSearchParams({ name, kind: isPhone() ? 'phone' : 'desktop' })}`;
});

// Back from the identity provider: the server already set the session cookie;
// just drop /#token=… from the address bar (or show /#login_error=…).
function takeAuthFragment() {
  const h = new URLSearchParams(location.hash.slice(1));
  if (!h.has('token') && !h.has('login_error')) return;
  history.replaceState(null, '', location.pathname + location.search);
  if (h.get('login_error')) setTimeout(() => ($('#loginError').textContent = h.get('login_error')), 0);
}

$('#loginForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('#loginError').textContent = '';
  if ($('#passwordBox').hidden || !$('#loginPassword').value) return $('#loginPassword').focus();
  try {
    const data = await api('/api/login', {
      method: 'POST',
      json: { password: $('#loginPassword').value, name: $('#loginName').value, kind: isPhone() ? 'phone' : 'desktop' },
    });
    $('#loginPassword').value = ''; // the server set the session cookie
    start();
  } catch (err) {
    $('#loginError').textContent = err.message;
  }
});

// ---------- Rendering ----------
function renderTargets() {
  const sel = $('#target');
  const prev = sel.value || 'all';
  sel.replaceChildren(el('option', { value: 'all' }, 'All devices'));
  for (const d of state.devices) {
    if (d.id === state.me.id || d.kind === 'api') continue;
    sel.append(el('option', { value: d.id }, d.name));
  }
  sel.value = [...sel.options].some((o) => o.value === prev) ? prev : 'all';
}

function renderDevices() {
  const list = $('#deviceList');
  list.replaceChildren();
  for (const d of state.devices) {
    const online = state.online.has(d.id);
    const meta = [
      d.kind,
      d.id === state.me.id ? 'this device' : online ? 'online' : `seen ${fmtTime(d.last_seen)}`,
      d.push_enabled ? 'notifications on' : null,
    ].filter(Boolean).join(' · ');
    list.append(el('li', {},
      el('span', { class: `dot${online ? ' on' : ''}` }),
      el('div', { class: 'info' }, el('div', { class: 'name' }, d.name), el('div', { class: 'meta' }, meta)),
      d.id === state.me.id ? null : el('button', {
        class: 'btn small danger',
        onclick: async () => {
          if (!confirm(`Remove "${d.name}"? It will be signed out.`)) return;
          await api(`/api/devices/${d.id}`, { method: 'DELETE' }).catch((e) => toast(e.message));
        },
      }, 'Remove'),
    ));
  }
}

function renderPush(p) {
  const mine = p.source_device === state.me.id;
  const route = `${deviceName(p.source_device)} → ${p.target_device ? deviceName(p.target_device) : 'all devices'}`;
  const card = el('article', { class: `card push${mine ? ' mine' : ''}`, 'data-id': p.id });
  const time = el('time', { datetime: new Date(p.created_at).toISOString(), title: new Date(p.created_at).toLocaleString(), 'data-ts': p.created_at }, fmtTime(p.created_at));
  card.append(el('div', { class: 'push-head' }, el('span', { class: 'route' }, route), time));

  if (p.title) card.append(el('div', { class: 'push-title' }, p.title));
  const actions = el('div', { class: 'push-actions' });

  if (p.type === 'link') {
    card.append(el('a', { class: 'push-link', href: p.url, target: '_blank', rel: 'noopener noreferrer' }, p.url));
    actions.append(
      el('a', { class: 'btn', href: p.url, target: '_blank', rel: 'noopener noreferrer' }, 'Open'),
      el('button', { class: 'btn', onclick: () => copyText(p.url) }, 'Copy link'),
    );
  }
  if (p.type === 'file') {
    const t = p.file_type || '';
    if (/^image\/(png|jpe?g|gif|webp|avif|bmp)/.test(t)) {
      card.append(el('a', { href: p.file_url, target: '_blank' }, el('img', { class: 'preview', src: p.file_url, alt: p.file_name, loading: 'lazy' })));
    } else if (t.startsWith('video/')) {
      card.append(el('video', { class: 'preview', src: p.file_url, controls: true, preload: 'metadata' }));
    } else if (t.startsWith('audio/')) {
      card.append(el('audio', { src: p.file_url, controls: true, preload: 'none', style: 'width:100%' }));
    }
    const ext = (p.file_name.match(/\.([a-z0-9]{1,4})$/i)?.[1] || 'file');
    card.append(el('div', { class: 'file-row' },
      el('div', { class: 'ext' }, ext),
      el('div', { style: 'min-width:0' }, el('div', { class: 'name' }, p.file_name), el('div', { class: 'meta' }, `${fmtSize(p.file_size)} · ${t}`)),
    ));
    actions.append(
      el('a', { class: 'btn primary', href: `${p.file_url}?download`, download: p.file_name }, 'Download'),
      el('a', { class: 'btn', href: p.file_url, target: '_blank' }, 'Open'),
    );
  }
  if (p.body) {
    card.append(el('div', { class: 'push-body' }, linkify(p.body)));
    actions.append(el('button', { class: 'btn', onclick: () => copyText(p.body) }, 'Copy'));
  }
  actions.append(el('button', {
    class: 'btn ghost del', title: 'Delete', 'aria-label': 'Delete',
    onclick: () => api(`/api/pushes/${p.id}`, { method: 'DELETE' }).catch((e) => toast(e.message)),
  }, 'Delete'));
  card.append(actions);
  return card;
}

function renderFeed() {
  const feed = $('#feed');
  feed.replaceChildren(...state.pushes.map(renderPush));
  $('#empty').hidden = state.pushes.length > 0;
}

function addPush(p, atTop = true) {
  if (state.pushes.some((x) => x.id === p.id)) return;
  if (atTop) {
    state.pushes.unshift(p);
    $('#feed').prepend(renderPush(p));
  } else {
    state.pushes.push(p);
    $('#feed').append(renderPush(p));
  }
  $('#empty').hidden = true;
}

setInterval(() => {
  for (const t of document.querySelectorAll('time[data-ts]')) t.textContent = fmtTime(Number(t.dataset.ts));
}, 30_000);

// ---------- Data ----------
async function loadPushes(more = false) {
  const qs = new URLSearchParams({ limit: 30 });
  if (more && state.oldest) qs.set('before', state.oldest);
  const { pushes } = await api(`/api/pushes?${qs}`);
  if (!more) { state.pushes = []; $('#feed').replaceChildren(); }
  for (const p of pushes) addPush(p, false);
  state.oldest = pushes.at(-1)?.created_at ?? state.oldest;
  $('#moreBtn').hidden = pushes.length < 30;
  $('#empty').hidden = state.pushes.length > 0;
}

$('#moreBtn').addEventListener('click', () => loadPushes(true).catch((e) => toast(e.message)));

function setDevices(devices, online) {
  state.devices = devices;
  if (online) state.online = new Set(online);
  const me = devices.find((d) => d.id === state.me.id);
  if (me) { state.me = me; $('#meName').textContent = me.name; }
  renderTargets();
  renderDevices();
  // Device names appear in the feed routes.
  for (const card of document.querySelectorAll('.push')) {
    const p = state.pushes.find((x) => x.id === card.dataset.id);
    if (p) card.querySelector('.route').textContent = `${deviceName(p.source_device)} → ${p.target_device ? deviceName(p.target_device) : 'all devices'}`;
  }
}

// ---------- WebSocket ----------
let backoff = 1000;
function connect() {
  if (!state.signedIn) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws`); // authenticated by the session cookie
  state.ws = ws;
  let pinger;
  ws.onopen = () => {
    backoff = 1000;
    $('#connDot').classList.add('on');
    $('#connDot').title = 'Connected';
    pinger = setInterval(() => ws.readyState === 1 && ws.send('ping'), 25_000);
    loadPushes().catch(() => {}); // catch up on anything missed while offline
  };
  ws.onmessage = (ev) => {
    if (ev.data === 'pong') return;
    const msg = JSON.parse(ev.data);
    if (msg.type === 'push') {
      addPush(msg.push);
      if (msg.push.source_device !== state.me.id && document.hidden === false) toast(`New push from ${deviceName(msg.push.source_device)}`);
    } else if (msg.type === 'delete') {
      state.pushes = state.pushes.filter((p) => p.id !== msg.id);
      document.querySelector(`.push[data-id="${CSS.escape(msg.id)}"]`)?.remove();
      $('#empty').hidden = state.pushes.length > 0;
    } else if (msg.type === 'clear') {
      state.pushes = [];
      renderFeed();
    } else if (msg.type === 'mirror') {
      const n = msg.notification;
      if (n.device !== state.me.id && !msg.update && !document.hidden) toast(`📱 ${n.app}: ${[n.title, n.text].filter(Boolean).join(' — ')}`, 5000);
    } else if (msg.type === 'devices') {
      setDevices(msg.devices, msg.online);
    }
  };
  ws.onclose = (ev) => {
    clearInterval(pinger);
    $('#connDot').classList.remove('on');
    $('#connDot').title = 'Offline, reconnecting…';
    if (ev.code === 4001) return signedOut();
    if (state.ws !== ws || !state.signedIn) return;
    setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 30_000);
  };
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && state.signedIn && (!state.ws || state.ws.readyState > 1)) connect();
});

// ---------- Composer ----------
function renderAttachments() {
  const box = $('#attachments');
  box.replaceChildren(...state.files.map((f, i) => el('span', { class: 'chip' },
    el('span', {}, `${f.name} (${fmtSize(f.size)})`),
    el('button', { type: 'button', 'aria-label': `Remove ${f.name}`, onclick: () => { state.files.splice(i, 1); renderAttachments(); } }, '✕'),
  )));
}

function addFiles(list) {
  for (const f of list) {
    if (f.size > state.maxUploadMb * 1024 * 1024) { toast(`${f.name} is larger than ${state.maxUploadMb} MB`); continue; }
    state.files.push(f);
  }
  renderAttachments();
}

$('#fileInput').addEventListener('change', (e) => { addFiles(e.target.files); e.target.value = ''; });

$('#text').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); $('#composer').requestSubmit(); }
});

document.addEventListener('paste', (e) => {
  if ($('#main').hidden) return;
  const files = [...(e.clipboardData?.files || [])];
  if (files.length) {
    e.preventDefault();
    addFiles(files.map((f, i) => f.name && f.name !== 'image.png' ? f
      : new File([f], `pasted-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}${i ? '-' + i : ''}.${(f.type.split('/')[1] || 'bin').replace('jpeg', 'jpg')}`, { type: f.type })));
  }
});

let dragDepth = 0;
window.addEventListener('dragenter', (e) => {
  if (!e.dataTransfer?.types.includes('Files') || $('#main').hidden) return;
  dragDepth++;
  $('#dropOverlay').hidden = false;
});
window.addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; $('#dropOverlay').hidden = true; } });
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('drop', (e) => {
  e.preventDefault();
  dragDepth = 0;
  $('#dropOverlay').hidden = true;
  if (e.dataTransfer?.files.length) addFiles(e.dataTransfer.files);
});

$('#composer').addEventListener('submit', async (e) => {
  e.preventDefault();
  const text = $('#text').value.trim();
  const target = $('#target').value;
  if (!text && !state.files.length) return $('#text').focus();
  const btn = $('#sendBtn');
  btn.disabled = true;
  const prog = $('#progress');
  try {
    const files = [...state.files];
    for (let i = 0; i < files.length; i++) {
      const f = files[i];
      prog.hidden = false;
      await upload(f, target, (r) => {
        prog.firstElementChild.style.transform = `scaleX(${r})`;
        prog.lastElementChild.textContent = `${Math.round(r * 100)}% · ${files.length > 1 ? `${i + 1}/${files.length} · ` : ''}${f.name}`;
      });
      state.files.shift();
      renderAttachments();
    }
    if (text) await api('/api/pushes', { method: 'POST', json: { body: text, target } });
    $('#text').value = '';
    toast('Sent');
  } catch (err) {
    toast(`Send failed: ${err.message}`, 5000);
  } finally {
    btn.disabled = false;
    prog.hidden = true;
    prog.firstElementChild.style.transform = 'scaleX(0)';
  }
});

// ---------- Notifications (Web Push) ----------
function urlB64ToUint8(b64) {
  const pad = '='.repeat((4 - (b64.length % 4)) % 4);
  const raw = atob((b64 + pad).replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}

const pushSupported = () => 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;

// Resolves to null instead of hanging when the service worker could not register.
async function swRegistration() {
  if (!pushSupported()) return null;
  return Promise.race([navigator.serviceWorker.ready, new Promise((r) => setTimeout(() => r(null), 4000))]);
}

async function currentSubscription() {
  const reg = await swRegistration();
  return reg ? reg.pushManager.getSubscription() : null;
}

async function enableNotifications() {
  if (!pushSupported()) {
    const ios = /iPhone|iPad/.test(navigator.userAgent);
    toast(ios ? 'On iPhone, add Pushbox to the Home Screen first (Share → Add to Home Screen), then open it from there.' : 'This browser does not support push notifications.', 6000);
    return;
  }
  const perm = await Notification.requestPermission();
  if (perm !== 'granted') { toast('Notifications were blocked in the browser settings.'); return; }
  const reg = await swRegistration();
  if (!reg) { toast('The service worker is not running (Pushbox must be served over HTTPS).', 6000); return; }
  let sub = await reg.pushManager.getSubscription();
  if (!sub) {
    try {
      sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: urlB64ToUint8(state.vapidKey) });
    } catch (err) {
      // Chromium builds without Google services (Brave by default, Helium, ungoogled…) have no push service.
      if (/push service/i.test(err.message)) {
        toast(/Brave/.test(navigator.userAgent) || navigator.brave
          ? 'Brave blocks push by default: enable "Use Google services for push messaging" in brave://settings/privacy, restart Brave, then try again.'
          : 'This browser has no push service (Chromium without Google services). Use Chrome, Edge, Firefox or Safari for notifications; live updates still work while the tab is open.', 12000);
        return;
      }
      throw err;
    }
  }
  await api('/api/push-subscription', { method: 'POST', json: { subscription: sub.toJSON() } });
  toast('Notifications on');
  refreshNotifUi();
}

async function disableNotifications() {
  const sub = await currentSubscription();
  await sub?.unsubscribe();
  await api('/api/push-subscription', { method: 'POST', json: { subscription: null } });
  toast('Notifications off');
  refreshNotifUi();
}

async function refreshNotifUi() {
  const sub = await currentSubscription().catch(() => null);
  const on = !!sub && Notification.permission === 'granted';
  $('#notifState').textContent = on ? 'Notifications are on' : 'Notifications are off';
  $('#notifToggle').textContent = on ? 'Turn off' : 'Turn on';
  $('#notifToggle').onclick = () => (on ? disableNotifications() : enableNotifications()).catch((e) => toast(e.message));
  $('#notifBanner').hidden = on || localStorage.getItem('pb_notif_dismissed') === '1';
  // Keep the server copy fresh (subscriptions can rotate).
  if (on) api('/api/push-subscription', { method: 'POST', json: { subscription: sub.toJSON() } }).catch(() => {});
}

$('#notifEnable').addEventListener('click', () => enableNotifications().catch((e) => toast(e.message)));
$('#notifDismiss').addEventListener('click', () => { localStorage.setItem('pb_notif_dismissed', '1'); $('#notifBanner').hidden = true; });

// ---------- Settings ----------
$('#settingsBtn').addEventListener('click', () => {
  $('#renameInput').value = state.me.name;
  renderDevices();
  refreshNotifUi();
  $('#settings').showModal();
});
$('#settings').addEventListener('click', (e) => {
  if (e.target === e.currentTarget || e.target.closest('[data-close]')) $('#settings').close();
});

$('#renameForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  await api(`/api/devices/${state.me.id}`, { method: 'PATCH', json: { name: $('#renameInput').value } }).catch((err) => toast(err.message));
  toast('Renamed');
});

$('#tokenForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    const { token } = await api('/api/devices', { method: 'POST', json: { name: $('#tokenName').value || 'API' } });
    $('#tokenValue').textContent = token;
    $('#tokenExample').textContent =
`# text or link
curl -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" \\
  -d '{"body":"hello from the terminal"}' ${location.origin}/api/pushes

# file
curl -H "Authorization: Bearer ${token}" --data-binary @photo.jpg \\
  "${location.origin}/api/upload?name=photo.jpg"`;
    $('#tokenOut').hidden = false;
    $('#tokenCopy').onclick = () => copyText(token);
    $('#tokenName').value = '';
  } catch (err) { toast(err.message); }
});

$('#clearAll').addEventListener('click', async () => {
  if (!confirm('Delete every push and stored file on all devices?')) return;
  await api('/api/pushes', { method: 'DELETE' }).catch((e) => toast(e.message));
});

$('#logout').addEventListener('click', async () => {
  if (!confirm('Sign out and remove this device?')) return;
  await disableNotifications().catch(() => {});
  await api('/api/logout', { method: 'POST' }).catch(() => {}); // also signs out the extension
  $('#settings').close();
  signedOut();
});

// ---------- IndexedDB (shared with the service worker) ----------
function idb() {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open('pushbox', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('kv');
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}
async function idbGet(key) {
  const db = await idb();
  return new Promise((res, rej) => {
    const r = db.transaction('kv').objectStore('kv').get(key);
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}
async function idbSet(key, val) {
  const db = await idb();
  return new Promise((res, rej) => {
    const tx = db.transaction('kv', 'readwrite');
    val == null ? tx.objectStore('kv').delete(key) : tx.objectStore('kv').put(val, key);
    tx.oncomplete = () => res();
    tx.onerror = () => rej(tx.error);
  });
}

// Android share sheet → service worker stores the share → we prefill the composer.
async function takeSharedContent() {
  const params = new URLSearchParams(location.search);
  if (!params.has('share')) return;
  history.replaceState(null, '', '/');
  const share = await idbGet('pendingShare').catch(() => null);
  await idbSet('pendingShare', null).catch(() => {});
  if (!share) return;
  // Some apps send "Title" + "Title https://…": keep each piece once.
  $('#text').value = dedupeShareText(share);
  if (share.files?.length) addFiles(share.files);
  toast('Choose a device and press Send');
  $('#sendBtn').focus();
}

function dedupeShareText({ title, text, url }) {
  const parts = [];
  for (const v of [title, text, url]) {
    if (!v) continue;
    if (parts.some((p) => p.includes(v))) continue;
    for (let i = parts.length - 1; i >= 0; i--) if (v.includes(parts[i])) parts.splice(i, 1);
    parts.push(v);
  }
  return parts.join('\n');
}

// ---------- Boot ----------
async function start() {
  try {
    const me = await api('/api/me');
    state.me = me.device;
    state.vapidKey = me.vapid_public_key;
    state.maxUploadMb = me.max_upload_mb;
  } catch (err) {
    if (err.message !== 'Signed out') { // server unreachable, not signed out
      toast('Server unreachable, retrying…', 4000);
      setTimeout(start, 5000);
    }
    return;
  }
  state.signedIn = true;
  $('#login').hidden = true;
  $('#main').hidden = false;
  $('#meName').textContent = state.me.name;
  const { devices, online } = await api('/api/devices');
  setDevices(devices, online);
  await loadPushes();
  connect();
  refreshNotifUi();
  takeSharedContent();
}

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch((e) => console.warn('SW registration failed', e));
}

// Before the shared cookie session, the page kept its own token (a separate
// device). Turn it into the cookie session, or retire it if the extension's
// session is already there.
async function migrateOldToken() {
  const old = localStorage.getItem('pb_token');
  if (!old) return;
  localStorage.removeItem('pb_token');
  const shared = await fetch('/api/me');
  if (shared.ok) {
    const sharedId = (await shared.json()).device.id;
    const mine = await fetch('/api/me', { headers: { Authorization: `Bearer ${old}` } });
    const oldId = mine.ok ? (await mine.json()).device.id : null;
    if (oldId && oldId !== sharedId) await fetch(`/api/devices/${oldId}`, { method: 'DELETE' });
  } else {
    await fetch('/api/session', { method: 'POST', headers: { Authorization: `Bearer ${old}` } });
  }
}

takeAuthFragment();
migrateOldToken().catch(() => {}).finally(start);
