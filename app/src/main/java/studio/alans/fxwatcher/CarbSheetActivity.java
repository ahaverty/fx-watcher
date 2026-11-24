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

import java.util.List;

/**
 * The carbs sheet: a bottom sheet from the widget with a real slider. Two modes, never mixed:
 * Meal (teal, 5 to 120 g, goes to the bolus calculator) and Low treatment (coral, 5 to 30 g, goes to
 * Add meal, Hypoglycaemia treatment). The big button asks CarbFill, which types the number and stops.
 * Low treatment opens straight in coral when the widget's Treat low is tapped.
 */
public class CarbSheetActivity extends Activity {

    static final String EXTRA_LOW = "low", EXTRA_GRAMS = "grams";
    private static final int TEAL = 0xFF1FA89A, CORAL = 0xFFF26B5B, CARD = 0xFF1E222A, MUTED = 0xFF9AA3AD;

    private boolean low;
    private int grams;
    private LinearLayout card;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        low = getIntent().getBooleanExtra(EXTRA_LOW, false);
        grams = start(low, getIntent().getIntExtra(EXTRA_GRAMS, -1));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> finish());
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.sheet_bg);
        card.setClickable(true);
        int pad = dp(20);
        card.setPadding(pad, dp(10), pad, dp(22));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(card, lp);
        setContentView(root);
        render();
    }

    /** Where the slider starts: what was asked, else the plan, else the last meal amount; low starts at 15. */
    static int start(boolean low, int asked) {
        if (low) return CarbOptions.validLow(asked) ? asked : CarbOptions.LOW_DEFAULT_G;
        return CarbOptions.valid(asked) ? CarbOptions.stepped(asked, 0) : 30;
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
        int accent = low ? CORAL : TEAL;

        View grab = new View(this);
        grab.setBackgroundResource(R.drawable.carb_stepper);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(36), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(14);
        card.addView(grab, gp);

        LinearLayout seg = new LinearLayout(this);
        seg.setBackgroundResource(R.drawable.carb_stepper);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.addView(segment("Meal", !low, TEAL, false));
        seg.addView(segment("Low treatment", low, CORAL, true));
        card.addView(seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        if (low) {
            TextView banner = text("Low treatment. No insulin. It selects Hypoglycaemia treatment in CamAPS's Add meal and types the amount.", 13, 0xFFFFB3A8, false);
            banner.setBackgroundResource(R.drawable.carb_chip_low);
            banner.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(12);
            card.addView(banner, bp);
        }

        TextView amount = text(String.valueOf(grams), 56, accent, true);
        amount.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(10);
        card.addView(amount, ap);
        TextView sub = text(label(), 14, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        card.addView(sub);

        int min = low ? CarbOptions.LOW_MIN_G : CarbOptions.STEP_MIN;
        int max = low ? CarbOptions.LOW_MAX_G : CarbOptions.STEP_MAX;
        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / CarbOptions.STEP);
        bar.setProgress((grams - min) / CarbOptions.STEP);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        sp.topMargin = dp(8);
        card.addView(bar, sp);

        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER);
        card.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView go = text(goLabel(), 17, 0xFFFFFFFF, true);
        go.setGravity(Gravity.CENTER);
        go.setBackgroundResource(low ? R.drawable.carb_set_low : R.drawable.carb_set);
        LinearLayout.LayoutParams gp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        gp2.topMargin = dp(14);
        card.addView(go, gp2);
        TextView note = text(low ? "It stops on the Add meal page. You check it and tap Continue." : "It types the number and stops. You confirm and deliver in CamAPS.", 12, MUTED, false);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(8);
        card.addView(note, np);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                grams = min + p * CarbOptions.STEP;
                amount.setText(String.valueOf(grams));
                sub.setText(label());
                go.setText(goLabel());
                if (fromUser) s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) { }
        });

        List<CarbOptions.Option> quick = low ? lowChips() : CarbWidget.options(this);
        for (int i = 0; i < quick.size() && i < 4; i++) {
            CarbOptions.Option o = quick.get(i);
            TextView chip = text(o.plan ? "Plan " + o.grams : String.valueOf(o.grams), 15, 0xFFFFFFFF, o.plan);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(low ? R.drawable.carb_chip_low : o.plan ? R.drawable.carb_chip_plan : R.drawable.carb_chip_snack);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            cp.setMargins(dp(4), dp(6), dp(4), dp(6));
            chips.addView(chip, cp);
            chip.setOnClickListener(v -> {
                grams = low ? o.grams : CarbOptions.stepped(o.grams, 0);
                bar.setProgress((grams - min) / CarbOptions.STEP);
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            });
        }

        go.setOnClickListener(v -> {
            String no = CarbFill.request(this, grams, low ? "low sheet" : "meal sheet", low ? CarbFill.Kind.LOW : CarbFill.Kind.MEAL);
            if (no != null) {
                Toast.makeText(this, no, Toast.LENGTH_LONG).show();
                return;
            }
            if (!low) Store.p(this).edit().putInt(Store.CARB_STEP, grams).apply();
            finish();
        });
    }

    private static List<CarbOptions.Option> lowChips() {
        java.util.ArrayList<CarbOptions.Option> l = new java.util.ArrayList<>();
        for (int g : CarbOptions.LOW_CHIPS) l.add(new CarbOptions.Option(g + " g", g, false));
        return l;
    }

    private String label() {
        if (low) return "g of fast carbs";
        return grams <= 15 ? "Small snack" : grams <= 30 ? "Light meal" : grams <= 50 ? "Medium meal" : "Big meal";
    }

    private String goLabel() {
        return low ? "Treat low: " + grams + " g" : "Put " + grams + " g in CamAPS";
    }

    private TextView segment(String s, boolean on, int color, boolean lowSeg) {
        TextView t = text(s, 15, on ? 0xFFFFFFFF : MUTED, on);
        t.setGravity(Gravity.CENTER);
        if (on) t.setBackgroundResource(lowSeg ? R.drawable.carb_set_low : R.drawable.carb_set);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        t.setOnClickListener(v -> {
            if (low != lowSeg) {
                low = lowSeg;
                grams = start(low, low ? CarbOptions.LOW_DEFAULT_G : Store.p(this).getInt(Store.CARB_STEP, 30));
                render();
            }
        });
        return t;
    }
}
