// Pushbox: self-hosted Pushbullet. One user, many devices.
// Text, links and files pushed from any device show up on all the others,
// live over WebSocket and as Web Push notifications when the app is closed.
import express from 'express';
import { WebSocketServer } from 'ws';
import webpush from 'web-push';
import { DatabaseSync } from 'node:sqlite';
import { createServer } from 'node:http';
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { rm, stat } from 'node:fs/promises';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createOidc } from './oidc.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = Number(process.env.PORT || 8080);
const DATA_DIR = path.resolve(process.env.DATA_DIR || path.join(__dirname, 'data'));
const FILES_DIR = path.join(DATA_DIR, 'files');
const PASSWORD = process.env.PUSHBOX_PASSWORD || '';
const MAX_UPLOAD_MB = Number(process.env.MAX_UPLOAD_MB || 1024);
const RETENTION_DAYS = Number(process.env.RETENTION_DAYS || 0); // 0 = keep forever
const VAPID_SUBJECT = process.env.VAPID_SUBJECT || 'mailto:admin@localhost';
const TRUST_PROXY = process.env.TRUST_PROXY !== '0';
const PUBLIC_URL = (process.env.PUBLIC_URL || '').replace(/\/$/, '');
const OIDC_ISSUER = process.env.OIDC_ISSUER || '';
const OIDC_CLIENT_ID = process.env.OIDC_CLIENT_ID || '';
const OIDC_CLIENT_SECRET = process.env.OIDC_CLIENT_SECRET || '';
const OIDC_NAME = process.env.OIDC_NAME || 'Pocket ID';
const listEnv = (v) => (v || '').split(',').map((x) => x.trim().toLowerCase()).filter(Boolean);
const OIDC_ALLOWED_EMAILS = listEnv(process.env.OIDC_ALLOWED_EMAILS);
const OIDC_ALLOWED_GROUPS = listEnv(process.env.OIDC_ALLOWED_GROUPS);
const OIDC_ENABLED = !!(OIDC_ISSUER && OIDC_CLIENT_ID);
// Where sign-in may hand the token back besides the web app:
// the browser extension (chrome.identity) and the Android app (custom scheme).
const EXTENSION_IDS = listEnv(process.env.EXTENSION_IDS || 'majdpknfbefcphglmfjgednickilefmb');
function allowedReturnTo(v) {
  if (!v) return null;
  const m = String(v).match(/^https:\/\/([a-p]{32})\.chromiumapp\.org\/$/);
  if (m && EXTENSION_IDS.includes(m[1])) return v;
  if (v === 'pushbox://auth') return v;
  return undefined;
}

if (!PASSWORD && !OIDC_ENABLED) {
  console.error('Set OIDC_ISSUER + OIDC_CLIENT_ID (and PUBLIC_URL), or PUSHBOX_PASSWORD. Refusing to start without sign-in.');
  process.exit(1);
}
if (OIDC_ENABLED && !PUBLIC_URL) {
  console.error('PUBLIC_URL is required with OIDC (e.g. https://push.example.com).');
  process.exit(1);
}

mkdirSync(FILES_DIR, { recursive: true });

// ---------- Database ----------
const db = new DatabaseSync(path.join(DATA_DIR, 'pushbox.db'));
db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA foreign_keys = ON;
  CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'browser',
    token_hash TEXT NOT NULL UNIQUE,
    push_sub TEXT,
    created_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS pushes (
    id TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    source_device TEXT,
    target_device TEXT,
    type TEXT NOT NULL,
    title TEXT,
    body TEXT,
    url TEXT,
    file_name TEXT,
    file_type TEXT,
    file_size INTEGER
  );
  CREATE INDEX IF NOT EXISTS pushes_created ON pushes(created_at DESC);
  CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT);
