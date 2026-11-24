package studio.alans.fxwatcher;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

/**
 * One slim row: the calm state's dot and word, your three common carb amounts and a three-dots menu.
 * A tap on an amount starts the bolus-calculator fill for it (a plan from Meal Log comes first, in teal).
 * When glucose is under 5.0 or heading low, the last amount becomes a purple Low that opens the sheet in
 * its low mode. The three dots open low treatments, the slow meal and the slider. Nothing is typed until
 * CarbFill runs, and it never confirms or delivers.
 */
public class CalmCarbWidget extends AppWidgetProvider {

    private static final int[] CHIPS = {R.id.cc_c1, R.id.cc_c2, R.id.cc_c3};

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

    private static CharSequence face(String top, String num) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(num);
        sb.setSpan(new RelativeSizeSpan(1.1f), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (top != null) {
            int a = sb.length();
            sb.append(" ").append(top);
            sb.setSpan(new RelativeSizeSpan(0.7f), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static void push(Context c, AppWidgetManager mgr, int[] ids, CalmState.Result r) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_calm_carbs);
        long now = System.currentTimeMillis();
        String colour = r.stale ? CalmState.GREY : r.color;
        v.setInt(R.id.cc_dot, "setColorFilter", Color.parseColor(colour));
        v.setTextViewText(R.id.cc_word, r.stale ? "No data" : r.word);
        v.setTextColor(R.id.cc_word, Color.parseColor(colour));
        v.setInt(R.id.cc_root, "setBackgroundResource", CalmWidget.card(r));
        v.setOnClickPendingIntent(R.id.cc_status, Calm.open(c));

        CalmState.Input in = Calm.input(c, now);
        boolean low = CarbFill.enabled(c) && CarbOptions.lowOffered(in.glucose, in.glucoseAt, r.state, now);
        List<CarbOptions.Option> opts = CarbWidget.options(c);
        int slots = low ? CHIPS.length - 1 : CHIPS.length;
        for (int s = 0; s < CHIPS.length; s++) {
            if (low && s == CHIPS.length - 1) {
                v.setViewVisibility(CHIPS[s], View.VISIBLE);
                v.setTextViewText(CHIPS[s], "Low");
                v.setInt(CHIPS[s], "setBackgroundResource", R.drawable.chip_low);
                v.setOnClickPendingIntent(CHIPS[s], PendingIntent.getActivity(c, 950,
                        new Intent(c, CarbSheetActivity.class).putExtra(CarbSheetActivity.EXTRA_MODE, CarbSheetActivity.LOW)
                                .putExtra(CarbSheetActivity.EXTRA_GRAMS, CarbOptions.LOW_DEFAULT_G)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else if (s < slots && s < opts.size()) {
                CarbOptions.Option o = opts.get(s);
                v.setViewVisibility(CHIPS[s], View.VISIBLE);
                v.setTextViewText(CHIPS[s], face(o.plan ? "plan" : null, String.valueOf(o.grams)));
                v.setInt(CHIPS[s], "setBackgroundResource", o.plan ? R.drawable.chip_plan : R.drawable.chip_small);
                v.setOnClickPendingIntent(CHIPS[s], CarbWidget.tap(c, o, 30 + s));
            } else {
                v.setViewVisibility(CHIPS[s], View.GONE);
            }
        }
        v.setOnClickPendingIntent(R.id.cc_more, PendingIntent.getActivity(c, 951,
                new Intent(c, CarbMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        mgr.updateAppWidget(ids, v);
    }
}
