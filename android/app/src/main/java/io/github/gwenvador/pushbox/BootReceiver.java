package io.github.gwenvador.pushbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reconnect after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        PushService.start(c);
    }
}
