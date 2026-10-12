package studio.alans.fxwatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Boost / Ease-off from the CamAPS screen, decided by explicit events only (pure Java, unit
 * tested; CamapsReader persists it with save()/load()):
 *   - start:  Confirm on the "Boost start" dialog (Now, or Later at a time)
 *   - bar:    the running bar ("HH:MM:SS" remaining + kind) on the home screen
 *   - stop:   Confirm on the "Boost" / "Ease-off" dialog with "Off" (the bar's X)
 *   - cancel: Confirm on the "Planned boost" / "Planned ease-off" dialog
 * A home screen WITHOUT the bar is never taken as a cancel: CamAPS can draw the home screen
 * for a moment before the bar, and that would have recorded a false cancel and then a second
 * Boost. Planned ones are kept per kind (not in one shared note), so confirming an Ease-off
 * cannot make a planned Boost start again as a new one.
 *
 * Each Post is one Nightscout treatment, keyed by (kind, start): sending the same start again
 * updates it (Nightscout upserts on created_at + eventType).
 */
final class BoostTracker {

    static final String BOOST = "Boost", EASE = "Ease-off";

    static final class Post {
        final String kind;
        final long start, end;
        final boolean startKnown;
        final String ended;     // planned | cancelled

        Post(String kind, long start, long end, boolean startKnown, String ended) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.startKnown = startKnown;
            this.ended = ended;
        }
    }

    /** One Boost or Ease-off: running (active) or planned for later. */
    static final class Span {
        String kind;
        long start, end;
        boolean startKnown;

        Span(String kind, long start, long end, boolean startKnown) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.startKnown = startKnown;
        }
    }

    Span active;                 // running now
    Span plannedBoost, plannedEase;
    String nowKind;              // confirmed "Now" waiting for its bar
    long nowAt;

    private Span planned(String kind) {
        return BOOST.equals(kind) ? plannedBoost : plannedEase;
    }

    private void setPlanned(String kind, Span s) {
        if (BOOST.equals(kind)) plannedBoost = s; else plannedEase = s;
    }

    /** Confirm on the start dialog. plannedAt = 0 for Now. minutes may be -1 (not seen). */
    List<Post> start(String kind, int minutes, long plannedAt, long now) {
        List<Post> out = new ArrayList<>();
        if (plannedAt > now + 60_000L) {
            long end = minutes > 0 ? plannedAt + minutes * 60_000L : plannedAt;
            Span s = new Span(kind, plannedAt, end, true);
            setPlanned(kind, s);
            if (minutes > 0) out.add(new Post(kind, s.start, s.end, true, "planned"));
        } else {
            nowKind = kind;
            nowAt = now;
        }
        return out;
    }

    /** The running bar: kind and remaining time. */
    List<Post> bar(String kind, long remaining, long now) {
        List<Post> out = new ArrayList<>();
        long end = now + remaining;
        if (active != null && active.kind.equals(kind)) {
            if (Math.abs(active.end - end) > 90_000L) {          // extended or shortened
                active.end = end;
                out.add(new Post(kind, active.start, end, active.startKnown, "planned"));
            }
            return out;
        }
        if (active != null) {                                   // the other kind replaced it
            active = null;
        }
        Span p = planned(kind);
        long start = now;
        boolean known = false;
        // A planned one has started if its start has come and it hasn't run out (length unknown:
        // allow a day, since the bar may only be seen when CamAPS is next opened).
        long plannedUntil = p == null ? 0 : p.end > p.start ? p.end : p.start + 24 * 3600_000L;
        if (p != null && p.start <= now + 60_000L && now < plannedUntil + 60_000L) {
            start = p.start;                                    // the planned one has started
            known = true;
            setPlanned(kind, null);
        } else if (kind.equals(nowKind) && now - nowAt < 180_000L) {
            start = nowAt;                                      // confirmed "Now" just before
            known = true;
        }
        nowKind = null;
        active = new Span(kind, start, end, known);
        out.add(new Post(kind, start, end, known, "planned"));
        return out;
    }

    /** Home screen without a bar: only forgets a span that has run its course. */
    void noBar(long now) {
        if (active != null && now >= active.end - 60_000L) active = null;
    }

    /** Confirm on the stop dialog ("Boost" / "Ease-off" with "Off"). */
    List<Post> stop(String kind, long now) {
        List<Post> out = new ArrayList<>();
        if (active != null && active.kind.equals(kind)) {
            if (now < active.end - 60_000L) {
                out.add(new Post(kind, active.start, now, active.startKnown, "cancelled"));
            }
            active = null;
        }
        return out;
    }

    /** Confirm on the "Planned boost" / "Planned ease-off" dialog. */
    List<Post> cancelPlanned(String kind, long now) {
        List<Post> out = new ArrayList<>();
        Span p = planned(kind);
        if (p != null) {
            out.add(new Post(kind, p.start, p.start, true, "cancelled"));
            setPlanned(kind, null);
        }
        return out;
    }

    // ── Persistence (plain text; org.json is not available in local unit tests) ──

    private static String span(Span s) {
        return s == null ? "" : s.kind + "," + s.start + "," + s.end + "," + s.startKnown;
    }

    private static Span span(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] a = s.split(",");
        return new Span(a[0], Long.parseLong(a[1]), Long.parseLong(a[2]), Boolean.parseBoolean(a[3]));
    }

    String save() {
        return span(active) + ";" + span(plannedBoost) + ";" + span(plannedEase) + ";"
                + (nowKind == null ? "" : nowKind + "," + nowAt);
    }

    static BoostTracker load(String s) {
        BoostTracker b = new BoostTracker();
        if (s == null || s.isEmpty()) return b;
        try {
            String[] a = s.split(";", -1);
            b.active = span(a[0]);
            b.plannedBoost = span(a[1]);
            b.plannedEase = span(a[2]);
            if (!a[3].isEmpty()) {
                String[] n = a[3].split(",");
                b.nowKind = n[0];
                b.nowAt = Long.parseLong(n[1]);
            }
        } catch (Exception e) {
            return new BoostTracker();   // older or damaged state
        }
        return b;
    }
}
