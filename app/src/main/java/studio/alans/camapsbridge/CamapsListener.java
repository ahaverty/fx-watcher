package studio.alans.camapsbridge;

import android.app.Notification;
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
 * normal notification fields), pulls out the auto mode state and glucose, and POSTs them
 * to a Home Assistant webhook. Same technique xDrip+ uses for its "companion app" mode:
 * inflate the RemoteViews and walk the TextViews.
 */
public class CamapsListener extends NotificationListenerService {

    private static final String TAG = "CamapsBridge";
    static final String PREFS = "cfg";
    static final String KEY_URL = "url";
    static final String KEY_LAST = "last";

    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private static boolean isCamaps(StatusBarNotification sbn) {
        return sbn != null && sbn.getPackageName() != null
                && sbn.getPackageName().startsWith("com.camdiab.");
    }

    @Override
    public void onListenerConnected() {
        // Report whatever CamAPS is showing right now.
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (isCamaps(sbn) && sbn.isOngoing()) handle(sbn);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "onListenerConnected", e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (isCamaps(sbn) && sbn.isOngoing()) handle(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (isCamaps(sbn) && sbn.isOngoing()) {
            send("No notification", null, new ArrayList<>(), sbn.getPackageName());
        }
    }

    private void handle(StatusBarNotification sbn) {
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
        Double glucose = null;
        for (int i = 0; i < texts.size(); i++) {
            String s = texts.get(i).trim();
            if (s.equalsIgnoreCase("Auto mode") && i + 1 < texts.size()) {
                status = texts.get(i + 1).trim();
            }
            if (glucose == null && s.matches("\\d{1,2}([.,]\\d)?|\\d{2,3}")) {
                try { glucose = Double.parseDouble(s.replace(',', '.')); } catch (NumberFormatException ignored) { }
            }
        }
        if (status == null) status = "Unknown";
        send(status, glucose, texts, sbn.getPackageName());
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

    private void send(String status, Double glucose, List<String> texts, String pkg) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String url = p.getString(KEY_URL, "");
        String summary = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.UK)
                .format(new java.util.Date()) + "  " + status + "  " + texts;
        p.edit().putString(KEY_LAST, summary).apply();
        if (url == null || url.isEmpty()) return;

        final String body;
        try {
            JSONObject j = new JSONObject();
            j.put("status", status);
            if (glucose != null) j.put("glucose", glucose);
            j.put("texts", new JSONArray(texts));
            j.put("package", pkg);
            j.put("ts", System.currentTimeMillis());
            body = j.toString();
        } catch (Exception e) {
            return;
        }

        net.execute(() -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                    int code = c.getResponseCode();
                    if (code >= 200 && code < 300) return;
                    Log.w(TAG, "HA webhook returned " + code);
                } catch (Exception e) {
                    Log.w(TAG, "POST failed (attempt " + (attempt + 1) + ")", e);
                } finally {
                    if (c != null) c.disconnect();
                }
                try { Thread.sleep(5000L * (attempt + 1)); } catch (InterruptedException ignored) { return; }
            }
        });
    }
}
