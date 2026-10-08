package studio.alans.fxwatcher;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Notification buttons, Android Auto replies and the watchdog timer. */
public class ActionReceiver extends BroadcastReceiver {

    static final String CHECK = "studio.alans.fxwatcher.CHECK";
    static final String SILENCE = "studio.alans.fxwatcher.SILENCE";
    static final String SNOOZE = "studio.alans.fxwatcher.SNOOZE";
    static final String UNSNOOZE = "studio.alans.fxwatcher.UNSNOOZE";
    static final String REPLY = "studio.alans.fxwatcher.REPLY";
    static final String OPEN_CAMAPS = "studio.alans.fxwatcher.OPEN_CAMAPS";

    private static final String[] NUMBERS = {"zero", "one", "two", "three", "four", "five", "six",
            "seven", "eight", "nine", "ten", "eleven", "twelve"};

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        PendingResult pending = goAsync();
        try {
            switch (a) {
                case CHECK: Monitor.check(c); break;
                case SILENCE: Monitor.silence(c); break;
                case SNOOZE: Monitor.snooze(c, i.getDoubleExtra("hours", 2)); break;
                case UNSNOOZE: Monitor.unsnooze(c); break;
                case OPEN_CAMAPS:
                    Monitor.silence(c);
                    Monitor.launchCamaps(c, "notification button");
                    break;
                case REPLY: reply(c, i); break;
                default: break;
            }
        } finally {
            pending.finish();
        }
    }

    /** "snooze 3" / "snooze three hours" snoozes, "resume" cancels a snooze, anything else silences. */
    private static void reply(Context c, Intent i) {
        Bundle r = RemoteInput.getResultsFromIntent(i);
        CharSequence cs = r == null ? null : r.getCharSequence(Alerts.KEY_REPLY);
        String text = cs == null ? "" : cs.toString().toLowerCase(Locale.UK);
        Store.log(c, "Reply: " + text);
        if (text.contains("resume") || text.contains("cancel") || text.contains("unsnooze")) {
            Monitor.unsnooze(c);
            return;
        }
        if (text.contains("snooze") || text.contains("hour")) {
            double hours = 2;
            Matcher m = Pattern.compile("(\\d+(\\.\\d+)?)").matcher(text);
            if (m.find()) hours = Double.parseDouble(m.group(1));
            else {
                for (int n = 1; n < NUMBERS.length; n++) {
                    if (text.matches(".*\\b" + NUMBERS[n] + "\\b.*")) { hours = n; break; }
                }
            }
            if (text.contains("half") && hours == 2) hours = 0.5;
            Monitor.snooze(c, Math.min(12, Math.max(0.25, hours)));
            return;
        }
        Monitor.silence(c);
    }
}
