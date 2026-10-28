// Pushbox extension worker: keeps a WebSocket to the server, turns pushes and
// mirrored phone notifications into desktop notifications, and owns the
// right-click "Push to…" menu. Works without any browser push service.
import { api, getConfig, pushBlob, pushText, deviceName, postDirect, reachable } from './api.js';
import { addFile } from './store.js';

let ws = null;
let pinger = null;
let devices = [];
let direct = {}; // deviceId -> { can, addrs }
const offers = new Map(); // offer id -> resolve
const phoneNotifs = new Map(); // mirrored notification id -> notification still showing on the phone

// The newest unseen item: the popup opens on it (its tab, scrolled, highlighted).
async function setFocus(tab, id) {
  await chrome.storage.session.set({ focus: { tab, id } });
}

// Desktop notification clicked: show that item in the popup.
async function openPopupAt(tab, id) {
  await setFocus(tab, id);
  // The resizable window is open: bring it forward on that item.
  const { winId } = await chrome.storage.local.get('winId');
  if (winId) {
    try {
      await chrome.windows.update(winId, { focused: true });
      await chrome.runtime.sendMessage({ target: 'window', type: 'show', tab, id });
      return true;
    } catch { /* closed meanwhile: fall back to the popup */ }
  }
  try { await chrome.action.openPopup(); return true; } catch { return false; }
}

// Badge = what has not been seen yet: unread pushes + phone notifications that
// arrived since the popup was last opened and are still showing on the phone.
async function unseenPhone() {
  const { seenMirror = [] } = await chrome.storage.session.get('seenMirror');
  const seen = new Set(seenMirror);
  return [...phoneNotifs.keys()].filter((id) => !seen.has(id)).length;
}

async function markPhoneSeen(id, seen) {
  const { seenMirror = [] } = await chrome.storage.session.get('seenMirror');
  const set = new Set(seenMirror);
  if (seen) set.add(id); else set.delete(id);
  await chrome.storage.session.set({ seenMirror: [...set].slice(-500) });
}

async function updateBadge() {
  const { unread = 0 } = await chrome.storage.session.get('unread');
  const { mirrorOff } = await chrome.storage.local.get('mirrorOff');
  const n = (mirrorOff ? 0 : await unseenPhone()) + unread;
  chrome.action.setBadgeText({ text: n ? String(n > 99 ? '99+' : n) : '' });
  chrome.action.setBadgeBackgroundColor({ color: unread ? '#4f46e5' : '#64748b' });
  chrome.action.setTitle({ title: `Pushbox · ${phoneNotifs.size} phone notification${phoneNotifs.size === 1 ? '' : 's'}${unread ? `, ${unread} unread` : ''}` });
}

async function addUnread(k) {
  const { unread = 0 } = await chrome.storage.session.get('unread');
  await chrome.storage.session.set({ unread: unread + k });
  updateBadge();
}

// What is showing on the phone right now (after a reconnect).
async function syncPhoneNotifs() {
  const { device } = await getConfig();
  const { notifications } = await api('/api/mirror');
  phoneNotifs.clear();
  for (const n of notifications) if (n.device !== device?.id) phoneNotifs.set(n.id, n);
  updateBadge();
}
const pushes = new Map(); // notificationId -> push, for click handling

// ---------- Shared session with the website ----------
// The website's session is the httpOnly pb_token cookie. The extension keeps it
// equal to its own token, so the site and the extension are one signed-in device.
const COOKIE = 'pb_token';

async function setSessionCookie(server, token) {
  await chrome.cookies.set({
    url: `${server}/`, name: COOKIE, value: token, path: '/',
    secure: server.startsWith('https:'), httpOnly: true, sameSite: 'strict',
    expirationDate: Math.floor(Date.now() / 1000) + 5 * 365 * 86400,
  });
}

async function clearSessionCookie(server) {
  await chrome.cookies.remove({ url: `${server}/`, name: COOKIE }).catch(() => {});
}

/** Use the session the user opened on the website (signed in there first). */
async function adoptSessionCookie() {
  const { server, token: current, device: old } = await getConfig();
  const c = await chrome.cookies.get({ url: `${server}/`, name: COOKIE });
  if (!c?.value || c.value === current) return false;
  const res = await fetch(`${server}/api/me`, { headers: { Authorization: `Bearer ${c.value}` } });
  if (!res.ok) return false;
  const { device } = await res.json();
  // Signed in separately before: retire the extension's own device, keep one.
  if (current && old?.id && old.id !== device.id) {
    await fetch(`${server}/api/devices/${old.id}`, { method: 'DELETE', headers: { Authorization: `Bearer ${c.value}` } }).catch(() => {});
  }
  await chrome.storage.local.set({ server, token: c.value, device });
  ws?.close();
  ws = null;
  connect();
  buildMenus();
  return true;
}

