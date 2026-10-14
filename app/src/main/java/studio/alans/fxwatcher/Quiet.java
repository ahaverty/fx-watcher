package studio.alans.fxwatcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;

import java.util.Calendar;

/**
 * Quiet event (cinema, wedding ceremony): media volume to 0 and the phone on vibrate until a set
 * time, FX Watcher's own alarms buzz instead of ring (urgent low still rings), and the bedtime
 * volume raise waits. At the end the volume and ringer go back, unless they were changed meanwhile.
 *
 * Media matters because CamAPS plays all its alerts except Urgent Low on the media volume.
 */
final class Quiet {

    private Quiet() { }

    private static final int REQ_END = 60;

    static boolean active(Context c) {
        return Store.p(c).getLong(Store.QUIET_UNTIL, 0) > System.currentTimeMillis();
    }

    static long until(Context c) {
        return Store.p(c).getLong(Store.QUIET_UNTIL, 0);
    }

    /** Only urgent low still rings during a quiet event. Pure, for the unit tests. */
    static boolean mutes(String code) {
        return !"LOW".equals(code);
    }

    /** The next time the clock shows hour:minute, today or tomorrow. Pure, for the unit tests. */
    static long nextAt(long now, int hour, int minute) {
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(now);
        t.set(Calendar.HOUR_OF_DAY, hour);
        t.set(Calendar.MINUTE, minute);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (t.getTimeInMillis() <= now) t.add(Calendar.DAY_OF_MONTH, 1);
        return t.getTimeInMillis();
    }

    /**
     * What to put a setting back to at the end: the old value if it is still what we set, else -1
     * (it was changed during the event, so leave it). Pure, for the unit tests.
     */
    static int restore(int now, int set, int prev) {
        return prev >= 0 && set >= 0 && now == set && now != prev ? prev : -1;
    }

    static void start(Context c, long until) {
        long now = System.currentTimeMillis();
        if (until <= now) return;
        SharedPreferences p = Store.p(c);
        if (active(c)) {   // already quiet: just move the end time, keep what is to be put back
            p.edit().putLong(Store.QUIET_UNTIL, until).apply();
            Store.log(c, "Quiet event now until " + Store.hm(until));
            schedule(c, until);
            Monitor.evaluate(c);
            return;
        }
        AudioManager a = c.getSystemService(AudioManager.class);
        SharedPreferences.Editor e = p.edit().putLong(Store.QUIET_UNTIL, until)
                .putInt(Store.QUIET_MEDIA_PREV, a.getStreamVolume(AudioManager.STREAM_MUSIC))
                .putInt(Store.QUIET_RINGER_PREV, a.getRingerMode());
        StringBuilder did = new StringBuilder();
        try {
            a.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            e.putInt(Store.QUIET_MEDIA_SET, 0);
            did.append("media 0");
        } catch (SecurityException se) {
            e.putInt(Store.QUIET_MEDIA_SET, -1);
            did.append("media refused");
        }
        if (a.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            try {
                a.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                e.putInt(Store.QUIET_RINGER_SET, AudioManager.RINGER_MODE_VIBRATE);
                did.append(", vibrate");
            } catch (SecurityException se) {
                e.putInt(Store.QUIET_RINGER_SET, -1);
                did.append(", vibrate refused");
            }
        } else {
            e.putInt(Store.QUIET_RINGER_SET, -1);   // already vibrate or silent: leave it alone
            did.append(", ringer already quiet");
        }
        e.apply();
        Store.log(c, "Quiet event until " + Store.hm(until) + " (" + did + ")");
        schedule(c, until);
        Monitor.evaluate(c);
    }

