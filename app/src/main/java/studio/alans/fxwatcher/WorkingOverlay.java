package studio.alans.fxwatcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A thin coloured line along the top and bottom of the screen while Carbs for CamAPS is tapping
 * through CamAPS, so you can tell at a glance whether to keep your hands off (red, pulsing) or
 * take over (green: your turn; amber: it stopped). No notification.
 *
 * Two small strips, never one full-screen window, and both are not touchable and not focusable:
 * your taps and FX Watcher's own clicks (accessibility actions, not touches) go straight to CamAPS,
 * and the middle of the screen, where CamAPS's buttons are, is never covered. The window alpha is
 * kept at 0.8, the most Android 12+ allows for touches to pass through another app's overlay.
 */
final class WorkingOverlay {

    private WorkingOverlay() { }

    enum State { WORKING, DONE, STOPPED }

    static final int RED = 0xFFE53935, GREEN = 0xFF43A047, AMBER = 0xFFFFA000;
    private static final long DONE_SHOW_MS = 5_000L, STOPPED_SHOW_MS = 8_000L, WATCH_MS = 2_000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static View top, bottom;
    private static TextView label;
    private static View pill;
    private static ValueAnimator pulse;
    private static State state;
    private static final Runnable hideRun = WorkingOverlay::remove;
    private static final Runnable watch = new Runnable() {
        @Override public void run() {
            // the run ended without telling us (deadline passed between screens): don't leave "hands off" up
            if (state == State.WORKING && !CarbFill.active()) {
                remove();
                return;
            }
            if (state == State.WORKING) main.postDelayed(this, WATCH_MS);
        }
    };

    static boolean allowed(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** Red "hands off" line with a short label. Returns false if overlays aren't allowed (use a notification instead). Any thread. */
    static boolean working(Context c, String text) {
        if (!allowed(c)) return false;
        app = c.getApplicationContext();
        main.post(() -> set(State.WORKING, text));
        return true;
    }

    /** The run is over: green "your turn" or amber "stopped", then it goes away by itself. Returns false if nothing is shown. */
    static boolean finished(Context c, boolean ok, String text) {
        if (!allowed(c)) return false;
        app = c.getApplicationContext();
        main.post(() -> set(ok ? State.DONE : State.STOPPED, text));
        return true;
    }

    static void hide() {
        main.post(WorkingOverlay::remove);
    }

    private static void set(State s, String text) {
        main.removeCallbacks(hideRun);
        main.removeCallbacks(watch);
        if (top == null && !add()) return;
        state = s;
        int colour = s == State.WORKING ? RED : s == State.DONE ? GREEN : AMBER;
        top.findViewWithTag("line").setBackgroundColor(colour);
        bottom.findViewWithTag("line").setBackgroundColor(colour);
        ((GradientDrawable) pill.getBackground()).setColor(colour);
        label.setText(text);
        if (s == State.WORKING) {
            if (pulse == null) {
                // a steady pulse means it's alive; if FX Watcher dies its windows go with it
                pulse = ValueAnimator.ofFloat(1f, 0.45f);
                pulse.setDuration(700);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.addUpdateListener(a -> {
                    float f = (float) a.getAnimatedValue();
                    if (top != null) top.setAlpha(f);
                    if (bottom != null) bottom.setAlpha(f);
                });
            }
            if (!pulse.isStarted()) pulse.start();
            main.postDelayed(watch, WATCH_MS);
        } else {
            if (pulse != null) pulse.cancel();
            top.setAlpha(1f);
            bottom.setAlpha(1f);
            main.postDelayed(hideRun, s == State.DONE ? DONE_SHOW_MS : STOPPED_SHOW_MS);
        }
    }

    private static boolean add() {
        try {
            WindowManager wm = app.getSystemService(WindowManager.class);
            int[] bars = bars(wm);
            top = strip(app, true);
            bottom = strip(app, false);
            wm.addView(top, params(Gravity.TOP, bars[0]));
            wm.addView(bottom, params(Gravity.BOTTOM, bars[1]));
            return true;
        } catch (Exception e) {
            Store.log(app, "Working overlay failed: " + e);
            remove();
            return false;
        }
    }

    private static void remove() {
        main.removeCallbacks(hideRun);
        main.removeCallbacks(watch);
        if (pulse != null) pulse.cancel();
        pulse = null;
        state = null;
        WindowManager wm = app == null ? null : app.getSystemService(WindowManager.class);
        for (View v : new View[]{top, bottom}) {
            if (v == null || wm == null) continue;
            try {
                wm.removeView(v);
            } catch (Exception ignored) { }
        }
        top = bottom = pill = null;
        label = null;
    }

    private static WindowManager.LayoutParams params(int gravity, int offset) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.y = offset;
        lp.alpha = 0.8f;
        lp.setTitle("FX Watcher working");
        return lp;
    }

    /** Status bar and navigation bar heights in px: the lines sit just inside them so they aren't hidden. */
    private static int[] bars(WindowManager wm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets in = wm.getCurrentWindowMetrics().getWindowInsets().getInsets(WindowInsets.Type.systemBars());
            return new int[]{in.top, in.bottom};
        }
        return new int[]{dimen("status_bar_height"), dimen("navigation_bar_height")};
    }

    private static int dimen(String name) {
        int id = app.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? app.getResources().getDimensionPixelSize(id) : 0;
    }

    private static View strip(Context c, boolean withLabel) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);

        View line = new View(c);
        line.setTag("line");
        line.setBackgroundColor(RED);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (4 * d));

        if (!withLabel) {
            l.addView(line, lineLp);
            return l;
        }
        l.addView(line, lineLp);
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setPadding((int) (12 * d), (int) (3 * d), (int) (12 * d), (int) (4 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(RED);
        float r = 10 * d;
        bg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        t.setBackground(bg);
        l.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        label = t;
        pill = t;
        return l;
    }
}
