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
 * Fills an amount into CamAPS after YOU tap it (the carbs sheet or the widget). Off by default
 * (Settings > Carbs for CamAPS). Two kinds, never mixed:
 *
 *  MEAL: gets to the bolus calculator, opens the Carbs entry and types the grams into the box titled
 *        "Carbohydrates". Stops with the box open.
 *  SLOW: the same Add meal route with "Slowly absorbed meal" selected instead.
 *  LOW:  a low treatment. Goes through the menu to Add meal, makes sure "Hypoglycaemia treatment" is the
 *        selected option (the page opens on "Meal or snack"), and types the grams into Amount. Stops
 *        there. It never types a low treatment into the bolus calculator, and never a meal into Add meal.
 *
 * What it never does, either kind: tap Confirm, Deliver, Next, Continue or Cancel; type into any box
 * that is not the one for that kind; type anything but a whole number in range (meals 1 to 150, low
 * treatments 5 to 30); go on for more than 60 seconds or 12 actions; act without a tap from you in the
 * last 60 seconds; act on a request twice. From the side menu, other pages, or a half-entered box it
 * presses Back (at most 6 times in all); an alert, a warning or a delivery on screen it leaves alone and
 * stops. If it can't tell where it is it gives up and says so.
 *
 * The only actions in this file are tap() (one click call), type() (one text-set call) and one Back,
 * and CI counts them. What tap() may be pointed at is limited to four finders: the Carbs field, the menu
 * button, the Add meal item, the Add meal option for this kind, and the X of the delivered-an-hour-ago
 * information popup; the Bolus tab is {@link BolusTab#openTab}.
 */
final class CarbFill {

    private CarbFill() { }

    static final long TTL_MS = 60_000L, STEP_GAP_MS = 700L, SETTLE_MS = 3_000L;
    static final int MAX_ACTIONS = 12, MAX_BACKS = 6;

    /** MEAL: the bolus calculator. LOW and SLOW: the Add meal page, with that option selected. */
    enum Kind { MEAL, LOW, SLOW }

    static final String OPT_MEAL = "Meal or snack", OPT_LOW = "Hypoglycaemia treatment", OPT_SLOW = "Slowly absorbed meal";

    static String optionOf(Kind k) {
        return k == Kind.LOW ? OPT_LOW : k == Kind.SLOW ? OPT_SLOW : OPT_MEAL;
    }

    // ---- pure: what screen is this, and what is the one next step (unit tested) ----

    /**
     * DRAWER: the side menu. ADD_MEAL: the Add meal pages. ENTRY_DIALOG: a half-entered Boost or Ease-off
     * duration or Glucose box (Back cancels it). OTHER_DIALOG: any other box (an alert, a warning): not touched.
     * UNKNOWN: any other page (Statistics, Info, Settings...), left with Back.
     */
    enum Screen { HOME, CALCULATOR, CARBS_DIALOG, ENTRY_DIALOG, OTHER_DIALOG, DRAWER, ADD_MEAL, INFO_POPUP, BUSY, UNKNOWN }

    /**
     * INFO_POPUP: CamAPS's "Information" popup that says how long ago insulin was delivered ("1.9 U delivered 8 min
     * ago."). It shows over the calculator, Back does not close it, and it is only a note, so its X is tapped.
     * Any other Information popup (a pump problem, say) is OTHER_DIALOG and left alone.
     */
    private static final Pattern DELIVERED_AGO = Pattern.compile("^\\d+([.,]\\d+)?\\s*u\\s+delivered\\b.*\\bago\\.?$", Pattern.CASE_INSENSITIVE);

    enum Step {
        OPEN_TAB, OPEN_CARBS, TYPE,
        OPEN_MENU, OPEN_ADD_MEAL, PICK_HYPO, TYPE_LOW, DISMISS_INFO,
        BACK, ABORT_BUSY, ABORT_OTHER_DIALOG, ABORT_LOST, ABORT_NO_FIELD
    }

    static Screen classify(List<String> texts, boolean hasEditable) {
        boolean confirm = false, carbsTitle = false, glucoseTitle = false, calc = false, home = false;
        boolean addMeal = false, alerts = false, settings = false, amount = false, cont = false, durationBox = false;
        boolean infoTitle = false, deliveredAgo = false;
        for (String raw : texts) {
            String l = raw.trim().toLowerCase(Locale.UK);
            if (l.startsWith("delivery") || l.startsWith("delivered") || l.startsWith("delivering")) return Screen.BUSY;
            if (l.equals("information")) infoTitle = true;
            if (DELIVERED_AGO.matcher(raw.trim()).matches()) deliveredAgo = true;
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
        if (infoTitle && deliveredAgo) return Screen.INFO_POPUP;
        if (infoTitle) return Screen.OTHER_DIALOG;
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

    /** Meal: the one step from this screen. */
    static Step decide(Screen s, int backs) {
        return decide(s, backs, Kind.MEAL, false, false);
    }

    /**
     * The one step from this screen for this kind of request. hypoSelected: the Add meal page has
     * "Hypoglycaemia treatment" selected (and "Meal or snack" not). hasAmount: it has its Amount field.
     */
    static Step decide(Screen s, int backs, Kind kind, boolean hypoSelected, boolean hasAmount) {
        if (s == Screen.BUSY) return Step.ABORT_BUSY;
        if (s == Screen.OTHER_DIALOG) return Step.ABORT_OTHER_DIALOG;
        if (s == Screen.INFO_POPUP) return Step.DISMISS_INFO;
        if (kind != Kind.MEAL) {
            switch (s) {
                case HOME:
                case CALCULATOR: return Step.OPEN_MENU;
                case DRAWER: return Step.OPEN_ADD_MEAL;
                case ADD_MEAL: return !hypoSelected ? Step.PICK_HYPO : hasAmount ? Step.TYPE_LOW : Step.ABORT_NO_FIELD;
                default: return backs < MAX_BACKS ? Step.BACK : Step.ABORT_LOST;   // the bolus box, entry boxes, other pages
            }
        }
        switch (s) {
            case HOME: return Step.OPEN_TAB;
            case CALCULATOR: return Step.OPEN_CARBS;
            case CARBS_DIALOG: return Step.TYPE;
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
            case ABORT_OTHER_DIALOG: return "a different box is open (not one I fill in)";
            case ABORT_NO_FIELD: return "the Add meal page has no Amount field";
            default: return "I couldn't tell which CamAPS screen this is";
        }
    }

    // ---- the request ----

    private static final class Req {
        final int grams;
        final String label;
        final Kind kind;
        final long deadline;
        int actions, backs;
        long lastAt;
        Step lastStep;
        int sameStep;
        boolean finished;

        Req(int grams, String label, Kind kind, long deadline) {
            this.grams = grams;
            this.label = label;
            this.kind = kind;
            this.deadline = deadline;
        }
    }

    private static volatile Req req;

    static boolean enabled(Context c) {
        return Store.p(c).getBoolean(Store.CARB_FILL, false);
    }

    /** Why a tap can't start now, or null if it can. */
    static String refusal(Context c, int grams, Kind kind) {
        if (!enabled(c)) return "Carbs for CamAPS is off. Turn it on in FX Watcher Settings.";
        if (!CarbOptions.validFor(kind, grams)) {
            return kind == Kind.LOW
                    ? "A low treatment is whole grams from " + CarbOptions.LOW_MIN_G + " to " + CarbOptions.LOW_MAX_G + "."
                    : kind == Kind.SLOW
                    ? "A slowly absorbed meal is whole grams from " + CarbOptions.STEP_MIN + " to " + CarbOptions.STEP_MAX + "."
                    : "Only whole grams from " + CarbOptions.MIN_G + " to " + CarbOptions.MAX_G + ".";
        }
        if (!Phone.readerOn(c)) return "Turn on FX Watcher's CamAPS screen reader first.";
        if (Calm.camapsLaunch(c) == null) return "CamAPS isn't installed.";
        return null;
    }

    /**
     * A person tapped an amount. Opens CamAPS, and the screen reader does the rest. Call it from a
     * foreground activity. Returns a reason it did not start, or null.
     */
    static String request(Context c, int grams, String label, Kind kind) {
        String no = refusal(c, grams, kind);
        if (no != null) return no;
        long now = System.currentTimeMillis();
        req = new Req(grams, label, kind, now + TTL_MS);
        Store.logCam(c, "Carbs for CamAPS: asked for " + grams + " g " + (kind == Kind.LOW ? "LOW TREATMENT" : kind == Kind.SLOW ? "SLOW MEAL" : "meal") + " (" + label + ")");
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
        if (r.actions >= MAX_ACTIONS) {
            finish(c, r, false, "too many steps, so I stopped", root);
            return;
        }
        // Right after a tap the next page is still animating in and looks unknown: give it a moment.
        boolean justTapped = r.lastStep == Step.OPEN_TAB || r.lastStep == Step.OPEN_CARBS
                || r.lastStep == Step.OPEN_MENU || r.lastStep == Step.OPEN_ADD_MEAL || r.lastStep == Step.PICK_HYPO
                || r.lastStep == Step.DISMISS_INFO;
        AccessibilityNodeInfo boxField = dialogField(root);
        Screen screen = classify(texts, boxField != null);
        AccessibilityNodeInfo amountBox = screen == Screen.ADD_MEAL ? amountField(root) : null;
        boolean hypo = screen == Screen.ADD_MEAL && r.kind != Kind.MEAL && optionSelected(root, r.kind);
        if (screen == Screen.UNKNOWN && justTapped && now - r.lastAt < SETTLE_MS) return;
        Step step = decide(screen, r.backs, r.kind, hypo, amountBox != null);
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
                tap(field);
                break;
            }
            case DISMISS_INFO: {
                AccessibilityNodeInfo close = closeButton(root);
                if (close == null) {
                    finish(c, r, false, "I couldn't find the close button on CamAPS's information popup", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(close);
                break;
            }
            case OPEN_MENU: {
                AccessibilityNodeInfo menu = menuButton(root);
                if (menu == null) {
                    finish(c, r, false, "I couldn't find CamAPS's menu button", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(menu);
                break;
            }
            case OPEN_ADD_MEAL: {
                AccessibilityNodeInfo item = addMealItem(root);
                if (item == null) {
                    finish(c, r, false, "I couldn't find Add meal in the menu", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(item);
                break;
            }
            case PICK_HYPO: {
                AccessibilityNodeInfo radio = optionNode(root, r.kind);
                if (radio == null) {
                    finish(c, r, false, "I couldn't find the " + optionOf(r.kind) + " option", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(radio);
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
                String shown = type(box, r.grams);
                if (verified(shown, r.grams)) {
                    finish(c, r, true, null, null);
                } else {
                    finish(c, r, false, "the box shows \"" + shown + "\" instead of " + r.grams + ". Fix it before you confirm", root);
                }
                break;
            }
            case TYPE_LOW: {
                r.actions++;
                r.lastAt = now;
                String shown = type(amountBox, r.grams);
                boolean stillRight = optionSelected(root, r.kind);
                if (verified(shown, r.grams) && stillRight) {
                    finish(c, r, true, null, null);
                } else {
                    finish(c, r, false, "the Amount shows \"" + shown + "\" and " + optionOf(r.kind) + " is "
                            + (stillRight ? "selected" : "NOT selected") + ". Check both before you continue", root);
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

    /** The only click in this file. */
    private static boolean tap(AccessibilityNodeInfo n) {
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /** The only text entry in this file: sets the grams, then reads the field back. */
    private static String type(AccessibilityNodeInfo box, int grams) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, String.valueOf(grams));
        boolean ok = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        box.refresh();
        String shown = box.getText() == null ? "" : box.getText().toString();
        return ok ? shown : "(not set) " + shown;
    }

    private static void finish(Context c, Req r, boolean ok, String problem, AccessibilityNodeInfo root) {
        r.finished = true;
        req = null;
        if (!ok && root != null) Store.logCam(c, "Carbs for CamAPS: the screen was:" + dump(root));
        Store.logCam(c, ok ? "Carbs for CamAPS: " + r.grams + " g typed in (" + r.kind + "), waiting for you"
                : "Carbs for CamAPS: stopped, " + problem);
        notifyResult(c, r.grams, r.kind, ok, problem);
    }

    // ---- finding things ----

    /**
     * The one field that may be typed into for a meal: an editable field that sits between the
     * "Carbohydrates" title and the Confirm button of the box that is open. Fields behind the box
     * are never chosen.
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

    /** The Add meal page's Amount field: the first visible editable field on that page. */
    static AccessibilityNodeInfo amountField(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        for (AccessibilityNodeInfo n : all) {
            if (n.isVisibleToUser() && n.isEditable()) return n;
        }
        return null;
    }

    private static AccessibilityNodeInfo byText(List<AccessibilityNodeInfo> all, String text) {
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
            if (n.isVisibleToUser() && t != null && t.toString().trim().equalsIgnoreCase(text)) return n;
        }
        return null;
    }

    private static boolean checked(AccessibilityNodeInfo n) {
        for (int up = 0; n != null && up < 3; up++, n = n.getParent()) {
            if (n.isChecked() || (n.isCheckable() && n.isSelected())) return true;
        }
        return false;
    }

    /** This kind's option is selected on the Add meal page and the other two are not. */
    static boolean optionSelected(AccessibilityNodeInfo root, Kind kind) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        String want = optionOf(kind);
        AccessibilityNodeInfo mine = null;
        for (String opt : new String[]{OPT_MEAL, OPT_LOW, OPT_SLOW}) {
            AccessibilityNodeInfo n = byText(all, opt);
            if (opt.equals(want)) mine = n;
            else if (n != null && checked(n)) return false;
        }
        return mine != null && checked(mine);
    }

    /** This kind's option on the Add meal page (or its clickable parent). */
    static AccessibilityNodeInfo optionNode(AccessibilityNodeInfo root, Kind kind) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        return clickableUp(byText(all, optionOf(kind)));
    }

    /** The Add meal entry in the side menu (or its clickable parent). */
    static AccessibilityNodeInfo addMealItem(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        return clickableUp(byText(all, "Add meal"));
    }

    /** The toolbar's menu button: the leftmost clickable node in the top-left corner of CamAPS's window. */
    static AccessibilityNodeInfo menuButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        Rect r = new Rect(), b = new Rect();
        root.getBoundsInScreen(r);
        AccessibilityNodeInfo best = null;
        int bestX = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : all) {
            if (!n.isVisibleToUser() || !n.isClickable()) continue;
            n.getBoundsInScreen(b);
            if (b.centerX() < r.left + r.width() * 0.22 && b.centerY() < r.top + r.height() * 0.14 && b.centerX() < bestX) {
                best = n;
                bestX = b.centerX();
            }
        }
        return best;
    }

    /** The X of the "Information" popup: the rightmost clickable node on the title's row. */
    static AccessibilityNodeInfo closeButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        AccessibilityNodeInfo title = byText(all, "Information");
        if (title == null) return null;
        Rect tb = new Rect(), b = new Rect();
        title.getBoundsInScreen(tb);
        AccessibilityNodeInfo best = null;
        int bestX = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : all) {
            if (!n.isVisibleToUser() || !n.isClickable()) continue;
            n.getBoundsInScreen(b);
            if (Math.abs(b.centerY() - tb.centerY()) <= tb.height() && b.centerX() > tb.centerX() && b.centerX() > bestX) {
                best = n;
                bestX = b.centerX();
            }
        }
        return best;
    }

    private static AccessibilityNodeInfo clickableUp(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo t = n;
        for (int up = 0; t != null && !t.isClickable() && up < 3; up++) t = t.getParent();
        return t != null && t.isClickable() ? t : null;
    }

    private static final Pattern AMOUNT = Pattern.compile("^(---|\\d{1,3}([.,]\\d)?)$");

    /**
     * The Carbs value on the calculator: a number or "---" on the same row as the "Carbs" label,
     * nearest to it, or its clickable parent.
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
        return best == null ? null : clickableUp(best);
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
                    .append(n.isChecked() ? " [checked]" : "")
                    .append(n.getViewIdResourceName() == null ? "" : " #" + n.getViewIdResourceName())
                    .append(" ").append(b.toShortString());
            if (++shown >= 60) break;
        }
        return sb.toString();
    }

    // ---- telling you ----

    static final String CH = "carbfill";
    static final int ID = 7;

    static void notifyResult(Context c, int grams, Kind kind, boolean ok, String problem) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Carbs for CamAPS", NotificationManager.IMPORTANCE_DEFAULT));
        String what = kind == Kind.LOW ? "Low treatment" : kind == Kind.SLOW ? "Slow meal" : "Meal carbs";
        String text = ok
                ? (kind != Kind.MEAL
                        ? what + " " + grams + " g is in CamAPS's Add meal page, with " + optionOf(kind) + " selected. Check both, then tap Continue yourself."
                        : "Meal carbs " + grams + " g are in CamAPS's Carbohydrates box. Check it, then tap Confirm yourself.")
                : "Couldn't fill in " + grams + " g (" + what.toLowerCase(Locale.UK) + "): " + problem + ". Nothing was confirmed or delivered.";
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(ok ? what + " ready in CamAPS" : what + " not filled in")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .build();
        nm.notify(ID, n);
    }
}
