package studio.alans.fxwatcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional (Settings > Connections, off by default): tells other apps on the phone about each
 * bolus FX Watcher records, so a separate app can act on it, e.g. ask what was eaten right after a
 * meal bolus, or cancel a "remember to bolus" reminder.
 *
 * For app builders: declare
 *   <uses-permission android:name="studio.alans.fxwatcher.permission.RECEIVE_BOLUS" />
 * and a manifest receiver for the action "studio.alans.fxwatcher.action.BOLUS". Extras:
 *   kind    "new" (a bolus was delivered) or "update" (the same bolus, carbs seen later)
 *   units   double, insulin delivered
 *   carbs   double, bolus calculator carbs in grams (absent if none were entered)
 *   at      long, bolus time in ms since the epoch
 *   source  String, what FX Watcher read it from ("camaps-notification", "camaps-screen")
 *   meal_size String, CamAPS meal size if shown (absent otherwise)
 * Only apps holding the permission receive it. Each one is sent its own explicit broadcast, since
 * Android no longer delivers implicit broadcasts to manifest receivers.
 */
final class BolusBroadcast {

    private BolusBroadcast() { }

    static final String ACTION = "studio.alans.fxwatcher.action.BOLUS";
    static final String PERMISSION = "studio.alans.fxwatcher.permission.RECEIVE_BOLUS";

    static void send(Context c, BolusTracker.Result r, String source, String mealSize) {
        if (!Store.p(c).getBoolean(Store.SHARE_BOLUS, false)) return;
        Intent base = new Intent(ACTION);
        List<ResolveInfo> found = c.getPackageManager().queryBroadcastReceivers(base, 0);
        Set<String> pkgs = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (c.getPackageManager().checkPermission(PERMISSION, pkg) == PackageManager.PERMISSION_GRANTED) {
                pkgs.add(pkg);
            }
        }
        for (String pkg : pkgs) {
            Intent i = new Intent(ACTION).setPackage(pkg)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("kind", r.kind == BolusTracker.Kind.NEW ? "new" : "update")
                    .putExtra("units", r.units)
                    .putExtra("at", r.at)
                    .putExtra("source", source);
            if (!Double.isNaN(r.carbs)) i.putExtra("carbs", r.carbs);
            if (mealSize != null && !mealSize.isEmpty()) i.putExtra("meal_size", mealSize);
            c.sendBroadcast(i, PERMISSION);
        }
        if (!pkgs.isEmpty()) Store.log(c, "Bolus shared with " + pkgs);
    }
}
