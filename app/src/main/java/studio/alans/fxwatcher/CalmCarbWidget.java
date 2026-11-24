package studio.alans.fxwatcher;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

/**
 * One widget for the calm status and the carbs: how things are (dot, word, why), four quick amounts
 * coloured by size (the plan from Meal Log first), and a stepper (minus 5, amount, plus 5) with a Set
 * button. Every amount goes the same way as the carbs widget: CarbFillActivity, then CarbFill, which
 * only types into CamAPS's Carbohydrates box and never confirms or delivers.
 */
public class CalmCarbWidget extends AppWidgetProvider {

    static final String STEP = "studio.alans.fxwatcher.action.CARB_STEP";
    private static final int[] QUICK = {R.id.cc_q1, R.id.cc_q2, R.id.cc_q3, R.id.cc_q4};
    private static final int[] BANDS = {R.drawable.carb_btn_small, R.drawable.carb_btn_snack, R.drawable.carb_btn_meal, R.drawable.carb_btn_big};
    private static final int[] CARDS = {0, 0, 0, 0, 0};

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        push(c, mgr, ids, CalmState.compute(Calm.input(c, System.currentTimeMillis())));
    }

    static int stepAmount(Context c) {
        return CarbOptions.stepped(Store.p(c).getInt(Store.CARB_STEP, CarbOptions.STEP_DEFAULT), 0);
    }

    static void update(Context c) {
        update(c, CalmState.compute(Calm.input(c, System.currentTimeMillis())));
    }

    static void update(Context c, CalmState.Result r) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, CalmCarbWidget.class));
            if (ids != null && ids.length > 0) push(c, mgr, ids, r);
        } catch (Exception e) {
            android.util.Log.w("FXWatcher", "calm carb widget", e);
        }
    }

    private static PendingIntent step(Context c, int delta, int req) {
        Intent i = new Intent(c, CarbStepReceiver.class).setAction(STEP).putExtra("delta", delta);
        return PendingIntent.getBroadcast(c, 800 + req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void push(Context c, AppWidgetManager mgr, int[] ids, CalmState.Result r) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_calm_carbs);
        String colour = r.stale ? CalmState.GREY : r.color;
        v.setInt(R.id.cc_dot, "setColorFilter", Color.parseColor(colour));
        v.setTextViewText(R.id.cc_word, r.stale ? "No data" : r.word);
        v.setTextColor(R.id.cc_word, Color.parseColor(colour));
        v.setTextViewText(R.id.cc_text, r.text.isEmpty() ? (r.stale ? "No recent reading" : "Nothing needs you") : r.text);
        v.setInt(R.id.cc_root, "setBackgroundResource", CalmWidget.card(r));
        v.setOnClickPendingIntent(R.id.cc_status, Calm.open(c));

        List<CarbOptions.Option> opts = CarbWidget.options(c);
        for (int s = 0; s < QUICK.length; s++) {
            if (s < opts.size()) {
                CarbOptions.Option o = opts.get(s);
                v.setViewVisibility(QUICK[s], View.VISIBLE);
                v.setTextViewText(QUICK[s], CarbWidget.face(o));
                v.setInt(QUICK[s], "setBackgroundResource", o.plan ? R.drawable.carb_btn_plan : BANDS[CarbOptions.band(o.grams)]);
                v.setTextColor(QUICK[s], o.plan || CarbOptions.band(o.grams) >= 2 ? 0xFFFFFFFF : 0xFF1B1B1B);
                v.setOnClickPendingIntent(QUICK[s], CarbWidget.tap(c, o, 10 + s));
            } else {
                v.setViewVisibility(QUICK[s], View.GONE);
            }
        }

        int amount = stepAmount(c);
        boolean on = CarbFill.enabled(c);
        v.setTextViewText(R.id.cc_amount, amount + " g");
        v.setOnClickPendingIntent(R.id.cc_down, step(c, -CarbOptions.STEP, 0));
        v.setOnClickPendingIntent(R.id.cc_up, step(c, CarbOptions.STEP, 1));
        v.setTextViewText(R.id.cc_set, on ? "Set " + amount + " g in CamAPS" : "Turn on carbs in Settings");
        v.setInt(R.id.cc_set, "setBackgroundResource", on ? R.drawable.carb_btn_plan : R.drawable.carb_btn_off);
        v.setOnClickPendingIntent(R.id.cc_set, CarbWidget.tap(c, new CarbOptions.Option(amount + " g", amount, false), 20));
        mgr.updateAppWidget(ids, v);
    }
}