chrome.cookies.onChanged.addListener(({ cookie, removed, cause }) => {
  if (cookie.name !== COOKIE) return;
  (async () => {
    const { server, token } = await getConfig();
    if (new URL(server).hostname !== cookie.domain.replace(/^\./, '')) return;
    if (!removed) return adoptSessionCookie(); // signed in on the website
    // Signed out on the website (the device is gone): sign out here too.
    // ('overwrite' is just a new value replacing it: handled by the add event.)
    if (cause !== 'overwrite' && cookie.value === token) await signOut({ remote: false });
  })().catch(() => {});
});

// ---------- Sign-in ----------
async function signIn(server) {
  const redirect = chrome.identity.getRedirectURL(); // https://<id>.chromiumapp.org/
  const qs = new URLSearchParams({ name: deviceName(), kind: 'desktop', return_to: redirect });
  const result = await chrome.identity.launchWebAuthFlow({ url: `${server}/auth/login?${qs}`, interactive: true });
  const h = new URLSearchParams(new URL(result).hash.slice(1));
  if (h.get('login_error')) throw new Error(h.get('login_error'));
  const token = h.get('token');
  if (!token) throw new Error('No token returned');
  await chrome.storage.local.set({ server, token });
  const { device } = await api('/api/me');
  await chrome.storage.local.set({ device });
  await setSessionCookie(server, token); // the website is now signed in too
  connect();
  return device;
}

async function signOut({ remote = true } = {}) {
  const { server } = await getConfig();
  if (remote) await api('/api/logout', { method: 'POST' }).catch(() => {});
  await clearSessionCookie(server); // signs the website out too
  await chrome.storage.local.remove(['token', 'device']);
  ws?.close();
  ws = null;
  chrome.action.setBadgeText({ text: '' });
  buildMenus();
}

// ---------- WebSocket ----------
async function connect() {
  const { server, token } = await getConfig();
  if (!token || (ws && ws.readyState <= 1)) return;
  const url = `${server.replace(/^http/, 'ws')}/ws?caps=direct`;
  const sock = new WebSocket(url, ['pushbox', token]);
  ws = sock;
  sock.onopen = () => {
    clearInterval(pinger);
    // Activity every 20 s keeps the MV3 worker alive while connected (Chrome 116+).
    pinger = setInterval(() => sock.readyState === 1 && sock.send('ping'), 20_000);
    chrome.action.setTitle({ title: 'Pushbox · connected' });
    catchUp().catch(() => {});
    syncPhoneNotifs().catch(() => {});
  };
  sock.onmessage = (ev) => {
    if (ev.data === 'pong') return;
    handle(JSON.parse(ev.data)).catch((e) => console.warn(e));
  };
  sock.onclose = () => {
    clearInterval(pinger);
    if (ws === sock) ws = null;
    chrome.action.setTitle({ title: 'Pushbox · reconnecting…' });
    setTimeout(connect, 5000);
  };
}

async function handle(msg) {
  const { device } = await getConfig();
  if (msg.type === 'devices') {
    devices = msg.devices;
    direct = msg.direct || {};
    buildMenus();
  } else if (msg.type === 'signal') {
    const d = msg.data || {};
    if (d.kind === 'accept') offers.get(d.id)?.({ path: d.path, addrs: direct[msg.from]?.addrs || [] });
    if (d.kind === 'file') receiveDirect(msg.from, d);
  } else if (msg.type === 'push') {
    const p = msg.push;
    await markSeen(p.created_at);
    if (p.source_device === device?.id) return;
    if (p.target_device && p.target_device !== device?.id) return;
    await notifyPush(p);
    await setFocus('pushes', p.id);
    await addUnread(1);
    // A link arriving live opens in a background tab (not for the catch-up on reconnect).
    const { linksOff } = await chrome.storage.local.get('linksOff');
    if (!linksOff && p.type === 'link' && /^https?:\/\//.test(p.url || '')) {
      chrome.tabs.create({ url: p.url, active: false });
    }
  } else if (msg.type === 'delete') {
    chrome.notifications.clear(`push-${msg.id}`);
  } else if (msg.type === 'mirror') {
    const n = msg.notification;
    if (n.device === device?.id) return;
    phoneNotifs.set(n.id, n);
    await markPhoneSeen(n.id, false); // new, or new content in an existing one
    await setFocus('phone', n.id);
    updateBadge();
    const { mirrorOff } = await chrome.storage.local.get('mirrorOff');
    if (mirrorOff) return;
    chrome.notifications.create(`mirror-${n.id}`, {
      type: 'basic',
      iconUrl: n.icon || 'icons/icon-128.png',
      title: n.title || n.app,
      message: n.text || '',
      contextMessage: `${n.app} · ${n.device_name}`,
      silent: !!msg.update,
      priority: 1,
    });
  } else if (msg.type === 'mirror_dismiss') {
    phoneNotifs.delete(msg.id);
    updateBadge();
    chrome.notifications.clear(`mirror-${msg.id}`);
  }
}

