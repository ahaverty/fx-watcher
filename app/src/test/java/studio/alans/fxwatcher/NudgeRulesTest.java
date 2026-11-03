package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

/** The nudge triggers (NudgeRules), with real cases from Alan's data, Oct-Nov 2026. */
public class NudgeRulesTest {

    private static final ZoneId DUBLIN = ZoneId.of("Europe/Dublin");
    private static final long MIN = 60_000L;

    /** "2026-10-25 19:48" in Irish time. */
    private static long t(String s) {
        return LocalDateTime.parse(s.replace(' ', 'T')).atZone(DUBLIN).toInstant().toEpochMilli();
    }

    private static NudgeRules.GlucoseLog glucose(long now, double... every5minOldestFirst) {
        NudgeRules.GlucoseLog g = new NudgeRules.GlucoseLog();
        int n = every5minOldestFirst.length;
        for (int i = 0; i < n; i++) g.add(now - (n - 1 - i) * 5 * MIN, every5minOldestFirst[i]);
        return g;
    }

    // ── Duplicate bolus ──

    @Test
    public void duplicate_25Oct_sameSnackFourMinutesApart() {
        NudgeRules.BolusLog log = new NudgeRules.BolusLog();
        log.add(t("2026-10-25 19:48"), 2.1, 15);
        NudgeRules.Bolus second = log.add(t("2026-10-25 19:52"), 2.1, 15);
        NudgeRules.Bolus first = NudgeRules.duplicateOf(log, second);
        assertNotNull(first);
        assertEquals("Possible duplicate bolus: 15 g at 19:48 and 19:52",
                NudgeRules.duplicateMessage(first, second, "19:48", "19:52"));
    }

    @Test
    public void duplicate_sameUnits_noCarbsOnTheSecond() {
        // The rule for two real deliveries with these numbers. On 1 Nov the second one wasn't real:
        // BolusTrackerTest shows it never gets this far.
        NudgeRules.BolusLog log = new NudgeRules.BolusLog();
        log.add(t("2026-11-01 17:51"), 1.4, 10);
        NudgeRules.Bolus second = log.add(t("2026-11-01 17:54"), 1.4, Double.NaN);
        NudgeRules.Bolus first = NudgeRules.duplicateOf(log, second);
        assertNotNull(first);
        assertEquals("Possible duplicate bolus: 1.4 U at 17:51 and 17:54",
                NudgeRules.duplicateMessage(first, second, "17:51", "17:54"));
    }

    @Test
    public void duplicate_similarCarbsWithin20Percent() {
        NudgeRules.BolusLog log = new NudgeRules.BolusLog();
        log.add(t("2026-10-25 12:00"), 2.1, 15);
        NudgeRules.Bolus same = log.add(t("2026-10-25 12:05"), 1.6, 12);   // 20% below 15, other units
        NudgeRules.Bolus first = NudgeRules.duplicateOf(log, same);
        assertNotNull(first);
        assertEquals("Possible duplicate bolus: 15 g at 12:00 and 12 g at 12:05",
                NudgeRules.duplicateMessage(first, same, "12:00", "12:05"));
        NudgeRules.BolusLog other = new NudgeRules.BolusLog();
        other.add(t("2026-10-25 12:00"), 2.1, 15);
        assertNull(NudgeRules.duplicateOf(other, other.add(t("2026-10-25 12:05"), 1.5, 11)));
    }

    @Test
    public void duplicate_notForAStarterAndAMain() {
        // 24 Oct: 15 g then 48 g two minutes apart was one meal in two parts.
        NudgeRules.BolusLog log = new NudgeRules.BolusLog();
        log.add(t("2026-10-24 19:13"), 2.1, 15);
        assertNull(NudgeRules.duplicateOf(log, log.add(t("2026-10-24 19:15"), 6.9, 48)));
    }

    @Test
    public void duplicate_tenMinuteWindow() {
        NudgeRules.BolusLog log = new NudgeRules.BolusLog();
        log.add(t("2026-10-25 20:00"), 2.1, 15);
        assertNotNull(NudgeRules.duplicateOf(log, log.add(t("2026-10-25 20:10"), 2.1, 15)));
        NudgeRules.BolusLog later = new NudgeRules.BolusLog();
        later.add(t("2026-10-25 20:36"), 2.1, 15);                         // real: 32 min apart
        assertNull(NudgeRules.duplicateOf(later, later.add(t("2026-10-25 21:08"), 2.1, 15)));
    }

