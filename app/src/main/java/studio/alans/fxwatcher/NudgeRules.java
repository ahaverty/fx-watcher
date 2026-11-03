package studio.alans.fxwatcher;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * When to nudge (pure Java, unit tested; Nudges runs them on the phone). A nudge is only ever a
 * notification. Nothing here touches the pump or CamAPS, and no nudge suggests a dose: the units
 * shown are what was delivered or what is still on board.
 *
 * Active insulin: CamAPS only shows it when its calculator or (i) panel is open (seen on 9 of 22
 * evenings, Oct 2026), so a fresh reading is used when there is one and otherwise an estimate from
 * the boluses this phone saw. The curve is the exponential one Loop and oref use with a 5 h
 * duration and a 55 min peak; it averaged 0.5 U from CamAPS's own readings over 3 weeks.
 */
final class NudgeRules {

    private NudgeRules() { }

    static final long MIN = 60_000L, HOUR = 60 * MIN;
    static final long DUPLICATE_WINDOW = 10 * MIN;
    static final double SIMILAR_CARBS = 0.20;
    static final long BOLUS_KEEP = 8 * HOUR;
    static final long IOB_FRESH = 15 * MIN;
    static final double IOB_DURATION_MIN = 300, IOB_PEAK_MIN = 55;
    static final long MEAL_FROM = 60 * MIN, MEAL_TO = 90 * MIN, MEAL_KEEP = 2 * HOUR, SAME_MEAL = 15 * MIN;
    static final long LOW_LOOKBACK = 3 * HOUR, ALCOHOL_LOOKBACK = 6 * HOUR, GLUCOSE_KEEP = 3 * HOUR + 15 * MIN;
    static final double BOOST_OVER = 10, LOW_UNDER = 4;
    static final double BED_IOB_OVER = 2, BED_UNDER = 6, BED_FALLING_UNDER = 7;
    static final int BED_FROM_MINUTE = 22 * 60 + 30, BED_TO_MINUTE = 30;
    /** Change over about 15 min that counts as rising or falling (mmol/L). */
    static final double TREND_DELTA = 0.3;

    enum Trend { UNKNOWN, FALLING, FLAT, RISING }

    // ── Boluses ──────────────────────────────────────────────────────────────────

    static final class Bolus {
        final long at;
        final double units;
        double carbs;     // NaN when none was seen
        boolean alerted;  // a duplicate alert already went out for this one

        Bolus(long at, double units, double carbs, boolean alerted) {
            this.at = at;
            this.units = units;
            this.carbs = carbs;
            this.alerted = alerted;
        }
    }

    static final class BolusLog {
        final List<Bolus> items = new ArrayList<>();

        Bolus add(long at, double units, double carbs) {
            items.removeIf(b -> b.at < at - BOLUS_KEEP);
            Bolus b = new Bolus(at, units, carbs, false);
            items.add(b);
            return b;
        }

        /** The carbs for a bolus already logged (BolusTracker's UPDATE). Null if it isn't there. */
        Bolus setCarbs(long at, double carbs) {
            for (Bolus b : items) {
                if (b.at == at) {
                    b.carbs = carbs;
                    return b;
                }
            }
            return null;
        }

        long lastAt() {
            long last = 0;
            for (Bolus b : items) last = Math.max(last, b.at);
            return last;
        }

        String save() {
            StringBuilder sb = new StringBuilder();
            for (Bolus b : items) {
                if (sb.length() > 0) sb.append(';');
                sb.append(b.at).append(',').append(b.units).append(',')
                        .append(Double.isNaN(b.carbs) ? "" : String.valueOf(b.carbs)).append(',')
                        .append(b.alerted ? 1 : 0);
            }
            return sb.toString();
        }

        static BolusLog load(String s) {
            BolusLog l = new BolusLog();
            if (s == null || s.isEmpty()) return l;
            try {
                for (String e : s.split(";")) {
                    String[] a = e.split(",", -1);
                    l.items.add(new Bolus(Long.parseLong(a[0]), Double.parseDouble(a[1]),
                            a[2].isEmpty() ? Double.NaN : Double.parseDouble(a[2]), a[3].equals("1")));
                }
            } catch (Exception e) {
                return new BolusLog();
            }
            return l;
        }
    }

    static boolean similarCarbs(double a, double b) {
        return !Double.isNaN(a) && !Double.isNaN(b) && a > 0 && b > 0
                && Math.abs(a - b) <= SIMILAR_CARBS * Math.max(a, b) + 1e-9;
    }

