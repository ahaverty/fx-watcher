package studio.alans.fxwatcher;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * READS the CamAPS FX screen while Alan uses it, to record what the notification never shows:
 * Boost / Ease-off (start, length, early cancel), the bolus calculator's carbs, and active
 * insulin from the calculator's info panel. Everything goes to Nightscout via {@link Nightscout}.
 *
 * STRICTLY READ-ONLY. CamAPS runs an insulin pump. This service never acts on any window: no
 * node actions, no gestures, no global actions, no text input. Its config
 * (res/xml/camaps_reader.xml) only subscribes to CamAPS's packages, asks for no gesture or key
 * capability, and CI fails the build if any acting API appears in the source.
 *
 * Rules work on the visible text (CamAPS's view ids are obfuscated numbers that change between
 * versions). Learnt from CamAPS 1.4(192) mmol/L, 12 Oct 2026:
 *   home:        "Auto mode" + status; idle shows "Boost" and "Ease-off" buttons; while one runs
 *                they are replaced by a bar: remaining "HH:MM:SS" + "Boost" or "Ease-off" + cancel
 *   picker:      "Boost duration" / "Ease-off duration", hours, "Hrs", ":", minutes, "Mins",
 *                "Cancel", "Next"
 *   calculator:  ... "Carbs", "g CHO", carbs or "---", "=", units ... units, "Total Units"
 *   info panel:  "Active insulin \n1.2 U \n\nInsulin to carbohydrate ratio ..."
 * Screens it does not know are logged (logcat tag FXReader, and once to the app log), never guessed.
 */
public class CamapsReader extends AccessibilityService {

    private static final String TAG = "FXReader";
    private static final Pattern DELIVERY_COUNTDOWN = Pattern.compile("^Delivery will start in(?:\\s*(\\d+))?");
    private static final Pattern COUNTDOWN = Pattern.compile("^(\\d{1,2}):(\\d{2}):(\\d{2})$");
    private static final Pattern NUM = Pattern.compile("^\\d+([.,]\\d+)?$");
    private static final Pattern IOB = Pattern.compile("Active insulin:?\\s*(\\d+(?:[.,]\\d+)?)\\s*U");
    private static final Set<String> seenScreens = new HashSet<>();

    /** One visible node: its text or description, and whether it is selected. */
    private static final class Item {
        final String text;
        final boolean selected;
        Item(String text, boolean selected) { this.text = text; this.selected = selected; }
    }

