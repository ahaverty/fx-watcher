package studio.alans.fxwatcher;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.List;

/**
 * Home-screen widget of carb amounts: the plan Meal Log sent (if fresh), then your presets. One
 * tap opens CamAPS and fills the carbs into its calculator, if "Carbs for CamAPS" is on. It never
 * confirms or delivers. Updated when the plan or the presets change.
 */
public class CarbWidget extends AppWidgetProvider {

    private static final int[] SLOTS = {R.id.carb_1, R.id.carb_2, R.id.carb_3, R.id.carb_4, R.id.carb_5, R.id.carb_6};

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        push(c, mgr, ids);
    }

    static List<CarbOptions.Option> options(Context c) {
        android.content.SharedPreferences p = Store.p(c);
        return CarbOptions.options(CarbOptions.Plan.load(p.getString(Store.CARB_PLAN, "")),
                CarbOptions.parsePresets(p.getString(Store.CARB_PRESETS, CarbOptions.DEFAULT_PRESETS)),
                System.currentTimeMillis());
    }

    /** The intent a tap on an amount fires. Explicit and immutable, so only this amount can be sent. */
    static PendingIntent tap(Context c, CarbOptions.Option o, int slot) {
        Intent i = new Intent(c, CarbFillActivity.class)
                .putExtra(CarbFillActivity.EXTRA_GRAMS, o.grams)
                .putExtra(CarbFillActivity.EXTRA_LABEL, o.label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return PendingIntent.getActivity(c, 700 + slot, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void update(Context c) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, CarbWidget.class));
            if (ids != null && ids.length > 0) push(c, mgr, ids);
        } catch (Exception e) {
            android.util.Log.w("FXWatcher", "carb widget", e);
        }
    }

    private static void push(Context c, AppWidgetManager mgr, int[] ids) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_carbs);
        List<CarbOptions.Option> opts = options(c);
        boolean on = CarbFill.enabled(c);
        v.setTextViewText(R.id.carb_title, on ? "Carbs to CamAPS" : "Carbs (turn on in Settings)");
        for (int s = 0; s < SLOTS.length; s++) {
            if (s < opts.size()) {
                CarbOptions.Option o = opts.get(s);
                v.setViewVisibility(SLOTS[s], android.view.View.VISIBLE);
                v.setTextViewText(SLOTS[s], o.label);
                v.setOnClickPendingIntent(SLOTS[s], tap(c, o, s));
            } else {
                v.setViewVisibility(SLOTS[s], android.view.View.GONE);
            }
        }
        mgr.updateAppWidget(ids, v);
    }
}
