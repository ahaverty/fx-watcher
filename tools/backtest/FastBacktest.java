package studio.alans.fxwatcher;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fast lows only: falls of at least 2.0 mmol/L in the 20 min before the first reading under 3.9.
 * Sweeps short-window fall-rate rules (LowWarning's fast-fall rule alone) for precision.
 * Usage: java -cp out studio.alans.fxwatcher.FastBacktest sgv.csv
 */
public class FastBacktest {

    public static void main(String[] args) throws Exception {
        List<Long> ts = new ArrayList<>();
        List<Double> vs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(args[0]))) {
            String l;
            while ((l = br.readLine()) != null) {
                String[] a = l.trim().split(",");
                if (a.length < 2) continue;
                ts.add(Long.parseLong(a[0]));
                vs.add(Math.round(Integer.parseInt(a[1]) / Backtest.MG * 10) / 10.0);
            }
        }
        Backtest.n = ts.size();
        Backtest.t = new long[Backtest.n];
        Backtest.g = new double[Backtest.n];
        for (int i = 0; i < Backtest.n; i++) {
            Backtest.t[i] = ts.get(i);
            Backtest.g[i] = vs.get(i);
        }
        int[] unev = new int[1];
        List<Integer> all = Backtest.episodes(unev);
        List<Integer> fast = new ArrayList<>();
        for (int e : all) {
            double max = 0;
            for (int m = e; m >= 0 && Backtest.t[e] - Backtest.t[m] <= 20 * Backtest.MIN; m--) max = Math.max(max, Backtest.g[m]);
            if (max - Backtest.g[e] >= 2.0) fast.add(e);
        }
        Backtest.Range[] ranges = {
                new Backtest.Range("all", 0, Backtest.n),
                new Backtest.Range("2026", Backtest.idx(2026, 1, 1), Backtest.n)};
        System.out.println("low episodes " + all.size() + ", fast ones (fall >= 2.0 in 20 min) " + fast.size());
        for (Backtest.Range r : ranges) {
            int c = 0;
            for (int e : fast) if (e >= r.lo && e < r.hi) c++;
            System.out.printf(Locale.US, "%s: %d fast lows in %.0f days = %.2f/day%n", r.name, c, (r.hi - r.lo) / 288.0, c / ((r.hi - r.lo) / 288.0));
        }
        for (Backtest.Range r : ranges) {
            System.out.println("\n## " + r.name);
            System.out.println("| fit | rate >= | only under | fast lows caught 5+ min | caught 10+ min | caught 15+ min | median lead | warnings/day | false/day |");
            System.out.println("|---|---|---|---|---|---|---|---|---|");
            for (int w : new int[]{10, 15}) {
                for (double cap : new double[]{5.0, 5.5, 6.0, 6.5, 7.5}) {
                    for (double rate : new double[]{0.10, 0.12, 0.15, 0.18, 0.20}) {
                        LowWarning.Params p = new LowWarning.Params(w, 20, -99, cap, rate, 1, 99);
                        Backtest.Result x = Backtest.run(p, fast, r);
                        int c5 = 0, c10 = 0;
                        // recount with other lead thresholds
                        List<Long> warns = warnTimes(p, r);
                        List<Double> leads = new ArrayList<>();
                        for (int e : fast) {
                            if (e < r.lo || e >= r.hi) continue;
                            Long first = null;
                            for (long wt : warns) if (wt >= Backtest.t[e] - 60 * Backtest.MIN && wt <= Backtest.t[e]) { first = wt; break; }
                            if (first == null) continue;
                            double lead = (Backtest.t[e] - first) / (double) Backtest.MIN;
                            if (lead >= 5) c5++;
                            if (lead >= 10) c10++;
                            if (lead >= 5) leads.add(lead);
                        }
                        leads.sort(null);
                        double med = leads.isEmpty() ? 0 : leads.get(leads.size() / 2);
                        System.out.printf(Locale.US, "| %d | %.2f | %.1f | %.0f%% | %.0f%% | %.0f%% | %.0f | %.2f | %.2f |%n",
                                w, rate, cap, 100.0 * c5 / Math.max(1, x.lows), 100.0 * c10 / Math.max(1, x.lows),
                                100.0 * x.caught15 / Math.max(1, x.lows), med, x.warns / x.days, x.falseWarns / x.days);
                    }
                }
            }
        }
    }

    static List<Long> warnTimes(LowWarning.Params p, Backtest.Range r) {
        List<double[]> items = new ArrayList<>();
        LowWarning.Tracker tr = new LowWarning.Tracker();
        List<Long> out = new ArrayList<>();
        for (int i = Math.max(0, r.lo - 60); i < r.hi; i++) {
            long at = Backtest.t[i];
            while (!items.isEmpty() && items.get(0)[0] < at - 195 * Backtest.MIN) items.remove(0);
            items.add(new double[]{at, Backtest.g[i]});
            LowWarning.Action a = tr.update(at, Backtest.g[i], LowWarning.project(items, at, p), p, false);
            if (i >= r.lo && a == LowWarning.Action.WARN) out.add(at);
        }
        return out;
    }
}
