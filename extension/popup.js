import { api, getConfig, pushBlob, pushText, postDirect, DEFAULT_SERVER } from './api.js';
import { listFiles, removeFile, saveToDisk, getFile } from './store.js';

const $ = (s) => document.querySelector(s);
let me = null;
let devices = [];
let direct = {};
let files = [];

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'class') e.className = v;
    else e.setAttribute(k, v);
  }
  for (const c of children.flat()) if (c != null) e.append(c);
  return e;
}

const send = (msg) => chrome.runtime.sendMessage(msg);

function ago(ts) {
  const s = (Date.now() - ts) / 1000;
  if (s < 60) return 'now';
  if (s < 3600) return `${Math.round(s / 60)} min`;
  if (s < 86400) return `${Math.round(s / 3600)} h`;
  return new Date(ts).toLocaleDateString();
}

function status(text, ms = 2500) {
  $('#status').textContent = text;
  clearTimeout(status.t);
  if (ms) status.t = setTimeout(() => ($('#status').textContent = ''), ms);
}

async function copy(text) {
  await navigator.clipboard.writeText(text);
  status('Copied');
}

// ---------- Sign-in ----------
async function showSignIn() {
  $('#main').hidden = true;
  $('#signin').hidden = false;
  const { server } = await getConfig();
  $('#server').value = server;
}

