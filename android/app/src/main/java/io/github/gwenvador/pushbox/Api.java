package io.github.gwenvador.pushbox;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny JSON client for the Pushbox API; all calls run on one background thread. */
final class Api {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();

    static final class HttpError extends Exception {
        final int status;
        HttpError(int status, String msg) { super(msg); this.status = status; }
    }

    static JSONObject call(String server, String token, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
        }
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = "";
        if (in != null) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            in.close();
            text = buf.toString("UTF-8");
        }
        c.disconnect();
        JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        if (status >= 400) throw new HttpError(status, json.optString("error", "HTTP " + status));
        return json;
    }
}