    @Override
    protected void onServiceConnected() {
        Store.log(this, "CamAPS screen reader on (read-only)");
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null
                || !e.getPackageName().toString().startsWith("com.camdiab.")) return;
        try {
            if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                onClick(e);
                return;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            if (root.getPackageName() == null || !root.getPackageName().toString().startsWith("com.camdiab.")) return;
            List<Item> items = new ArrayList<>();
            collect(root, items, 0);
            onScreen(items);
        } catch (Exception ex) {
            Log.w(TAG, "event", ex);
        }
    }

    private static void collect(AccessibilityNodeInfo n, List<Item> out, int depth) {
        if (n == null || depth > 40) return;
        if (n.isVisibleToUser()) {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String s = t != null && t.length() > 0 ? t.toString() : d != null && d.length() > 0 ? d.toString() : null;
            if (s != null) out.add(new Item(s.trim(), n.isSelected()));
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    private static int indexOf(List<Item> items, String text) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).text.equalsIgnoreCase(text)) return i;
        return -1;
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    // ── Screens ──────────────────────────────────────────────────────────────────

    private void onScreen(List<Item> items) {
        long now = System.currentTimeMillis();
        boolean known = false;

        // "Delivered 0.1 U of insulin." as an in-app popup (CamAPS open; a notification otherwise).
        // Every read goes to the tracker: it needs to see the popup appear, not just be there.
        List<String> texts = new ArrayList<>();
        for (Item it : items) {
            texts.add(it.text);
            if (it.text.startsWith("Delivered")) known = true;
            // "Delivery will start in 5 seconds": only on screen after Deliver was tapped.
            Matcher cd = DELIVERY_COUNTDOWN.matcher(it.text);
            if (cd.find()) BolusWatch.countdown(this, cd.group(1) == null ? -1 : Integer.parseInt(cd.group(1)), now);
        }
        try {
            CamapsListener.screen(this, texts, now);
        } catch (Exception ex) {
            Log.w(TAG, "delivered", ex);
        }

        // Active insulin: side menu, as two items "Active insulin" | "0.9 U"
        int ai = indexOf(items, "Active insulin");
        if (ai >= 0 && ai + 1 < items.size()) {
            Matcher m = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*U$").matcher(items.get(ai + 1).text);
            if (m.find()) {
                activeInsulin(num(m.group(1)), now, null);
                known = true;
            }
        }
        // Active insulin: the info panels, one text block each
        for (Item it : items) {
            Matcher m = IOB.matcher(it.text);
            if (m.find()) {
                activeInsulin(num(m.group(1)), now, it.text.contains("infusion rate") ? it.text : null);
                known = true;
            }
        }

        // Boost / Ease-off setup, three dialogs (CamAPS 192, 12 Oct 2026):
        //   "Boost duration"  hours "Hrs" : minutes "Mins"           -> Next
        //   "Boost start"     "Now" | "Later", or "Now" + a time wheel (3 visible values per
        //                     wheel, the middle one is selected: "18 19 20 : 17 32 47" = 19:32)
        //                     -> Confirm
        //   "Planned boost" / "Boost" with "Off" -> Confirm cancels a planned / running one
        // The dialog on screen is remembered; the click handler acts on Confirm.
        dialog = null;
        if (!items.isEmpty()) {
            String t = items.get(0).text;
            String kind = t.startsWith("Boost") || t.startsWith("Planned boost") ? "Boost"
                    : t.startsWith("Ease-off") || t.startsWith("Planned ease-off") ? "Ease-off" : null;
            if (kind != null) {
                known = true;
                if (t.endsWith(" duration")) {
                    int h = indexOf(items, "Hrs"), m = indexOf(items, "Mins");
                    if (h > 0 && m > 0 && NUM.matcher(items.get(h - 1).text).matches()
                            && NUM.matcher(items.get(m - 1).text).matches()) {
                        setupKind = kind;
                        setupMinutes = (int) (num(items.get(h - 1).text) * 60 + num(items.get(m - 1).text));
                    }
                    dialog = "duration";
                } else if (t.endsWith(" start")) {
                    setupKind = kind;
                    setupAt = wheelTime(items, now);
                    dialog = "start";
                } else if (t.startsWith("Planned")) {
                    dialog = "cancel-planned:" + kind;
                } else if (indexOf(items, "Off") >= 0) {
                    dialog = "stop:" + kind;
                }
            }

            // Add meal (side menu), CamAPS 192: "Add meal" screen with the amount before "g CHO"
            // and three types, the one tapped last is used (none shows as selected) -> Next ->
            // "Meal" dialog: amount, "g CHO", Cancel, Confirm.
            int gcho = indexOf(items, "g CHO");
            if (indexOf(items, "Add meal") >= 0 && indexOf(items, "Meal or snack") >= 0) {
                known = true;
                if (gcho > 0 && NUM.matcher(items.get(gcho - 1).text).matches()) mealAmount = num(items.get(gcho - 1).text);
            } else if (t.equals("Meal") && gcho > 0 && indexOf(items, "Confirm") >= 0) {
                known = true;
                if (NUM.matcher(items.get(gcho - 1).text).matches()) mealAmount = num(items.get(gcho - 1).text);
                dialog = "meal";
            }
        }

        // Bolus calculator
        int total = indexOf(items, "Total Units"), cho = indexOf(items, "g CHO");
        if (total > 0 && NUM.matcher(items.get(total - 1).text).matches()) {
            try {
                JSONObject calc = new JSONObject();
                calc.put("time", now);
                calc.put("units", num(items.get(total - 1).text));
                if (cho >= 0 && cho + 1 < items.size() && NUM.matcher(items.get(cho + 1).text).matches()) {
                    calc.put("carbs", num(items.get(cho + 1).text));
                }
                for (Item it : items) {
                    if (it.selected && it.text.toLowerCase(Locale.UK).endsWith("meal size")) {
                        calc.put("meal_size", it.text.replace(" meal size", "").toLowerCase(Locale.UK));
                    }
                }
                String shown = calc.optDouble("carbs", 0) + " g, " + calc.optDouble("units") + " U"
                        + (calc.has("meal_size") ? ", " + calc.optString("meal_size") + " meal" : "");
                if (!shown.equals(lastCalc)) {
                    lastCalc = shown;
                    Store.log(this, "Bolus calculator: " + shown);
                    BolusWatch.calculator(this, calc.optDouble("carbs", Double.NaN), calc.optDouble("units"), now);
                }
                Store.p(this).edit().putString(Store.RD_CALC, calc.toString()).apply();
            } catch (Exception ignored) {
                // a half-drawn screen; the next event has it
            }
            known = true;
        }

        // Home: the Boost / Ease-off bar, or its absence
        if (indexOf(items, "Auto mode") >= 0) {
            known = true;
            String kind = null;
            long remaining = -1;
            for (int i = 0; i < items.size(); i++) {
                Matcher m = COUNTDOWN.matcher(items.get(i).text);
                if (!m.matches()) continue;
                remaining = (Long.parseLong(m.group(1)) * 3600 + Long.parseLong(m.group(2)) * 60
                        + Long.parseLong(m.group(3))) * 1000L;
                boolean boost = indexOf(items, "Boost") >= 0, ease = indexOf(items, "Ease-off") >= 0;
                if (boost != ease) kind = boost ? "Boost" : "Ease-off";
            }
            final String k = kind;
            final long rem = remaining;
            if (k != null) boost(t -> t.bar(k, rem, now));
            else if (indexOf(items, "Boost") >= 0 && indexOf(items, "Ease-off") >= 0) {
                boost(t -> { t.noBar(now); return new ArrayList<>(); });
            }
        }

        // Landscape chart: read its basal line once the view settles (scrolling, zooming)
        if (indexOf(items, "Rotate screen") >= 0 && indexOf(items, "CGM data by Dexcom") >= 0) {
            known = true;
            main.removeCallbacks(chartShot);
            main.postDelayed(chartShot, 1500);
        }

        if (!known) unknownScreen(items);
    }

    private String lastCalc;
    /** Add meal: amount on screen and the type tapped last. */
    private double mealAmount = -1;
    private String mealType = "meal";

    /** The Boost/Ease-off dialog on screen ("duration", "start", "cancel-planned:K", "stop:K"). */
    private String dialog;
    /** What the setup dialogs showed: kind, duration in minutes (-1 unknown), start (0 = now). */
    private String setupKind;
    private int setupMinutes = -1;
    private long setupAt;

    /** The time on the start dialog's wheels (middle value of each), as the next such time; 0 if none. */
    private static long wheelTime(List<Item> items, long now) {
        int colon = indexOf(items, ":");
        if (colon < 0) return 0;
        List<String> hh = new ArrayList<>(), mm = new ArrayList<>();
        for (int i = colon - 1; i >= 0 && NUM.matcher(items.get(i).text).matches(); i--) hh.add(0, items.get(i).text);
        for (int i = colon + 1; i < items.size() && NUM.matcher(items.get(i).text).matches(); i++) mm.add(items.get(i).text);
        if (hh.isEmpty() || mm.isEmpty()) return 0;
        int h = Integer.parseInt(hh.get(hh.size() == 3 ? 1 : 0)), m = Integer.parseInt(mm.get(mm.size() == 3 ? 1 : 0));
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(java.util.Calendar.HOUR_OF_DAY, h);
        c.set(java.util.Calendar.MINUTE, m);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() < now - 120_000L) c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private void onClick(AccessibilityEvent e) {
        String label = e.getText() != null && !e.getText().isEmpty() ? String.valueOf(e.getText().get(0))
                : e.getContentDescription() != null ? e.getContentDescription().toString() : "";
        Log.i(TAG, "click: " + label + " (dialog " + dialog + ")");
        if (label.equals("Add meal")) { mealType = "meal"; mealAmount = -1; }
        else if (label.equals("Meal or snack")) mealType = "meal";
        else if (label.equals("Hypoglycaemia treatment")) mealType = "hypo";
        else if (label.equals("Slowly absorbed meal")) mealType = "slow";
        if (!label.equalsIgnoreCase("Confirm") || dialog == null) return;
        long now = System.currentTimeMillis();
        SharedPreferences p = Store.p(this);
        try {
            if (dialog.equals("meal") && mealAmount > 0) {
                JSONObject t = Nightscout.treatment("Carbs", now, "camaps-screen");
                t.put("carbs", mealAmount);
                t.put("meal_type", mealType);    // meal | hypo | slow (slowly absorbed)
                t.put("notes", "Add meal: " + mealAmount + " g, " + ("hypo".equals(mealType)
                        ? "hypo treatment" : "slow".equals(mealType) ? "slowly absorbed meal" : "meal or snack"));
                Store.log(this, t.getString("notes"));
                Nightscout.postTreatment(this, t);
                mealAmount = -1;
                return;
            }
            if (dialog.equals("start") && setupKind != null) {
                boost(t -> t.start(setupKind, setupMinutes, setupAt, now));
            } else if (dialog.startsWith("stop:")) {
                String kind = dialog.substring(5);
                boost(t -> t.stop(kind, now));
            } else if (dialog.startsWith("cancel-planned:")) {
                String kind = dialog.substring(15);
                boost(t -> t.cancelPlanned(kind, now));
            }
        } catch (Exception ex) {
            Log.w(TAG, "click", ex);
        }
    }

    // ── Boost / Ease-off ─────────────────────────────────────────────────────────

    /** Runs one BoostTracker event against the saved state and sends what it decides. */
    private synchronized void boost(java.util.function.Function<BoostTracker, List<BoostTracker.Post>> event) {
        SharedPreferences p = Store.p(this);
        BoostTracker t = BoostTracker.load(p.getString(Store.BOOST_TRACKER, ""));
        String before = t.save();
        List<BoostTracker.Post> posts = event.apply(t);
        String after = t.save();
        if (!after.equals(before)) p.edit().putString(Store.BOOST_TRACKER, after).apply();
        for (BoostTracker.Post x : posts) {
            try {
                send(x);
            } catch (Exception ex) {
                Log.w(TAG, "boost", ex);
            }
        }
    }

    private void send(BoostTracker.Post x) throws Exception {
        boolean cancelled = "cancelled".equals(x.ended);
        Store.log(this, x.kind + (cancelled ? (x.end == x.start ? " planned for " + Store.hm(x.start) + " cancelled"
                : " cancelled at " + Store.hm(x.end)) : " " + Store.hm(x.start) + "-" + Store.hm(x.end))
                + (x.startKnown ? "" : " (start not seen)"));
        JSONObject t = Nightscout.treatment("CamAPS " + x.kind, x.start, "camaps-screen");
        t.put("duration", Math.round((x.end - x.start) / 600.0) / 100.0);
        t.put("ended", x.ended);
        t.put("start_known", x.startKnown);
        t.put("notes", x.kind + " " + Store.hm(x.start) + "-" + Store.hm(x.end)
                + (cancelled ? " (cancelled early)" : ""));
        Nightscout.postTreatment(this, t);
    }

    // ── Active insulin ───────────────────────────────────────────────────────────

    /**
     * Home screen info panel (the (i) on the glucose card), one text block, CamAPS 192:
     * "Active insulin: 0.90 U  Insulin infusion rate: 0.00 U/h  Pump reservoir: 129 U
     *  Pump battery: 100 %   Bolus: 1.30 U 55 min ago   Pump connect: 1 min ago
     *  Sensor data: 2 min ago  Target glucose: 6.1 mmol/L  Insulin today: 34.8 U
     *  Insulin yesterday: 48.5 U   Glucose today: 8.1 mmol/L  Glucose yesterday: 9.1 mmol/L"
     * The bolus calculator's info panel only has "Active insulin \n1.2 U" (no colon).
     */
    private static final String[][] PANEL = {
            {"infusion_rate_u_h", "Insulin infusion rate:\\s*(\\d+(?:[.,]\\d+)?)\\s*U/h"},
            {"reservoir_u", "Pump reservoir:\\s*(\\d+(?:[.,]\\d+)?)\\s*U"},
            {"battery_pct", "Pump battery:\\s*(\\d+)\\s*%"},
            {"last_bolus_u", "Bolus:\\s*(\\d+(?:[.,]\\d+)?)\\s*U"},
            {"last_bolus_min_ago", "Bolus:\\s*\\d+(?:[.,]\\d+)?\\s*U\\s*(\\d+)\\s*min ago"},
            {"pump_connect_min_ago", "Pump connect:\\s*(\\d+)\\s*min ago"},
            {"sensor_data_min_ago", "Sensor data:\\s*(\\d+)\\s*min ago"},
            {"target_mmol", "Target glucose:\\s*(\\d+(?:[.,]\\d+)?)"},
            {"insulin_today_u", "Insulin today:\\s*(\\d+(?:[.,]\\d+)?)\\s*U"},
            {"insulin_yesterday_u", "Insulin yesterday:\\s*(\\d+(?:[.,]\\d+)?)\\s*U"},
            {"glucose_today_mmol", "Glucose today:\\s*(\\d+(?:[.,]\\d+)?)"},
            {"glucose_yesterday_mmol", "Glucose yesterday:\\s*(\\d+(?:[.,]\\d+)?)"},
    };

    private void activeInsulin(double iob, long now, String text) {
        try {
            JSONObject camaps = new JSONObject();
            if (text != null) {
                for (String[] f : PANEL) {
                    Matcher m = Pattern.compile(f[1]).matcher(text);
                    if (m.find()) camaps.put(f[0], num(m.group(1)));
                }
            }
            // Same values within 5 min: already sent.
            String key = iob + "|" + camaps;
            SharedPreferences p = Store.p(this);
            String last = p.getString(Store.RD_IOB, "");
            int at = last.lastIndexOf('@');
            if (at > 0 && last.substring(0, at).equals(key)
                    && now - Long.parseLong(last.substring(at + 1)) < 300_000L) return;
            p.edit().putString(Store.RD_IOB, key + "@" + now).apply();

            JSONObject d = new JSONObject();
            d.put("device", "fx-watcher");
            d.put("created_at", Nightscout.iso(now));
            JSONObject pump = new JSONObject(), io = new JSONObject();
            io.put("iob", iob);
            io.put("timestamp", Nightscout.iso(now));
            pump.put("iob", io);
            if (camaps.has("reservoir_u")) pump.put("reservoir", camaps.get("reservoir_u"));
            if (camaps.has("battery_pct")) {
                JSONObject b = new JSONObject();
                b.put("percent", camaps.get("battery_pct"));
                pump.put("battery", b);
            }
            d.put("pump", pump);
            if (camaps.length() > 0) d.put("camaps", camaps);
            d.put("source", text != null && camaps.length() > 0 ? "camaps-screen:status" : "camaps-screen:calculator");
            Nightscout.postDeviceStatus(this, d);
            Store.log(this, "Active insulin " + iob + " U"
                    + (camaps.has("infusion_rate_u_h") ? ", rate " + camaps.get("infusion_rate_u_h") + " U/h" : ""));
        } catch (Exception ex) {
            Log.w(TAG, "iob", ex);
        }
    }

    // ── Chart basal ──────────────────────────────────────────────────────────────

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService chartWork = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable chartShot = this::chartShot;
    private volatile boolean shooting;
    private long lastShot;

    /** Screenshot (memory only) of the chart view, parsed off the main thread, then dropped. */
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private void chartShot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || shooting) return;
        long now = System.currentTimeMillis();
        if (now - lastShot < 5000) {
            main.postDelayed(chartShot, 5000 - (now - lastShot));
            return;
        }
        DisplayManager dm = getSystemService(DisplayManager.class);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null || d.getRotation() == Surface.ROTATION_0 || d.getRotation() == Surface.ROTATION_180) return;
        Rect bounds = chartBounds();
        if (bounds == null) return;
        shooting = true;
        lastShot = now;
        takeScreenshot(Display.DEFAULT_DISPLAY, chartWork, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult shot) {
                long at = System.currentTimeMillis();
                HardwareBuffer hb = shot.getHardwareBuffer();
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, shot.getColorSpace());
                    if (hw == null) return;
                    Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                    hw.recycle();
                    Rect c = new Rect(bounds);
                    if (!c.intersect(0, 0, sw.getWidth(), sw.getHeight()) || c.width() < bounds.width() * 0.9) {
                        String size = sw.getWidth() + "x" + sw.getHeight();
                        sw.recycle();
                        chartLog("Chart not read: screenshot " + size + ", chart at " + bounds);
                        return;
                    }
                    int[] px = new int[c.width() * c.height()];
                    sw.getPixels(px, 0, c.width(), c.left, c.top, c.width(), c.height());
                    sw.recycle();
                    ChartParser.Result r = ChartParser.parse(px, c.width(), c.height(), at, java.time.ZoneId.systemDefault());
                    ChartBasal.handle(CamapsReader.this, r, at);
                    chartLog(null);
                } catch (ChartParser.Fail f) {
                    chartLog("Chart not read: " + f.getMessage());
                } catch (Exception ex) {
                    Log.w(TAG, "chart", ex);
                } finally {
                    hb.close();
                    shooting = false;
                }
            }

            @Override
            public void onFailure(int code) {
                shooting = false;
                Log.w(TAG, "chart screenshot failed: " + code);
            }
        });
    }

    /** The chart: CamAPS's largest text-less View on screen. */
    private Rect chartBounds() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !root.getPackageName().toString().startsWith("com.camdiab.")) return null;
        Rect[] best = {null};
        findChart(root, best, 0);
        return best[0];
    }

    private static void findChart(AccessibilityNodeInfo n, Rect[] best, int depth) {
        if (n == null || depth > 40) return;
        if ("android.view.View".contentEquals(n.getClassName() == null ? "" : n.getClassName())
                && n.getText() == null && n.getContentDescription() == null && n.isVisibleToUser()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (best[0] == null || r.width() * r.height() > best[0].width() * best[0].height()) best[0] = r;
        }
        for (int i = 0; i < n.getChildCount(); i++) findChart(n.getChild(i), best, depth + 1);
    }

    /** Logs why the chart wasn't read, once per reason (it is retried on every look). */
    private void chartLog(String why) {
        SharedPreferences p = Store.p(this);
        String last = p.getString(Store.CHART_LAST, "");
        if (why == null) { if (!last.isEmpty()) p.edit().putString(Store.CHART_LAST, "").apply(); return; }
        Log.i(TAG, why);
        if (why.equals(last)) return;
        p.edit().putString(Store.CHART_LAST, why).apply();
        Store.log(this, why);
    }

    // ── Discovery ────────────────────────────────────────────────────────────────

    /**
     * A screen no rule knows. Logs only its title (first two labels): CamAPS screens also show
     * the account email, PE number, Dexcom username, pairing code and serials. The full text goes
     * to logcat only while debug dumps are on (adb: DebugReceiver --ez reader_dump true), for
     * writing new rules, and should be switched off after.
     */
    private void unknownScreen(List<Item> items) {
        StringBuilder sig = new StringBuilder();
        int n = 0;
        for (int i = 0; i < items.size() && n < 2; i++) {
            String t = items.get(i).text;
            if (NUM.matcher(t).matches() || t.equals("Acknowledge and dismiss")) continue;
            sig.append(t.length() > 30 ? t.substring(0, 30) : t).append(" | ");
            n++;
        }
        if (Store.p(this).getBoolean(Store.RD_DUMP, false)) {
            StringBuilder all = new StringBuilder();
            for (Item it : items) all.append(it.text.replace('\n', ' ')).append(it.selected ? "[sel]" : "").append(" | ");
            Log.i(TAG, "screen: " + all);
        }
        if (seenScreens.add(sig.toString())) Log.i(TAG, "new screen: " + sig);
    }
}
