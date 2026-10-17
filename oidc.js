// Minimal OpenID Connect client: authorization code flow with PKCE,
// ID token signature checked against the provider's JWKS.
import { createHash, createPublicKey, randomBytes, verify } from 'node:crypto';

const b64url = (buf) => Buffer.from(buf).toString('base64url');

export function createOidc({ issuer, clientId, clientSecret, redirectUri, scope = 'openid email profile groups' }) {
  let discovery = null;
  let jwks = null;
  let jwksFetchedAt = 0;
  const pending = new Map(); // state -> { verifier, nonce, extra, expires }

  async function getDiscovery() {
    if (discovery) return discovery;
    const res = await fetch(`${issuer.replace(/\/$/, '')}/.well-known/openid-configuration`);
    if (!res.ok) throw new Error(`OIDC discovery failed: HTTP ${res.status}`);
    const d = await res.json();
    if (d.issuer.replace(/\/$/, '') !== issuer.replace(/\/$/, '')) throw new Error('OIDC issuer mismatch in discovery');
    discovery = d;
    return d;
  }

  async function getKey(kid, force = false) {
    if (!jwks || force) {
      // Refetch at most every 10 s (handles key rotation without hammering the IdP).
      if (force && Date.now() - jwksFetchedAt < 10_000) return null;
      const d = await getDiscovery();
      const res = await fetch(d.jwks_uri);
      if (!res.ok) throw new Error(`JWKS fetch failed: HTTP ${res.status}`);
      jwks = (await res.json()).keys || [];
      jwksFetchedAt = Date.now();
    }
    const jwk = jwks.find((k) => !kid || k.kid === kid);
    if (!jwk) return force ? null : getKey(kid, true);
    return createPublicKey({ key: jwk, format: 'jwk' });
  }

  async function verifyIdToken(idToken, nonce) {
    const [h, p, s] = idToken.split('.');
    if (!h || !p || !s) throw new Error('malformed ID token');
    const header = JSON.parse(Buffer.from(h, 'base64url'));
    const claims = JSON.parse(Buffer.from(p, 'base64url'));
    const data = Buffer.from(`${h}.${p}`);
    const sig = Buffer.from(s, 'base64url');
    const check = (key) => {
      const algs = {
        RS256: () => verify('sha256', data, key, sig),
        RS384: () => verify('sha384', data, key, sig),
        RS512: () => verify('sha512', data, key, sig),
        PS256: () => verify('sha256', data, { key, padding: 6, saltLength: 32 }, sig),
        ES256: () => verify('sha256', data, { key, dsaEncoding: 'ieee-p1363' }, sig),
        ES384: () => verify('sha384', data, { key, dsaEncoding: 'ieee-p1363' }, sig),
        EdDSA: () => verify(null, data, key, sig),
      };
      if (!algs[header.alg]) throw new Error(`unsupported ID token algorithm ${header.alg}`);
      return algs[header.alg]();
    };
    let key = await getKey(header.kid);
    let ok = key && check(key);
    if (!ok) {
      // the provider may have rotated its keys
      key = await getKey(header.kid, true);
      ok = key && check(key);
    }
    if (!ok) throw new Error(`invalid ID token signature (${header.alg})`);
    const now = Date.now() / 1000;
    const aud = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
    if (claims.iss.replace(/\/$/, '') !== issuer.replace(/\/$/, '')) throw new Error('ID token issuer mismatch');
    if (!aud.includes(clientId)) throw new Error('ID token audience mismatch');
    if (claims.exp < now - 60) throw new Error('ID token expired');
    if (claims.nonce !== nonce) throw new Error('ID token nonce mismatch');
    return claims;
  }

  // Returns { url, state } to redirect the browser to.
  async function authorizationUrl(extra = {}) {
    const d = await getDiscovery();
    for (const [k, v] of pending) if (v.expires < Date.now()) pending.delete(k);
    const state = b64url(randomBytes(24));
    const nonce = b64url(randomBytes(24));
    const verifier = b64url(randomBytes(48));
    pending.set(state, { verifier, nonce, extra, expires: Date.now() + 10 * 60_000 });
    const url = new URL(d.authorization_endpoint);
    url.search = new URLSearchParams({
      response_type: 'code',
      client_id: clientId,
      redirect_uri: redirectUri,
      scope,
      state,
      nonce,
      code_challenge: b64url(createHash('sha256').update(verifier).digest()),
      code_challenge_method: 'S256',
    });
    return { url: url.toString(), state };
  }

  // Exchanges the code, verifies the ID token, merges userinfo claims.
  async function callback({ code, state }) {
    const p = pending.get(state);
    pending.delete(state);
    if (!p || p.expires < Date.now()) throw new Error('login expired, try again');
    const d = await getDiscovery();
    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: redirectUri,
      code_verifier: p.verifier,
    });
    const headers = { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' };
    if (clientSecret) {
      headers.Authorization = `Basic ${Buffer.from(`${encodeURIComponent(clientId)}:${encodeURIComponent(clientSecret)}`).toString('base64')}`;
    } else {
      body.set('client_id', clientId);
    }
    const res = await fetch(d.token_endpoint, { method: 'POST', headers, body });
    const tokens = await res.json().catch(() => ({}));
    if (!res.ok || !tokens.id_token) throw new Error(`token exchange failed: ${tokens.error_description || tokens.error || res.status}`);
    const claims = await verifyIdToken(tokens.id_token, p.nonce);
    if (tokens.access_token && d.userinfo_endpoint) {
      const ui = await fetch(d.userinfo_endpoint, { headers: { Authorization: `Bearer ${tokens.access_token}` } });
      if (ui.ok) {
        const info = await ui.json();
        if (info.sub === claims.sub) Object.assign(claims, { ...info, ...claims, groups: info.groups ?? claims.groups });
      }
    }
    return { claims, extra: p.extra };
  }

  return { authorizationUrl, callback, getDiscovery };
}
