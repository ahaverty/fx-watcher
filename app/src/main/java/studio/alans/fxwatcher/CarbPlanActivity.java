package studio.alans.fxwatcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Another app (Meal Log) asks FX Watcher to run a plan in CamAPS. Nothing starts from the other app alone:
 * this screen lists every step, in the order they will run, and one tap on Run here starts it. The steps
 * are checked again by CarbFill (order, ranges, at most one of each).
 *
 *   studio.alans.fxwatcher.action.RUN_PLAN   extra "steps" = "SLOW:15;BOOST:120;MEAL:45"   (BOOST, EASE in minutes)
 *                                            extra "correction" = true to also confirm the glucose
 */
public class CarbPlanActivity extends Activity {

    static final String EXTRA_STEPS = "steps", EXTRA_CORRECTION = "correction";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        List<CarbFill.Item> items = CarbFill.parseSteps(getIntent().getStringExtra(EXTRA_STEPS), "plan");
        boolean correction = getIntent().getBooleanExtra(EXTRA_CORRECTION, false);
        String bad = items == null ? "That plan couldn't be read." : CarbFill.validatePlan(items);
        if (bad == null) bad = CarbFill.refusal(this, items.get(0).amount, items.get(0).kind);
        if (bad != null) {
            Toast.makeText(this, bad, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (CarbFill.Item it : items) {
            String d = CarbFill.describe(it);
            sb.append(n++).append(". ").append(d.substring(0, 1).toUpperCase(Locale.UK)).append(d.substring(1)).append('\n');
        }
        boolean meal = items.get(items.size() - 1).kind == CarbFill.Kind.MEAL;
        sb.append(meal ? "\nIt stops on the bolus screen. You tap Next and Deliver." : "\nNothing else is delivered.");
        new AlertDialog.Builder(this)
                .setTitle("Run in CamAPS?")
                .setMessage(sb.toString())
                .setPositiveButton("Run", (dlg, w) -> {
                    String no = CarbFill.runPlan(this, items, correction);
                    if (no != null) Toast.makeText(this, no, Toast.LENGTH_LONG).show();
                    finish();
                })
                .setNegativeButton("Cancel", (dlg, w) -> finish())
                .setOnCancelListener(dlg -> finish())
                .show();
    }
}
