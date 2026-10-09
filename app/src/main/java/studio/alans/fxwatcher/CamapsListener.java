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
            registerReceiver(screen, f);
            screenRegistered = true;
        }
        scanActive();
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
