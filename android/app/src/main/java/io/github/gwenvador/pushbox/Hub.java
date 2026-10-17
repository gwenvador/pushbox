package io.github.gwenvador.pushbox;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Live state shared by the service and the screens (devices, connection, new pushes). */
final class Hub {
    interface Listener {
        default void onConnection(boolean connected) { }
        default void onDevices() { }
        default void onPush(JSONObject push) { }
        default void onDelete(String id) { }
        default void onClear() { }
        default void onInbox() { }
    }

    static volatile boolean connected;
    static volatile String myId;
    static volatile JSONArray devices = new JSONArray();
    static volatile Set<String> online = new HashSet<>();
    /** deviceId -> {can: bool, addrs: [...]} for direct transfers. */
    static volatile JSONObject direct = new JSONObject();

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void add(Listener l) { listeners.add(l); }
    static void remove(Listener l) { listeners.remove(l); }

    static void emit(java.util.function.Consumer<Listener> f) {
        main.post(() -> { for (Listener l : listeners) f.accept(l); });
    }

    static String deviceName(String id) {
        if (id == null) return "API";
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d != null && id.equals(d.optString("id"))) return d.optString("name");
        }
        return "Removed device";
    }

    /** Devices a push can target (not this one, not API tokens), as {id, name}. */
    static List<String[]> targets() {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null || d.optString("id").equals(myId) || "api".equals(d.optString("kind"))) continue;
            out.add(new String[]{d.optString("id"), d.optString("name")});
        }
        return out;
    }

    /** The device has an open, direct-capable connection right now. */
    static boolean canDirect(String id) {
        JSONObject d = direct.optJSONObject(id);
        return d != null && d.optBoolean("can");
    }
}