    @Test
    public void bolusLog_survivesSaving() {
        NudgeRules.BolusLog log = new NudgeRules.BolusLog();
        log.add(1000, 2.1, 15).alerted = true;
        log.add(2000, 1.4, Double.NaN);
        NudgeRules.BolusLog back = NudgeRules.BolusLog.load(log.save());
        assertEquals(2, back.items.size());
        assertEquals(15, back.items.get(0).carbs, 0);
        assertTrue(back.items.get(0).alerted);
        assertTrue(Double.isNaN(back.items.get(1).carbs));
        assertEquals(0, NudgeRules.BolusLog.load("damaged").items.size());
        assertNotNull(back.setCarbs(2000, 10));
        assertEquals(10, back.items.get(1).carbs, 0);
        assertNull(back.setCarbs(3000, 10));
    }

    // ── Active insulin ──

    private static NudgeRules.BolusLog evening24Oct() {
        NudgeRules.BolusLog log = new NudgeRules.BolusLog();
        log.add(t("2026-10-24 19:13"), 2.1, 15);
        log.add(t("2026-10-24 19:15"), 6.9, 48);
        log.add(t("2026-10-24 20:09"), 7.1, 50);
        log.add(t("2026-10-24 20:16"), 2.1, 15);
        return log;
    }

    @Test
    public void iob_estimateMatchesTheRealNight() {
        NudgeRules.Iob at22 = NudgeRules.iob(evening24Oct(), -1, 0, t("2026-10-24 22:00"));
        assertEquals(4.3, at22.units, 0.1);
        assertTrue(at22.estimated);
    }

    @Test
    public void iob_freshCamapsReadingWins_oldOneDoesNot() {
        long now = t("2026-10-24 22:30");
        NudgeRules.BolusLog log = evening24Oct();
        NudgeRules.Iob fresh = NudgeRules.iob(log, 3.0, now - 10 * MIN, now);
        assertEquals(3.0, fresh.units, 0.001);
        assertFalse(fresh.estimated);
        NudgeRules.Iob old = NudgeRules.iob(log, 3.0, now - 16 * MIN, now);
        assertTrue(old.estimated);
        assertEquals(2.5, old.units, 0.1);
    }

    @Test
    public void iob_bolusAfterTheReadingIsAdded() {
        long now = t("2026-10-24 22:30");
        NudgeRules.BolusLog log = new NudgeRules.BolusLog();
        log.add(now - 5 * MIN, 2.0, Double.NaN);
        NudgeRules.Iob i = NudgeRules.iob(log, 1.0, now - 10 * MIN, now);
        assertEquals(3.0, i.units, 0.05);
        assertTrue(i.estimated);
    }

    @Test
    public void iob_curveRunsFromAllToNothing() {
        assertEquals(1, NudgeRules.remaining(0), 1e-9);
        assertEquals(0, NudgeRules.remaining(300), 1e-9);
        assertTrue(NudgeRules.remaining(60) < NudgeRules.remaining(30));
    }

    // ── Glucose trend ──

    @Test
    public void trend_comparesWithAbout15MinutesAgo() {
        long now = t("2026-11-01 13:08");
        assertEquals(NudgeRules.Trend.RISING, glucose(now, 10.3, 10.5, 10.7, 11.0).trend(now));
        assertEquals(NudgeRules.Trend.FALLING, glucose(now, 8.5, 8.0, 7.3, 6.8).trend(now));
        assertEquals(NudgeRules.Trend.FLAT, glucose(now, 6.0, 6.0, 6.1, 6.1).trend(now));
        assertEquals(NudgeRules.Trend.UNKNOWN, glucose(now, 6.0, 6.1).trend(now));
        assertEquals(NudgeRules.Trend.UNKNOWN, glucose(now - 13 * MIN, 8, 8, 8, 9).trend(now));
    }

    @Test
    public void glucoseLog_rereadOfTheSameValueIsOneReading_andOldOnesGo() {
        NudgeRules.GlucoseLog g = new NudgeRules.GlucoseLog();
        g.add(0, 5);
        g.add(MIN, 5);
        g.add(5 * MIN, 5);
        assertEquals(2, g.items.size());
        g.add(4 * 60 * MIN, 6);
        assertEquals(1, g.items.size());
        NudgeRules.GlucoseLog back = NudgeRules.GlucoseLog.load(g.save());
        assertEquals(6, back.items.get(0)[1], 0.001);
        assertEquals(0, NudgeRules.GlucoseLog.load("damaged").items.size());
    }

