package studio.alans.fxwatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Which plans (by the key the sender gave them) have run in CamAPS, and how far. Lets a second tap on the same
 * plan say "already done" instead of starting Boost or a slow meal twice. Pure Java, stored as one string
 * in preferences: one line per plan, "key TAB at TAB complete TAB KIND,KIND".
 */
final class PlanRuns {

    private PlanRuns() { }

    static final long TTL_MS = 12 * 3_600_000L;
    static final int MAX = 20;

    static final class Run {
        final String key;
        final long at;
        final boolean complete;
        final List<String> done;

        Run(String key, long at, boolean complete, List<String> done) {
            this.key = key;
            this.at = at;
            this.complete = complete;
            this.done = done;
        }
    }

    /** A key is a short plain token: letters, digits, dash, underscore, dot, up to 64. Anything else is no key. */
    static boolean validKey(String key) {
        return key != null && key.matches("[A-Za-z0-9._-]{1,64}");
    }

    static List<Run> parse(String stored, long now) {
        List<Run> out = new ArrayList<>();
        if (stored == null) return out;
        for (String line : stored.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length != 4 || !validKey(f[0])) continue;
            try {
                long at = Long.parseLong(f[1]);
                if (now - at > TTL_MS || at > now + 60_000L) continue;
                List<String> done = new ArrayList<>();
                for (String k : f[3].split(",")) if (!k.isEmpty()) done.add(k);
                out.add(new Run(f[0], at, "1".equals(f[2]), done));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static String save(List<Run> runs) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, runs.size() - MAX);
        for (int i = from; i < runs.size(); i++) {
            Run r = runs.get(i);
            sb.append(r.key).append('\t').append(r.at).append('\t').append(r.complete ? "1" : "0").append('\t')
                    .append(String.join(",", r.done)).append('\n');
        }
        return sb.toString();
    }

    static Run find(String stored, String key, long now) {
        if (!validKey(key)) return null;
        for (Run r : parse(stored, now)) if (r.key.equals(key)) return r;
        return null;
    }

    /** A fresh run of this key (replacing any earlier record of it): nothing done yet. */
    static String start(String stored, String key, long now) {
        List<Run> runs = parse(stored, now);
        runs.removeIf(r -> r.key.equals(key));
        runs.add(new Run(key, now, false, new ArrayList<>()));
        return save(runs);
    }

    /** This step finished. complete: it was the last one. */
    static String stepDone(String stored, String key, long now, String kind, boolean complete) {
        List<Run> runs = parse(stored, now);
        Run old = null;
        for (Run r : runs) if (r.key.equals(key)) old = r;
        List<String> done = old == null ? new ArrayList<>() : new ArrayList<>(old.done);
        if (!done.contains(kind)) done.add(kind);
        runs.removeIf(r -> r.key.equals(key));
        runs.add(new Run(key, old == null ? now : old.at, complete, done));
        return save(runs);
    }

    /** The steps of the plan not yet done in this run, in order. */
    static List<CarbFill.Item> remaining(List<CarbFill.Item> items, Run run) {
        List<CarbFill.Item> out = new ArrayList<>();
        for (CarbFill.Item it : items) if (run == null || !run.done.contains(it.kind.name())) out.add(it);
        return out;
    }
}
