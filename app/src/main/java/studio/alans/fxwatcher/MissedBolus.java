package studio.alans.fxwatcher;

import java.util.Locale;

/**
 * Catches a bolus that was worked out in CamAPS's bolus calculator but never delivered. Pure Java
 * (no Android), so it is unit tested; BolusWatch persists it with save()/load() and runs the alarm.
 *
 * 13 Oct 2026: 46 g / 7.1 U was entered at 12:32 and never delivered (no "Delivered" notification,
 * no CamAPS alert); glucose reached 15.9 before it was noticed. So:
 *   1. The calculator showing carbs or units above 0 starts a pending bolus, or restarts its timer
 *      with the latest values while typing goes on (0 g, 38 g ... 46 g).
 *   2. Any delivery clears it, whatever the amount: the calculator can be retyped before the
 *      bolus (46 g, 23 g, 30 g, then 4.6 U for 30 g).
 *   3. Nothing delivered for the timeout after the last value -> one alert. A calculator back at
 *      0 g / 0 U is not a cancel: that is just what a missed bolus looks like.
 *   4. A calculator still on screen right after a delivery (the same burst) is that bolus, not a
 *      new one; so is one showing the units of a bolus delivered shortly before.
 */
final class MissedBolus {

    /** Calculator reads closer together than this are one burst of typing. */
    static final long BURST_GAP = 2 * 60_000L;
    /** A calculator showing the units of a delivery this recent is that delivery. */
    static final long SAME_BOLUS = 30 * 60_000L;

    /** Pump delivery time allowed per unit once the countdown has started (7.1 U must not false-alarm). */
    static final long PER_UNIT = 10_000L;

    enum Event { NONE, STARTED, CLEARED, ALERT, DISMISSED }

    // Pending bolus (pendingAt = 0: none)
    long pendingAt;          // when the calculator last showed it (the latest value)
    long shownAt;            // the same, kept after an alert or delivery for messages
    double carbs, units;
    long deadline;
    boolean counting;        // CamAPS's "Delivery will start in" countdown was seen: Deliver was tapped
    // Calculator burst
    long burstStart, lastCalcAt;
    // Last delivery seen
    long deliveredAt;
    double deliveredUnits = -1;
    // The alert raised, kept so the notification and the "cleared later" note can name it
    long alertedAt;

    boolean pending() {
        return pendingAt > 0;
    }

    /** One calculator read (carbs NaN when it shows none). timeoutMs = 0 turns the check off. */
    Event calculator(double carbs, double units, long now, long timeoutMs) {
        double g = Double.isNaN(carbs) ? 0 : carbs;
        boolean sameBurst = lastCalcAt > 0 && now - lastCalcAt <= BURST_GAP;
        if (!sameBurst) burstStart = now;
        lastCalcAt = now;
        if (g <= 0 && units <= 0) return Event.NONE;         // 0 g / 0 U: neither start nor cancel
        if (timeoutMs <= 0) return Event.NONE;
        // The calculator still showing after its bolus went in: same burst, or the same units.
        if (deliveredAt > 0 && deliveredAt >= burstStart) return Event.NONE;
        if (deliveredAt > 0 && now - deliveredAt <= SAME_BOLUS
                && Math.abs(units - deliveredUnits) < 0.05) return Event.NONE;
        boolean fresh = !pending();
        if (fresh) { alertedAt = 0; counting = false; }                            // a new bolus: the old alert is history
        pendingAt = now;
        shownAt = now;
        this.carbs = g;
        this.units = units;
        deadline = now + timeoutMs;
        counting = false;                                    // retyped: the next countdown is a new tap
        return fresh ? Event.STARTED : Event.NONE;
    }

    /**
     * CamAPS's "Delivery will start in n" countdown is on screen, so Deliver was tapped. Brings the
     * deadline forward to baseMs + PER_UNIT per unit after the first sighting; later ticks keep it.
     * Returns STARTED the first time (for the log). Nothing pending (calculator never read): ignored.
     */
    Event countdown(long now, long baseMs) {
        if (!pending() || baseMs <= 0 || counting) return Event.NONE;
        counting = true;
        long fast = now + baseMs + Math.round(units * PER_UNIT);
        if (fast < deadline) deadline = fast;
        return Event.STARTED;
    }

    /** A delivery ("Delivered X U of insulin."), any amount. */
    Event delivered(double units, long now) {
        deliveredAt = now;
        deliveredUnits = units;
        if (!pending()) return Event.NONE;
        pendingAt = 0;
        counting = false;
        return Event.CLEARED;
    }

    /** The scheduled check. ALERT once per pending bolus. */
    Event tick(long now) {
        if (!pending() || now < deadline) return Event.NONE;
        alertedAt = now;
        pendingAt = 0;
        counting = false;
        return Event.ALERT;
    }

    /** "I didn't bolus" on the alert, or the check being switched off. */
    Event dismiss() {
        boolean was = pending() || alertedAt > 0;
        pendingAt = 0;
        alertedAt = 0;
        counting = false;
        return was ? Event.DISMISSED : Event.NONE;
    }

    /** "Bolus not delivered? Calculator showed 46 g / 7.1 U at 12:32, no delivery seen." */
    static String message(double carbs, double units, String hhmm) {
        return "Bolus not delivered? Calculator showed " + num(carbs) + " g / " + num(units) + " U at "
                + hhmm + ", no delivery seen.";
    }

    /** The alert when the countdown started and no delivery followed. */
    static String countdownMessage(double carbs, double units, String hhmm) {
        return "Deliver was tapped at " + hhmm + " (" + num(carbs) + " g / " + num(units)
                + " U) but no delivery was confirmed. Check CamAPS.";
    }

    static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }

    // ── Persistence (plain text, like BolusTracker) ──

    String save() {
        return String.format(Locale.US, "%d|%s|%s|%d|%d|%d|%d|%s|%d|%d|%d", pendingAt, carbs, units, deadline,
                burstStart, lastCalcAt, deliveredAt, deliveredUnits, alertedAt, shownAt, counting ? 1 : 0);
    }

    static MissedBolus load(String s) {
        MissedBolus m = new MissedBolus();
        if (s == null || s.isEmpty()) return m;
        try {
            String[] a = s.split("\\|", -1);
            m.pendingAt = Long.parseLong(a[0]);
            m.carbs = Double.parseDouble(a[1]);
            m.units = Double.parseDouble(a[2]);
            m.deadline = Long.parseLong(a[3]);
            m.burstStart = Long.parseLong(a[4]);
            m.lastCalcAt = Long.parseLong(a[5]);
            m.deliveredAt = Long.parseLong(a[6]);
            m.deliveredUnits = Double.parseDouble(a[7]);
            m.alertedAt = Long.parseLong(a[8]);
            m.shownAt = Long.parseLong(a[9]);
            m.counting = a.length > 10 && a[10].equals("1");
        } catch (Exception e) {
            return new MissedBolus();
        }
        return m;
    }
}
