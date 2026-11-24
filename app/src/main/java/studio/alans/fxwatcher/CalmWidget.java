package studio.alans.fxwatcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.widget.RemoteViews;

/**
 * Home-screen widget: a coloured dot and one word. No glucose number. Tap opens CamAPS.
 * Updated whenever the calm state is recomputed (Calm.refresh); it never polls on its own.
 */
public class CalmWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        CalmState.Result r = CalmState.compute(Calm.input(c, System.currentTimeMillis()));
        push(c, mgr, ids, r);
    }

    /** Called by Calm after every recompute. */
    static void update(Context c, CalmState.Result r) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, CalmWidget.class));
            if (ids != null && ids.length > 0) push(c, mgr, ids, r);
        } catch (Exception e) {
            android.util.Log.w("FXWatcher", "calm widget", e);
        }
    }

    private static void push(Context c, AppWidgetManager mgr, int[] ids, CalmState.Result r) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_calm);
        v.setTextViewText(R.id.calm_word, r.stale ? "No data" : r.word);
        v.setInt(R.id.calm_dot, "setColorFilter", Color.parseColor(r.stale ? CalmState.GREY : r.color));
        v.setOnClickPendingIntent(R.id.calm_root, Calm.open(c));
        mgr.updateAppWidget(ids, v);
    }
}
