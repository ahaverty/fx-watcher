package studio.alans.fxwatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The calm view of how things are: one of five states, one word, a colour and a line of text that
 * says what to do, never the glucose number. Pure Java so the rules are unit tested.
 *
 *   steady    nothing needs you
 *   handled   above range, but Boost is on or a bolus/correction is working: no alerts
 *   check     one of three nudges: "Did you bolus?", "Check the site?", "CamAPS isn't looping"
 *   low_soon  a low is coming (or is here): take fast carbs
 *   treating  Treating mode is on
 */
final class CalmState {

    private CalmState() { }

    static final String STEADY = "steady", HANDLED = "handled", CHECK = "check", LOW_SOON = "low_soon", TREATING = "treating";

    // colours: steady teal, handled amber, check coral, low_soon and treating purple, no data grey
    static final String TEAL = "#1FA89A", AMBER = "#F2A33A", CORAL = "#F26B5B", PURPLE = "#8A63D2", GREY = "#9AA0A6";

    static final long MIN = 60_000L;
    static final double RANGE_HIGH = 10.0;        // above this is "above range"
    static final double LOW = 3.9;
    static final double CHECK_SITE_ABOVE = 12.0;
    static final long CHECK_SITE_AFTER = 90 * MIN;
    static final long LOOP_OFF_AFTER = 15 * MIN;
    static final long BOLUS_RECENT = 120 * MIN;
    static final long STALE_AFTER = 15 * MIN;

    /** Kinds of check nudge (also the action ids on the notification). */
    static final String NUDGE_BOLUS = "bolus", NUDGE_SITE = "site", NUDGE_LOOP = "loop";

    static final class Input {
        long now;
        double glucose = -1;           // latest reading, mmol/L; < 0 when none
        long glucoseAt;
        List<double[]> readings = new ArrayList<>();   // {ms, mmol}, oldest first
        boolean boostOn;
        List<NudgeRules.Bolus> boluses = new ArrayList<>();
        boolean treating;
        long treatingUntil;
        boolean lowSoon;               // the early low warning is active
        long hypoAt;                   // the last hypo treatment logged in CamAPS (0 = none)
        double hypoGrams, hypoMmol;    // its grams, and the glucose when it was logged (<= 0 unknown)
        long autoOffSince;             // CamAPS auto mode not On since then; 0 when on
        long bolusYesUntil;            // "Yes done" tapped: no "Did you bolus?" until then
    }

    static final class Result {
        String state = STEADY;
        String word = "Steady";
        String text = "";              // empty when steady
        String color = TEAL;
        String nudge = "";             // NUDGE_* when state is check
        boolean stale;                 // no reading for 15 min
        boolean showYesDone, showOpenCamaps;
        long handledEta;               // when range is expected (0 = unknown), for the text

        /** What changes the notification: not the ETA, so the watch is not re-alerted for it. */
        String key() {
            return state + "|" + nudge;
        }
    }

    static String colorOf(String state) {
        switch (state) {
            case HANDLED: return AMBER;
            case CHECK: return CORAL;
            case LOW_SOON:
            case TREATING: return PURPLE;
            default: return TEAL;
        }
    }

    static String wordOf(String state) {
        switch (state) {
            case HANDLED: return "Handled";
            case CHECK: return "Check";
            case LOW_SOON: return "Low soon";
            case TREATING: return "Treating";
            default: return "Steady";
        }
    }

    // ---- trend ----

    /** Slope in mmol/L per minute over the last 20 min, or NaN when there is not enough. */
    static double slope(List<double[]> readings, long now) {
        LowWarning.Projection p = LowWarning.project(readings, now, new LowWarning.Params(20, 20, 4.0, 0, 0));
        return p == null ? Double.NaN : p.slope;
    }

    /** Flat is within 0.3 mmol/L over 15 min, the same as the nudges use. */
    /** A logged hypo treatment counts as handled for this long... */
    static final long TREATED_WINDOW = 20 * MIN, TREATED_MUST_RISE = 15 * MIN;

    /**
     * A hypo treatment was logged in CamAPS within the last 20 min and glucose is not already low (under 3.9
     * always says "take 10 g"). From 15 min in, it stops counting unless glucose is above where it was when
     * logged: a treatment that has not worked puts the warnings back. Display only: it never silences an alert.
     */
    static boolean treatedRecently(Input in, boolean stale) {
        if (stale || in.hypoAt <= 0 || in.now < in.hypoAt || in.now - in.hypoAt > TREATED_WINDOW) return false;
        if (in.glucose < LOW) return false;
        if (in.now - in.hypoAt >= TREATED_MUST_RISE && in.hypoMmol > 0 && in.glucose <= in.hypoMmol + 0.05) return false;
        return true;
    }

    static boolean flatOrFalling(double slope) {
        return !Double.isNaN(slope) && slope <= NudgeRules.TREND_DELTA / 15.0 + 1e-9;
    }

    // ---- check nudges ----

