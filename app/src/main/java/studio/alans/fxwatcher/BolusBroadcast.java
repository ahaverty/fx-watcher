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
 * Two more actions go to the same apps, with the same permission (v3.13.0):
 *   "studio.alans.fxwatcher.action.CARBS"  an Add meal entry confirmed in CamAPS (no bolus of its own):
 *       carbs (double, g), meal_type ("hypo" | "meal" | "slow"), at (long ms)
 *   "studio.alans.fxwatcher.action.BOOST"  Boost or Ease-off confirmed in CamAPS:
 *       kind ("Boost" | "Ease-off"), start (long ms, when it starts), minutes (int, -1 unknown), at (long ms)
 * Only apps holding the permission receive it. Each one is sent its own explicit broadcast, since
 * Android no longer delivers implicit broadcasts to manifest receivers.
 */
final class BolusBroadcast {

    private BolusBroadcast() { }

    static final String ACTION = "studio.alans.fxwatcher.action.BOLUS";
    static final String PERMISSION = "studio.alans.fxwatcher.permission.RECEIVE_BOLUS";

    static final String ACTION_CARBS = "studio.alans.fxwatcher.action.CARBS";
    static final String ACTION_BOOST = "studio.alans.fxwatcher.action.BOOST";

    /** An Add meal entry (hypo treatment, meal or snack, slowly absorbed meal). */
    static void carbs(Context c, long at, double carbs, String mealType) {
        Intent i = new Intent(ACTION_CARBS).putExtra("at", at).putExtra("carbs", carbs).putExtra("meal_type", mealType);
        sendAll(c, ACTION_CARBS, i, "Add meal " + carbs + " g " + mealType);
    }

    /** Boost / Ease-off confirmed (start 0 = now). */
    static void boost(Context c, String kind, long start, int minutes, long at) {
        Intent i = new Intent(ACTION_BOOST).putExtra("kind", kind).putExtra("start", start > 0 ? start : at)
                .putExtra("minutes", minutes).putExtra("at", at);
        sendAll(c, ACTION_BOOST, i, kind);
    }

    private static void sendAll(Context c, String action, Intent template, String what) {
        if (!Store.p(c).getBoolean(Store.SHARE_BOLUS, false)) return;
        Set<String> pkgs = receivers(c, action);
        for (String pkg : pkgs) {
            Intent i = new Intent(template).setPackage(pkg)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            c.sendBroadcast(i, PERMISSION);
        }
        if (!pkgs.isEmpty()) Store.log(c, what + " shared with " + pkgs);
    }

    private static Set<String> receivers(Context c, String action) {
        List<ResolveInfo> found = c.getPackageManager().queryBroadcastReceivers(new Intent(action), 0);
        Set<String> pkgs = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (c.getPackageManager().checkPermission(PERMISSION, pkg) == PackageManager.PERMISSION_GRANTED) pkgs.add(pkg);
        }
        return pkgs;
    }

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
