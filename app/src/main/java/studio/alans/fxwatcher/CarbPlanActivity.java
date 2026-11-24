package studio.alans.fxwatcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Another app (Meal Log) asks FX Watcher to run a plan in CamAPS. Nothing starts from the other app alone:
 * this screen lists every step, in the order they will run, and one tap on Run here starts it. The steps
 * are checked again by CarbFill (order, ranges, at most one of each).
 *
 * The sender gives the plan a key. If that key already ran, this screen says so and offers to run only what
 * is left, or to run it all again, never silently a second time.
 *
 *   studio.alans.fxwatcher.action.RUN_PLAN   extra "steps" = "SLOW:15;BOOST:120;MEAL:45"   (BOOST, EASE in minutes)
 *                                            extra "key" = the plan's idempotency key (letters, digits, . _ -)
 *                                            extra "correction" = true to also confirm the glucose
 */
public class CarbPlanActivity extends Activity {

    static final String EXTRA_STEPS = "steps", EXTRA_CORRECTION = "correction", EXTRA_KEY = "key";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        List<CarbFill.Item> items = CarbFill.parseSteps(getIntent().getStringExtra(EXTRA_STEPS), "plan");
        boolean correction = getIntent().getBooleanExtra(EXTRA_CORRECTION, Store.p(this).getBoolean(Store.CARB_CORRECTION, false));
        String key = getIntent().getStringExtra(EXTRA_KEY);
        if (key != null && !PlanRuns.validKey(key)) key = null;
        String bad = items == null ? "That plan couldn't be read." : CarbFill.validatePlan(items);
        if (bad == null) bad = CarbFill.refusal(this, items.get(0).amount, items.get(0).kind);
        if (bad != null) {
            Toast.makeText(this, bad, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        long now = System.currentTimeMillis();
        PlanRuns.Run earlier = key == null ? null : PlanRuns.find(Store.p(this).getString(Store.CARB_RUNS, ""), key, now);
        final String k = key;
        AlertDialog.Builder d = new AlertDialog.Builder(this).setOnCancelListener(dlg -> finish());
        if (earlier == null) {
            d.setTitle("Run in CamAPS?").setMessage(steps(items))
                    .setPositiveButton("Run", (dlg, w) -> start(items, correction, k))
                    .setNegativeButton("Cancel", (dlg, w) -> finish());
        } else {
            String when = new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(earlier.at));
            List<CarbFill.Item> left = PlanRuns.remaining(items, earlier);
            if (earlier.complete || left.isEmpty()) {
                d.setTitle("Already run at " + when).setMessage("This plan was already run in CamAPS.\n\n" + steps(items)
                                + "\nRunning it again would repeat every step.")
                        .setPositiveButton("Cancel", (dlg, w) -> finish())
                        .setNegativeButton("Run it all again", (dlg, w) -> start(items, correction, k));
            } else {
                d.setTitle("Started at " + when + ", not finished").setMessage("Done: " + doneText(items, earlier) + "\n\nLeft to do:\n" + steps(left))
                        .setPositiveButton("Run the rest", (dlg, w) -> start(left, correction, k))
                        .setNeutralButton("Cancel", (dlg, w) -> finish())
                        .setNegativeButton("Run it all again", (dlg, w) -> start(items, correction, k));
            }
        }
        d.show();
    }

    private void start(List<CarbFill.Item> items, boolean correction, String key) {
        String no = CarbFill.runPlan(this, items, correction, key);
        if (no != null) Toast.makeText(this, no, Toast.LENGTH_LONG).show();
        finish();
    }

    private static String doneText(List<CarbFill.Item> items, PlanRuns.Run run) {
        StringBuilder sb = new StringBuilder();
        for (CarbFill.Item it : items) if (run.done.contains(it.kind.name())) sb.append(sb.length() == 0 ? "" : ", ").append(CarbFill.describe(it));
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    private static String steps(List<CarbFill.Item> items) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (CarbFill.Item it : items) {
            String d = CarbFill.describe(it);
            sb.append(n++).append(". ").append(d.substring(0, 1).toUpperCase(Locale.UK)).append(d.substring(1)).append('\n');
        }
        boolean meal = items.get(items.size() - 1).kind == CarbFill.Kind.MEAL;
        sb.append(meal ? "\nIt stops on the bolus screen. You tap Next and Deliver." : "\nNothing else is delivered.");
        return sb.toString();
    }
}
