package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GarminPushTest {

    private static final long NOW = 1_795_000_000_000L;

    private static CalmState.Result handled() {
        CalmState.Input in = new CalmState.Input();
        in.now = NOW;
        in.boostOn = true;
        in.glucose = 12.0;
        in.glucoseAt = NOW - 60_000L;
        List<double[]> r = new ArrayList<>();
        for (int m = 30; m >= 0; m -= 5) r.add(new double[]{NOW - m * 60_000L, 12.0});
        in.readings = r;
        return CalmState.compute(in);
    }

    @Test
    public void messageHasTheSameFieldsAsTheJson() {
        CalmState.Result r = handled();
        Map<String, Object> m = CalmState.message(r, 12.04, "flat", NOW - 120_000L, NOW);
        assertEquals("handled", m.get("state"));
        assertEquals("Handled", m.get("word"));
        assertEquals(CalmState.AMBER, m.get("colour"));
        assertEquals(12.0, (Double) m.get("glucose"), 1e-9);
        assertEquals("flat", m.get("trend"));
        assertEquals(2, m.get("age_min"));
        assertEquals(NOW / 1000, m.get("updated"));
        assertTrue(((String) m.get("text")).startsWith("Handled: Boost working"));
    }

    @Test
    public void aStaleReadingIsPushedGrey() {
        CalmState.Input in = new CalmState.Input();
        in.now = NOW;
        in.glucose = 6.0;
        in.glucoseAt = NOW - 20 * 60_000L;
        in.readings = new ArrayList<>();
        CalmState.Result r = CalmState.compute(in);
        Map<String, Object> m = CalmState.message(r, 6.0, "flat", in.glucoseAt, NOW);
        assertEquals("No data", m.get("word"));
        assertEquals(CalmState.GREY, m.get("colour"));
        assertEquals(20, m.get("age_min"));
    }

    @Test
    public void noGlucoseIsMinusOne() {
        CalmState.Input in = new CalmState.Input();
        in.now = NOW;
        in.readings = new ArrayList<>();
        Map<String, Object> m = CalmState.message(CalmState.compute(in), -1, "unknown", 0, NOW);
        assertEquals(-1.0, (Double) m.get("glucose"), 1e-9);
        assertEquals(-1, m.get("age_min"));
    }

    @Test
    public void theAgeAloneDoesNotMakeAMessageNew() {
        CalmState.Result r = handled();
        Map<String, Object> a = CalmState.message(r, 12.0, "flat", NOW - 60_000L, NOW);
        Map<String, Object> b = CalmState.message(r, 12.0, "flat", NOW - 60_000L, NOW + 120_000L);
        assertEquals(GarminPush.key(a), GarminPush.key(b));
        Map<String, Object> c = CalmState.message(r, 11.8, "flat", NOW - 60_000L, NOW);
        assertFalse(GarminPush.key(a).equals(GarminPush.key(c)));
    }

    @Test
    public void sendsOnChangeAndOtherwiseOnlyEveryFourMinutes() {
        long t = 100_000L;
        assertTrue("first ever", GarminPush.due("a", "", t, 0));
        assertFalse("same thing 1 min later", GarminPush.due("a", "a", t + 60_000L, t));
        assertTrue("same thing 4 min later: a heartbeat so the face stays fresh", GarminPush.due("a", "a", t + 240_000L, t));
        assertTrue("changed", GarminPush.due("b", "a", t + 60_000L, t));
        assertFalse("changed twice inside 20 s", GarminPush.due("c", "b", t + 10_000L, t));
    }

    @Test
    public void aSilentRingerNeedsTheDirectBuzz() {
        assertTrue(LowWarnings.needsDirectBuzz(0));    // RINGER_MODE_SILENT
        assertFalse(LowWarnings.needsDirectBuzz(1));   // vibrate: the channel already buzzes
        assertFalse(LowWarnings.needsDirectBuzz(2));   // normal
    }

    @Test
    public void nightWatchAddsSlowFallsAndDropsTheInsulinGate() {
        LowWarning.Params fast = LowWarning.preset(LowWarning.FAST), night = LowWarning.night();
        assertTrue(fast.minIob > 0);
        assertEquals(0.0, night.minIob, 1e-9);
        assertTrue("projection rule is on at night", night.projBelow > 0);
        assertTrue("and off by day", fast.projBelow < 0);
        // a slow fall, 0.05/min from 6.5: projects 20 min ahead to under 3.8 only after it is near 4.8
        List<double[]> r = new ArrayList<>();
        for (int m = 0; m <= 20; m += 5) r.add(new double[]{NOW + m * 60_000L, 5.4 - 0.05 * m});   // 5.4 -> 4.4
        LowWarning.Projection pr = LowWarning.project(r, NOW + 20 * 60_000L, night);
        assertTrue(LowWarning.fires(4.4, pr, night, 0.0));
        assertFalse(LowWarning.fires(4.4, LowWarning.project(r, NOW + 20 * 60_000L, fast), fast, 0.0));
    }
}
