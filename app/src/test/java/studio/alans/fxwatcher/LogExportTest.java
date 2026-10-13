package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;

public class LogExportTest {

    private static long at(int month, int day, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(2026, month - 1, day, h, m, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static final String LOG = String.join("\n",
            "13/10 15:15:21  Bolus delivered: 0.2 U",
            "13/10 15:15:00  Bolus calculator: 1.0 g, 0.2 U",
            "  (continued detail)",
            "13/10 12:32:03  Bolus calculator: 46.0 g, 7.1 U",
            "12/10 22:00:00  Old line");

    @Test
    public void lastHoursKeepsRecentLinesOldestFirst() {
        String out = LogExport.lines(LOG, at(10, 13, 15, 30), 1);
        assertEquals("13/10 15:15:00  Bolus calculator: 1.0 g, 0.2 U\n"
                + "  (continued detail)\n"
                + "13/10 15:15:21  Bolus delivered: 0.2 U\n", out);
        assertEquals(3, LogExport.count(out));
    }

    @Test
    public void zeroHoursMeansEverythingOldestFirst() {
        String out = LogExport.lines(LOG, at(10, 13, 15, 30), 0);
        assertEquals(5, LogExport.count(out));
        assertEquals("12/10 22:00:00  Old line", out.split("\n")[0]);
        assertEquals("13/10 15:15:21  Bolus delivered: 0.2 U", out.split("\n")[4]);
    }

    @Test
    public void aWiderWindowReachesBackToTheLunchBolus() {
        String out = LogExport.lines(LOG, at(10, 13, 15, 30), 3.5);
        assertEquals("13/10 12:32:03  Bolus calculator: 46.0 g, 7.1 U", out.split("\n")[0]);
        assertEquals(4, LogExport.count(out));
    }

    @Test
    public void emptyAndNull() {
        assertEquals("", LogExport.lines("", 0, 3));
        assertEquals("", LogExport.lines(null, 0, 3));
        assertEquals(0, LogExport.count(""));
    }

    @Test
    public void headerNamesTheVersionAndPhone() {
        assertEquals("FX Watcher v3.3.0 (build 19) on samsung SM-S918B, Android 15, copied 13/10 15:30",
                LogExport.header("v3.3.0 (build 19)", "samsung SM-S918B", "15", at(10, 13, 15, 30)));
    }

    @Test
    public void januaryLinesSeenInDecemberBelongToTheSameYearAndNewYearsEveToLast() {
        long now = at(1, 1, 0, 30);       // 1 Jan 00:30
        String out = LogExport.lines("01/01 00:10:00  New year\n31/12 23:50:00  Old year", now, 1);
        assertEquals(2, LogExport.count(out));
    }
}
