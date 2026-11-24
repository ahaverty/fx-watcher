package studio.alans.fxwatcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Treating mode: quiet CamAPS's media-volume alerts while you are already handling a low.
 *
 * What it does, and all it does: media volume to 0 (CamAPS plays its alerts on the media stream),
 * and, while glucose is low or a CamAPS alert has just posted, set the media volume back to 0 if
 * CamAPS lifts it for an Urgent low (see camaps-re/findings.md, "Urgent low audio"). FX Watcher's
 * own urgent low buzzes instead of ringing. It does NOT touch the ringer, Do Not Disturb, the
 * pump, CamAPS or any CamAPS setting.
 *
 * Guardrails (each one has a unit test):
 *  - Only ever armed by a person: the app button, the notification action, or Home Assistant's
 *    broadcast. Nothing in the app calls start() on its own (TreatingTest scans for that).
 *  - 20 min by default, 10 to 30 configurable; the status notification counts down.
 *  - Ends early, restores the volume and rings FX Watcher's alarm at full volume if glucose goes
 *    under 2.5, or if it is not higher than when armed 15 min in (or no reading by then).
 *  - State is persisted. An exact alarm, the watchdog and the boot receiver all end it, so the
 *    volume comes back after the app is killed or the phone restarts.
 *
 * Not promised: Urgent low is not guaranteed silent. CamAPS creates its player right after lifting
 * the volume, so a short sound may get through before FX Watcher sets it back; it may also fail.
 */
final class Treating {

    private Treating() { }

    static final long MIN = 60_000L;
    static final int DEF_MIN = 20, MIN_MIN = 10, MAX_MIN = 30;
    static final double ESCALATE_BELOW = 2.5;
    static final long MUST_RISE_AFTER = 15 * MIN;
    /** A volume lift is countered while glucose is under this (an Urgent low may be sounding)... */
    static final double COUNTER_BELOW = 3.5;
    /** ...or within this long of a CamAPS alert notification. */
    static final long COUNTER_AFTER_ALERT = 30_000L;

    private static final int REQ_END = 61;

    // ---- pure rules (unit tested) ----

    static int clampMinutes(int m) {
        return Math.max(MIN_MIN, Math.min(MAX_MIN, m));
    }

    enum End { NONE, TIME_UP, VERY_LOW, NOT_RISING, NO_DATA }

    /**
     * Whether the window should end now. glucose < 0 means unknown. A reading older than 12 min
     * counts as unknown. At the 15 min mark glucose must be higher than when it was armed.
     */
    static End decide(long now, long armedAt, long until, double baseline, double glucose, long glucoseAt) {
        boolean known = glucose > 0 && now - glucoseAt <= 12 * MIN;
        if (known && glucose < ESCALATE_BELOW) return End.VERY_LOW;
        if (now >= until) return End.TIME_UP;
        if (now - armedAt >= MUST_RISE_AFTER) {
            if (!known) return End.NO_DATA;
            if (!(glucose >= baseline + 0.1 - 1e-9)) return End.NOT_RISING;
        }
        return End.NONE;
    }

    /** Escalations ring FX Watcher's alarm at full volume; a plain time-up or a button does not. */
    static boolean escalates(End e) {
        return e == End.VERY_LOW || e == End.NOT_RISING || e == End.NO_DATA;
    }

    /** FX Watcher's own urgent low only buzzes while treating. Everything else is left as it is. */
    static boolean mutes(boolean active, String problemCode) {
        return active && "LOW".equals(problemCode);
    }

    /** Whether to set the media volume back to 0 after seeing it change to newVolume. */
    static boolean shouldCounter(boolean active, int newVolume, double glucose, long glucoseAt,
                                 long lastAlertAt, long now) {
        if (!active || newVolume <= 0) return false;
        boolean lowNow = glucose > 0 && glucose < COUNTER_BELOW && now - glucoseAt <= 15 * MIN;
        return lowNow || (lastAlertAt > 0 && now - lastAlertAt <= COUNTER_AFTER_ALERT);
    }

    /** What to put the media volume back to: the old value if it is still what we set, else -1. */
    static int restoreMedia(int now, int set, int prev) {
        return Quiet.restore(now, set, prev);
    }

    // ---- state ----

    static boolean active(Context c) {
        return Store.p(c).getLong(Store.TREAT_UNTIL, 0) > System.currentTimeMillis();
    }

    static long until(Context c) {
        return Store.p(c).getLong(Store.TREAT_UNTIL, 0);
    }

    static int minutes(Context c) {
        return clampMinutes(Store.p(c).getInt(Store.TREAT_MIN, DEF_MIN));
    }

