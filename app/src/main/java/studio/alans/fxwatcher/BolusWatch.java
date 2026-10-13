package studio.alans.fxwatcher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.util.Log;

import org.json.JSONObject;

/**
 * Missed bolus check (MissedBolus decides): the calculator showed a bolus, nothing was delivered
 * within the set minutes -> an alarm-style notification, a Nightscout announcement and a Home
 * Assistant post. The deadline is an exact AlarmManager alarm, so it fires with the app in the
 * background, the screen off or the process gone; the state is in SharedPreferences.
 */
final class BolusWatch {

    private BolusWatch() { }

    private static final String TAG = "FXWatcher";
    static final String CH = "missed_bolus";
    static final int NOTIF = 50;
    private static final int REQ_CHECK = 51, REQ_OPEN = 52, REQ_DISMISS = 53;

    private static MissedBolus load(SharedPreferences p) {
        return MissedBolus.load(p.getString(Store.MISSED_BOLUS, ""));
    }

    private static void save(SharedPreferences p, MissedBolus m) {
        p.edit().putString(Store.MISSED_BOLUS, m.save()).apply();
    }

    static long timeout(Context c) {
        return Store.minutes(c, Store.MISSED_BOLUS_MIN, Store.DEF_MISSED_BOLUS_MIN);
    }

    /** Every bolus calculator read (CamapsReader). carbs NaN when the calculator shows none. */
    static synchronized void calculator(Context c, double carbs, double units, long now) {
        SharedPreferences p = Store.p(c);
        MissedBolus m = load(p);
        String before = m.save();
        MissedBolus.Event e = m.calculator(carbs, units, now, timeout(c));
        if (!m.save().equals(before)) save(p, m);
        if (e == MissedBolus.Event.STARTED) {
            Store.log(c, "Missed bolus check: pending " + MissedBolus.num(m.carbs) + " g / "
                    + MissedBolus.num(m.units) + " U, alert at " + Store.hm(m.deadline) + " if not delivered");
        }
        if (m.pending()) schedule(c, m.deadline);
    }

    /** Seconds after the countdown starts before a missing delivery is flagged (0 = off). */
    static int fastSec(Context c) {
        return Store.p(c).getInt(Store.DELIVERY_FAST_SEC, Store.DEF_DELIVERY_FAST_SEC);
    }

    /** True while a calculator bolus is waiting for its delivery. */
    static boolean pending(Context c) {
        return load(Store.p(c)).pending();
    }

    /** "Delivery will start in n" read on the CamAPS screen: Deliver was tapped (CamapsReader). */
    static synchronized void countdown(Context c, int secsLeft, long now) {
        SharedPreferences p = Store.p(c);
        MissedBolus m = load(p);
        // One log line per tick, not per screen event (the same number is read many times a second).
        if (secsLeft != p.getInt("cd_last_secs", -1) || now - p.getLong("cd_last_at", 0) > 15_000L) {
            Store.logCam(c, secsLeft < 0 ? "Delivery countdown on screen" : "Delivery countdown " + secsLeft + " s");
        }
        p.edit().putInt("cd_last_secs", secsLeft).putLong("cd_last_at", now).apply();
        MissedBolus.Event e = m.countdown(now, fastSec(c) * 1000L);
        save(p, m);
        if (e == MissedBolus.Event.STARTED) {
            p.edit().putLong("cd_first_at", now).apply();
            Store.logCam(c, "Deliver tapped (countdown seen): " + MissedBolus.num(m.units) + " U, alert at "
                    + Store.hm(m.deadline) + " if not delivered");
            schedule(c, m.deadline);
        }
    }

    /** A delivered bolus (CamapsListener), any amount. */
    static synchronized void delivered(Context c, double units, long now) {
        SharedPreferences p = Store.p(c);
        MissedBolus m = load(p);
        double carbs = m.carbs, calcUnits = m.units;
        long alerted = m.alertedAt;
        boolean tapped = m.counting;
        MissedBolus.Event e = m.delivered(units, now);
        if (tapped && e == MissedBolus.Event.CLEARED) {
            long first = p.getLong("cd_first_at", 0);
            if (first > 0) Store.logCam(c, "Delivery confirmed " + (now - first) / 1000 + " s after the countdown started ("
                    + MissedBolus.num(units) + " U)");
        }
        if (e == MissedBolus.Event.CLEARED) {
            Store.log(c, "Missed bolus check: cleared by delivery of " + MissedBolus.num(units) + " U (calculator "
                    + MissedBolus.num(carbs) + " g / " + MissedBolus.num(calcUnits) + " U)");
            cancel(c);
        } else if (alerted > 0 && now - alerted < 3 * 3600_000L) {
            // Delivered after all, after the alert: take the alert down everywhere.
            m.alertedAt = 0;
            Store.log(c, "Missed bolus check: " + MissedBolus.num(units) + " U delivered after the alert, alert closed");
            closeNotification(c);
            forward(c, "cleared", carbs, calcUnits, "Delivered " + MissedBolus.num(units) + " U after all");
        }
        save(p, m);
    }

