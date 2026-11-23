package studio.alans.fxwatcher;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Backtests LowWarning (the code the phone runs) against Nightscout history.
 * Usage: java -cp out studio.alans.fxwatcher.Backtest sgv.csv      (lines "epoch_ms,mg_dl,device", oldest first)
 * Build: javac -d out app/src/main/java/studio/alans/fxwatcher/LowWarning.java tools/backtest/Backtest.java
 *
 * Readings are rounded to 0.1 mmol/L like CamAPS's notification, which is what the app sees.
 * "Lows" are episodes whose first reading is under 3.9 after 30 clean minutes at or above it.
 */
public class Backtest {

    static final double MG = 18.0182;
    static final long MIN = 60_000L;

    static long[] t;
    static double[] g;
    static int n;

    static class Range {
        final String name;
        final int lo, hi;

        Range(String name, int lo, int hi) {
            this.name = name;
            this.lo = lo;
            this.hi = hi;
        }
    }

    static int lowerBound(long ms) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (t[m] < ms) lo = m + 1;
            else hi = m;
        }
        return lo;
    }

    static int idx(int y, int mo, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, mo - 1, d);
        return lowerBound(c.getTimeInMillis());
    }

    /** Index of the first reading of each low episode that has 30 clean minutes before it. */
    static List<Integer> episodes(int[] unevaluable) {
        List<Integer> out = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (g[i] < LowWarning.LOW) {
                int start = i, j = i, k = j;
                while (k + 1 < n && t[k + 1] - t[j] <= 30 * MIN) {
                    k++;
                    if (g[k] < LowWarning.LOW) j = k;
                }
                boolean clean = start > 0 && t[start] - t[start - 1] <= 10 * MIN;
                int cnt = 0;
                for (int m = start - 1; m >= 0 && t[start] - t[m] <= 30 * MIN; m--) {
                    cnt++;
                    if (g[m] < LowWarning.LOW || t[m + 1] - t[m] > 10 * MIN) clean = false;
                }
                if (cnt < 4) clean = false;
                if (clean) out.add(start);
                else unevaluable[0]++;
                i = j + 1;
                continue;
            }
            i++;
        }
        return out;
    }

    static class Result {
        int lows, caught15, caughtAny, warns, rebuzzes, falseWarns, hardFalse;
        double[] leads = new double[0];
        double days;

        double median() {
            if (leads.length == 0) return 0;
            double[] s = leads.clone();
            Arrays.sort(s);
            return s[s.length / 2];
        }
    }

    /** Runs the app's rule over every reading and scores it on [lo, hi). */
    static Result run(LowWarning.Params p, List<Integer> eps, Range r) {
        List<double[]> items = new ArrayList<>();
        LowWarning.Tracker tr = new LowWarning.Tracker();
        List<Long> warnAt = new ArrayList<>();
        int rebuzz = 0;
        for (int i = Math.max(0, r.lo - 60); i < r.hi; i++) {
            long at = t[i];
            while (!items.isEmpty() && items.get(0)[0] < at - (3 * 60 + 15) * MIN) items.remove(0);
            items.add(new double[]{at, g[i]});
            LowWarning.Projection pr = LowWarning.project(items, at, p);
            LowWarning.Action a = tr.update(at, g[i], pr, p, false);
            if (i < r.lo) continue;
            if (a == LowWarning.Action.WARN) warnAt.add(at);
            else if (a == LowWarning.Action.REBUZZ) rebuzz++;
        }
        Result res = new Result();
        res.warns = warnAt.size();
        res.rebuzzes = rebuzz;
        res.days = (r.hi - r.lo) / 288.0;
        List<Double> leads = new ArrayList<>();
        for (int e : eps) {
            if (e < r.lo || e >= r.hi) continue;
            res.lows++;
            long start = t[e];
            Long first = null;
            for (long w : warnAt) {
                if (w >= start - 60 * MIN && w <= start) {
                    first = w;
                    break;
                }
            }
            if (first != null) {
                res.caughtAny++;
                double lead = (start - first) / (double) MIN;
                if (lead >= 15) {
                    res.caught15++;
                    leads.add(lead);
                }
            }
        }
        res.leads = new double[leads.size()];
        for (int i = 0; i < res.leads.length; i++) res.leads[i] = leads.get(i);
        for (long w : warnAt) {
            boolean hit = false;
            double nadir = 99;
            for (int m = lowerBound(w); m < n && t[m] - w <= LowWarning.OUTCOME_WINDOW; m++) {
                if (t[m] <= w) continue;
                nadir = Math.min(nadir, g[m]);
                if (g[m] < LowWarning.LOW) hit = true;
            }
            if (!hit) {
                res.falseWarns++;
                if (nadir >= 4.5) res.hardFalse++;   // not even a near miss
            }
        }
        return res;
    }

    public static void main(String[] args) throws Exception {
        List<Long> ts = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(args[0]))) {
            String l;
            while ((l = br.readLine()) != null) {
                String[] a = l.trim().split(",");
                if (a.length < 2) continue;
                ts.add(Long.parseLong(a[0]));
                vals.add(Math.round(Integer.parseInt(a[1]) / MG * 10) / 10.0);
            }
        }
        n = ts.size();
        t = new long[n];
        g = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = ts.get(i);
            g[i] = vals.get(i);
        }
        int[] unev = new int[1];
        List<Integer> eps = episodes(unev);
        Range[] ranges = {
                new Range("all (Jan 2024 to now)", 0, n),
                new Range("2026 only", idx(2026, 1, 1), n),
                new Range("since 6 Oct 2026 (G7 + xDrip upload)", idx(2026, 10, 6), n)};
        System.out.println("readings " + n + ", low episodes (under 3.9) " + eps.size()
                + ", skipped (no clean 30 min before) " + unev[0]);
        for (Range r : ranges) {
            int c = 0;
            for (int e : eps) if (e >= r.lo && e < r.hi) c++;
            System.out.printf(Locale.US, "range %s: %d readings (about %.0f days of data), %d lows%n",
                    r.name, r.hi - r.lo, (r.hi - r.lo) / 288.0, c);
        }
        double[] projs = {3.8, 4.0, 4.5, 5.0};
        int[] windows = {15, 20}, horizons = {20, 25, 30}, confirms = {1, 2};
        double[] caps = {99, 8.0, 7.0};
        double[][] fasts = {{0, 0}, {5.5, 0.10}};
        for (Range r : ranges) {
            System.out.println();
            System.out.println("## " + r.name);
            System.out.println("| fit | ahead | proj below | fast rule | confirm | only below | lows | caught 15+ min | median lead | any warning | warnings/day | false/day | hard false/day | rebuzz/day |");
            System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
            for (int w : windows) for (int h : horizons) for (double pj : projs) for (double[] f : fasts)
                for (int cf : confirms) for (double cap : caps) {
                    LowWarning.Params p = new LowWarning.Params(w, h, pj, f[0], f[1], cf, cap);
                    Result x = run(p, eps, r);
                    System.out.printf(Locale.US,
                            "| %d | %d | %.1f | %s | %d | %s | %d | %.0f%% | %.0f | %.0f%% | %.2f | %.2f | %.2f | %.2f |%n",
                            w, h, pj, f[0] > 0 ? String.format(Locale.US, "<%.1f & %.2f/min", f[0], f[1]) : "off",
                            cf, cap > 50 ? "any" : String.format(Locale.US, "%.1f", cap),
                            x.lows, 100.0 * x.caught15 / Math.max(1, x.lows), x.median(),
                            100.0 * x.caughtAny / Math.max(1, x.lows), x.warns / x.days,
                            x.falseWarns / x.days, x.hardFalse / x.days, x.rebuzzes / x.days);
                }
        }
    }
}
