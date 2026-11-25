package studio.alans.fxwatcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The widget's three-dots menu: your snack amounts for the bolus calculator and low treatment amounts in
 * one tap, then the slow meal and the slider. Snack amounts under 20 g are hidden while low or heading
 * low. An app widget can't take a long press, so this is the other way in. Each row asks CarbFill (which
 * never delivers) or opens the carbs sheet.
 */
public class CarbMenuActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000);
        root.setOnClickListener(v -> finish());
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.sheet_bg);
        card.setClickable(true);
        card.setPadding(dp(18), dp(10), dp(18), dp(20));
        root.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        long now = System.currentTimeMillis();
        CalmState.Input in = Calm.input(this, now);
        boolean isLow = CarbOptions.lowOffered(in.glucose, in.glucoseAt, CalmState.compute(in).state, now);
        List<Integer> snacks = CarbOptions.snacks(
                Store.p(this).getString(Store.CARB_SNACKS, CarbOptions.DEFAULT_SNACKS), isLow);
        if (!snacks.isEmpty()) {
            card.addView(label("Bolus calculator"));
            card.addView(chips(snacks, R.drawable.pill_meal, CarbFill.Kind.MEAL));
        }

        card.addView(label("Low treatment, no bolus"));
        List<Integer> lows = new ArrayList<>();
        for (int g : CarbOptions.LOW_CHIPS) lows.add(g);
        card.addView(chips(lows, R.drawable.pill_low, CarbFill.Kind.LOW));

        card.addView(item("Slowly absorbed meal\u2026", CarbSheetActivity.SLOW));
        card.addView(item("Carbs slider\u2026", CarbSheetActivity.MEAL));
        setContentView(root);
    }

    private LinearLayout chips(List<Integer> amounts, int bg, CarbFill.Kind kind) {
        LinearLayout line = new LinearLayout(this);
        for (int g : amounts) {
            TextView chip = row(g + " g", bg, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
            lp.setMargins(dp(4), dp(6), dp(4), dp(6));
            line.addView(chip, lp);
            chip.setOnClickListener(v -> {
                String no = CarbFill.request(this, g, "menu", kind);
                if (no != null) Toast.makeText(this, no, Toast.LENGTH_LONG).show();
                finish();
            });
        }
        return line;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(0xFF9AA3AD);
        t.setPadding(dp(4), dp(8), 0, 0);
        return t;
    }

    private TextView row(String s, int bg, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(17);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackgroundResource(bg);
        return t;
    }

    private TextView item(String s, String mode) {
        TextView t = row(s, R.drawable.chip_more, false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            startActivity(new Intent(this, CarbSheetActivity.class).putExtra(CarbSheetActivity.EXTRA_MODE, mode));
            finish();
        });
        return t;
    }
}
