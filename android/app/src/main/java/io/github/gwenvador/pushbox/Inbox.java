package io.github.gwenvador.pushbox;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Files received directly from another device, kept in the app's private
 * storage until the user chooses to download them (nothing lands in
 * Downloads without a tap).
 */
public final class Inbox {
    private static final int MAX_FILES = 20;
    private static final long MAX_BYTES = 500L * 1024 * 1024;

    public static final class Item {
        public final String id, name, mime, from;
        public final long size, at;
        /** Set once saved to Download/Pushbox; the private copy is gone by then. */
        public final String savedUri;
        Item(String id, String name, String mime, String from, long size, long at) { this(id, name, mime, from, size, at, null); }
        Item(String id, String name, String mime, String from, long size, long at, String savedUri) {
            this.id = id; this.name = name; this.mime = mime; this.from = from; this.size = size; this.at = at; this.savedUri = savedUri;
        }
    }

    private Inbox() { }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "inbox");
        d.mkdirs();
        return d;
    }

    private static File index(Context c) { return new File(dir(c), "index.json"); }

    /** Where an incoming file is written before it is added. */
    static File fileFor(Context c, String id) { return new File(dir(c), id + ".bin"); }

    static synchronized List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        try (InputStream in = new FileInputStream(index(c))) {
            JSONArray arr = new JSONArray(new String(Files.readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Item(o.getString("id"), o.getString("name"), o.optString("mime", "application/octet-stream"),
                        o.optString("from"), o.optLong("size"), o.optLong("at"), o.isNull("saved") ? null : o.optString("saved", null)));
            }
        } catch (Exception ignored) { }
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    private static void write(Context c, List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item i : items) arr.put(new JSONObject().put("id", i.id).put("name", i.name).put("mime", i.mime)
                    .put("from", i.from).put("size", i.size).put("at", i.at).put("saved", i.savedUri == null ? JSONObject.NULL : i.savedUri));
            File tmp = new File(dir(c), "index.json.tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(arr.toString().getBytes(StandardCharsets.UTF_8)); }
            tmp.renameTo(index(c));
        } catch (Exception ignored) { }
    }

    /** Register a fully written file, then drop the oldest beyond the limits. */
    static synchronized void add(Context c, Item item) {
        List<Item> items = list(c);
        items.add(0, item);
        List<Item> keep = new ArrayList<>();
        long total = 0;
        for (Item i : items) {
            if (i.savedUri == null) total += i.size; // saved ones no longer use app storage
            if (keep.size() < MAX_FILES && total <= MAX_BYTES) keep.add(i);
            else fileFor(c, i.id).delete();
        }
        write(c, keep);
        Hub.emit(Hub.Listener::onInbox);
    }

    static synchronized void remove(Context c, String id) {
        List<Item> items = list(c);
        items.removeIf(i -> i.id.equals(id));
        fileFor(c, id).delete();
        write(c, items);
        Hub.emit(Hub.Listener::onInbox);
    }

    static Item get(Context c, String id) {
        for (Item i : list(c)) if (i.id.equals(id)) return i;
        return null;
    }

    /** Copy to Download/Pushbox (once), keep it in the history as saved, and return the saved file. */
    static Uri saveToDownloads(Context c, String id) throws Exception {
        Item it = get(c, id);
        if (it == null) throw new Exception("File no longer available");
        if (it.savedUri != null) return Uri.parse(it.savedUri);
        Files.Incoming dst = Files.saveIncoming(c, it.name, it.mime);
        boolean ok = false;
        try (InputStream in = new FileInputStream(fileFor(c, id))) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) dst.out.write(buf, 0, n);
            ok = true;
        } finally {
            dst.finish(ok);
        }
        markSaved(c, id, dst.uri.toString());
        return dst.uri;
    }

    private static synchronized void markSaved(Context c, String id, String uri) {
        List<Item> items = list(c);
        for (int k = 0; k < items.size(); k++) {
            Item i = items.get(k);
            if (i.id.equals(id)) items.set(k, new Item(i.id, i.name, i.mime, i.from, i.size, i.at, uri));
        }
        fileFor(c, id).delete();
        write(c, items);
        Hub.emit(Hub.Listener::onInbox);
    }
}
