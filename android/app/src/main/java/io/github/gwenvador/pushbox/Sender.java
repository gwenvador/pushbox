package io.github.gwenvador.pushbox;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Sends text and files: directly to the target device when possible, else via the server. */
final class Sender {
    interface Callback { void done(boolean ok, String how); }

    private static final Handler main = new Handler(Looper.getMainLooper());

    private Sender() { }

    static void text(Context c, String body, String target, Callback cb) {
        Prefs p = new Prefs(c);
        Api.IO.execute(() -> {
            try {
                Api.call(p.server(), p.token(), "POST", "/api/pushes", new JSONObject().put("body", body).put("target", target));
                main.post(() -> cb.done(true, "sent"));
            } catch (Exception e) {
                main.post(() -> cb.done(false, e.getMessage()));
            }
        });
    }

    static void file(Context c, Uri uri, String target, Callback cb) {
        Context app = c.getApplicationContext();
        Files.Meta m = Files.meta(app, uri);
        if (!"all".equals(target) && Hub.canDirect(target)) {
            Direct.sendFile(target, uri, m, ok -> {
                if (ok) cb.done(true, "sent directly");
                else upload(app, uri, m, target, cb);
            });
        } else {
            upload(app, uri, m, target, cb);
        }
    }

    /** Upload through the server, streaming from the content URI with a progress notification. */
    static void upload(Context c, Uri uri, Files.Meta m, String target, Callback cb) {
        Prefs p = new Prefs(c);
        new Thread(() -> {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            int nid = Notifier.idFor("up" + uri);
            Notification.Builder nb = Notifier.progress(c, m.name);
            try {
                String qs = "name=" + LocalServer.enc(m.name) + "&target=" + LocalServer.enc(target) + "&type=" + LocalServer.enc(m.mime);
                HttpURLConnection con = (HttpURLConnection) new URL(p.server() + "/api/upload?" + qs).openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setConnectTimeout(15_000);
                con.setReadTimeout(120_000);
                con.setRequestProperty("Authorization", "Bearer " + p.token());
                con.setRequestProperty("Content-Type", "application/octet-stream");
                if (m.size >= 0) con.setFixedLengthStreamingMode(m.size); else con.setChunkedStreamingMode(65536);
                long sent = 0, lastUi = 0;
                try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream out = con.getOutputStream()) {
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        sent += n;
                        if (m.size > 0 && System.currentTimeMillis() - lastUi > 500) {
                            lastUi = System.currentTimeMillis();
                            nm.notify(nid, nb.setProgress(1000, (int) (sent * 1000 / m.size), false)
                                    .setContentText(Files.humanSize(sent) + " / " + Files.humanSize(m.size)).build());
                        }
                    }
                }
                int code = con.getResponseCode();
                con.disconnect();
                nm.cancel(nid);
                if (code >= 300) throw new Exception("HTTP " + code);
                main.post(() -> cb.done(true, "sent via server"));
            } catch (Exception e) {
                nm.cancel(nid);
                main.post(() -> cb.done(false, e.getMessage()));
            }
        }).start();
    }
}
