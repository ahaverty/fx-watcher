package studio.alans.fxwatcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Settings and monitor state, all in one SharedPreferences file so they survive the process dying. */
final class Store {

    private Store() { }

    static final String PREFS = "cfg";

    // Settings
    static final String URL = "url";                    // optional Home Assistant webhook
    static final String SIGNAL_MIN = "signal_min";      // auto mode not On / no glucose -> alarm after
    static final String MISSING_MIN = "missing_min";    // CamAPS notification gone -> alarm after
    static final String STALE_MIN = "stale_min";        // FX Watcher can't confirm CamAPS -> alarm after
    static final String REALARM_MIN = "realarm_min";    // silenced alarm comes back after
    static final String LOW_MMOL = "low_mmol";          // urgent low threshold, 0 = off
    static final String AUTO_LAUNCH = "auto_launch";
    static final String MAX_VOLUME = "max_volume";
    static final String STATUS_NOTIF = "status_notif";

    static final int DEF_SIGNAL_MIN = 20;
    static final int DEF_MISSING_MIN = 5;
    static final int DEF_STALE_MIN = 15;
    static final int DEF_REALARM_MIN = 15;
    static final float DEF_LOW_MMOL = 3.0f;

    // State
    static final String LAST = "last";                  // human-readable last reading, for the app screen
    static final String LAST_SEEN = "last_seen";        // last time the CamAPS notification was confirmed present
    static final String PRESENT = "present";
    static final String STATUS = "status";
    static final String GLUCOSE = "glucose";            // as shown by CamAPS, "" when none
    static final String MMOL = "mmol";                  // glucose in mmol/L, -1 when none
    static final String TEXTS = "texts";
    static final String PKG = "pkg";
    static final String BAD_SINCE = "bad_since";
    static final String MISSING_SINCE = "missing_since";
    static final String LOW_SINCE = "low_since";
    static final String SNOOZE_UNTIL = "snooze_until";
    static final String ACK_UNTIL = "ack_until";
    static final String LEVEL = "level";
    static final String PROBLEM = "problem";
    static final String PROBLEM_TITLE = "problem_title";
    static final String LAST_LAUNCH = "last_launch";
    static final String SIM_UNTIL = "sim_until";
    static final String TEST_UNTIL = "test_until";
    static final String SAVED_VOLUME = "saved_volume";
    static final String LOG = "log";

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static long minutes(Context c, String key, int def) {
        return p(c).getInt(key, def) * 60_000L;
    }

    static String hm(long t) {
        return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(t));
    }

    static String ago(long t, long now) {
        long m = Math.max(0, (now - t) / 60_000L);
        if (m < 1) return "just now";
        if (m < 120) return m + " min ago";
        return (m / 60) + " h ago";
    }

    /** Keeps the last 80 events, newest first, for the log on the app screen. */
    static synchronized void log(Context c, String msg) {
        String line = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.UK).format(new Date()) + "  " + msg;
        android.util.Log.i("FXWatcher", msg);
        SharedPreferences p = p(c);
        String old = p.getString(LOG, "");
        StringBuilder sb = new StringBuilder(line);
        int lines = 1;
        for (String l : old.split("\n")) {
            if (l.isEmpty()) continue;
            if (++lines > 80) break;
            sb.append('\n').append(l);
        }
        p.edit().putString(LOG, sb.toString()).apply();
    }
}
