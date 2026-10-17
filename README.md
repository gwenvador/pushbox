# Pushbox

A self-hosted Pushbullet. Send notes, links and files between your phone and your computers,
and see your phone's notifications on your desktop. No third-party cloud: one small Node server you run yourself.

## What's in the box

| Part | Folder | What it does |
|---|---|---|
| **Server** | `/` | Node + SQLite + files on disk. REST API, WebSocket for live updates, OIDC or password sign-in. Also serves the web app. |
| **Android app** | `android/` | Native (Kotlin, Jetpack Compose, Material 3). Push feed, send text and files, *Share → Pushbox*, live notifications through its own connection (no Google services needed), notification forwarding, direct transfers. |
| **Browser extension** | `extension/` | Chrome / Brave / Edge (Manifest V3). Popup to send text, files or the current tab, right-click *Push to…*, desktop notifications for pushes and for your phone's notifications, direct transfers. |
| **Web app** | `public/` | For any browser. Shares its sign-in with the extension in the same browser. |

Features:
- **Pushes** of notes, links and files, to all your devices or to one; history with image previews.
- **Notification forwarding**: the Android app relays the phone's notifications (per-app filter) to your
  computers; dismissing them on the phone removes them everywhere.
- **Direct transfers**: between the Android app and the extension, files go device to device on your LAN
  or over Tailscale; the server only relays a few signaling messages. If the devices can't reach each other,
  the file goes through the server instead.
- **Nothing is downloaded without asking**: received files wait in the app or the extension until you tap *Download*.
- **One login per device**: the extension and the website in the same browser share one session;
  the Android app can open the website already signed in.
- **Sign-in** with any OIDC provider (Pocket ID, Authelia, Authentik, Keycloak…) and/or a password.
  Each device gets its own revocable token; tokens never appear in URLs.

## Clients

- **Android**: `cd android && ./gradlew assembleRelease`, then install the APK. Put it in `public/pushbox.apk`
  to let the server offer it for download at `/pushbox.apk`. Enter your server address at sign-in.
- **Extension**: *Extensions → Developer mode → Load unpacked* → the `extension/` folder. Enter your server
  address at sign-in; the extension asks for permission to reach that server only.
  The manifest has a fixed `key`, so the extension ID is stable; the server accepts sign-in hand-offs for
  that ID by default (`EXTENSION_IDS` to change it).

## Run

```bash
cp .env.example .env     # set PUBLIC_URL + OIDC_*, or PUSHBOX_PASSWORD
docker compose up -d --build
```

The app listens on port 2483. Put it behind HTTPS (e.g. Nginx Proxy Manager with a Let's Encrypt cert,
WebSocket support turned on). **HTTPS is required** for the service worker, so for installing, notifications and the share target.
Raise the proxy's body size limit to match `MAX_UPLOAD_MB` (NPM advanced tab: `client_max_body_size 1024m;`).

Without Docker: `npm ci && PUSHBOX_PASSWORD=… node server.js` (Node 22.13+).

| Variable | Default | |
|---|---|---|
| `PUBLIC_URL` | | Required with OIDC, e.g. `https://push.example.com` |
| `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` | | OIDC sign-in (code flow + PKCE). Redirect URI: `PUBLIC_URL/auth/callback` |
| `OIDC_NAME` | `Pocket ID` | Button label |
| `OIDC_ALLOWED_EMAILS`, `OIDC_ALLOWED_GROUPS` | | Comma-separated. Empty: first account to sign in becomes the owner |
| `PUSHBOX_PASSWORD` | | Optional password sign-in; empty disables it |
| `PORT` | `8080` | |
| `DATA_DIR` | `./data` | SQLite DB, files, VAPID keys |
| `MAX_UPLOAD_MB` | `1024` | Per file |
| `RETENTION_DAYS` | `0` | Auto-delete older pushes (0 = never) |
| `VAPID_SUBJECT` | `mailto:admin@localhost` | Contact sent to push services |

Back up the data volume: it holds the VAPID key pair; losing it means each device has to turn notifications on again.

## Install on the phone

- **Android (Chrome)**: open the site, sign in, menu → *Install app*. Then *Enable* notifications. Share anything → *Pushbox*.
- **iPhone (Safari)**: Share → *Add to Home Screen*, open it from there, sign in, enable notifications.
  iOS has no PWA share target; to send from the share sheet use a Shortcut (below).

## API

Every device has a bearer token. Create one for scripts in *Settings → API & shortcuts*.

```bash
# text (a body that is only a URL becomes a link push)
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"body":"hello","target":"all"}' https://push.example.com/api/pushes

# file (raw body; name/target/body in the query string)
curl -H "Authorization: Bearer $TOKEN" --data-binary @photo.jpg \
  "https://push.example.com/api/upload?name=photo.jpg"
```

| Method | Path | |
|---|---|---|
| POST | `/api/login` | `{password, name, kind}` → `{token, device}` |
| GET | `/api/pushes?before=&limit=` | Newest first |
| POST | `/api/pushes` | `{body, title?, url?, target?}` |
| POST | `/api/upload?name=&target=&body=` | Raw file body |
| GET | `/api/files/:id/:name[?download]` | |
| DELETE | `/api/pushes/:id`, `/api/pushes` | One / all |
| GET/PATCH/DELETE | `/api/devices[/:id]` | |
| WS | `/ws?token=` | Events `push`, `delete`, `clear`, `devices` |

### iOS Shortcut (share sheet → Pushbox)

New shortcut, *Show in Share Sheet*, accepts Images, Files, Text, URLs:
1. **Get Contents of URL**: `https://push.example.com/api/upload?name=` + *Shortcut Input › Name*,
   method POST, header `Authorization: Bearer <token>`, request body **File** = *Shortcut Input*.

For text only, use `/api/pushes` with a JSON body `{"body": Shortcut Input}`.
