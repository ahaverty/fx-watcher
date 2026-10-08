package studio.alans.fxwatcher;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
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

        // ---- Setup checklist
        header("Setup");
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        check("Read CamAPS notification", enabled != null && enabled.contains(getPackageName()),
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        NotificationManager nm = getSystemService(NotificationManager.class);
        check("Show notifications", nm.areNotificationsEnabled(), this::appNotificationSettings);
        if (Build.VERSION.SDK_INT >= 34) {
            check("Full-screen alarms over the lock screen", nm.canUseFullScreenIntent(), () ->
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:" + getPackageName()))));
        }
        check("Display over other apps (lets it reopen CamAPS)", Settings.canDrawOverlays(this), () ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        check("See the pump's Bluetooth link", Phone.canSeeBluetooth(this), () ->
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1));
        PowerManager pm = getSystemService(PowerManager.class);
        check("Battery: unrestricted", pm.isIgnoringBatteryOptimizations(getPackageName()), () ->
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))));
        if ("Unknown".equals(p.getString(Store.STATUS, "")) && p.getBoolean(Store.PRESENT, false)) {
            TextView lang = text("FX Watcher can't read the auto mode from CamAPS (it looks for the English "
                    + "label \"Auto mode\"). It will still alarm if glucose disappears or CamAPS stops, "
                    + "but not when auto mode drops out.", 14);
            lang.setTextColor(0xFFEF6C00);
            root.addView(lang);
        }

        // ---- Test
        header("Test");
        root.addView(button("Test alarm now", () -> Monitor.test(this)));
        root.addView(button("Test alarm in 20 s (lock the phone)", () -> {
            Toast.makeText(this, "Lock the phone now", Toast.LENGTH_SHORT).show();
            handler.postDelayed(() -> Monitor.test(getApplicationContext()), 20_000L);
        }));

        // ---- Settings
        header("Settings");
        EditText signal = number("Signal loss / auto mode not On: alarm after (min)",
                p.getInt(Store.SIGNAL_MIN, Store.DEF_SIGNAL_MIN), false);
        EditText missing = number("CamAPS not running: alarm after (min)",
                p.getInt(Store.MISSING_MIN, Store.DEF_MISSING_MIN), false);
        EditText stale = number("No word from CamAPS: alarm after (min)",
                p.getInt(Store.STALE_MIN, Store.DEF_STALE_MIN), false);
        EditText realarm = number("Silenced alarm comes back after (min)",
                p.getInt(Store.REALARM_MIN, Store.DEF_REALARM_MIN), false);
        EditText low = number("Urgent low alarm at or below (mmol/L; 3.0 = 54 mg/dL; 0 = off)",
                p.getFloat(Store.LOW_MMOL, Store.DEF_LOW_MMOL), true);
        EditText pumpMin = number("Pump link off: warn and reopen CamAPS after (min, alarm 10 min later, 0 = off)",
                p.getInt(Store.PUMP_MIN, Store.DEF_PUMP_MIN), false);
        EditText pumpNudge = number("Pump link off: quietly reopen CamAPS after (min, then every 8, screen off only, 0 = off)",
                p.getInt(Store.PUMP_NUDGE, Store.DEF_PUMP_NUDGE), false);
        root.addView(text("Pump Bluetooth name (or part of it)", 14));
        EditText pumpName = new EditText(this);
        pumpName.setSingleLine(true);
        pumpName.setText(p.getString(Store.PUMP_NAME, Store.DEF_PUMP_NAME));
        root.addView(pumpName);
        EditText battNag = number("At night, warn if battery below (%) and not charging",
                p.getInt(Store.BATT_NAG, Store.DEF_BATT_NAG), false);
        EditText battAlarm = number("At night, alarm if battery below (%) and not charging",
                p.getInt(Store.BATT_ALARM, Store.DEF_BATT_ALARM), false);
        EditText nightFrom = number("Night starts (hour)", p.getInt(Store.NIGHT_FROM, Store.DEF_NIGHT_FROM), false);
        EditText nightTo = number("Night ends (hour)", p.getInt(Store.NIGHT_TO, Store.DEF_NIGHT_TO), false);
        CheckBox launch = box("Reopen CamAPS automatically when something's wrong",
                p.getBoolean(Store.AUTO_LAUNCH, true));
        CheckBox maxVol = box("Alarm at full alarm volume", p.getBoolean(Store.MAX_VOLUME, true));
        String tone;
        try {
            tone = RingtoneManager.getRingtone(this, Alerts.soundUri(this)).getTitle(this);
        } catch (Exception e) {
            tone = "default";
        }
        root.addView(button("Alarm tone: " + tone, this::pickTone));
        CheckBox statusN = box("Quiet status notification", p.getBoolean(Store.STATUS_NOTIF, true));
        root.addView(text("Advanced, optional: Home Assistant webhook. Leave empty unless you use Home "
                + "Assistant; nothing leaves the phone without it.", 14));
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setText(p.getString(Store.URL, ""));
        url.setHint("https://…/api/webhook/…");
        root.addView(url);
        root.addView(button("Save settings", () -> {
            p.edit().putInt(Store.SIGNAL_MIN, intOf(signal, Store.DEF_SIGNAL_MIN))
                    .putInt(Store.MISSING_MIN, intOf(missing, Store.DEF_MISSING_MIN))
                    .putInt(Store.STALE_MIN, Math.max(6, intOf(stale, Store.DEF_STALE_MIN)))
                    .putInt(Store.REALARM_MIN, Math.max(1, intOf(realarm, Store.DEF_REALARM_MIN)))
                    .putFloat(Store.LOW_MMOL, floatOf(low, Store.DEF_LOW_MMOL))
                    .putInt(Store.PUMP_MIN, intOf(pumpMin, Store.DEF_PUMP_MIN))
                    .putInt(Store.PUMP_NUDGE, intOf(pumpNudge, Store.DEF_PUMP_NUDGE))
                    .putString(Store.PUMP_NAME, pumpName.getText().toString().trim())
                    .putInt(Store.BATT_NAG, Math.min(100, intOf(battNag, Store.DEF_BATT_NAG)))
                    .putInt(Store.BATT_ALARM, Math.min(100, intOf(battAlarm, Store.DEF_BATT_ALARM)))
                    .putInt(Store.NIGHT_FROM, Math.min(23, intOf(nightFrom, Store.DEF_NIGHT_FROM)))
                    .putInt(Store.NIGHT_TO, Math.min(23, intOf(nightTo, Store.DEF_NIGHT_TO)))
                    .putBoolean(Store.AUTO_LAUNCH, launch.isChecked())
                    .putBoolean(Store.MAX_VOLUME, maxVol.isChecked())
                    .putBoolean(Store.STATUS_NOTIF, statusN.isChecked())
                    .putString(Store.URL, url.getText().toString().trim())
                    .apply();
            Store.log(this, "Settings saved");
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        }));
        root.addView(button("Open CamAPS now", () -> Monitor.launchCamaps(this, "manual")));

        // ---- Log
        header("Log");
        TextView log = text(p.getString(Store.LOG, ""), 12);
        log.setTypeface(Typeface.MONOSPACE);
        root.addView(log);

        setContentView(sv);
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
        root.addView(r);
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

    private EditText number(String label, float value, boolean decimal) {
        root.addView(text(label, 14));
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setText(decimal ? String.valueOf(value) : String.valueOf((int) value));
        root.addView(e);
        return e;
    }

    private CheckBox box(String label, boolean checked) {
        CheckBox b = new CheckBox(this);
        b.setText(label);
        b.setChecked(checked);
        root.addView(b);
        return b;
    }

    private static int intOf(EditText e, int def) {
        try { return Math.max(0, Integer.parseInt(e.getText().toString().trim())); } catch (Exception x) { return def; }
    }

    private static float floatOf(EditText e, float def) {
        try { return Math.max(0, Float.parseFloat(e.getText().toString().trim().replace(',', '.'))); } catch (Exception x) { return def; }
    }
}
