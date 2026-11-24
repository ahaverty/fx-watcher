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
    static final String DEFAULT_PRESETS = "20,30,45,60";
    /** One-tap meal amounts start here, so a quick tap is never a hypo-sized number (those are a separate, coral path). */
    static final int MEAL_QUICK_MIN = 20;
    static final int LOW_MIN_G = 5, LOW_MAX_G = 30, LOW_DEFAULT_G = 15;
    static final int[] LOW_CHIPS = {10, 15, 20};
    static final long PLAN_TTL_MS = 2 * 3_600_000L;
    /** Boost and Ease-off run from 15 minutes to 6 hours, in steps of 5 minutes. */
    static final int MODE_MIN = 15, MODE_MAX = 360;
    static final int SLOTS = 6, MAX_PLAN_ITEMS = 2, MAX_PRESETS = 12;

    /** A whole number of grams from 1 to 150: the only thing that is ever typed into CamAPS. */
    static boolean valid(int grams) {
        return grams >= MIN_G && grams <= MAX_G;
    }

    /** The range for each kind: a meal 1 to 150, a slowly absorbed meal 5 to 120, a low treatment 5 to 30. */
    static boolean validFor(CarbFill.Kind kind, int grams) {
        if (kind == CarbFill.Kind.LOW) return validLow(grams);
        if (kind == CarbFill.Kind.SLOW) return grams >= STEP_MIN && grams <= STEP_MAX;
        if (CarbFill.isMode(kind)) return grams >= MODE_MIN && grams <= MODE_MAX && grams % 5 == 0;
        return valid(grams);
    }

    /** A low treatment: whole grams 5 to 30. */
    static boolean validLow(int grams) {
        return grams >= LOW_MIN_G && grams <= LOW_MAX_G;
    }

    /**
     * Whether the widget shows "Treat low": a reading from the last 12 min that is under 5.0, or the
     * calm state is low soon or treating. Never when there is no fresh reading, and never otherwise, so it
     * is not there to be tapped by accident.
     */
    static boolean lowOffered(double glucose, long glucoseAt, String state, long now) {
        if (glucose <= 0 || now - glucoseAt > 12 * 60_000L) return false;
        return glucose < 5.0 || CalmState.LOW_SOON.equals(state) || CalmState.TREATING.equals(state);
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

    /**
     * The plan after a bolus with these carbs was delivered: the item that matches (within 3 g or 15%) is used up, so the
     * widgets stop offering it. Nothing else is removed. Returns the same plan when nothing matched.
     */
    static Plan without(Plan p, double carbs) {
        if (Double.isNaN(carbs) || carbs <= 0) return p;
        Plan q = new Plan(p.at);
        boolean used = false;
        for (PlanItem i : p.items) {
            if (!used && Math.abs(i.grams - carbs) <= Math.max(3.0, i.grams * 0.15)) {
                used = true;
                continue;
            }
            q.items.add(i);
        }
        return used ? q : p;
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
            if (g < MEAL_QUICK_MIN) continue;
            if (out.size() >= SLOTS) break;
            boolean dup = false;
            for (Option o : out) if (o.grams == g && !o.plan) dup = true;
            if (!dup) out.add(new Option(g + " g", g, false));
        }
        return out.size() > SLOTS ? out.subList(0, SLOTS) : out;
    }

    /**
     * The size band of an amount, for the widget's colour: 0 small (up to 15 g, the hypo-treatment sizes),
     * 1 snack (16 to 30), 2 meal (31 to 50), 3 big (over 50). Pure, so it is tested.
     */
    static final int STEP = 5, STEP_MIN = 5, STEP_MAX = 120, STEP_DEFAULT = 20;

    /** The stepper: move by `delta` grams, keep it a multiple of 5 between 5 and 120. */
    static int stepped(int current, int delta) {
        int v = Math.round((current + delta) / (float) STEP) * STEP;
        return Math.max(STEP_MIN, Math.min(STEP_MAX, v));
    }

    static int band(int grams) {
        if (grams <= 15) return 0;
        if (grams <= 30) return 1;
        if (grams <= 50) return 2;
        return 3;
    }

    static String describe(int grams) {
        return String.format(Locale.UK, "%d g", grams);
    }
}