async function markSeen(ts) {
  const { lastPushAt = 0 } = await chrome.storage.local.get('lastPushAt');
  if (ts > lastPushAt) await chrome.storage.local.set({ lastPushAt: ts });
}

// Pushes that arrived while the browser was closed: notify them on reconnect.
async function catchUp() {
  const { device } = await getConfig();
  const { lastPushAt = 0 } = await chrome.storage.local.get('lastPushAt');
  const { pushes: list } = await api('/api/pushes?limit=20');
  if (!list.length) return;
  await markSeen(list[0].created_at);
  if (!lastPushAt) return; // first run: nothing counts as missed
  const missed = list.filter((p) => p.created_at > lastPushAt && p.source_device !== device?.id
    && (!p.target_device || p.target_device === device?.id)).reverse().slice(-10);
  for (const p of missed) await notifyPush(p);
  if (missed.length) {
    await addUnread(missed.length);
  }
}

function senderName(id) {
  return devices.find((d) => d.id === id)?.name || 'Pushbox';
}

async function notifyPush(p) {
  const { pushesOff } = await chrome.storage.local.get('pushesOff');
  if (pushesOff) return; // still listed in the popup and counted in the badge
  const id = `push-${p.id}`;
  pushes.set(id, p);
  const buttons = [];
  let message = p.body || '';
  if (p.type === 'link') { message = [p.body, p.url].filter(Boolean).join('\n'); buttons.push({ title: 'Open link' }); }
  if (p.type === 'file') { message = [p.file_name, p.body].filter(Boolean).join('\n'); buttons.push({ title: 'Download' }); }
  if (p.body || p.type === 'link') buttons.push({ title: 'Copy' });
  chrome.notifications.create(id, {
    type: 'basic',
    iconUrl: 'icons/icon-128.png',
    title: p.title || (p.type === 'file' ? `File from ${senderName(p.source_device)}` : p.type === 'link' ? `Link from ${senderName(p.source_device)}` : `Note from ${senderName(p.source_device)}`),
    message: message.slice(0, 1000),
    buttons: buttons.slice(0, 2),
    requireInteraction: p.type === 'file',
    priority: 2,
  });
}

async function downloadFile(p) {
  const { server, token } = await getConfig();
  await chrome.downloads.download({
    url: `${server}${p.file_url}?download`,
    filename: p.file_name,
    headers: [{ name: 'Authorization', value: `Bearer ${token}` }],
  });
}

// Clipboard and blob URLs need a document: use an offscreen page.
async function offscreen() {
  if (!(await chrome.offscreen.hasDocument())) {
    await chrome.offscreen.createDocument({
      url: 'offscreen.html', reasons: ['CLIPBOARD', 'BLOBS'],
      justification: 'Copy pushed text and save received files',
    });
  }
}

async function copyToClipboard(text) {
  await offscreen();
  await chrome.runtime.sendMessage({ target: 'offscreen', type: 'copy', text });
}

// Save a file kept in the extension (direct transfer) to the Downloads folder.
async function saveStored(id) {
  await offscreen();
  const r = await chrome.runtime.sendMessage({ target: 'offscreen', type: 'blobUrl', id });
  if (!r?.url) throw new Error(r?.error || 'File no longer available');
  const downloadId = await chrome.downloads.download({ url: r.url, filename: r.name, conflictAction: 'uniquify' });
  const done = (delta) => {
    if (delta.id !== downloadId || !delta.state || delta.state.current === 'in_progress') return;
    chrome.downloads.onChanged.removeListener(done);
    chrome.runtime.sendMessage({ target: 'offscreen', type: 'revoke', url: r.url });
  };
  chrome.downloads.onChanged.addListener(done);
}

