package studio.alans.fxwatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Snooze sync from Home Assistant, sent by the HA companion app as a broadcast intent:
 *
 *   action: notify.mobile_app_...
 *   data:
 *     message: command_broadcast_intent
 *     data:
 *       intent_package_name: studio.alans.fxwatcher
 *       intent_action: studio.alans.fxwatcher.HA_SNOOZE
 *       intent_extras: "until:<epoch ms, 0 = cancel>:long,key:<webhook id>"
 *
 * The key must match the webhook id FX Watcher posts to, so other apps can't silence it.
 */
public class HaReceiver extends BroadcastReceiver {

    static final String HA_SNOOZE = "studio.alans.fxwatcher.HA_SNOOZE";

    @Override
    public void onReceive(Context c, Intent i) {
        if (!HA_SNOOZE.equals(i.getAction())) return;
        Bundle x = i.getExtras();
        if (x == null) return;
        String key = String.valueOf(x.get("key"));
        String url = Store.p(c).getString(Store.URL, "");
        if (key.length() < 8 || url == null || !url.endsWith(key)) {
            Store.log(c, "Ignored a snooze broadcast with the wrong key");
            return;
        }
        long until;
        try {
            until = Long.parseLong(String.valueOf(x.get("until")).trim());
        } catch (NumberFormatException e) {
            Store.log(c, "Ignored a snooze broadcast without a valid 'until'");
            return;
        }
        Monitor.setSnooze(c, until, true);
    }
}
