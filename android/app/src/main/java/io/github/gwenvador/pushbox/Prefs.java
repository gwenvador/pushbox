package io.github.gwenvador.pushbox;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Settings and credentials, stored in the app's private SharedPreferences. */
final class Prefs {
    /** No built-in server: entered once at sign-in. */
    static final String DEFAULT_SERVER = "";

    private final SharedPreferences sp;
    private final Context ctx;

    Prefs(Context c) {
        ctx = c.getApplicationContext();
        sp = c.getSharedPreferences("pushbox", Context.MODE_PRIVATE);
    }

    String server() { return sp.getString("server", DEFAULT_SERVER); }
    void server(String v) { sp.edit().putString("server", v).apply(); }

    String token() { return sp.getString("token", null); }
    String deviceName() { return sp.getString("device_name", null); }
    void signIn(String token, String name) { sp.edit().putString("token", token).putString("device_name", name).apply(); }
    void signOut() { sp.edit().remove("token").remove("device_name").apply(); }

    /** created_at of the newest push already notified (catch-up after being offline). */
    long lastPushAt() { return sp.getLong("last_push_at", 0); }
    void lastPushAt(long v) { if (v > lastPushAt()) sp.edit().putLong("last_push_at", v).apply(); }

    /** Files already saved to Download/Pushbox, by push or inbox id: "uri\nmime". */
    String savedUri(String id) { String v = saved().getString(id, null); return v == null ? null : v.split("\n", 2)[0]; }
    String savedMime(String id) { String v = saved().getString(id, null); return v == null || !v.contains("\n") ? null : v.split("\n", 2)[1]; }
    java.util.Set<String> savedIds() { return new HashSet<>(saved().getAll().keySet()); }
    void saved(String id, String uri, String mime) { saved().edit().putString(id, uri + "\n" + mime).apply(); }
    void forgetSaved(String id) { saved().edit().remove(id).apply(); }
    private SharedPreferences saved() { return ctx.getSharedPreferences("saved", Context.MODE_PRIVATE); }

    boolean forwarding() { return sp.getBoolean("forwarding", true); }
    void forwarding(boolean v) { sp.edit().putBoolean("forwarding", v).apply(); }

    /** Packages whose notifications are NOT forwarded. */
    Set<String> blocked() { return new HashSet<>(sp.getStringSet("blocked", new HashSet<>())); }
    void blocked(Set<String> v) { sp.edit().putStringSet("blocked", new HashSet<>(v)).apply(); }
}