    /** The alarm went off. */
    static synchronized void check(Context c) {
        SharedPreferences p = Store.p(c);
        MissedBolus m = load(p);
        long now = System.currentTimeMillis();
        boolean tapped = m.counting;
        MissedBolus.Event e = m.tick(now);
        save(p, m);
        if (e == MissedBolus.Event.ALERT) {
            String msg = tapped ? MissedBolus.countdownMessage(m.carbs, m.units, Store.hm(m.shownAt))
                    : MissedBolus.message(m.carbs, m.units, Store.hm(m.shownAt));
            Store.log(c, "Missed bolus check: ALERT. " + msg);
            alert(c, msg, now);
            announce(c, msg, now);
            forward(c, "alert", m.carbs, m.units, msg);
        } else if (m.pending()) {
            schedule(c, m.deadline);      // woke early (deadline moved on); wait for the new one
        }
    }

    /** "I didn't bolus (dismiss)". */
    static synchronized void dismiss(Context c) {
        SharedPreferences p = Store.p(c);
        MissedBolus m = load(p);
        double carbs = m.carbs, units = m.units;
        if (m.dismiss() == MissedBolus.Event.DISMISSED) {
            Store.log(c, "Missed bolus check: dismissed (didn't bolus)");
            forward(c, "dismissed", carbs, units, "Dismissed on the phone");
        }
        save(p, m);
        cancel(c);
        closeNotification(c);
    }

    /** "Open CamAPS": the alert has done its job; the pending bolus stays answered (no repeat). */
    static void openCamaps(Context c) {
        Store.log(c, "Missed bolus check: Open CamAPS tapped");
        closeNotification(c);
        Monitor.launchCamaps(c, "missed bolus alert");
    }

    /** After a reboot or an update: alarms are lost, set it again (or fire it, if overdue). */
    static void resume(Context c) {
        MissedBolus m = load(Store.p(c));
        if (!m.pending()) return;
        if (m.deadline <= System.currentTimeMillis()) check(c);
        else schedule(c, m.deadline);
    }

    // ── Alarm ──

    private static PendingIntent checkIntent(Context c) {
        return PendingIntent.getBroadcast(c, REQ_CHECK,
                new Intent(c, ActionReceiver.class).setAction(ActionReceiver.BOLUS_CHECK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void schedule(Context c, long at) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, checkIntent(c));
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, checkIntent(c));
        }
    }

    private static void cancel(Context c) {
        c.getSystemService(AlarmManager.class).cancel(checkIntent(c));
    }

    // ── Where the alert goes ──

    private static void channel(Context c) {
        NotificationChannel ch = new NotificationChannel(CH, "Missed bolus", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("The bolus calculator showed a bolus but no delivery followed");
        ch.setSound(Alerts.defaultSound(), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 400, 800, 400, 1500});
        ch.setBypassDnd(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private static PendingIntent action(Context c, String act, int req) {
        return PendingIntent.getBroadcast(c, req, new Intent(c, ActionReceiver.class).setAction(act),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void alert(Context c, String msg, long now) {
        channel(c);
        PendingIntent open = action(c, ActionReceiver.BOLUS_OPEN, REQ_OPEN);
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFFC62828)
                .setContentTitle("Bolus not delivered?")
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .setWhen(now)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat),
                        "Open CamAPS", open).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat),
                        "I didn't bolus (dismiss)", action(c, ActionReceiver.BOLUS_DISMISS, REQ_DISMISS)).build())
                .build();
        n.flags |= Notification.FLAG_INSISTENT;   // sound repeats until it is answered
        c.getSystemService(NotificationManager.class).notify(NOTIF, n);
    }

    private static void closeNotification(Context c) {
        c.getSystemService(NotificationManager.class).cancel(NOTIF);
    }

    /** Nightscout announcement (shows on its web page and in followers' apps). */
    private static void announce(Context c, String msg, long now) {
        if (!Nightscout.configured(c)) return;
        try {
            JSONObject t = Nightscout.treatment("Announcement", now, "fx-watcher-missed-bolus");
            t.put("notes", msg);
            t.put("isAnnouncement", true);
            Nightscout.postTreatment(c, t);
        } catch (Exception e) {
            Log.w(TAG, "missed bolus announcement", e);
        }
    }

    private static void forward(Context c, String state, double carbs, double units, String msg) {
        CamapsListener.forwardMissedBolus(c, state, carbs, units, msg);
    }
}
