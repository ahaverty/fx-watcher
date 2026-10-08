package studio.alans.fxwatcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Full-screen alarm, shown over the lock screen. */
public class AlarmActivity extends Activity {

    private static WeakReference<AlarmActivity> open = new WeakReference<>(null);

    static void closeIfOpen() {
        AlarmActivity a = open.get();
        if (a != null) a.runOnUiThread(a::finish);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        open = new WeakReference<>(this);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        build();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        build();
    }

    @Override
    protected void onDestroy() {
        if (open.get() == this) open = new WeakReference<>(null);
        super.onDestroy();
    }

    private void build() {
        SharedPreferences p = Store.p(this);
        long now = System.currentTimeMillis();
        Monitor.Problem pr = Monitor.problem(this, now);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setBackgroundColor(0xFFB71C1C);
        l.setPadding(pad, pad * 4, pad, pad);

        TextView t = new TextView(this);
        t.setText(pr == null ? "All clear" : pr.title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(32);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        l.addView(t);

        TextView s = new TextView(this);
        s.setText(pr == null ? "" : pr.detail);
        s.setTextColor(Color.WHITE);
        s.setTextSize(18);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, pad, 0, pad);
        l.addView(s);

        TextView g = new TextView(this);
        g.setText(Alerts.summary(this, now));
        g.setTextColor(0xFFFFCDD2);
        g.setTextSize(16);
        g.setGravity(Gravity.CENTER);
        g.setPadding(0, 0, 0, pad * 2);
        l.addView(g);

        int mins = p.getInt(Store.REALARM_MIN, Store.DEF_REALARM_MIN);
        l.addView(button("I'm on it: silence " + mins + " min", () -> Monitor.silence(this)));
        l.addView(button("Open CamAPS", () -> {
            Monitor.silence(this);
            Monitor.launchCamaps(this, "alarm screen");
        }));
        if (pr == null || pr.snoozable) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int h : new int[]{1, 2, 4, 8}) {
                Button bt = button("Snooze " + h + "h", () -> Monitor.snooze(this, h));
                row.addView(bt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            }
            l.addView(row);
        }
        setContentView(l);
    }

    private Button button(String text, Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setOnClickListener(v -> {
            r.run();
            finish();
        });
        return b;
    }
}