    static boolean sameUnits(double a, double b) {
        return Math.abs(a - b) < 0.05;
    }

    /** The bolus this one may repeat: the closest earlier one within 10 min, with similar carbs or the same units. */
    static Bolus duplicateOf(BolusLog log, Bolus b) {
        Bolus best = null;
        for (Bolus o : log.items) {
            if (o == b || o.at >= b.at || b.at - o.at > DUPLICATE_WINDOW) continue;
            if (!similarCarbs(o.carbs, b.carbs) && !sameUnits(o.units, b.units)) continue;
            if (best == null || o.at > best.at) best = o;
        }
        return best;
    }

    static String duplicateMessage(Bolus first, Bolus second, String firstHm, String secondHm) {
        if (similarCarbs(first.carbs, second.carbs)) {
            return Math.abs(first.carbs - second.carbs) < 0.05
                    ? "Possible duplicate bolus: " + num(first.carbs) + " g at " + firstHm + " and " + secondHm
                    : "Possible duplicate bolus: " + num(first.carbs) + " g at " + firstHm + " and "
                    + num(second.carbs) + " g at " + secondHm;
        }
        return "Possible duplicate bolus: " + num(first.units) + " U at " + firstHm + " and " + secondHm;
    }

    // ── Active insulin ───────────────────────────────────────────────────────────

    /** Fraction of a bolus still active after this many minutes. */
    static double remaining(double minutes) {
        if (minutes <= 0) return 1;
        if (minutes >= IOB_DURATION_MIN) return 0;
        double d = IOB_DURATION_MIN, p = IOB_PEAK_MIN;
        double tau = p * (1 - p / d) / (1 - 2 * p / d);
        double a = 2 * tau / d;
        double s = 1 / (1 - a + (1 + a) * Math.exp(-d / tau));
        return 1 - s * (1 - a) * ((minutes * minutes / (tau * d * (1 - a)) - minutes / tau - 1)
                * Math.exp(-minutes / tau) + 1);
    }

    static final class Iob {
        final double units;
        final boolean estimated;

        Iob(double units, boolean estimated) {
            this.units = units;
            this.estimated = estimated;
        }
    }

    /** A CamAPS reading from the last 15 min (plus any bolus since), otherwise the estimate. readingAt 0: none. */
    static Iob iob(BolusLog log, double reading, long readingAt, long now) {
        boolean fresh = readingAt > 0 && reading >= 0 && now - readingAt <= IOB_FRESH && readingAt <= now;
        double sum = fresh ? reading : 0;
        boolean added = false;
        for (Bolus b : log.items) {
            if (b.at > now || (fresh && b.at <= readingAt)) continue;
            double left = b.units * remaining((now - b.at) / (double) MIN);
            if (left > 0) {
                sum += left;
                added = true;
            }
        }
        return new Iob(sum, !fresh || added);
    }

    // ── Glucose history (CamAPS's notification has no trend arrow) ───────────────

    static final class GlucoseLog {
        final List<double[]> items = new ArrayList<>();   // {time ms, mmol}

        void add(long at, double mmol) {
            items.removeIf(g -> g[0] < at - GLUCOSE_KEEP);
            if (!items.isEmpty()) {
                double[] last = items.get(items.size() - 1);
                // The notification is re-read between readings: the same value soon after is the same one.
                if (at < last[0] || (at - last[0] < 4 * MIN && Math.abs(last[1] - mmol) < 0.01)) return;
            }
            items.add(new double[]{at, mmol});
        }

        /** Latest reading against the one closest to 15 min before it (10 to 20 min back). */
        Trend trend(long now) {
            if (items.isEmpty()) return Trend.UNKNOWN;
            double[] last = items.get(items.size() - 1);
            if (now - last[0] > 12 * MIN) return Trend.UNKNOWN;
            double[] old = null;
            for (double[] g : items) {
                double back = last[0] - g[0];
                if (back < 10 * MIN || back > 20 * MIN) continue;
                if (old == null || Math.abs(back - 15 * MIN) < Math.abs(last[0] - old[0] - 15 * MIN)) old = g;
            }
            if (old == null) return Trend.UNKNOWN;
            double delta = last[1] - old[1];
            return delta >= TREND_DELTA - 1e-9 ? Trend.RISING : delta <= -TREND_DELTA + 1e-9 ? Trend.FALLING : Trend.FLAT;
        }

        boolean anyUnder(double mmol, long since) {
            for (double[] g : items) if (g[0] >= since && g[1] < mmol) return true;
            return false;
        }

