package studio.alans.fxwatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;

/**
 * The calm phone view: works out the state (CalmState), keeps it for the widget and the watch
 * (state.json, see CalmServer), and shows one silent status notification.
 *
 * The notification has no glucose number. While the state is steady it is not shown at all. In
 * any other state it is low importance (no sound, no buzz) and is the only thing that puts a small
 * icon in the status bar. Its text only changes when the state or the kind of check changes, so a
 * watch does not announce it again every few minutes.
 */
final class Calm {

    private Calm() { }

    static final String CH = "calm";
    static final int ID = 5;
    private static final int REQ_OPEN = 501, REQ_YES = 502, REQ_END = 503;

    private static String lastKey = "";

    static boolean enabled(SharedPreferences p) {
        return p.getBoolean(Store.CALM_ON, true);
    }

    /** Recomputes everything. Safe to call often; never throws. */
    static synchronized void refresh(Context c) {
        try {
            run(c);
        } catch (Exception e) {
            android.util.Log.w("FXWatcher", "calm refresh", e);
        }
    }

    static CalmState.Input input(Context c, long now) {
        SharedPreferences p = Store.p(c);
        CalmState.Input in = new CalmState.Input();
        in.now = now;
        float mmol = p.getFloat(Store.MMOL, -1f);
        in.glucose = mmol > 0 ? mmol : -1;
        in.glucoseAt = p.getLong(Store.LAST_SEEN, 0);
        in.readings = NudgeRules.GlucoseLog.load(p.getString(Store.NUDGE_GLUCOSE, "")).items;
        in.boostOn = BoostTracker.load(p.getString(Store.BOOST_TRACKER, "")).on(BoostTracker.BOOST, now);
        in.boluses = NudgeRules.BolusLog.load(p.getString(Store.NUDGE_BOLUSES, "")).items;
        in.treating = Treating.active(c);
        in.treatingUntil = Treating.until(c);
        in.lowSoon = LowWarning.Tracker.load(p.getString(Store.LOWWARN_TRACKER, "")).active
                && now - LowWarning.Tracker.load(p.getString(Store.LOWWARN_TRACKER, "")).warnAt < 45 * LowWarning.MIN;
        String status = p.getString(Store.STATUS, "");
        in.autoOffSince = p.getBoolean(Store.PRESENT, false) && !status.isEmpty() && !"On".equalsIgnoreCase(status)
                ? p.getLong(Store.BAD_SINCE, 0) : 0;
        in.bolusYesUntil = p.getLong(Store.CALM_YES_UNTIL, 0);
        return in;
    }

    private static void run(Context c) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        CalmState.Input in = input(c, now);
        CalmState.Result r = CalmState.compute(in);
        double slope = CalmState.slope(in.readings, now);
        String json = CalmState.json(r, in.glucose, CalmState.trend(slope), in.glucoseAt, now, now);
        String key = r.key();
        boolean changed = !key.equals(p.getString(Store.CALM_KEY, ""));
        p.edit().putString(Store.CALM_JSON, json).putString(Store.CALM_KEY, key).apply();
        if (changed) Store.logCam(c, "Calm state: " + r.state + (r.nudge.isEmpty() ? "" : " (" + r.nudge + ")"));
        CalmWidget.update(c, r);
        GarminPush.push(c, CalmState.message(r, in.glucose, CalmState.trend(slope), in.glucoseAt, now));
        if (!enabled(p)) {
            cancel(c);
            return;
        }
        if (CalmState.STEADY.equals(r.state)) {
            if (!lastKey.isEmpty() || changed) cancel(c);
            lastKey = "";
            return;
        }
        if (key.equals(lastKey)) return;       // same state: leave the notification alone
        lastKey = key;
        show(c, r, in);
    }

    private static void channel(Context c) {
        NotificationChannel ch = new NotificationChannel(CH, "Calm status", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Silent. Only there when something is not steady. No glucose number.");
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    static PendingIntent open(Context c) {
        Intent i = camapsLaunch(c);
        if (i == null) i = new Intent(c, MainActivity.class);
        return PendingIntent.getActivity(c, REQ_OPEN, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The launch intent for whichever CamAPS FX is installed, or null. */
    static Intent camapsLaunch(Context c) {
        PackageManager pm = c.getPackageManager();
        String[] pkgs = {Store.p(c).getString(Store.PKG, null), "com.camdiab.fx_alert.mmoll", "com.camdiab.fx_alert.mgdl",
                "com.camdiab.fx_alert.hx.mmoll", "com.camdiab.fx_alert.hx.mgdl"};
        for (String pkg : pkgs) {
            if (pkg == null) continue;
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i;
        }
        return null;
    }

    private static void show(Context c, CalmState.Result r, CalmState.Input in) {
        channel(c);
        Notification.Builder b = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(Color.parseColor(r.color))
                .setContentTitle(r.word)
                .setContentText(r.text)
                .setStyle(new Notification.BigTextStyle().bigText(r.text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open(c));
        if (in.treating) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true).setWhen(in.treatingUntil);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "End treating",
                    PendingIntent.getBroadcast(c, REQ_END, new Intent(c, ActionReceiver.class).setAction(ActionReceiver.TREAT_END),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        if (r.showYesDone) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "Yes done",
                    PendingIntent.getBroadcast(c, REQ_YES, new Intent(c, ActionReceiver.class).setAction(ActionReceiver.CALM_YES),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        if (r.showOpenCamaps) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "Open CamAPS", open(c)).build());
        }
        c.getSystemService(NotificationManager.class).notify(ID, b.build());
    }

    static void cancel(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID);
    }

    /** "Yes done" on the bolus check: no "Did you bolus?" for the next 2 hours. */
    static void yesDone(Context c) {
        Store.p(c).edit().putLong(Store.CALM_YES_UNTIL, System.currentTimeMillis() + CalmState.BOLUS_RECENT).apply();
        Store.logCam(c, "Calm: \"Did you bolus?\" answered yes");
        lastKey = "";
        refresh(c);
    }

    /** What the watch and widget read, worked out now (so age_min is right when it is asked for). */
    static String json(Context c) {
        long now = System.currentTimeMillis();
        CalmState.Input in = input(c, now);
        CalmState.Result r = CalmState.compute(in);
        return CalmState.json(r, in.glucose, CalmState.trend(CalmState.slope(in.readings, now)), in.glucoseAt, now, now);
    }
}
