package io.github.gwenvador.pushbox;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP server for direct device-to-device transfers on the LAN or
 * Tailscale. It only answers URLs holding a one-time 128-bit secret that was
 * handed out through the (authenticated) server signaling, and each expires.
 */
final class LocalServer {
    private static final String TAG = "Pushbox";
    private static final long TTL_MS = 10 * 60_000;

    interface DownloadListener {
        void started();
        void finished(boolean ok);
    }

    interface UploadListener {
        /** The file is complete in the app's inbox storage (Inbox.fileFor(id)). */
        void received(String id, long size);
    }

    private static final class Ticket {
        final long expires = System.currentTimeMillis() + TTL_MS;
        Uri source; String name, mime; long size;
        DownloadListener down;
        UploadListener up;
        boolean used;
    }

    private final Context ctx;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket socket;
    private volatile int port = -1;

    LocalServer(Context c) { ctx = c.getApplicationContext(); }

    int port() { return port; }

    void start() {
        pool.execute(() -> {
            try {
                socket = new ServerSocket(0, 16);
                port = socket.getLocalPort();
                if (PushService.get() != null) PushService.get().announce();
                while (!socket.isClosed()) {
                    Socket s = socket.accept();
                    pool.execute(() -> serve(s));
                }
            } catch (Exception e) {
                if (socket != null && !socket.isClosed()) Log.w(TAG, "local server", e);
            }
        });
    }

    void stop() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    String allowDownload(String secret, Uri source, String name, String mime, long size, DownloadListener l) {
        Ticket t = new Ticket();
        t.source = source; t.name = name; t.mime = mime; t.size = size; t.down = l;
        tickets.put(secret, t);
        return "/d/" + secret + "/" + enc(name);
    }

    String allowUpload(String secret, String name, String mime, UploadListener l) {
        Ticket t = new Ticket();
        t.name = name; t.mime = mime; t.up = l;
        tickets.put(secret, t);
        return "/u/" + secret;
    }

    void revoke(String secret) { tickets.remove(secret); }

    private Ticket ticket(String secret) {
        Ticket t = tickets.get(secret);
        if (t == null) return null;
        if (t.expires < System.currentTimeMillis()) { tickets.remove(secret); return null; }
        return t;
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], path = parts[1];
            Map<String, String> headers = new HashMap<>();
            for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
            }
            String[] seg = path.split("\\?")[0].split("/");

            if ("OPTIONS".equals(method)) {
                write(out, 204, "No Content", null, 0, null);
                return;
            }
            // GET/HEAD /d/<secret>/<name>: the other device downloads a file from this phone.
            if (seg.length >= 3 && "d".equals(seg[1]) && ("GET".equals(method) || "HEAD".equals(method))) {
                Ticket t = ticket(seg[2]);
                if (t == null || t.down == null) { write(out, 404, "Not Found", "text/plain", 0, null); return; }
                String disp = "attachment; filename*=UTF-8''" + enc(t.name);
                if ("HEAD".equals(method)) { write(out, 200, "OK", t.mime, t.size, disp); return; }
                if (t.used) { write(out, 410, "Gone", "text/plain", 0, null); return; }
                t.used = true;
                t.down.started();
                boolean ok = false;
                try (InputStream src = ctx.getContentResolver().openInputStream(t.source)) {
                    write(out, 200, "OK", t.mime, t.size, disp);
                    byte[] buf = new byte[65536];
                    for (int n; (n = src.read(buf)) > 0; ) out.write(buf, 0, n);
                    out.flush();
                    ok = true;
                } finally {
                    tickets.remove(seg[2]);
                    t.down.finished(ok);
                }
                return;
            }
            // POST /u/<secret>: the other device sends a file to this phone.
            if (seg.length >= 3 && "u".equals(seg[1]) && "POST".equals(method)) {
                Ticket t = ticket(seg[2]);
                String lenH = headers.get("content-length");
                if (t == null || t.up == null || t.used || lenH == null) { write(out, t == null ? 404 : 400, "Bad Request", "text/plain", 0, null); return; }
                t.used = true;
                tickets.remove(seg[2]);
                long len = Long.parseLong(lenH);
                // Kept in the app's private inbox, not in Downloads: the user decides later.
                String fileId = seg[2].substring(0, 12);
                java.io.File dstFile = Inbox.fileFor(ctx, fileId);
                boolean ok = false;
                try (java.io.OutputStream dst = new java.io.FileOutputStream(dstFile)) {
                    byte[] buf = new byte[65536];
                    long left = len;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) break;
                        dst.write(buf, 0, n);
                        left -= n;
                    }
                    ok = left == 0;
                } finally {
                    if (!ok) dstFile.delete();
                }
                byte[] body = (ok ? "{\"ok\":true}" : "{\"ok\":false}").getBytes(StandardCharsets.UTF_8);
                write(out, ok ? 200 : 400, ok ? "OK" : "Bad Request", "application/json", body.length, null);
                out.write(body);
                out.flush();
                if (ok) t.up.received(fileId, len);
                return;
            }
            write(out, 404, "Not Found", "text/plain", 0, null);
        } catch (Exception e) {
            Log.w(TAG, "local request", e);
        }
    }

    private static void write(OutputStream out, int code, String reason, String type, long len, String disposition) throws Exception {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Methods: GET, HEAD, POST, OPTIONS\r\n")
                .append("Access-Control-Allow-Headers: Content-Type\r\n")
                .append("Access-Control-Allow-Private-Network: true\r\n")
                .append("Access-Control-Expose-Headers: Content-Length, Content-Disposition\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        if (len >= 0) h.append("Content-Length: ").append(len).append("\r\n");
        if (disposition != null) h.append("Content-Disposition: ").append(disposition).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) return null;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return "file"; }
    }
}