    @Test
    public void glucoseLog_lowInTheLast3Hours() {
        long now = t("2026-11-01 13:00");
        NudgeRules.GlucoseLog g = new NudgeRules.GlucoseLog();
        g.add(now - 170 * MIN, 3.9);
        g.add(now, 11);
        assertTrue(g.anyUnder(4, now - 3 * 60 * MIN));
        assertFalse(g.anyUnder(4, now - 169 * MIN));
        NudgeRules.GlucoseLog exactly4 = new NudgeRules.GlucoseLog();
        exactly4.add(now - 60 * MIN, 4.0);
        assertFalse(exactly4.anyUnder(4, now - 3 * 60 * MIN));
    }

    // ── Boost after a slow meal ──

    @Test
    public void boost_1Nov_over10AndRising60MinAfterTheMeal() {
        long meal = t("2026-11-01 12:08");
        NudgeRules.SlowMeals m = new NudgeRules.SlowMeals();
        m.add(meal, meal);
        long now = meal + 60 * MIN;
        assertNotNull(m.due(now));
        NudgeRules.Trend trend = glucose(now, 10.3, 10.5, 10.7, 11.0).trend(now);
        assertTrue(NudgeRules.boostWanted(11.0, trend, false, false, false));
    }

    @Test
    public void boost_onlyInThe60To90MinuteWindow_oncePerMeal() {
        long meal = t("2026-11-01 12:08");
        NudgeRules.SlowMeals m = new NudgeRules.SlowMeals();
        m.add(meal, meal);
        assertNull(m.due(meal + 59 * MIN));
        assertNotNull(m.due(meal + 90 * MIN));
        assertNull(m.due(meal + 91 * MIN));
        m.due(meal + 60 * MIN)[1] = 1;
        assertNull(m.due(meal + 70 * MIN));
        NudgeRules.SlowMeals back = NudgeRules.SlowMeals.load(m.save());
        assertNull(back.due(meal + 70 * MIN));
    }

    @Test
    public void boost_sameMealFromAddMealAndTheChartCountsOnce() {
        long meal = t("2026-10-25 11:41");
        NudgeRules.SlowMeals m = new NudgeRules.SlowMeals();
        m.add(meal, meal);
        m.add(meal + 2 * MIN, meal + 30 * MIN);
        assertEquals(1, m.items.size());
    }

    @Test
    public void boost_suppressed() {
        assertFalse("Boost already on", NudgeRules.boostWanted(11, NudgeRules.Trend.RISING, true, false, false));
        assertFalse("Ease-off on", NudgeRules.boostWanted(11, NudgeRules.Trend.RISING, false, true, false));
        assertFalse("under 4 lately", NudgeRules.boostWanted(11, NudgeRules.Trend.RISING, false, false, true));
        assertFalse("exactly 10", NudgeRules.boostWanted(10, NudgeRules.Trend.RISING, false, false, false));
        assertFalse("not rising", NudgeRules.boostWanted(11, NudgeRules.Trend.FLAT, false, false, false));
        assertFalse("trend unknown", NudgeRules.boostWanted(11, NudgeRules.Trend.UNKNOWN, false, false, false));
    }

    @Test
    public void alcoholTag_inTheRealNoteOnly() {
        assertTrue(NudgeRules.alcoholTag("Korean restaurant: bulgogi plus Birra Moretti 330ml. #meal #alcohol"));
        assertTrue(NudgeRules.alcoholTag("#Alcohol at the wedding"));
        assertFalse("mentioned, not tagged", NudgeRules.alcoholTag("no alcohol tonight #meal"));
        assertFalse(NudgeRules.alcoholTag("#alcoholfree beer"));
        assertFalse(NudgeRules.alcoholTag(null));
    }

    // ── Bedtime ──

    @Test
    public void bedtime_realNight_24Oct_lowOf2_8At04() {
        long now = t("2026-10-24 22:33");
        NudgeRules.Iob iob = NudgeRules.iob(evening24Oct(), -1, 0, now);
        NudgeRules.Trend trend = glucose(now, 4.8, 4.6, 4.6, 4.6).trend(now);
        assertTrue(NudgeRules.bedtimeWindow(now, DUBLIN));
        assertTrue(NudgeRules.bedtimeWanted(iob.units, 4.6, trend, false));
        assertEquals("About 2.3 U still on board at 4.6. Ease-off for 2 h before sleep?",
                NudgeRules.bedtimeMessage(iob, "4.6"));
        // 22:00 has more on board but is outside the window.
        assertFalse(NudgeRules.bedtimeWindow(t("2026-10-24 22:00"), DUBLIN));
    }

