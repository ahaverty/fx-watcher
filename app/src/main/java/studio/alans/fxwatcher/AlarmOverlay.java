package studio.alans.fxwatcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A big silence/snooze card drawn over whatever app is open while the alarm is ringing, so it can
 * be stopped without finding the notification. Uses "Display over other apps" (already in Setup).
 * Android keeps overlays below the lock screen; there the full-screen AlarmActivity does the job,
 * and while that screen is in front the card steps aside so the buttons aren't shown twice.
 */
final class AlarmOverlay {

    private AlarmOverlay() { }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static View view;
    private static String shownKey;
    private static boolean alarmScreen;
    private static Context app;
    private static Monitor.Problem last;

    /** Shows or refreshes the card for this alarm. Safe from any thread. */
    static void show(Context c, Monitor.Problem pr) {
        app = c.getApplicationContext();
        last = pr;
        main.post(AlarmOverlay::sync);
    }

    static void hide() {
        last = null;
        main.post(AlarmOverlay::sync);
    }

    /** AlarmActivity is in front (true) or has gone (false). */
    static void alarmScreen(boolean inFront) {
        main.post(() -> {
            alarmScreen = inFront;
            sync();
        });
    }

    private static void sync() {
        Monitor.Problem pr = last;
        boolean want = pr != null && !alarmScreen && app != null && Settings.canDrawOverlays(app);
        String key = pr == null ? null : pr.code + "|" + pr.title + "|" + pr.detail;
        if (view != null && (!want || !key.equals(shownKey))) {
            try {
                app.getSystemService(WindowManager.class).removeView(view);
            } catch (Exception ignored) { }
            view = null;
            shownKey = null;
        }
        if (!want || view != null) return;
        try {
            View v = build(app, pr);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            app.getSystemService(WindowManager.class).addView(v, lp);
            view = v;
            shownKey = key;
        } catch (Exception e) {
            Store.log(app, "Alarm overlay failed: " + e);
        }
    }

    private static View build(Context c, Monitor.Problem pr) {
        float d = c.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0B71C1C);
        bg.setCornerRadius(24 * d);
        l.setBackground(bg);

        TextView t = new TextView(c);
        t.setText(pr.title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        l.addView(t);

        TextView s = new TextView(c);
        s.setText(pr.detail);
        s.setTextColor(0xFFFFCDD2);
        s.setTextSize(16);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, pad / 2, 0, pad);
        l.addView(s);

        int mins = Store.p(c).getInt(Store.REALARM_MIN, Store.DEF_REALARM_MIN);
        Button silence = button(c, "Silence " + mins + " min", () -> Monitor.silence(c));
        silence.setTextSize(22);
        silence.setMinHeight((int) (88 * d));
        l.addView(silence, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (pr.snoozable) {
            for (int h : new int[]{1, 2}) {
                row.addView(button(c, "Snooze " + h + "h", () -> Monitor.snooze(c, h)),
                        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            }
        }
        row.addView(button(c, "Open CamAPS", () -> {
            Monitor.silence(c);
            Monitor.launchCamaps(c, "alarm overlay");
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        l.addView(row);

        LinearLayout wrap = new LinearLayout(c);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private static Button button(Context c, String text, Runnable r) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(16);
        b.setOnClickListener(v -> {
            Store.log(c, "Alarm overlay: " + text);
            hide();
            r.run();
        });
        return b;
    }
}
