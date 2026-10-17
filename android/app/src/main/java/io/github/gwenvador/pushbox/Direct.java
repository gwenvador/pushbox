package io.github.gwenvador.pushbox;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct transfers between this phone and another device on the same network
 * (or Tailscale). The server only relays the small signaling messages:
 *
 *   phone -> desktop: we serve the file, send {kind:file, urls}; the desktop downloads it.
 *   desktop -> phone: desktop sends {kind:offer}; we reply {kind:accept, path}; it POSTs the file.
 *
 * If the other side cannot reach us in time, the caller falls back to the server.
 */
final class Direct {
    private static final String TAG = "Pushbox";
    private static final long START_TIMEOUT_MS = 12_000;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Handler main = new Handler(Looper.getMainLooper());

    interface Result { void done(boolean ok); }

    private static final class Pending {
        String secret; Result result; boolean started, settled;
    }

    private static final Map<String, Pending> sends = new ConcurrentHashMap<>();

    private Direct() { }

    static String secret() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Offer a file to `target`; result(false) means "use the server instead". */
    static void sendFile(String target, Uri uri, Files.Meta m, Result result) {
        PushService svc = PushService.get();
        if (svc == null || svc.local() == null || svc.local().port() <= 0 || !Hub.canDirect(target)) { result.done(false); return; }
        String id = secret().substring(0, 12);
        Pending p = new Pending();
        p.secret = secret();
        p.result = result;
        sends.put(id, p);
        String path = svc.local().allowDownload(p.secret, uri, m.name, m.mime, m.size, new LocalServer.DownloadListener() {
            @Override public void started() { p.started = true; }
            @Override public void finished(boolean ok) {
                // A dropped download is retried through the server.
                settle(id, ok);
            }
        });
        try {
            JSONArray urls = new JSONArray();
            for (String ip : PushService.localAddresses()) urls.put("http://" + ip + ":" + svc.local().port() + path);
            svc.signal(target, new JSONObject().put("kind", "file").put("id", id)
                    .put("name", m.name).put("size", m.size).put("mime", m.mime).put("urls", urls));
        } catch (Exception e) {
            settle(id, false);
            return;
        }
        main.postDelayed(() -> { if (!p.started) settle(id, false); }, START_TIMEOUT_MS);
    }

    private static void settle(String id, boolean ok) {
        Pending p = sends.remove(id);
        if (p == null || p.settled) return;
        p.settled = true;
        PushService svc = PushService.get();
        if (!ok && svc != null && svc.local() != null) svc.local().revoke(p.secret);
        main.post(() -> p.result.done(ok));
    }

    static void onSignal(PushService svc, String from, JSONObject data) {
        if (data == null) return;
        String kind = data.optString("kind"), id = data.optString("id");
        try {
            switch (kind) {
                case "offer": { // the desktop wants to send us a file
                    String name = data.optString("name", "file").replace('/', '_');
                    String mime = data.optString("mime", "application/octet-stream");
                    String fromName = Hub.deviceName(from);
                    String path = svc.local().allowUpload(secret(), name, mime, (fileId, size) -> {
                        Inbox.Item item = new Inbox.Item(fileId, name, mime, fromName, size, System.currentTimeMillis());
                        Inbox.add(svc, item);
                        Notifier.received(svc, item);
                    });
                    svc.signal(from, new JSONObject().put("kind", "accept").put("id", id).put("path", path));
                    break;
                }
                case "fail": // the desktop could not reach us
                    settle(id, false);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "direct " + kind, e);
        }
    }
}
