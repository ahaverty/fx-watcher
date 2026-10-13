package studio.alans.fxwatcher;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Status, snooze, setup checklist, settings, test alarm and the event log. */
public class MainActivity extends Activity {

    private LinearLayout root;
    private LinearLayout cur;       // where the next field goes: root, or the open group
    private int pad;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pad = (int) (16 * getResources().getDisplayMetrics().density);
        SharedPreferences p = Store.p(this);

        // Setup over adb: am start -n studio.alans.fxwatcher/.MainActivity --es url "<webhook url>"
        String fromIntent = getIntent() != null ? getIntent().getStringExtra("url") : null;
        if (fromIntent != null && !fromIntent.trim().isEmpty()) {
            p.edit().putString(Store.URL, fromIntent.trim()).apply();
        }
        Alerts.channels(this);
        java.util.List<String> ask = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ask.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!Phone.canSeeBluetooth(this)) ask.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), 1);
        NotificationListenerService.requestRebind(new ComponentName(this, CamapsListener.class));
        if (!p.getBoolean(Store.ACCEPTED, false)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Before you rely on this")
                    .setMessage("FX Watcher is an unofficial extra alarm, not a medical device. It is not made "
                            + "by or affiliated with CamDiab or Ypsomed, and it can fail (for example if the phone "
                            + "is off, the battery dies, or a CamAPS update changes its notification).\n\n"
                            + "Keep CamAPS FX's own alerts switched on. FX Watcher only reads CamAPS's "
                            + "notification and may reopen the app; it never changes CamAPS settings or "
                            + "insulin delivery.\n\nUse \"Test alarm\" after setup to check it can wake you.")
                    .setCancelable(false)
                    .setPositiveButton("I understand", (d, w) -> p.edit().putBoolean(Store.ACCEPTED, true).apply())
                    .setNegativeButton("Exit", (d, w) -> finish())
                    .show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Monitor.check(this);
        build();
    }

    private void build() {
        SharedPreferences p = Store.p(this);
        long now = System.currentTimeMillis();
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        cur = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad * 2);
        sv.addView(root);

        // ---- Status
        Monitor.Problem pr = Monitor.problem(this, now);
        TextView big = text(Alerts.summary(this, now), 22);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(big);
        long seen = p.getLong(Store.LAST_SEEN, 0);
        String line = pr == null ? "All good" : pr.title;
        if (seen > 0) line += " · checked " + Store.ago(seen, now);
        TextView st = text(line, 16);
        st.setTextColor(pr == null ? 0xFF2E7D32 : now < pr.warnAt ? 0xFFEF6C00 : 0xFFC62828);
        root.addView(st);
        int pump = p.getInt(Store.PUMP_STATE, Phone.UNKNOWN);
        long upAt = p.getLong(Store.PUMP_UP_AT, 0);
        root.addView(text("Pump link " + (pump == Phone.UP ? "on"
                : pump == Phone.DOWN ? "off since " + Store.hm(p.getLong(Store.PUMP_DOWN_SINCE, now)) : "unknown")
                + (pump != Phone.UP && upAt > 0 ? " (last on " + Store.hm(upAt) + ")" : "")
                + " · battery " + Phone.battery(this) + "%" + (Phone.charging(this) ? " charging" : ""), 14));
        SensorExpiry sensor = SensorExpiry.load(p.getString(Store.SENSOR_EXPIRY, ""));
        if (sensor != null && sensor.graceEnd > now) {
            TextView se = text(now < sensor.expires
                    ? "Sensor expires " + Store.dayHm(sensor.expires) + " · grace until " + Store.hm(sensor.graceEnd)
                    : "Sensor expired · grace period ends " + Store.dayHm(sensor.graceEnd), 14);
            if (now >= sensor.expires) se.setTextColor(0xFFEF6C00);
            root.addView(se);
        }
        TextView ver = text("FX Watcher " + version(), 12);
        ver.setTextColor(0xFF888888);
        root.addView(ver);

        long snooze = p.getLong(Store.SNOOZE_UNTIL, 0);
        if (snooze > now) {
            root.addView(text("Signal alarms snoozed until " + Store.hm(snooze)
                    + ". Urgent lows still alarm.", 16));
            root.addView(button("Cancel snooze", () -> Monitor.unsnooze(this)));
        }
        LinearLayout row = new LinearLayout(this);
        for (int h : new int[]{1, 2, 4, 8}) {
            row.addView(button("Snooze " + h + "h", () -> Monitor.snooze(this, h)),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(row);
        if (Alerts.sounding() || p.getInt(Store.LEVEL, 0) >= Monitor.ACKED) {
            root.addView(button("Silence alarm", () -> Monitor.silence(this)));
        }

        // ---- Setup checklist (open while anything is missing, folded away once all is well)
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean okListener = enabled != null && enabled.contains(getPackageName());
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean okNotif = nm.areNotificationsEnabled();
        boolean okFull = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent();
        boolean okOverlay = Settings.canDrawOverlays(this);
        boolean okBt = Phone.canSeeBluetooth(this);
        String a11y = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean okA11y = a11y != null && a11y.contains(getPackageName() + "/");
        PowerManager pm = getSystemService(PowerManager.class);
        boolean okBattery = pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean allOk = okListener && okNotif && okFull && okOverlay && okBt && okA11y && okBattery;
        section(allOk ? "Setup  ✓ all set" : "Setup  ✗ needs attention", "setup", !allOk);
        check("Read CamAPS notification", okListener,
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        check("Show notifications", okNotif, this::appNotificationSettings);
        if (Build.VERSION.SDK_INT >= 34) {
            check("Full-screen alarms over the lock screen", okFull, () ->
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:" + getPackageName()))));
        }
        check("Display over other apps (lets it reopen CamAPS)", okOverlay, () ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        check("See the pump's Bluetooth link", okBt, () ->
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1));
        check("Read the CamAPS screen (Boost, meals, bolus checks; read-only)", okA11y,
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        check("Battery: unrestricted", okBattery, () ->
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))));
        if ("Unknown".equals(p.getString(Store.STATUS, "")) && p.getBoolean(Store.PRESENT, false)) {
            TextView lang = text("FX Watcher can't read the auto mode from CamAPS (it looks for the English "
                    + "label 'Auto mode'). It will still alarm if glucose disappears or CamAPS stops, "
                    + "but not when auto mode drops out.", 14);
            lang.setTextColor(0xFFEF6C00);
            cur.addView(lang);
        }
        cur = root;

        // ---- Settings, grouped. Every field exists even while its group is folded, so Save sees them all.
        header("Settings");
        section("Alarms", "alarms", false);
        EditText signal = number("Signal lost or auto mode off: alarm after (min)", null,
                p.getInt(Store.SIGNAL_MIN, Store.DEF_SIGNAL_MIN), false);
        EditText missing = number("CamAPS not running: alarm after (min)", null,
                p.getInt(Store.MISSING_MIN, Store.DEF_MISSING_MIN), false);
        EditText stale = number("No word from CamAPS: alarm after (min)", "At least 6.",
                p.getInt(Store.STALE_MIN, Store.DEF_STALE_MIN), false);
        EditText low = number("Urgent low at or below (mmol/L)", "3.0 = 54 mg/dL. 0 turns it off.",
                p.getFloat(Store.LOW_MMOL, Store.DEF_LOW_MMOL), true);
        EditText realarm = number("Silenced alarm comes back after (min)", null,
                p.getInt(Store.REALARM_MIN, Store.DEF_REALARM_MIN), false);
        CheckBox maxVol = box("Alarm at full alarm volume", p.getBoolean(Store.MAX_VOLUME, true));
        String tone;
        try {
            tone = RingtoneManager.getRingtone(this, Alerts.soundUri(this)).getTitle(this);
        } catch (Exception e) {
            tone = "default";
        }
        cur.addView(button("Alarm tone: " + tone, this::pickTone));
        CheckBox statusN = box("Quiet status notification", p.getBoolean(Store.STATUS_NOTIF, true));

        section("Insulin delivery checks", "bolus", false);
        EditText missedBolus = number("Bolus entered but never delivered: alert after (min)",
                "Counted from the last value on the bolus calculator. 0 turns it off.",
                p.getInt(Store.MISSED_BOLUS_MIN, Store.DEF_MISSED_BOLUS_MIN), false);
        EditText deliveryFast = number("Deliver tapped but not delivered: alert after (sec)",
                "Plus 10 s for each unit. Starts when the delivery countdown shows. 0 turns it off.",
                p.getInt(Store.DELIVERY_FAST_SEC, Store.DEF_DELIVERY_FAST_SEC), false);

        section("Pump link", "pump", false);
        EditText pumpName = field("Pump Bluetooth name", "Or part of it, for example YpsoPump.",
                p.getString(Store.PUMP_NAME, Store.DEF_PUMP_NAME));
        EditText pumpMin = number("Link off: warn and reopen CamAPS after (min)",
                "The alarm follows 10 min later. 0 turns it off.",
                p.getInt(Store.PUMP_MIN, Store.DEF_PUMP_MIN), false);
        EditText pumpNudge = number("Link off: quietly reopen CamAPS after (min)",
                "Then every 8 min, only while the screen is off. 0 turns it off.",
                p.getInt(Store.PUMP_NUDGE, Store.DEF_PUMP_NUDGE), false);
        CheckBox launch = box("Reopen CamAPS automatically when something's wrong",
                p.getBoolean(Store.AUTO_LAUNCH, true));

        section("Night and battery", "night", false);
        EditText nightFrom = number("Night starts (hour)", null, p.getInt(Store.NIGHT_FROM, Store.DEF_NIGHT_FROM), false);
        EditText nightTo = number("Night ends (hour)", null, p.getInt(Store.NIGHT_TO, Store.DEF_NIGHT_TO), false);
        EditText battNag = number("At night, warn below battery (%)", "Only when not charging.",
                p.getInt(Store.BATT_NAG, Store.DEF_BATT_NAG), false);
        EditText battAlarm = number("At night, alarm below battery (%)", "Only when not charging.",
                p.getInt(Store.BATT_ALARM, Store.DEF_BATT_ALARM), false);

        section("Bedtime media volume", "bedtime", false);
        cur.addView(text("CamAPS plays most alerts (all but Urgent Low) on the media volume, so they are "
                + "silent all night if media is turned down. This raises media volume at bedtime and puts "
                + "it back in the morning.", 14));
        CheckBox bedOn = box("Raise media volume at bedtime", p.getBoolean(Store.BED_ON, false));
        if (Bedtime.on(this)) cur.addView(text(bedtimeStatus(p), 14));
        EditText bedLevel = number("Media volume to set (% of max)", "Only ever raised, never lowered.",
                p.getInt(Store.BED_LEVEL_PCT, Store.DEF_BED_LEVEL_PCT), false);
        CheckBox bedMode = box("Start when sleep, bedtime or do-not-disturb mode turns on",
                p.getBoolean(Store.BED_USE_MODE, true));
        CheckBox bedGoogle = box("Start when Google sleep detection thinks I'm asleep (needs Physical activity)",
                p.getBoolean(Store.BED_USE_GOOGLE, false));
        EditText bedEarliest = field("Those only count after (HH:MM)", null, p.getString(Store.BED_EARLIEST, Store.DEF_BED_EARLIEST));
        EditText bedFallback = field("Otherwise start at (HH:MM)", null, p.getString(Store.BED_FALLBACK, Store.DEF_BED_FALLBACK));
        EditText bedIdle = number("Then wait until the phone is unused for (min)", "With nothing playing.",
                p.getInt(Store.BED_IDLE_MIN, Store.DEF_BED_IDLE_MIN), false);
        CheckBox bedRestore = box("Put the volume back in the morning (unless I changed it)",
                p.getBoolean(Store.BED_RESTORE, true));
        EditText bedMorning = field("Morning from (HH:MM)", "First unlock after this time.",
                p.getString(Store.BED_MORNING, Store.DEF_BED_MORNING));
        EditText bedDays = field("Advanced: start time for each evening, Mon to Sun",
                "Seven entries separated by commas; 'off' skips that night; empty = the single time above.",
                p.getString(Store.BED_FALLBACK_DAYS, ""));
        bedDays.setHint("00:00,00:00,00:00,00:00,01:00,off,00:00");
        EditText bedConf = number("Advanced: Google sleep confidence needed (%)", null,
                p.getInt(Store.BED_GOOGLE_CONF, Store.DEF_BED_GOOGLE_CONF), false);

        section("Connections (optional)", "connections", false);
        cur.addView(text("Home Assistant webhook. Leave empty unless you use Home Assistant; nothing "
                + "leaves the phone without it.", 14));
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setText(p.getString(Store.URL, ""));
        url.setHint("https://…/api/webhook/…");
        cur.addView(url);
        cur.addView(text("Nightscout. Sends boluses, carbs, Boost/Ease-off and active insulin seen in "
                + "CamAPS. Use an access token whose role can create treatments and device status.", 14));
        EditText nsUrl = field("Nightscout address", null, p.getString(Store.NS_URL, ""));
        nsUrl.setHint("https://my-nightscout.example");
        EditText nsToken = field("Nightscout access token", null, p.getString(Store.NS_TOKEN, ""));
        nsToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (Nightscout.configured(this)) {
            long sent = p.getLong(Store.NS_LAST_SENT, 0);
            cur.addView(text("Nightscout: " + Nightscout.queued(this) + " waiting"
                    + (sent > 0 ? " · last sent " + Store.ago(sent, now) : ""), 14));
        }
        cur = root;

        root.addView(button("Save settings", () -> {
            p.edit().putInt(Store.SIGNAL_MIN, intOf(signal, Store.DEF_SIGNAL_MIN))
                    .putInt(Store.MISSING_MIN, intOf(missing, Store.DEF_MISSING_MIN))
                    .putInt(Store.STALE_MIN, Math.max(6, intOf(stale, Store.DEF_STALE_MIN)))
                    .putInt(Store.REALARM_MIN, Math.max(1, intOf(realarm, Store.DEF_REALARM_MIN)))
                    .putFloat(Store.LOW_MMOL, floatOf(low, Store.DEF_LOW_MMOL))
                    .putInt(Store.PUMP_MIN, intOf(pumpMin, Store.DEF_PUMP_MIN))
                    .putInt(Store.PUMP_NUDGE, intOf(pumpNudge, Store.DEF_PUMP_NUDGE))
                    .putInt(Store.MISSED_BOLUS_MIN, intOf(missedBolus, Store.DEF_MISSED_BOLUS_MIN))
                    .putInt(Store.DELIVERY_FAST_SEC, intOf(deliveryFast, Store.DEF_DELIVERY_FAST_SEC))
                    .putString(Store.PUMP_NAME, pumpName.getText().toString().trim())
                    .putInt(Store.BATT_NAG, Math.min(100, intOf(battNag, Store.DEF_BATT_NAG)))
                    .putInt(Store.BATT_ALARM, Math.min(100, intOf(battAlarm, Store.DEF_BATT_ALARM)))
                    .putInt(Store.NIGHT_FROM, Math.min(23, intOf(nightFrom, Store.DEF_NIGHT_FROM)))
                    .putInt(Store.NIGHT_TO, Math.min(23, intOf(nightTo, Store.DEF_NIGHT_TO)))
                    .putBoolean(Store.AUTO_LAUNCH, launch.isChecked())
                    .putBoolean(Store.MAX_VOLUME, maxVol.isChecked())
                    .putBoolean(Store.STATUS_NOTIF, statusN.isChecked())
                    .putString(Store.URL, url.getText().toString().trim())
                    .putString(Store.NS_URL, nsUrl.getText().toString().trim())
                    .putString(Store.NS_TOKEN, nsToken.getText().toString().trim())
                    .putBoolean(Store.BED_ON, bedOn.isChecked())
                    .putInt(Store.BED_LEVEL_PCT, Math.max(1, Math.min(100, intOf(bedLevel, Store.DEF_BED_LEVEL_PCT))))
                    .putBoolean(Store.BED_USE_MODE, bedMode.isChecked())
                    .putBoolean(Store.BED_USE_GOOGLE, bedGoogle.isChecked())
                    .putString(Store.BED_EARLIEST, time(bedEarliest, Store.DEF_BED_EARLIEST))
                    .putString(Store.BED_FALLBACK, time(bedFallback, Store.DEF_BED_FALLBACK))
                    .putInt(Store.BED_IDLE_MIN, Math.max(1, intOf(bedIdle, Store.DEF_BED_IDLE_MIN)))
                    .putBoolean(Store.BED_RESTORE, bedRestore.isChecked())
                    .putString(Store.BED_MORNING, time(bedMorning, Store.DEF_BED_MORNING))
                    .putString(Store.BED_FALLBACK_DAYS, days(bedDays))
                    .putInt(Store.BED_GOOGLE_CONF, Math.min(100, intOf(bedConf, Store.DEF_BED_GOOGLE_CONF)))
                    .apply();
            if (bedOn.isChecked() && bedGoogle.isChecked() && !SleepReceiver.permitted(this)) {
                requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 3);
            }
            p.edit().remove(Store.NS_JWT).apply();   // re-exchange, in case the token changed
            Nightscout.flush(this);
            SleepReceiver.sync(this);
            Bedtime.tick(this);
            Store.log(this, "Settings saved");
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        }));

        // ---- Tools
        section("Test and tools", "tools", false);
        cur.addView(button("Test alarm now", () -> Monitor.test(this)));
        cur.addView(button("Test alarm in 20 s (lock the phone)", () -> {
            Toast.makeText(this, "Lock the phone now", Toast.LENGTH_SHORT).show();
            handler.postDelayed(() -> Monitor.test(getApplicationContext()), 20_000L);
        }));
        cur.addView(button("Open CamAPS now", () -> Monitor.launchCamaps(this, "manual")));
        cur = root;

        // ---- Logs: copy buttons always visible, the text itself folded away until opened
        logSection("FX Watcher log", "log", Store.LOG, "FX Watcher log", 1);
        logSection("What FX Watcher saw in CamAPS", "camlog", Store.CAMLOG, "CamAPS log (as seen by FX Watcher)", 3);

        setContentView(sv);
    }

    /** One log: copy / share buttons, and the text in a fold-away area. */
    private void logSection(String title, String key, String prefKey, String label, int defaultHours) {
        section(title, key, false);
        String log = Store.p(this).getString(prefKey, "");
        int n = LogExport.count(LogExport.lines(log, System.currentTimeMillis(), 0));
        cur.addView(text(n + " lines kept. Copy gives oldest first, with the app version and phone on top, "
                + "ready to paste to Claude.", 12));
        LinearLayout r = new LinearLayout(this);
        r.addView(copyButton("Copy last " + defaultHours + " h", prefKey, label, defaultHours),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.addView(copyButton("Copy last 24 h", prefKey, label, 24),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.addView(copyButton("Copy all", prefKey, label, 0),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        cur.addView(r);
        Button share = new Button(this);
        share.setText("Share…");
        share.setAllCaps(false);
        share.setOnClickListener(v -> {
            String body = exportText(prefKey, label, 24);
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, label).putExtra(Intent.EXTRA_TEXT, body), "Share log"));
        });
        cur.addView(share);
        TextView t = text(log.isEmpty() ? "(empty)" : log, 12);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        cur.addView(t);
        cur = root;
    }

    private Button copyButton(String caption, String prefKey, String label, int hours) {
        Button b = new Button(this);
        b.setText(caption);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            String body = exportText(prefKey, label, hours);
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText(label, body));
            Toast.makeText(this, "Copied " + LogExport.count(body) + " lines", Toast.LENGTH_SHORT).show();
        });
        return b;
    }

    private String exportText(String prefKey, String label, double hours) {
        long now = System.currentTimeMillis();
        String lines = LogExport.lines(Store.p(this).getString(prefKey, ""), now, hours);
        return label + "\n" + LogExport.header(version(), Build.MANUFACTURER + " " + Build.MODEL,
                Build.VERSION.RELEASE, now) + "\n\n" + lines;
    }

    private String version() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "v" + pi.versionName + " (build " + pi.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "";
        }
    }

    /** A fold-away group: tap the heading to open or close it; later fields go into it until reset to root. */
    private void section(String title, String key, boolean openDefault) {
        boolean open = Store.p(this).getBoolean("ui_open_" + key, openDefault);
        TextView h = text((open ? "▾  " : "▸  ") + title, 17);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, pad, 0, pad / 2);
        root.addView(h);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        root.addView(body);
        h.setOnClickListener(v -> {
            boolean now = body.getVisibility() != View.VISIBLE;
            body.setVisibility(now ? View.VISIBLE : View.GONE);
            h.setText((now ? "▾  " : "▸  ") + title);
            Store.p(this).edit().putBoolean("ui_open_" + key, now).apply();
        });
        cur = body;
    }

    private static final int PICK_TONE = 2;

    private void pickTone() {
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                        RingtoneManager.TYPE_ALARM | RingtoneManager.TYPE_RINGTONE)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "FX Watcher alarm tone")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Alerts.defaultSound())
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Alerts.soundUri(this));
        startActivityForResult(i, PICK_TONE);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != PICK_TONE || result != RESULT_OK || data == null) return;
        Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        boolean isDefault = uri == null || uri.equals(Alerts.defaultSound())
                || Settings.System.DEFAULT_ALARM_ALERT_URI.equals(uri);
        Store.p(this).edit().putString(Store.SOUND, isDefault ? "" : uri.toString()).apply();
        Store.log(this, "Alarm tone set to " + (isDefault ? "system default" : uri));
    }

    private void appNotificationSettings() {
        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    private TextView text(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setPadding(0, pad / 4, 0, pad / 4);
        return t;
    }

    private void header(String s) {
        TextView t = text(s, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, pad * 2, 0, pad / 2);
        root.addView(t);
    }

    private void check(String label, boolean ok, Runnable fix) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = text((ok ? "✓  " : "✗  ") + label, 15);
        t.setTextColor(ok ? 0xFF2E7D32 : 0xFFC62828);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!ok) {
            Button b = new Button(this);
            b.setText("Fix");
            b.setOnClickListener(v -> fix.run());
            r.addView(b);
        }
        cur.addView(r);
    }

    private Button button(String s, Runnable r) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            r.run();
            build();
        });
        return b;
    }

    private TextView hint(String s) {
        TextView t = text(s, 12);
        t.setTextColor(0xFF888888);
        return t;
    }

    private EditText number(String label, String hint, float value, boolean decimal) {
        cur.addView(text(label, 14));
        if (hint != null) cur.addView(hint(hint));
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setText(decimal ? String.valueOf(value) : String.valueOf((int) value));
        cur.addView(e);
        return e;
    }

    private CheckBox box(String label, boolean checked) {
        CheckBox b = new CheckBox(this);
        b.setText(label);
        b.setChecked(checked);
        cur.addView(b);
        return b;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == 3) SleepReceiver.sync(this);
    }

    private EditText field(String label, String hint, String value) {
        cur.addView(text(label, 14));
        if (hint != null) cur.addView(hint(hint));
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        cur.addView(e);
        return e;
    }

    /** A valid "HH:MM" from the field, else the default. */
    private static String time(EditText e, String def) {
        int m = Bedtime.minutes(e.getText().toString());
        return m < 0 ? def : Bedtime.hhmm(m);
    }

    /** "Mon..Sun" times: 7 valid entries ("HH:MM" or "off"), else empty (= use the single time). */
    private static String days(EditText e) {
        String[] a = e.getText().toString().split(",");
        if (a.length != 7) return "";
        StringBuilder sb = new StringBuilder();
        for (String d : a) {
            String t = d.trim();
            if (t.equalsIgnoreCase("off")) sb.append("off");
            else if (Bedtime.minutes(t) >= 0) sb.append(Bedtime.hhmm(Bedtime.minutes(t)));
            else return "";
            sb.append(',');
        }
        return sb.substring(0, sb.length() - 1);
    }

    private static String bedtimeStatus(SharedPreferences p) {
        String st = p.getString(Store.BED_STATE, "idle");
        int prev = p.getInt(Store.BED_PREV, -1), set = p.getInt(Store.BED_SET, -1);
        switch (st) {
            case "armed": return "Tonight: waiting for the phone to be put down";
            case "applied": return "Tonight: " + (set >= 0 ? "media volume set " + prev + " → " + set : "volume was already high enough");
            case "skipped": return "Tonight: skipped";
            default: return "Tonight: not started yet";
        }
    }

    private static int intOf(EditText e, int def) {
        try { return Math.max(0, Integer.parseInt(e.getText().toString().trim())); } catch (Exception x) { return def; }
    }

    private static float floatOf(EditText e, float def) {
        try { return Math.max(0, Float.parseFloat(e.getText().toString().trim().replace(',', '.'))); } catch (Exception x) { return def; }
    }
}
