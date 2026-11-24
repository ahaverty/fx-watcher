package studio.alans.fxwatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The carb amounts offered by the widget and the app: the plan an app such as Meal Log sent,
 * then the quick presets from Settings. Pure Java, so the limits are unit tested.
 *
 * Nothing here talks to CamAPS. The amount is only ever typed by CarbFill, after a tap, and only
 * if it passes {@link #valid}.
 */
final class CarbOptions {

    private CarbOptions() { }

    static final int MIN_G = 1, MAX_G = 150;
    static final String DEFAULT_PRESETS = "10,15,20,30,45,60";
    static final long PLAN_TTL_MS = 2 * 3_600_000L;
    static final int SLOTS = 6, MAX_PLAN_ITEMS = 2, MAX_PRESETS = 12;

    /** A whole number of grams from 1 to 150: the only thing that is ever typed into CamAPS. */
    static boolean valid(int grams) {
        return grams >= MIN_G && grams <= MAX_G;
    }

    /** "10, 15 20;30" to [10, 15, 20, 30]. Drops anything not a whole number in range, and repeats. */
    static List<Integer> parsePresets(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            try {
                int g = Integer.parseInt(part.trim());
                if (valid(g) && !out.contains(g)) out.add(g);
            } catch (NumberFormatException ignored) { }
            if (out.size() >= MAX_PRESETS) break;
        }
        return out;
    }

    static String joinPresets(List<Integer> list) {
        StringBuilder sb = new StringBuilder();
        for (int g : list) sb.append(sb.length() > 0 ? "," : "").append(g);
        return sb.toString();
    }

    static final class Option {
        final String label;      // "Plan 45 g", "30 g"
        final int grams;
        final boolean plan;

        Option(String label, int grams, boolean plan) {
            this.label = label;
            this.grams = grams;
            this.plan = plan;
        }
    }

    static final class PlanItem {
        final String name;
        final int grams;

        PlanItem(String name, int grams) {
            this.name = name;
            this.grams = grams;
        }
    }

    /** The plan another app last sent, with when. Stored as "at;name|grams;name|grams". */
    static final class Plan {
        final long at;
        final List<PlanItem> items = new ArrayList<>();

        Plan(long at) {
            this.at = at;
        }

        boolean fresh(long now) {
            return !items.isEmpty() && now - at >= 0 && now - at <= PLAN_TTL_MS;
        }

        String save() {
            StringBuilder sb = new StringBuilder().append(at);
            for (PlanItem i : items) sb.append(';').append(i.name.replace('|', ' ').replace(';', ' ')).append('|').append(i.grams);
            return sb.toString();
        }

        static Plan load(String s) {
            Plan p = new Plan(0);
            if (s == null || s.isEmpty()) return p;
            try {
                String[] a = s.split(";");
                Plan q = new Plan(Long.parseLong(a[0]));
                for (int i = 1; i < a.length; i++) {
                    PlanItem it = parseItem(a[i]);
                    if (it != null) q.items.add(it);
                }
                return q;
            } catch (Exception e) {
                return p;
            }
        }
    }

    /** "Plan|45" to an item, or null if the name is empty or the grams are out of range. */
    static PlanItem parseItem(String s) {
        if (s == null) return null;
        int bar = s.lastIndexOf('|');
        if (bar <= 0) return null;
        String name = s.substring(0, bar).trim();
        if (name.isEmpty() || name.length() > 24) return null;
        try {
            int g = (int) Math.round(Double.parseDouble(s.substring(bar + 1).trim()));
            return valid(g) ? new PlanItem(name, g) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** What an app sent ("Plan|45;Usual|60") to a plan. Bad items are dropped; at most 2 are kept. */
    static Plan fromBroadcast(String items, long now) {
        Plan p = new Plan(now);
        if (items == null) return p;
        for (String part : items.split(";")) {
            PlanItem it = parseItem(part);
            if (it != null && p.items.size() < MAX_PLAN_ITEMS) p.items.add(it);
        }
        return p;
    }

    /** The widget's and the app's buttons: plan items first, then the presets, up to 6. */
    static List<Option> options(Plan plan, List<Integer> presets, long now) {
        List<Option> out = new ArrayList<>();
        if (plan != null && plan.fresh(now)) {
            for (PlanItem i : plan.items) out.add(new Option(i.name + " " + i.grams + " g", i.grams, true));
        }
        for (int g : presets) {
            if (out.size() >= SLOTS) break;
            boolean dup = false;
            for (Option o : out) if (o.grams == g && !o.plan) dup = true;
            if (!dup) out.add(new Option(g + " g", g, false));
        }
        return out.size() > SLOTS ? out.subList(0, SLOTS) : out;
    }

    static String describe(int grams) {
        return String.format(Locale.UK, "%d g", grams);
    }
}
