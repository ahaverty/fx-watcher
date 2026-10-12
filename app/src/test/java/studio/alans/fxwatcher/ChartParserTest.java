package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Inflater;

/**
 * CamAPS landscape chart screenshots (S23 Ultra, CamAPS 1.4(192), 12-13 Oct 2026), cropped to
 * the chart view, against what Glooko recorded for the same times. Totals must be within 3%.
 *   chart1  ~2 h, now on screen         chart5  ~6 h, now on screen
 *   chart2  ~1 h (max zoom in)          chart6  ~7 h, scrolled back (12 Oct 11:20-18:20)
 *   chart3  ~48 h (max zoom out)        chart7  ~7 h, scrolled back (11 Oct, a slow meal)
 *   chart4  ~48 h, scrolled back
 */
public class ChartParserTest {

    private static final ZoneId DUBLIN = ZoneId.of("Europe/Dublin");

    private static long at(String s) {
        return LocalDateTime.parse(s.replace(' ', 'T')).atZone(DUBLIN).toInstant().toEpochMilli();
    }

    private static ChartParser.Result parse(int n, String captured) throws Exception {
        try (InputStream in = ChartParserTest.class.getResourceAsStream("/chart/chart" + n + ".png")) {
            int[] wh = new int[2];
            int[] px = png(in, wh);
            return ChartParser.parse(px, wh[0], wh[1], at(captured), DUBLIN);
        }
    }

