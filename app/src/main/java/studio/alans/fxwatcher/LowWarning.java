package studio.alans.fxwatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Early low warning: "Low coming: take 10 g", early enough to treat before glucose reaches 3.9.
 *
 * Fits a straight line through the last 15 to 20 min of CamAPS readings and projects it 20 to 30
 * min ahead. It warns when the projection is below the threshold (4.0 by default), or when glucose
 * is under 5.5 and falling 0.1 mmol/L/min or faster. It does not look at Ease-off, Boost or
 * suspension: those are exactly the cases where a low still comes.
 *
 * Pure Java on NudgeRules.GlucoseLog items ({time ms, mmol}), so the same code runs in the phone,
 * in the unit tests and in the backtest against Nightscout history (tools/backtest).
 */
final class LowWarning {

    private LowWarning() { }

    static final long MIN = 60_000L;
    /** The level the warning is meant to stop glucose reaching (CamAPS's low alert is 3.9 here). */
    static final double LOW = 3.9;
    /** After a warning, no new one for this long, and none until the rule has stayed quiet 15 min. */
    static final long COOLDOWN = 30 * MIN, REARM_QUIET = 15 * MIN;
    /** The one re-buzz: 10 min after the warning, and only if the projection got this much worse. */
    static final long REBUZZ_AFTER = 10 * MIN;
    static final double REBUZZ_WORSE = 0.3;
    /** A warning is judged "right" if glucose went under LOW within this long. */
    static final long OUTCOME_WINDOW = 45 * MIN;

    static final class Params {
        final int windowMin, horizonMin;
        final double projBelow;       // warn if the projection is below this (mmol/L)
        final double fastBelow;       // and/or glucose is below this ...
        final double fastRate;        // ... and falling at least this fast (mmol/L per min). fastBelow <= 0: off
        final int confirm;            // consecutive readings the rule must hold before warning (1 = at once)
        final double maxGlucose;      // never warn above this (a projection from a high start is rarely a low)

        Params(int windowMin, int horizonMin, double projBelow, double fastBelow, double fastRate) {
            this(windowMin, horizonMin, projBelow, fastBelow, fastRate, 1, 99);
        }

        Params(int windowMin, int horizonMin, double projBelow, double fastBelow, double fastRate,
               int confirm, double maxGlucose) {
            this.windowMin = windowMin;
            this.horizonMin = horizonMin;
            this.projBelow = projBelow;
            this.fastBelow = fastBelow;
            this.fastRate = fastRate;
            this.confirm = confirm;
            this.maxGlucose = maxGlucose;
        }

        /** What the app ships with ("balanced"): see docs/early-low-backtest.md for how it was chosen. */
        static final Params DEFAULT = new Params(20, 20, 4.0, 5.5, 0.10, 1, 7.0);

        @Override
        public String toString() {
            return String.format(Locale.US, "fit %d min, ahead %d min, proj < %.1f, fast: %s, confirm %d, only below %.1f",
                    windowMin, horizonMin, projBelow,
                    fastBelow > 0 ? String.format(Locale.US, "< %.1f and falling >= %.2f/min", fastBelow, fastRate) : "off",
                    confirm, maxGlucose);
        }
    }

    static final String OFF = "off", CALM = "calm", BALANCED = "balanced", EARLY = "early";

    /** The three sensitivity levels (null for off). Numbers and the trade-off: docs/early-low-backtest.md. */
    static Params preset(String mode) {
        if (CALM.equals(mode)) return new Params(20, 20, 3.8, 5.5, 0.10, 1, 7.0);
        if (EARLY.equals(mode)) return new Params(15, 25, 4.0, 5.5, 0.10, 1, 7.0);
        if (OFF.equals(mode)) return null;
        return Params.DEFAULT;
    }

    static final class Projection {
        final double now;        // glucose on the fitted line at the newest reading
        final double slope;      // mmol/L per minute, negative when falling
        final double projected;  // value on the line `horizonMin` ahead
        final long at;

        Projection(double now, double slope, double projected, long at) {
            this.now = now;
            this.slope = slope;
            this.projected = projected;
            this.at = at;
        }
    }

