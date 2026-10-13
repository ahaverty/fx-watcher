package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Missed bolus check (MissedBolus), replaying FX Watcher log lines. The 13 Oct 2026 sequences are
 * copied from the app's log: 12:32, 46 g / 7.1 U never delivered (glucose 15.9 two hours later);
 * 14:22-14:24, 46 g, 23 g, 30 g, then "Delivered 4.6 U" for 30 g.
 */
public class MissedBolusTest {

    private static final long MIN = 60_000L;
    private static final long TIMEOUT = 5 * MIN;
    private static final Pattern LINE = Pattern.compile("(?:\\d\\d/\\d\\d )?(\\d\\d):(\\d\\d):(\\d\\d)\\s+(.*)");
    private static final Pattern CALC = Pattern.compile("Bolus calculator: ([\\d.]+) g, ([\\d.]+) U.*");
    private static final Pattern DELIVERED = Pattern.compile("Bolus delivered: ([\\d.]+) U.*");

    static long at(String hms) {
        String[] a = hms.split(":");
        return (Long.parseLong(a[0]) * 3600 + Long.parseLong(a[1]) * 60 + (a.length > 2 ? Long.parseLong(a[2]) : 0)) * 1000L;
    }

    /** Runs log lines through a save/load each time, as the app does, and ticks every second. */
    private static final class Replay {
        String state = "";
        final List<String> events = new ArrayList<>();

        MissedBolus t() { return MissedBolus.load(state); }

        void run(String... lines) {
            for (String l : lines) {
                Matcher m = LINE.matcher(l.trim());
                assertTrue(l, m.matches());
                long now = at(m.group(1) + ":" + m.group(2) + ":" + m.group(3));
                String msg = m.group(4);
                MissedBolus t = t();
                MissedBolus.Event e = MissedBolus.Event.NONE;
                Matcher c = CALC.matcher(msg), d = DELIVERED.matcher(msg);
                if (c.matches()) e = t.calculator(Double.parseDouble(c.group(1)), Double.parseDouble(c.group(2)), now, TIMEOUT);
                else if (d.matches()) e = t.delivered(Double.parseDouble(d.group(1)), now);
                record(e, now);
                state = t.save();
            }
        }

        /** The scheduled alarm, checked every second from..to (it really fires once, at the deadline). */
        void tick(String from, String to) {
            for (long now = at(from); now <= at(to); now += 1000L) {
                MissedBolus t = t();
                record(t.tick(now), now);
                state = t.save();
            }
        }

        void dismiss() {
            MissedBolus t = t();
            events.add(t.dismiss().name());
            state = t.save();
        }

        private void record(MissedBolus.Event e, long now) {
            if (e == MissedBolus.Event.NONE) return;
            long s = now / 1000;
            events.add(String.format("%02d:%02d:%02d %s", s / 3600, s / 60 % 60, s % 60, e));
        }
    }

    // 12:32: entered, never delivered (FX Watcher's log, 13 Oct 2026, verbatim).
    private static final String[] MISSED_1232 = {
            "13/10 12:32:00  Bolus calculator: 0.0 g, 0.0 U",
            "13/10 12:32:01  Bolus calculator: 38.0 g, 5.9 U",
            "13/10 12:32:02  Bolus calculator: 39.0 g, 6.0 U",
            "13/10 12:32:02  Bolus calculator: 41.0 g, 6.3 U",
            "13/10 12:32:02  Bolus calculator: 42.0 g, 6.4 U",
            "13/10 12:32:02  Bolus calculator: 42.0 g, 6.5 U",
            "13/10 12:32:02  Bolus calculator: 43.0 g, 6.6 U",
            "13/10 12:32:03  Bolus calculator: 44.0 g, 6.7 U",
            "13/10 12:32:03  Bolus calculator: 45.0 g, 6.9 U",
            "13/10 12:32:03  Bolus calculator: 45.0 g, 7.0 U",
            "13/10 12:32:03  Bolus calculator: 46.0 g, 7.1 U",
    };

    // 14:22-14:24: retyped twice, 4.6 U for 30 g delivered (verbatim).
    private static final String[] DELIVERED_1422 = {
            "13/10 14:22:40  Bolus calculator: 0.0 g, 0.0 U",
            "13/10 14:22:45  Bolus calculator: 46.0 g, 7.1 U",
            "13/10 14:22:52  Bolus calculator: 23.0 g, 3.5 U",
            "13/10 14:22:57  Bolus calculator: 30.0 g, 4.6 U",
            "13/10 14:24:01  CamAPS: Information: Delivered 4.6 U of insulin.",
            "13/10 14:24:01  Bolus delivered: 4.6 U, 30.0 g (camaps-notification)",
            "13/10 14:24:01  Nightscout: queued Bolus",
    };

    @Test
    public void missedBolusAt1232AlertsBy1237() {
        Replay r = new Replay();
        r.run(MISSED_1232);
        r.tick("12:32:03", "13:30:00");
        assertEquals(Arrays.asList("12:32:01 STARTED", "12:37:03 ALERT"), r.events);
        MissedBolus t = r.t();
        assertEquals("Bolus not delivered? Calculator showed 46 g / 7.1 U at 12:32, no delivery seen.",
                MissedBolus.message(t.carbs, t.units, "12:32"));
        assertEquals(46.0, t.carbs, 0);
        assertEquals(7.1, t.units, 0);
    }

