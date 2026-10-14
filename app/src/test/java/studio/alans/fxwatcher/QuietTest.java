package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class QuietTest {

    private static long at(int day, int hour, int minute) {
        Calendar t = Calendar.getInstance();
        t.clear();
        t.set(2026, Calendar.OCTOBER, day, hour, minute, 0);
        return t.getTimeInMillis();
    }

    @Test
    public void endTimeLaterToday() {
        assertEquals(at(14, 21, 30), Quiet.nextAt(at(14, 19, 5), 21, 30));
    }

    @Test
    public void endTimeAfterMidnightIsTomorrow() {
        assertEquals(at(15, 0, 15), Quiet.nextAt(at(14, 22, 40), 0, 15));
        assertEquals(at(15, 22, 40), Quiet.nextAt(at(14, 22, 40), 22, 40));   // "now" means tomorrow
    }

    @Test
    public void putsBackOnlyWhatIsStillAsSet() {
        assertEquals(9, Quiet.restore(0, 0, 9));      // still 0: back to 9
        assertEquals(-1, Quiet.restore(4, 0, 9));     // turned up during the film: leave it
        assertEquals(-1, Quiet.restore(0, 0, 0));     // was 0 already: nothing to do
        assertEquals(-1, Quiet.restore(1, -1, 2));    // we never set it (already on vibrate)
    }

    @Test
    public void onlyUrgentLowStillRings() {
        assertFalse(Quiet.mutes("LOW"));
        assertTrue(Quiet.mutes("SIGNAL"));
        assertTrue(Quiet.mutes("PUMP"));
        assertTrue(Quiet.mutes("MISSING"));
    }
}
