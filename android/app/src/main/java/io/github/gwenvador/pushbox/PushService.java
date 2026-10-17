package io.github.gwenvador.pushbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Keeps one WebSocket to the server while the app is signed in: live pushes
 * become notifications, and direct-transfer signaling is handled here.
 */
public class PushService extends Service {
    private static final String TAG = "Pushbox";
    static final String CH_SERVICE = "service", CH_PUSH = "pushes", CH_TRANSFER = "transfers";
    private static final int FG_ID = 1;

    private static PushService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    private Prefs prefs;
    private WebSocket ws;
    private long backoff = 2000;
    private boolean stopping;
    private LocalServer local;
    private ConnectivityManager.NetworkCallback netCb;

    static void start(Context c) {
        if (new Prefs(c).token() == null) return;
        Intent i = new Intent(c, PushService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) { c.stopService(new Intent(c, PushService.class)); }

    static PushService get() { return instance; }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN);
        s.setDescription("Shown while Pushbox stays connected to receive pushes");
        s.setShowBadge(false);
        nm.createNotificationChannel(s);
        nm.createNotificationChannel(new NotificationChannel(CH_PUSH, "Pushes", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_TRANSFER, "Transfers", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        channels(this);
        Notification n = new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Pushbox is connected")
                .setContentText("Tap to open. You can hide this in the notification settings.")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        else startForeground(FG_ID, n);

        local = new LocalServer(this);
        local.start();

        // Reconnect at once when the network changes (Wi-Fi <-> mobile, Tailscale up).
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { handler.post(() -> reconnectSoon(500)); }
            @Override public void onLost(Network network) { handler.post(() -> reconnectSoon(500)); }
        };
        cm.registerDefaultNetworkCallback(netCb);
        connect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ws == null) connect();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        instance = null;
        if (ws != null) ws.close(1000, "bye");
        if (local != null) local.stop();
        try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        Hub.connected = false;
        Hub.emit(l -> l.onConnection(false));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void reconnectSoon(long delay) {
        if (stopping) return;
        if (ws != null) { ws.cancel(); ws = null; }
        handler.removeCallbacks(this::connect);
        handler.postDelayed(this::connect, delay);
    }

    private void connect() {
        if (stopping || ws != null) return;
        String token = prefs.token();
        if (token == null) { stopSelf(); return; }
        String url = prefs.server().replaceFirst("^http", "ws") + "/ws?caps=direct";
        Request req = new Request.Builder().url(url).header("Authorization", "Bearer " + token).build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response r) {
                backoff = 2000;
                Hub.connected = true;
                Hub.emit(l -> l.onConnection(true));
                announce();
                catchUp();
            }
            @Override public void onMessage(WebSocket s, String text) {
                if ("pong".equals(text)) return;
                try { handle(new JSONObject(text)); } catch (Exception e) { Log.w(TAG, "message", e); }
            }
            @Override public void onClosed(WebSocket s, int code, String reason) { closed(s, code); }
            @Override public void onFailure(WebSocket s, Throwable t, Response r) {
                closed(s, r != null && r.code() == 401 ? 4001 : 0);
            }
        });
    }

    private void closed(WebSocket s, int code) {
        handler.post(() -> {
            if (ws != s) return;
            ws = null;
            Hub.connected = false;
            Hub.emit(l -> l.onConnection(false));
            if (code == 4001) { // device removed in Pushbox
                prefs.signOut();
                stopSelf();
                return;
            }
            handler.postDelayed(this::connect, backoff);
            backoff = Math.min(backoff * 2, 60_000);
        });
    }

    /** Notify pushes that arrived while this phone was offline (oldest first, at most 10). */
    private void catchUp() {
        final String server = prefs.server(), token = prefs.token();
        final long since = prefs.lastPushAt();
        Api.IO.execute(() -> {
            try {
                if (Hub.myId == null) Hub.myId = Api.call(server, token, "GET", "/api/me", null).getJSONObject("device").getString("id");
                org.json.JSONArray list = Api.call(server, token, "GET", "/api/pushes?limit=20", null).getJSONArray("pushes");
                if (since == 0) { // first run: nothing is "missed", just remember where we are
                    if (list.length() > 0) prefs.lastPushAt(list.getJSONObject(0).optLong("created_at"));
                    return;
                }
                java.util.List<JSONObject> missed = new java.util.ArrayList<>();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject p = list.getJSONObject(i);
                    if (p.optLong("created_at") <= since) break;
                    String src = p.optString("source_device", null), tgt = p.isNull("target_device") ? null : p.optString("target_device");
                    if (Hub.myId.equals(src) || (tgt != null && !tgt.equals(Hub.myId))) continue;
                    missed.add(p);
                }
                if (list.length() > 0) prefs.lastPushAt(list.getJSONObject(0).optLong("created_at"));
                java.util.Collections.reverse(missed);
                for (JSONObject p : missed.subList(Math.max(0, missed.size() - 10), missed.size())) Notifier.push(this, p);
            } catch (Exception e) {
                Log.w(TAG, "catch-up", e);
            }
        });
    }

    /** Tell the server where this phone can be reached for direct transfers. */
    void announce() {
        if (ws == null || local == null || local.port() <= 0) return;
        try {
            org.json.JSONArray addrs = new org.json.JSONArray();
            for (String ip : localAddresses()) addrs.put("http://" + ip + ":" + local.port());
            ws.send(new JSONObject().put("type", "presence").put("addrs", addrs).toString());
        } catch (Exception e) { Log.w(TAG, "presence", e); }
    }

    /** LAN (private ranges) and Tailscale (100.64/10) IPv4 addresses. */
    static Set<String> localAddresses() {
        Set<String> out = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    byte[] b = a.getAddress();
                    int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
                    boolean tailscale = b0 == 100 && b1 >= 64 && b1 < 128;
                    if (a.isSiteLocalAddress() || tailscale) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    void signal(String to, JSONObject data) {
        try {
            if (ws != null) ws.send(new JSONObject().put("type", "signal").put("to", to).put("data", data).toString());
        } catch (Exception e) { Log.w(TAG, "signal", e); }
    }

    private void handle(JSONObject msg) throws Exception {
        switch (msg.optString("type")) {
            case "hello":
                Hub.myId = msg.optString("device");
                break;
            case "devices": {
                Hub.devices = msg.optJSONArray("devices");
                Set<String> on = new HashSet<>();
                org.json.JSONArray o = msg.optJSONArray("online");
                for (int i = 0; o != null && i < o.length(); i++) on.add(o.optString(i));
                Hub.online = on;
                Hub.direct = msg.optJSONObject("direct") != null ? msg.optJSONObject("direct") : new JSONObject();
                Hub.emit(Hub.Listener::onDevices);
                break;
            }
            case "push": {
                JSONObject p = msg.getJSONObject("push");
                Hub.emit(l -> l.onPush(p));
                String src = p.optString("source_device", null), tgt = p.isNull("target_device") ? null : p.optString("target_device");
                prefs.lastPushAt(p.optLong("created_at"));
                if (src != null && src.equals(Hub.myId)) break;
                if (tgt != null && !tgt.equals(Hub.myId)) break;
                Notifier.push(this, p);
                break;
            }
            case "delete":
                Hub.emit(l -> l.onDelete(msg.optString("id")));
                Notifier.cancelPush(this, msg.optString("id"));
                break;
            case "clear":
                Hub.emit(Hub.Listener::onClear);
                break;
            case "signal":
                Direct.onSignal(this, msg.optString("from"), msg.optJSONObject("data"));
                break;
        }
    }

    LocalServer local() { return local; }
}