    /**
     * The fit at time `at` (the newest reading is items.get(last)). Null when there is nothing
     * trustworthy: fewer than 3 readings in the window, they span under 10 min, or the newest is
     * more than 7 min old.
     */
    static Projection project(List<double[]> items, long at, Params p) {
        int n = items.size();
        if (n < 3) return null;
        double[] newest = items.get(n - 1);
        if (at - newest[0] > 7 * MIN) return null;
        int j = n - 1;
        long from = (long) newest[0] - p.windowMin * MIN;
        while (j > 0 && items.get(j - 1)[0] >= from) j--;
        int cnt = n - j;
        if (cnt < 3 || newest[0] - items.get(j)[0] < 10 * MIN) return null;
        double mx = 0, my = 0;
        for (int k = j; k < n; k++) {
            mx += (items.get(k)[0] - newest[0]) / MIN;
            my += items.get(k)[1];
        }
        mx /= cnt;
        my /= cnt;
        double sxx = 0, sxy = 0;
        for (int k = j; k < n; k++) {
            double x = (items.get(k)[0] - newest[0]) / MIN - mx;
            sxx += x * x;
            sxy += x * (items.get(k)[1] - my);
        }
        if (sxx == 0) return null;
        double slope = sxy / sxx;
        double nowFit = my + slope * (0 - mx);
        return new Projection(nowFit, slope, nowFit + slope * p.horizonMin, (long) newest[0]);
    }

    static boolean fastFall(double glucose, Projection pr, Params p) {
        return p.fastBelow > 0 && glucose < p.fastBelow && pr.slope <= -p.fastRate + 1e-9;
    }

    /** Whether the rule says a low is coming at this reading. */
    static boolean fires(double glucose, Projection pr, Params p) {
        if (pr == null || glucose > p.maxGlucose) return false;
        return pr.projected < p.projBelow || fastFall(glucose, pr, p);
    }

    enum Action { NONE, WARN, REBUZZ }

    /** Debounce and the one re-buzz. Persisted between readings. */
    static final class Tracker {
        boolean active;            // a warning is out and the rule has not been quiet for 15 min since
        long warnAt, lastWarnAt, quietSince;
        int streak;                // consecutive readings the rule has held
        double warnProjected;
        boolean rebuzzed;

        /** Feed every reading, in order. `treating` suppresses everything (Treating mode). */
        Action update(long at, double glucose, Projection pr, Params p, boolean treating) {
            if (glucose < LOW) {            // already low: CamAPS and the urgent low backstop own this now
                active = false;
                quietSince = 0;
                streak = 0;
                lastWarnAt = at;                // the cooldown runs from the low, so nothing fires on the way back up
                return Action.NONE;
            }
            if (pr == null) return Action.NONE;       // no usable fit: keep the state as it is
            boolean fire = fires(glucose, pr, p);
            streak = fire ? streak + 1 : 0;
            if (fire) {
                quietSince = 0;
                if (treating) return Action.NONE;
                if (!active) {
                    if (streak < p.confirm || at - lastWarnAt < COOLDOWN) return Action.NONE;
                    active = true;
                    warnAt = lastWarnAt = at;
                    warnProjected = pr.projected;
                    rebuzzed = false;
                    return Action.WARN;
                }
                if (!rebuzzed && at - warnAt >= REBUZZ_AFTER && pr.projected <= warnProjected - REBUZZ_WORSE) {
                    rebuzzed = true;
                    return Action.REBUZZ;
                }
                return Action.NONE;
            }
            if (active) {
                if (quietSince == 0) quietSince = at;
                if (at - quietSince >= REARM_QUIET) active = false;
            }
            return Action.NONE;
        }

        String save() {
            return (active ? 1 : 0) + "," + warnAt + "," + lastWarnAt + "," + quietSince + ","
                    + String.format(Locale.US, "%.3f", warnProjected) + "," + (rebuzzed ? 1 : 0) + "," + streak;
        }

        static Tracker load(String s) {
            Tracker t = new Tracker();
            if (s == null || s.isEmpty()) return t;
            try {
                String[] a = s.split(",");
                t.active = "1".equals(a[0]);
                t.warnAt = Long.parseLong(a[1]);
                t.lastWarnAt = Long.parseLong(a[2]);
                t.quietSince = Long.parseLong(a[3]);
                t.warnProjected = Double.parseDouble(a[4]);
                t.rebuzzed = "1".equals(a[5]);
                t.streak = a.length > 6 ? Integer.parseInt(a[6]) : 0;
            } catch (Exception e) {
                return new Tracker();
            }
            return t;
        }
    }

    /** What happened after a warning: unknown yet, glucose went under LOW (minutes later), or it did not. */
    static final class Outcome {
        static final int PENDING = 0, LOW_FOLLOWED = 1, NO_LOW = 2;
        final int kind;
        final int minutes;

        Outcome(int kind, int minutes) {
            this.kind = kind;
            this.minutes = minutes;
        }
    }

