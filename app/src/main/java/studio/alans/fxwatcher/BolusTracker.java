package studio.alans.fxwatcher;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a CamAPS "Delivered X U of insulin." is a NEW bolus, extra information for the
 * bolus already sent (UPDATE), or a repeat of it (NONE). Pure Java (no Android), so it is unit
 * tested; CamapsListener persists it with save()/load().
 *
 * CamAPS says "Delivered" as an in-app popup while it is open (read by CamapsReader) and as a
 * notification otherwise. Neither carries a delivery time, and the popup stays up until it is
 * dismissed, so the same popup is read again whenever CamAPS is reopened (12 Oct 2026: a 4.4 U
 * bolus at 20:56 was sent again at 20:59, 181 s later, past the old 180 s window). So:
 *   1. A popup counts only when it appears: if the previous CamAPS screen read already showed
 *      the same popup, it is stale and ignored.
 *   2. The same amount as the last bolus is that bolus, however much later, unless there is
 *      evidence of a new delivery since: CamAPS's "Delivery will start in" countdown (every
 *      in-app bolus shows it) was on screen after it, the carbs differ, or it is a second,
 *      separate CamAPS notification. No timers: CamAPS can be reopened hours later. (This covers
 *      a reopen that reads another CamAPS screen before the popup, so the popup seems to appear.)
 *   3. A repeat that brings carbs the first one lacked updates that bolus (same created_at, which
 *      Nightscout upserts on); a repeat without carbs never removes them.
 * A different amount is always a new bolus.
 */
final class BolusTracker {

    static final String SCREEN = "screen", NOTIFICATION = "notification";
    private static final Pattern DELIVERED =
            Pattern.compile("Delivered\\s+(\\d+(?:[.,]\\d+)?)\\s*U", Pattern.CASE_INSENSITIVE);

    enum Kind { NONE, NEW, UPDATE }

    /** What to do. For NEW and UPDATE, at = the bolus's time (created_at), carbs may be NaN. */
    static final class Result {
        final Kind kind;
        final double units;
        final long at;
        final double carbs;
        final String text;

        Result(Kind kind, double units, long at, double carbs, String text) {
            this.kind = kind;
            this.units = units;
            this.at = at;
            this.carbs = carbs;
            this.text = text;
        }

        static final Result NOTHING = new Result(Kind.NONE, 0, 0, Double.NaN, null);
    }

    // State
    double lastUnits = -1;
    long lastAt;
    double lastCarbs = Double.NaN;
    String lastVia = "";
    long lastNotificationAt = -1;   // post time of the last bolus notification, -1 = none
    String popup = "";              // "Delivered ..." popup on the previous screen read, "" if none
    long deliveringAt;              // last time CamAPS's delivery countdown was on screen

    /** As stored: one line, no field separator. */
    private static String clean(String t) {
        return t.replace('|', '/').replace('\n', ' ').trim();
    }

    static double units(String text) {
        Matcher m = DELIVERED.matcher(text == null ? "" : text);
        return m.find() ? Double.parseDouble(m.group(1).replace(',', '.')) : -1;
    }

    /** One CamAPS screen read (all visible texts). carbs = bolus calculator carbs, NaN if none. */
    Result screen(List<String> texts, long now, double carbs) {
        String found = "";
        for (String t : texts) {
            if (t.startsWith("Delivery will start in")) deliveringAt = now;
            if (found.isEmpty() && units(t) >= 0 && t.startsWith("Delivered")) found = clean(t);
        }
        String before = popup;
        popup = found;
        if (found.isEmpty() || found.equals(before)) return Result.NOTHING;   // none, or stale
        return decide(units(found), now, carbs, SCREEN, found);
    }

    /** CamAPS's "Delivered" notification, at its post time. */
    Result notification(String text, long postTime, double carbs) {
        double u = units(text);
        if (u < 0) return Result.NOTHING;
        if (postTime == lastNotificationAt) return Result.NOTHING;          // the same post again
        Result r = decide(u, postTime, carbs, NOTIFICATION, text);
        lastNotificationAt = postTime;
        return r;
    }

    private Result decide(double units, long at, double carbs, String via, String text) {
        // No time window: how long ago the last bolus was says nothing (a popup can sit there for
        // hours). Only events count: a new delivery countdown, other carbs, another notification.
        boolean same = lastUnits >= 0 && Math.abs(units - lastUnits) < 0.001;
        boolean newDelivery = deliveringAt > lastAt;
        boolean otherCarbs = !Double.isNaN(carbs) && !Double.isNaN(lastCarbs) && carbs != lastCarbs;
        boolean secondNotification = NOTIFICATION.equals(via) && NOTIFICATION.equals(lastVia);
        if (!same || newDelivery || otherCarbs || secondNotification) {
            lastUnits = units;
            lastAt = at;
            lastCarbs = carbs;
            lastVia = via;
            return new Result(Kind.NEW, units, at, carbs, text);
        }
        if (!Double.isNaN(carbs) && Double.isNaN(lastCarbs)) {
            lastCarbs = carbs;
            return new Result(Kind.UPDATE, lastUnits, lastAt, carbs, text);
        }
        return Result.NOTHING;
    }

    // ── Persistence (plain text: org.json is not available in local unit tests) ──

    String save() {
        return String.format(Locale.US, "%s|%d|%s|%s|%d|%d|%s", lastUnits, lastAt,
                Double.isNaN(lastCarbs) ? "" : String.valueOf(lastCarbs), lastVia,
                lastNotificationAt, deliveringAt, popup);
    }

    static BolusTracker load(String s) {
        BolusTracker b = new BolusTracker();
        if (s == null || s.isEmpty()) return b;
        try {
            String[] a = s.split("\\|", -1);
            b.lastUnits = Double.parseDouble(a[0]);
            b.lastAt = Long.parseLong(a[1]);
            b.lastCarbs = a[2].isEmpty() ? Double.NaN : Double.parseDouble(a[2]);
            b.lastVia = a[3];
            b.lastNotificationAt = Long.parseLong(a[4]);
            b.deliveringAt = Long.parseLong(a[5]);
            b.popup = a[6];
        } catch (Exception e) {
            // older or damaged state: start afresh
            return new BolusTracker();
        }
        return b;
    }
}
