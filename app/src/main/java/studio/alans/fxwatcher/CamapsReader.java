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

        // "Delivered 0.1 U of insulin." as an in-app popup (CamAPS open; a notification otherwise).
        // Every read goes to the tracker: it needs to see the popup appear, not just be there.
        List<String> texts = new ArrayList<>();
        for (Item it : items) {
            texts.add(it.text);
            if (it.text.startsWith("Delivered")) known = true;
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
                JSONObject pend = new JSONObject();
                pend.put("kind", setupKind);
                pend.put("time", now);
                pend.put("minutes", setupMinutes);
                pend.put("planned", setupAt > now + 60_000L ? setupAt : 0);
                p.edit().putString(Store.RD_PENDING, pend.toString()).apply();
                Log.i(TAG, "confirmed " + pend);
                if (setupAt > now + 60_000L && setupMinutes > 0) {
                    // Planned for later: send it now, it may start with the phone in a pocket.
                    JSONObject a = new JSONObject();
                    a.put("kind", setupKind);
                    a.put("start", setupAt);
                    a.put("end", setupAt + setupMinutes * 60_000L);
                    a.put("start_known", true);
                    Store.log(this, setupKind + " planned " + Store.hm(setupAt) + "-" + Store.hm(setupAt + setupMinutes * 60_000L));
                    send(a, "planned");
                }
            } else if (dialog.startsWith("cancel-planned:")) {
                JSONObject pend = new JSONObject(p.getString(Store.RD_PENDING, "{}"));
                if (pend.optLong("planned") > 0) {
                    JSONObject a = new JSONObject();
                    a.put("kind", pend.getString("kind"));
                    a.put("start", pend.getLong("planned"));
                    a.put("end", pend.getLong("planned"));
                    a.put("start_known", true);
                    Store.log(this, "Planned " + pend.getString("kind") + " at " + Store.hm(pend.getLong("planned")) + " cancelled");
                    send(a, "cancelled");
                }
                p.edit().remove(Store.RD_PENDING).apply();
            }
        } catch (Exception ex) {
            Log.w(TAG, "click", ex);
        }
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
            long planned = pend.optLong("planned");
            if (pend.optString("kind").equals(kind) && planned > 0 && planned <= now + 60_000L
                    && now - planned < 12 * 3600_000L) {
                start = planned;                    // a planned one that has now started
                known = true;
            } else if (pend.optString("kind").equals(kind) && planned == 0
                    && now - pend.optLong("time") < 180_000L) {
                start = pend.optLong("time");       // confirmed "Now" just before
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