$('#signinBtn').addEventListener('click', async () => {
  $('#signinError').textContent = '';
  let server = $('#server').value.trim().replace(/\/+$/, '') || DEFAULT_SERVER;
  try {
    if (!server) throw new Error('Enter the address of your Pushbox server');
    if (!/^https?:\/\//.test(server)) server = `https://${server}`;
    const origin = new URL(server).origin;
    // The extension may only talk to the server the user chose (asked once).
    const ok = await chrome.permissions.request({ origins: [`${origin}/*`] });
    if (!ok) throw new Error('Permission to reach the server was refused');
    $('#signinBtn').disabled = true;
    const r = await send({ type: 'signIn', server: origin });
    if (!r.ok) throw new Error(r.error);
    start();
  } catch (err) {
    $('#signinError').textContent = err.message;
  } finally {
    $('#signinBtn').disabled = false;
  }
});

// ---------- Tabs ----------
for (const b of document.querySelectorAll('.tab[data-tab]')) {
  b.addEventListener('click', () => {
    for (const x of document.querySelectorAll('.tab[data-tab]')) x.classList.toggle('active', x === b);
    for (const p of document.querySelectorAll('.pane')) p.hidden = p.id !== `tab-${b.dataset.tab}`;
    if (b.dataset.tab === 'phone') loadMirror();
  });
}

// ---------- Pushes ----------
const nameOf = (id) => devices.find((d) => d.id === id)?.name || 'API';

// ---------- Image previews ----------
const PREVIEWABLE = /^image\/(png|jpe?g|gif|webp|avif|bmp)$/i;
const MAX_PREVIEW = 15 * 1024 * 1024;
const previewUrls = new Map(); // push id -> object URL, for this popup session

// The popup is another origin than the website, so the image is fetched with
// the device token rather than through the site's cookie.
function serverImage(p) {
  const img = el('img', { class: 'thumb', alt: p.file_name, title: 'Open full size' });
  img.addEventListener('click', async () => {
    const { server } = await getConfig();
    chrome.tabs.create({ url: server + p.file_url }); // the site's shared session serves it
  });
  (async () => {
    if (!previewUrls.has(p.id)) {
      const { server, token } = await getConfig();
      const res = await fetch(server + p.file_url, { headers: { Authorization: `Bearer ${token}` } });
      if (!res.ok) throw new Error();
      previewUrls.set(p.id, URL.createObjectURL(await res.blob()));
    }
    img.src = previewUrls.get(p.id);
  })().catch(() => img.remove());
  return img;
}

function renderPush(p) {
  const head = el('div', { class: 'head' },
    el('span', {}, `${nameOf(p.source_device)} → ${p.target_device ? nameOf(p.target_device) : 'all'}`),
    el('time', {}, ago(p.created_at)));
  const item = el('div', { class: 'item', 'data-id': p.id }, head);
  if (p.title) item.append(el('div', { class: 'name' }, p.title));
  const actions = el('div', { class: 'actions' });
  if (p.type === 'link') {
    item.append(el('a', { href: p.url, target: '_blank' }, p.url));
    actions.append(el('button', { class: 'btn small', onclick: () => copy(p.url) }, 'Copy'));
  }
  if (p.type === 'file') {
    if (PREVIEWABLE.test(p.file_type || '') && p.file_size <= MAX_PREVIEW) item.append(serverImage(p));
    item.append(el('div', { class: 'name' }, `📎 ${p.file_name}`));
    actions.append(el('button', { class: 'btn small', onclick: () => send({ type: 'download', push: p }) }, 'Download'));
  }
  if (p.body) {
    item.append(el('div', { class: 'body' }, p.body));
    actions.append(el('button', { class: 'btn small', onclick: () => copy(p.body) }, 'Copy'));
  }
  // Removes the push from the history on every device.
  actions.append(el('button', {
    class: 'btn small ghost del', title: 'Remove from history', 'aria-label': 'Remove from history',
    onclick: async () => {
      item.classList.add('removing');
      try {
        await api(`/api/pushes/${p.id}`, { method: 'DELETE' });
        item.remove();
      } catch (e) {
        item.classList.remove('removing');
        status(`Remove failed: ${e.message}`, 5000);
      }
    },
  }, 'Remove'));
  item.append(actions);
  return item;
}

const fmtSize = (n) => (n < 1048576 ? `${Math.max(1, Math.round(n / 1024))} KB` : `${(n / 1048576).toFixed(1)} MB`);

// A file received directly from the phone, kept in the extension until saved.
// Preview of an image received directly (stored in the extension); click saves it.
function storedImage(f) {
  const img = el('img', { class: 'thumb', alt: f.name, title: 'Download' });
  img.addEventListener('click', () => saveToDisk(f.id).then(() => status(`Saved ${f.name}`), (e) => status(e.message, 5000)));
  getFile(f.id).then((full) => { img.src = URL.createObjectURL(full.blob); }, () => img.remove());
  return img;
}

function renderReceived(f) {
  return el('div', { class: 'item', 'data-id': `rx-${f.id}` },
    el('div', { class: 'head' }, el('span', {}, `${f.from} → this browser · ⚡ direct`), el('time', {}, ago(f.at))),
    PREVIEWABLE.test(f.mime || '') && f.size <= MAX_PREVIEW ? storedImage(f) : null,
    el('div', { class: 'name' }, `📎 ${f.name}`),
    el('div', { class: 'muted' }, `${fmtSize(f.size)} · kept in Pushbox, not downloaded yet`),
    el('div', { class: 'actions' },
      el('button', {
        class: 'btn small primary',
        onclick: async () => {
          try { await saveToDisk(f.id); status(`Saved ${f.name}`); } catch (e) { status(e.message, 5000); }
        },
      }, 'Download'),
      el('button', { class: 'btn small ghost del', onclick: async () => { await removeFile(f.id); loadPushes(); } }, 'Remove'),
    ));
}

async function loadPushes() {
  const [{ pushes }, files] = await Promise.all([api('/api/pushes?limit=15'), listFiles().catch(() => [])]);
  const items = [
    ...pushes.map((p) => ({ at: p.created_at, node: () => renderPush(p) })),
    ...files.map((f) => ({ at: f.at, node: () => renderReceived(f) })),
  ].sort((a, b) => b.at - a.at);
  $('#list').replaceChildren(...items.map((i) => i.node()));
}

function renderTargets() {
  const sel = $('#target');
  const prev = sel.value || 'all';
  sel.replaceChildren(el('option', { value: 'all' }, 'All devices'));
  for (const d of devices) {
    if (d.id === me.id || d.kind === 'api') continue;
    sel.append(el('option', { value: d.id }, d.name + (direct[d.id]?.addrs?.length ? '  ⚡' : '')));
  }
  sel.value = [...sel.options].some((o) => o.value === prev) ? prev : 'all';
}

$('#fileInput').addEventListener('change', (e) => {
  files.push(...e.target.files);
  e.target.value = '';
  $('#files').replaceChildren(...files.map((f) => el('span', {}, f.name)));
});

$('#text').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); $('#composer').requestSubmit(); }
});

$('#composer').addEventListener('submit', async (e) => {
  e.preventDefault();
  const text = $('#text').value.trim();
  const target = $('#target').value;
  if (!text && !files.length) return;
  $('#send').disabled = true;
  try {
    let how = 'Sent ✓';
    for (const f of files) {
      if (target !== 'all' && direct[target]?.addrs?.length) {
        status(`Sending ${f.name} directly…`, 0);
        const { offer } = await send({ type: 'directOffer', target, meta: { name: f.name, size: f.size, mime: f.type || 'application/octet-stream' } });
        if (await postDirect(offer, f)) { how = 'Sent directly ⚡'; continue; }
      }
      status(`Uploading ${f.name}…`, 0);
      await pushBlob(f, f.name, target);
    }
    if (text) await pushText(text, target);
    files = [];
    $('#files').replaceChildren();
    $('#text').value = '';
    status(how);
    loadPushes();
  } catch (err) {
    status(`Failed: ${err.message}`, 6000);
  } finally {
    $('#send').disabled = false;
  }
});

