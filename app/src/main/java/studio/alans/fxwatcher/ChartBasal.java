package studio.alans.fxwatcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sends the basal read from CamAPS's chart ({@link ChartParser}) to Nightscout as "Temp Basal"
 * treatments, one per clock-aligned 5-min bin (created_at = bin start, duration 5, absolute =
 * the bin's average U/h, source camaps-chart). Nightscout upserts on (created_at, eventType),
 * so a bin read again simply replaces itself.
 *
 * The {@link Ledger} remembers what was sent per bin and at which zoom, so each look at the chart
 * only sends what is new, and a bin is only replaced by a reading at the same or a finer zoom.
 *
 * Slowly absorbed meals on the chart go as "CamAPS Slow meal" (created_at = when entered,
 * duration = minutes until CamAPS expects it absorbed, slow_carbs_g). The amount is NOT in a
 * "carbs" field: the Add meal entry (screen reader) and Glooko already carry those carbs.
 * {@link MealLedger} sends each meal once, again only if its end moved at the same or a finer
 * zoom, always under the first created_at so Nightscout replaces it.
 */
final class ChartBasal {

    private ChartBasal() { }

    private static final String TAG = "FXChart";
    private static final long KEEP = 8 * 24 * 3600_000L;
    private static final int BATCH = 100;

    /** Sent bins: start ms -> {rate x 100, px per min x 100}. Pure, for the unit tests. */
    static final class Ledger {
        final TreeMap<Long, long[]> bins = new TreeMap<>();

        static Ledger load(String s) {
            Ledger l = new Ledger();
            if (s == null || s.isEmpty()) return l;
            try {
                for (String e : s.split(";")) {
                    String[] a = e.split(",");
                    l.bins.put(Long.parseLong(a[0]), new long[]{Long.parseLong(a[1]), Long.parseLong(a[2])});
                }
            } catch (Exception e) {
                return new Ledger();   // damaged: sending again is harmless (upsert)
            }
            return l;
        }

        String save() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Long, long[]> e : bins.entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append(',').append(e.getValue()[0]).append(',').append(e.getValue()[1]);
            }
            return sb.toString();
        }

        /**
         * The parsed bins ({start, rate x 100}) worth sending, recorded as sent: bins not sent
         * before, and bins whose rate changed when read at a zoom at least 0.8x as fine. Forgets
         * bins older than 8 days.
         */
        List<long[]> take(List<long[]> parsed, double pxPerMin, long now) {
            bins.headMap(now - KEEP).clear();
            long res = Math.round(pxPerMin * 100);
            List<long[]> out = new ArrayList<>();
            for (long[] b : parsed) {
                if (b[0] < now - KEEP) continue;
                long[] old = bins.get(b[0]);
                if (old != null && (old[0] == b[1] || res < old[1] * 0.8)) continue;
                bins.put(b[0], new long[]{b[1], Math.max(res, old == null ? 0 : old[1])});
                out.add(b);
            }
            return out;
        }
    }

    /** Sent slow meals: {created_at ms, end ms, grams, px per min x 100}. Pure, for the unit tests. */
    static final class MealLedger {
        static final long SAME = 3 * 60_000L;   // tick read at another zoom: same meal within 3 min
        final List<long[]> meals = new ArrayList<>();

        static MealLedger load(String s) {
            MealLedger l = new MealLedger();
            if (s == null || s.isEmpty()) return l;
            try {
                for (String e : s.split(";")) {
                    String[] a = e.split(",");
                    l.meals.add(new long[]{Long.parseLong(a[0]), Long.parseLong(a[1]), Long.parseLong(a[2]), Long.parseLong(a[3])});
                }
            } catch (Exception e) {
                return new MealLedger();
            }
            return l;
        }

        String save() {
            StringBuilder sb = new StringBuilder();
            for (long[] m : meals) {
                if (sb.length() > 0) sb.append(';');
                sb.append(m[0]).append(',').append(m[1]).append(',').append(m[2]).append(',').append(m[3]);
            }
            return sb.toString();
        }

        /** Meals worth sending, as {created_at, end, grams}; recorded as sent. Forgets after 8 days. */
        List<long[]> take(List<ChartParser.SlowMeal> parsed, double pxPerMin, long now) {
            meals.removeIf(m -> m[0] < now - KEEP);
            long res = Math.round(pxPerMin * 100);
            List<long[]> out = new ArrayList<>();
            for (ChartParser.SlowMeal p : parsed) {
                long[] old = null;
                for (long[] m : meals) if (Math.abs(m[0] - p.start) <= SAME && m[2] == p.grams) old = m;
                if (old == null) {
                    meals.add(new long[]{p.start, p.end, p.grams, res});
                    out.add(new long[]{p.start, p.end, p.grams});
                } else if (Math.abs(old[1] - p.end) > 2 * 60_000L && res >= old[3] * 0.8) {
                    old[1] = p.end;
                    old[3] = Math.max(old[3], res);
                    out.add(new long[]{old[0], p.end, p.grams});
                }
            }
            return out;
        }
    }

    /** A parsed chart: send its new bins and slow meals. */
    static synchronized void handle(Context c, ChartParser.Result r, long now) {
        List<long[]> bins = ChartParser.bins(r);
        SharedPreferences p = Store.p(c);
        Ledger l = Ledger.load(p.getString(Store.CHART_SENT, ""));
        List<long[]> send = l.take(bins, r.pxPerMin, now);
        p.edit().putString(Store.CHART_SENT, l.save()).apply();
        meals(c, r, now);
        Log.i(TAG, "chart " + Store.hm(r.left) + "-" + Store.hm(r.right) + ": " + bins.size() + " bins, "
                + send.size() + " new, " + Math.round(r.pxPerMin * 60) + " px/h");
        if (send.isEmpty()) return;
        Store.log(c, "Chart basal: " + send.size() + " x 5 min, " + Store.hm(send.get(0)[0]) + "-"
                + Store.hm(send.get(send.size() - 1)[0] + ChartParser.BIN_MIN * 60_000L));
        try {
            JSONArray batch = new JSONArray();
            for (long[] b : send) {
                JSONObject t = Nightscout.treatment("Temp Basal", b[0], "camaps-chart");
                double rate = b[1] / 100.0;
                t.put("duration", ChartParser.BIN_MIN);
                t.put("absolute", rate);
                t.put("rate", rate);
                batch.put(t);
                if (batch.length() == BATCH) { Nightscout.postTreatments(c, batch); batch = new JSONArray(); }
            }
            if (batch.length() > 0) Nightscout.postTreatments(c, batch);
        } catch (Exception e) {
            Log.w(TAG, "send", e);
        }
    }

    private static void meals(Context c, ChartParser.Result r, long now) {
        SharedPreferences p = Store.p(c);
        MealLedger l = MealLedger.load(p.getString(Store.CHART_MEALS, ""));
        List<long[]> send = l.take(r.slowMeals, r.pxPerMin, now);
        if (send.isEmpty()) return;
        p.edit().putString(Store.CHART_MEALS, l.save()).apply();
        for (long[] m : send) {
            try {
                JSONObject t = Nightscout.treatment("CamAPS Slow meal", m[0], "camaps-chart");
                t.put("duration", Math.round((m[1] - m[0]) / 60_000.0));
                t.put("slow_carbs_g", m[2]);
                t.put("absorbed_by", Nightscout.iso(m[1]));
                t.put("notes", "Slowly absorbed " + m[2] + " g, " + Store.hm(m[0]) + "-" + Store.hm(m[1]));
                Store.log(c, "Chart: slow meal " + m[2] + " g, " + Store.hm(m[0]) + "-" + Store.hm(m[1]));
                Nightscout.postTreatment(c, t);
            } catch (Exception e) {
                Log.w(TAG, "meal", e);
            }
        }
    }
}
