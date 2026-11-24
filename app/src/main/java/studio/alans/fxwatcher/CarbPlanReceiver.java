package studio.alans.fxwatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * An app (Meal Log) tells FX Watcher the carbs of the plan it is showing, so the widget can offer
 * them. Sender needs FX Watcher's bolus permission (declared on the receiver). It only stores
 * amounts that pass CarbOptions; nothing is typed until you tap one.
 *
 *   studio.alans.fxwatcher.action.SET_PLAN    extra "items" = "Plan|45;Usual|60"
 *   studio.alans.fxwatcher.action.CLEAR_PLAN
 */
public final class CarbPlanReceiver extends BroadcastReceiver {

    static final String SET = "studio.alans.fxwatcher.action.SET_PLAN";
    static final String CLEAR = "studio.alans.fxwatcher.action.CLEAR_PLAN";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        long now = System.currentTimeMillis();
        if (CLEAR.equals(a)) {
            Store.p(c).edit().remove(Store.CARB_PLAN).apply();
            Store.log(c, "Plan cleared by the sending app");
        } else if (SET.equals(a)) {
            CarbOptions.Plan plan = CarbOptions.fromBroadcast(i.getStringExtra("items"), now);
            if (plan.items.isEmpty()) {
                Store.log(c, "Plan from an app had no usable amounts; ignored");
                return;
            }
            Store.p(c).edit().putString(Store.CARB_PLAN, plan.save()).apply();
            Store.log(c, "Plan received: " + plan.items.size() + " amount(s)");
        } else {
            return;
        }
        CarbWidget.update(c);
    }
}