    /** ARGB pixels of an 8-bit RGB / RGBA, non-interlaced PNG (unit tests have no ImageIO). */
    private static int[] png(InputStream raw, int[] wh) throws Exception {
        DataInputStream in = new DataInputStream(raw);
        in.readLong();                                          // signature
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int w = 0, h = 0, type = 0;
        while (true) {
            int len = in.readInt();
            byte[] t = new byte[4];
            in.readFully(t);
            byte[] d = new byte[len];
            in.readFully(d);
            in.readInt();                                       // crc
            String kind = new String(t, "US-ASCII");
            if (kind.equals("IHDR")) {
                w = ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
                h = ((d[4] & 0xff) << 24) | ((d[5] & 0xff) << 16) | ((d[6] & 0xff) << 8) | (d[7] & 0xff);
                type = d[9];
                if (d[8] != 8 || (type != 2 && type != 6) || d[12] != 0) throw new IllegalArgumentException("unsupported PNG");
            } else if (kind.equals("IDAT")) idat.write(d);
            else if (kind.equals("IEND")) break;
        }
        int bpp = type == 6 ? 4 : 3, stride = w * bpp;
        byte[] data = new byte[(stride + 1) * h];
        Inflater inf = new Inflater();
        inf.setInput(idat.toByteArray());
        int got = 0;
        while (got < data.length && !inf.finished()) got += inf.inflate(data, got, data.length - got);
        inf.end();
        byte[] prev = new byte[stride], cur = new byte[stride];
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int f = data[y * (stride + 1)];
            System.arraycopy(data, y * (stride + 1) + 1, cur, 0, stride);
            for (int i = 0; i < stride; i++) {
                int a = i >= bpp ? cur[i - bpp] & 0xff : 0, b = prev[i] & 0xff, c = i >= bpp ? prev[i - bpp] & 0xff : 0;
                int x = cur[i] & 0xff;
                switch (f) {
                    case 1: x += a; break;
                    case 2: x += b; break;
                    case 3: x += (a + b) / 2; break;
                    case 4: {
                        int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                        x += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                        break;
                    }
                    default: break;
                }
                cur[i] = (byte) x;
            }
            for (int x = 0; x < w; x++) {
                int o = x * bpp;
                px[y * w + x] = 0xff000000 | (cur[o] & 0xff) << 16 | (cur[o + 1] & 0xff) << 8 | (cur[o + 2] & 0xff);
            }
            byte[] t = prev; prev = cur; cur = t;
        }
        wh[0] = w;
        wh[1] = h;
        return px;
    }

    /** Units delivered over [from, to) by the 5-min bins, asserting every bin is there. */
    private static double units(ChartParser.Result r, String from, String to) {
        long a = at(from), b = at(to);
        double u = 0;
        int n = 0;
        for (long[] bin : ChartParser.bins(r)) {
            if (bin[0] >= a && bin[0] < b) { u += bin[1] / 100.0 * ChartParser.BIN_MIN / 60.0; n++; }
        }
        assertEquals("bins " + from + " - " + to, (b - a) / (ChartParser.BIN_MIN * 60_000L), n);
        return u;
    }

    private static void glooko(ChartParser.Result r, String from, String to, double glooko) {
        double u = units(r, from, to);
        assertEquals(from + " - " + to + ": " + u + " U vs Glooko " + glooko, glooko, u, glooko * 0.03);
    }

    /** Highest bin in [from, to): {start ms, rate}. */
    private static double[] peak(ChartParser.Result r, String from, String to) {
        double[] p = {0, -1};
        for (long[] bin : ChartParser.bins(r)) {
            if (bin[0] >= at(from) && bin[0] < at(to) && bin[1] / 100.0 > p[1]) p = new double[]{bin[0], bin[1] / 100.0};
        }
        return p;
    }

    @Test
    public void nowOnScreen() throws Exception {
        ChartParser.Result r = parse(1, "2026-10-12 23:56:24");
        assertEquals(Arrays.asList(22 * 60, 23 * 60, 0), r.labels);
        assertEquals(Arrays.asList(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13)), r.dates);
        assertEquals(Arrays.asList(6, 1), r.yLabels);
        assertTrue("now line at the clock", Math.abs(r.now - at("2026-10-12 23:56:24")) < 60_000L);
        assertTrue("nothing after now", r.right <= at("2026-10-12 23:56:24"));
        for (ChartParser.Step s : r.steps) assertTrue(s.end <= r.right);
        glooko(r, "2026-10-12 22:00", "2026-10-12 23:00", 2.68);
        assertEquals(3.48, steps(r, "2026-10-12 22:17"), 0.03);   // Glooko: 3.48 from 22:15
    }

    /** The step rate at a time. */
    private static double steps(ChartParser.Result r, String t) {
        long x = at(t);
        for (ChartParser.Step s : r.steps) if (s.start <= x && x < s.end) return s.rate;
        fail("no step at " + t);
        return -1;
    }

    @Test
    public void maxZoomIn() throws Exception {
        ChartParser.Result r = parse(2, "2026-10-12 23:57:58");
        assertEquals(Arrays.asList(23 * 60, 0), r.labels);
        assertTrue(Math.abs(r.now - at("2026-10-12 23:57:58")) < 60_000L);
        assertEquals(2235, r.pxPerMin * 60, 5);
        assertEquals(0.82, steps(r, "2026-10-12 23:32"), 0.03);   // same step as chart5 shows
    }

    @Test
    public void midZoomNow() throws Exception {
        ChartParser.Result r = parse(5, "2026-10-13 00:00:54");
        assertEquals(Arrays.asList(19 * 60, 20 * 60, 21 * 60, 22 * 60, 23 * 60, 0), r.labels);
        glooko(r, "2026-10-12 18:00", "2026-10-12 22:20", 2.18);
        glooko(r, "2026-10-12 22:00", "2026-10-12 23:00", 2.68);
    }

    @Test
    public void scrolledBackNoNowLine() throws Exception {
        ChartParser.Result r = parse(6, "2026-10-13 00:04:24");
        assertEquals(-1, r.now);
        assertEquals(Arrays.asList(LocalDate.of(2026, 10, 12)), r.dates);
        glooko(r, "2026-10-12 12:00", "2026-10-12 18:00", 5.27);
        double[] p = peak(r, "2026-10-12 12:00", "2026-10-12 18:00");
        assertEquals(2.56, p[1], 0.02);                             // Glooko: 2.56 at 16:20
        assertEquals(at("2026-10-12 16:20"), p[0], 6 * 60_000L);
    }

    @Test
    public void scrolledBackSlowMeal() throws Exception {
        ChartParser.Result r = parse(7, "2026-10-13 00:05:57");
        assertEquals(Arrays.asList(LocalDate.of(2026, 10, 11)), r.dates);
        glooko(r, "2026-10-11 10:00", "2026-10-11 16:00", 4.79);
        double[] p = peak(r, "2026-10-11 10:00", "2026-10-11 16:00");
        assertEquals(3.48, p[1], 0.02);                             // Glooko: 3.48 at 14:34
        assertEquals(at("2026-10-11 14:34"), p[0], 8 * 60_000L);
    }

    @Test
    public void maxZoomOutIsRefused() throws Exception {
        for (int n : new int[]{3, 4}) {
            try {
                parse(n, "2026-10-12 23:59:00");
                fail("chart" + n + " should be refused");
            } catch (ChartParser.Fail f) {
                assertTrue(f.getMessage(), f.getMessage().startsWith("zoomed out too far"));
            }
        }
    }

    @Test
    public void wrongClockIsRefused() throws Exception {
        // the now line says 23:56; a phone clock 10 min off must not shift the data
        try {
            parse(1, "2026-10-12 23:46:24");
            fail("should be refused");
        } catch (ChartParser.Fail f) {
            assertTrue(f.getMessage(), f.getMessage().startsWith("now line"));
        }
    }

    @Test
    public void wrongDayIsRefused() throws Exception {
        // a scrolled-back view taken "before" the time it shows is impossible
        try {
            parse(6, "2026-10-12 15:00:00");
            fail("should be refused");
        } catch (ChartParser.Fail f) {
            assertTrue(f.getMessage(), f.getMessage().startsWith("future in view"));
        }
    }

    /**
     * Slowly absorbed meals: the stretched carb box. Glooko logs the slow part as equal portions
     * every 30 min; absorption ends 30 min after the last one.
     */
    @Test
    public void slowMeals() throws Exception {
        // 11 Oct: 24 g slow (6 x 4 g, 12:18 ... 14:48)
        List<ChartParser.SlowMeal> m = parse(7, "2026-10-13 00:05:57").slowMeals;
        assertEquals(1, m.size());
        assertEquals(24, m.get(0).grams);
        assertEquals(at("2026-10-11 12:18"), m.get(0).start, 2 * 60_000L);
        assertEquals(at("2026-10-11 15:18"), m.get(0).end, 2 * 60_000L);
        // 12 Oct: "1 g" slow (6 x 0.2 g, 19:37 ... 22:07; entered with the 19:35 bolus), a normal
        // 35 g box drawn over it at 20:55
        m = parse(5, "2026-10-13 00:00:54").slowMeals;
        assertEquals(1, m.size());
        assertEquals(1, m.get(0).grams);
        assertEquals(at("2026-10-12 19:36"), m.get(0).start, 3 * 60_000L);
        assertEquals(at("2026-10-12 22:37"), m.get(0).end, 2 * 60_000L);
        // normal meals only, or the slow box's start cut off by the left edge: nothing
        assertEquals(0, parse(6, "2026-10-13 00:04:24").slowMeals.size());
        assertEquals(0, parse(1, "2026-10-12 23:56:24").slowMeals.size());
        assertEquals(0, parse(2, "2026-10-12 23:57:58").slowMeals.size());
    }

    @Test
    public void mealLedgerSendsOnceUnderTheFirstTime() {
        long t = at("2026-10-11 12:18");
        ChartBasal.MealLedger l = new ChartBasal.MealLedger();
        List<long[]> a = l.take(Arrays.asList(new ChartParser.SlowMeal(t, t + 180 * 60_000L, 24)), 6.2, t);
        assertEquals(1, a.size());
        // read again at another zoom: tick 1 min off, same end -> nothing
        assertEquals(0, l.take(Arrays.asList(new ChartParser.SlowMeal(t + 60_000L, t + 181 * 60_000L, 24)), 17.1, t).size());
        // end moved, coarser zoom -> nothing; finer -> resent under the first time
        assertEquals(0, l.take(Arrays.asList(new ChartParser.SlowMeal(t, t + 200 * 60_000L, 24)), 2.0, t).size());
        List<long[]> b = l.take(Arrays.asList(new ChartParser.SlowMeal(t + 60_000L, t + 200 * 60_000L, 24)), 17.1, t);
        assertEquals(1, b.size());
        assertEquals(t, b.get(0)[0]);
        assertEquals(l.save(), ChartBasal.MealLedger.load(l.save()).save());
        // a different meal
        assertEquals(1, l.take(Arrays.asList(new ChartParser.SlowMeal(t + 60_000L, t + 120 * 60_000L, 10)), 6.2, t).size());
    }

    @Test
    public void ledgerSendsOnlyNewOrFiner() {
        ChartBasal.Ledger l = new ChartBasal.Ledger();
        long now = at("2026-10-12 23:00");
        List<long[]> a = Arrays.asList(new long[]{now - 600_000L, 98}, new long[]{now - 300_000L, 82});
        assertEquals(2, l.take(a, 6.2, now).size());
        assertEquals("same again: nothing", 0, l.take(a, 6.2, now).size());
        List<long[]> changed = Arrays.asList(new long[]{now - 600_000L, 98}, new long[]{now - 300_000L, 90});
        assertEquals("coarser zoom never replaces", 0, l.take(changed, 2.0, now).size());
        assertEquals("finer zoom replaces", 1, l.take(changed, 17.1, now).size());
        ChartBasal.Ledger back = ChartBasal.Ledger.load(l.save());
        assertEquals(l.save(), back.save());
        assertEquals("old bins forgotten", 0, back.take(a, 6.2, now + 9 * 24 * 3600_000L).size());
        assertEquals(0, back.bins.size());
    }
}
