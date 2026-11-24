package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PlanRunsTest {

    private static final long T = 1_700_000_000_000L;

    private static List<CarbFill.Item> plan() {
        return Arrays.asList(new CarbFill.Item(CarbFill.Kind.SLOW, 15, "p"), new CarbFill.Item(CarbFill.Kind.BOOST, 120, "p"),
                new CarbFill.Item(CarbFill.Kind.MEAL, 45, "p"));
    }

    @Test
    public void keysAreShortPlainTokens() {
        assertTrue(PlanRuns.validKey("plan-2026-11-24.v3_a"));
        assertFalse(PlanRuns.validKey(""));
        assertFalse(PlanRuns.validKey(null));
        assertFalse(PlanRuns.validKey("a b"));
        assertFalse(PlanRuns.validKey("a\tb"));
        assertFalse(PlanRuns.validKey("x".repeat(65)));
    }

    @Test
    public void anUnseenKeyHasNoRun() {
        assertNull(PlanRuns.find("", "k1", T));
        assertNull(PlanRuns.find(null, "k1", T));
        assertNull(PlanRuns.find(PlanRuns.start("", "k2", T), "k1", T));
    }

    @Test
    public void aStartedPlanRecordsEachFinishedStepAndCompletesOnTheLast() {
        String s = PlanRuns.start("", "k1", T);
        assertFalse(PlanRuns.find(s, "k1", T).complete);
        s = PlanRuns.stepDone(s, "k1", T + 1000, "SLOW", false);
        s = PlanRuns.stepDone(s, "k1", T + 2000, "BOOST", false);
        PlanRuns.Run r = PlanRuns.find(s, "k1", T + 3000);
        assertEquals(Arrays.asList("SLOW", "BOOST"), r.done);
        assertFalse(r.complete);
        assertEquals(T, r.at);
        List<CarbFill.Item> left = PlanRuns.remaining(plan(), r);
        assertEquals(1, left.size());
        assertEquals(CarbFill.Kind.MEAL, left.get(0).kind);
        s = PlanRuns.stepDone(s, "k1", T + 4000, "MEAL", true);
        assertTrue(PlanRuns.find(s, "k1", T + 5000).complete);
        assertEquals(0, PlanRuns.remaining(plan(), PlanRuns.find(s, "k1", T + 5000)).size());
    }

    @Test
    public void aStepIsNotRecordedTwice() {
        String s = PlanRuns.stepDone(PlanRuns.start("", "k1", T), "k1", T, "SLOW", false);
        s = PlanRuns.stepDone(s, "k1", T, "SLOW", false);
        assertEquals(1, PlanRuns.find(s, "k1", T).done.size());
    }

    @Test
    public void runningItAgainStartsAFreshRecord() {
        String s = PlanRuns.stepDone(PlanRuns.start("", "k1", T), "k1", T, "SLOW", true);
        s = PlanRuns.start(s, "k1", T + 10_000);
        PlanRuns.Run r = PlanRuns.find(s, "k1", T + 10_000);
        assertFalse(r.complete);
        assertEquals(0, r.done.size());
        assertEquals(T + 10_000, r.at);
    }

    @Test
    public void oldRunsAreForgottenAndKeysAreIndependent() {
        String s = PlanRuns.start(PlanRuns.start("", "old", T), "new", T + PlanRuns.TTL_MS + 5);
        assertNull(PlanRuns.find(s, "old", T + PlanRuns.TTL_MS + 5));
        assertNotNull(PlanRuns.find(s, "new", T + PlanRuns.TTL_MS + 5));
    }

    @Test
    public void onlyTheLatestTwentyAreKeptAndJunkIsIgnored() {
        String s = "";
        for (int i = 0; i < 25; i++) s = PlanRuns.start(s, "k" + i, T + i);
        assertNull(PlanRuns.find(s, "k0", T + 30));
        assertNotNull(PlanRuns.find(s, "k24", T + 30));
        assertEquals(0, PlanRuns.parse("garbage\nmore\tjunk\n", T).size());
    }
}
