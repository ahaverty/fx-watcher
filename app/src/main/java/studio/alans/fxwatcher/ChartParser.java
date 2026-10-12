package studio.alans.fxwatcher;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads CamAPS FX's landscape chart (the "insulin infusion (U/h)" graph) from a screenshot of
 * the chart view: the blue auto-mode basal step line, as rate steps with real times.
 *
 * DETERMINISTIC, NO GUESSING. The chart is one canvas with nothing in the accessibility tree,
 * so this works on pixels, and everything is measured from the image (nothing is fixed to one
 * phone's screen size):
 *   y axis   the long ticks and the numbers beside them (read by glyph templates) give U/h
 *            per pixel; the axis must start at 0.
 *   time     the hour labels under the axis ("22:00", read by templates) give minutes per
 *            pixel by a straight-line fit; the date line ("2026-10-12 - 2026-10-13") gives
 *            the days. The dashed "now" line, when on screen, must agree with the clock.
 *   basal    flats of the blue line give a rate directly; risers give the rates on both
 *            sides (a riser runs from one level's line to the next), which covers steps too
 *            short to show a flat. The dotted profile-basal line is found and ignored.
 * Anything that can't be verified throws {@link Fail} and nothing is used: labels unreadable,
 * uneven, off the fit, a date line that disagrees, a now line away from the clock, a clock
 * change in view, or zoomed out so far that a 10-min step is under 6 line widths (there,
 * glucose dots hide too much and it read 20-30% low against Glooko).
 *
 * Checked against Glooko, 7 screenshots (test fixtures): within 2% of the units delivered over
 * 1-24 h windows and the same peaks.
 *
 * Pure Java on ARGB pixels, so the unit tests run it on the fixture PNGs.
 */
final class ChartParser {

    private ChartParser() { }

    static final double MAX_GAP_MIN = 8.0;  // longest hidden stretch bridged (shorter than a CamAPS cycle, ~10 min)
    static final int BIN_MIN = 5;
    private static final int GW = 20, GH = 24;
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d\\d)-(\\d\\d)");

    static final class Fail extends Exception {
        Fail(String m) { super(m); }
    }

    /** A basal rate from start to end (ms). */
    static final class Step {
        final long start, end;
        final double rate;
        Step(long start, long end, double rate) { this.start = start; this.end = end; this.rate = rate; }
    }

    static final class Result {
        final List<Step> steps = new ArrayList<>();
        double pxPerMin;
        long left, right;          // ms of the plot's left edge and of the last usable time
        long now = -1;             // ms of the now line, -1 if not on screen
        final List<Integer> labels = new ArrayList<>();   // minutes of day, left to right
        final List<LocalDate> dates = new ArrayList<>();
        final List<Integer> yLabels = new ArrayList<>();
    }

    // ── pixels ───────────────────────────────────────────────────────────────────

    private static final class Img {
        final int[] px;
        final int w, h;
        Img(int[] px, int w, int h) { this.px = px; this.w = w; this.h = h; }
        int r(int x, int y) { return (px[y * w + x] >> 16) & 0xff; }
        int g(int x, int y) { return (px[y * w + x] >> 8) & 0xff; }
        int b(int x, int y) { return px[y * w + x] & 0xff; }
        int sum(int x, int y) { int c = px[y * w + x]; return ((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff); }
        boolean dark(int x, int y) { return sum(x, y) < 200; }
        boolean ink(int x, int y) { return sum(x, y) < 450; }
        boolean blue(int x, int y) {
            int c = px[y * w + x], r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
            return b > 200 && r < 90 && g > 100 && g < 180;
        }
        boolean gridish(int x, int y) {
            int c = px[y * w + x];
            return Math.max(Math.max((c >> 16) & 0xff, (c >> 8) & 0xff), c & 0xff) < 200 && !blue(x, y);
        }
    }

    // ── glyphs ───────────────────────────────────────────────────────────────────

    private static final List<Object[]> TEMPLATES = new ArrayList<>();
    static {
        for (String[] t : ChartGlyphs.TEMPLATES) {
            boolean[] bits = new boolean[GW * GH];
            for (int i = 0; i < t[1].length(); i++) {
                int v = Character.digit(t[1].charAt(i), 16);
                for (int k = 0; k < 4; k++) bits[i * 4 + k] = (v & (8 >> k)) != 0;
            }
            TEMPLATES.add(new Object[]{t[0].charAt(0), bits});
        }
    }

    /** Column runs [a, b) with ink in rows [y0, y1). */
    private static List<int[]> inkRuns(Img I, int x0, int x1, int y0, int y1) {
        List<int[]> out = new ArrayList<>();
        int cur = -1;
        for (int x = x0; x < x1; x++) {
            boolean has = false;
            for (int y = y0; y < y1 && !has; y++) has = I.ink(x, y);
            if (has && cur < 0) cur = x;
            if (!has && cur >= 0) { out.add(new int[]{cur, x}); cur = -1; }
        }
        if (cur >= 0) out.add(new int[]{cur, x1});
        return out;
    }

    /** Glyph columns [a, b), band rows [y0, y1): GH x GW cells scaled by the band height, left aligned. */
    static boolean[] norm(Img I, int a, int b, int y0, int y1) {
        double s = (y1 - y0) / (double) GH;
        boolean[] bits = new boolean[GW * GH];
        for (int r = 0; r < GH; r++) {
            int y = (int) (y0 + (r + 0.5) * s);
            for (int c = 0; c < GW; c++) {
                int x = (int) (a + (c + 0.5) * s);
                bits[r * GW + c] = x < b && y < y1 && I.ink(x, y);
            }
        }
        return bits;
    }

    /** Ink runs grouped into words: a gap wider than gap x band height splits. */
    private static List<List<int[]>> words(Img I, int x0, int x1, int y0, int y1, double gap) {
        int H = y1 - y0;
        List<List<int[]>> W = new ArrayList<>();
        for (int[] r : inkRuns(I, x0, x1, y0, y1)) {
            if (!W.isEmpty()) {
                List<int[]> last = W.get(W.size() - 1);
                if (r[0] - last.get(last.size() - 1)[1] <= H * gap) { last.add(r); continue; }
            }
            List<int[]> n = new ArrayList<>();
            n.add(r);
            W.add(n);
        }
        return W;
    }

    /** The word's text, or null if any glyph is unlike every template. */
    private static String read(Img I, List<int[]> word, int y0, int y1) {
        StringBuilder s = new StringBuilder();
        for (int[] g : word) {
            boolean[] v = norm(I, g[0], g[1], y0, y1);
            int best = Integer.MAX_VALUE;
            char ch = '?';
            for (Object[] t : TEMPLATES) {
                boolean[] tb = (boolean[]) t[1];
                int d = 0;
                for (int i = 0; i < v.length; i++) if (v[i] != tb[i]) d++;
                if (d < best) { best = d; ch = (Character) t[0]; }
            }
            if (best > v.length * 0.15) return null;
            s.append(ch);
        }
        return s.toString();
    }

    /** Row bands [y0, y1) with ink, at least 8 rows tall. */
    private static List<int[]> bands(Img I, int x0, int x1, int y0, int y1) {
        List<int[]> B = new ArrayList<>();
        for (int y = y0; y < y1; y++) {
            boolean has = false;
            for (int x = x0; x < x1 && !has; x++) has = I.ink(x, y);
            if (!has) continue;
            if (!B.isEmpty() && y - B.get(B.size() - 1)[1] <= 1) B.get(B.size() - 1)[1] = y;
            else B.add(new int[]{y, y});
        }
        List<int[]> out = new ArrayList<>();
        for (int[] b : B) if (b[1] - b[0] >= 7) out.add(new int[]{b[0], b[1] + 1});
        return out;
    }

    private static boolean digits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    // ── parse ────────────────────────────────────────────────────────────────────

    /**
     * @param px        the chart view's pixels, ARGB, row by row
     * @param captureMs when the screenshot was taken
     */
    static Result parse(int[] px, int w, int h, long captureMs, ZoneId zone) throws Fail {
        Img I = new Img(px, w, h);
        Result res = new Result();

        // plot box: the left axis is the longest dark column in the left fifth
        int lx = 0, bestN = -1;
        for (int x = 0; x < w / 5; x++) {
            int n = 0;
            for (int y = h / 5; y < h * 9 / 10; y++) if (I.dark(x, y)) n++;
            if (n > bestN) { bestN = n; lx = x; }
        }
        int run = 0, top = 0, bot = 0, cur = -1;
        for (int y = 0; y < h; y++) {
            if (I.dark(lx, y)) {
                if (cur < 0) cur = y;
                if (y - cur + 1 > run) { run = y - cur + 1; top = cur; bot = y; }
            } else cur = -1;
        }
        if (run < h * 0.3) throw new Fail("no axis");
        int rx = lx;
        while (rx + 1 < w) {
            boolean more = false;
            for (int k = 1; k <= 3 && !more; k++) {
                int x = Math.min(w - 1, rx + k);
                for (int y = bot - 1; y <= bot + 1 && !more; y++) {
                    if (y >= 0 && y < h && (I.dark(x, y) || I.blue(x, y))) more = true;
                }
            }
            if (!more) break;
            rx++;
        }
        if (rx - lx < w * 0.5) throw new Fail("no bottom axis");

        // y axis: long ticks and their labels
        int[] tl = new int[bot + 1];
        int mx = 0;
        for (int y = top; y <= bot; y++) {
            int n = 0;
            while (lx - 1 - n >= 0 && I.dark(lx - 1 - n, y)) n++;
            tl[y] = n;
            mx = Math.max(mx, n);
        }
        if (mx < 6) throw new Fail("no ticks");
        List<int[]> tg = new ArrayList<>();
        for (int y = top; y <= bot; y++) {
            if (tl[y] < mx * 0.7) continue;
            if (!tg.isEmpty() && y - tg.get(tg.size() - 1)[1] <= 2) tg.get(tg.size() - 1)[1] = y;
            else tg.add(new int[]{y, y});
        }
        List<Double> maj = new ArrayList<>();
        for (int[] t : tg) maj.add((t[0] + t[1]) / 2.0);
        if (maj.size() < 2) throw new Fail("too few major ticks");
        double sp = (maj.get(maj.size() - 1) - maj.get(0)) / (maj.size() - 1);
        List<double[]> ylab = new ArrayList<>();
        for (double yc : maj) {
            int y0 = Math.max(0, (int) (yc - sp * 0.45)), y1 = Math.min(h, (int) (yc + sp * 0.45));
            List<int[]> B = bands(I, 0, lx - mx - 1, y0, y1);
            if (B.size() != 1) continue;
            List<List<int[]>> W = words(I, 0, lx - mx - 1, B.get(0)[0], B.get(0)[1], 1.0);
            if (W.size() != 1) continue;
            String s = read(I, W.get(0), B.get(0)[0], B.get(0)[1]);
            if (s != null && digits(s)) ylab.add(new double[]{yc, Integer.parseInt(s)});
        }
        if (ylab.size() < 2) throw new Fail("y labels unreadable");
        double ya = ylab.get(0)[0], va = ylab.get(0)[1];
        double yb = ylab.get(ylab.size() - 1)[0], vb = ylab.get(ylab.size() - 1)[1];
        double upp = (va - vb) / (yb - ya);
        if (upp <= 0) throw new Fail("y labels out of order");
        for (double[] l : ylab) {
            if (Math.abs(vb + (yb - l[0]) * upp - l[1]) > 0.05) throw new Fail("y labels not linear");
            res.yLabels.add((int) l[1]);
        }
        if (Math.abs(vb + (yb - bot) * upp) > 0.1) throw new Fail("y axis does not start at 0");

        // x labels and the date line: the first two text bands under the axis
        int tx = lx + w / 50;                       // clear of the Dexcom logo under the axis
        List<int[]> B = bands(I, tx, rx, bot + 3, h);
        if (B.size() < 2) throw new Fail("no label/date bands");
        int l0 = B.get(0)[0], l1 = B.get(0)[1], d0 = B.get(1)[0], d1 = B.get(1)[1];
        List<double[]> labs = new ArrayList<>();
        for (List<int[]> W : words(I, tx, rx, l0, l1, 1.0)) {
            if (W.size() != 5) continue;
            String s = read(I, W, l0, l1);
            if (s == null || s.length() != 5 || s.charAt(2) != ':' || !digits(s.substring(0, 2) + s.substring(3))) continue;
            int hh = Integer.parseInt(s.substring(0, 2)), mm = Integer.parseInt(s.substring(3));
            if (hh > 23 || mm > 59) continue;
            labs.add(new double[]{(W.get(0)[0] + W.get(4)[1]) / 2.0, hh * 60 + mm});
            res.labels.add(hh * 60 + mm);
        }
        if (labs.size() < 2) throw new Fail("fewer than 2 time labels");
        double[] m = new double[labs.size()];
        m[0] = labs.get(0)[1];
        for (int i = 1; i < m.length; i++) {
            double v = labs.get(i)[1];
            while (v <= m[i - 1]) v += 1440;
            m[i] = v;
        }
        double dif = m[1] - m[0];
        for (int i = 1; i < m.length; i++) if (m[i] - m[i - 1] != dif) throw new Fail("time labels not evenly spaced");
        double mxx = 0, mmm = 0;
        for (int i = 0; i < m.length; i++) { mxx += labs.get(i)[0]; mmm += m[i]; }
        mxx /= m.length;
        mmm /= m.length;
        double num = 0, den = 0;
        for (int i = 0; i < m.length; i++) {
            double dx = labs.get(i)[0] - mxx;
            num += dx * (m[i] - mmm);
            den += dx * dx;
        }
        final double b = num / den, a = mmm - b * mxx;
        if (b <= 0) throw new Fail("time runs backwards");
        for (int i = 0; i < m.length; i++) {
            if (Math.abs(a + b * labs.get(i)[0] - m[i]) > Math.max(1.0, 0.02 * dif)) throw new Fail("labels off the fit");
        }

        StringBuilder line = new StringBuilder();
        for (List<int[]> W : words(I, tx, rx, d0, d1, 0)) {
            String s = read(I, W, d0, d1);
            line.append(s == null ? "?" : s);
        }
        Matcher dm = DATE.matcher(line);
        while (dm.find()) {
            try {
                res.dates.add(LocalDate.of(Integer.parseInt(dm.group(1)), Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(3))));
            } catch (Exception e) {
                throw new Fail("date line unreadable");
            }
        }
        if (res.dates.isEmpty()) throw new Fail("date line unreadable");
        LocalDate D1 = res.dates.get(0), D2 = res.dates.get(res.dates.size() - 1);
        double eL = a + b * lx, eR = a + b * rx;
        if (eL < -1440) throw new Fail("left edge too far before first label");
        LocalDate L0 = eL >= 0 ? D1 : D1.plusDays(1);
        if (!L0.plusDays((long) Math.floor(eR / 1440)).equals(D2)) throw new Fail("date line disagrees with labels");
        ZonedDateTime mid = L0.atStartOfDay(zone);
        final long midMs = mid.toInstant().toEpochMilli();
        java.util.function.DoubleToLongFunction ms = x -> midMs + Math.round((a + b * x) * 60000);
        ZoneOffset off0 = mid.getOffset();
        if (!zone.getRules().getOffset(java.time.Instant.ofEpochMilli(ms.applyAsLong(lx))).equals(off0)
                || !zone.getRules().getOffset(java.time.Instant.ofEpochMilli(ms.applyAsLong(rx))).equals(off0)) {
            throw new Fail("clock change in view");
        }

        // now line: a wide dashed vertical line
        int H = bot - top;
        List<int[]> G = new ArrayList<>();
        for (int x = lx + 3; x < rx - 3; x++) {
            int c = 0;
            for (int y = top + 5; y < bot - 5; y++) if (I.gridish(x, y)) c++;
            if (c <= H * 0.25) continue;
            if (!G.isEmpty() && x - G.get(G.size() - 1)[1] <= 1) G.get(G.size() - 1)[1] = x;
            else G.add(new int[]{x, x});
        }
        int[] wide = null;
        for (int[] g : G) if (g[1] - g[0] + 1 >= 4 && (wide == null || g[1] - g[0] > wide[1] - wide[0])) wide = g;
        if (wide != null) {
            res.now = ms.applyAsLong((wide[0] + wide[1]) / 2.0);
            if (Math.abs(res.now - captureMs) > 180_000L) {
                throw new Fail(String.format(java.util.Locale.US, "now line %.1f min from the clock", (res.now - captureMs) / 60000.0));
            }
        } else if (ms.applyAsLong(rx) > captureMs + 120_000L) {
            throw new Fail("future in view but no now line");
        }
        long cutoff = res.now >= 0 ? Math.min(captureMs, res.now) : captureMs;

        res.pxPerMin = 1 / b;
        res.left = ms.applyAsLong(lx);
        res.right = Math.min(ms.applyAsLong(rx), cutoff);
        for (double[] st : trace(I, lx, rx, top, bot, res.pxPerMin)) {
            double v = Math.max(0.0, vb + (yb - st[2]) * upp);
            if (v < 0.03) v = 0.0;                  // the zero line half hides under the axis
            long s = Math.max(ms.applyAsLong(st[0]), res.left), e = Math.min(ms.applyAsLong(st[1]), cutoff);
            if (e > s) res.steps.add(new Step(s, e, Math.round(v * 100) / 100.0));
        }
        return res;
    }

    /**
     * The blue step line as [x0, x1, y centre]. Flats give a level directly; where a step is too
     * short to show a flat the risers on both sides give it: a riser runs from one level's line
     * to the next, so its two ends are the two levels.
     */
    private static List<double[]> trace(Img I, int lx, int rx, int top, int bot, double pxmin) throws Fail {
        int yEnd = Math.min(I.h, bot + 12);
        Map<Integer, List<int[]>> C = new HashMap<>();
        Map<Integer, Integer> lens = new HashMap<>();
        for (int x = lx + 2; x < rx - 1; x++) {
            List<int[]> cl = new ArrayList<>();
            for (int y = top; y < yEnd; y++) {
                if (!I.blue(x, y)) continue;
                if (!cl.isEmpty() && y - cl.get(cl.size() - 1)[1] <= 2) cl.get(cl.size() - 1)[1] = y;
                else cl.add(new int[]{y, y});
            }
            C.put(x, cl);
            for (int[] c : cl) {
                int n = c[1] - c[0] + 1;
                if (n <= 20) lens.merge(n, 1, Integer::sum);
            }
        }
        if (lens.isEmpty()) return new ArrayList<>();
        int thick = 0, best = -1;
        for (Map.Entry<Integer, Integer> e : lens.entrySet()) {
            if (e.getValue() > best || e.getValue() == best && e.getKey() < thick) { best = e.getValue(); thick = e.getKey(); }
        }
        if (pxmin * 10 < 6 * thick) {
            throw new Fail(String.format(java.util.Locale.US, "zoomed out too far: a 10 min step is %.1f px, the line %d px", pxmin * 10, thick));
        }
        double half = (thick - 1) / 2.0;

        // dotted rows: blue in many short pieces across the plot
        Set<Integer> dotted = new HashSet<>();
        List<Integer> dotRuns = new ArrayList<>();
        for (int y = top; y < yEnd; y++) {
            List<Integer> runs = new ArrayList<>();
            int run = 0;
            for (int x = lx + 2; x < rx - 1; x++) {
                if (I.blue(x, y)) run++;
                else if (run > 0) { runs.add(run); run = 0; }
            }
            int shortRuns = 0;
            for (int r : runs) if (r <= 3 * thick) shortRuns++;
            if (runs.size() >= 20 && shortRuns >= runs.size() * 0.8) {
                dotted.add(y);
                for (int r : runs) if (r <= 3 * thick) dotRuns.add(r);
            }
        }
        java.util.Collections.sort(dotRuns);
        int dotLen = dotRuns.isEmpty() ? 0 : dotRuns.get(dotRuns.size() / 2);

        Map<Integer, List<Double>> flats = new HashMap<>();
        Map<Integer, int[]> tall = new HashMap<>();
        for (int x = lx + 2; x < rx - 1; x++) {
            for (int[] c : C.get(x)) {
                int n = c[1] - c[0] + 1;
                if (n >= thick + 4) tall.put(x, c);
                else if (n >= Math.max(2, thick - 3)) {
                    double yc = (c[0] + c[1]) / 2.0;
                    boolean onDots = false;
                    for (int y = c[0]; y <= c[1] && !onDots; y++) onDots = dotted.contains(y);
                    if (onDots && hrun(I, lx, rx, x, (int) Math.round(yc)) < 2.5 * Math.max(dotLen, 1)) continue;
                    flats.computeIfAbsent(x, k -> new ArrayList<>()).add(yc);
                }
            }
        }

        // risers: neighbouring tall columns -> {x0, x1, top, bot}
        List<double[]> R = new ArrayList<>();
        for (int x = lx + 2; x < rx - 1; x++) {
            int[] t = tall.get(x);
            if (t == null) continue;
            double[] last = R.isEmpty() ? null : R.get(R.size() - 1);
            if (last != null && x - last[1] <= 1) {
                last[1] = x;
                last[2] = Math.min(last[2], t[0]);
                last[3] = Math.max(last[3], t[1]);
            } else R.add(new double[]{x, x, t[0], t[1]});
        }
        List<double[]> out = new ArrayList<>();
        double maxgap = MAX_GAP_MIN * pxmin;
        for (int i = -1; i < R.size(); i++) {
            double[] L = i >= 0 ? R.get(i) : null, Rr = i + 1 < R.size() ? R.get(i + 1) : null;
            double x0 = L != null ? (L[0] + L[1]) / 2 : lx + 2;
            double x1 = Rr != null ? (Rr[0] + Rr[1]) / 2 : rx - 1;
            double xa = L != null ? L[1] + 1 : x0;
            double xb = Rr != null ? Rr[0] - 1 : x1;
            // level groups of the flats in between: [first x, last x, ys...]
            List<double[]> Gx = new ArrayList<>();
            List<List<Double>> Gy = new ArrayList<>();
            for (int x = (int) xa; x <= (int) xb; x++) {
                List<Double> f = flats.get(x);
                if (f == null || f.size() != 1) continue;
                double y = f.get(0);
                if (!Gy.isEmpty()) {
                    List<Double> last = Gy.get(Gy.size() - 1);
                    if (Math.abs(y - last.get(last.size() - 1)) <= 2.5) {
                        Gx.get(Gx.size() - 1)[1] = x;
                        last.add(y);
                        continue;
                    }
                }
                Gx.add(new double[]{x, x});
                List<Double> n = new ArrayList<>();
                n.add(y);
                Gy.add(n);
            }
            if (!Gy.isEmpty()) {
                double start = x0;
                for (int j = 0; j < Gy.size(); j++) {
                    double y = median(Gy.get(j));
                    if (j + 1 < Gy.size()) {
                        // a riser hidden (glucose dots on it): split between the level groups
                        double gEnd = Gx.get(j)[1], next = Gx.get(j + 1)[0];
                        if (next - gEnd > maxgap) {
                            out.add(new double[]{start, gEnd, y});
                            start = next;
                        } else {
                            double cut = (gEnd + next) / 2;
                            out.add(new double[]{start, cut, y});
                            start = cut;
                        }
                    } else out.add(new double[]{start, x1, y});
                }
                continue;
            }
            if (L != null && Rr != null) {
                double bd = Double.MAX_VALUE, by = 0;
                for (double e : new double[]{L[2] + half, L[3] - half}) {
                    for (double f : new double[]{Rr[2] + half, Rr[3] - half}) {
                        if (Math.abs(e - f) < bd) { bd = Math.abs(e - f); by = (e + f) / 2; }
                    }
                }
                if (bd <= 2.5) out.add(new double[]{x0, x1, by});
            }
        }
        // merge neighbours at the same level, close gaps a riser left
        List<double[]> M = new ArrayList<>();
        for (double[] s : out) {
            double[] last = M.isEmpty() ? null : M.get(M.size() - 1);
            if (last != null && Math.abs(last[2] - s[2]) <= 2.5 && s[0] - last[1] <= maxgap) last[1] = s[1];
            else M.add(s.clone());
        }
        return M;
    }

    private static int hrun(Img I, int lx, int rx, int x, int y) {
        int n = 0;
        for (int k = 0; lx < x + k && x + k < rx && I.blue(x + k, y); k++) n++;
        for (int k = 1; lx < x - k && x - k < rx && I.blue(x - k, y); k++) n++;
        return n;
    }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        java.util.Collections.sort(s);
        return s.get(s.size() / 2);
    }

    // ── 5-minute bins ────────────────────────────────────────────────────────────

    /** Clock-aligned 5-min bins at least 80% covered by steps: {start ms, rate x 100}. */
    static List<long[]> bins(Result r) {
        long B = BIN_MIN * 60_000L;
        List<long[]> out = new ArrayList<>();
        for (long t = Math.floorDiv(-r.left, B) * -B; t + B <= r.right; t += B) {
            double cov = 0, tot = 0;
            for (Step s : r.steps) {
                long o = Math.min(s.end, t + B) - Math.max(s.start, t);
                if (o > 0) { cov += o; tot += o * s.rate; }
            }
            if (cov >= 0.8 * B) out.add(new long[]{t, Math.round(tot / cov * 100)});
        }
        return out;
    }
}
