package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/** The Treating mode guardrails: who may arm it, how long, when it ends and what it never touches. */
public class TreatingTest {

    private static final long MIN = Treating.MIN;
    private static final long T0 = 1_000_000_000_000L;

    // ---- length: 20 by default, 10 to 30 ----

    @Test
    public void lengthIsClampedToTenToThirty() {
        assertEquals(20, Treating.DEF_MIN);
        assertEquals(10, Treating.clampMinutes(1));
        assertEquals(10, Treating.clampMinutes(-5));
        assertEquals(15, Treating.clampMinutes(15));
        assertEquals(30, Treating.clampMinutes(30));
        assertEquals(30, Treating.clampMinutes(120));
    }

    // ---- ending: time, very low, not rising, no data ----

    @Test
    public void staysOnWhileGlucoseRisesAndTimeRemains() {
        long until = T0 + 20 * MIN;
        assertEquals(Treating.End.NONE, Treating.decide(T0 + 5 * MIN, T0, until, 3.2, 3.2, T0 + 5 * MIN));
        assertEquals(Treating.End.NONE, Treating.decide(T0 + 16 * MIN, T0, until, 3.2, 3.9, T0 + 15 * MIN));
    }

    @Test
    public void endsWhenTimeIsUp() {
        long until = T0 + 20 * MIN;
        assertEquals(Treating.End.TIME_UP, Treating.decide(until, T0, until, 3.2, 5.0, until - MIN));
        assertEquals(Treating.End.TIME_UP, Treating.decide(until + 10 * MIN, T0, until, 3.2, 5.0, until));
    }

    @Test
    public void glucoseUnderTwoPointFiveEndsAtOnceAndEscalates() {
        long until = T0 + 20 * MIN;
        Treating.End e = Treating.decide(T0 + 2 * MIN, T0, until, 3.0, 2.4, T0 + 2 * MIN);
        assertEquals(Treating.End.VERY_LOW, e);
        assertTrue(Treating.escalates(e));
        assertEquals("2.5 itself is not under 2.5", Treating.End.NONE,
                Treating.decide(T0 + 2 * MIN, T0, until, 3.0, 2.5, T0 + 2 * MIN));
    }

    @Test
    public void notHigherAfterFifteenMinutesEndsAndEscalates() {
        long until = T0 + 20 * MIN;
        Treating.End same = Treating.decide(T0 + 15 * MIN, T0, until, 3.2, 3.2, T0 + 14 * MIN);
        Treating.End lower = Treating.decide(T0 + 15 * MIN, T0, until, 3.2, 3.0, T0 + 14 * MIN);
        assertEquals(Treating.End.NOT_RISING, same);
        assertEquals(Treating.End.NOT_RISING, lower);
        assertTrue(Treating.escalates(same));
    }

    @Test
    public void notCheckedBeforeFifteenMinutes() {
        long until = T0 + 20 * MIN;
        assertEquals(Treating.End.NONE, Treating.decide(T0 + 14 * MIN, T0, until, 3.2, 3.0, T0 + 13 * MIN));
    }

    @Test
    public void higherByOneStepCountsAsRising() {
        long until = T0 + 20 * MIN;
        assertEquals(Treating.End.NONE, Treating.decide(T0 + 15 * MIN, T0, until, 3.2, 3.3, T0 + 14 * MIN));
    }

    @Test
    public void noFreshReadingAtFifteenMinutesEndsAndEscalates() {
        long until = T0 + 20 * MIN;
        Treating.End e = Treating.decide(T0 + 15 * MIN, T0, until, 3.2, 4.5, T0 + 2 * MIN);   // last reading 13 min old
        assertEquals(Treating.End.NO_DATA, e);
        assertTrue(Treating.escalates(e));
    }

    @Test
    public void aStaleVeryLowReadingDoesNotEndIt() {
        long until = T0 + 20 * MIN;
        assertEquals(Treating.End.NONE, Treating.decide(T0 + 13 * MIN, T0, until, 3.2, 2.0, T0));   // 13 min old: not trusted
    }

    @Test
    public void onlyTheEarlyEndingsRingTheAlarm() {
        assertFalse(Treating.escalates(Treating.End.NONE));
        assertFalse(Treating.escalates(Treating.End.TIME_UP));
        assertTrue(Treating.escalates(Treating.End.VERY_LOW));
        assertTrue(Treating.escalates(Treating.End.NOT_RISING));
        assertTrue(Treating.escalates(Treating.End.NO_DATA));
    }

    // ---- FX Watcher's own urgent low: a buzz while treating, nothing else is muted ----

    @Test
    public void onlyUrgentLowBuzzesInsteadOfRinging() {
        assertTrue(Treating.mutes(true, "LOW"));
        assertFalse("not while treating is off", Treating.mutes(false, "LOW"));
        for (String code : Arrays.asList("SIGNAL", "STALE", "MISSING", "PUMP", "BATTERY", "TREAT")) {
            assertFalse(code + " must still ring", Treating.mutes(true, code));
        }
    }

