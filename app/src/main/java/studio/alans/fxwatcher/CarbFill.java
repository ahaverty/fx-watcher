package studio.alans.fxwatcher;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fills the carbs into CamAPS's bolus calculator after YOU tap an amount (widget, app, or a plan
 * from Meal Log). Off by default (Settings > Carbs for CamAPS).
 *
 * What it does: gets to CamAPS's bolus calculator from wherever CamAPS is, opens the Carbs entry,
 * and types the amount into the box titled "Carbohydrates". Then it stops, with the box open, and
 * tells you. You check it, tap Confirm, and deliver yourself.
 *
 * What it never does: tap Confirm, Deliver, Next, Cancel or anything on a delivery; type into any
 * box that is not titled "Carbohydrates"; type anything but a whole number from 1 to 150; go on
 * for more than 60 seconds or 8 actions; act without a tap from you in the last 60 seconds; act on
 * a request twice. From the side menu, the Add meal pages, a half-entered Boost, Ease-off or Glucose box or any
 * other CamAPS page it presses Back (at most 6 times in all) to get to the home screen; any other box (an alert, a
 * warning) or a delivery on screen it leaves alone and stops. If it can't tell where it is it gives up and
 * says so. The tab tap is {@link BolusTab#openTab}; the only other actions are in this file and CI
 * counts them: one Carbs field tap, one text set, one Back.
 */
final class CarbFill {

    private CarbFill() { }

    static final long TTL_MS = 60_000L, STEP_GAP_MS = 700L, SETTLE_MS = 3_000L;
    static final int MAX_ACTIONS = 12, MAX_BACKS = 6;

    // ---- pure: what screen is this, and what is the one next step (unit tested) ----

    /**
     * DRAWER: the side menu. ADD_MEAL: the Add meal pages. ENTRY_DIALOG: a half-entered Boost or Ease-off
     * duration or Glucose box (Back cancels it). OTHER_DIALOG: any other box (an alert, a warning): not touched.
     * UNKNOWN: any other page (Statistics, Info, Settings...), left with Back.
     */
    enum Screen { HOME, CALCULATOR, CARBS_DIALOG, ENTRY_DIALOG, OTHER_DIALOG, DRAWER, ADD_MEAL, BUSY, UNKNOWN }

    enum Step { OPEN_TAB, OPEN_CARBS, TYPE, BACK, ABORT_BUSY, ABORT_OTHER_DIALOG, ABORT_LOST }

    static Screen classify(List<String> texts, boolean hasEditable) {
        boolean confirm = false, carbsTitle = false, glucoseTitle = false, calc = false, home = false;
        boolean addMeal = false, alerts = false, settings = false, amount = false, cont = false, durationBox = false;
        for (String raw : texts) {
            String l = raw.trim().toLowerCase(Locale.UK);
            if (l.startsWith("delivery") || l.startsWith("delivered") || l.startsWith("delivering")) return Screen.BUSY;
            if (l.equals("confirm")) confirm = true;
            if (l.equals("carbohydrates") || l.equals("carbohydrate")) carbsTitle = true;
            if (l.equals("glucose")) glucoseTitle = true;
            if (l.equals("total units") || l.equals("g cho") || l.equals("meal size")) calc = true;
            if (l.equals("auto mode")) home = true;
            if (l.equals("add meal")) addMeal = true;
            if (l.equals("alerts")) alerts = true;
            if (l.equals("settings")) settings = true;
            if (l.equals("amount")) amount = true;
            if (l.equals("continue")) cont = true;
            if (l.equals("boost duration") || l.equals("ease-off duration")) durationBox = true;
        }
        if (addMeal && alerts && settings) return Screen.DRAWER;
        if (confirm) {
            if (carbsTitle && hasEditable) return Screen.CARBS_DIALOG;
            if (!carbsTitle && glucoseTitle) return Screen.ENTRY_DIALOG;
            return Screen.OTHER_DIALOG;
        }
        if (durationBox) return Screen.ENTRY_DIALOG;
        if (addMeal && (amount || cont)) return Screen.ADD_MEAL;
        if (calc) return Screen.CALCULATOR;
        if (home) return Screen.HOME;
        return Screen.UNKNOWN;
    }

    static Step decide(Screen s, int backs) {
        switch (s) {
            case HOME: return Step.OPEN_TAB;
            case CALCULATOR: return Step.OPEN_CARBS;
            case CARBS_DIALOG: return Step.TYPE;
            case BUSY: return Step.ABORT_BUSY;
            case OTHER_DIALOG: return Step.ABORT_OTHER_DIALOG;
            default: return backs < MAX_BACKS ? Step.BACK : Step.ABORT_LOST;   // DRAWER, ADD_MEAL, ENTRY_DIALOG, UNKNOWN
        }
    }

    /** The box shows exactly the amount: "30", "30.0" or "30 " but not "3", "300" or "". */
    static boolean verified(String shown, int grams) {
        if (shown == null) return false;
        String s = shown.trim().replace(',', '.');
        if (!s.matches("\\d{1,3}(\\.0+)?")) return false;
        return (int) Math.round(Double.parseDouble(s)) == grams;
    }

    static String why(Step s) {
        switch (s) {
            case ABORT_BUSY: return "a delivery is on screen";
            case ABORT_OTHER_DIALOG: return "a different box is open (not Carbohydrates)";
            default: return "I couldn't tell which CamAPS screen this is";
        }
    }

    // ---- the request ----

    private static final class Req {
        final int grams;
        final String label;
        final long deadline;
        int actions, backs;
        long lastAt;
        Step lastStep;
        int sameStep;
        boolean finished;

        Req(int grams, String label, long deadline) {
            this.grams = grams;
            this.label = label;
            this.deadline = deadline;
        }
    }

    private static volatile Req req;

    static boolean enabled(Context c) {
        return Store.p(c).getBoolean(Store.CARB_FILL, false);
    }

    /** Why a tap can't start now, or null if it can. */
    static String refusal(Context c, int grams) {
        if (!enabled(c)) return "Carbs for CamAPS is off. Turn it on in FX Watcher Settings.";
        if (!CarbOptions.valid(grams)) return "Only whole grams from " + CarbOptions.MIN_G + " to " + CarbOptions.MAX_G + ".";
        if (!Phone.readerOn(c)) return "Turn on FX Watcher's CamAPS screen reader first.";
        if (Calm.camapsLaunch(c) == null) return "CamAPS isn't installed.";
        return null;
    }

    /**
     * A person tapped an amount. Opens CamAPS, and the screen reader does the rest. Call it from a
     * foreground activity (the widget goes through CarbFillActivity). Returns a reason it did not
     * start, or null.
     */
    static String request(Context c, int grams, String label) {
        String no = refusal(c, grams);
        if (no != null) return no;
        long now = System.currentTimeMillis();
        req = new Req(grams, label, now + TTL_MS);
        Store.logCam(c, "Carbs for CamAPS: asked for " + grams + " g (" + label + ")");
        Intent i = Calm.camapsLaunch(c);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            req = null;
            return "Couldn't open CamAPS.";
        }
        return null;
    }

    static boolean active() {
        Req r = req;
        return r != null && !r.finished && System.currentTimeMillis() <= r.deadline;
    }

    /** From the screen reader, for each CamAPS screen. Does nothing unless a request is live. */
    static void maybeAct(Context c, AccessibilityService svc, AccessibilityNodeInfo root, List<String> texts) {
        Req r = req;
        if (r == null || r.finished) return;
        long now = System.currentTimeMillis();
        if (now > r.deadline) {
            finish(c, r, false, "it took too long, so I stopped", root);
            return;
        }
        if (now - r.lastAt < STEP_GAP_MS) return;
        // Right after a tap on the tab or the Carbs field the next page is still animating in and looks unknown:
        // give it a moment instead of pressing Back.
        boolean justTapped = r.lastStep == Step.OPEN_TAB || r.lastStep == Step.OPEN_CARBS;
        if (r.actions >= MAX_ACTIONS) {
            finish(c, r, false, "too many steps, so I stopped", root);
            return;
        }
        AccessibilityNodeInfo boxField = dialogField(root);
        Screen screen = classify(texts, boxField != null);
        if (screen == Screen.UNKNOWN && justTapped && now - r.lastAt < SETTLE_MS) return;
        Step step = decide(screen, r.backs);
        Store.logCam(c, "Carbs for CamAPS: step " + (r.actions + 1) + ": " + screen + " -> " + step);
        if (step == r.lastStep) {
            if (++r.sameStep > 2) {
                finish(c, r, false, "the same step didn't work three times (" + screen + ")", root);
                return;
            }
        } else {
            r.lastStep = step;
            r.sameStep = 1;
        }
        switch (step) {
            case OPEN_TAB:
                r.actions++;
                r.lastAt = now;
                if (!BolusTab.openTab(root)) finish(c, r, false, "I couldn't find the Bolus tab", root);
                break;
            case OPEN_CARBS: {
                AccessibilityNodeInfo field = carbsField(root);
                if (field == null) {
                    finish(c, r, false, "I couldn't find the Carbs field", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                field.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                break;
            }
            case TYPE: {
                AccessibilityNodeInfo box = boxField;
                if (box == null) {
                    finish(c, r, false, "the Carbohydrates box has no field", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, String.valueOf(r.grams));
                boolean ok = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                box.refresh();
                String shown = box.getText() == null ? "" : box.getText().toString();
                if (ok && verified(shown, r.grams)) {
                    finish(c, r, true, null, null);
                } else {
                    finish(c, r, false, "the box shows \"" + shown + "\" instead of " + r.grams + ". Fix it before you confirm", root);
                }
                break;
            }
            case BACK:
                r.actions++;
                r.backs++;
                r.lastAt = now;
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                break;
            default:
                finish(c, r, false, why(step), root);
        }
    }

    private static void finish(Context c, Req r, boolean ok, String problem, AccessibilityNodeInfo root) {
        r.finished = true;
        req = null;
        if (!ok && root != null) Store.logCam(c, "Carbs for CamAPS: the screen was:" + dump(root));
        Store.logCam(c, ok ? "Carbs for CamAPS: " + r.grams + " g typed in, waiting for you to confirm"
                : "Carbs for CamAPS: stopped, " + problem);
        notifyResult(c, r.grams, ok, problem);
    }

    // ---- finding things ----

    /**
     * The one field that may be typed into: an editable field that sits between the "Carbohydrates"
     * title and the Confirm button of the box that is open. Fields behind the box (Glucose, Carbs on the
     * calculator) are never chosen. Null if the title, the Confirm button or such a field is missing.
     */
    static AccessibilityNodeInfo dialogField(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        AccessibilityNodeInfo title = null, confirm = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (!n.isVisibleToUser() || t == null) continue;
            String s = t.toString().trim();
            if (title == null && s.equalsIgnoreCase("Carbohydrates")) title = n;
            if (confirm == null && s.equalsIgnoreCase("Confirm")) confirm = n;
        }
        if (title == null || confirm == null) return null;
        Rect tb = new Rect(), cb = new Rect(), b = new Rect();
        title.getBoundsInScreen(tb);
        confirm.getBoundsInScreen(cb);
        for (AccessibilityNodeInfo n : all) {
            if (!n.isVisibleToUser() || !n.isEditable()) continue;
            n.getBoundsInScreen(b);
            if (b.centerY() > tb.bottom - 4 && b.centerY() < cb.top + 4) return n;
        }
        return null;
    }

    private static final Pattern AMOUNT = Pattern.compile("^(---|\\d{1,3}([.,]\\d)?)$");

    /**
     * The Carbs value on the calculator: a number or "---" on the same row as the "Carbs" label,
     * nearest to it, or its clickable parent. If the calculator's layout is not what this expects it
     * finds nothing and the request stops with a dump of the screen in the log.
     */
    static AccessibilityNodeInfo carbsField(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        AccessibilityNodeInfo label = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (n.isVisibleToUser() && t != null && t.toString().trim().equalsIgnoreCase("Carbs")) {
                label = n;
                break;
            }
        }
        if (label == null) return null;
        Rect lb = new Rect(), b = new Rect();
        label.getBoundsInScreen(lb);
        AccessibilityNodeInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (n == label || !n.isVisibleToUser() || t == null || !AMOUNT.matcher(t.toString().trim()).matches()) continue;
            n.getBoundsInScreen(b);
            int dy = Math.abs(b.centerY() - lb.centerY());
            if (dy > Math.max(lb.height(), b.height()) * 1.2) continue;     // not on the label's row
            int dx = Math.abs(b.centerX() - lb.centerX());
            if (dx < bestDist) {
                bestDist = dx;
                best = n;
            }
        }
        if (best == null) return null;
        AccessibilityNodeInfo t = best;
        for (int up = 0; t != null && !t.isClickable() && up < 3; up++) t = t.getParent();
        return t != null && t.isClickable() ? t : null;
    }

    private static void flatten(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40 || out.size() > 600) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) flatten(n.getChild(i), out, depth + 1);
    }

    /** For the log when the layout is not what was expected: class, text, id, clickable, editable, bounds. */
    static String dump(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        StringBuilder sb = new StringBuilder();
        Rect b = new Rect();
        int shown = 0;
        for (AccessibilityNodeInfo n : all) {
            if (!n.isVisibleToUser()) continue;
            CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
            if (t == null && !n.isClickable() && !n.isEditable()) continue;
            n.getBoundsInScreen(b);
            String cls = String.valueOf(n.getClassName());
            sb.append("\n  ").append(cls.substring(cls.lastIndexOf('.') + 1))
                    .append(t == null ? "" : " \"" + t + "\"")
                    .append(n.isClickable() ? " [click]" : "").append(n.isEditable() ? " [edit]" : "")
                    .append(n.getViewIdResourceName() == null ? "" : " #" + n.getViewIdResourceName())
                    .append(" ").append(b.toShortString());
            if (++shown >= 60) break;
        }
        return sb.toString();
    }

    // ---- telling you ----

    static final String CH = "carbfill";
    static final int ID = 7;

    static void notifyResult(Context c, int grams, boolean ok, String problem) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Carbs for CamAPS", NotificationManager.IMPORTANCE_DEFAULT));
        String text = ok ? grams + " g is in CamAPS's Carbohydrates box. Check it, then tap Confirm yourself."
                : "Couldn't fill in " + grams + " g: " + problem + ". CamAPS was not confirmed or delivered.";
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(ok ? "Carbs ready in CamAPS" : "Carbs not filled in")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .build();
        nm.notify(ID, n);
    }
}
