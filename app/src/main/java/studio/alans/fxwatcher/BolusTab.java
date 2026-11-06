package studio.alans.fxwatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * THE ONLY THING FX WATCHER EVER TAPS IN CAMAPS, and only if turned on in Settings > Connections
 * ("Open the Bolus tab when an app asks", off by default).
 *
 * An app on the phone (Meal Log's "Open CamAPS with this plan") sends {@link #ACTION} just before
 * it opens CamAPS. Within the next {@link #WINDOW_MS}, on CamAPS's home screen, the screen reader
 * taps the Bolus tab (the middle, knife-and-fork tab) once, so the calculator is open. Nothing
 * else: no numbers, no Deliver, no other screen, no repeat. A request with the setting off is
 * logged and ignored. The sender must hold FX Watcher's bolus permission (the receiver is
 * declared with it).
 *
 * While that app's plan is up (30 min after asking), the reader also tells it whether a CamAPS
 * entry dialog (Carbohydrates, Glucose, ... with Confirm, so the keyboard may be up) is open, so it
 * can shrink its overlay out of the keyboard's way. That is reading, not acting.
 *
 * CI allows the one acting call (ACTION_CLICK) in this file only; everything else in the app stays
 * read-only.
 */
public final class BolusTab extends BroadcastReceiver {

    static final String ACTION = "studio.alans.fxwatcher.action.OPEN_BOLUS_TAB";
    static final String SCREEN = "studio.alans.fxwatcher.action.CAMAPS_SCREEN";
    static final String MEAL_LOG = "studio.alans.meallog";
    static final long WINDOW_MS = 20_000;
    static final long SESSION_MS = 30 * 60_000L;
    private static volatile long pendingUntil = 0;
    private static volatile long sessionUntil = 0;
    private static volatile Boolean lastDialog = null;

    @Override
    public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        if (!Store.p(c).getBoolean(Store.TAP_BOLUS_TAB, false)) {
            Store.log(c, "An app asked to open CamAPS's Bolus tab; ignored (off in Settings)");
            return;
        }
        long now = System.currentTimeMillis();
        pendingUntil = now + WINDOW_MS;
        sessionUntil = now + SESSION_MS;
        lastDialog = null;
    }

    /** Called by the screen reader for each CamAPS screen. texts = everything visible on it. */
    static void maybeTap(Context c, AccessibilityNodeInfo root, List<String> texts) {
        tellOverlay(c, texts);
        if (pendingUntil == 0) return;
        if (System.currentTimeMillis() > pendingUntil) { pendingUntil = 0; return; }
        // Home only: "Auto mode" showing, and nothing from the calculator or a delivery.
        boolean home = false;
        for (String t : texts) {
            String s = t.trim();
            if (s.equalsIgnoreCase("Auto mode")) home = true;
            if (s.equalsIgnoreCase("Carbs") || s.equalsIgnoreCase("g CHO") || s.equalsIgnoreCase("Deliver")
                    || s.startsWith("Delivery") || s.startsWith("Delivered") || s.equalsIgnoreCase("Total Units")
                    || s.equalsIgnoreCase("Meal size") || s.equalsIgnoreCase("Confirm")) {
                pendingUntil = 0;   // calculator, a dialog or a delivery already on screen: nothing to do
                return;
            }
        }
        if (!home) return;  // still loading or another screen: wait for the next event
        AccessibilityNodeInfo target = byLabel(root);
        String how = "label";
        if (target == null) { target = middleTab(root); how = "position"; }
        pendingUntil = 0;   // one try, whatever happens
        if (target == null) {
            Store.log(c, "Couldn't find CamAPS's Bolus tab. Tabs: " + tabLabels(root));
            return;
        }
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Store.log(c, (ok ? "Opened CamAPS's Bolus tab" : "Couldn't open CamAPS's Bolus tab")
                + " (asked by an app, found by " + how + "). Tabs: " + tabLabels(root));
    }

    /** A visible, unselected tab described as the bolus / meal tab, or its clickable parent. */
    private static AccessibilityNodeInfo byLabel(AccessibilityNodeInfo root) {
        for (String word : new String[]{"Bolus", "Meal"}) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(word);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
                if (t == null) continue;
                String s = t.toString().trim().toLowerCase(Locale.UK);
                if (!(s.equals("bolus") || s.equals("meal") || s.equals("bolus calculator") || s.equals("bolus tab")))
                    continue;
                if (!n.isVisibleToUser() || n.isSelected() || !nearTop(root, n)) continue;
                AccessibilityNodeInfo target = clickable(n);
                if (target != null) return target;
            }
        }
        return null;
    }

    /**
     * The tabs are icons (home, knife and fork, drop), so their labels aren't known for sure. The
     * tab bar is the row near the top with exactly three clickable cells; the Bolus tab is the
     * middle one.
     */
    private static AccessibilityNodeInfo middleTab(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> row = tabRow(root);
        if (row == null) return null;
        AccessibilityNodeInfo mid = row.get(1);
        return mid.isSelected() ? null : mid;
    }

    private static List<AccessibilityNodeInfo> tabRow(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);
        for (int k = 0; k < queue.size() && k < 400; k++) {
            AccessibilityNodeInfo n = queue.get(k);
            if (n == null) continue;
            List<AccessibilityNodeInfo> cells = new ArrayList<>();
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch == null) continue;
                queue.add(ch);
                AccessibilityNodeInfo cl = ch.isClickable() ? ch : clickableBelow(ch, 3);
                if (cl != null && cl.isVisibleToUser() && nearTop(root, cl)) cells.add(cl);
            }
            if (cells.size() == 3 && sameRow(cells)) return cells;
        }
        return null;
    }

    private static AccessibilityNodeInfo clickableBelow(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth < 0) return null;
        if (n.isClickable()) return n;
        if (n.getChildCount() != 1) return null;
        return clickableBelow(n.getChild(0), depth - 1);
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo t = n;
        for (int up = 0; t != null && !t.isClickable() && up < 3; up++) t = t.getParent();
        return t != null && t.isClickable() ? t : null;
    }

    /** In the top quarter of CamAPS's window (the tab bar sits under the title bar). */
    private static boolean nearTop(AccessibilityNodeInfo root, AccessibilityNodeInfo n) {
        Rect r = new Rect(), b = new Rect();
        root.getBoundsInScreen(r);
        n.getBoundsInScreen(b);
        return r.height() > 0 && b.centerY() < r.top + r.height() / 4;
    }

    private static boolean sameRow(List<AccessibilityNodeInfo> cells) {
        Rect a = new Rect(), b = new Rect();
        cells.get(0).getBoundsInScreen(a);
        for (AccessibilityNodeInfo c : cells) {
            c.getBoundsInScreen(b);
            if (Math.abs(b.centerY() - a.centerY()) > a.height() / 2 + 1) return false;
        }
        return true;
    }

    /** For the log, so the real tab labels can be seen once it has run on the phone. */
    private static String tabLabels(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> row = tabRow(root);
        if (row == null) return "(no 3-tab row found)";
        StringBuilder sb = new StringBuilder();
        for (AccessibilityNodeInfo n : row) {
            sb.append(sb.length() > 0 ? " | " : "").append(label(n)).append(n.isSelected() ? "[sel]" : "");
        }
        return sb.toString();
    }

    private static String label(AccessibilityNodeInfo n) {
        if (n == null) return "?";
        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
        if (t != null && t.length() > 0) return t.toString().trim();
        for (int i = 0; i < n.getChildCount(); i++) {
            String s = label(n.getChild(i));
            if (!s.equals("?")) return s;
        }
        return "?";
    }

    /** Read-only: while the app's plan is up, tell it whether an entry dialog is open. */
    private static void tellOverlay(Context c, List<String> texts) {
        if (sessionUntil == 0) return;
        if (System.currentTimeMillis() > sessionUntil) { sessionUntil = 0; return; }
        boolean dialog = false;
        for (String t : texts) if (t.trim().equalsIgnoreCase("Confirm")) { dialog = true; break; }
        if (lastDialog != null && lastDialog == dialog) return;
        lastDialog = dialog;
        c.sendBroadcast(new Intent(SCREEN).setPackage(MEAL_LOG).putExtra("dialog", dialog));
    }
}
