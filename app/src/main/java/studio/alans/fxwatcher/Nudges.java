package studio.alans.fxwatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.util.Log;

import org.json.JSONObject;

import java.time.ZoneId;

/**
 * Nudges: a notification at the moment Ease-off or Boost might help, or a mistake could be caught
 * (NudgeRules decides). They never touch CamAPS or the pump and never suggest a dose. Each has its
 * own switch, all off by default; each one that fires is logged to Nightscout as a #nudge note.
 *
 * Duplicate bolus, Boost after a slow meal and the bedtime check are decided here. Home Assistant
 * decides the walking and late home ones (it knows where the phone is) and sends them to HaReceiver,
 * so they look the same, and are logged the same way, as the rest.
 */
final class Nudges {

    private Nudges() { }

    private static final String TAG = "FXWatcher";
    static final String CH_NUDGE = "nudge", CH_NIGHT = "nudge_night", CH_QUIET = "nudge_quiet";
    private static final int NOTIF_DUPLICATE = 70, NOTIF_BOOST = 71, NOTIF_BEDTIME = 72, NOTIF_WALKING = 73,
            NOTIF_LATE = 74, NOTIF_TEST = 75;
    private static final int REQ_OPEN = 76;

    private static volatile boolean checkingAlcohol;

    private static NudgeRules.BolusLog boluses(SharedPreferences p) {
        return NudgeRules.BolusLog.load(p.getString(Store.NUDGE_BOLUSES, ""));
    }

    private static BoostTracker boost(SharedPreferences p) {
        return BoostTracker.load(p.getString(Store.BOOST_TRACKER, ""));
    }

    private static NudgeRules.Iob iob(SharedPreferences p, NudgeRules.BolusLog log, long now) {
        return NudgeRules.iob(log, p.getFloat(Store.NUDGE_IOB, -1), p.getLong(Store.NUDGE_IOB_AT, 0), now);
    }

    /** Active insulin read from CamAPS's screen. */
    static void iobSeen(Context c, double units, long at) {
        Store.p(c).edit().putFloat(Store.NUDGE_IOB, (float) units).putLong(Store.NUDGE_IOB_AT, at).apply();
    }

    /** A bolus BolusTracker counted (NEW) or added carbs to (UPDATE). Kept whether or not nudges are on. */
    static synchronized void bolus(Context c, BolusTracker.Result r) {
        SharedPreferences p = Store.p(c);
        NudgeRules.BolusLog log = boluses(p);
        NudgeRules.Bolus b = r.kind == BolusTracker.Kind.NEW ? log.add(r.at, r.units, r.carbs) : log.setCarbs(r.at, r.carbs);
        if (b != null && !b.alerted && p.getBoolean(Store.NUDGE_DUPLICATE, false) && Phone.readerOn(c)) {
            // Without the screen reader a CamAPS re-post can't be told from a real second bolus.
            NudgeRules.Bolus first = NudgeRules.duplicateOf(log, b);
            if (first != null) {
                b.alerted = true;
                send(c, NudgeRules.duplicateMessage(first, b, Store.hm(first.at), Store.hm(b.at)),
                        NOTIF_DUPLICATE, false, System.currentTimeMillis());
            }
        }
        p.edit().putString(Store.NUDGE_BOLUSES, log.save()).apply();
        forwardInsulin(c);
    }