    /** The only ways in are ActionReceiver (notification action), HaReceiver (Home Assistant) and MainActivity (button). */
    static void start(Context c, String source) {
        long now = System.currentTimeMillis();
        SharedPreferences p = Store.p(c);
        if (p.getLong(Store.TREAT_UNTIL, 0) > now) return;          // already on; a second tap changes nothing
        long until = now + minutes(c) * MIN;
        double baseline = p.getFloat(Store.MMOL, -1f);
        long seen = p.getLong(Store.LAST_SEEN, 0);
        if (baseline <= 0 || now - seen > 10 * MIN) baseline = Double.NaN;   // first reading after arming becomes it
        AudioManager a = c.getSystemService(AudioManager.class);
        int prev = a.getStreamVolume(AudioManager.STREAM_MUSIC);
        int set = -1;
        String did;
        if (Quiet.active(c)) {
            did = "media already 0 by the Quiet event";
        } else {
            try {
                a.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                set = 0;
                did = "media volume 0";
            } catch (SecurityException se) {
                did = "media volume refused";
            }
        }
        p.edit().putLong(Store.TREAT_UNTIL, until).putLong(Store.TREAT_AT, now)
                .putFloat(Store.TREAT_BASE, (float) baseline)
                .putInt(Store.TREAT_MEDIA_PREV, prev).putInt(Store.TREAT_MEDIA_SET, set)
                .putLong(Store.TREAT_ESCALATE_AT, 0).apply();
        Store.logCam(c, "Treating mode started by " + source + " until " + Store.hm(until) + " (" + did
                + String.format(Locale.US, ", glucose %s", Double.isNaN(baseline) ? "unknown" : String.format(Locale.US, "%.1f", baseline)) + ")");
        schedule(c, until);
        watchVolume(c);
        LowWarnings.dismiss(c);
        Monitor.evaluate(c);
    }

    /** From a button: back to normal. */
    static void end(Context c, String why) {
        finish(c, End.NONE, why);
    }

    private static void finish(Context c, End reason, String why) {
        SharedPreferences p = Store.p(c);
        if (p.getLong(Store.TREAT_UNTIL, 0) == 0) return;
        AudioManager a = c.getSystemService(AudioManager.class);
        int media = restoreMedia(a.getStreamVolume(AudioManager.STREAM_MUSIC),
                p.getInt(Store.TREAT_MEDIA_SET, -1), p.getInt(Store.TREAT_MEDIA_PREV, -1));
        String did = "nothing to put back";
        if (media >= 0) {
            try {
                a.setStreamVolume(AudioManager.STREAM_MUSIC, media, 0);
                did = "media volume back to " + media;
            } catch (SecurityException ignored) {
                did = "media volume could not be restored";
            }
        }
        SharedPreferences.Editor e = p.edit().putLong(Store.TREAT_UNTIL, 0).remove(Store.TREAT_AT).remove(Store.TREAT_BASE)
                .remove(Store.TREAT_MEDIA_PREV).remove(Store.TREAT_MEDIA_SET);
        if (escalates(reason)) e.putLong(Store.TREAT_ESCALATE_AT, System.currentTimeMillis());
        e.apply();
        unwatchVolume(c);
        c.getSystemService(AlarmManager.class).cancel(endIntent(c));
        Store.logCam(c, "Treating mode over (" + why + "): " + did + (escalates(reason) ? ", ringing the alarm" : ""));
        Monitor.evaluate(c);
    }

    /** Called with every CamAPS reading. */
    static void reading(Context c, double mmol, long now) {
        SharedPreferences p = Store.p(c);
        long until = p.getLong(Store.TREAT_UNTIL, 0);
        if (until == 0) return;
        double base = p.getFloat(Store.TREAT_BASE, Float.NaN);
        if (Double.isNaN(base) && mmol > 0) {            // armed with no fresh reading: this one is the start
            base = mmol;
            p.edit().putFloat(Store.TREAT_BASE, (float) mmol).apply();
        }
        check(c, mmol, now, now);
    }

    /** Watchdog, alarm and boot: end a window whose time or conditions have come, put the volume back. */
    static void tick(Context c) {
        SharedPreferences p = Store.p(c);
        long until = p.getLong(Store.TREAT_UNTIL, 0);
        if (until == 0) return;
        long now = System.currentTimeMillis();
        double g = p.getFloat(Store.MMOL, -1f);
        check(c, g, p.getLong(Store.LAST_SEEN, 0), now);
        if (p.getLong(Store.TREAT_UNTIL, 0) != 0) {       // still on: make sure the alarm and the guard exist
            schedule(c, p.getLong(Store.TREAT_UNTIL, 0));
            watchVolume(c);
        }
    }

