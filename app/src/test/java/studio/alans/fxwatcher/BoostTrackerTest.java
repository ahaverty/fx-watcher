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
        void noBar(long now) { run(t -> t.noBar(now)); }
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
        assertEquals(10 * MIN, b.end);
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
    public void easeOffNowIsOnFromConfirmEvenIfTheBarIsSeenLater() {
        // 7 Nov 2026: 6 h Ease-off confirmed at 23:36, screen off, bar first seen at 00:00.
        Phone p = new Phone();
        p.start("Ease-off", 360, 0, 0);
        assertEquals(1, p.sent.size());                       // sent at Confirm
        BoostTracker t = BoostTracker.load(p.state);
        assertTrue(t.on("Ease-off", 1 * MIN));                // reminders know straight away
        assertEquals(360 * MIN, t.until("Ease-off", 1 * MIN));
        p.bar("Ease-off", 336 * MIN, 24 * MIN);               // seen later, same span
        BoostTracker.Post e = p.only();
        assertEquals(0, e.start);
        assertTrue(e.startKnown);
        assertEquals(1, p.sent.size());
    }

    @Test
    public void nowWhoseBarNeverShowsIsTakenBack() {
        Phone p = new Phone();
        p.start("Ease-off", 120, 0, 0);
        p.noBar(10_000);                                      // home before the bar: kept
        assertTrue(BoostTracker.load(p.state).on("Ease-off", 2 * MIN));
        p.noBar(6 * MIN);                                     // still no bar after 5 min
        BoostTracker.Post e = p.only();
        assertEquals("cancelled", e.ended);
        assertEquals(e.start, e.end);
        assertFalse(BoostTracker.load(p.state).on("Ease-off", 7 * MIN));
    }

    @Test
    public void nowWithoutALengthStillWaitsForTheBar() {
        Phone p = new Phone();
        p.start("Boost", -1, 0, 0);
        assertEquals(0, p.sent.size());
        p.bar("Boost", 30 * MIN, 1_000);
        assertEquals(0, p.only().start);
    }

    @Test
    public void oldSavedStateStillLoads() {
        BoostTracker t = BoostTracker.load("Ease-off,0,600000,true;;;");
        assertTrue(t.on("Ease-off", 1));
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

    @Test
    public void onUntil_runningPlannedAndOver() {
        long min = 60_000L;
        BoostTracker t = new BoostTracker();
        org.junit.Assert.assertFalse(t.on(BoostTracker.EASE, 0));
        t.bar(BoostTracker.EASE, 120 * min, 0);
        org.junit.Assert.assertTrue(t.on(BoostTracker.EASE, 119 * min));
        org.junit.Assert.assertEquals(120 * min, t.until(BoostTracker.EASE, 0));
        org.junit.Assert.assertFalse(t.on(BoostTracker.BOOST, 0));
        org.junit.Assert.assertFalse("ran out", t.on(BoostTracker.EASE, 120 * min));
        BoostTracker p = new BoostTracker();
        p.start(BoostTracker.BOOST, 60, 10 * min, 0);
        org.junit.Assert.assertFalse("not yet", p.on(BoostTracker.BOOST, 5 * min));
        org.junit.Assert.assertTrue("planned one has started", p.on(BoostTracker.BOOST, 20 * min));
        org.junit.Assert.assertFalse(p.on(BoostTracker.BOOST, 70 * min));
    }
}