    @Test
    public void deliveredAt1424ClearsWithoutAlert() {
        Replay r = new Replay();
        r.run(DELIVERED_1422);
        r.tick("14:24:01", "15:30:00");
        assertEquals(Arrays.asList("14:22:45 STARTED", "14:24:01 CLEARED"), r.events);
    }

    @Test
    public void calculatorStillShowingRightAfterTheDeliveryIsThatBolus() {
        Replay r = new Replay();
        r.run(DELIVERED_1422);
        r.run("14:24:05  Bolus calculator: 0.0 g, 0.0 U",
              "14:24:20  Bolus calculator: 30.0 g, 4.6 U");
        r.tick("14:24:20", "15:00:00");
        assertEquals(Arrays.asList("14:22:45 STARTED", "14:24:01 CLEARED"), r.events);
    }

    @Test
    public void bothSequencesInOneDay() {
        Replay r = new Replay();
        r.run(MISSED_1232);
        r.tick("12:32:03", "14:22:00");
        r.run(DELIVERED_1422);
        r.tick("14:24:01", "15:30:00");
        assertEquals(Arrays.asList("12:32:01 STARTED", "12:37:03 ALERT", "14:22:45 STARTED", "14:24:01 CLEARED"), r.events);
    }

    @Test
    public void lateDeliveryJustBeforeDeadlineClears() {
        Replay r = new Replay();
        r.run("12:32:06  Bolus calculator: 46.0 g, 7.1 U",
              "12:37:00  Bolus delivered: 7.1 U, 46.0 g (camaps-notification)");
        r.tick("12:37:00", "13:00:00");
        assertEquals(Arrays.asList("12:32:06 STARTED", "12:37:00 CLEARED"), r.events);
    }

    @Test
    public void typingRestartsTheTimerFromTheLatestValue() {
        Replay r = new Replay();
        r.run("12:32:06  Bolus calculator: 20.0 g, 3.1 U",
              "12:35:00  Bolus calculator: 46.0 g, 7.1 U");
        r.tick("12:35:00", "12:45:00");
        assertEquals(Arrays.asList("12:32:06 STARTED", "12:40:00 ALERT"), r.events);
    }

    @Test
    public void zeroNeverCancels() {
        Replay r = new Replay();
        r.run("12:32:06  Bolus calculator: 46.0 g, 7.1 U",
              "12:32:30  Bolus calculator: 0.0 g, 0.0 U",
              "12:34:00  Bolus calculator: 0.0 g, 0.0 U");
        r.tick("12:34:00", "12:40:00");
        assertEquals(Arrays.asList("12:32:06 STARTED", "12:37:06 ALERT"), r.events);
    }

    @Test
    public void unitsOnlyCorrectionCounts() {
        Replay r = new Replay();
        r.run("09:00:00  Bolus calculator: 0.0 g, 1.2 U");
        r.tick("09:00:00", "09:10:00");
        assertEquals(Arrays.asList("09:00:00 STARTED", "09:05:00 ALERT"), r.events);
    }

    @Test
    public void dismissedAfterAlertNeverRepeats() {
        Replay r = new Replay();
        r.run(MISSED_1232);
        r.tick("12:32:03", "12:38:00");
        r.dismiss();
        r.tick("12:38:00", "14:00:00");
        assertEquals(Arrays.asList("12:32:01 STARTED", "12:37:03 ALERT", "DISMISSED"), r.events);
    }

    @Test
    public void dismissedBeforeTheDeadlineDoesNotAlert() {
        Replay r = new Replay();
        r.run("12:32:06  Bolus calculator: 46.0 g, 7.1 U");
        r.dismiss();
        r.tick("12:32:10", "13:00:00");
        assertEquals(Arrays.asList("12:32:06 STARTED", "DISMISSED"), r.events);
    }

    @Test
    public void calculatorReopenedWithTheDeliveredBolusIsNotNew() {
        Replay r = new Replay();
        r.run(DELIVERED_1422);
        r.run("14:40:00  Bolus calculator: 30.0 g, 4.6 U");
        r.tick("14:40:00", "15:00:00");
        assertEquals(Arrays.asList("14:22:45 STARTED", "14:24:01 CLEARED"), r.events);
    }

    @Test
    public void aSecondMealLaterIsNew() {
        Replay r = new Replay();
        r.run(DELIVERED_1422);
        r.run("18:00:00  Bolus calculator: 60.0 g, 9.2 U");
        r.tick("18:00:00", "18:10:00");
        assertEquals(Arrays.asList("14:22:45 STARTED", "14:24:01 CLEARED", "18:00:00 STARTED", "18:05:00 ALERT"), r.events);
    }

    @Test
    public void offWhenTimeoutIsZero() {
        MissedBolus t = new MissedBolus();
        assertEquals(MissedBolus.Event.NONE, t.calculator(46, 7.1, at("12:32:06"), 0));
        assertFalse(t.pending());
        assertEquals(MissedBolus.Event.NONE, t.tick(at("13:00:00")));
    }

    @Test
    public void damagedStateStartsAfresh() {
        assertFalse(MissedBolus.load("garbage|x").pending());
        assertFalse(MissedBolus.load("").pending());
    }
}
