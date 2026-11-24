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

/**
 * The widget's three-dots menu: low treatment amounts in one tap, then the slow meal and the slider.
 * An app widget can't take a long press, so this is the other way in. Each row asks CarbFill (which only
 * types a number and stops) or opens the carbs sheet.
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

        card.addView(label("Low treatment"));
        LinearLayout lows = new LinearLayout(this);
        for (int g : CarbOptions.LOW_CHIPS) {
            TextView chip = row(g + " g", R.drawable.pill_low, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
            lp.setMargins(dp(4), dp(6), dp(4), dp(6));
            lows.addView(chip, lp);
            chip.setOnClickListener(v -> {
                String no = CarbFill.request(this, g, "menu", CarbFill.Kind.LOW);
                if (no != null) Toast.makeText(this, no, Toast.LENGTH_LONG).show();
                finish();
            });
        }
        card.addView(lows);

        card.addView(item("Slowly absorbed meal\u2026", CarbSheetActivity.SLOW));
        card.addView(item("Carbs slider\u2026", CarbSheetActivity.MEAL));
        setContentView(root);
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
