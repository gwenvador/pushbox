// Shared by the background worker and the popup.
// No built-in server: each install is pointed at its own Pushbox at sign-in.
export const DEFAULT_SERVER = '';

export async function getConfig() {
  const c = await chrome.storage.local.get(['server', 'token', 'device']);
  return { server: c.server || DEFAULT_SERVER, token: c.token || null, device: c.device || null };
}

export async function api(path, opts = {}) {
  const { server, token } = await getConfig();
  if (!token) throw new Error('Not signed in');
  const headers = { Authorization: `Bearer ${token}`, ...(opts.headers || {}) };
  if (opts.json !== undefined) {
    headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(opts.json);
  }
  const res = await fetch(server + path, { ...opts, headers });
  if (res.status === 401) {
    await chrome.storage.local.remove(['token', 'device']);
    throw new Error('Signed out');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

export async function pushText(body, target = 'all', title) {
  return api('/api/pushes', { method: 'POST', json: { body, target, title } });
}

export async function pushBlob(blob, name, target = 'all') {
  const qs = new URLSearchParams({ name, target, type: blob.type || 'application/octet-stream' });
  return api(`/api/upload?${qs}`, { method: 'POST', body: blob, headers: { 'Content-Type': 'application/octet-stream' } });
}

export function deviceName() {
  const ua = navigator.userAgent;
  const os = /Mac OS X/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : 'Desktop';
  const brands = (navigator.userAgentData?.brands || []).map((b) => b.brand).join(' ');
  const br = navigator.brave || /Brave/.test(brands) ? 'Brave' : /Edg\//.test(ua) ? 'Edge' : 'Chrome';
  return `${os} ${br}`;
}

// ---------- Direct transfers (to the Android app on the same network / Tailscale) ----------
export async function withTimeout(ms, fn) {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), ms);
  try { return await fn(ac.signal); } finally { clearTimeout(t); }
}

// The first of the phone's addresses that answers quickly, or null.
export async function reachable(urls) {
  const tries = urls.map((u) => withTimeout(2500, (signal) => fetch(u, { method: 'OPTIONS', signal }))
    .then((r) => (r.ok || r.status === 204 ? u : Promise.reject())));
  try { return await Promise.any(tries); } catch { return null; }
}

// offer = { path, addrs } from the background's directOffer. Returns true if delivered.
export async function postDirect(offer, blob) {
  if (!offer) return false;
  const base = await reachable(offer.addrs);
  if (!base) return false;
  try {
    const res = await fetch(base + offer.path, { method: 'POST', body: blob, headers: { 'Content-Type': 'application/octet-stream' } });
    return res.ok;
  } catch {
    return false;
  }
}
