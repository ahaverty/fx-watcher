package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class LowWarningTest {

    private static final long MIN = LowWarning.MIN;
    private static final long T0 = 1_000_000_000_000L;
    private static final LowWarning.Params P = LowWarning.Params.DEFAULT;   // balanced: 20 min fit, 20 ahead, < 4.0

    /** Readings every 5 min starting at start, ending at end (inclusive), value = start + rate * minutes. */
    private static List<double[]> ramp(double startValue, double ratePerMin, int minutes) {
        List<double[]> items = new ArrayList<>();
        for (int m = 0; m <= minutes; m += 5) {
            items.add(new double[]{T0 + m * MIN, Math.round((startValue + ratePerMin * m) * 10) / 10.0});
        }
        return items;
    }

    private static long end(List<double[]> items) {
        return (long) items.get(items.size() - 1)[0];
    }

    // ---- the near-low floor: under 4.5 and falling, whatever the insulin ----

    @Test
    public void floorFiresUnderFourPointFiveWhenFallingEvenWithNoInsulin() {
        List<double[]> r = ramp(5.3, -0.06, 15);                       // 5.3 .. 4.4
        LowWarning.Params fast = LowWarning.preset(LowWarning.FAST);
        LowWarning.Projection pr = LowWarning.project(r, end(r), fast);
        assertTrue(LowWarning.floorFall(4.4, pr));
        assertTrue("no insulin on board", LowWarning.fires(4.4, pr, fast, 0.0));
        assertTrue("every level but off", LowWarning.fires(4.4, LowWarning.project(r, end(r), P), P, 0.0));
    }

    @Test
    public void floorStaysQuietWhenFlatHighOrJustSlightlyDown() {
        LowWarning.Params fast = LowWarning.preset(LowWarning.FAST);
        List<double[]> flat = ramp(4.3, 0.0, 15);
        assertFalse(LowWarning.floorFall(4.3, LowWarning.project(flat, end(flat), fast)));
        List<double[]> slight = ramp(4.4, -0.02, 15);
        assertFalse("a drift of 0.02/min is not a fall", LowWarning.floorFall(4.1, LowWarning.project(slight, end(slight), fast)));
        List<double[]> high = ramp(6.2, -0.07, 15);                    // falling but still 5.2
        assertFalse(LowWarning.floorFall(5.2, LowWarning.project(high, end(high), fast)));
        assertFalse(LowWarning.floorFall(4.0, null));
    }

    // ---- the fit ----

    @Test
    public void straightFallGivesItsSlopeAndProjection() {
        List<double[]> r = ramp(8.0, -0.1, 20);                 // 8.0 .. 6.0
        LowWarning.Projection pr = LowWarning.project(r, end(r), P);
        assertNotNull(pr);
        assertEquals(-0.1, pr.slope, 0.01);
        assertEquals(6.0, pr.now, 0.05);
        assertEquals(4.0, pr.projected, 0.1);                   // 20 min ahead at -0.1/min
    }

    @Test
    public void noFitWithoutEnoughOrFreshReadings() {
        assertNull(LowWarning.project(ramp(8, -0.1, 5), T0 + 5 * MIN, P));          // 2 readings
        List<double[]> r = ramp(8, -0.1, 20);
        assertNull(LowWarning.project(r, end(r) + 8 * MIN, P));                      // newest 8 min old
        List<double[]> tight = new ArrayList<>();
        for (int s = 0; s < 3; s++) tight.add(new double[]{T0 + s * 2 * MIN, 6.0});
        assertNull(LowWarning.project(tight, end(tight), P));                        // spans only 4 min
    }

    @Test
    public void onlyTheWindowCounts() {
        List<double[]> r = new ArrayList<>();
        for (int m = 0; m < 40; m += 5) r.add(new double[]{T0 + m * MIN, 12.0 - 0.3 * m});   // old steep fall
        for (int m = 40; m <= 80; m += 5) r.add(new double[]{T0 + m * MIN, 5.0});            // then flat
        LowWarning.Projection pr = LowWarning.project(r, end(r), P);
        assertEquals(0.0, pr.slope, 0.01);
    }

    // ---- when it fires ----

    @Test
    public void projectionBelowFourFires() {
        List<double[]> r = ramp(7.0, -0.12, 20);                // 7.0 .. 4.6 falling 0.12/min -> about 2.2 in 20 min
        LowWarning.Projection pr = LowWarning.project(r, end(r), P);
        assertTrue(LowWarning.fires(4.6, pr, P));
    }

    @Test
    public void gentleFallDoesNotFire() {
        List<double[]> r = ramp(7.0, -0.03, 20);                // 7.0 .. 6.4: projects to 5.8
        assertFalse(LowWarning.fires(6.4, LowWarning.project(r, end(r), P), P));
    }

    @Test
    public void flatLowishDoesNotFire() {
        List<double[]> r = ramp(4.4, 0.0, 20);
        assertFalse(LowWarning.fires(4.4, LowWarning.project(r, end(r), P), P));
    }

    @Test
    public void risingNeverFires() {
        List<double[]> r = ramp(4.0, 0.1, 20);
        assertFalse(LowWarning.fires(6.0, LowWarning.project(r, end(r), P), P));
    }

    @Test
    public void fastFallUnderFiveAndAHalfFiresEvenWhenProjectionRuleIsStricter() {
        LowWarning.Params strict = new LowWarning.Params(20, 10, 3.0, 5.5, 0.10);   // projection alone: 5.0-1.0 = 4.0, not < 3.0
        List<double[]> r = ramp(7.0, -0.1, 20);                                      // ends 5.0, falling 0.1/min
        LowWarning.Projection pr = LowWarning.project(r, end(r), strict);
        assertTrue(pr.projected >= 3.0);
        assertTrue(LowWarning.fastFall(5.0, pr, strict));
        assertTrue(LowWarning.fires(5.0, pr, strict));
        assertFalse("not below 5.5 yet", LowWarning.fastFall(5.8, pr, strict));
    }

    @Test
    public void highGlucoseNeverWarnsEvenIfFallingFast() {
        List<double[]> r = ramp(14.0, -0.4, 20);                // 14 .. 6, falling 0.4/min; project goes negative
        LowWarning.Projection pr = LowWarning.project(r, end(r), P);
        assertTrue(LowWarning.fires(6.0, pr, P));
        assertFalse("above the cap of 7.0", LowWarning.fires(7.4, pr, P));
    }

    @Test
    public void presetsAreOrderedBySensitivity() {
        LowWarning.Params calm = LowWarning.preset(LowWarning.CALM), bal = LowWarning.preset(LowWarning.BALANCED),
                early = LowWarning.preset(LowWarning.EARLY);
        assertNull(LowWarning.preset(LowWarning.OFF));
        assertTrue(calm.projBelow < bal.projBelow);
        assertTrue(early.horizonMin > bal.horizonMin);
        assertEquals(bal.horizonMin, LowWarning.preset("junk").horizonMin);      // unknown means balanced
    }

    // ---- the tracker: one warning, one re-buzz, quiet when treating ----

    private static LowWarning.Projection falling(long at, double now, double slope) {
        return new LowWarning.Projection(now, slope, now + slope * 20, at);
    }

    @Test
    public void warnsOnceAndStaysQuietWhileItKeepsFiring() {
        LowWarning.Tracker t = new LowWarning.Tracker();
        assertEquals(LowWarning.Action.WARN, t.update(T0, 5.0, falling(T0, 5.0, -0.12), P, false));
        assertEquals(LowWarning.Action.NONE, t.update(T0 + 5 * MIN, 4.7, falling(T0, 4.7, -0.12), P, false));
        assertEquals(LowWarning.Action.NONE, t.update(T0 + 10 * MIN - 1, 4.5, falling(T0, 4.5, -0.12), P, false));
    }

    @Test
    public void reBuzzOnlyAfterTenMinutesAndOnlyIfWorseAndOnlyOnce() {
        LowWarning.Tracker t = new LowWarning.Tracker();
        t.update(T0, 5.0, falling(T0, 5.0, -0.12), P, false);                                // projects 2.6
        // 10 min later but not worse (projection 2.7): no re-buzz
        assertEquals(LowWarning.Action.NONE, t.update(T0 + 10 * MIN, 4.8, falling(T0, 4.8, -0.105), P, false));
        // 15 min later, clearly worse (projection 1.6)
        assertEquals(LowWarning.Action.REBUZZ, t.update(T0 + 15 * MIN, 4.4, falling(T0, 4.4, -0.14), P, false));
        // never a second one
        assertEquals(LowWarning.Action.NONE, t.update(T0 + 20 * MIN, 4.0, falling(T0, 4.0, -0.2), P, false));
    }

    @Test
    public void noReBuzzBeforeTenMinutes() {
        LowWarning.Tracker t = new LowWarning.Tracker();
        t.update(T0, 5.0, falling(T0, 5.0, -0.12), P, false);
        assertEquals(LowWarning.Action.NONE, t.update(T0 + 5 * MIN, 4.2, falling(T0, 4.2, -0.3), P, false));
    }

    @Test
    public void treatingSuppressesWarningAndKeepsItQuiet() {
        LowWarning.Tracker t = new LowWarning.Tracker();
        assertEquals(LowWarning.Action.NONE, t.update(T0, 5.0, falling(T0, 5.0, -0.12), P, true));
        // treating ends, still heading low: now it warns
        assertEquals(LowWarning.Action.WARN, t.update(T0 + 5 * MIN, 4.8, falling(T0, 4.8, -0.12), P, false));
    }

    @Test
    public void nothingWhileAlreadyLowAndNoWarningOnTheWayBackUp() {
        LowWarning.Tracker t = new LowWarning.Tracker();
        assertEquals(LowWarning.Action.NONE, t.update(T0, 3.6, falling(T0, 3.6, -0.1), P, false));
        // back over 3.9 but still projected low within 30 min of the low: held back by the cooldown
        assertEquals(LowWarning.Action.NONE, t.update(T0 + 10 * MIN, 4.1, falling(T0, 4.1, -0.1), P, false));
        assertEquals(LowWarning.Action.WARN, t.update(T0 + 31 * MIN, 4.1, falling(T0, 4.1, -0.1), P, false));
    }

    @Test
    public void rearmsAfterFifteenQuietMinutesButNotInsideTheCooldown() {
        LowWarning.Tracker t = new LowWarning.Tracker();
        t.update(T0, 5.0, falling(T0, 5.0, -0.12), P, false);
        long q = T0 + 5 * MIN;                                                       // rule stops firing
        for (int m = 0; m <= 15; m += 5) t.update(q + m * MIN, 6.0, falling(q, 6.0, 0.0), P, false);
        assertFalse(t.active);
        // fires again at +22 min: inside the 30 min cooldown, so still quiet
        assertEquals(LowWarning.Action.NONE, t.update(T0 + 22 * MIN, 5.0, falling(T0, 5.0, -0.12), P, false));
        assertEquals(LowWarning.Action.WARN, t.update(T0 + 31 * MIN, 5.0, falling(T0, 5.0, -0.12), P, false));
    }

    @Test
    public void confirmNeedsConsecutiveReadings() {
        LowWarning.Params two = new LowWarning.Params(20, 20, 4.0, 5.5, 0.10, 2, 99);
        LowWarning.Tracker t = new LowWarning.Tracker();
        assertEquals(LowWarning.Action.NONE, t.update(T0, 5.0, falling(T0, 5.0, -0.12), two, false));
        assertEquals(LowWarning.Action.WARN, t.update(T0 + 5 * MIN, 4.8, falling(T0, 4.8, -0.12), two, false));
        LowWarning.Tracker u = new LowWarning.Tracker();
        u.update(T0, 5.0, falling(T0, 5.0, -0.12), two, false);
        u.update(T0 + 5 * MIN, 6.0, falling(T0, 6.0, 0.0), two, false);              // a blip
        assertEquals(LowWarning.Action.NONE, u.update(T0 + 10 * MIN, 5.0, falling(T0, 5.0, -0.12), two, false));
    }

    @Test
    public void missingFitKeepsState() {
        LowWarning.Tracker t = new LowWarning.Tracker();
        t.update(T0, 5.0, falling(T0, 5.0, -0.12), P, false);
        assertEquals(LowWarning.Action.NONE, t.update(T0 + 5 * MIN, 5.0, null, P, false));
        assertTrue(t.active);
    }

    @Test
    public void trackerSurvivesSaveAndLoad() {
        LowWarning.Tracker t = new LowWarning.Tracker();
        t.update(T0, 5.0, falling(T0, 5.0, -0.12), P, false);
        LowWarning.Tracker u = LowWarning.Tracker.load(t.save());
        assertTrue(u.active);
        assertEquals(t.warnAt, u.warnAt);
        assertEquals(t.warnProjected, u.warnProjected, 0.001);
        assertFalse(LowWarning.Tracker.load("garbage").active);
    }

    // ---- end to end on a synthetic fall ----

    @Test
    public void aFallToThreeGetsWarnedWellAhead() {
        // 6.5 falling 0.1/min from t=0: crosses 3.9 at t=26 min. Feed readings every 5 min.
        LowWarning.Tracker t = new LowWarning.Tracker();
        List<double[]> items = new ArrayList<>();
        long warnedAt = -1;
        for (int m = 0; m <= 30; m += 5) {
            double g = Math.round((6.5 - 0.1 * m) * 10) / 10.0;
            items.add(new double[]{T0 + m * MIN, g});
            LowWarning.Projection pr = LowWarning.project(items, T0 + m * MIN, P);
            if (t.update(T0 + m * MIN, g, pr, P, false) == LowWarning.Action.WARN && warnedAt < 0) warnedAt = m;
        }
        assertTrue("warned at " + warnedAt, warnedAt >= 0);
        assertTrue("at least 15 min before reaching 3.9 (t=26)", 26 - warnedAt >= 15);
    }

    // ---- the journal: did glucose go under 3.9 within 45 min? ----

    private static List<double[]> after(long at, double... values) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) out.add(new double[]{at + (i + 1) * 5 * MIN, values[i]});
        return out;
    }

    @Test
    public void outcomeLowFollowed() {
        LowWarning.Outcome o = LowWarning.outcome(T0, after(T0, 4.6, 4.2, 3.8), T0 + 20 * MIN);
        assertEquals(LowWarning.Outcome.LOW_FOLLOWED, o.kind);
        assertEquals(15, o.minutes);
    }

    @Test
    public void outcomePendingThenNoLow() {
        List<double[]> r = after(T0, 4.8, 4.6, 4.9, 5.4);
        assertEquals(LowWarning.Outcome.PENDING, LowWarning.outcome(T0, r, T0 + 20 * MIN).kind);
        assertEquals(LowWarning.Outcome.NO_LOW, LowWarning.outcome(T0, r, T0 + 46 * MIN).kind);
    }

    @Test
    public void aLowAfterFortyFiveMinutesIsNotTheWarningsLow() {
        List<double[]> r = after(T0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 3.5);   // low at +50
        assertEquals(LowWarning.Outcome.NO_LOW, LowWarning.outcome(T0, r, T0 + 60 * MIN).kind);
    }

    @Test
    public void journalSettlesEntriesAndSurvivesSaveAndLoad() {
        LowWarning.Journal j = new LowWarning.Journal();
        j.add(new LowWarning.Entry(T0, 5.0, 3.4, -0.1, "projection"));
        j.add(new LowWarning.Entry(T0 + 120 * MIN, 5.2, 3.7, -0.09, "fast fall"));
        List<double[]> readings = new ArrayList<>(after(T0, 4.6, 4.1, 3.7));
        List<LowWarning.Entry> done = j.resolve(readings, T0 + 30 * MIN);
        assertEquals(1, done.size());
        assertEquals(LowWarning.Outcome.LOW_FOLLOWED, j.items.get(0).kind);
        assertEquals(15, j.items.get(0).minutes);
        assertEquals(LowWarning.Outcome.PENDING, j.items.get(1).kind);
        LowWarning.Journal k = LowWarning.Journal.load(j.save());
        assertEquals(2, k.items.size());
        assertEquals("fast fall", k.items.get(1).rule);
        assertEquals(LowWarning.Outcome.LOW_FOLLOWED, k.items.get(0).kind);
        assertTrue(k.summary(), k.summary().startsWith("2 warnings kept: 1 went under 3.9, 0 did not, 1 waiting"));
        assertTrue(done.get(0).line("14:05").contains("went under 3.9 15 min later"));
    }

    // ---- fast lows only, with insulin on board, and CamAPS's own words ----

    @Test
    public void fastModeNeedsAFastFallAndInsulinOnBoard() {
        LowWarning.Params fast = LowWarning.preset(LowWarning.FAST);
        List<double[]> r = ramp(7.2, -0.12, 15);                 // 7.2 -> 5.4 in 15 min
        LowWarning.Projection pr = LowWarning.project(r, end(r), fast);
        assertTrue("with 1.0 U on board", LowWarning.fires(5.4, pr, fast, 1.0));
        assertFalse("0.2 U on board: not worth a buzz", LowWarning.fires(5.4, pr, fast, 0.2));
        assertTrue("insulin unknown: do not lose the warning", LowWarning.fires(5.4, pr, fast, Double.NaN));
    }

    @Test
    public void fastModeIgnoresSlowFallsAndHighStarts() {
        LowWarning.Params fast = LowWarning.preset(LowWarning.FAST);
        List<double[]> slow = ramp(6.0, -0.05, 15);              // 5.25: the projection rule is off in this mode
        assertFalse(LowWarning.fires(5.3, LowWarning.project(slow, end(slow), fast), fast, 2.0));
        List<double[]> high = ramp(10.0, -0.15, 15);             // fast, but only down to 7.8
        assertFalse(LowWarning.fires(7.8, LowWarning.project(high, end(high), fast), fast, 2.0));
    }

    @Test
    public void camapsOwnFallingWordsAreRecognised() {
        assertTrue(LowWarning.camapsFalling("Alert: Urgent Low Soon. Glucose is falling quickly"));
        assertTrue(LowWarning.camapsFalling("Glucose falling fast"));
        assertTrue(LowWarning.camapsFalling("Glucose falling very fast"));
        assertTrue(LowWarning.camapsFalling("Falling glucose alert."));
        assertFalse("plain falling is not an alarm", LowWarning.camapsFalling("Glucose falling"));
        assertFalse(LowWarning.camapsFalling("Delivered 1.3 U of insulin."));
        assertFalse(LowWarning.camapsFalling(null));
    }

    @Test
    public void camapsTriggerWarnsOnceWithTheSameDebounceAndSuppression() {
        LowWarning.Tracker t = new LowWarning.Tracker();
        assertEquals(LowWarning.Action.NONE, t.external(T0, 5.2, true));              // treating
        assertEquals(LowWarning.Action.NONE, t.external(T0, 3.5, false));             // already low
        assertEquals(LowWarning.Action.WARN, t.external(T0, 5.2, false));
        assertEquals(LowWarning.Action.NONE, t.external(T0 + 5 * MIN, 5.0, false));   // still active
        // the rule cannot warn again inside the cooldown either
        assertEquals(LowWarning.Action.NONE, t.update(T0 + 6 * MIN, 5.0, falling(T0, 5.0, -0.2), P, false));
    }
}
