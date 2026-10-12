package studio.alans.fxwatcher;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.util.Log;
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

        // Active insulin (calculator info panel)
        for (Item it : items) {
            Matcher m = IOB.matcher(it.text);
            if (m.find()) {
                activeInsulin(num(m.group(1)), now, it.text.contains("infusion rate") ? it.text : null);
                known = true;
            }
        }

        // Boost / Ease-off picker: remember the duration on screen; a confirming click uses it.
        for (int i = 0; i < items.size(); i++) {
            String t = items.get(i).text;
            if (t.endsWith(" duration") && (t.startsWith("Boost") || t.startsWith("Ease-off"))) {
                int h = indexOf(items, "Hrs"), m = indexOf(items, "Mins");
                if (h > 0 && m > 0 && NUM.matcher(items.get(h - 1).text).matches()
                        && NUM.matcher(items.get(m - 1).text).matches()) {
                    int minutes = (int) (num(items.get(h - 1).text) * 60 + num(items.get(m - 1).text));
                    picker = new String[]{t.startsWith("Boost") ? "Boost" : "Ease-off", String.valueOf(minutes)};
                }
                known = true;
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
            if (kind != null) running(kind, now, remaining);
            else if (indexOf(items, "Boost") >= 0 && indexOf(items, "Ease-off") >= 0) notRunning(now);
        }

        if (!known) unknownScreen(items);
    }

    /** Latest Boost/Ease-off picker on screen: {kind, minutes}. */
    private String[] picker;

    private void onClick(AccessibilityEvent e) {
        String label = e.getText() != null && !e.getText().isEmpty() ? String.valueOf(e.getText().get(0))
                : e.getContentDescription() != null ? e.getContentDescription().toString() : "";
        Log.i(TAG, "click: " + label);
        if (picker != null && label.matches("(?i)next|start|ok|confirm|yes")) {
            try {
                JSONObject p = new JSONObject();
                p.put("kind", picker[0]);
                p.put("minutes", Integer.parseInt(picker[1]));
                p.put("time", System.currentTimeMillis());
                Store.p(this).edit().putString(Store.RD_PENDING, p.toString()).apply();
                Log.i(TAG, "pending " + p);
            } catch (Exception ignored) { }
        }
        if (label.matches("(?i)cancel")) picker = null;
    }

    // ── Boost / Ease-off ─────────────────────────────────────────────────────────

    private void running(String kind, long now, long remaining) {
        SharedPreferences p = Store.p(this);
        try {
            JSONObject a = new JSONObject(p.getString(Store.RD_ACTIVE, "{}"));
            long end = now + remaining;
            if (a.optString("kind").equals(kind) && a.optLong("end") > now - 60_000L) {
                // Same one still running: only re-send if its end moved (extended/shortened).
                if (Math.abs(a.optLong("end") - end) > 90_000L) {
                    a.put("end", end);
                    p.edit().putString(Store.RD_ACTIVE, a.toString()).apply();
                    send(a, "planned");
                }
                return;
            }
            long start = now;
            boolean known = false;
            JSONObject pend = new JSONObject(p.getString(Store.RD_PENDING, "{}"));
            if (pend.optString("kind").equals(kind) && now - pend.optLong("time") < 180_000L) {
                start = pend.optLong("time");
                known = true;
            } else if (remaining > 0 && pend.optString("kind").equals(kind)
                    && Math.abs(pend.optInt("minutes") * 60_000L - remaining) < 120_000L) {
                start = now;
                known = true;
            }
            JSONObject n = new JSONObject();
            n.put("kind", kind);
            n.put("start", start);
            n.put("end", end);
            n.put("start_known", known);
            p.edit().putString(Store.RD_ACTIVE, n.toString()).remove(Store.RD_PENDING).apply();
            Store.log(this, kind + " running until " + Store.hm(end)
                    + (known ? ", started " + Store.hm(start) : " (start not seen)"));
            send(n, "planned");
        } catch (Exception ex) {
            Log.w(TAG, "running", ex);
        }
    }

    private void notRunning(long now) {
        SharedPreferences p = Store.p(this);
        try {
            JSONObject a = new JSONObject(p.getString(Store.RD_ACTIVE, "{}"));
            if (!a.has("kind")) return;
            if (now < a.optLong("end") - 60_000L) {
                a.put("end", now);
                Store.log(this, a.optString("kind") + " cancelled at " + Store.hm(now));
                send(a, "cancelled");
            }
            p.edit().remove(Store.RD_ACTIVE).apply();
        } catch (Exception ex) {
            Log.w(TAG, "notRunning", ex);
        }
    }

    private void send(JSONObject a, String ended) throws Exception {
        long start = a.getLong("start"), end = a.getLong("end");
        JSONObject t = Nightscout.treatment("CamAPS " + a.getString("kind"), start, "camaps-screen");
        t.put("duration", Math.round((end - start) / 600.0) / 100.0);
        t.put("ended", ended);
        t.put("start_known", a.optBoolean("start_known"));
        t.put("notes", a.getString("kind") + " " + Store.hm(start) + "-" + Store.hm(end)
                + ("cancelled".equals(ended) ? " (cancelled early)" : ""));
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

    // ── Discovery ────────────────────────────────────────────────────────────────

    private void unknownScreen(List<Item> items) {
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < items.size() && i < 8; i++) {
            if (NUM.matcher(items.get(i).text).matches()) continue;
            sig.append(items.get(i).text.length() > 30 ? items.get(i).text.substring(0, 30) : items.get(i).text).append(" | ");
        }
        StringBuilder all = new StringBuilder();
        for (Item it : items) all.append(it.text.replace('\n', ' ')).append(it.selected ? "[sel]" : "").append(" | ");
        Log.i(TAG, "screen: " + all);
        if (seenScreens.add(sig.toString())) Store.log(this, "New CamAPS screen: " + sig);
    }
}
