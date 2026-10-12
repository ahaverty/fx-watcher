package studio.alans.fxwatcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends what FX Watcher learns about CamAPS (boluses, Boost/Ease-off, active insulin) to
 * Nightscout as treatments / devicestatus, with the token of a Nightscout subject that may only
 * create and read those. Events wait in a queue that survives restarts and are retried until
 * Nightscout takes them (dropped after 3 days). Nightscout v1 upserts treatments on
 * (created_at, eventType), so sending one twice, or again with a corrected duration, is safe.
 *
 * Setup over adb: DebugReceiver --es ns_url https://... --es ns_token fx-watcher-...
 */
final class Nightscout {

    private Nightscout() { }

    private static final String TAG = "FXWatcher";
    private static final String QPREFS = "nsq";
    private static final String Q = "q";
    private static final long MAX_AGE = 3 * 24 * 3600_000L;
    private static final ExecutorService net = Executors.newSingleThreadExecutor();

    static boolean configured(Context c) {
        SharedPreferences p = Store.p(c);
        return !p.getString(Store.NS_URL, "").isEmpty() && !p.getString(Store.NS_TOKEN, "").isEmpty();
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** A treatment with the fields every FX Watcher event carries. */
    static JSONObject treatment(String eventType, long at, String source) throws Exception {
        JSONObject j = new JSONObject();
        j.put("eventType", eventType);
        j.put("created_at", iso(at));
        j.put("enteredBy", "fx-watcher");
        j.put("source", source);
        return j;
    }

    static void postTreatment(Context c, JSONObject t) {
        enqueue(c, "/api/v1/treatments", t);
    }

    static void postDeviceStatus(Context c, JSONObject d) {
        enqueue(c, "/api/v1/devicestatus", d);
    }

    static int queued(Context c) {
        return load(c).length();
    }

    private static synchronized JSONArray load(Context c) {
        try {
            return new JSONArray(c.getSharedPreferences(QPREFS, Context.MODE_PRIVATE).getString(Q, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static synchronized void save(Context c, JSONArray q) {
        c.getSharedPreferences(QPREFS, Context.MODE_PRIVATE).edit().putString(Q, q.toString()).apply();
    }

    private static synchronized void enqueue(Context c, String path, JSONObject body) {
        try {
            JSONArray q = load(c);
            JSONObject item = new JSONObject();
            item.put("path", path);
            item.put("body", body);
            item.put("queued", System.currentTimeMillis());
            q.put(item);
            save(c, q);
            Store.log(c, "Nightscout: queued " + body.optString("eventType", "device status"));
        } catch (Exception e) {
            Log.w(TAG, "enqueue", e);
        }
        flush(c);
    }

    /** Tries to send everything queued. Cheap when the queue is empty; call it freely. */
    static void flush(Context c) {
        Context app = c.getApplicationContext();
        net.execute(() -> drain(app));
    }

    private static void drain(Context c) {
        if (!configured(c)) return;
        while (true) {
            JSONObject item;
            synchronized (Nightscout.class) {
                JSONArray q = load(c);
                if (q.length() == 0) return;
                item = q.optJSONObject(0);
                if (item == null || System.currentTimeMillis() - item.optLong("queued") > MAX_AGE) {
                    q.remove(0);
                    save(c, q);
                    if (item != null) Store.log(c, "Nightscout: dropped an event older than 3 days");
                    continue;
                }
            }
            int code = send(c, item.optString("path"), item.optJSONObject("body"), true);
            if (code >= 200 && code < 300 || code == 400 || code == 422) {
                // Sent, or Nightscout will never take it (bad request): either way, next.
                synchronized (Nightscout.class) {
                    JSONArray q = load(c);
                    if (q.length() > 0) q.remove(0);
                    save(c, q);
                }
                Store.p(c).edit().putLong(Store.NS_LAST_SENT, System.currentTimeMillis()).apply();
                if (code >= 400) Store.log(c, "Nightscout refused an event (" + code + "), dropped it");
            } else {
                return;   // offline or Nightscout down: try again on the next flush
            }
        }
    }

    private static String jwt(Context c, boolean fresh) {
        SharedPreferences p = Store.p(c);
        String cached = p.getString(Store.NS_JWT, "");
        if (!fresh && !cached.isEmpty() && p.getLong(Store.NS_JWT_EXP, 0) > System.currentTimeMillis() + 300_000L) {
            return cached;
        }
        HttpURLConnection conn = null;
        try {
            String base = p.getString(Store.NS_URL, "").replaceAll("/+$", "");
            conn = (HttpURLConnection) new URL(base + "/api/v2/authorization/request/"
                    + p.getString(Store.NS_TOKEN, "")).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            if (conn.getResponseCode() != 200) {
                Store.log(c, "Nightscout token refused (" + conn.getResponseCode() + ")");
                return null;
            }
            JSONObject j = new JSONObject(read(conn.getInputStream()));
            String t = j.getString("token");
            p.edit().putString(Store.NS_JWT, t).putLong(Store.NS_JWT_EXP, j.optLong("exp") * 1000L).apply();
            return t;
        } catch (Exception e) {
            Log.w(TAG, "Nightscout token exchange", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int send(Context c, String path, JSONObject body, boolean retryAuth) {
        String token = jwt(c, false);
        if (token == null) return -1;
        HttpURLConnection conn = null;
        try {
            String base = Store.p(c).getString(Store.NS_URL, "").replaceAll("/+$", "");
            conn = (HttpURLConnection) new URL(base + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if ((code == 401 || code == 403) && retryAuth) {
                Store.p(c).edit().remove(Store.NS_JWT).apply();
                return send(c, path, body, false);
            }
            if (code >= 300) Log.w(TAG, "Nightscout " + path + " returned " + code);
            return code;
        } catch (Exception e) {
            Log.w(TAG, "Nightscout POST " + path, e);
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
