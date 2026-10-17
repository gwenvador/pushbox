package io.github.gwenvador.pushbox;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Forwards the phone's notifications to Pushbox, which relays them live to the
 * other devices, and tells it when they are dismissed here.
 */
public class ForwarderService extends NotificationListenerService {
    private static final String TAG = "Pushbox";

    private Prefs prefs;
    /** Notification key -> hash of what was last sent, to skip identical re-posts. */
    private final Map<String, Integer> sent = new HashMap<>();
    private final Map<String, String> iconCache = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!prefs.forwarding() || prefs.token() == null) return;
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName())) return;
        Set<String> blocked = prefs.blocked();
        if (blocked.contains(pkg)) return;

        Notification n = sbn.getNotification();
        // Skip what is noise on a desktop: persistent/foreground notifications,
        // group summaries (the individual messages are forwarded), media and progress.
        if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_GROUP_SUMMARY)) != 0) return;
        String cat = n.category;
        if (Notification.CATEGORY_TRANSPORT.equals(cat) || Notification.CATEGORY_PROGRESS.equals(cat)
                || Notification.CATEGORY_SERVICE.equals(cat)) return;

        Bundle x = n.extras;
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE_BIG));
        if (title == null) title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text == null) text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            StringBuilder b = new StringBuilder();
            for (CharSequence l : lines) { if (b.length() > 0) b.append('\n'); b.append(l); }
            text = b.toString();
        }
        if (title == null && text == null) return;

        String key = sbn.getKey();
        int hash = (title + "\u0000" + text).hashCode();
        Integer prev = sent.get(key);
        if (prev != null && prev == hash) return;
        sent.put(key, hash);
        if (sent.size() > 500) sent.clear();

        boolean silent = prev != null && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
        try {
            JSONObject body = new JSONObject()
                    .put("key", key)
                    .put("package", pkg)
                    .put("app", appLabel(pkg))
                    .put("title", title == null ? JSONObject.NULL : title)
                    .put("text", text == null ? JSONObject.NULL : text)
                    .put("posted_at", sbn.getPostTime())
                    .put("silent_update", silent);
            String icon = appIcon(pkg);
            if (icon != null) body.put("icon", icon);
            send("/api/mirror", body);
        } catch (Exception e) {
            Log.w(TAG, "build notification", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sent.remove(sbn.getKey()) == null || prefs.token() == null) return;
        try {
            send("/api/mirror/dismiss", new JSONObject().put("key", sbn.getKey()));
        } catch (Exception e) {
            Log.w(TAG, "dismiss", e);
        }
    }

    private void send(String path, JSONObject body) {
        final String server = prefs.server(), token = prefs.token();
        Api.IO.execute(() -> {
            try {
                Api.call(server, token, "POST", path, body);
            } catch (Api.HttpError e) {
                if (e.status == 401) prefs.signOut(); // device was removed in Pushbox
                Log.w(TAG, path + ": " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, path + ": " + e);
            }
        });
    }

    private static String str(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** App icon as a small PNG data URL, cached per package. */
    private String appIcon(String pkg) {
        if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
        String url = null;
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            Bitmap bmp = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, 72, 72);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            url = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
        }
        iconCache.put(pkg, url);
        return url;
    }
}
