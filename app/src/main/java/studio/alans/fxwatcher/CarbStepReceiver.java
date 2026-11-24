package studio.alans.fxwatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The combined widget's minus 5 and plus 5. Only moves the number the widget shows; nothing is sent to CamAPS. */
public final class CarbStepReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!CalmCarbWidget.STEP.equals(i.getAction())) return;
        int next = CarbOptions.stepped(CalmCarbWidget.stepAmount(c), i.getIntExtra("delta", 0));
        Store.p(c).edit().putInt(Store.CARB_STEP, next).apply();
        CalmCarbWidget.update(c);
    }
}
