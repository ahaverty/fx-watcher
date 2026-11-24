package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class CalmStateTest {

    private static final long MIN = CalmState.MIN;
    private static final long NOW = at(21, 10);        // 21:10

    private static long at(int hour, int minute) {
        Calendar t = Calendar.getInstance();
        t.clear();
        t.set(2026, Calendar.NOVEMBER, 23, hour, minute, 0);
        return t.getTimeInMillis();
    }

    /** Readings every 5 min for `minutes` minutes up to NOW, from `start` by `perMin`. */
    private static List<double[]> series(double start, double perMin, int minutes) {
        List<double[]> out = new ArrayList<>();
        for (int m = 0; m <= minutes; m += 5) {
            out.add(new double[]{NOW - (minutes - m) * MIN, Math.round((start + perMin * m) * 10) / 10.0});
        }
        return out;
    }

    private static CalmState.Input input(List<double[]> readings) {
        CalmState.Input in = new CalmState.Input();
        in.now = NOW;
        in.readings = readings;
        double[] last = readings.get(readings.size() - 1);
        in.glucose = last[1];
        in.glucoseAt = (long) last[0];
        return in;
    }

    private static NudgeRules.Bolus bolus(long agoMin, double carbs) {
        return new NudgeRules.Bolus(NOW - agoMin * MIN, 1.5, carbs, false);
    }

    // ---- steady ----

    @Test
    public void inRangeAndNothingGoingOnIsSteadyWithNoText() {
        CalmState.Result r = CalmState.compute(input(series(6.2, 0.0, 30)));
        assertEquals(CalmState.STEADY, r.state);
        assertEquals("", r.text);
        assertEquals("Steady", r.word);
        assertEquals(CalmState.TEAL, r.color);
    }

    // ---- handled ----

    @Test
    public void boostAndCorrectionWorkingSaysSoWithAnEta() {
        // 12.0 falling 0.05/min for an hour: now 9.0 would be in range, so start higher: 14.0 -> 11.0
        CalmState.Input in = input(series(14.0, -0.05, 60));            // ends at 11.0
        in.boostOn = true;
        in.boluses.add(bolus(50, Double.NaN));                          // a correction, no carbs
        CalmState.Result r = CalmState.compute(in);
        assertEquals(CalmState.HANDLED, r.state);
        assertEquals(CalmState.AMBER, r.color);
        // 11.0 down to 10.0 at 0.05/min is 20 min: 21:30, rounded to a quarter hour = 21:30
        assertEquals("Handled: Boost and correction working, back in range by about 21:30", r.text);
        assertFalse("never the glucose number", r.text.contains("11"));
    }

    @Test
    public void aBolusWithCarbsIsCalledABolusNotACorrection() {
        CalmState.Input in = input(series(14.0, -0.05, 60));
        in.boluses.add(bolus(40, 45));
        assertTrue(CalmState.compute(in).text.startsWith("Handled: bolus working"));
    }

    @Test
    public void aRecentBolusHandlesItOnlyWhileFlatOrFalling() {
        CalmState.Input falling = input(series(13.0, -0.04, 50));
        falling.boluses.add(bolus(60, 30));
        assertEquals(CalmState.HANDLED, CalmState.compute(falling).state);

        CalmState.Input flat = input(series(11.5, 0.0, 50));
        flat.boluses.add(bolus(60, 30));
        CalmState.Result rf = CalmState.compute(flat);
        assertEquals(CalmState.HANDLED, rf.state);
        assertEquals("flat means no ETA", "Handled: bolus working", rf.text);

        CalmState.Input rising = input(series(9.0, 0.06, 45));          // 9.0 -> 11.7, still going up
        rising.boluses.add(bolus(20, 40));
        assertEquals("rising: not handled yet, and nothing nags either", CalmState.STEADY, CalmState.compute(rising).state);
    }

    @Test
    public void aBolusOlderThanTwoHoursNoLongerHandlesIt() {
        CalmState.Input in = input(series(13.0, -0.04, 50));
        in.boluses.add(bolus(130, 30));
        assertEquals(CalmState.STEADY, CalmState.compute(in).state);
    }

    @Test
    public void boostAloneHandlesAHighEvenWithoutABolus() {
        CalmState.Input in = input(series(12.0, 0.0, 30));
        in.boostOn = true;
        CalmState.Result r = CalmState.compute(in);
        assertEquals(CalmState.HANDLED, r.state);
        assertEquals("Handled: Boost working", r.text);
    }

    @Test
    public void inRangeIsNeverHandled() {
        CalmState.Input in = input(series(8.0, 0.0, 30));
        in.boostOn = true;
        in.boluses.add(bolus(30, 20));
        assertEquals(CalmState.STEADY, CalmState.compute(in).state);
    }

    @Test
    public void etaIsRoundedAndOnlyWhenFalling() {
        assertEquals(at(21, 30), CalmState.eta(11.0, -0.05, NOW));
        assertEquals(0, CalmState.eta(11.0, 0.0, NOW));
        assertEquals(0, CalmState.eta(11.0, 0.02, NOW));
        assertEquals("more than 3 h away is not a promise", 0, CalmState.eta(20.0, -0.01, NOW));
    }

    // ---- check ----

    @Test
    public void notLoopingForFifteenMinutesIsACheck() {
        CalmState.Input in = input(series(6.0, 0.0, 30));
        in.autoOffSince = NOW - 14 * MIN;
        assertEquals(CalmState.STEADY, CalmState.compute(in).state);
        in.autoOffSince = NOW - 15 * MIN;
        CalmState.Result r = CalmState.compute(in);
        assertEquals(CalmState.CHECK, r.state);
        assertEquals("CamAPS isn't looping", r.text);
        assertEquals(CalmState.NUDGE_LOOP, r.nudge);
        assertTrue(r.showOpenCamaps);
        assertFalse(r.showYesDone);
        assertEquals(CalmState.CORAL, r.color);
    }

    @Test
    public void risingFromAMealWithNoBolusAsksDidYouBolus() {
        CalmState.Input in = input(series(6.0, 0.08, 45));              // 6.0 -> 9.6 in 45 min
        CalmState.Result r = CalmState.compute(in);
        assertEquals(CalmState.CHECK, r.state);
        assertEquals("Did you bolus?", r.text);
        assertEquals(CalmState.NUDGE_BOLUS, r.nudge);
        assertTrue(r.showYesDone);
        assertTrue(r.showOpenCamaps);
    }

    @Test
    public void aBolusInTheLastTwoHoursOrYesDoneSilencesTheBolusQuestion() {
        CalmState.Input delivered = input(series(6.0, 0.08, 45));
        delivered.boluses.add(bolus(30, 40));
        assertEquals(CalmState.STEADY, CalmState.compute(delivered).state);

        CalmState.Input yes = input(series(6.0, 0.08, 45));
        yes.bolusYesUntil = NOW + MIN;
        assertEquals(CalmState.STEADY, CalmState.compute(yes).state);
    }

    @Test
    public void aSmallOrSlowRiseDoesNotAsk() {
        assertEquals(CalmState.STEADY, CalmState.compute(input(series(6.0, 0.03, 45))).state);   // +1.4
        assertEquals("under 8.0", CalmState.STEADY, CalmState.compute(input(series(4.0, 0.08, 40))).state);
    }

    @Test
    public void aboveTwelveForNinetyMinutesDespiteACorrectionSaysCheckTheSite() {
        CalmState.Input in = input(series(13.5, 0.0, 100));
        in.boluses.add(bolus(70, Double.NaN));
        CalmState.Result r = CalmState.compute(in);
        assertEquals(CalmState.CHECK, r.state);
        assertEquals("Check the site?", r.text);
        assertEquals(CalmState.NUDGE_SITE, r.nudge);
    }

    @Test
    public void theSiteQuestionNeedsNinetyMinutesAndACorrection() {
        CalmState.Input shortHigh = input(series(13.5, 0.0, 80));
        shortHigh.boluses.add(bolus(70, Double.NaN));
        assertFalse(CalmState.NUDGE_SITE.equals(CalmState.compute(shortHigh).nudge));

        CalmState.Input noBolus = input(series(13.5, 0.0, 100));
        assertFalse(CalmState.NUDGE_SITE.equals(CalmState.compute(noBolus).nudge));

        CalmState.Input freshBolus = input(series(13.5, 0.0, 100));
        freshBolus.boluses.add(bolus(20, Double.NaN));          // too soon to judge
        assertFalse(CalmState.NUDGE_SITE.equals(CalmState.compute(freshBolus).nudge));

        CalmState.Input dipped = input(series(13.5, 0.0, 100));
        dipped.readings.get(3)[1] = 11.0;                       // came under 12 once: the stretch restarts
        dipped.boluses.add(bolus(70, Double.NaN));
        assertFalse(CalmState.NUDGE_SITE.equals(CalmState.compute(dipped).nudge));
    }

    @Test
    public void aCheckBeatsHandled() {
        CalmState.Input in = input(series(13.5, 0.0, 100));
        in.boostOn = true;
        in.boluses.add(bolus(70, Double.NaN));
        assertEquals(CalmState.CHECK, CalmState.compute(in).state);
    }

    // ---- low soon and treating win ----

    @Test
    public void lowSoonShowsAndBeatsEveryCheck() {
        CalmState.Input in = input(series(6.0, -0.05, 30));
        in.lowSoon = true;
        in.autoOffSince = NOW - 40 * MIN;
        CalmState.Result r = CalmState.compute(in);
        assertEquals(CalmState.LOW_SOON, r.state);
        assertEquals(CalmState.PURPLE, r.color);
        assertTrue(r.text, r.text.contains("take 10 g"));
    }

    @Test
    public void actuallyLowIsNeverSteady() {
        CalmState.Result r = CalmState.compute(input(series(4.6, -0.05, 30)));   // ends 3.1
        assertEquals(CalmState.LOW_SOON, r.state);
    }

    @Test
    public void treatingBeatsEverythingAndKeepsTheSamePurple() {
        CalmState.Input in = input(series(3.3, 0.02, 20));
        in.treating = true;
        in.lowSoon = true;
        CalmState.Result r = CalmState.compute(in);
        assertEquals(CalmState.TREATING, r.state);
        assertEquals(CalmState.PURPLE, r.color);
    }

    // ---- stale, notification key, json ----

    @Test
    public void staleReadingIsNoDataNotSteadyOrAHigh() {
        CalmState.Input in = input(series(14.0, 0.0, 30));
        in.now = NOW + 20 * MIN;
        in.boostOn = true;
        CalmState.Result r = CalmState.compute(in);
        assertTrue(r.stale);
        assertEquals("No data", r.word);
        assertEquals(CalmState.STEADY, r.state);
    }

    @Test
    public void notificationKeyIgnoresTheEtaSoWatchesAreNotAlertedForIt() {
        CalmState.Input a = input(series(14.0, -0.05, 60));
        a.boostOn = true;
        CalmState.Input b = input(series(14.0, -0.055, 60));
        b.boostOn = true;
        CalmState.Result ra = CalmState.compute(a), rb = CalmState.compute(b);
        assertEquals(ra.key(), rb.key());
    }

    @Test
    public void jsonHasTheStateAndTheGlanceNumberButTheTextHasNone() {
        CalmState.Input in = input(series(6.2, 0.0, 30));
        CalmState.Result r = CalmState.compute(in);
        String j = CalmState.json(r, in.glucose, "flat", in.glucoseAt, NOW + 3 * MIN, NOW);
        assertTrue(j, j.contains("\"state\":\"steady\""));
        assertTrue(j, j.contains("\"word\":\"Steady\""));
        assertTrue(j, j.contains("\"colour\":\"#1FA89A\""));
        assertTrue(j, j.contains("\"glucose\":6.2"));
        assertTrue(j, j.contains("\"trend\":\"flat\""));
        assertTrue(j, j.contains("\"age_min\":3"));
        assertTrue(j, j.contains("\"updated\":" + NOW / 1000));
    }

    @Test
    public void jsonGoesGreyAfterFifteenMinutes() {
        CalmState.Input in = input(series(6.2, 0.0, 30));
        in.now = NOW + 20 * MIN;
        CalmState.Result r = CalmState.compute(in);
        String j = CalmState.json(r, in.glucose, "flat", in.glucoseAt, in.now, in.now);
        assertTrue(j, j.contains("\"word\":\"No data\""));
        assertTrue(j, j.contains("\"colour\":\"#9AA0A6\""));
        assertTrue(j, j.contains("\"age_min\":20"));
    }

    @Test
    public void noTextEverHoldsAnEmDash() {
        List<CalmState.Input> cases = new ArrayList<>();
        CalmState.Input loop = input(series(6.0, 0.0, 30));
        loop.autoOffSince = NOW - 20 * MIN;
        cases.add(loop);
        CalmState.Input boost = input(series(14.0, -0.05, 60));
        boost.boostOn = true;
        cases.add(boost);
        CalmState.Input low = input(series(6.0, -0.05, 30));
        low.lowSoon = true;
        cases.add(low);
        for (CalmState.Input in : cases) {
            String t = CalmState.compute(in).text;
            assertFalse(t, t.contains("—") || t.contains("–"));
        }
    }
}