`);
if (!db.prepare('PRAGMA table_info(devices)').all().some((c) => c.name === 'owner')) {
  db.exec('ALTER TABLE devices ADD COLUMN owner TEXT');
}

const q = {
  deviceByToken: db.prepare('SELECT * FROM devices WHERE token_hash = ?'),
  deviceById: db.prepare('SELECT * FROM devices WHERE id = ?'),
  devices: db.prepare('SELECT * FROM devices ORDER BY created_at'),
  insertDevice: db.prepare('INSERT INTO devices (id, name, kind, token_hash, created_at, last_seen, owner) VALUES (?, ?, ?, ?, ?, ?, ?)'),
  getSetting: db.prepare('SELECT value FROM settings WHERE key = ?'),
  setSetting: db.prepare('INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'),
  touchDevice: db.prepare('UPDATE devices SET last_seen = ? WHERE id = ?'),
  renameDevice: db.prepare('UPDATE devices SET name = ? WHERE id = ?'),
  setPushSub: db.prepare('UPDATE devices SET push_sub = ? WHERE id = ?'),
  deleteDevice: db.prepare('DELETE FROM devices WHERE id = ?'),
  insertPush: db.prepare(`INSERT INTO pushes (id, created_at, source_device, target_device, type, title, body, url, file_name, file_type, file_size)
                          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`),
  pushById: db.prepare('SELECT * FROM pushes WHERE id = ?'),
  pushesPage: db.prepare('SELECT * FROM pushes WHERE created_at < ? ORDER BY created_at DESC LIMIT ?'),
  deletePush: db.prepare('DELETE FROM pushes WHERE id = ?'),
  allFilePushes: db.prepare("SELECT id FROM pushes WHERE type = 'file'"),
  deleteAllPushes: db.prepare('DELETE FROM pushes'),
  expiredPushes: db.prepare('SELECT id, type FROM pushes WHERE created_at < ?'),
};

// ---------- Helpers ----------
const newId = () => randomBytes(9).toString('base64url');
const hashToken = (t) => createHash('sha256').update(t).digest('hex');
const now = () => Date.now();
const URL_ONLY = /^\s*(https?:\/\/[^\s]+)\s*$/i;

function safeEqual(a, b) {
  const ha = createHash('sha256').update(String(a)).digest();
  const hb = createHash('sha256').update(String(b)).digest();
  return timingSafeEqual(ha, hb);
}

function publicDevice(d) {
  return {
    id: d.id, name: d.name, kind: d.kind, owner: d.owner || null,
    created_at: d.created_at, last_seen: d.last_seen,
    push_enabled: !!d.push_sub,
  };
}

function publicPush(p) {
  const out = {
    id: p.id, created_at: p.created_at, type: p.type,
    source_device: p.source_device, target_device: p.target_device,
    title: p.title, body: p.body, url: p.url,
  };
  if (p.type === 'file') {
    out.file_name = p.file_name;
    out.file_type = p.file_type;
    out.file_size = p.file_size;
    out.file_url = `/api/files/${p.id}/${encodeURIComponent(p.file_name)}`;
  }
  return out;
}

function cleanName(s, fallback) {
  const v = String(s ?? '').replace(/[\u0000-\u001f]/g, '').trim().slice(0, 60);
  return v || fallback;
}

function cleanFileName(s) {
  const base = path.basename(String(s || '').replace(/\\/g, '/'));
  const v = base.replace(/[\u0000-\u001f<>:"|?*]/g, '_').trim().slice(0, 200);
  return v && v !== '.' && v !== '..' ? v : 'file';
}

const filePath = (id) => path.join(FILES_DIR, id);

// ---------- VAPID (Web Push) ----------
const vapidFile = path.join(DATA_DIR, 'vapid.json');
let vapid;
if (existsSync(vapidFile)) {
  vapid = JSON.parse(readFileSync(vapidFile, 'utf8'));
} else {
  vapid = webpush.generateVAPIDKeys();
  writeFileSync(vapidFile, JSON.stringify(vapid, null, 2), { mode: 0o600 });
}
webpush.setVapidDetails(VAPID_SUBJECT, vapid.publicKey, vapid.privateKey);

// ---------- Live connections ----------
const sockets = new Map(); // ws -> deviceId

function broadcast(msg) {
  const data = JSON.stringify(msg);
  for (const ws of sockets.keys()) {
    if (ws.readyState === ws.OPEN) ws.send(data);
  }
}

function connectedDeviceIds() {
  return [...new Set(sockets.values())];
}

// Devices that can do direct transfers, with the local addresses they listen on
// (the Android app announces LAN / Tailscale URLs; the extension only downloads/uploads).
function directPeers() {
  const out = {};
  for (const [ws, id] of sockets) {
    if (!ws.direct) continue;
    out[id] = { ...(out[id] || {}), can: true, ...(ws.addrs?.length ? { addrs: ws.addrs } : {}) };
  }
  return out;
}

function broadcastDevices() {
  broadcast({ type: 'devices', devices: q.devices.all().map(publicDevice), online: connectedDeviceIds(), direct: directPeers() });
}

async function notify(push) {
  const source = push.source_device ? q.deviceById.get(push.source_device) : null;
  const payload = JSON.stringify({
    id: push.id,
    type: push.type,
    from: source?.name || 'API',
    title: push.title,
    body: push.type === 'file' ? (push.body || push.file_name) : push.body,
    url: push.url,
    file_name: push.file_name,
  });
  for (const d of q.devices.all()) {
    if (!d.push_sub || d.id === push.source_device) continue;
    if (push.target_device && push.target_device !== d.id) continue;
    try {
      await webpush.sendNotification(JSON.parse(d.push_sub), payload, { TTL: 60 * 60 * 24, urgency: 'high' });
    } catch (err) {
      if (err.statusCode === 404 || err.statusCode === 410) {
        q.setPushSub.run(null, d.id); // subscription gone
      } else {
        console.warn(`web push to ${d.name} failed:`, err.statusCode || err.message);
      }
    }
  }
}

function publish(push) {
  broadcast({ type: 'push', push: publicPush(push) });
  notify(push).catch((e) => console.warn('notify failed', e));
}

// ---------- App ----------
const app = express();
app.disable('x-powered-by');
if (TRUST_PROXY) app.set('trust proxy', true);

app.use((req, res, next) => {
  res.set('X-Content-Type-Options', 'nosniff');
  res.set('Referrer-Policy', 'no-referrer');
  next();
});

function tokenFrom(req) {
  const h = req.get('authorization');
  if (h?.startsWith('Bearer ')) return h.slice(7).trim();
  const cookie = req.get('cookie') || '';
  const m = cookie.match(/(?:^|;\s*)pb_token=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : null;
}

function deviceFromToken(token) {
  if (!token) return null;
  return q.deviceByToken.get(hashToken(token)) || null;
}

function auth(req, res, next) {
  const device = deviceFromToken(tokenFrom(req));
  if (!device) return res.status(401).json({ error: 'unauthorized' });
  // Cookie-authenticated writes must come from our own origin (CSRF).
  if (!req.get('authorization') && !['GET', 'HEAD'].includes(req.method)) {
    const origin = req.get('origin');
    if (origin && new URL(origin).host !== req.get('host')) {
      return res.status(403).json({ error: 'bad origin' });
    }
  }
  if (now() - device.last_seen > 60_000) q.touchDevice.run(now(), device.id);
  req.device = device;
  next();
}

function setTokenCookie(req, res, token) {
  res.cookie('pb_token', token, {
    httpOnly: true, sameSite: 'strict', secure: req.secure,
    maxAge: 1000 * 60 * 60 * 24 * 365 * 5, path: '/',
  });
}

function createDevice(name, kind, owner, fallbackName = 'New device') {
  const token = randomBytes(32).toString('base64url');
  const id = newId();
  const t = now();
  const k = ['phone', 'desktop', 'browser', 'api'].includes(kind) ? kind : 'browser';
  q.insertDevice.run(id, cleanName(name, fallbackName), k, hashToken(token), t, t, owner || null);
  broadcastDevices();
  return { token, device: q.deviceById.get(id) };
}

// Login rate limit: 10 attempts / 10 min per IP.
const attempts = new Map();
function loginLimited(ip) {
  const t = now();
  const list = (attempts.get(ip) || []).filter((x) => t - x < 10 * 60_000);
  attempts.set(ip, list);
  return list.length >= 10;
}

app.get('/auth/config', (req, res) => {
  res.json({ password: !!PASSWORD, oidc: OIDC_ENABLED ? { name: OIDC_NAME } : null });
});

app.post('/api/login', express.json({ limit: '10kb' }), (req, res) => {
  if (!PASSWORD) return res.status(403).json({ error: 'password sign-in is disabled' });
  const ip = req.ip;
  if (loginLimited(ip)) return res.status(429).json({ error: 'too many attempts, wait a few minutes' });
  const { password, name, kind } = req.body || {};
  if (!password || !safeEqual(password, PASSWORD)) {
    attempts.get(ip).push(now());
    return res.status(401).json({ error: 'wrong password' });
  }
  const { token, device } = createDevice(name, kind, 'password');
  setTokenCookie(req, res, token);
  res.json({ token, device: publicDevice(device) });
});

// ---------- OIDC ----------
const oidc = !OIDC_ENABLED ? null : createOidc({
  issuer: OIDC_ISSUER, clientId: OIDC_CLIENT_ID, clientSecret: OIDC_CLIENT_SECRET,
  redirectUri: `${PUBLIC_URL}/auth/callback`,
});

// Who may sign in: the allowlists if set, otherwise the first account that
// signs in becomes the owner and is the only one accepted afterwards.
function oidcAllowed(claims) {
  const email = String(claims.email || '').toLowerCase();
  const groups = (Array.isArray(claims.groups) ? claims.groups : []).map((g) => String(g).toLowerCase());
  if (OIDC_ALLOWED_EMAILS.length || OIDC_ALLOWED_GROUPS.length) {
    return (email && claims.email_verified !== false && OIDC_ALLOWED_EMAILS.includes(email))
      || groups.some((g) => OIDC_ALLOWED_GROUPS.includes(g));
  }
  const owner = q.getSetting.get('oidc_owner_sub')?.value;
  if (!owner) {
    q.setSetting.run('oidc_owner_sub', claims.sub);
    console.log(`OIDC: ${email || claims.sub} is now the owner (first sign-in)`);
    return true;
  }
  return owner === claims.sub;
}

// intent:// links are what Chromium browsers on Android (Chrome, Brave…)
// reliably hand to an app; a bare pushbox:// redirect is often ignored.
const androidIntent = (params) =>
  `intent://auth?${new URLSearchParams(params)}#Intent;scheme=pushbox;package=io.github.gwenvador.pushbox;end`;

