package io.github.gwenvador.pushbox;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** File helpers: names/sizes of content URIs, saving to Downloads, fetching from the server. */
final class Files {
    private Files() { }

    static final class Meta {
        String name = "file";
        long size = -1;
        String mime = "application/octet-stream";
    }

    static Meta meta(Context c, Uri uri) {
        Meta m = new Meta();
        ContentResolver cr = c.getContentResolver();
        try (Cursor cur = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                if (!cur.isNull(0)) m.name = cur.getString(0);
                if (!cur.isNull(1)) m.size = cur.getLong(1);
            }
        } catch (Exception ignored) { }
        if ("file".equals(m.name) && uri.getLastPathSegment() != null) m.name = uri.getLastPathSegment();
        String t = cr.getType(uri);
        if (t == null) {
            String ext = MimeTypeMap.getFileExtensionFromUrl(m.name);
            t = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        }
        if (t != null) m.mime = t;
        return m;
    }

    /** A file being written to Download/Pushbox; call finish() when done. */
    static final class Incoming {
        final Uri uri;
        final OutputStream out;
        private final Context c;
        private final boolean pending;

        Incoming(Context c, Uri uri, OutputStream out, boolean pending) { this.c = c; this.uri = uri; this.out = out; this.pending = pending; }

        void finish(boolean ok) {
            try { out.close(); } catch (Exception ignored) { }
            if (!pending) return;
            if (ok) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                c.getContentResolver().update(uri, v, null, null);
            } else {
                c.getContentResolver().delete(uri, null, null);
            }
        }
    }

    static Incoming saveIncoming(Context c, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pushbox");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("cannot create file");
            return new Incoming(c, uri, c.getContentResolver().openOutputStream(uri), true);
        }
        File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Pushbox");
        dir.mkdirs();
        File f = new File(dir, name);
        return new Incoming(c, Uri.fromFile(f), new FileOutputStream(f), false);
    }

    /** Save a pushed file from the server into Download/Pushbox and return it (blocking). */
    static Uri fetchToDownloads(Context c, JSONObject p) throws Exception {
        Prefs prefs = new Prefs(c);
        String name = p.optString("file_name", "file").replace('/', '_');
        String mime = p.optString("file_type", "application/octet-stream");
        HttpURLConnection con = (HttpURLConnection) new URL(prefs.server() + p.optString("file_url") + "?download").openConnection();
        con.setRequestProperty("Authorization", "Bearer " + prefs.token());
        con.setConnectTimeout(15_000);
        con.setReadTimeout(60_000);
        if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
        Incoming dst = saveIncoming(c, name, mime);
        boolean ok = false;
        try (InputStream in = con.getInputStream()) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) dst.out.write(buf, 0, n);
            ok = true;
        } finally {
            dst.finish(ok);
            con.disconnect();
        }
        prefs.saved(p.optString("id"), dst.uri.toString(), mime);
        Hub.emit(Hub.Listener::onInbox);
        return dst.uri;
    }

    /** Fetch and downscale an image push (for notifications and the feed). */
    static Bitmap fetchImage(Context c, String fileUrl, int maxPx) {
        Prefs prefs = new Prefs(c);
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(prefs.server() + fileUrl).openConnection();
            con.setRequestProperty("Authorization", "Bearer " + prefs.token());
            con.setConnectTimeout(10_000);
            con.setReadTimeout(20_000);
            if (con.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = con.getInputStream()) { data = readAll(in); }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[65536];
        for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
        return buf.toByteArray();
    }

    static String humanSize(long n) {
        if (n < 0) return "";
        String[] u = {"B", "KB", "MB", "GB"};
        double v = n;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return (i == 0 || v >= 10 ? String.format("%.0f", v) : String.format("%.1f", v)) + " " + u[i];
    }
}