    /** Judges a warning given the readings since it. `now` decides if the 45 min window is over. */
    static Outcome outcome(long warnAt, List<double[]> items, long now) {
        for (double[] g : items) {
            if (g[0] <= warnAt || g[0] - warnAt > OUTCOME_WINDOW) continue;
            if (g[1] < LOW) return new Outcome(Outcome.LOW_FOLLOWED, (int) Math.round((g[0] - warnAt) / (double) MIN));
        }
        return now - warnAt >= OUTCOME_WINDOW ? new Outcome(Outcome.NO_LOW, 0) : new Outcome(Outcome.PENDING, 0);
    }

    /** One warning and what came of it, kept for retuning. */
    static final class Entry {
        final long at;
        final double glucose, projected, slope;
        final String rule;          // "projection" or "fast fall"
        int rebuzz;                 // 0 or 1
        int kind = Outcome.PENDING;
        int minutes;                // minutes to the low, when kind is LOW_FOLLOWED
        double nadir = 99;          // lowest reading in the 45 min after

        Entry(long at, double glucose, double projected, double slope, String rule) {
            this.at = at;
            this.glucose = glucose;
            this.projected = projected;
            this.slope = slope;
            this.rule = rule;
        }

        /** "Low warning 14:05 (projection): glucose went under 3.9 22 min later" for the log. */
        String line(String when) {
            String r = kind == Outcome.LOW_FOLLOWED ? "glucose went under " + LOW + " " + minutes + " min later"
                    : kind == Outcome.NO_LOW ? "no low within 45 min, lowest " + String.format(Locale.US, "%.1f", nadir)
                    : "waiting";
            return "Low warning " + when + " (" + rule + String.format(Locale.US, ", glucose %.1f, projected %.1f", glucose, projected)
                    + (rebuzz > 0 ? ", re-buzzed" : "") + "): " + r;
        }
    }

    static final class Journal {
        static final int KEEP = 300;
        final List<Entry> items = new ArrayList<>();

        void add(Entry e) {
            items.add(e);
            while (items.size() > KEEP) items.remove(0);
        }

        Entry last() {
            return items.isEmpty() ? null : items.get(items.size() - 1);
        }

        /** Settles every entry whose answer is known; returns those settled just now. */
        List<Entry> resolve(List<double[]> readings, long now) {
            List<Entry> done = new ArrayList<>();
            for (Entry e : items) {
                if (e.kind != Outcome.PENDING) continue;
                Outcome o = outcome(e.at, readings, now);
                double nadir = 99;
                for (double[] g : readings) {
                    if (g[0] > e.at && g[0] - e.at <= OUTCOME_WINDOW) nadir = Math.min(nadir, g[1]);
                }
                e.nadir = nadir;
                if (o.kind == Outcome.PENDING) continue;
                e.kind = o.kind;
                e.minutes = o.minutes;
                done.add(e);
            }
            return done;
        }

        String save() {
            StringBuilder sb = new StringBuilder();
            for (Entry e : items) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.at).append(',').append(String.format(Locale.US, "%.1f,%.2f,%.3f,", e.glucose, e.projected, e.slope))
                        .append(e.rule.replace(',', ' ').replace(';', ' ')).append(',').append(e.rebuzz).append(',')
                        .append(e.kind).append(',').append(e.minutes).append(',')
                        .append(String.format(Locale.US, "%.1f", e.nadir));
            }
            return sb.toString();
        }

        static Journal load(String s) {
            Journal j = new Journal();
            if (s == null || s.isEmpty()) return j;
            try {
                for (String l : s.split(";")) {
                    String[] a = l.split(",");
                    Entry e = new Entry(Long.parseLong(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]),
                            Double.parseDouble(a[3]), a[4]);
                    e.rebuzz = Integer.parseInt(a[5]);
                    e.kind = Integer.parseInt(a[6]);
                    e.minutes = Integer.parseInt(a[7]);
                    e.nadir = Double.parseDouble(a[8]);
                    j.items.add(e);
                }
            } catch (Exception ex) {
                return new Journal();
            }
            return j;
        }

        /** "7 warnings in the log: 5 went under 3.9, 2 did not." */
        String summary() {
            int low = 0, none = 0, wait = 0;
            for (Entry e : items) {
                if (e.kind == Outcome.LOW_FOLLOWED) low++;
                else if (e.kind == Outcome.NO_LOW) none++;
                else wait++;
            }
            return items.size() + " warnings kept: " + low + " went under " + LOW + ", " + none + " did not"
                    + (wait > 0 ? ", " + wait + " waiting" : "") + ".";
        }
    }

    static final String TITLE = "Low coming";
    static final String TEXT = "Take 10 g of fast carbs now.";
    static final String TEXT_AGAIN = "Still heading low. Take another 10 g if you have not had any.";
}