chrome.notifications.onButtonClicked.addListener(async (id, index) => {
  const p = pushes.get(id);
  if (!p) return;
  const actions = [];
  if (p.type === 'link') actions.push('open');
  if (p.type === 'file') actions.push('download');
  if (p.body || p.type === 'link') actions.push('copy');
  const a = actions[index];
  if (a === 'open') chrome.tabs.create({ url: p.url });
  if (a === 'download') downloadFile(p);
  if (a === 'copy') copyToClipboard(p.type === 'link' && !p.body ? p.url : p.body || p.url);
  chrome.notifications.clear(id);
});

// Clicking a notification opens the popup on that item (the buttons do the actions).
chrome.notifications.onClicked.addListener(async (id) => {
  const p = pushes.get(id);
  chrome.notifications.clear(id);
  if (id.startsWith('mirror-')) return openPopupAt('phone', id.slice(7));
  if (id.startsWith('rx-')) return openPopupAt('pushes', id);
  if (id.startsWith('push-')) {
    const shown = await openPopupAt('pushes', id.slice(5));
    if (!shown && p?.type === 'link') chrome.tabs.create({ url: p.url }); // no popup possible: old behaviour
  }
});

// Closing a phone notification here closes it on the other desktops too.
chrome.notifications.onClosed.addListener((id, byUser) => {
  pushes.delete(id);
  if (byUser && id.startsWith('mirror-')) api(`/api/mirror/${id.slice(7)}`, { method: 'DELETE' }).catch(() => {});
});

// ---------- Direct transfers ----------
const rid = () => crypto.getRandomValues(new Uint32Array(2)).join('');

// Ask the phone for an upload slot; null if it is not reachable for direct transfers.
function directOffer(target, meta) {
  if (!ws || ws.readyState !== 1 || !direct[target]?.addrs?.length) return Promise.resolve(null);
  const id = rid();
  return new Promise((resolve) => {
    const done = (v) => { offers.delete(id); resolve(v); };
    offers.set(id, done);
    ws.send(JSON.stringify({ type: 'signal', to: target, data: { kind: 'offer', id, ...meta } }));
    setTimeout(() => done(null), 5000);
  });
}

async function sendBlob(blob, name, target) {
  if (target !== 'all') {
    const offer = await directOffer(target, { name, size: blob.size, mime: blob.type || 'application/octet-stream' });
    if (await postDirect(offer, blob)) return 'direct';
  }
  await pushBlob(blob, name, target);
  return 'server';
}

// The phone offers a file it serves itself: fetch it straight from the phone and
// keep it in the extension; the user saves it from the popup or the notification.
async function receiveDirect(from, d) {
  const fail = () => ws?.send(JSON.stringify({ type: 'signal', to: from, data: { kind: 'fail', id: d.id } }));
  const url = await reachable(d.urls || []);
  if (!url) return fail(); // the phone then sends it through the server
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const name = String(d.name || 'file').replace(/[\\/:*?"<>|]/g, '_');
    await addFile({ id: d.id, name, mime: d.mime || blob.type, from: senderName(from), blob });
    chrome.notifications.create(`rx-${d.id}`, {
      type: 'basic', iconUrl: 'icons/icon-128.png', priority: 2,
      title: `File from ${senderName(from)} ⚡`,
      message: `${name} · ${(blob.size / 1048576).toFixed(blob.size < 1048576 ? 2 : 1)} MB, sent directly`,
      contextMessage: 'Kept in Pushbox until you download it',
      buttons: [{ title: 'Download' }],
      requireInteraction: true,
    });
    await setFocus('pushes', `rx-${d.id}`);
    await addUnread(1);
  } catch (err) {
    console.warn('direct receive failed', err);
    // The phone sees the transfer break and retries through the server.
  }
}

chrome.notifications.onButtonClicked.addListener((id) => {
  if (!id.startsWith('rx-')) return;
  saveStored(id.slice(3)).catch((e) => chrome.notifications.create({ type: 'basic', iconUrl: 'icons/icon-128.png', title: 'Download failed', message: e.message }));
  chrome.notifications.clear(id);
});

// ---------- Context menu ----------
// Rebuilds can be triggered back to back (device updates); run them one at a
// time so removeAll() always finishes before the items are created again.
let menuChain = Promise.resolve();
function buildMenus() {
  menuChain = menuChain.then(rebuildMenus, rebuildMenus);
  return menuChain;
}

async function rebuildMenus() {
  await chrome.contextMenus.removeAll();
  const { token, device } = await getConfig();
  if (!token) return;
  const contexts = ['page', 'link', 'image', 'selection', 'video', 'audio'];
  const add = (item) => new Promise((resolve) => chrome.contextMenus.create(item, () => { void chrome.runtime.lastError; resolve(); }));
  await add({ id: 'root', title: 'Push to', contexts });
  await add({ id: 'to:all', parentId: 'root', title: 'All devices', contexts });
  const others = devices.filter((d) => d.id !== device?.id && d.kind !== 'api');
  if (others.length) await add({ id: 'sep', parentId: 'root', type: 'separator', contexts });
  for (const d of others) await add({ id: `to:${d.id}`, parentId: 'root', title: d.name, contexts });
}

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (!String(info.menuItemId).startsWith('to:')) return;
  const target = String(info.menuItemId).slice(3);
  try {
    if (info.selectionText) {
      await pushText(info.selectionText, target);
    } else if (info.mediaType === 'image' && info.srcUrl) {
      try {
        const res = await fetch(info.srcUrl);
        if (!res.ok) throw new Error();
        const blob = await res.blob();
        const name = decodeURIComponent(new URL(info.srcUrl).pathname.split('/').pop() || 'image') || 'image';
        const ext = (blob.type.split('/')[1] || 'png').replace('jpeg', 'jpg');
        await sendBlob(blob, /\.[a-z0-9]{2,5}$/i.test(name) ? name : `${name}.${ext}`, target);
      } catch {
        await pushText(info.srcUrl, target); // no access to the image host: send its URL
      }
    } else if (info.linkUrl) {
      await pushText(info.linkUrl, target);
    } else if (info.srcUrl) {
      await pushText(info.srcUrl, target);
    } else {
      await api('/api/pushes', { method: 'POST', json: { url: tab.url, title: tab.title, target } });
    }
    chrome.action.setBadgeText({ text: '✓', tabId: tab?.id });
    setTimeout(() => chrome.action.setBadgeText({ text: '', tabId: tab?.id }), 1500);
  } catch (err) {
    chrome.notifications.create({ type: 'basic', iconUrl: 'icons/icon-128.png', title: 'Push failed', message: err.message });
  }
});