$('#pushTab').addEventListener('click', async () => {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  try {
    await api('/api/pushes', { method: 'POST', json: { url: tab.url, title: tab.title, target: $('#target').value } });
    status('Tab sent ✓');
    loadPushes();
  } catch (err) { status(`Failed: ${err.message}`, 6000); }
});

// ---------- Phone notifications ----------
async function loadMirror() {
  const { notifications } = await api('/api/mirror');
  const list = notifications.filter((n) => n.device !== me.id);
  $('#mirrorEmpty').hidden = list.length > 0;
  $('#mirrorList').replaceChildren(...list.map((n) => el('div', { class: 'item mirror', 'data-id': n.id },
    el('img', { src: n.icon || 'icons/icon-48.png', alt: '' }),
    el('div', { class: 'head' }, el('span', {}, `${n.app} · ${n.device_name}`), el('time', {}, ago(n.posted_at))),
    n.title ? el('div', { class: 'name' }, n.title) : null,
    n.text ? el('div', { class: 'body' }, n.text) : null,
  )));
}

// ---------- Settings ----------
$('#mirrorToggle').addEventListener('change', async (e) => {
  await chrome.storage.local.set({ mirrorOff: !e.target.checked });
  send({ type: 'badge' });
});
$('#signout').addEventListener('click', async () => {
  await send({ type: 'signOut' });
  showSignIn();
});

// ---------- Boot ----------
// Signed in, but the browser does not let the extension reach the server
// (e.g. after an update that moved the server out of the manifest).
async function needsAccess(cfg) {
  if (!cfg.token || !cfg.server) return false;
  const origin = new URL(cfg.server).origin;
  if (await chrome.permissions.contains({ origins: [`${origin}/*`] })) return false;
  $('#main').hidden = true;
  $('#signin').hidden = true;
  $('#grant').hidden = false;
  $('#grantHost').textContent = new URL(origin).host;
  $('#grantBtn').onclick = async () => {
    if (await chrome.permissions.request({ origins: [`${origin}/*`] })) { $('#grant').hidden = true; start(); }
  };
  return true;
}

async function start() {
  let cfg = await getConfig();
  if (await needsAccess(cfg)) return;
  // Already signed in on the website? Use that session instead of asking again.
  if (!cfg.token && (await send({ type: 'adopt' }))?.adopted) cfg = await getConfig();
  if (!cfg.token) return showSignIn();
  try {
    me = (await api('/api/me')).device;
  } catch {
    return showSignIn();
  }
  $('#signin').hidden = true;
  $('#main').hidden = false;
  $('#meName').textContent = me.name;
  $('#meServer').textContent = new URL(cfg.server).host;
  $('#openWeb').href = cfg.server;
  $('#webLink').href = cfg.server;
  $('#webLink').title = `Open ${new URL(cfg.server).host}`;
  const { mirrorOff } = await chrome.storage.local.get('mirrorOff');
  $('#mirrorToggle').checked = !mirrorOff;
  const st = await send({ type: 'status' });
  direct = st.direct || {};
  $('#dot').classList.toggle('on', !!st.connected);
  $('#dot').title = st.connected ? 'Connected' : 'Connecting…';
  devices = (await api('/api/devices')).devices;
  renderTargets();
  // Read where the unseen item is before marking everything seen.
  const { focus } = await chrome.storage.session.get('focus').catch(() => ({}));
  send({ type: 'seen' });
  await loadPushes();
  if (focus?.id) await showItem(focus.tab, focus.id);
  else $('#text').focus();
}

// Open the item's tab, scroll to it and highlight it.
async function showItem(tab, id) {
  if (tab === 'phone') {
    $('.tab[data-tab="phone"]').click();
    await loadMirror().catch(() => {});
  }
  const node = document.querySelector(`.item[data-id="${CSS.escape(id)}"]`);
  if (!node) return;
  node.scrollIntoView({ block: 'center' });
  node.classList.add('flash');
  setTimeout(() => node.classList.remove('flash'), 1800);
}

// Long texts scroll inside their card; a click shows them in full.
document.addEventListener('click', (e) => {
  const body = e.target.closest('.item .body');
  if (body && !window.getSelection().toString()) body.classList.toggle('open');
});

start();