    @Test
    public void bedtime_realNight_25Oct_under7AndFalling() {
        long now = t("2026-10-25 23:48");
        NudgeRules.BolusLog log = new NudgeRules.BolusLog();
        log.add(t("2026-10-25 21:08"), 2.1, 15);
        log.add(t("2026-10-25 22:19"), 2.5, 15);
        log.add(t("2026-10-25 23:29"), 2.1, 12);
        NudgeRules.Iob iob = NudgeRules.iob(log, -1, 0, now);
        NudgeRules.Trend trend = glucose(now, 8.5, 8.0, 7.3, 6.8).trend(now);
        assertEquals(NudgeRules.Trend.FALLING, trend);
        assertTrue(NudgeRules.bedtimeWanted(iob.units, 6.8, trend, false));
    }

    @Test
    public void bedtime_conditions() {
        assertFalse("Ease-off on", NudgeRules.bedtimeWanted(4, 4.6, NudgeRules.Trend.FLAT, true));
        assertFalse("exactly 2 U", NudgeRules.bedtimeWanted(2, 4.6, NudgeRules.Trend.FLAT, false));
        assertFalse("exactly 6, flat", NudgeRules.bedtimeWanted(4, 6, NudgeRules.Trend.FLAT, false));
        assertFalse("under 7, flat", NudgeRules.bedtimeWanted(4, 6.5, NudgeRules.Trend.FLAT, false));
        assertTrue("under 7, falling", NudgeRules.bedtimeWanted(4, 6.5, NudgeRules.Trend.FALLING, false));
        assertFalse("7, falling", NudgeRules.bedtimeWanted(4, 7, NudgeRules.Trend.FALLING, false));
    }

    @Test
    public void bedtime_windowAndOnceANight() {
        for (String yes : Arrays.asList("22:30", "23:59", "00:00", "00:29")) {
            assertTrue(yes, NudgeRules.bedtimeWindow(t("2026-10-28 " + yes), DUBLIN));
        }
        for (String no : Arrays.asList("22:29", "00:30", "12:00")) {
            assertFalse(no, NudgeRules.bedtimeWindow(t("2026-10-28 " + no), DUBLIN));
        }
        assertEquals(NudgeRules.nightKey(t("2026-10-28 22:30"), DUBLIN), NudgeRules.nightKey(t("2026-10-29 00:29"), DUBLIN));
        assertFalse(NudgeRules.nightKey(t("2026-10-28 22:30"), DUBLIN).equals(NudgeRules.nightKey(t("2026-10-29 22:30"), DUBLIN)));
    }

    @Test
    public void bedtime_exactWhenCamapsWasJustRead() {
        assertEquals("4.3 U still on board at 5.2. Ease-off for 2 h before sleep?",
                NudgeRules.bedtimeMessage(new NudgeRules.Iob(4.3, false), "5.2"));
    }

    // ── From Home Assistant, and the note ──

    @Test
    public void haKinds() {
        assertEquals("Walking with insulin on board. Ease-off?", NudgeRules.haMessage(NudgeRules.WALKING));
        assertEquals("Back late. Overnight Ease-off for 6 h?", NudgeRules.haMessage(NudgeRules.LATE_HOME));
        assertNull(NudgeRules.haMessage("anything else"));
    }

    @Test
    public void noteIsTaggedForTheReviews() {
        assertEquals("Back late. Overnight Ease-off for 6 h? #nudge",
                NudgeRules.note(NudgeRules.haMessage(NudgeRules.LATE_HOME)));
    }

    @Test
    public void noDashesInAnyMessage() {
        NudgeRules.Bolus a = new NudgeRules.Bolus(0, 1, 15, false), b = new NudgeRules.Bolus(1, 1, 15, false);
        for (String s : Arrays.asList(NudgeRules.BOOST_MESSAGE, NudgeRules.haMessage(NudgeRules.WALKING),
                NudgeRules.haMessage(NudgeRules.LATE_HOME), NudgeRules.duplicateMessage(a, b, "19:48", "19:52"),
                NudgeRules.bedtimeMessage(new NudgeRules.Iob(3, true), "5.2"))) {
            assertFalse(s, s.contains("—") || s.contains("–"));
        }
    }
}
