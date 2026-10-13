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
    static final String SOUND = "sound";                // alarm tone URI, "" = system alarm sound
    static final String STATUS_NOTIF = "status_notif";
    static final String PUMP_NAME = "pump_name";        // paired Bluetooth name (part of it) or address
    static final String PUMP_MIN = "pump_min";          // pump link off -> warn + reopen CamAPS after; alarm 10 min later
    static final String PUMP_NUDGE = "pump_nudge";      // pump link off -> wake the screen after this many min (0 = off)
    static final String BATT_NAG = "batt_nag";         // at night, warn below this % when not charging
    static final String BATT_ALARM = "batt_alarm";      // at night, alarm below this % when not charging
    static final String NIGHT_FROM = "night_from";      // hour
    static final String NIGHT_TO = "night_to";          // hour
    // Bedtime media volume (Bedtime.java)
    static final String BED_ON = "bed_on";
    static final String BED_LEVEL_PCT = "bed_level_pct";      // target media volume, % of max
    static final String BED_EARLIEST = "bed_earliest";        // "HH:MM": sleep signals count from here
    static final String BED_FALLBACK = "bed_fallback";        // "HH:MM": arm anyway at this time
    static final String BED_FALLBACK_DAYS = "bed_fallback_days"; // optional "Mon,..,Sun" times or "off"
    static final String BED_IDLE_MIN = "bed_idle_min";        // screen off this long, nothing playing
    static final String BED_USE_MODE = "bed_use_mode";        // sleep / DND mode arms it
    static final String BED_USE_GOOGLE = "bed_use_google";    // Google sleep detection arms it
    static final String BED_GOOGLE_CONF = "bed_google_conf";  // confidence needed (0-100)
    static final String BED_RESTORE = "bed_restore";          // put it back in the morning
    static final String BED_MORNING = "bed_morning";          // "HH:MM": restore on first unlock after
    static final int DEF_BED_LEVEL_PCT = 60;
    static final String DEF_BED_EARLIEST = "21:00";
    static final String DEF_BED_FALLBACK = "00:00";
    static final int DEF_BED_IDLE_MIN = 10;
    static final int DEF_BED_GOOGLE_CONF = 80;
    static final String DEF_BED_MORNING = "07:00";
    static final String NS_URL = "ns_url";             // optional Nightscout to send CamAPS events to
    static final String NS_TOKEN = "ns_token";          // its access token (subject with treatments create/read)

    static final int DEF_SIGNAL_MIN = 20;
    static final String DEF_PUMP_NAME = "YpsoPump";
    static final int DEF_PUMP_MIN = 25;
    static final int DEF_PUMP_NUDGE = 12;
    static final int DEF_BATT_NAG = 30;
    static final int DEF_BATT_ALARM = 15;
    static final int DEF_NIGHT_FROM = 23;
    static final int DEF_NIGHT_TO = 8;
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
    static final String PUMP_STATE = "pump_state";      // Phone.UP / DOWN / UNKNOWN
    static final String PUMP_DOWN_SINCE = "pump_down_since";
    static final String PUMP_UP_AT = "pump_up_at";
    static final String LAST_NUDGE = "last_nudge";
    static final String BATT_LOW_SINCE = "batt_low_since";
    static final String LAST_REPORT = "last_report";    // last state report to Home Assistant
    static final String REPORTED = "reported";          // what it said, to report changes at once
    static final String LOG = "log";
    static final String ACCEPTED = "accepted";          // first-run disclaimer accepted
    static final String NS_JWT = "ns_jwt";              // Nightscout bearer token from the access token
    static final String NS_JWT_EXP = "ns_jwt_exp";
    static final String NS_LAST_SENT = "ns_last_sent";
    static final String NS_AUTO_MODE = "ns_auto_mode";  // last REAL auto mode sent (not simulations)
    static final String BOLUS_TRACKER = "bolus_tracker";   // BolusTracker state (bolus dedupe)
    static final String LAST_INFO = "last_info";       // last CamAPS info notification handled (dedupe)
    static final String BOOST_TRACKER = "boost_tracker";   // BoostTracker state
    static final String RD_ACTIVE = "rd_active";        // Boost/Ease-off running, as JSON (CamapsReader)
    static final String RD_PENDING = "rd_pending";      // Boost/Ease-off duration just confirmed, as JSON
    static final String RD_CALC = "rd_calc";            // last bolus calculator values seen, as JSON
    static final String BED_NIGHT = "bed_night";              // which evening BED_STATE is for
    static final String BED_STATE = "bed_state";
    static final String BED_SCREEN_OFF = "bed_screen_off";    // when the screen went off, 0 = on
    static final String BED_PREV = "bed_prev";                // media volume before, -1 = untouched
    static final String BED_SET = "bed_set";                  // what it was set to
    static final String RD_DUMP = "rd_dump";           // log full text of unknown CamAPS screens (debug, adb only)
    static final String RD_IOB = "rd_iob";             // last active insulin sent, as "value@time"
    static final String CHART_SENT = "chart_sent";     // chart basal bins sent (ChartBasal.Ledger)
    static final String CHART_MEALS = "chart_meals";   // chart slow meals sent (ChartBasal.MealLedger)
    static final String CHART_LAST = "chart_last";     // last chart outcome logged (no repeats)

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

    /**
     * Keeps the last 1500 events, newest first, for the log on the app screen: about two days,
     * so a whole night can be looked back on (the pump link alone logs ~400 lines a day; 80
     * lines lost the evening's bedtime-volume events by 05:00).
     */
    static synchronized void log(Context c, String msg) {
        String line = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.UK).format(new Date()) + "  " + msg;
        android.util.Log.i("FXWatcher", msg);
        SharedPreferences p = p(c);
        String old = p.getString(LOG, "");
        StringBuilder sb = new StringBuilder(line);
        int lines = 1;
        for (String l : old.split("\n")) {
            if (l.isEmpty()) continue;
            if (++lines > 1500) break;
            sb.append('\n').append(l);
        }
        p.edit().putString(LOG, sb.toString()).apply();
    }
}
