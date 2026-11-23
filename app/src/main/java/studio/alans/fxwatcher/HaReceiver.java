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
 *
 * Treating mode, same key: intent_action studio.alans.fxwatcher.HA_TREAT, intent_extras "key:<webhook id>"
 * (or "action:end,key:..." to end it). Home Assistant must only send it when Alan asks.
 *
 * Nudges Home Assistant decided on, the same way: intent_action studio.alans.fxwatcher.HA_NUDGE,
 * intent_extras "kind:walking,key:<webhook id>" (or kind:late_home). Shown and logged by Nudges.
 */
public class HaReceiver extends BroadcastReceiver {

    static final String HA_SNOOZE = "studio.alans.fxwatcher.HA_SNOOZE";
    static final String HA_NUDGE = "studio.alans.fxwatcher.HA_NUDGE";
    static final String HA_TREAT = "studio.alans.fxwatcher.HA_TREAT";

    @Override
    public void onReceive(Context c, Intent i) {
        if (!HA_SNOOZE.equals(i.getAction()) && !HA_NUDGE.equals(i.getAction()) && !HA_TREAT.equals(i.getAction())) return;
        Bundle x = i.getExtras();
        if (x == null) return;
        String key = String.valueOf(x.get("key"));
        String url = Store.p(c).getString(Store.URL, "");
        if (key.length() < 8 || url == null || !url.endsWith(key)) {
            Store.log(c, "Ignored a Home Assistant broadcast with the wrong key");
            return;
        }
        if (HA_TREAT.equals(i.getAction())) {
            // Armed by Alan from Home Assistant (a button or voice command). "action:end" ends it.
            if ("end".equals(String.valueOf(x.get("action")))) Treating.end(c, "ended from Home Assistant");
            else Treating.start(c, "Home Assistant");
            return;
        }
        if (HA_NUDGE.equals(i.getAction())) {
            Nudges.fromHa(c, String.valueOf(x.get("kind")));
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