// Chrome on Android may refuse to follow a redirect to an app scheme, so the
// Android app gets a page with a button (a tap always opens the app).
function appHandoff(res, url) {
  const safe = url.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
  res.set('Cache-Control', 'no-store');
  res.set('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'");
  res.type('html').send(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pushbox</title><body style="font:16px system-ui;display:grid;place-items:center;min-height:90vh;margin:0;background:#0f1116;color:#e8eaf0">
<div style="text-align:center"><p>Signed in. Return to the app.</p>
<a href="${safe}" style="display:inline-block;background:#7c74ff;color:#fff;padding:14px 22px;border-radius:12px;text-decoration:none;font-weight:600">Open Pushbox</a></div>
<script>location.href=${JSON.stringify(url).replace(/</g, '\\u003c')}</script>`);
}

const authError = (res, msg) => res.redirect(303, `/#login_error=${encodeURIComponent(msg)}`);

app.get('/auth/handoff', (req, res) => {
  const h = handoffs.get(String(req.query.code || ''));
  handoffs.delete(String(req.query.code || ''));
  if (!h || h.expires < now() || !deviceFromToken(h.token)) return authError(res, 'This sign-in link has expired, open it again from the app');
  setTokenCookie(req, res, h.token);
  res.redirect(303, '/');
});

app.get('/auth/login', async (req, res) => {
  if (!oidc) return res.status(404).send('OIDC is not configured');
  const returnTo = allowedReturnTo(req.query.return_to);
  if (returnTo === undefined) return res.status(400).send('return_to not allowed');
  try {
    const { url, state } = await oidc.authorizationUrl({
      name: String(req.query.name || '').slice(0, 60),
      kind: String(req.query.kind || ''),
      returnTo,
    });
    // Lax so the cookie comes back on the top-level redirect from the IdP.
    res.cookie('pb_oidc', state, { httpOnly: true, sameSite: 'lax', secure: req.secure, maxAge: 10 * 60_000, path: '/auth' });
    res.redirect(303, url);
  } catch (err) {
    console.warn('OIDC login:', err.message);
    authError(res, `${OIDC_NAME} is unreachable`);
  }
});

app.get('/auth/callback', async (req, res) => {
  if (!oidc) return res.status(404).send('OIDC is not configured');
  const cookieState = (req.get('cookie') || '').match(/(?:^|;\s*)pb_oidc=([^;]+)/)?.[1];
  res.clearCookie('pb_oidc', { path: '/auth' });
  if (req.query.error) return authError(res, String(req.query.error_description || req.query.error));
  const { code, state } = req.query;
  if (!code || !state || state !== cookieState) return authError(res, 'Sign-in expired or was started elsewhere, try again');
  try {
    const { claims, extra } = await oidc.callback({ code: String(code), state: String(state) });
    const back = extra.returnTo || '/';
    if (!oidcAllowed(claims)) {
      console.warn(`OIDC: refused ${claims.email || claims.sub}`);
      const errUrl = `${back}#login_error=${encodeURIComponent(`${claims.email || 'This account'} is not allowed to use Pushbox`)}`;
      return back.startsWith('pushbox:') ? appHandoff(res, androidIntent({ login_error: `${claims.email || 'This account'} is not allowed to use Pushbox` })) : res.redirect(303, errUrl);
    }
    const { token } = createDevice(extra.name, extra.kind, claims.email || claims.preferred_username || claims.sub);
    if (!extra.returnTo) setTokenCookie(req, res, token);
    // The token travels in the fragment: never sent to a server or written to logs.
    if (back.startsWith('pushbox:')) return appHandoff(res, androidIntent({ token }));
    res.redirect(303, `${back}#token=${token}`);
  } catch (err) {
    console.warn('OIDC callback:', err.message);
    authError(res, 'Sign-in failed, try again');
  }
});

app.use('/api', auth);
app.use('/api', express.json({ limit: '2mb' }));

// Turn a bearer token into the browser session cookie (used to migrate pages
// that kept their own token before the session was shared with the extension).
app.post('/api/session', (req, res) => {
  setTokenCookie(req, res, tokenFrom(req));
  res.json({ ok: true });
});

// Sign the phone's browser in with the app's session (one login per device):
// the app gets a single-use link valid 60 s, the browser trades it for the cookie.
const handoffs = new Map(); // code -> { token, expires }

app.post('/api/handoff', (req, res) => {
  for (const [k, v] of handoffs) if (v.expires < now()) handoffs.delete(k);
  const code = randomBytes(24).toString('base64url');
  handoffs.set(code, { token: tokenFrom(req), expires: now() + 60_000 });
  res.json({ url: `/auth/handoff?code=${code}` });
});

app.get('/api/me', (req, res) => {
  res.json({ device: publicDevice(req.device), vapid_public_key: vapid.publicKey, max_upload_mb: MAX_UPLOAD_MB });
});

app.post('/api/logout', (req, res) => {
  q.deleteDevice.run(req.device.id);
  res.clearCookie('pb_token', { path: '/' });
  broadcastDevices();
  res.json({ ok: true });
});

app.get('/api/devices', (req, res) => {
  res.json({ devices: q.devices.all().map(publicDevice), online: connectedDeviceIds(), direct: directPeers() });
});

app.patch('/api/devices/:id', (req, res) => {
  const d = q.deviceById.get(req.params.id);
  if (!d) return res.status(404).json({ error: 'not found' });
  q.renameDevice.run(cleanName(req.body?.name, d.name), d.id);
  broadcastDevices();
  res.json({ device: publicDevice(q.deviceById.get(d.id)) });
});

app.delete('/api/devices/:id', (req, res) => {
  const d = q.deviceById.get(req.params.id);
  if (!d) return res.status(404).json({ error: 'not found' });
  q.deleteDevice.run(d.id);
  for (const [ws, id] of sockets) if (id === d.id) ws.close(4001, 'device removed');
  broadcastDevices();
  res.json({ ok: true });
});

// New API-only device (for scripts, iOS Shortcuts, curl). Returns its token once.
app.post('/api/devices', (req, res) => {
  const { token, device } = createDevice(req.body?.name, 'api', req.device.owner, 'API');
  res.json({ token, device: publicDevice(device) });
});

app.post('/api/push-subscription', (req, res) => {
  const sub = req.body?.subscription;
  if (sub && (typeof sub.endpoint !== 'string' || !sub.keys)) return res.status(400).json({ error: 'bad subscription' });
  q.setPushSub.run(sub ? JSON.stringify(sub) : null, req.device.id);
  broadcastDevices();
  res.json({ ok: true });
});

app.get('/api/pushes', (req, res) => {
  const before = Number(req.query.before) || Number.MAX_SAFE_INTEGER;
  const limit = Math.min(Number(req.query.limit) || 50, 200);
  res.json({ pushes: q.pushesPage.all(before, limit).map(publicPush) });
});

function resolveTarget(t) {
  if (!t || t === 'all') return null;
  return q.deviceById.get(t) ? t : undefined;
}

// Text / link push.
app.post('/api/pushes', (req, res) => {
  const { title, body, url, target } = req.body || {};
  const tgt = resolveTarget(target);
  if (tgt === undefined) return res.status(400).json({ error: 'unknown target device' });
  let text = String(body ?? '').slice(0, 100_000);
  let link = url ? String(url).slice(0, 4000) : null;
  let pageTitle = title ? String(title).slice(0, 300) : null;
  if (!link) {
    const m = text.match(URL_ONLY);
    if (m) { link = m[1]; text = ''; }
  }
  // Share sheets send "Page title\nhttps://…": a short text ending with its
  // only URL is a link whose title is the text before it.
  if (!link) {
    const m = text.match(/^([^\n]{0,300}?)\s*\n?\s*(https?:\/\/\S+)\s*$/);
    if (m && !/https?:\/\//.test(m[1])) { link = m[2]; pageTitle ||= m[1].trim() || null; text = ''; }
  }
  if (link && !/^https?:\/\//i.test(link)) return res.status(400).json({ error: 'url must be http(s)' });
  if (!text.trim() && !link && !title) return res.status(400).json({ error: 'empty push' });
  const p = {
    id: newId(), created_at: now(), source_device: req.device.id, target_device: tgt,
    type: link ? 'link' : 'note', title: pageTitle,
    body: text || null, url: link, file_name: null, file_type: null, file_size: null,
  };
  q.insertPush.run(p.id, p.created_at, p.source_device, p.target_device, p.type, p.title, p.body, p.url, null, null, null);
  publish(p);
  res.status(201).json({ push: publicPush(p) });
});

// File push: raw request body, metadata in the query string.
//   curl -H "Authorization: Bearer $T" --data-binary @photo.jpg "https://host/api/upload?name=photo.jpg"
app.post('/api/upload', async (req, res) => {
  const tgt = resolveTarget(req.query.target);
  if (tgt === undefined) return res.status(400).json({ error: 'unknown target device' });
  const name = cleanFileName(req.query.name || req.get('x-file-name'));
  const max = MAX_UPLOAD_MB * 1024 * 1024;
  const declared = Number(req.get('content-length'));
  if (declared > max) return res.status(413).json({ error: `file larger than ${MAX_UPLOAD_MB} MB` });

  const id = newId();
  const dest = filePath(id);
  let size = 0;
  const limiter = new Transform({
    transform(chunk, _enc, cb) {
      size += chunk.length;
      if (size > max) return cb(Object.assign(new Error('too large'), { code: 'TOO_LARGE' }));
      cb(null, chunk);
    },
  });
  try {
    await pipeline(req, limiter, createWriteStream(dest, { mode: 0o600 }));
  } catch (err) {
    await rm(dest, { force: true });
    if (err.code === 'TOO_LARGE') return res.status(413).json({ error: `file larger than ${MAX_UPLOAD_MB} MB` });
    if (!res.headersSent) res.status(400).json({ error: 'upload interrupted' });
    return;
  }
  if (size === 0 && !req.query.allow_empty) {
    await rm(dest, { force: true });
    return res.status(400).json({ error: 'empty file' });
  }
  let type = String(req.query.type || req.get('content-type') || 'application/octet-stream').split(';')[0].trim().slice(0, 100);
  if (type === 'application/x-www-form-urlencoded') type = 'application/octet-stream';
  const body = req.query.body ? String(req.query.body).slice(0, 10_000) : null;
  const p = {
    id, created_at: now(), source_device: req.device.id, target_device: tgt,
    type: 'file', title: null, body, url: null, file_name: name, file_type: type, file_size: size,
  };
  q.insertPush.run(p.id, p.created_at, p.source_device, p.target_device, 'file', null, body, null, name, type, size);
  publish(p);
  res.status(201).json({ push: publicPush(p) });
});

// Inline display only for types that cannot run script in our origin.
const INLINE_TYPES = /^(image\/(png|jpe?g|gif|webp|avif|heic|heif|bmp)|video\/|audio\/|application\/pdf$|text\/plain$)/i;

app.get('/api/files/:id/:name', async (req, res) => {
  const p = q.pushById.get(req.params.id);
  if (!p || p.type !== 'file') return res.status(404).json({ error: 'not found' });
  try { await stat(filePath(p.id)); } catch { return res.status(410).json({ error: 'file no longer stored' }); }
  const inline = req.query.download === undefined && INLINE_TYPES.test(p.file_type);
  res.set('Content-Security-Policy', "default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'; object-src 'self'");
  res.set('Cache-Control', 'private, max-age=31536000, immutable');
  res.type(inline ? p.file_type : 'application/octet-stream');
  res.attachment(p.file_name);
  if (inline) res.set('Content-Disposition', res.get('Content-Disposition').replace(/^attachment/, 'inline'));
  res.sendFile(filePath(p.id), { headers: { 'Content-Type': inline ? p.file_type : 'application/octet-stream' } });
});

async function removePush(p) {
  q.deletePush.run(p.id);
  if (p.type === 'file') await rm(filePath(p.id), { force: true });
  broadcast({ type: 'delete', id: p.id });
}

app.delete('/api/pushes/:id', async (req, res) => {
  const p = q.pushById.get(req.params.id);
  if (!p) return res.status(404).json({ error: 'not found' });
  await removePush(p);
  res.json({ ok: true });
});

app.delete('/api/pushes', async (req, res) => {
  const files = q.allFilePushes.all();
  q.deleteAllPushes.run();
  await Promise.all(files.map((f) => rm(filePath(f.id), { force: true })));
  broadcast({ type: 'clear' });
  res.json({ ok: true });
});

// ---------- Notification mirroring (phone -> other devices) ----------
// Live only: kept in memory so a desktop that connects late sees what is
// still showing on the phone, never written to disk.
const mirrored = new Map(); // `${deviceId}|${key}` -> notification

function mirrorPushPayload(n) {
  return JSON.stringify({ kind: 'mirror', id: n.id, key: n.key, from: n.device_name, app: n.app, title: n.title, body: n.text });
}

async function notifyMirror(n) {
  for (const d of q.devices.all()) {
    if (!d.push_sub || d.id === n.device) continue;
    try {
      await webpush.sendNotification(JSON.parse(d.push_sub), mirrorPushPayload(n), { TTL: 300, urgency: 'high', topic: n.id.slice(0, 32) });
    } catch (err) {
      if (err.statusCode === 404 || err.statusCode === 410) q.setPushSub.run(null, d.id);
    }
  }
}

const clip = (v, n) => (v == null ? null : String(v).slice(0, n));

app.post('/api/mirror', (req, res) => {
  const b = req.body || {};
  if (!b.key) return res.status(400).json({ error: 'key required' });
  const icon = typeof b.icon === 'string' && /^data:image\/(png|webp|jpeg);base64,[a-z0-9+/=]+$/i.test(b.icon) && b.icon.length < 40_000 ? b.icon : null;
  const n = {
    id: createHash('sha256').update(`${req.device.id}|${b.key}`).digest('base64url').slice(0, 22),
    key: clip(b.key, 300), device: req.device.id, device_name: req.device.name,
    package: clip(b.package, 200), app: clip(b.app, 100) || clip(b.package, 100),
    title: clip(b.title, 300), text: clip(b.text, 2000), icon,
    posted_at: Number(b.posted_at) || now(),
  };
  if (!n.title && !n.text) return res.status(400).json({ error: 'empty notification' });
  const k = `${n.device}|${n.key}`;
  const isUpdate = mirrored.has(k);
  mirrored.delete(k);
  mirrored.set(k, n);
  while (mirrored.size > 200) mirrored.delete(mirrored.keys().next().value);
  broadcast({ type: 'mirror', notification: n, update: isUpdate });
  if (!b.silent_update || !isUpdate) notifyMirror(n).catch(() => {});
  res.status(201).json({ id: n.id });
});

app.post('/api/mirror/dismiss', (req, res) => {
  const key = String(req.body?.key || '');
  const k = `${req.device.id}|${key}`;
  const n = mirrored.get(k);
  mirrored.delete(k);
  if (n) broadcast({ type: 'mirror_dismiss', id: n.id, key, device: req.device.id });
  res.json({ ok: true });
});

// Dismissed on a computer: close it everywhere, the phone included
// (remote: true tells the phone to clear it from its notification shade).
app.delete('/api/mirror/:id', (req, res) => {
  for (const [k, n] of mirrored) {
    if (n.id === req.params.id) {
      mirrored.delete(k);
      broadcast({ type: 'mirror_dismiss', id: n.id, key: n.key, device: n.device, remote: true });
    }
  }
  res.json({ ok: true });
});

app.get('/api/mirror', (req, res) => {
  res.json({ notifications: [...mirrored.values()].reverse() });
});

app.use('/api', (req, res) => res.status(404).json({ error: 'not found' }));

// ---------- Static PWA ----------
app.use(express.static(path.join(__dirname, 'public'), {
  setHeaders(res, file) {
    if (file.endsWith('sw.js') || file.endsWith('.html') || file.endsWith('.webmanifest')) {
      res.set('Cache-Control', 'no-cache');
    }
  },
}));
app.get('/{*splat}', (req, res) => res.sendFile(path.join(__dirname, 'public', 'index.html')));

// ---------- Server + WebSocket ----------
const server = createServer(app);
// Clients pass their token in the Authorization header (Android app) or as the
// second WebSocket sub-protocol (browsers), never in the URL, which proxies log.
const wss = new WebSocketServer({
  noServer: true,
  maxPayload: 64 * 1024,
  handleProtocols: (protocols) => (protocols.has('pushbox') ? 'pushbox' : false),
});

function wsToken(req, url) {
  const h = req.headers.authorization;
  if (h?.startsWith('Bearer ')) return h.slice(7).trim();
  const protos = String(req.headers['sec-websocket-protocol'] || '').split(',').map((p) => p.trim());
  if (protos[0] === 'pushbox' && protos[1]) return protos[1];
  return url.searchParams.get('token'); // older clients
}

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname !== '/ws') return socket.destroy();
  const cookie = req.headers.cookie || '';
  const m = cookie.match(/(?:^|;\s*)pb_token=([^;]+)/);
  const queryToken = wsToken(req, url);
  // Cookie auth is ambient, so it only counts from our own pages; an explicit
  // token (extension, Android app, scripts) may connect from any origin.
  const origin = req.headers.origin;
  let sameOrigin = true;
  try { sameOrigin = !origin || new URL(origin).host === req.headers.host; } catch { sameOrigin = false; }
  if (!queryToken && !sameOrigin) return socket.destroy();
  const token = queryToken || (m && decodeURIComponent(m[1]));
  const device = deviceFromToken(token);
  if (!device) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    return socket.destroy();
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    sockets.set(ws, device.id);
    ws.direct = (url.searchParams.get('caps') || '').split(',').includes('direct');
    q.touchDevice.run(now(), device.id);
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('message', (raw) => {
      const text = String(raw);
      if (text === 'ping') return ws.send('pong');
      let msg;
      try { msg = JSON.parse(text); } catch { return; }
      // Local addresses a device can be reached on for direct transfers.
      if (msg?.type === 'presence' && ws.direct) {
        ws.addrs = (Array.isArray(msg.addrs) ? msg.addrs : [])
          .filter((a) => typeof a === 'string' && /^http:\/\/[\d.]+:\d{2,5}$|^http:\/\/\[[0-9a-f:]+\]:\d{2,5}$/i.test(a)).slice(0, 6);
        return broadcastDevices();
      }
      // Direct-transfer signaling, relayed as-is to the target device. The file
      // itself goes device to device and never through the server.
      if (msg?.type !== 'signal' || typeof msg.to !== 'string') return;
      const out = JSON.stringify({ type: 'signal', from: device.id, data: msg.data });
      if (out.length > 16_000) return;
      for (const [peer, id] of sockets) {
        if (id === msg.to && peer !== ws && peer.direct && peer.readyState === peer.OPEN) peer.send(out);
      }
    });
    ws.on('close', () => { sockets.delete(ws); broadcastDevices(); });
    ws.send(JSON.stringify({ type: 'hello', device: device.id }));
    broadcastDevices();
  });
});

// Heartbeat drops dead connections (phones going to sleep).
setInterval(() => {
  for (const ws of sockets.keys()) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30_000);

// Retention sweep.
if (RETENTION_DAYS > 0) {
  const sweep = async () => {
    const old = q.expiredPushes.all(now() - RETENTION_DAYS * 86_400_000);
    for (const p of old) await removePush(p);
    if (old.length) console.log(`retention: removed ${old.length} pushes`);
  };
  sweep();
  setInterval(sweep, 60 * 60_000);
}

server.listen(PORT, () => {
  console.log(`Pushbox listening on :${PORT} (data: ${DATA_DIR})`);
  console.log(`Sign-in: ${[OIDC_ENABLED && `OIDC (${OIDC_ISSUER})`, PASSWORD && 'password'].filter(Boolean).join(' + ')}`);
  if (OIDC_ENABLED && !OIDC_ALLOWED_EMAILS.length && !OIDC_ALLOWED_GROUPS.length) {
    const owner = q.getSetting.get('oidc_owner_sub')?.value;
    console.log(owner ? 'OIDC: only the owner account may sign in' : 'OIDC: no allowlist set, the first account to sign in becomes the owner');
  }
  oidc?.getDiscovery().catch((e) => console.warn(e.message));
});
