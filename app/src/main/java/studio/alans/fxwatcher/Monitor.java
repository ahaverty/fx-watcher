package studio.alans.fxwatcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.List;

/**
 * Decides whether something is wrong with CamAPS and how loudly to say so.
 *
 * Problems, most serious first:
 *  MISSING  the CamAPS ongoing notification is gone (app killed or crashed)
 *  STALE    FX Watcher has not been able to confirm CamAPS for a while (lost notification access?)
 *  LOW      urgent low glucose (not snoozable)
 *  SIGNAL   auto mode is not "On", or no glucose is shown (sensor or pump signal loss)
 *
 * Each problem first raises a warning (normal notification, reaches the watch and the car),
 * then a full alarm (alarm-stream sound at full volume, vibration, full-screen alert).
 */
final class Monitor {

    private Monitor() { }

    static final int NONE = 0, WARN = 1, ACKED = 2, ALARM = 3;

    static final class Problem {
        String code;
        String title;
        String detail;
        long since;
        long warnAt;
        long alarmAt;
        boolean snoozable = true;
        boolean launch;
    }

    /** New CamAPS state from the notification listener (or a simulation). */
    static synchronized void onCamaps(Context c, boolean present, String status, String glucose,
                                      List<String> texts, String pkg, boolean simulated) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        if (!simulated && p.getLong(Store.SIM_UNTIL, 0) > now) { // a test is holding the state
            if (present) p.edit().putLong(Store.LAST_SEEN, now).apply();
            evaluate(c);
            return;
        }

        boolean wasPresent = p.getBoolean(Store.PRESENT, false);
        String oldStatus = p.getString(Store.STATUS, "");
        String oldTexts = p.getString(Store.TEXTS, "");
        SharedPreferences.Editor e = p.edit();

