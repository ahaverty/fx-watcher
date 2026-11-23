package studio.alans.fxwatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;

import java.util.List;
import java.util.Locale;

/**
 * Early low warning on the phone: feeds every CamAPS reading to LowWarning, shows "Low coming" as
 * one strong buzz and a high-importance notification with no sound (the Garmin mirrors it as one
 * buzz on the wrist), and keeps a journal of each warning and whether glucose then went under 3.9.
 *
 * Quiet event: nothing changes, because the warning never plays a sound, only vibrates.
 * Treating mode: the warning is suppressed (you are already handling it).
 * Ease-off, Boost and suspension are deliberately ignored: the low can still come.
 */
final class LowWarnings {

    private LowWarnings() { }

    static final String CH = "low_soon";
    static final int ID = 40;
    private static final int REQ_OPEN = 401, REQ_DISMISS = 402, REQ_TREAT = 403;

    static String mode(SharedPreferences p) {
        return p.getString(Store.LOWWARN_MODE, LowWarning.FAST);
    }

    /** Called with every CamAPS reading (about every 5 min) after it is in the glucose log. */
    static synchronized void reading(Context c, NudgeRules.GlucoseLog g, double mmol, long now) {
        SharedPreferences p = Store.p(c);
        LowWarning.Journal j = LowWarning.Journal.load(p.getString(Store.LOWWARN_JOURNAL, ""));
        boolean changed = false;
        for (LowWarning.Entry e : j.resolve(g.items, now)) {
            Store.logCam(c, e.line(Store.hm(e.at)));
            changed = true;
        }
        LowWarning.Params prm = LowWarning.preset(mode(p));
        SharedPreferences.Editor ed = p.edit();
        if (prm != null) {
            LowWarning.Projection pr = LowWarning.project(g.items, now, prm);
            LowWarning.Tracker t = LowWarning.Tracker.load(p.getString(Store.LOWWARN_TRACKER, ""));
            boolean treating = p.getLong(Store.TREAT_UNTIL, 0) > now;
            LowWarning.Action a = t.update(now, mmol, pr, prm, treating, iob(p, now));
            ed.putString(Store.LOWWARN_TRACKER, t.save());
            if (a == LowWarning.Action.WARN && pr != null) {
                String rule = pr.projected < prm.projBelow ? "projection" : "fast fall";
                j.add(new LowWarning.Entry(now, mmol, pr.projected, pr.slope, rule));
                changed = true;
                Store.logCam(c, "Low warning sent (" + rule + String.format(Locale.US, ", glucose %.1f, falling %.2f/min, "
                        + "projected %.1f in %d min)", mmol, -pr.slope, pr.projected, prm.horizonMin));
                show(c, false);
            } else if (a == LowWarning.Action.REBUZZ && pr != null && j.last() != null) {
                j.last().rebuzz = 1;
                changed = true;
                Store.logCam(c, String.format(Locale.US, "Low warning again (projection got worse: %.1f)", pr.projected));
                show(c, true);
            }
        }
        if (changed) ed.putString(Store.LOWWARN_JOURNAL, j.save());
        ed.apply();
    }

    /** Insulin on board in units: CamAPS's own figure when fresh, plus boluses since; NaN when nothing is known. */
    static double iob(SharedPreferences p, long now) {
        NudgeRules.BolusLog log = NudgeRules.BolusLog.load(p.getString(Store.NUDGE_BOLUSES, ""));
        float reading = p.getFloat(Store.NUDGE_IOB, -1);
        long readingAt = p.getLong(Store.NUDGE_IOB_AT, 0);
        if (log.items.isEmpty() && (reading < 0 || now - readingAt > NudgeRules.IOB_FRESH)) return Double.NaN;
        return NudgeRules.iob(log, reading, readingAt, now).units;
    }

    /**
     * CamAPS says glucose is falling fast or an urgent low is near (alert text or status card).
     * Turned into the same "Low coming" warning, with the same debounce, in every mode but off.
     */
    static synchronized void camaps(Context c, String text, long now) {
        if (!LowWarning.camapsFalling(text)) return;
        SharedPreferences p = Store.p(c);
        if (LowWarning.preset(mode(p)) == null) return;
        float g = p.getFloat(Store.MMOL, -1f);
        LowWarning.Tracker t = LowWarning.Tracker.load(p.getString(Store.LOWWARN_TRACKER, ""));
        boolean treating = p.getLong(Store.TREAT_UNTIL, 0) > now;
        if (t.external(now, g, treating) != LowWarning.Action.WARN) return;
        LowWarning.Journal j = LowWarning.Journal.load(p.getString(Store.LOWWARN_JOURNAL, ""));
        j.add(new LowWarning.Entry(now, g, g, 0, "CamAPS alert"));
        p.edit().putString(Store.LOWWARN_TRACKER, t.save()).putString(Store.LOWWARN_JOURNAL, j.save()).apply();
        Store.logCam(c, "Low warning sent (CamAPS said: " + text.replace('\n', ' ').trim() + ")");
        show(c, false);
    }

    static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "Low coming", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Early warning that glucose is heading low. One strong buzz, no sound.");
        ch.setSound(null, null);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 900, 250, 900});
        ch.setShowBadge(false);
        ch.enableLights(false);
        nm.createNotificationChannel(ch);
    }

    /** The notification. No glucose number in it: it says what to do. */
    static void show(Context c, boolean again) {
        channel(c);
        PendingIntent open = PendingIntent.getBroadcast(c, REQ_OPEN,
                new Intent(c, ActionReceiver.class).setAction(ActionReceiver.OPEN_CAMAPS),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent treat = PendingIntent.getBroadcast(c, REQ_TREAT,
                new Intent(c, ActionReceiver.class).setAction(ActionReceiver.TREAT_START),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent dismiss = PendingIntent.getBroadcast(c, REQ_DISMISS,
                new Intent(c, ActionReceiver.class).setAction(ActionReceiver.LOWWARN_DISMISS),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = again ? LowWarning.TEXT_AGAIN : LowWarning.TEXT;
        Notification.Builder b = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(LowWarning.TITLE)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setTimeoutAfter(30 * LowWarning.MIN)
                .setOnlyAlertOnce(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat),
                        "Open CamAPS", open).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat),
                        "I'm treating", treat).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat),
                        "Dismiss", dismiss).build());
        c.getSystemService(NotificationManager.class).notify(ID, b.build());
    }

    static void dismiss(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID);
    }

    /** A test that looks and buzzes like the real one, and writes nothing to the journal. */
    static void test(Context c) {
        show(c, false);
    }

    /** Last few warnings with what came of them, for the settings screen. */
    static String recent(Context c) {
        LowWarning.Journal j = LowWarning.Journal.load(Store.p(c).getString(Store.LOWWARN_JOURNAL, ""));
        if (j.items.isEmpty()) return "No warnings yet.";
        StringBuilder sb = new StringBuilder(j.summary());
        List<LowWarning.Entry> items = j.items;
        for (int i = items.size() - 1, shown = 0; i >= 0 && shown < 8; i--, shown++) {
            LowWarning.Entry e = items.get(i);
            sb.append('\n').append(e.line(Store.hm(e.at)));
        }
        return sb.toString();
    }
}