        String save() {
            StringBuilder sb = new StringBuilder();
            for (double[] g : items) {
                if (sb.length() > 0) sb.append(';');
                sb.append((long) g[0]).append(',').append(String.format(Locale.US, "%.2f", g[1]));
            }
            return sb.toString();
        }

        static GlucoseLog load(String s) {
            GlucoseLog l = new GlucoseLog();
            if (s == null || s.isEmpty()) return l;
            try {
                for (String e : s.split(";")) {
                    String[] a = e.split(",");
                    l.items.add(new double[]{Long.parseLong(a[0]), Double.parseDouble(a[1])});
                }
            } catch (Exception e) {
                return new GlucoseLog();
            }
            return l;
        }
    }

    // ── Boost after a slow meal ──────────────────────────────────────────────────

    /** Slowly absorbed meal entries, each nudged at most once. */
    static final class SlowMeals {
        final List<long[]> items = new ArrayList<>();   // {time ms, done 0/1}

        /** From Add meal and from the chart: the same meal within 15 min counts once. */
        void add(long at, long now) {
            items.removeIf(m -> m[0] < now - MEAL_KEEP);
            for (long[] m : items) if (Math.abs(m[0] - at) <= SAME_MEAL) return;
            items.add(new long[]{at, 0});
        }

        /** A meal 60 to 90 min old that hasn't been dealt with, or null. */
        long[] due(long now) {
            for (long[] m : items) {
                if (m[1] == 0 && now >= m[0] + MEAL_FROM && now <= m[0] + MEAL_TO) return m;
            }
            return null;
        }

        String save() {
            StringBuilder sb = new StringBuilder();
            for (long[] m : items) {
                if (sb.length() > 0) sb.append(';');
                sb.append(m[0]).append(',').append(m[1]);
            }
            return sb.toString();
        }

        static SlowMeals load(String s) {
            SlowMeals l = new SlowMeals();
            if (s == null || s.isEmpty()) return l;
            try {
                for (String e : s.split(";")) {
                    String[] a = e.split(",");
                    l.items.add(new long[]{Long.parseLong(a[0]), Long.parseLong(a[1])});
                }
            } catch (Exception e) {
                return new SlowMeals();
            }
            return l;
        }
    }

    /** Everything but the alcohol note, which needs a Nightscout read and is checked last. */
    static boolean boostWanted(double mmol, Trend trend, boolean boostOn, boolean easeOn, boolean lowRecently) {
        return mmol > BOOST_OVER && trend == Trend.RISING && !boostOn && !easeOn && !lowRecently;
    }

    private static final Pattern ALCOHOL = Pattern.compile("(^|\\s)#alcohol\\b", Pattern.CASE_INSENSITIVE);

    static boolean alcoholTag(String notes) {
        return notes != null && ALCOHOL.matcher(notes).find();
    }

    static final String BOOST_MESSAGE = "Over 10 and rising after the slow meal. Boost for 2 h?";

    // ── Bedtime ──────────────────────────────────────────────────────────────────

    /** 22:30 up to, but not including, 00:30. */
    static boolean bedtimeWindow(long now, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(zone);
        int m = t.getHour() * 60 + t.getMinute();
        return m >= BED_FROM_MINUTE || m < BED_TO_MINUTE;
    }

    static boolean bedtimeWanted(double iob, double mmol, Trend trend, boolean easeOn) {
        if (easeOn || iob <= BED_IOB_OVER) return false;
        return mmol < BED_UNDER || (mmol < BED_FALLING_UNDER && trend == Trend.FALLING);
    }

    /** Which night a time belongs to, for "once a night": the date 12 h earlier. */
    static String nightKey(long now, ZoneId zone) {
        return Instant.ofEpochMilli(now).atZone(zone).minusHours(12).toLocalDate().toString();
    }

    static String bedtimeMessage(Iob iob, String glucose) {
        return (iob.estimated ? "About " : "") + String.format(Locale.US, "%.1f", iob.units)
                + " U still on board at " + glucose + ". Ease-off for 2 h before sleep?";
    }

    // ── Asked for by Home Assistant ──────────────────────────────────────────────

    static final String WALKING = "walking", LATE_HOME = "late_home";

    /** The text for a nudge Home Assistant decided on, null for anything else. */
    static String haMessage(String kind) {
        if (WALKING.equals(kind)) return "Walking with insulin on board. Ease-off?";
        if (LATE_HOME.equals(kind)) return "Back late. Overnight Ease-off for 6 h?";
        return null;
    }

    /** What goes to Nightscout: a note the reviews can find. */
    static String note(String message) {
        return message + " #nudge";
    }

    static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }
}