    /** A slowly absorbed meal, from CamAPS's Add meal screen or its chart. */
    static synchronized void slowMeal(Context c, long at) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        NudgeRules.SlowMeals m = NudgeRules.SlowMeals.load(p.getString(Store.NUDGE_MEALS, ""));
        m.add(at, now);
        p.edit().putString(Store.NUDGE_MEALS, m.save()).apply();
    }

    /** Every CamAPS reading, about every 5 min. */
    static synchronized void reading(Context c, double mmol, String shown, long now) {
        SharedPreferences p = Store.p(c);
        NudgeRules.GlucoseLog g = NudgeRules.GlucoseLog.load(p.getString(Store.NUDGE_GLUCOSE, ""));
        g.add(now, mmol);
        p.edit().putString(Store.NUDGE_GLUCOSE, g.save()).apply();
        NudgeRules.Trend trend = g.trend(now);
        BoostTracker t = boost(p);
        boolean easeOn = t.on(BoostTracker.EASE, now);
        checkBedtime(c, p, mmol, shown, trend, easeOn, now);
        if (p.getBoolean(Store.NUDGE_BOOST, false)) {
            boolean low = g.anyUnder(NudgeRules.LOW_UNDER, now - NudgeRules.LOW_LOOKBACK);
            NudgeRules.SlowMeals meals = NudgeRules.SlowMeals.load(p.getString(Store.NUDGE_MEALS, ""));
            long[] due = meals.due(now);
            if (due != null && NudgeRules.boostWanted(mmol, trend, t.on(BoostTracker.BOOST, now), easeOn, low)) {
                checkAlcoholThenBoost(c, due[0], now);
            }
        }
    }

    private static void checkBedtime(Context c, SharedPreferences p, double mmol, String shown,
                                     NudgeRules.Trend trend, boolean easeOn, long now) {
        if (!p.getBoolean(Store.NUDGE_BEDTIME, false)) return;
        ZoneId zone = ZoneId.systemDefault();
        if (!NudgeRules.bedtimeWindow(now, zone)) return;
        String night = NudgeRules.nightKey(now, zone);
        if (night.equals(p.getString(Store.NUDGE_BED_NIGHT, ""))) return;
        NudgeRules.Iob i = iob(p, boluses(p), now);
        if (!NudgeRules.bedtimeWanted(i.units, mmol, trend, easeOn)) return;
        p.edit().putString(Store.NUDGE_BED_NIGHT, night).apply();
        send(c, NudgeRules.bedtimeMessage(i, shown), NOTIF_BEDTIME, true, now);
    }

    /**
     * Last check for the Boost nudge: an #alcohol note in the last 6 h. Nightscout has to answer: if
     * it can't, nothing is sent and the next reading in the window tries again, since a missed Boost
     * nudge is the safer way to fail.
     */
    private static void checkAlcoholThenBoost(Context c, long mealAt, long now) {
        if (checkingAlcohol) return;
        checkingAlcohol = true;
        Nightscout.alcoholSince(c, now - NudgeRules.ALCOHOL_LOOKBACK, alcohol -> {
            try {
                if (alcohol == null) {
                    Store.log(c, "Boost nudge: couldn't read Nightscout notes, not sending");
                    return;
                }
                finishMeal(c, mealAt, alcohol, now);
            } finally {
                checkingAlcohol = false;
            }
        });
    }

    private static synchronized void finishMeal(Context c, long mealAt, boolean alcohol, long now) {
        SharedPreferences p = Store.p(c);
        NudgeRules.SlowMeals meals = NudgeRules.SlowMeals.load(p.getString(Store.NUDGE_MEALS, ""));
        boolean found = false;
        for (long[] m : meals.items) {
            if (m[0] == mealAt && m[1] == 0) {
                m[1] = 1;
                found = true;
            }
        }
        if (!found) return;
        p.edit().putString(Store.NUDGE_MEALS, meals.save()).apply();
        if (alcohol) Store.log(c, "Boost nudge: alcohol note in the last 6 h, not sending");
        else send(c, NudgeRules.BOOST_MESSAGE, NOTIF_BOOST, false, now);
    }

    /** Home Assistant decided on a nudge. The phone knows Ease-off best, so it has the last word. */
    static synchronized void fromHa(Context c, String kind) {
        String msg = NudgeRules.haMessage(kind);
        if (msg == null) {
            Store.log(c, "Ignored a nudge from Home Assistant of unknown kind");
            return;
        }
        long now = System.currentTimeMillis();
        if (boost(Store.p(c)).on(BoostTracker.EASE, now)) {
            Store.log(c, "Nudge from Home Assistant (" + kind + "): Ease-off is already on, not sending");
            return;
        }
        boolean late = NudgeRules.LATE_HOME.equals(kind);
        send(c, msg, late ? NOTIF_LATE : NOTIF_WALKING, late, now);
    }

    /** Shows what a nudge looks like. Not logged to Nightscout. */
    static void test(Context c) {
        show(c, "This is what a nudge looks like. Nothing was sent to Nightscout.", NOTIF_TEST, false,
                System.currentTimeMillis());
    }

    // ── Where a nudge goes ───────────────────────────────────────────────────────

    /** duringNight: meant for night-time, so it still makes a sound in the night hours. */
    private static void send(Context c, String msg, int id, boolean duringNight, long now) {
        Store.logCam(c, "Nudge: " + msg);
        show(c, msg, id, duringNight, now);
        if (!Nightscout.configured(c)) return;
        try {
            JSONObject t = Nightscout.treatment("Note", now, "fx-watcher-nudge");
            t.put("notes", NudgeRules.note(msg));
            Nightscout.postTreatment(c, t);
        } catch (Exception e) {
            Log.w(TAG, "nudge note", e);
        }
    }

    private static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel n = new NotificationChannel(CH_NUDGE, "Nudges", NotificationManager.IMPORTANCE_HIGH);
        n.setDescription("A reminder that Ease-off or Boost might help, or a bolus to check");
        NotificationChannel b = new NotificationChannel(CH_NIGHT, "Night-time nudges", NotificationManager.IMPORTANCE_HIGH);
        b.setDescription("The bedtime and late home nudges, meant to reach you at night");
        b.setBypassDnd(true);
        NotificationChannel q = new NotificationChannel(CH_QUIET, "Nudges in the night hours", NotificationManager.IMPORTANCE_LOW);
        q.setDescription("Other nudges during the night hours: no sound or buzz");
        nm.createNotificationChannel(n);
        nm.createNotificationChannel(b);
        nm.createNotificationChannel(q);
    }

    private static void show(Context c, String msg, int id, boolean duringNight, long now) {
        channels(c);
        String ch = duringNight ? CH_NIGHT : Phone.night(c, now) ? CH_QUIET : CH_NUDGE;
        PendingIntent open = PendingIntent.getBroadcast(c, REQ_OPEN,
                new Intent(c, ActionReceiver.class).setAction(ActionReceiver.NUDGE_OPEN),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, ch)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Nudge")
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .setWhen(now)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat),
                        "Open CamAPS", open).build())
                .build();
        c.getSystemService(NotificationManager.class).notify(id, n);
    }

    /** What Home Assistant needs for its nudges: sent with each change and every 9 min. */
    static void forwardInsulin(Context c) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        BoostTracker t = boost(p);
        NudgeRules.BolusLog log = boluses(p);
        NudgeRules.Iob i = iob(p, log, now);
        CamapsListener.forwardInsulin(c, t.until(BoostTracker.EASE, now), t.until(BoostTracker.BOOST, now),
                log.lastAt(), i.units, i.estimated);
    }
}
