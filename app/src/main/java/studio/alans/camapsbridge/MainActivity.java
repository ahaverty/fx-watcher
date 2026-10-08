package studio.alans.camapsbridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One screen: paste the HA webhook URL, grant notification access, see the last reading. */
public class MainActivity extends Activity {

    private TextView last;
    private TextView access;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences(CamapsListener.PREFS, MODE_PRIVATE);

        // Allow setup over adb: am start -n studio.alans.camapsbridge/.MainActivity --es url "<webhook url>"
        String fromIntent = getIntent() != null ? getIntent().getStringExtra("url") : null;
        if (fromIntent != null && !fromIntent.trim().isEmpty()) {
            p.edit().putString(CamapsListener.KEY_URL, fromIntent.trim()).apply();
            NotificationListenerService.requestRebind(new ComponentName(this, CamapsListener.class));
        }

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(pad, pad * 3, pad, pad);

        TextView h = new TextView(this);
        h.setText("Home Assistant webhook URL");
        l.addView(h);

        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setText(p.getString(CamapsListener.KEY_URL, ""));
        url.setHint("https://xxxx.ui.nabu.casa/api/webhook/...");
        l.addView(url);

        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(v -> {
            p.edit().putString(CamapsListener.KEY_URL, url.getText().toString().trim()).apply();
            // Ask Android to rebind so it re-reads and re-sends the current CamAPS state.
            NotificationListenerService.requestRebind(new ComponentName(this, CamapsListener.class));
            refresh();
        });
        l.addView(save);

        access = new TextView(this);
        access.setPadding(0, pad, 0, 0);
        l.addView(access);

        Button grant = new Button(this);
        grant.setText("Notification access settings");
        grant.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        l.addView(grant);

        TextView lh = new TextView(this);
        lh.setPadding(0, pad, 0, 0);
        lh.setText("Last CamAPS reading:");
        l.addView(lh);

        last = new TextView(this);
        l.addView(last);

        Button r = new Button(this);
        r.setText("Refresh");
        r.setOnClickListener(v -> refresh());
        l.addView(r);

        setContentView(l);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        SharedPreferences p = getSharedPreferences(CamapsListener.PREFS, MODE_PRIVATE);
        last.setText(p.getString(CamapsListener.KEY_LAST, "(nothing yet)"));
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean ok = enabled != null && enabled.contains(getPackageName());
        access.setText(ok ? "Notification access: granted" : "Notification access: NOT granted (tap below)");
    }
}