        if (present) {
            double mmol = toMmol(glucose, pkg);
            boolean bad = !"On".equalsIgnoreCase(status) || mmol < 0;
            float lowThr = p.getFloat(Store.LOW_MMOL, Store.DEF_LOW_MMOL);
            boolean low = lowThr > 0 && mmol >= 0 && mmol <= lowThr;
            String joined = String.join(" | ", texts);

            e.putBoolean(Store.PRESENT, true)
                    .putLong(Store.LAST_SEEN, now)
                    .putLong(Store.MISSING_SINCE, 0)
                    .putString(Store.STATUS, status)
                    .putString(Store.GLUCOSE, glucose == null ? "" : glucose)
                    .putFloat(Store.MMOL, (float) mmol)
                    .putString(Store.TEXTS, joined)
                    .putString(Store.PKG, pkg)
                    .putString(Store.LAST, Store.hm(now) + "  " + status + "  " + texts);
            if (!bad) e.putLong(Store.BAD_SINCE, 0);
            else if (p.getLong(Store.BAD_SINCE, 0) == 0) e.putLong(Store.BAD_SINCE, now);
            if (!low) e.putLong(Store.LOW_SINCE, 0);
            else if (p.getLong(Store.LOW_SINCE, 0) == 0) e.putLong(Store.LOW_SINCE, now);
            e.apply();

            if (!wasPresent) Store.log(c, "CamAPS notification present: " + joined);
            else if (!status.equals(oldStatus)) Store.log(c, "Auto mode " + oldStatus + " -> " + status);
            if (!simulated && (!joined.equals(oldTexts) || !wasPresent)) {
                Double g = mmol < 0 || glucose == null ? null : parse(glucose);
                CamapsListener.forward(c, status, g, texts, pkg);
            }
        } else {
            if (wasPresent || p.getLong(Store.MISSING_SINCE, 0) == 0) {
                e.putBoolean(Store.PRESENT, false);
                if (p.getLong(Store.MISSING_SINCE, 0) == 0) e.putLong(Store.MISSING_SINCE, now);
                e.putString(Store.LAST, Store.hm(now) + "  No CamAPS notification");
                e.apply();
                if (wasPresent) {
                    Store.log(c, "CamAPS notification is gone");
                    if (!simulated) CamapsListener.forward(c, "No notification", null, texts, null);
                }
            }
        }
        evaluate(c);
    }

    private static Double parse(String s) {
        try { return Double.parseDouble(s.replace(',', '.')); } catch (Exception e) { return null; }
    }

    /** Glucose in mmol/L, -1 if none. LOW/HIGH are the sensor's out-of-range readings. */
    static double toMmol(String glucose, String pkg) {
        if (glucose == null || glucose.isEmpty()) return -1;
        if (glucose.equalsIgnoreCase("LOW")) return 2.1;
        if (glucose.equalsIgnoreCase("HIGH")) return 22.3;
        Double g = parse(glucose);
        if (g == null) return -1;
        return pkg != null && pkg.endsWith("mgdl") ? g / 18.0 : g;
    }

    /** The current problem, or null if all is well. */
    static Problem problem(Context c, long now) {
        SharedPreferences p = Store.p(c);
        long lastSeen = p.getLong(Store.LAST_SEEN, 0);
        boolean present = p.getBoolean(Store.PRESENT, false);
        long missingSince = p.getLong(Store.MISSING_SINCE, 0);
        long staleMs = Store.minutes(c, Store.STALE_MIN, Store.DEF_STALE_MIN);
        long testUntil = p.getLong(Store.TEST_UNTIL, 0);

        if (testUntil > now) {
            Problem t = new Problem();
            t.code = "TEST";
            t.title = "Test alarm";
            t.detail = "This is what a CamAPS signal-loss alarm looks and sounds like.";
            t.since = t.warnAt = t.alarmAt = testUntil - 120_000L;
            return t;
        }

        if (!present && missingSince > 0) {
            Problem m = new Problem();
            m.code = "MISSING";
            m.title = "CamAPS FX isn't running";
            m.detail = "Its notification disappeared at " + Store.hm(missingSince)
                    + ". FX Watcher will try to restart it; open CamAPS and check the pump and sensor.";
            m.since = missingSince;
            m.warnAt = missingSince;
            m.alarmAt = missingSince + Store.minutes(c, Store.MISSING_MIN, Store.DEF_MISSING_MIN);
            m.launch = true;
            return m;
        }

        if (lastSeen > 0 && now - lastSeen > staleMs) {
            Problem s = new Problem();
            s.code = "STALE";
            s.title = "Can't see CamAPS";
            s.detail = "No CamAPS update since " + Store.hm(lastSeen)
                    + ". FX Watcher may have lost notification access. Open FX Watcher to check.";
            s.since = lastSeen;
            s.warnAt = s.alarmAt = lastSeen + staleMs;
            s.launch = true;
            return s;
        }

        if (!present) return null;
        String status = p.getString(Store.STATUS, "");
        String glucose = p.getString(Store.GLUCOSE, "");
        String texts = p.getString(Store.TEXTS, "");

        long lowSince = p.getLong(Store.LOW_SINCE, 0);
        if (lowSince > 0) {
            Problem l = new Problem();
            l.code = "LOW";
            l.title = "Urgent low: " + glucose;
            l.detail = "Glucose " + glucose + " at " + Store.hm(p.getLong(Store.LAST_SEEN, now)) + ". Treat now.";
            l.since = l.warnAt = l.alarmAt = lowSince;
            l.snoozable = false;
            return l;
        }

        long badSince = p.getLong(Store.BAD_SINCE, 0);
        if (badSince > 0) {
            long signalMs = Store.minutes(c, Store.SIGNAL_MIN, Store.DEF_SIGNAL_MIN);
            Problem s = new Problem();
            s.code = "SIGNAL";
            boolean on = "On".equalsIgnoreCase(status);
            s.title = on ? "No glucose reading" : "Auto mode: " + status;
            s.detail = "Since " + Store.hm(badSince) + " (" + Store.ago(badSince, now).replace(" ago", "")
                    + "). CamAPS shows: " + texts;
            s.since = badSince;
            s.warnAt = badSince + signalMs / 2;
            s.alarmAt = badSince + signalMs;
            s.launch = true;
            return s;
        }
        return null;
    }

    /** Works out what should be happening right now and makes it so. Safe to call any time. */
    static synchronized void evaluate(Context c) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        Problem pr = problem(c, now);
        int prevLevel = p.getInt(Store.LEVEL, NONE);
        String prevCode = p.getString(Store.PROBLEM, "");
        String prevTitle = p.getString(Store.PROBLEM_TITLE, "");
        long snoozeUntil = p.getLong(Store.SNOOZE_UNTIL, 0);
        long ackUntil = p.getLong(Store.ACK_UNTIL, 0);
        boolean snoozed = pr != null && pr.snoozable && snoozeUntil > now;

        int level = NONE;
        if (pr != null && !snoozed) {
            if (now >= pr.alarmAt) level = ALARM;
            else if (now >= pr.warnAt) level = WARN;
            if (level == ALARM && ackUntil > now) level = ACKED;
        }

        SharedPreferences.Editor e = p.edit();
        if (pr == null) {
            e.putLong(Store.ACK_UNTIL, 0).putString(Store.PROBLEM, "").putString(Store.PROBLEM_TITLE, "");
        } else {
            e.putString(Store.PROBLEM, pr.code).putString(Store.PROBLEM_TITLE, pr.title);
        }
        e.putInt(Store.LEVEL, level).apply();

        if (level == ALARM) {
            Alerts.alarm(c, pr, prevLevel != ALARM || !pr.code.equals(prevCode));
        } else {
            Alerts.stopSound(c);
            if (level == WARN || level == ACKED) {
                Alerts.problem(c, pr, level, prevLevel != level || !pr.code.equals(prevCode));
            } else {
                Alerts.cancelProblem(c);
                if (prevLevel != NONE && pr == null && !"TEST".equals(prevCode)) {
                    Alerts.resolved(c, prevTitle);
                }
            }
        }
        if (prevLevel != level || (pr != null && !pr.code.equals(prevCode))) {
            Store.log(c, (pr == null ? "All clear" : pr.code + " " + pr.title)
                    + " -> " + new String[]{"none", "warning", "silenced", "ALARM"}[level]
                    + (snoozed ? " (snoozed until " + Store.hm(snoozeUntil) + ")" : ""));
        }

        // Try to fix it: relaunching CamAPS restarts it if it was killed and nudges its Bluetooth.
        if (pr != null && pr.launch && now >= pr.warnAt && p.getBoolean(Store.AUTO_LAUNCH, true)
                && !(snoozed && "SIGNAL".equals(pr.code))
                && now - p.getLong(Store.LAST_LAUNCH, 0) > 10 * 60_000L) {
            p.edit().putLong(Store.LAST_LAUNCH, now).apply();
            launchCamaps(c, "auto");
        }

        Alerts.status(c, pr, level, snoozed);
        schedule(c, pr, level, now);
    }

    /** Next watchdog wake-up: the next deadline, the end of a snooze or silence, or 10 minutes. */
    private static void schedule(Context c, Problem pr, int level, long now) {
        SharedPreferences p = Store.p(c);
        long next = now + 10 * 60_000L;
        if (level == ALARM) next = now + 2 * 60_000L; // re-buzz the watch and car
        if (pr != null) {
            if (pr.warnAt > now) next = Math.min(next, pr.warnAt);
            if (pr.alarmAt > now) next = Math.min(next, pr.alarmAt);
        }
        long ack = p.getLong(Store.ACK_UNTIL, 0);
        if (ack > now) next = Math.min(next, ack);
        long snooze = p.getLong(Store.SNOOZE_UNTIL, 0);
        if (snooze > now) next = Math.min(next, snooze);
        long lastSeen = p.getLong(Store.LAST_SEEN, 0);
        long staleAt = lastSeen + Store.minutes(c, Store.STALE_MIN, Store.DEF_STALE_MIN);
        if (lastSeen > 0 && staleAt > now) next = Math.min(next, staleAt + 1000);
        long test = p.getLong(Store.TEST_UNTIL, 0);
        if (test > now) next = Math.min(next, test);
        next = Math.max(next, now + 30_000L);

        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1,
                new Intent(c, ActionReceiver.class).setAction(ActionReceiver.CHECK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    /** Called by the watchdog timer: confirm CamAPS is still there, then re-evaluate. */
    static void check(Context c) {
        if (!CamapsListener.poll(c)) {
            Store.log(c, "Watchdog: notification listener not bound");
            evaluate(c);
        }
        // poll() re-reads CamAPS, which calls evaluate() itself.
    }

    static void snooze(Context c, double hours) {
        long until = System.currentTimeMillis() + (long) (hours * 3_600_000L);
        Store.p(c).edit().putLong(Store.SNOOZE_UNTIL, until).apply();
        Store.log(c, "Snoozed until " + Store.hm(until));
        evaluate(c);
    }

    static void unsnooze(Context c) {
        Store.p(c).edit().putLong(Store.SNOOZE_UNTIL, 0).apply();
        Store.log(c, "Snooze cancelled");
        evaluate(c);
    }

    /** Silences the current alarm; it comes back after the re-alarm time if the problem is still there. */
    static void silence(Context c) {
        SharedPreferences p = Store.p(c);
        long until = System.currentTimeMillis() + Store.minutes(c, Store.REALARM_MIN, Store.DEF_REALARM_MIN);
        p.edit().putLong(Store.ACK_UNTIL, until).putLong(Store.TEST_UNTIL, 0).apply();
        Store.log(c, "Alarm silenced until " + Store.hm(until));
        evaluate(c);
    }

    static void test(Context c) {
        Store.p(c).edit().putLong(Store.TEST_UNTIL, System.currentTimeMillis() + 120_000L)
                .putLong(Store.ACK_UNTIL, 0).apply();
        Store.log(c, "Test alarm");
        evaluate(c);
    }

    static boolean launchCamaps(Context c, String why) {
        PackageManager pm = c.getPackageManager();
        String last = Store.p(c).getString(Store.PKG, null);
        String[] pkgs = {last, "com.camdiab.fx_alert.mmoll", "com.camdiab.fx_alert.mgdl",
                "com.camdiab.fx_alert.hx.mmoll", "com.camdiab.fx_alert.hx.mgdl"};
        for (String pkg : pkgs) {
            if (pkg == null) continue;
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) continue;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(i);
                Store.log(c, "Opened CamAPS (" + why + ")"
                        + (Settings.canDrawOverlays(c) ? "" : "; may be blocked: 'Display over other apps' is off"));
                return true;
            } catch (Exception ex) {
                Store.log(c, "Could not open CamAPS: " + ex.getClass().getSimpleName());
                return false;
            }
        }
        Store.log(c, "CamAPS FX is not installed?");
        return false;
    }
}