    // ---- the volume guard: only when an Urgent low may be sounding ----

    @Test
    public void volumeLiftIsCounteredOnlyWhileLowOrRightAfterACamapsAlert() {
        long now = T0;
        assertTrue("glucose 3.1 just read", Treating.shouldCounter(true, 7, 3.1, now - 2 * MIN, 0, now));
        assertTrue("alert posted 10 s ago", Treating.shouldCounter(true, 7, 6.0, now - 2 * MIN, now - 10_000L, now));
        assertFalse("you turn the volume up at 6.0 with no alert", Treating.shouldCounter(true, 7, 6.0, now - 2 * MIN, 0, now));
        assertFalse("alert 5 min ago", Treating.shouldCounter(true, 7, 6.0, now - MIN, now - 5 * MIN, now));
        assertFalse("not treating", Treating.shouldCounter(false, 7, 3.1, now - MIN, now - 1000L, now));
        assertFalse("volume 0 needs no counter", Treating.shouldCounter(true, 0, 3.1, now - MIN, now - 1000L, now));
        assertFalse("stale low reading", Treating.shouldCounter(true, 7, 3.1, now - 30 * MIN, 0, now));
    }

    // ---- what is put back ----

    @Test
    public void volumeIsRestoredOnlyIfStillWhatWeSet() {
        assertEquals(9, Treating.restoreMedia(0, 0, 9));          // still 0: back to 9
        assertEquals(-1, Treating.restoreMedia(5, 0, 9));         // you changed it meanwhile: leave it
        assertEquals(-1, Treating.restoreMedia(0, -1, 9));        // we never changed it (Quiet event was on)
        assertEquals(-1, Treating.restoreMedia(0, 0, 0));         // it was 0 already
    }

    // ---- nothing arms it by itself ----

    private static File sources() {
        for (String p : new String[]{"src/main/java/studio/alans/fxwatcher", "app/src/main/java/studio/alans/fxwatcher"}) {
            File f = new File(p);
            if (f.isDirectory()) return f;
        }
        throw new AssertionError("source folder not found from " + new File(".").getAbsolutePath());
    }

    @Test
    public void onlyAPersonCanStartIt() throws Exception {
        // start() may be called from a button tap, a notification action and Home Assistant's broadcast.
        // Anything else (Monitor, LowWarnings, Nudges, a timer) would be "automatic".
        TreeSet<String> callers = new TreeSet<>();
        File[] files = sources().listFiles((d, n) -> n.endsWith(".java"));
        for (File f : files) {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (f.getName().equals("Treating.java")) continue;
            if (text.contains("Treating.start(")) callers.add(f.getName());
        }
        assertEquals(new TreeSet<>(Arrays.asList("ActionReceiver.java", "HaReceiver.java", "MainActivity.java")), callers);
    }

    @Test
    public void startIsReachedOnlyFromThoseThreeUserActions() throws Exception {
        List<String> lines = new ArrayList<>();
        for (String name : new String[]{"ActionReceiver.java", "HaReceiver.java", "MainActivity.java"}) {
            for (String l : Files.readAllLines(new File(sources(), name).toPath(), StandardCharsets.UTF_8)) {
                if (l.contains("Treating.start(")) lines.add(name + ": " + l.trim());
            }
        }
        // one call each, with a source label naming the user's action
        assertEquals(lines.toString(), 3, lines.size());
        for (String l : lines) {
            assertTrue(l, l.contains("the notification button") || l.contains("Home Assistant") || l.contains("the app button"));
        }
    }

    @Test
    public void treatingTouchesOnlyTheMediaVolume() throws Exception {
        String text = new String(Files.readAllBytes(new File(sources(), "Treating.java").toPath()), StandardCharsets.UTF_8);
        // strip comments so the explanation of what it avoids does not count
        String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertFalse(code.contains("setRingerMode"));
        assertFalse(code.contains("setInterruptionFilter"));
        assertFalse(code.contains("setStreamMute"));
        assertFalse(code.contains("STREAM_ALARM"));
        assertFalse(code.contains("STREAM_RING"));
        assertFalse(code.contains("STREAM_NOTIFICATION"));
        assertFalse(code.contains("com.camdiab"));
        assertTrue(code.contains("setStreamVolume(AudioManager.STREAM_MUSIC"));
    }

    // ---- the words ----

    @Test
    public void guideSaysWhatStillRingsAndWhatItDoes() {
        String g = Treating.guide(null, 20);
        assertTrue(g, g.contains("Pump alarms"));
        assertTrue(g, g.contains("Urgent low"));
        assertTrue(g, g.contains("never starts itself"));
        assertTrue(g, g.contains("2.5"));
        assertTrue(g, g.contains("15 min"));
        assertTrue(g, g.contains("media volume and nothing else"));
        assertFalse("no em dashes", g.contains("—"));
    }
}
