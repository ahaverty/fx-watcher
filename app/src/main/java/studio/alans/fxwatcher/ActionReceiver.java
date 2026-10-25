package studio.alans.fxwatcher;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Notification buttons, Android Auto replies, the watchdog timer, the missed bolus check and quiet events. */
public class ActionReceiver extends BroadcastReceiver {

    static final String CHECK = "studio.alans.fxwatcher.CHECK";
    static final String SILENCE = "studio.alans.fxwatcher.SILENCE";
    static final String SWIPED = "studio.alans.fxwatcher.SWIPED";
    static final String SNOOZE = "studio.alans.fxwatcher.SNOOZE";
    static final String UNSNOOZE = "studio.alans.fxwatcher.UNSNOOZE";
    static final String BATT_SNOOZE = "studio.alans.fxwatcher.BATT_SNOOZE";
    static final String REPLY = "studio.alans.fxwatcher.REPLY";
    static final String OPEN_CAMAPS = "studio.alans.fxwatcher.OPEN_CAMAPS";
    static final String BED_TICK = "studio.alans.fxwatcher.BED_TICK";
    static final String BED_SKIP = "studio.alans.fxwatcher.BED_SKIP";
    static final String BED_NOW = "studio.alans.fxwatcher.BED_NOW";
    static final String BOLUS_CHECK = "studio.alans.fxwatcher.BOLUS_CHECK";
    static final String BOLUS_OPEN = "studio.alans.fxwatcher.BOLUS_OPEN";
    static final String BOLUS_DISMISS = "studio.alans.fxwatcher.BOLUS_DISMISS";
    static final String QUIET_END = "studio.alans.fxwatcher.QUIET_END";

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
                case SWIPED:
                    Store.log(c, "Alarm notification swiped away");
                    if (Store.p(c).getInt(Store.LEVEL, Monitor.NONE) == Monitor.ALARM) Monitor.silence(c);
                    else Monitor.evaluate(c);
                    break;
                case SNOOZE: Monitor.snooze(c, i.getDoubleExtra("hours", 2)); break;
                case UNSNOOZE: Monitor.unsnooze(c); break;
                case BATT_SNOOZE: Monitor.snoozeBattery(c); break;
                case OPEN_CAMAPS:
                    Monitor.silence(c);
                    Monitor.launchCamaps(c, "notification button");
                    break;
                case REPLY: reply(c, i); break;
                case BED_TICK: Bedtime.tick(c); break;
                case BED_SKIP: Bedtime.skip(c); break;
                case BED_NOW: Bedtime.now(c); break;
                case BOLUS_CHECK: BolusWatch.check(c); break;
                case BOLUS_OPEN: BolusWatch.openCamaps(c); break;
                case BOLUS_DISMISS: BolusWatch.dismiss(c); break;
                case QUIET_END: Quiet.end(c, i.getBooleanExtra("now", false) ? "ended early" : "time up"); break;
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
        if (text.contains("snooze") && "BATTERY".equals(Store.p(c).getString(Store.PROBLEM, ""))) {
            Monitor.snoozeBattery(c);
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
