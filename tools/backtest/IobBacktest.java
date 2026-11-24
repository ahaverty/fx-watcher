package studio.alans.fxwatcher;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;

/**
 * Does insulin on board sharpen the fall-rate rule? Uses delivered boluses FX Watcher logged to
 * Nightscout (since 6 Oct 2026) and the same insulin curve the app uses (NudgeRules.remaining).
 * Usage: java -cp out studio.alans.fxwatcher.IobBacktest sgv.csv boluses.csv
 */
public class IobBacktest {

    static double[] iob;

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
        List<double[]> bol = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(args[1]))) {
            String l;
            while ((l = br.readLine()) != null) {
                String[] a = l.trim().split(",");
                bol.add(new double[]{Long.parseLong(a[0]), Double.parseDouble(a[1])});
            }
        }
        int n = ts.size();
        Backtest.n = n;
        Backtest.t = new long[n];
        Backtest.g = new double[n];
        iob = new double[n];
        for (int i = 0; i < n; i++) {
            Backtest.t[i] = ts.get(i);
            Backtest.g[i] = vs.get(i);
            double sum = 0;
            for (double[] b : bol) {
                if (b[0] > Backtest.t[i]) break;
                sum += b[1] * NudgeRules.remaining((Backtest.t[i] - b[0]) / 60000.0);
            }
            iob[i] = sum;
        }
        Backtest.Range r = new Backtest.Range("since 6 Oct 2026", Backtest.idx(2026, 10, 6), n);
        List<Integer> all = Backtest.episodes(new int[1]);
        List<Integer> fast = new ArrayList<>();
        int lowsInRange = 0;
        for (int e : all) {
            if (e < r.lo) continue;
            lowsInRange++;
            double max = 0;
            for (int m = e; m >= 0 && Backtest.t[e] - Backtest.t[m] <= 20 * Backtest.MIN; m--) max = Math.max(max, Backtest.g[m]);
            if (max - Backtest.g[e] >= 2.0) fast.add(e);
        }
        int withIob = 0;
        for (int e : fast) if (iob[e] >= 1.0) withIob++;
        System.out.printf(Locale.US, "since 6 Oct: %d days, %d lows, %d fast, of which %d had >= 1.0 U on board%n",
                (n - r.lo) / 288, lowsInRange, fast.size(), withIob);
        System.out.println("| rule | fast lows warned 5+ min | 10+ min | all lows warned 10+ min | warnings/day | false/day |");
        System.out.println("|---|---|---|---|---|---|");
        row("A fall >= 0.10/min, under 6.0 (no insulin info)", d -> new LowWarning.Params(15, 20, -99, 6.0, 0.10, 1, 99), fast, all, r);
        row("B fall >= 0.12/min, under 6.0 (no insulin info)", d -> new LowWarning.Params(15, 20, -99, 6.0, 0.12, 1, 99), fast, all, r);
        row("C 0.07/min if >= 1.0 U on board, else 0.12", d -> new LowWarning.Params(15, 20, -99, 6.0, d >= 1.0 ? 0.07 : 0.12, 1, 99), fast, all, r);
        row("D 0.08/min if >= 1.0 U on board, else 0.15", d -> new LowWarning.Params(15, 20, -99, 6.0, d >= 1.0 ? 0.08 : 0.15, 1, 99), fast, all, r);
        row("E 0.10/min only if >= 0.5 U on board", d -> d >= 0.5 ? new LowWarning.Params(15, 20, -99, 6.0, 0.10, 1, 99) : new LowWarning.Params(15, 20, -99, 0, 0, 1, 99), fast, all, r);
        row("F 0.08/min if >= 1.5 U on board, else off", d -> d >= 1.5 ? new LowWarning.Params(15, 20, -99, 6.5, 0.08, 1, 99) : new LowWarning.Params(15, 20, -99, 0, 0, 1, 99), fast, all, r);
        row("G 0.12/min if < 0.5 U on board, 0.08 if >= 0.5 U, under 6.5", d -> new LowWarning.Params(15, 20, -99, 6.5, d >= 0.5 ? 0.08 : 0.12, 1, 99), fast, all, r);
    }

    static void row(String name, DoubleFunction<LowWarning.Params> pick, List<Integer> fast, List<Integer> all, Backtest.Range r) {
        List<double[]> items = new ArrayList<>();
        LowWarning.Tracker tr = new LowWarning.Tracker();
        List<Long> warns = new ArrayList<>();
        for (int i = Math.max(0, r.lo - 60); i < r.hi; i++) {
            long at = Backtest.t[i];
            while (!items.isEmpty() && items.get(0)[0] < at - 195 * Backtest.MIN) items.remove(0);
            items.add(new double[]{at, Backtest.g[i]});
            LowWarning.Params p = pick.apply(iob[i]);
            LowWarning.Action a = tr.update(at, Backtest.g[i], LowWarning.project(items, at, p), p, false);
            if (i >= r.lo && a == LowWarning.Action.WARN) warns.add(at);
        }
        double days = (r.hi - r.lo) / 288.0;
        int f5 = 0, f10 = 0, a10 = 0, nf = 0, na = 0;
        for (int e : fast) if (e >= r.lo) { nf++; double l = lead(warns, e); if (l >= 5) f5++; if (l >= 10) f10++; }
        for (int e : all) if (e >= r.lo) { na++; if (lead(warns, e) >= 10) a10++; }
        int fals = 0;
        for (long w : warns) {
            boolean hit = false;
            for (int m = 0; m < Backtest.n; m++) {
                if (Backtest.t[m] > w && Backtest.t[m] - w <= 45 * Backtest.MIN && Backtest.g[m] < 3.9) { hit = true; break; }
                if (Backtest.t[m] - w > 45 * Backtest.MIN) break;
            }
            if (!hit) fals++;
        }
        System.out.printf(Locale.US, "| %s | %.0f%% (%d/%d) | %.0f%% | %.0f%% (%d/%d) | %.2f | %.2f |%n", name,
                100.0 * f5 / nf, f5, nf, 100.0 * f10 / nf, 100.0 * a10 / na, a10, na, warns.size() / days, fals / days);
    }

    static double lead(List<Long> warns, int e) {
        for (long w : warns) if (w >= Backtest.t[e] - 60 * Backtest.MIN && w <= Backtest.t[e]) return (Backtest.t[e] - w) / 60000.0;
        return -1;
    }
}