// ---------- Messages from the popup ----------
chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (msg.target === 'offscreen') return;
  (async () => {
    if (msg.type === 'signIn') return { device: await signIn(msg.server) };
    if (msg.type === 'signOut') return signOut();
    if (msg.type === 'adopt') return { adopted: await adoptSessionCookie() };
    if (msg.type === 'status') return { connected: ws?.readyState === 1, devices, direct };
    if (msg.type === 'badge') return updateBadge();
    if (msg.type === 'directOffer') return { offer: await directOffer(msg.target, msg.meta) };
    if (msg.type === 'download') return downloadFile(msg.push);
    if (msg.type === 'seen') {
      await chrome.storage.session.set({ unread: 0, seenMirror: [...phoneNotifs.keys()], focus: null });
      updateBadge();
    }
  })().then((r) => reply({ ok: true, ...r }), (e) => reply({ ok: false, error: e.message }));
  return true;
});

// Access to the server granted again from the popup: reconnect.
chrome.permissions.onAdded.addListener(() => { connect(); buildMenus(); });

// The resizable Pushbox window was closed: forget it.
chrome.windows.onRemoved.addListener(async (id) => {
  const { winId } = await chrome.storage.local.get('winId');
  if (id === winId) chrome.storage.local.remove('winId');
});

// ---------- Lifecycle ----------
// The alarm restarts the worker (and the socket) if the browser stopped it.
chrome.alarms.create('keepalive', { periodInMinutes: 0.5 });
chrome.alarms.onAlarm.addListener(() => connect());
chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(() => { buildMenus(); connect(); });

(async () => {
  const { server, token } = await getConfig();
  if (!token) { await adoptSessionCookie().catch(() => false); return; }
  // Keep the website signed in with the same session (e.g. after clearing cookies).
  const c = await chrome.cookies.get({ url: `${server}/`, name: COOKIE }).catch(() => null);
  if (c?.value !== token) {
    // The site had its own session (a separate device): retire it, the extension's wins.
    if (c?.value) {
      const other = await fetch(`${server}/api/me`, { headers: { Authorization: `Bearer ${c.value}` } }).catch(() => null);
      const { device } = await getConfig();
      const otherId = other?.ok ? (await other.json()).device.id : null;
      if (otherId && otherId !== device?.id) await api(`/api/devices/${otherId}`, { method: 'DELETE' }).catch(() => {});
    }
    await setSessionCookie(server, token).catch(() => {});
  }
  try { devices = (await api('/api/devices')).devices; } catch {}
  buildMenus();
  connect();
})();
