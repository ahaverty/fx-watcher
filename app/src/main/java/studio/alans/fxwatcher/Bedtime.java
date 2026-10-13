package studio.alans.fxwatcher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.PowerManager;

import java.util.Calendar;

/**
 * Bedtime media volume. CamAPS FX plays most of its alerts (all but Urgent Low) on the MEDIA
 * volume, so with media turned down for the evening they are silent all night. Off unless
 * switched on in settings.
 *
 * Each night it ARMS at the first of:
 *   - the phone's sleep / bedtime / do-not-disturb mode switching on (after "earliest"),
 *   - Google's sleep detection saying probably asleep (optional, needs Physical activity),
 *   - the fallback time for that evening's weekday (default midnight; a day can be "off").
 * Armed, it shows a quiet notification and sets the volume once the screen has been off for
 * the idle time (default 10 min) with nothing playing, so it never blasts a video or podcast
 * in bed. It only ever raises the volume. If the volume is turned down again before morning (a
 * video in bed at midnight), it is raised again every time the phone is put down for the idle
 * time with nothing playing (12-13 Oct 2026: raised at 22:30, turned down to 2 for YouTube at
 * 00:14, and stayed at 2 all night). In the morning, on the first unlock after the morning
 * time, it puts the volume back where it was before bedtime, unless it was changed meanwhile.
 */
final class Bedtime {

    private Bedtime() { }

    static final String CH = "bedtime";
    private static final int NOTIF = 40;
    private static final int REQ_TICK = 41, REQ_SKIP = 42, REQ_NOW = 43;

    // Per-night state, stored with the night's key (the evening's date)
    private static final String IDLE = "idle", ARMED = "armed", APPLIED = "applied", SKIPPED = "skipped", DONE = "done";

    // ── Settings helpers ───────────────────────────────────────────────────────

    static boolean on(Context c) {
        return Store.p(c).getBoolean(Store.BED_ON, false);
    }

