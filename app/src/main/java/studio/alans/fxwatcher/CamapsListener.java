package studio.alans.fxwatcher;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads the CamAPS FX ongoing notification (a custom layout, so its text is not in the
 * normal notification fields), pulls out the auto mode state and glucose, and hands them
 * to the Monitor. Same technique xDrip+ uses for its "companion app" mode: inflate the
 * RemoteViews and walk the TextViews. Optionally forwards each reading to a Home Assistant webhook.
 */
public class CamapsListener extends NotificationListenerService {

    private static final String TAG = "FXWatcher";

    private static volatile CamapsListener instance;
    private static final ExecutorService net = Executors.newSingleThreadExecutor();

    static boolean isCamaps(StatusBarNotification sbn) {
        return sbn != null && sbn.getPackageName() != null
                && sbn.getPackageName().startsWith("com.camdiab.")
                && sbn.isOngoing();
    }

    static boolean connected() {
        return instance != null;
    }

    /** Re-reads the active notifications so the monitor knows CamAPS is still there. False if not bound. */
    static boolean poll(Context c) {
        CamapsListener l = instance;
        if (l == null) {
            requestRebind(new ComponentName(c, CamapsListener.class));
            return false;
        }
        l.scanActive();
        return true;
    }

    /** While the pump link is off, notes when the screen comes on or the phone is unlocked (to learn what brings it back). */
    private final android.content.BroadcastReceiver screen = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, android.content.Intent i) {
            String act = i.getAction();
            if (android.content.Intent.ACTION_SCREEN_OFF.equals(act)) { Bedtime.onScreen(c, false); return; }
            if (android.content.Intent.ACTION_SCREEN_ON.equals(act)) Bedtime.onScreen(c, true);
            if (android.content.Intent.ACTION_USER_PRESENT.equals(act)) Bedtime.onUnlock(c);
            SharedPreferences p = Store.p(c);
            long down = p.getLong(Store.PUMP_DOWN_SINCE, 0);
            if (down == 0) return;
            String what = android.content.Intent.ACTION_USER_PRESENT.equals(i.getAction()) ? "Phone unlocked" : "Screen on";
            Store.log(c, what + " (pump link off " + Store.ago(down, System.currentTimeMillis()).replace(" ago", "") + ")");
        }
    };
    private boolean screenRegistered;

    @Override
    public void onListenerConnected() {
        instance = this;
        Store.log(this, "Notification listener connected");
        if (!screenRegistered) {
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON);
            f.addAction(android.content.Intent.ACTION_USER_PRESENT);
            f.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            registerReceiver(screen, f);
            screenRegistered = true;
        }
        scanActive();
        SleepReceiver.sync(this);
        onInterruptionFilterChanged(getCurrentInterruptionFilter());
        Bedtime.tick(this);
    }

    /** Sleep / bedtime / do-not-disturb mode on or off: bedtime media volume listens for it. */
    @Override
    public void onInterruptionFilterChanged(int filter) {
        boolean quiet = filter != INTERRUPTION_FILTER_ALL && filter != INTERRUPTION_FILTER_UNKNOWN;
        Bedtime.onQuietMode(this, quiet);
    }

    @Override
    public void onListenerDisconnected() {
        if (screenRegistered) {
            try { unregisterReceiver(screen); } catch (Exception ignored) { }
            screenRegistered = false;
        }
        instance = null;
        Store.log(this, "Notification listener disconnected, asking to rebind");
        requestRebind(new ComponentName(this, CamapsListener.class));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (isCamaps(sbn) && !handle(sbn, false)) logIgnored(sbn);
        else if (sbn != null && sbn.getPackageName() != null
                && sbn.getPackageName().startsWith("com.camdiab.") && !sbn.isOngoing()) info(sbn);
    }

    private static final java.util.regex.Pattern DELIVERED =
            java.util.regex.Pattern.compile("Delivered\\s+(\\d+(?:[.,]\\d+)?)\\s*U", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * CamAPS's one-off "Information" notifications, e.g. "Delivered 1.3 U of insulin." (posted
     * when the pump has delivered a bolus). A delivered bolus goes to Nightscout as a Bolus,
     * with the carbs from the bolus calculator if CamapsReader saw it in the last 10 min;
     * anything else as "CamAPS Info" so new kinds of message show up. Read only.
     */
    private void info(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (x == null) x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = t == null ? "" : t.toString().trim(), text = x == null ? "" : x.toString().trim();
        if (title.isEmpty() && text.isEmpty()) return;
        // CamAPS's own time for the message: unlike the post time, it stays the same if CamAPS
        // updates or re-posts the notification, so a re-post is not taken as a second bolus.
        long at = n.when > 0 ? n.when : sbn.getPostTime();
        SharedPreferences p = Store.p(this);
        String key = at + "|" + text;
        if (key.equals(p.getString(Store.LAST_INFO, ""))) return;   // the same post seen twice
        p.edit().putString(Store.LAST_INFO, key).apply();
        Store.log(this, "CamAPS: " + (title.isEmpty() ? "" : title + ": ") + text);
        if (!Nightscout.configured(this)) return;
        try {
            if (delivered(this, text, at, "camaps-notification")) return;
            JSONObject tr = Nightscout.treatment("CamAPS Info", at, "camaps-notification");
            tr.put("notes", (title.isEmpty() || title.equalsIgnoreCase("Information") ? "" : title + ": ") + text);
            Nightscout.postTreatment(this, tr);
        } catch (Exception e) {
            Log.w(TAG, "info notification", e);
        }
    }

    /**
     * "Delivered X U of insulin." -> one Nightscout Bolus. CamAPS shows it as a notification when
     * it is in the background and as an in-app dialog when open (CamapsReader), maybe both: the
     * same amount within 3 min counts once. Adds the bolus calculator's carbs if seen in the last
     * 10 min. Returns false if the text is not a delivery.
     */
    static synchronized boolean delivered(Context c, String text, long at, String source) throws Exception {
        if (BolusTracker.units(text) < 0) return false;
        SharedPreferences p = Store.p(c);
        BolusTracker t = BolusTracker.load(p.getString(Store.BOLUS_TRACKER, ""));
        post(c, t.notification(text, at, calcCarbs(p, at)), source);
        p.edit().putString(Store.BOLUS_TRACKER, t.save()).apply();
        return true;
    }

    /** Every CamAPS screen read (CamapsReader): picks up the in-app "Delivered" popup. */
    static synchronized void screen(Context c, java.util.List<String> texts, long now) throws Exception {
        SharedPreferences p = Store.p(c);
        BolusTracker t = BolusTracker.load(p.getString(Store.BOLUS_TRACKER, ""));
        String before = t.save();
        BolusTracker.Result r = t.screen(texts, now, calcCarbs(p, now));
        String after = t.save();
        if (!after.equals(before)) p.edit().putString(Store.BOLUS_TRACKER, after).apply();
        post(c, r, "camaps-screen");
    }

    /** Carbs from the bolus calculator if it was on screen in the 10 min before, else NaN. */
    private static double calcCarbs(SharedPreferences p, long at) {
        try {
            JSONObject calc = new JSONObject(p.getString(Store.RD_CALC, "{}"));
            if (calc.has("carbs") && calc.optDouble("carbs") > 0
                    && at - calc.optLong("time") < 600_000L && at >= calc.optLong("time") - 60_000L) {
                return calc.optDouble("carbs");
            }
        } catch (Exception ignored) { }
        return Double.NaN;
    }

    /** NEW -> a Bolus treatment; UPDATE -> the same treatment again (same created_at) with carbs. */
    private static void post(Context c, BolusTracker.Result r, String source) throws Exception {
        if (r.kind == BolusTracker.Kind.NONE) return;
        SharedPreferences p = Store.p(c);
        Store.log(c, (r.kind == BolusTracker.Kind.NEW ? "Bolus delivered: " : "Bolus updated with carbs: ")
                + r.units + " U" + (Double.isNaN(r.carbs) ? "" : ", " + r.carbs + " g") + " (" + source + ")");
        if (!Nightscout.configured(c)) return;
        JSONObject tr = Nightscout.treatment("Bolus", r.at, source);
        tr.put("insulin", r.units);
        if (!Double.isNaN(r.carbs)) {
            JSONObject calc = new JSONObject(p.getString(Store.RD_CALC, "{}"));
            tr.put("carbs", r.carbs);
            if (calc.has("meal_size")) tr.put("meal_size", calc.optString("meal_size"));
            if (calc.has("units")) tr.put("calculator_units", calc.optDouble("units"));
            tr.put("source", source + "+calculator");
            p.edit().remove(Store.RD_CALC).apply();
        }
        tr.put("notes", r.text);
        Nightscout.postTreatment(c, tr);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (isCamaps(sbn)) {
            // Make sure it really is gone (CamAPS may have reposted under another id).
            if (!scanActive()) Store.log(this, "CamAPS notification removed");
        }
    }

    /** Reports whatever CamAPS is showing right now. Returns true if its notification is present. */
    private boolean scanActive() {
        try {
            // CamAPS can show more than one ongoing notification (e.g. a low alert next to the
            // status one), so use the one that actually carries the status.
            StatusBarNotification[] active = getActiveNotifications();
            StatusBarNotification other = null;
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (!isCamaps(sbn)) continue;
                    if (handle(sbn, false)) return true;
                    other = sbn;
                }
            }
            if (other != null) {
                handle(other, true); // CamAPS is there, but nothing readable
                return true;
            }
            Monitor.onCamaps(this, false, null, null, new ArrayList<>(), null, false);
        } catch (Exception e) {
            Log.w(TAG, "scanActive", e);
        }
        return false;
    }

    /**
     * Reports this notification to the Monitor if it is CamAPS's status notification (has the
     * "Auto mode" label or a glucose value). Returns false and reports nothing otherwise, unless
     * force is set.
     */
    private boolean handle(StatusBarNotification sbn, boolean force) {
        List<String> texts = new ArrayList<>();
        try {
            Notification n = sbn.getNotification();
            RemoteViews rv = n.contentView != null ? n.contentView : n.bigContentView;
            if (rv == null) {
                CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                if (t != null) texts.add(t.toString());
                if (x != null) texts.add(x.toString());
            } else {
                View root = rv.apply(getApplicationContext(), null);
                collect(root, texts);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read CamAPS notification", e);
            texts.add("ERROR: " + e.getClass().getSimpleName());
        }

        String status = null;
        String glucose = null;
        for (int i = 0; i < texts.size(); i++) {
            String s = texts.get(i).trim();
            if (s.equalsIgnoreCase("Auto mode") && i + 1 < texts.size()) {
                status = texts.get(i + 1).trim();
            }
            if (glucose == null && (s.matches("\\d{1,2}([.,]\\d)?|\\d{2,3}")
                    || s.equalsIgnoreCase("LOW") || s.equalsIgnoreCase("HIGH"))) {
                glucose = s;
            }
        }
        if (status == null && glucose == null && !force) return false;
        if (status == null) status = "Unknown";
        String pkg = sbn.getPackageName();
        Monitor.onCamaps(this, true, status, glucose, texts, pkg, false);
        return true;
    }

    /** Notes a CamAPS notification that isn't the status one, so we can see what it was. */
    private void logIgnored(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        Store.log(this, "Ignored other CamAPS notification (id " + sbn.getId()
                + ", channel " + n.getChannelId() + ", category " + n.category
                + (t != null ? ", \"" + t + "\"" : "") + (x != null ? ", \"" + x + "\"" : "") + ")");
    }

    private static void collect(View v, List<String> out) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) out.add(t.toString());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    /** POSTs the reading to the Home Assistant webhook, if one is set. Same payload as CamAPS Bridge. */
    static void forward(Context c, String status, Double glucose, List<String> texts, String pkg) {
        try {
            JSONObject j = new JSONObject();
            j.put("status", status);
            if (glucose != null) j.put("glucose", glucose);
            j.put("texts", new JSONArray(texts));
            if (pkg != null) j.put("package", pkg);
            j.put("ts", System.currentTimeMillis());
            post(c, j.toString());
        } catch (Exception ignored) {
            // nothing to send
        }
    }

    /** Tells Home Assistant about a snooze set on the phone, so its alarms snooze too. 0 = cancelled. */
    static void forwardSnooze(Context c, long until) {
        try {
            JSONObject j = new JSONObject();
            j.put("type", "snooze");
            j.put("until", until);
            j.put("ts", System.currentTimeMillis());
            post(c, j.toString());
        } catch (Exception ignored) {
            // nothing to send
        }
    }

    /** FX Watcher's own state and heartbeat, so Home Assistant only steps in when it's needed. */
    static void forwardState(Context c, String level, String code, String title, boolean snoozed,
                             String pump, int battery, boolean charging) {
        try {
            JSONObject j = new JSONObject();
            j.put("type", "state");
            j.put("level", level);
            j.put("problem", code);
            j.put("title", title);
            j.put("snoozed", snoozed);
            j.put("pump", pump);
            j.put("battery", battery);
            j.put("charging", charging);
            j.put("ts", System.currentTimeMillis());
            post(c, j.toString());
        } catch (Exception ignored) {
            // nothing to send
        }
    }

    private static void post(Context c, String body) {
        SharedPreferences p = Store.p(c);
        String url = p.getString(Store.URL, "");
        if (url == null || url.isEmpty()) return;

        net.execute(() -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) return;
                    Log.w(TAG, "HA webhook returned " + code);
                } catch (Exception e) {
                    Log.w(TAG, "POST failed (attempt " + (attempt + 1) + ")", e);
                } finally {
                    if (conn != null) conn.disconnect();
                }
                try { Thread.sleep(5000L * (attempt + 1)); } catch (InterruptedException ignored) { return; }
            }
        });
    }
}
