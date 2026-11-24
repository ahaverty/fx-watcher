package studio.alans.fxwatcher;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The carbs sheet, for when the widget's quick amounts are not the amount. A segmented switch for what
 * the number is (Meal, Slow meal, Low), a big number, a real slider with haptic ticks, quick chips and one
 * button. Each mode has its own colour and its own range; the big button asks CarbFill, which types the
 * number and stops.
 */
public class CarbSheetActivity extends Activity {

    static final String EXTRA_MODE = "mode", EXTRA_GRAMS = "grams";
    static final String MEAL = "meal", SLOW = "slow", LOW = "low";
    private static final int CARD = 0xFF1E222A, MUTED = 0xFF9AA3AD;

    private String mode = MEAL;
    private int grams;
    private LinearLayout card;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String m = getIntent().getStringExtra(EXTRA_MODE);
        mode = SLOW.equals(m) ? SLOW : LOW.equals(m) ? LOW : MEAL;
        grams = start(mode, getIntent().getIntExtra(EXTRA_GRAMS, -1));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> finish());
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.sheet_bg);
        card.setClickable(true);
        card.setPadding(dp(20), dp(10), dp(20), dp(22));
        root.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        render();
    }

    static CarbFill.Kind kind(String mode) {
        return LOW.equals(mode) ? CarbFill.Kind.LOW : SLOW.equals(mode) ? CarbFill.Kind.SLOW : CarbFill.Kind.MEAL;
    }

    static int min(String mode) {
        return MEAL.equals(mode) ? CarbOptions.STEP_MIN : SLOW.equals(mode) ? CarbOptions.STEP_MIN : CarbOptions.LOW_MIN_G;
    }

    static int max(String mode) {
        return LOW.equals(mode) ? CarbOptions.LOW_MAX_G : CarbOptions.STEP_MAX;
    }

    /** Where the slider starts: what was asked if it fits the mode, else a sensible default for it. */
    static int start(String mode, int asked) {
        if (LOW.equals(mode)) return CarbOptions.validLow(asked) ? asked : CarbOptions.LOW_DEFAULT_G;
        if (CarbOptions.valid(asked) && asked >= CarbOptions.STEP_MIN) return CarbOptions.stepped(asked, 0);
        return 30;
    }

    private int accent() {
        return LOW.equals(mode) ? 0xFF9B7BE0 : SLOW.equals(mode) ? 0xFF5B9BE0 : 0xFF1FA89A;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void render() {
        card.removeAllViews();
        int accent = accent();

        View grab = new View(this);
        grab.setBackgroundResource(R.drawable.carb_stepper);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(36), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(14);
        card.addView(grab, gp);

        LinearLayout seg = new LinearLayout(this);
        seg.setBackgroundResource(R.drawable.carb_stepper);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.addView(segment("Meal", MEAL, R.drawable.carb_set));
        seg.addView(segment("Slow meal", SLOW, R.drawable.carb_set_slow));
        seg.addView(segment("Low", LOW, R.drawable.carb_set_low));
        card.addView(seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        TextView amount = text(String.valueOf(grams), 60, accent, true);
        amount.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(14);
        card.addView(amount, ap);
        TextView unit = text("g", 15, MUTED, false);
        unit.setGravity(Gravity.CENTER);
        card.addView(unit);

        int min = min(mode), max = max(mode);
        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / CarbOptions.STEP);
        bar.setProgress((grams - min) / CarbOptions.STEP);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        sp.topMargin = dp(10);
        card.addView(bar, sp);

        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER);
        card.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView go = text(goLabel(), 17, 0xFFFFFFFF, true);
        go.setGravity(Gravity.CENTER);
        go.setBackgroundResource(LOW.equals(mode) ? R.drawable.carb_set_low : SLOW.equals(mode) ? R.drawable.carb_set_slow : R.drawable.carb_set);
        LinearLayout.LayoutParams gp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        gp2.topMargin = dp(14);
        card.addView(go, gp2);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                grams = min + p * CarbOptions.STEP;
                amount.setText(String.valueOf(grams));
                go.setText(goLabel());
                if (fromUser) s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) { }
        });

        int chipBg = LOW.equals(mode) ? R.drawable.carb_chip_low : SLOW.equals(mode) ? R.drawable.carb_chip_slow : R.drawable.carb_chip_snack;
        List<CarbOptions.Option> quick = LOW.equals(mode) ? lowChips() : CarbWidget.options(this);
        for (int i = 0; i < quick.size() && i < 4; i++) {
            CarbOptions.Option o = quick.get(i);
            TextView chip = text(o.plan ? "Plan " + o.grams : String.valueOf(o.grams), 15, 0xFFFFFFFF, o.plan);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(o.plan && MEAL.equals(mode) ? R.drawable.carb_chip_plan : chipBg);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            cp.setMargins(dp(4), dp(6), dp(4), dp(6));
            chips.addView(chip, cp);
            chip.setOnClickListener(v -> {
                grams = Math.max(min, Math.min(max, CarbOptions.stepped(o.grams, 0)));
                bar.setProgress((grams - min) / CarbOptions.STEP);
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            });
        }

        go.setOnClickListener(v -> {
            String no = CarbFill.request(this, grams, mode + " sheet", kind(mode));
            if (no != null) {
                Toast.makeText(this, no, Toast.LENGTH_LONG).show();
                return;
            }
            if (MEAL.equals(mode)) Store.p(this).edit().putInt(Store.CARB_STEP, grams).apply();
            finish();
        });
    }

    private static List<CarbOptions.Option> lowChips() {
        List<CarbOptions.Option> l = new ArrayList<>();
        for (int g : CarbOptions.LOW_CHIPS) l.add(new CarbOptions.Option(g + " g", g, false));
        return l;
    }

    private String goLabel() {
        return LOW.equals(mode) ? "Treat " + grams + " g" : SLOW.equals(mode) ? "Slow meal " + grams + " g" : "Put " + grams + " g in CamAPS";
    }

    private TextView segment(String s, String m, int onBg) {
        boolean on = mode.equals(m);
        TextView t = text(s, 15, on ? 0xFFFFFFFF : MUTED, on);
        t.setGravity(Gravity.CENTER);
        if (on) t.setBackgroundResource(onBg);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        t.setOnClickListener(v -> {
            if (!mode.equals(m)) {
                mode = m;
                grams = start(mode, LOW.equals(m) ? CarbOptions.LOW_DEFAULT_G : Store.p(this).getInt(Store.CARB_STEP, 30));
                render();
            }
        });
        return t;
    }
}