    /** "HH:MM" -> minutes after midnight, or -1 for "off"/garbage. */
    static int minutes(String s) {
        try {
            String[] a = s.trim().split(":");
            int h = Integer.parseInt(a[0]), m = a.length > 1 ? Integer.parseInt(a[1]) : 0;
            return h >= 0 && h < 24 && m >= 0 && m < 60 ? h * 60 + m : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static String hhmm(int minutes) {
        return String.format(java.util.Locale.UK, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Fallback time for the evening starting on calendar day dow (Calendar.MONDAY..SUNDAY). */
    private static int fallback(SharedPreferences p, int dow) {
        String[] days = p.getString(Store.BED_FALLBACK_DAYS, "").split(",");
        int idx = (dow + 5) % 7;    // Monday = 0
        if (days.length == 7 && !days[idx].trim().isEmpty()) {
            return days[idx].trim().equalsIgnoreCase("off") ? -1 : minutes(days[idx]);
        }
        return minutes(p.getString(Store.BED_FALLBACK, Store.DEF_BED_FALLBACK));
    }

    /** The evening a moment belongs to: before noon counts as the previous evening. */
    private static Calendar evening(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (c.get(Calendar.HOUR_OF_DAY) < 12) c.add(Calendar.DAY_OF_MONTH, -1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    /** Wall time on the evening's date (minutes before noon land on the next day). */
    private static long at(Calendar ev, int minutes) {
        Calendar c = (Calendar) ev.clone();
        if (minutes < 12 * 60) c.add(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.MINUTE, minutes);
        return c.getTimeInMillis();
    }

    private static String key(Calendar ev) {
        return ev.get(Calendar.YEAR) + "-" + (ev.get(Calendar.MONTH) + 1) + "-" + ev.get(Calendar.DAY_OF_MONTH);
    }

    private static String state(SharedPreferences p, String key) {
        return key.equals(p.getString(Store.BED_NIGHT, "")) ? p.getString(Store.BED_STATE, IDLE) : IDLE;
    }

    private static void setState(Context c, String key, String st) {
        Store.p(c).edit().putString(Store.BED_NIGHT, key).putString(Store.BED_STATE, st).apply();
    }

    // ── Events ─────────────────────────────────────────────────────────────────

    /** Sleep / DND mode changed (from the notification listener). */
    static void onQuietMode(Context c, boolean quiet) {
        if (!on(c) || !quiet || !Store.p(c).getBoolean(Store.BED_USE_MODE, true)) return;
        arm(c, "sleep / do-not-disturb mode on");
    }

    /** Google sleep detection: confidence 0-100 that the user is asleep now. */
    static void onSleepConfidence(Context c, int confidence) {
        SharedPreferences p = Store.p(c);
        if (!on(c) || !p.getBoolean(Store.BED_USE_GOOGLE, false)) return;
        if (confidence >= p.getInt(Store.BED_GOOGLE_CONF, Store.DEF_BED_GOOGLE_CONF)) {
            arm(c, "Google sleep detection (" + confidence + "%)");
        }
    }

    static void onScreen(Context c, boolean screenOn) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        if (screenOn) p.edit().putLong(Store.BED_SCREEN_OFF, 0).apply();
        else p.edit().putLong(Store.BED_SCREEN_OFF, now).apply();
        if (on(c)) tick(c);
    }

    /** Unlocked: the morning restore waits for this, not just the screen lighting up. */
    static void onUnlock(Context c) {
        if (!on(c)) return;
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        Calendar ev = evening(now);
        String key = key(ev);
        int morning = minutes(p.getString(Store.BED_MORNING, Store.DEF_BED_MORNING));
        if (APPLIED.equals(state(p, key)) && morning >= 0 && now >= at(ev, morning)) restore(c, key);
        tick(c);
    }

    /** "Not tonight" on the notification. */
    static void skip(Context c) {
        setState(c, key(evening(System.currentTimeMillis())), SKIPPED);
        cancelNotification(c);
        Store.log(c, "Bedtime volume: not tonight");
        schedule(c);
    }

    /** "Set it now" on the notification. */
    static void now(Context c) {
        String key = key(evening(System.currentTimeMillis()));
        apply(c, key, "set now from the notification");
        schedule(c);
    }

    private static void arm(Context c, String why) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        Calendar ev = evening(now);
        String key = key(ev);
        if (!IDLE.equals(state(p, key))) return;
        if (fallback(p, ev.get(Calendar.DAY_OF_WEEK)) < 0) return;  // this evening is set to "off"
        int earliest = minutes(p.getString(Store.BED_EARLIEST, Store.DEF_BED_EARLIEST));
        int morning = minutes(p.getString(Store.BED_MORNING, Store.DEF_BED_MORNING));
        boolean signal = !why.startsWith("fallback");
        if (signal && earliest >= 0 && now < at(ev, earliest)) return;   // too early for a signal
        if (morning >= 0 && now >= at(ev, morning)) return;              // already morning
        setState(c, key, ARMED);
        Store.log(c, "Bedtime volume armed: " + why);
        notifyArmed(c);
        tick(c);
    }

    // ── The clock ──────────────────────────────────────────────────────────────

    /** Re-evaluates and schedules the next wake-up. Cheap; called by the watchdog too. */
    static void tick(Context c) {
        if (!on(c)) {
            cancelNotification(c);
            return;
        }
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        Calendar ev = evening(now);
        String key = key(ev);
        String st = state(p, key);

        // Morning restore belongs to LAST night when it is morning now; evening() handles that,
        // since before noon the key is still last evening's.
        int morning = minutes(p.getString(Store.BED_MORNING, Store.DEF_BED_MORNING));
        boolean isMorning = morning >= 0 && now >= at(ev, morning) && now < at(ev, 12 * 60 - 1);
        if (APPLIED.equals(st) && isMorning) {     // restore happens on the next unlock
            schedule(c);
            return;
        }
        if (isMorning && ARMED.equals(st)) {
            setState(c, key, DONE);   // never got to set it; don't set it in the morning
            cancelNotification(c);
        }

        int fb = fallback(p, ev.get(Calendar.DAY_OF_WEEK));
        if (IDLE.equals(st) && !isMorning && p.getBoolean(Store.BED_USE_MODE, true)) {
            int f = c.getSystemService(NotificationManager.class).getCurrentInterruptionFilter();
            if (f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                arm(c, "sleep / do-not-disturb mode on");   // was already on before "earliest"
            }
        }
        st = state(p, key);
        if (IDLE.equals(st) && fb >= 0 && now >= at(ev, fb) && !isMorning) arm(c, "fallback time " + hhmm(fb));
        st = state(p, key);

        if (ARMED.equals(st) || watching(st, isMorning, below(c))) {
            long off = p.getLong(Store.BED_SCREEN_OFF, 0);
            if (!interactive(c) && off == 0) {          // screen already off when armed
                off = now;
                p.edit().putLong(Store.BED_SCREEN_OFF, off).apply();
            }
            long idle = p.getInt(Store.BED_IDLE_MIN, Store.DEF_BED_IDLE_MIN) * 60_000L;
            if (!interactive(c) && off > 0 && now - off >= idle) {
                if (musicPlaying(c)) {
                    Store.log(c, "Bedtime volume: something is playing, waiting");
                } else if (ARMED.equals(st)) {
                    apply(c, key, "phone unused for " + (idle / 60_000L) + " min");
                } else {
                    raiseAgain(c, idle);
                }
            }
        }
        schedule(c);
    }

    private static void schedule(Context c) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        Calendar ev = evening(now);
        String key = key(ev);
        String st = state(p, key);
        long next;
        int mornMin = minutes(p.getString(Store.BED_MORNING, Store.DEF_BED_MORNING));
        boolean isMorning = mornMin >= 0 && now >= at(ev, mornMin) && now < at(ev, 12 * 60 - 1);
        if (ARMED.equals(st) || watching(st, isMorning, below(c))) {
            long off = p.getLong(Store.BED_SCREEN_OFF, 0);
            long idle = p.getInt(Store.BED_IDLE_MIN, Store.DEF_BED_IDLE_MIN) * 60_000L;
            next = off > 0 ? Math.max(off + idle, now + 60_000L) : now + 15 * 60_000L;
            int morning = minutes(p.getString(Store.BED_MORNING, Store.DEF_BED_MORNING));
            if (APPLIED.equals(st) && morning >= 0 && at(ev, morning) > now) next = Math.min(next, at(ev, morning));
            if (musicPlaying(c)) next = now + 5 * 60_000L;
        } else {
            // Next fallback time (tonight or tomorrow) or the morning time, whichever is first.
            next = now + 12 * 3600_000L;
            int fb = fallback(p, ev.get(Calendar.DAY_OF_WEEK));
            if (IDLE.equals(st) && fb >= 0 && at(ev, fb) > now) next = Math.min(next, at(ev, fb));
            // At "earliest", look again: sleep / DND mode may already be on by then.
            int earliest = minutes(p.getString(Store.BED_EARLIEST, Store.DEF_BED_EARLIEST));
            if (IDLE.equals(st) && earliest >= 0 && at(ev, earliest) > now) next = Math.min(next, at(ev, earliest));
            int morning = minutes(p.getString(Store.BED_MORNING, Store.DEF_BED_MORNING));
            if (APPLIED.equals(st) && morning >= 0 && at(ev, morning) > now) next = Math.min(next, at(ev, morning));
            Calendar tomorrow = (Calendar) ev.clone();
            tomorrow.add(Calendar.DAY_OF_MONTH, 1);
            int fb2 = fallback(p, tomorrow.get(Calendar.DAY_OF_WEEK));
            if (fb2 >= 0) next = Math.min(next, at(tomorrow, fb2));
            next = Math.max(next, now + 60_000L);
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, REQ_TICK,
                new Intent(c, ActionReceiver.class).setAction(ActionReceiver.BED_TICK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    // ── Volume ─────────────────────────────────────────────────────────────────

    static int target(Context c) {
        AudioManager a = c.getSystemService(AudioManager.class);
        int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int pct = Store.p(c).getInt(Store.BED_LEVEL_PCT, Store.DEF_BED_LEVEL_PCT);
        return Math.max(1, Math.min(max, Math.round(max * pct / 100f)));
    }

    private static void apply(Context c, String key, String why) {
        AudioManager a = c.getSystemService(AudioManager.class);
        int cur = a.getStreamVolume(AudioManager.STREAM_MUSIC), tgt = target(c);
        SharedPreferences.Editor e = Store.p(c).edit();
        if (cur >= tgt) {
            Store.log(c, "Bedtime volume: media already at " + cur + " (target " + tgt + "), left alone");
            e.putInt(Store.BED_PREV, -1).putInt(Store.BED_SET, -1).apply();
        } else {
            try {
                a.setStreamVolume(AudioManager.STREAM_MUSIC, tgt, 0);
            } catch (SecurityException se) {
                Store.log(c, "Bedtime volume: Android refused (" + se.getMessage() + ")");
                return;
            }
            e.putInt(Store.BED_PREV, cur).putInt(Store.BED_SET, tgt).apply();
            Store.log(c, "Bedtime volume: media " + cur + " -> " + tgt + " (" + why + ")");
        }
        setState(c, key, APPLIED);
        cancelNotification(c);
    }

    /**
     * Applied tonight, still night, and the media volume is below the bedtime level again: watch
     * for the phone being put down, to raise it again. Pure, for the unit tests.
     */
    static boolean watching(String state, boolean morning, boolean below) {
        return APPLIED.equals(state) && !morning && below;
    }

    private static boolean below(Context c) {
        return c.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC) < target(c);
    }

    /** Turned down since it was set: back up. The morning restore still goes to the pre-bedtime level. */
    private static void raiseAgain(Context c, long idle) {
        AudioManager a = c.getSystemService(AudioManager.class);
        int cur = a.getStreamVolume(AudioManager.STREAM_MUSIC), tgt = target(c);
        if (cur >= tgt) return;
        try {
            a.setStreamVolume(AudioManager.STREAM_MUSIC, tgt, 0);
        } catch (SecurityException se) {
            Store.log(c, "Bedtime volume: Android refused (" + se.getMessage() + ")");
            return;
        }
        SharedPreferences p = Store.p(c);
        SharedPreferences.Editor e = p.edit().putInt(Store.BED_SET, tgt);
        // it was already loud enough at bedtime (nothing to restore then): the morning goes back
        // to the level it was turned down to
        if (p.getInt(Store.BED_PREV, -1) < 0) e.putInt(Store.BED_PREV, cur);
        e.apply();
        Store.log(c, "Bedtime volume: media was turned down to " + cur + ", back to " + tgt
                + " (phone unused for " + (idle / 60_000L) + " min)");
    }

    private static void restore(Context c, String key) {
        SharedPreferences p = Store.p(c);
        setState(c, key, DONE);
        int prev = p.getInt(Store.BED_PREV, -1), set = p.getInt(Store.BED_SET, -1);
        if (!p.getBoolean(Store.BED_RESTORE, true) || prev < 0) return;
        AudioManager a = c.getSystemService(AudioManager.class);
        int cur = a.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (cur != set) {
            Store.log(c, "Bedtime volume: changed overnight (" + cur + "), not restoring");
            return;
        }
        a.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0);
        Store.log(c, "Bedtime volume: media back to " + prev);
    }

    private static boolean interactive(Context c) {
        return c.getSystemService(PowerManager.class).isInteractive();
    }

    private static boolean musicPlaying(Context c) {
        return c.getSystemService(AudioManager.class).isMusicActive();
    }

    // ── Notification ───────────────────────────────────────────────────────────

    static void channel(Context c) {
        NotificationChannel ch = new NotificationChannel(CH, "Bedtime media volume", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Says when the media volume will be raised for CamAPS alerts");
        ch.setShowBadge(false);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private static PendingIntent act(Context c, String action, int req) {
        return PendingIntent.getBroadcast(c, req, new Intent(c, ActionReceiver.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void notifyArmed(Context c) {
        channel(c);
        int pct = Store.p(c).getInt(Store.BED_LEVEL_PCT, Store.DEF_BED_LEVEL_PCT);
        int idle = Store.p(c).getInt(Store.BED_IDLE_MIN, Store.DEF_BED_IDLE_MIN);
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Media volume → " + pct + "% for CamAPS alerts")
                .setContentText("Once the phone has been unused for " + idle + " min and nothing is playing.")
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Not tonight", act(c, ActionReceiver.BED_SKIP, REQ_SKIP)).build())
                .addAction(new Notification.Action.Builder(null, "Set it now", act(c, ActionReceiver.BED_NOW, REQ_NOW)).build())
                .build();
        try {
            c.getSystemService(NotificationManager.class).notify(NOTIF, n);
        } catch (SecurityException ignored) {
            // notifications not allowed; it still works, just silently
        }
    }

    private static void cancelNotification(Context c) {
        c.getSystemService(NotificationManager.class).cancel(NOTIF);
    }
}