    /** A bolus or correction delivered within the last 2 hours. Returns the newest, or null. */
    static NudgeRules.Bolus recentBolus(List<NudgeRules.Bolus> boluses, long now) {
        NudgeRules.Bolus best = null;
        for (NudgeRules.Bolus b : boluses) {
            if (b.at <= now && now - b.at <= BOLUS_RECENT && (best == null || b.at > best.at)) best = b;
        }
        return best;
    }

    /**
     * A rise that looks like a meal with no bolus: up at least 2.0 over the last 45 min to 8.0 or more,
     * still rising, nothing delivered in the last 2 hours, and not a recovery from a low. There is no "I ate" signal, so this is
     * the shape of a meal, not proof of one.
     */
    static boolean riseWithoutBolus(Input in) {
        if (in.glucose < 8.0 || in.now < in.bolusYesUntil) return false;
        if (recentBolus(in.boluses, in.now) != null) return false;
        // A rise out of a low is the treatment working, not a missed meal bolus (24 Nov 2026: "Did you bolus?" after
        // treating a 3.9): a hypo treatment logged in the last 3 h, or a reading under 4.5 in the last 90 min.
        if (in.hypoAt > 0 && in.now >= in.hypoAt && in.now - in.hypoAt <= 3 * 60 * MIN) return false;
        for (double[] r : in.readings) {
            if (r[0] <= in.now && in.now - r[0] <= 90 * MIN && r[1] < LowWarning.FLOOR_BELOW) return false;
        }
        double min = Double.MAX_VALUE;
        for (double[] r : in.readings) {
            if (in.now - r[0] <= 45 * MIN && r[0] <= in.now) min = Math.min(min, r[1]);
        }
        if (min == Double.MAX_VALUE || in.glucose - min < 2.0) return false;
        double s = slope(in.readings, in.now);
        return !Double.isNaN(s) && s >= NudgeRules.TREND_DELTA / 15.0 - 1e-9;
    }

    /**
     * Above 12 for 90 min or more with a bolus delivered 45 min to 3 h ago: the correction did not
     * bring it down, so the infusion site is the usual suspect.
     */
    static boolean highDespiteCorrection(Input in) {
        if (in.glucose <= CHECK_SITE_ABOVE) return false;
        long since = Long.MAX_VALUE;
        double prevT = in.now;
        for (int i = in.readings.size() - 1; i >= 0; i--) {
            double[] r = in.readings.get(i);
            if (r[1] <= CHECK_SITE_ABOVE) break;
            if (prevT - r[0] > 12 * MIN) break;            // a gap: cannot say it stayed above
            since = (long) r[0];
            prevT = r[0];
        }
        if (since == Long.MAX_VALUE || in.now - since < CHECK_SITE_AFTER) return false;
        for (NudgeRules.Bolus b : in.boluses) {
            long age = in.now - b.at;
            if (age >= 45 * MIN && age <= 180 * MIN) return true;
        }
        return false;
    }

    static boolean loopOff(Input in) {
        return in.autoOffSince > 0 && in.now - in.autoOffSince >= LOOP_OFF_AFTER;
    }

    // ---- the state ----

    static Result compute(Input in) {
        Result r = new Result();
        r.stale = in.glucose < 0 || in.now - in.glucoseAt > STALE_AFTER;
        if (in.treating) return set(r, TREATING, "Treating, alarm still buzzes");
        boolean low = !r.stale && in.glucose < LOW;
        if (low) return set(r, LOW_SOON, "Low: take 10 g of fast carbs");
        if (treatedRecently(in, r.stale)) {
            return set(r, HANDLED, String.format(java.util.Locale.US, "Treated %d g, waiting for it to work", Math.round(in.hypoGrams)));
        }
        if (in.lowSoon) return set(r, LOW_SOON, "Low coming: take 10 g");
        // Near a low and falling by the glance's own definition: never "Steady", whatever the buzz rule decided
        // (24 Nov 2026: 3.9 and "falling" on the watch glance, "Steady" on the face).
        if (!r.stale && in.glucose < LowWarning.FLOOR_BELOW) {
            double fall = slope(in.readings, in.now);
            if (!Double.isNaN(fall) && fall <= -NudgeRules.TREND_DELTA / 15.0 + 1e-9) return set(r, LOW_SOON, "Falling near low: take 10 g");
        }
        if (loopOff(in)) {
            r.nudge = NUDGE_LOOP;
            r.showOpenCamaps = true;
            return set(r, CHECK, "CamAPS isn't looping");
        }
        if (!r.stale && highDespiteCorrection(in)) {
            r.nudge = NUDGE_SITE;
            r.showOpenCamaps = true;
            return set(r, CHECK, "Check the site?");
        }
        if (!r.stale && riseWithoutBolus(in)) {
            r.nudge = NUDGE_BOLUS;
            r.showYesDone = true;
            r.showOpenCamaps = true;
            return set(r, CHECK, "Did you bolus?");
        }
        if (!r.stale && in.glucose > RANGE_HIGH) {
            NudgeRules.Bolus b = recentBolus(in.boluses, in.now);
            double s = slope(in.readings, in.now);
            boolean bolusWorking = b != null && flatOrFalling(s);
            if (in.boostOn || bolusWorking) {
                List<String> working = new ArrayList<>();
                if (in.boostOn) working.add("Boost");
                if (b != null) working.add(Double.isNaN(b.carbs) || b.carbs <= 0 ? "correction" : "bolus");
                r.handledEta = eta(in.glucose, s, in.now);
                String text = "Handled: " + join(working) + " working";
                if (r.handledEta > 0) text += ", back in range by about " + Store.hm(r.handledEta);
                return set(r, HANDLED, text);
            }
        }
        r.text = "";
        if (r.stale) r.word = "No data";
        return r;
    }

