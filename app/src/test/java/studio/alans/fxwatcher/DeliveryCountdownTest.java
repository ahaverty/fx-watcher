package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Deliver tapped (CamAPS's "Delivery will start in n" countdown seen) brings the missed-bolus
 * deadline forward from the 5 minute default to base + 10 s per unit after the countdown starts.
 */
public class DeliveryCountdownTest {

    private static final long MIN = 60_000L;
    private static final long TIMEOUT = 5 * MIN;
    private static final long BASE = 60_000L;

    private static long at(String hms) {
        return MissedBolusTest.at(hms);
    }

    @Test
    public void countdownBringsTheDeadlineForward() {
        MissedBolus t = new MissedBolus();
        t.calculator(46, 7.1, at("12:32:03"), TIMEOUT);
        assertEquals(at("12:37:03"), t.deadline);
        assertEquals(MissedBolus.Event.STARTED, t.countdown(at("12:32:20"), BASE));
        // 12:32:20 + 60 s + 7.1 U x 10 s = 12:34:31
        assertEquals(at("12:34:31"), t.deadline);
        assertTrue(t.counting);
        assertEquals(MissedBolus.Event.NONE, t.tick(at("12:34:30")));
        assertEquals(MissedBolus.Event.ALERT, t.tick(at("12:34:31")));
        assertFalse(t.pending());
    }

    @Test
    public void laterTicksOfTheSameCountdownDoNotMoveIt() {
        MissedBolus t = new MissedBolus();
        t.calculator(30, 4.6, at("14:22:57"), TIMEOUT);
        t.countdown(at("14:23:50"), BASE);
        long deadline = t.deadline;
        assertEquals(MissedBolus.Event.NONE, t.countdown(at("14:23:52"), BASE));
        assertEquals(deadline, t.deadline);
    }

    @Test
    public void deliveryAfterTheCountdownClears() {
        MissedBolus t = new MissedBolus();
        t.calculator(30, 4.6, at("14:22:57"), TIMEOUT);
        t.countdown(at("14:23:50"), BASE);
        assertEquals(MissedBolus.Event.CLEARED, t.delivered(4.6, at("14:24:01")));
        assertFalse(t.counting);
        assertEquals(MissedBolus.Event.NONE, t.tick(at("15:00:00")));
    }

    @Test
    public void tinyBolusFromTheLockTestGivesNoFalseAlarm() {
        // 13 Oct lock tests: 1 g / 0.2 U delivered 16-21 s after the calculator.
        MissedBolus t = new MissedBolus();
        t.calculator(1, 0.2, at("15:15:00"), TIMEOUT);
        t.countdown(at("15:15:02"), BASE);
        assertEquals(MissedBolus.Event.CLEARED, t.delivered(0.2, at("15:15:21")));
        assertEquals(MissedBolus.Event.NONE, t.tick(at("15:30:00")));
    }

    @Test
    public void countdownNeverPostponesTheDefaultDeadline() {
        MissedBolus t = new MissedBolus();
        t.calculator(1, 0.2, at("09:00:00"), 30_000L);       // a very short custom timeout
        t.countdown(at("09:00:10"), BASE);
        assertEquals(at("09:00:30"), t.deadline);
    }

    @Test
    public void retypedAfterTheCountdownMakesTheNextOneNew() {
        MissedBolus t = new MissedBolus();
        t.calculator(30, 4.6, at("10:00:00"), TIMEOUT);
        t.countdown(at("10:00:30"), BASE);
        t.calculator(20, 3.1, at("10:01:00"), TIMEOUT);
        assertFalse(t.counting);
        assertEquals(MissedBolus.Event.STARTED, t.countdown(at("10:01:20"), BASE));
        assertEquals(at("10:01:20") + BASE + 31_000L, t.deadline);
    }

    @Test
    public void countdownWithNoCalculatorReadIsIgnored() {
        MissedBolus t = new MissedBolus();
        assertEquals(MissedBolus.Event.NONE, t.countdown(at("10:00:00"), BASE));
        assertFalse(t.pending());
    }

    @Test
    public void offWhenTheBaseIsZero() {
        MissedBolus t = new MissedBolus();
        t.calculator(30, 4.6, at("10:00:00"), TIMEOUT);
        assertEquals(MissedBolus.Event.NONE, t.countdown(at("10:00:30"), 0));
        assertEquals(at("10:05:00"), t.deadline);
    }

    @Test
    public void stateSurvivesSaveAndLoad() {
        MissedBolus t = new MissedBolus();
        t.calculator(46, 7.1, at("12:32:03"), TIMEOUT);
        t.countdown(at("12:32:20"), BASE);
        MissedBolus u = MissedBolus.load(t.save());
        assertTrue(u.counting);
        assertEquals(t.deadline, u.deadline);
        assertEquals("Deliver was tapped at 12:32 (46 g / 7.1 U) but no delivery was confirmed. Check CamAPS.",
                MissedBolus.countdownMessage(u.carbs, u.units, "12:32"));
    }

    @Test
    public void oldSavedStateWithoutTheFlagStillLoads() {
        MissedBolus u = MissedBolus.load("1000|46.0|7.1|2000|0|0|0|-1.0|0|1000");
        assertTrue(u.pending());
        assertFalse(u.counting);
    }
}