    private static void check(Context c, double glucose, long glucoseAt, long now) {
        SharedPreferences p = Store.p(c);
        double base = p.getFloat(Store.TREAT_BASE, Float.NaN);
        End e = decide(now, p.getLong(Store.TREAT_AT, now), p.getLong(Store.TREAT_UNTIL, 0),
                Double.isNaN(base) ? -1 : base, glucose, glucoseAt);
        if (e == End.NONE) return;
        String why = e == End.TIME_UP ? "time up"
                : e == End.VERY_LOW ? "glucose under " + ESCALATE_BELOW
                : e == End.NOT_RISING ? "glucose not higher after 15 min"
                : "no glucose reading after 15 min";
        finish(c, e, why);
    }

    /** The next time the watchdog must look: the 15 min mark or the end. 0 when not treating. */
    static long nextCheck(Context c) {
        SharedPreferences p = Store.p(c);
        long until = p.getLong(Store.TREAT_UNTIL, 0);
        if (until == 0) return 0;
        long mark = p.getLong(Store.TREAT_AT, 0) + MUST_RISE_AFTER;
        long now = System.currentTimeMillis();
        return mark > now ? Math.min(mark, until) : until;
    }

    // ---- alarm and volume guard ----

    private static PendingIntent endIntent(Context c) {
        return PendingIntent.getBroadcast(c, REQ_END,
                new Intent(c, ActionReceiver.class).setAction(ActionReceiver.TREAT_TICK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void schedule(Context c, long at) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long when = Math.min(at, nextCheck(c) > 0 ? nextCheck(c) : at);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, endIntent(c));
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, endIntent(c));
        }
    }

    private static BroadcastReceiver guard;

    /** Sets the media volume back to 0 when CamAPS lifts it for an Urgent low. Logs how fast. */
    private static synchronized void watchVolume(Context c) {
        if (guard != null) return;
        final Context app = c.getApplicationContext();
        guard = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return;
                int value = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                SharedPreferences p = Store.p(ctx);
                long now = System.currentTimeMillis();
                boolean counter = shouldCounter(Treating.active(ctx), value, p.getFloat(Store.MMOL, -1f),
                        p.getLong(Store.LAST_SEEN, 0), p.getLong(Store.CAM_ALERT_AT, 0), now);
                if (!counter) return;
                long t0 = SystemClock.elapsedRealtime();
                try {
                    ctx.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                    Store.logCam(ctx, "Treating: media volume went to " + value + " (CamAPS alert?), set back to 0 in "
                            + (SystemClock.elapsedRealtime() - t0) + " ms");
                } catch (SecurityException se) {
                    Store.logCam(ctx, "Treating: could not set media volume back to 0");
                }
            }
        };
        try {
            app.registerReceiver(guard, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                    Context.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            guard = null;
        }
    }

    private static synchronized void unwatchVolume(Context c) {
        if (guard == null) return;
        try {
            c.getApplicationContext().unregisterReceiver(guard);
        } catch (Exception ignored) { }
        guard = null;
    }

    // ---- the words ----

    /** Exactly what still rings and what does not. Shown before starting and kept in the README. */
    static String guide(Context c, int minutes) {
        StringBuilder s = new StringBuilder();
        s.append("TREATING MODE, ").append(minutes).append(" MIN\n")
                .append("For when you are already handling a low and the alerts only nag. You arm it; it never starts itself.\n\n")
                .append("FX WATCHER WILL\n")
                .append("• Turn media volume to 0 (CamAPS plays its alerts there).\n")
                .append("• Set it to 0 again if CamAPS lifts it for an Urgent low. This is a race, so a short sound may still get through, and it may not work at all.\n")
                .append("• Make its own urgent low buzz the phone and watch instead of ringing.\n")
                .append("• Count down in the status notification, then put the volume back.\n\n")
                .append("IT ENDS EARLY AND RINGS AT FULL VOLUME IF\n")
                .append("• glucose goes under ").append(String.format(Locale.US, "%.1f", ESCALATE_BELOW)).append(", or\n")
                .append("• glucose is not higher than now after 15 min (or there is no reading by then).\n\n")
                .append("STILL RINGS, NOT TOUCHED\n")
                .append("• Pump alarms (blockage, empty cartridge or battery, no delivery): they come from the pump itself.\n")
                .append("• Dexcom G7 app alarms, clock alarms, calls.\n")
                .append("• CamAPS Urgent low if the volume trick above loses the race.\n\n")
                .append("STILL BUZZES\n")
                .append("• CamAPS alerts still show, and the phone and watch still buzz for them.\n")
                .append("• FX Watcher's own warnings, and its urgent low, buzz.\n\n")
                .append("It changes the media volume and nothing else. Not the ringer, not Do Not Disturb, not CamAPS.");
        return s.toString();
    }
}
