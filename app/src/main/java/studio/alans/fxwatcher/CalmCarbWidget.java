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
 * The calm status and one button for carbs. The status is what the other widget shows (dot, word, the
 * reason). "Carbs" opens the carbs sheet (a fresh plan from Meal Log makes it "Plan 45 g" and preselects
 * it). "Treat low" is only there when glucose is under 5.0 or heading low; it opens the sheet in its coral
 * low-treatment mode. Nothing is sent to CamAPS from the widget itself: the sheet's big button does that.
 */
public class CalmCarbWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        push(c, mgr, ids, CalmState.compute(Calm.input(c, System.currentTimeMillis())));
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

    private static PendingIntent sheet(Context c, boolean low, int grams, int req) {
        Intent i = new Intent(c, CarbSheetActivity.class)
                .putExtra(CarbSheetActivity.EXTRA_LOW, low)
                .putExtra(CarbSheetActivity.EXTRA_GRAMS, grams)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return PendingIntent.getActivity(c, 900 + req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void push(Context c, AppWidgetManager mgr, int[] ids, CalmState.Result r) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_calm_carbs);
        long now = System.currentTimeMillis();
        String colour = r.stale ? CalmState.GREY : r.color;
        v.setInt(R.id.cc_dot, "setColorFilter", Color.parseColor(colour));
        v.setTextViewText(R.id.cc_word, r.stale ? "No data" : r.word);
        v.setTextColor(R.id.cc_word, Color.parseColor(colour));
        v.setTextViewText(R.id.cc_text, r.text.isEmpty() ? (r.stale ? "No recent reading" : "Nothing needs you") : r.text);
        v.setInt(R.id.cc_root, "setBackgroundResource", CalmWidget.card(r));
        v.setOnClickPendingIntent(R.id.cc_status, Calm.open(c));

        List<CarbOptions.Option> opts = CarbWidget.options(c);
        CarbOptions.Option plan = !opts.isEmpty() && opts.get(0).plan ? opts.get(0) : null;
        v.setTextViewText(R.id.cc_meal, plan != null ? "Plan " + plan.grams + " g" : "Carbs");
        v.setOnClickPendingIntent(R.id.cc_meal, sheet(c, false, plan != null ? plan.grams : -1, 0));

        CalmState.Input in = Calm.input(c, now);
        boolean low = CarbFill.enabled(c) && CarbOptions.lowOffered(in.glucose, in.glucoseAt, r.state, now);
        v.setViewVisibility(R.id.cc_low, low ? View.VISIBLE : View.GONE);
        v.setOnClickPendingIntent(R.id.cc_low, sheet(c, true, CarbOptions.LOW_DEFAULT_G, 1));
        mgr.updateAppWidget(ids, v);
    }
}
