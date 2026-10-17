package io.github.gwenvador.pushbox;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;

import org.json.JSONObject;

/** System notifications for incoming pushes and transfers. */
final class Notifier {
    private Notifier() { }

    static int idFor(String pushId) { return 1000 + (pushId.hashCode() & 0x3fffffff); }

    static void push(Context c, JSONObject p) {
        String type = p.optString("type");
        String from = Hub.deviceName(p.optString("source_device", null));
        String body = p.isNull("body") ? null : p.optString("body");
        String url = p.isNull("url") ? null : p.optString("url");
        String title = p.isNull("title") ? null : p.optString("title");
        String id = p.optString("id");
        int nid = idFor(id);

        String heading = title != null ? title
                : "file".equals(type) ? "File from " + from
                : "link".equals(type) ? "Link from " + from
                : "Note from " + from;
        String text = "file".equals(type) ? p.optString("file_name") + (body != null ? "\n" + body : "")
                : "link".equals(type) ? (body != null ? body + "\n" : "") + url
                : body;

        Notification.Builder b = new Notification.Builder(c, PushService.CH_PUSH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(heading)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setWhen(p.optLong("created_at", System.currentTimeMillis()))
                .setShowWhen(true);

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if ("link".equals(type)) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(c, nid, view, PendingIntent.FLAG_IMMUTABLE));
            b.addAction(new Notification.Action.Builder(null, "Copy link", ActionReceiver.copy(c, nid, url)).build());
        } else if ("file".equals(type)) {
            // Tapping opens the app; only the button downloads.
            b.setContentIntent(PendingIntent.getActivity(c, nid, open, PendingIntent.FLAG_IMMUTABLE));
            b.addAction(new Notification.Action.Builder(null, "Open", PendingIntent.getActivity(c, nid + 2,
                    OpenFileActivity.Companion.forPush(c, p), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        } else {
            b.setContentIntent(PendingIntent.getActivity(c, nid, open, PendingIntent.FLAG_IMMUTABLE));
        }
        if (body != null) b.addAction(new Notification.Action.Builder(null, "Copy", ActionReceiver.copy(c, nid + 1, body)).build());

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.notify(nid, b.build());

        // Images: add the picture once fetched.
        String mime = p.optString("file_type", "");
        if ("file".equals(type) && mime.startsWith("image/") && p.optLong("file_size") < 12_000_000) {
            Api.IO.execute(() -> {
                Bitmap bmp = Files.fetchImage(c, p.optString("file_url"), 1080);
                if (bmp == null) return;
                b.setLargeIcon(bmp).setStyle(new Notification.BigPictureStyle().bigPicture(bmp).setSummaryText(text));
                nm.notify(nid, b.build());
            });
        }
    }

    static void cancelPush(Context c, String pushId) {
        c.getSystemService(NotificationManager.class).cancel(idFor(pushId));
    }

    /** A direct file waiting in the inbox: tap opens the app, the button saves it. */
    static void received(Context c, Inbox.Item it) {
        int nid = idFor("rx" + it.id);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new Notification.Builder(c, PushService.CH_PUSH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("File from " + it.from + " ⚡")
                .setContentText(it.name + " · " + Files.humanSize(it.size) + ", kept in Pushbox")
                .setContentIntent(PendingIntent.getActivity(c, nid, open, PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, "Open", PendingIntent.getActivity(c, nid + 2,
                        OpenFileActivity.Companion.forInbox(c, it.id), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(nid, n);
    }

    static void saved(Context c, String name, Uri uri, String mime) {
        int nid = idFor("saved" + uri);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new Notification.Builder(c, PushService.CH_TRANSFER)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Saved " + name)
                .setContentText("In Download/Pushbox · tap to open")
                .setContentIntent(PendingIntent.getActivity(c, nid, view, PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(nid, n);
    }

    /** Ongoing progress for uploads; returns the id to update/cancel. */
    static Notification.Builder progress(Context c, String name) {
        return new Notification.Builder(c, PushService.CH_TRANSFER)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Sending " + name)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
    }
}
