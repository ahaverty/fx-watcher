package studio.alans.fxwatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * THE ONLY THING FX WATCHER EVER TAPS IN CAMAPS, and only if turned on in Settings > Connections
 * ("Open the Bolus tab when an app asks", off by default).
 *
 * An app on the phone (Meal Log's "Open CamAPS with this plan") sends {@link #ACTION} just before
 * it opens CamAPS. Within the next {@link #WINDOW_MS}, on CamAPS's home screen, the screen reader
 * taps the "Bolus" tab once, so the calculator is open. Nothing else: no numbers, no Deliver,
 * no other screen, no repeat. A request with the setting off is logged and ignored. The sender
 * must hold FX Watcher's bolus permission (the receiver is declared with it).
 *
 * CI allows the one acting call (ACTION_CLICK) in this file only; everything else in the app stays
 * read-only.
 */
public final class BolusTab extends BroadcastReceiver {

    static final String ACTION = "studio.alans.fxwatcher.action.OPEN_BOLUS_TAB";
    static final long WINDOW_MS = 20_000;
    private static volatile long pendingUntil = 0;

    @Override
    public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        if (!Store.p(c).getBoolean(Store.TAP_BOLUS_TAB, false)) {
            Store.log(c, "An app asked to open CamAPS's Bolus tab; ignored (off in Settings)");
            return;
        }
        pendingUntil = System.currentTimeMillis() + WINDOW_MS;
    }

    /** Called by the screen reader for each CamAPS screen. texts = everything visible on it. */
    static void maybeTap(Context c, AccessibilityNodeInfo root, List<String> texts) {
        if (pendingUntil == 0) return;
        if (System.currentTimeMillis() > pendingUntil) { pendingUntil = 0; return; }
        // Home only: "Auto mode" showing, and nothing from the calculator or a delivery.
        boolean home = false;
        for (String t : texts) {
            String s = t.trim();
            if (s.equalsIgnoreCase("Auto mode")) home = true;
            if (s.equalsIgnoreCase("Carbs") || s.equalsIgnoreCase("g CHO") || s.equalsIgnoreCase("Deliver")
                    || s.startsWith("Delivery") || s.startsWith("Delivered") || s.equalsIgnoreCase("Total Units")) {
                pendingUntil = 0;   // calculator (or a delivery) already on screen: nothing to do
                return;
            }
        }
        if (!home) return;  // still loading or another screen: wait for the next event
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText("Bolus");
        if (found == null) return;
        for (AccessibilityNodeInfo n : found) {
            CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
            if (t == null || !t.toString().trim().equalsIgnoreCase("Bolus") || !n.isVisibleToUser() || n.isSelected()) continue;
            AccessibilityNodeInfo target = n;
            for (int up = 0; target != null && !target.isClickable() && up < 3; up++) target = target.getParent();
            if (target == null || !target.isClickable()) continue;
            pendingUntil = 0;   // one try, whatever happens
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Store.log(c, ok ? "Opened CamAPS's Bolus tab (asked by an app)" : "Couldn't open CamAPS's Bolus tab");
            return;
        }
    }
}
