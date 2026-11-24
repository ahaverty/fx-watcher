package studio.alans.fxwatcher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * No screen: a widget tap lands here (an activity started by your tap may open another app),
 * asks CarbFill to start, and closes. The amount comes from the tap and is checked again in
 * CarbFill, so nothing else can reach it with a different number.
 */
public class CarbFillActivity extends Activity {

    static final String EXTRA_GRAMS = "grams", EXTRA_LABEL = "label";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int grams = getIntent().getIntExtra(EXTRA_GRAMS, -1);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        String no = CarbFill.request(this, grams, label == null ? "widget" : label);
        if (no != null) Toast.makeText(this, no, Toast.LENGTH_LONG).show();
        finish();
    }
}
