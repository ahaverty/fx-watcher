package studio.alans.fxwatcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** When bedtime volume watches for the phone being put down again to raise it again. */
public class BedtimeTest {

    @Test
    public void raisesAgainOnlyAfterSettingItAtNight() {
        // 12-13 Oct 2026: set at 22:30, turned down to 2 for YouTube at 00:14 -> watch again
        assertTrue(Bedtime.watching("applied", false, true));
        assertFalse("still at the bedtime level", Bedtime.watching("applied", false, false));
        assertFalse("morning: leave it for the restore", Bedtime.watching("applied", true, true));
        assertFalse("not set yet: the armed path handles it", Bedtime.watching("armed", false, true));
        assertFalse("'not tonight'", Bedtime.watching("skipped", false, true));
        assertFalse("done for the night", Bedtime.watching("done", false, true));
    }
}
