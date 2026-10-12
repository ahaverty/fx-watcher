package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Boost / Ease-off from the CamAPS screen (BoostTracker). Times are ms from an arbitrary start. */
public class BoostTrackerTest {

    private static final long MIN = 60_000L;

    /** Plays events through save/load like the app, and keeps Nightscout's view (upsert on start+kind). */
    private static final class Phone {
        String state = "";
        final Map<String, BoostTracker.Post> nightscout = new LinkedHashMap<>();
        final List<BoostTracker.Post> sent = new ArrayList<>();

        void run(java.util.function.Function<BoostTracker, List<BoostTracker.Post>> event) {
            BoostTracker t = BoostTracker.load(state);
            for (BoostTracker.Post p : event.apply(t)) {
                sent.add(p);
                nightscout.put(p.kind + "@" + p.start, p);
            }
            state = t.save();
        }

        void start(String kind, int minutes, long plannedAt, long now) { run(t -> t.start(kind, minutes, plannedAt, now)); }
        void bar(String kind, long remaining, long now) { run(t -> t.bar(kind, remaining, now)); }
        void noBar(long now) { run(t -> { t.noBar(now); return new ArrayList<>(); }); }
        void stop(String kind, long now) { run(t -> t.stop(kind, now)); }
        void cancelPlanned(String kind, long now) { run(t -> t.cancelPlanned(kind, now)); }

        BoostTracker.Post only() {
            assertEquals(1, nightscout.size());
            return nightscout.values().iterator().next();
        }
    }

    @Test
    public void boostNowRecordsItsConfirmTimeAndLength() {
        Phone p = new Phone();
        p.start("Boost", 60, 0, 10 * MIN);
        p.bar("Boost", 60 * MIN - 5_000, 10 * MIN + 5_000);
        BoostTracker.Post b = p.only();
        assertEquals(10 * MIN, b.start);
        assertTrue(b.startKnown);
        assertEquals("planned", b.ended);
    }

    @Test
    public void homeDrawnBeforeTheBarIsNotACancel() {
        // The false cancel this fixes: home without the bar for a moment, then the bar again.
        Phone p = new Phone();
        p.start("Boost", 60, 0, 0);
        p.bar("Boost", 60 * MIN, 1_000);
        p.noBar(20 * MIN);
        p.bar("Boost", 40 * MIN, 20 * MIN + 300);
        BoostTracker.Post b = p.only();
        assertEquals("planned", b.ended);
        assertEquals(0, b.start);
    }

    @Test
    public void stopConfirmIsTheCancel() {
        Phone p = new Phone();
        p.start("Ease-off", 120, 0, 0);
        p.bar("Ease-off", 120 * MIN, 2_000);
        p.stop("Ease-off", 30 * MIN);
        BoostTracker.Post e = p.only();
        assertEquals("cancelled", e.ended);
        assertEquals(30 * MIN, e.end);
        p.bar("Boost", 10 * MIN, 31 * MIN);          // something new afterwards is new
        assertEquals(2, p.nightscout.size());
    }

    @Test
    public void runningOutIsNotACancel() {
        Phone p = new Phone();
        p.start("Boost", 10, 0, 0);
        p.bar("Boost", 10 * MIN, 1_000);
        p.noBar(11 * MIN);
        BoostTracker.Post b = p.only();
        assertEquals("planned", b.ended);
        assertEquals(10 * MIN + 1_000, b.end);
    }

    @Test
    public void plannedBoostIsOneRecordEvenIfAnEaseOffIsConfirmedMeanwhile() {
        // The duplicate this fixes: the shared "pending" note was overwritten by another dialog.
        Phone p = new Phone();
        p.start("Boost", 60, 120 * MIN, 0);                   // planned for later
        p.start("Ease-off", 30, 0, 5 * MIN);                  // something else confirmed now
        p.bar("Ease-off", 30 * MIN, 5 * MIN + 2_000);
        p.stop("Ease-off", 10 * MIN);
        p.bar("Boost", 50 * MIN, 130 * MIN);                  // seen 10 min into the planned Boost
        assertEquals(2, p.nightscout.size());
        BoostTracker.Post b = p.nightscout.get("Boost@" + (120 * MIN));
        assertTrue(b.startKnown);
        assertEquals(180 * MIN, b.end);
    }

    @Test
    public void plannedBoostSentAtConfirmAndCancelledBeforeItStarts() {
        Phone p = new Phone();
        p.start("Boost", 30, 60 * MIN, 0);
        assertEquals(1, p.sent.size());
        p.cancelPlanned("Boost", 10 * MIN);
        BoostTracker.Post b = p.only();
        assertEquals("cancelled", b.ended);
        assertEquals(b.start, b.end);
    }

    @Test
    public void barSeenWithoutItsStartIsMarkedStartNotSeen() {
        Phone p = new Phone();
        p.bar("Ease-off", 45 * MIN, 100 * MIN);
        BoostTracker.Post e = p.only();
        assertFalse(e.startKnown);
        assertEquals(100 * MIN, e.start);
    }

    @Test
    public void extendedBoostUpdatesTheSameRecord() {
        Phone p = new Phone();
        p.start("Boost", 30, 0, 0);
        p.bar("Boost", 30 * MIN, 1_000);
        p.bar("Boost", 60 * MIN, 10 * MIN);                   // extended by 30 min
        BoostTracker.Post b = p.only();
        assertEquals(70 * MIN, b.end);
    }

    @Test
    public void stateSurvivesSaveAndLoad() {
        BoostTracker t = new BoostTracker();
        t.start("Boost", 60, 120 * MIN, 0);
        t.start("Ease-off", 30, 0, 5 * MIN);
        t.bar("Ease-off", 30 * MIN, 5 * MIN + 1_000);
        assertEquals(t.save(), BoostTracker.load(t.save()).save());
        assertEquals("", BoostTracker.load("garbage").save().replace(";", ""));
    }
}
