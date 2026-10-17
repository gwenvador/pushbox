package io.github.gwenvador.pushbox;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Notification button: copy text (files open through OpenFileActivity). */
public class ActionReceiver extends BroadcastReceiver {
    private static final String COPY = "io.github.gwenvador.pushbox.COPY";

    static PendingIntent copy(Context c, int req, String text) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(COPY).putExtra("text", text);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (COPY.equals(i.getAction())) {
            ClipboardManager cm = c.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("Pushbox", i.getStringExtra("text")));
            Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
        }
    }
}