    /** When the fitted line crosses the top of the range, rounded to 15 min; 0 if it is not falling or too far off. */
    static long eta(double glucose, double slope, long now) {
        if (Double.isNaN(slope) || slope >= -0.01) return 0;
        double minutes = (glucose - RANGE_HIGH) / -slope;
        if (minutes <= 0 || minutes > 180) return 0;
        long at = now + (long) (minutes * MIN);
        long q = 15 * MIN;
        return (at + q / 2) / q * q;
    }

    private static String join(List<String> items) {
        if (items.isEmpty()) return "treatment";
        if (items.size() == 1) return items.get(0);
        return items.get(0) + " and " + items.get(1);
    }

    private static Result set(Result r, String state, String text) {
        r.state = state;
        r.word = wordOf(state);
        r.color = colorOf(state);
        r.text = text;
        return r;
    }

    /** The JSON the watch and widget read. The glucose number is only for the deliberate glance. */
    static String json(Result r, double glucose, String trend, long glucoseAt, long now, long updated) {
        long ageMin = glucose < 0 ? -1 : Math.max(0, (now - glucoseAt) / MIN);
        return String.format(Locale.US,
                "{\"state\":\"%s\",\"word\":\"%s\",\"colour\":\"%s\",\"text\":\"%s\",\"glucose\":%s,\"trend\":\"%s\",\"age_min\":%d,\"updated\":%d}",
                r.state, r.stale ? "No data" : r.word, r.stale ? GREY : r.color, esc(r.text),
                glucose < 0 ? "null" : String.format(Locale.US, "%.1f", glucose), trend, ageMin, updated / 1000);
    }

    /** Slots in the history: 6 hours at 5 minutes. */
    static final int HIST_SLOTS = 72;

    /**
     * What the watch's chart reads (GET /history.json): "end" the newest reading's time in seconds, "h" the last
     * 6 hours in 5 minute slots oldest first as tenths of mmol/L (-1 where there was no reading), "slope" mmol/L
     * per minute times 1000 from the same 20 min fit as the trend word (0 when unknown), and "lowin" the minutes
     * until 3.9 if it keeps going at that rate (-1 when not falling, already low, or more than 10 hours off).
     */
    static String historyJson(List<double[]> readings, long now) {
        if (readings == null || readings.isEmpty()) return "{\"end\":0,\"h\":[],\"slope\":0,\"lowin\":-1}";
        double[] newest = readings.get(readings.size() - 1);
        long end = (long) newest[0];
        StringBuilder sb = new StringBuilder("{\"end\":").append(end / 1000).append(",\"h\":[");
        for (int i = 0; i < HIST_SLOTS; i++) {
            long slot = end - (long) (HIST_SLOTS - 1 - i) * 5 * MIN;
            int v = -1;
            long best = 150_000L;                                   // within 2.5 minutes of the slot
            for (double[] r : readings) {
                long d = Math.abs((long) r[0] - slot);
                if (d <= best) {
                    best = d;
                    v = (int) Math.round(r[1] * 10);
                }
            }
            if (i > 0) sb.append(',');
            sb.append(v);
        }
        double s = slope(readings, now);
        long slopeMilli = Double.isNaN(s) ? 0 : Math.round(s * 1000);
        int lowIn = -1;
        if (!Double.isNaN(s) && s <= -0.005 && newest[1] > LOW) {
            long m = Math.round((newest[1] - LOW) / -s);
            if (m <= 600) lowIn = (int) m;
        }
        return sb.append("],\"slope\":").append(slopeMilli).append(",\"lowin\":").append(lowIn).append('}').toString();
    }

    /** The same fields as the json, as a map for the Garmin push. */
    static java.util.Map<String, Object> message(Result r, double glucose, String trend, long glucoseAt, long now) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("state", r.state);
        m.put("word", r.stale ? "No data" : r.word);
        m.put("colour", r.stale ? GREY : r.color);
        m.put("text", r.text);
        m.put("glucose", glucose < 0 ? -1.0 : Math.round(glucose * 10) / 10.0);
        m.put("trend", trend);
        m.put("age_min", glucose < 0 ? -1 : (int) Math.max(0, (now - glucoseAt) / MIN));
        m.put("updated", now / 1000);
        return m;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** "rising", "flat" or "falling" for the glance, from the slope. */
    static String trend(double slope) {
        if (Double.isNaN(slope)) return "unknown";
        double d = NudgeRules.TREND_DELTA / 15.0;
        return slope >= d ? "rising" : slope <= -d ? "falling" : "flat";
    }
}