    static void end(Context c, String why) {
        SharedPreferences p = Store.p(c);
        if (p.getLong(Store.QUIET_UNTIL, 0) == 0) return;
        AudioManager a = c.getSystemService(AudioManager.class);
        StringBuilder did = new StringBuilder();
        int media = restore(a.getStreamVolume(AudioManager.STREAM_MUSIC),
                p.getInt(Store.QUIET_MEDIA_SET, -1), p.getInt(Store.QUIET_MEDIA_PREV, -1));
        if (media >= 0) {
            try {
                a.setStreamVolume(AudioManager.STREAM_MUSIC, media, 0);
                did.append("media back to ").append(media);
            } catch (SecurityException ignored) { }
        }
        int ringer = restore(a.getRingerMode(),
                p.getInt(Store.QUIET_RINGER_SET, -1), p.getInt(Store.QUIET_RINGER_PREV, -1));
        if (ringer >= 0) {
            try {
                a.setRingerMode(ringer);
                did.append(did.length() > 0 ? ", " : "").append("sound on");
            } catch (SecurityException ignored) { }
        }
        p.edit().putLong(Store.QUIET_UNTIL, 0).remove(Store.QUIET_MEDIA_PREV).remove(Store.QUIET_MEDIA_SET)
                .remove(Store.QUIET_RINGER_PREV).remove(Store.QUIET_RINGER_SET).apply();
        c.getSystemService(AlarmManager.class).cancel(endIntent(c));
        Store.log(c, "Quiet event over (" + why + ")" + (did.length() > 0 ? ": " + did : ": nothing to put back"));
        Alerts.quietOver(c, did.length() > 0 ? "Sound is back on (" + did + ")." : "Volume left as you set it.");
        Monitor.evaluate(c);
        Bedtime.tick(c);
    }

    /** From the watchdog and at boot: end an event whose time has passed (the exact alarm may be lost). */
    static void tick(Context c) {
        long until = until(c);
        if (until == 0) return;
        if (until <= System.currentTimeMillis()) end(c, "time up");
        else schedule(c, until);
    }

    private static PendingIntent endIntent(Context c) {
        return PendingIntent.getBroadcast(c, REQ_END,
                new Intent(c, ActionReceiver.class).setAction(ActionReceiver.QUIET_END),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void schedule(Context c, long at) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, endIntent(c));
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, endIntent(c));
        }
    }

    private static boolean installed(Context c, String... pkgs) {
        PackageManager pm = c.getPackageManager();
        for (String pkg : pkgs) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return false;
    }

    /** The walkthrough shown before starting: what it does, what still rings, what still buzzes. */
    static String guide(Context c, long until) {
        SharedPreferences p = Store.p(c);
        float low = p.getFloat(Store.LOW_MMOL, Store.DEF_LOW_MMOL);
        boolean garmin = installed(c, "com.garmin.android.apps.connectmobile");
        boolean wear = installed(c, "com.google.android.wearable.app", "com.samsung.android.app.watchmanager");
        StringBuilder s = new StringBuilder();
        s.append("FX WATCHER WILL\n")
                .append("• Turn media volume to 0. CamAPS plays its alerts (all but Urgent Low) on media volume, "
                        + "so they go silent.\n")
                .append("• Put the phone on vibrate: calls, messages and other apps.\n")
                .append("• Make its own alarms buzz and show instead of ring.\n");
        if (Bedtime.on(c)) s.append("• Hold off the bedtime volume raise.\n");
        s.append("• At ").append(Store.hm(until)).append(", put the volume and ringer back (unless you changed "
                + "them) and tell you.\n\n");

        s.append("STILL MAKES SOUND, CAN'T BE SILENCED\n")
                .append("• CamAPS Urgent Low alarm.\n");
        if (low > 0) s.append("• FX Watcher urgent low (").append(low).append(" mmol/L or below).\n");
        s.append("• Pump alarms (blockage, empty cartridge or battery): they come from the pump itself.\n");
        if (installed(c, "com.dexcom.g7")) s.append("• Dexcom G7 app: Urgent Low always sounds.\n");
        if (installed(c, "com.eveningoutpost.dexdrip")) {
            s.append("• xDrip+: alerts set to override silent mode. Use Snooze All Alerts in xDrip if needed.\n");
        }
        s.append("• Clock alarms you have set.\n\n");

        s.append("SILENT, BUT STILL BUZZ OR SHOW\n")
                .append("• CamAPS alerts (hypo, high, signal loss and the rest) still show on the phone")
                .append(garmin || wear ? " and the watch" : "").append(".\n")
                .append("• FX Watcher warnings and alarms buzz the phone")
                .append(garmin || wear ? " and the watch" : "").append(".\n")
                .append("• Calls and messages vibrate.\n");
        if (garmin) {
            s.append("• Garmin: phone notifications still buzz on your wrist, the quietest way to notice "
                    + "a hypo alert. Garmin's own Do Not Disturb hides them, so leave it off.\n");
        }
        if (wear) {
            s.append("• Watch: notifications still buzz on the wrist. Theatre mode or the watch's Do Not "
                    + "Disturb hides them.\n");
        }
        if (!garmin && !wear) {
            s.append("• A smartwatch would buzz with these too, if you wear one. Its Do Not Disturb hides them.\n");
        }
        s.append("\nKeep the phone somewhere you'll feel it buzz.");
        return s.toString();
    }
}
