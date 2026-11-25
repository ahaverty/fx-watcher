package studio.alans.fxwatcher;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fills things into CamAPS after YOU tap them (the carbs sheet, the widget, or the plan runner). Off by
 * default (Settings > Carbs for CamAPS). Kinds, never mixed:
 *
 *  MEAL: gets to the bolus calculator, opens the Carbs entry, types the grams into the box titled
 *        "Carbohydrates" and taps that box's Confirm, then reads the calculator's Carbs value back. If a
 *        correction was asked for, it then opens the Glucose box, types CamAPS's own displayed glucose if
 *        the box is empty (or checks it if filled), and taps that box's Confirm. It stops on the
 *        calculator: Next and Deliver are yours.
 *  SLOW / LOW: the Add meal route. Selects "Slowly absorbed meal" or "Hypoglycaemia treatment", types the
 *        grams into Amount, reads both back, taps Continue, and on the final box (which must show the same
 *        grams) taps Confirm.
 *  BOOST / EASE: taps Boost or Ease-off on the home card (first cancelling one that is already running, by
 *        its X and the "Off" box), types the duration into the Hrs and Mins fields, reads it back, taps
 *        Next, picks Now and taps Confirm. Then checks the running banner shows that mode and duration.
 *
 * What it never does: tap Deliver or Cancel; tap Next on the bolus calculator; tap Confirm, Continue or
 * Next anywhere but on the one box of the step it is on, after that box has been read back; type into any
 * box that is not the one for that step; type anything but whole numbers in range; go on for more than
 * 60 seconds or 12 actions per step; act without a tap from you in the last 60 seconds; act on a request
 * twice. From the side menu, other pages, or a half-entered box it presses Back (at most 6 times in all);
 * an alert, a warning or a delivery on screen it leaves alone and stops. If it can't tell where it is it
 * gives up and says so. A plan runs its steps in a fixed order (low, slow, boost or ease-off, meal) and
 * stops at the first one that fails.
 *
 * The only actions in this file are tap() (one click call), type() (one text-set call) and one Back,
 * and CI counts them. What tap() may be pointed at is limited to the finders listed in the test.
 */
final class CarbFill {

    private CarbFill() { }

    static final long TTL_MS = 60_000L, STEP_GAP_MS = 700L, SETTLE_MS = 3_000L;
    /** After each action, look at the screen again on our own at these delays (ms): CamAPS may send no event once it settles. */
    static final long[] RECHECK_MS = {900L, 2_200L, 3_600L};
    static final int MAX_ACTIONS = 12, MAX_BACKS = 6, MAX_WAITS = 10;
    /**
     * CamAPS shows a spinner while it connects to the pump (5 to 7 s or more), and while it does the screen has none
     * of the words FX Watcher knows. That is waited out, never answered with Back: Back on CamAPS's main page closes
     * CamAPS, which threw you back to the app you came from (24 Nov 2026). Before any action there is a shorter wait,
     * after one a longer wait, then it stops and says so.
     */
    static final long START_WAIT_MS = 12_000L, LOADING_WAIT_MS = 30_000L;

    /** Whether an unrecognised CamAPS screen should still be waited on. acted: a step has already been taken. */
    static boolean keepWaiting(boolean acted, long sinceMs) {
        return sinceMs < (acted ? LOADING_WAIT_MS : START_WAIT_MS);
    }

    /** MEAL: the bolus calculator. LOW and SLOW: the Add meal page. BOOST and EASE: the home card's modes. */
    enum Kind { MEAL, LOW, SLOW, BOOST, EASE }

    static boolean isMode(Kind k) {
        return k == Kind.BOOST || k == Kind.EASE;
    }

    static final String OPT_MEAL = "Meal or snack", OPT_LOW = "Hypoglycaemia treatment", OPT_SLOW = "Slowly absorbed meal";

    static String optionOf(Kind k) {
        return k == Kind.LOW ? OPT_LOW : k == Kind.SLOW ? OPT_SLOW : OPT_MEAL;
    }

    static String modeName(Kind k) {
        return k == Kind.BOOST ? "Boost" : "Ease-off";
    }

    // ---- pure: what screen is this, and what is the one next step (unit tested) ----

    /**
     * DRAWER: the side menu. ADD_MEAL: the Add meal pages. ADD_CONFIRM: the final "Meal / g CHO" box after
     * Continue. MODE_DURATION / MODE_START / MODE_OFF: the Boost and Ease-off boxes. ENTRY_DIALOG: a
     * half-entered Glucose box (Back cancels it). OTHER_DIALOG: any other box (an alert, a warning): not touched.
     * UNKNOWN: any other page (Statistics, Info, Settings...), left with Back.
     */
    enum Screen { HOME, CALCULATOR, CARBS_DIALOG, GLUCOSE_DIALOG, ENTRY_DIALOG, OTHER_DIALOG, DRAWER, ADD_MEAL, ADD_CONFIRM,
        MODE_DURATION, MODE_START, MODE_OFF, INFO_POPUP, BUSY, UNKNOWN }

    /**
     * INFO_POPUP: CamAPS's "Information" popup that says how long ago insulin was delivered ("1.9 U delivered 8 min
     * ago."). It shows over the calculator, Back does not close it, and it is only a note, so its X is tapped.
     * Any other Information popup (a pump problem, say) is OTHER_DIALOG and left alone.
     */
    private static final Pattern DELIVERED_AGO = Pattern.compile("^\\d+([.,]\\d+)?\\s*u\\s+delivered\\b.*\\bago\\.?$", Pattern.CASE_INSENSITIVE);
    /** "Sensor expires in less than 2 hours." with its grace-period note: a reminder with nothing to act on, so its X is tapped too. */
    private static final Pattern SENSOR_EXPIRES = Pattern.compile("^sensor expires in\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMER = Pattern.compile("^\\d{1,2}:\\d{2}:\\d{2}$");

    enum Step {
        OPEN_TAB, OPEN_CARBS, TYPE,
        OPEN_MENU, OPEN_ADD_MEAL, PICK_HYPO, TYPE_LOW, DISMISS_INFO,
        CONFIRM_CARBS, OPEN_GLUCOSE, TYPE_GLUCOSE, CONFIRM_GLUCOSE, FINISH,
        CONTINUE_ADD, CONFIRM_ADD,
        OPEN_MODE, OPEN_CANCEL, CONFIRM_OFF, TYPE_DURATION, NEXT_DURATION, PICK_NOW, CONFIRM_START,
        WAIT, BACK, ABORT_BUSY, ABORT_OTHER_DIALOG, ABORT_LOST, ABORT_NO_FIELD, ABORT_MISMATCH
    }

    /**
     * Where a request is. Meal: carbs typed, carbs confirmed, glucose box opened, typed, confirmed.
     * Add meal: amount typed, Continue tapped, final box confirmed.
     * Mode: running one cancelled (X tapped, Off confirmed), duration typed, Next tapped, Now picked, Confirm tapped.
     */
    enum Phase { START, TYPED, CARBS_CONFIRMED, GLUCOSE_OPENED, GLUCOSE_TYPED, GLUCOSE_CONFIRMED,
        CONTINUED, ADD_CONFIRMED,
        CANCELLING, OFF_CONFIRMED, DURATION_TYPED, DURATION_NEXT, NOW_PICKED, START_CONFIRMED }

    /** What the Glucose box holds when it opens: nothing (a hint), the glucose the calculator shows, or something else. */
    enum GlucoseBox { EMPTY, MATCH, WRONG }

    static Screen classify(List<String> texts, boolean hasEditable) {
        return classify(texts, hasEditable, false);
    }

    /** hasGlucoseField: an editable field sits inside the box titled Glucose. */
    static Screen classify(List<String> texts, boolean hasEditable, boolean hasGlucoseField) {
        boolean confirm = false, carbsTitle = false, glucoseTitle = false, calc = false, home = false;
        boolean addMeal = false, alerts = false, settings = false, amount = false, cont = false, durationBox = false;
        boolean infoTitle = false, deliveredAgo = false, benignInfo = false, startBox = false, off = false, modeWord = false, gcho = false;
        for (String raw : texts) {
            String l = raw.trim().toLowerCase(Locale.UK);
            if (l.startsWith("delivery") || l.startsWith("delivered") || l.startsWith("delivering")) return Screen.BUSY;
            if (l.equals("information")) infoTitle = true;
            if (DELIVERED_AGO.matcher(raw.trim()).matches()) deliveredAgo = true;
            if (SENSOR_EXPIRES.matcher(raw.trim()).matches()) benignInfo = true;
            if (l.equals("confirm")) confirm = true;
            if (l.equals("carbohydrates") || l.equals("carbohydrate")) carbsTitle = true;
            if (l.equals("glucose")) glucoseTitle = true;
            if (l.equals("total units") || l.equals("meal size")) calc = true;
            if (l.equals("g cho")) {
                calc = true;
                gcho = true;
            }
            if (l.equals("auto mode")) home = true;
            if (l.equals("add meal")) addMeal = true;
            if (l.equals("alerts")) alerts = true;
            if (l.equals("settings")) settings = true;
            if (l.equals("amount")) amount = true;
            if (l.equals("continue")) cont = true;
            if (l.equals("boost duration") || l.equals("ease-off duration")) durationBox = true;
            if (l.equals("boost start") || l.equals("ease-off start")) startBox = true;
            if (l.equals("off")) off = true;
            if (l.equals("boost") || l.equals("ease-off")) modeWord = true;
        }
        if (infoTitle && (deliveredAgo || benignInfo)) return Screen.INFO_POPUP;
        if (infoTitle) return Screen.OTHER_DIALOG;
        if (addMeal && alerts && settings) return Screen.DRAWER;
        if (startBox && confirm) return Screen.MODE_START;
        if (durationBox) return Screen.MODE_DURATION;
        if (confirm && off && modeWord && !carbsTitle && !glucoseTitle) return Screen.MODE_OFF;
        if (confirm) {
            if (carbsTitle && hasEditable) return Screen.CARBS_DIALOG;
            if (!carbsTitle && glucoseTitle && hasGlucoseField) return Screen.GLUCOSE_DIALOG;
            if (!carbsTitle && glucoseTitle) return Screen.ENTRY_DIALOG;
            if (!carbsTitle && !glucoseTitle && gcho) return Screen.ADD_CONFIRM;
            return Screen.OTHER_DIALOG;
        }
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
     * The one step from this screen for this kind of request, at the start. hypoSelected: the Add meal page has
     * this kind's option selected (and the others not). hasAmount: it has its Amount field.
     */
    static Step decide(Screen s, int backs, Kind kind, boolean hypoSelected, boolean hasAmount) {
        if (kind == Kind.MEAL) return decideMeal(s, backs, Phase.START, false, GlucoseBox.EMPTY, false);
        if (isMode(kind)) return decideMode(s, backs, Phase.START, false, true, false, false);
        return decideAdd(s, backs, Phase.START, hypoSelected, hasAmount, false, false);
    }

    /**
     * The meal request's next step. phase: how far it has got. correction: also confirm the glucose.
     * glucoseBox: what the Glucose box holds (only used when that box is open). carbsOnCalc: the calculator's
     * Carbs value reads back as the grams asked for.
     */
    static Step decideMeal(Screen s, int backs, Phase phase, boolean correction, GlucoseBox glucoseBox, boolean carbsOnCalc) {
        if (s == Screen.BUSY) return Step.ABORT_BUSY;
        if (s == Screen.OTHER_DIALOG) return Step.ABORT_OTHER_DIALOG;
        if (s == Screen.INFO_POPUP) return Step.DISMISS_INFO;
        switch (phase) {
            case START:
                switch (s) {
                    case HOME: return Step.OPEN_TAB;
                    case CALCULATOR: return Step.OPEN_CARBS;
                    case CARBS_DIALOG: return Step.TYPE;
                    default: return backs < MAX_BACKS ? Step.BACK : Step.ABORT_LOST;   // DRAWER, ADD_MEAL, other boxes, other pages
                }
            case TYPED:
                if (s == Screen.CARBS_DIALOG) return Step.CONFIRM_CARBS;
                return backs < MAX_BACKS && s != Screen.CALCULATOR ? Step.BACK : Step.ABORT_LOST;
            case CARBS_CONFIRMED:
            case GLUCOSE_OPENED:
                if (s == Screen.CALCULATOR) {
                    if (!carbsOnCalc) return Step.ABORT_MISMATCH;
                    return correction ? Step.OPEN_GLUCOSE : Step.FINISH;
                }
                if (s == Screen.GLUCOSE_DIALOG && phase == Phase.GLUCOSE_OPENED) {
                    return glucoseBox == GlucoseBox.EMPTY ? Step.TYPE_GLUCOSE
                            : glucoseBox == GlucoseBox.MATCH ? Step.CONFIRM_GLUCOSE : Step.ABORT_MISMATCH;
                }
                return backs < MAX_BACKS ? Step.BACK : Step.ABORT_LOST;
            case GLUCOSE_TYPED:
                if (s == Screen.GLUCOSE_DIALOG) return Step.CONFIRM_GLUCOSE;
                return Step.ABORT_LOST;
            default:    // GLUCOSE_CONFIRMED
                if (s == Screen.CALCULATOR) return carbsOnCalc ? Step.FINISH : Step.ABORT_MISMATCH;
                return backs < MAX_BACKS ? Step.BACK : Step.ABORT_LOST;
        }
    }

    /**
     * Slow meal or low treatment through Add meal. selected: this kind's option is on (and the others off).
     * hasAmount: the Amount field exists. amountOk: it reads back as the grams. confirmOk: the final box shows the
     * same grams. settled: the final box has been gone for a moment.
     */
    static Step decideAdd(Screen s, int backs, Phase phase, boolean selected, boolean hasAmount, boolean amountOk, boolean confirmOk) {
        return decideAdd(s, backs, phase, selected, hasAmount, amountOk, confirmOk, false);
    }

    static Step decideAdd(Screen s, int backs, Phase phase, boolean selected, boolean hasAmount, boolean amountOk, boolean confirmOk, boolean settled) {
        if (s == Screen.BUSY) return Step.ABORT_BUSY;
        if (s == Screen.OTHER_DIALOG) return Step.ABORT_OTHER_DIALOG;
        if (s == Screen.INFO_POPUP) return Step.DISMISS_INFO;
        switch (phase) {
            case START:
                switch (s) {
                    case HOME:
                    case CALCULATOR: return Step.OPEN_MENU;
                    case DRAWER: return Step.OPEN_ADD_MEAL;
                    case ADD_MEAL: return !selected ? Step.PICK_HYPO : hasAmount ? Step.TYPE_LOW : Step.ABORT_NO_FIELD;
                    default: return backs < MAX_BACKS ? Step.BACK : Step.ABORT_LOST;   // the bolus box, entry boxes, other pages
                }
            case TYPED:
                if (s == Screen.ADD_MEAL) return selected && amountOk ? Step.CONTINUE_ADD : Step.ABORT_MISMATCH;
                return Step.ABORT_LOST;
            case CONTINUED:
                if (s == Screen.ADD_CONFIRM) return confirmOk ? Step.CONFIRM_ADD : Step.ABORT_MISMATCH;
                return s == Screen.ADD_MEAL || s == Screen.UNKNOWN ? Step.WAIT : Step.ABORT_LOST;
            default:    // ADD_CONFIRMED
                if (s == Screen.ADD_CONFIRM) return Step.WAIT;
                return settled ? Step.FINISH : Step.WAIT;
        }
    }

    /**
     * Boost or Ease-off. modeRunning: a mode timer is on the home card. dialogMatches: the Duration or Start
     * box's title is this mode's (not the other one's). durationOk: the Hrs and Mins read back as asked.
     * bannerOk: the running banner shows this mode with about this duration left.
     */
    static Step decideMode(Screen s, int backs, Phase phase, boolean modeRunning, boolean dialogMatches, boolean durationOk, boolean bannerOk) {
        if (s == Screen.BUSY) return Step.ABORT_BUSY;
        if (s == Screen.OTHER_DIALOG) return Step.ABORT_OTHER_DIALOG;
        if (s == Screen.INFO_POPUP) return Step.DISMISS_INFO;
        if ((s == Screen.MODE_DURATION || s == Screen.MODE_START) && !dialogMatches) return Step.ABORT_MISMATCH;
        switch (s) {
            case HOME:
                switch (phase) {
                    case START: return modeRunning ? Step.OPEN_CANCEL : Step.OPEN_MODE;
                    case CANCELLING: return Step.WAIT;                          // the Off box is on its way
                    case OFF_CONFIRMED: return modeRunning ? Step.WAIT : Step.OPEN_MODE;
                    case START_CONFIRMED: return bannerOk ? Step.FINISH : Step.WAIT;
                    default: return Step.ABORT_LOST;                            // a box we filled in has gone
                }
            case MODE_OFF:
                return phase == Phase.CANCELLING ? Step.CONFIRM_OFF : phase == Phase.OFF_CONFIRMED ? Step.WAIT : Step.BACK;
            case MODE_DURATION:
                if (phase == Phase.START || phase == Phase.OFF_CONFIRMED) return Step.TYPE_DURATION;
                if (phase == Phase.DURATION_TYPED) return durationOk ? Step.NEXT_DURATION : Step.ABORT_MISMATCH;
                return phase == Phase.DURATION_NEXT ? Step.WAIT : Step.ABORT_LOST;
            case MODE_START:
                if (phase == Phase.DURATION_NEXT) return Step.PICK_NOW;
                if (phase == Phase.NOW_PICKED) return Step.CONFIRM_START;
                return phase == Phase.START_CONFIRMED ? Step.WAIT : Step.ABORT_LOST;
            default:
                if (phase == Phase.START || phase == Phase.OFF_CONFIRMED) return backs < MAX_BACKS ? Step.BACK : Step.ABORT_LOST;
                return Step.WAIT;
        }
    }

    /** The glucose the box holds is the one the calculator shows, to a tenth. */
    static boolean glucoseMatches(String shown, double expected) {
        if (shown == null || Double.isNaN(expected)) return false;
        String s = shown.trim().replace(',', '.');
        if (!s.matches("\\d{1,2}(\\.\\d)?")) return false;
        return Math.abs(Double.parseDouble(s) - expected) <= 0.05;
    }

    /** The calculator's glucose agrees with the last reading FX Watcher saw (within 0.3), so it is the real current one. */
    static boolean glucoseSane(double onScreen, double lastReading) {
        return onScreen > 0 && lastReading > 0 && Math.abs(onScreen - lastReading) <= 0.3;
    }

    /** The box shows exactly the amount: "30", "30.0" or "30 " but not "3", "300" or "". */
    static boolean verified(String shown, int grams) {
        if (shown == null) return false;
        String s = shown.trim().replace(',', '.');
        if (!s.matches("\\d{1,3}(\\.0+)?")) return false;
        return (int) Math.round(Double.parseDouble(s)) == grams;
    }

    /** "02:00:00" is 120, "01:59:56" is 119; null or anything else is -1. */
    static int timerMinutes(String t) {
        if (t == null || !TIMER.matcher(t.trim()).matches()) return -1;
        String[] p = t.trim().split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    /** Hrs and Mins as typed ("2", "00", "30") to minutes, or -1 if either is not a plain number. */
    static int durationMinutes(String hrs, String mins) {
        if (hrs == null || mins == null) return -1;
        String h = hrs.trim(), m = mins.trim();
        if (!h.matches("\\d{1,2}") || !m.matches("\\d{1,2}")) return -1;
        int mm = Integer.parseInt(m);
        if (mm > 59) return -1;
        return Integer.parseInt(h) * 60 + mm;
    }

    /** The running banner shows this mode and about this long left (a few minutes under, never over). */
    static boolean bannerMatches(List<String> texts, Kind kind, int minutes) {
        boolean name = false;
        int left = -1;
        for (String t : texts) {
            if (t.trim().equalsIgnoreCase(modeName(kind))) name = true;
            int m = timerMinutes(t);
            if (m >= 0) left = m;
        }
        return name && left >= 0 && left <= minutes && left >= minutes - 6;
    }

    /** The mode box on screen belongs to this kind: its title says Boost (or Ease-off) duration/start. */
    static boolean dialogMatches(List<String> texts, Kind kind) {
        String mine = modeName(kind).toLowerCase(Locale.UK), other = modeName(kind == Kind.BOOST ? Kind.EASE : Kind.BOOST).toLowerCase(Locale.UK);
        boolean ours = false;
        for (String t : texts) {
            String l = t.trim().toLowerCase(Locale.UK);
            if (l.equals(mine + " duration") || l.equals(mine + " start")) ours = true;
            if (l.equals(other + " duration") || l.equals(other + " start")) return false;
        }
        return ours;
    }

    /** True when a mode timer is on the home card (a Boost or Ease-off is running). */
    static boolean modeRunning(List<String> texts) {
        for (String t : texts) if (timerMinutes(t) >= 0) return true;
        return false;
    }

    // ---- a plan: the steps in a fixed, safe order ----

    static final class Item {
        final Kind kind;
        final int amount;       // grams, or minutes for BOOST and EASE
        final String label;

        Item(Kind kind, int amount, String label) {
            this.kind = kind;
            this.amount = amount;
            this.label = label;
        }
    }

    static int rank(Kind k) {
        return k == Kind.LOW ? 0 : k == Kind.SLOW ? 1 : isMode(k) ? 2 : 3;
    }

    /** Why this list of steps can't run as a plan, or null. At most one of each; low only alone; the meal last. */
    static String validatePlan(List<Item> items) {
        if (items == null || items.isEmpty()) return "Nothing to do.";
        if (items.size() > 4) return "Too many steps.";
        int prev = -1;
        boolean boost = false, ease = false;
        java.util.EnumSet<Kind> seen = java.util.EnumSet.noneOf(Kind.class);
        for (Item it : items) {
            if (!seen.add(it.kind)) return "Each step can only appear once.";
            if (rank(it.kind) < prev) return "The steps must go: slow meal, then Boost or Ease-off, then the meal.";
            prev = rank(it.kind);
            if (it.kind == Kind.BOOST) boost = true;
            if (it.kind == Kind.EASE) ease = true;
            if (!CarbOptions.validFor(it.kind, it.amount)) return "That amount isn't allowed for " + describe(it) + ".";
        }
        if (boost && ease) return "Boost and Ease-off can't both be started.";
        if (seen.contains(Kind.LOW) && items.size() > 1) return "A low treatment runs on its own.";
        return null;
    }

    /**
     * "SLOW:15;BOOST:120;MEAL:45" to steps (BOOST and EASE in minutes, the rest in grams), or null if any part is
     * not a known step with a whole number. Order and ranges are checked by validatePlan.
     */
    static List<Item> parseSteps(String s, String label) {
        if (s == null || s.trim().isEmpty()) return null;
        List<Item> out = new ArrayList<>();
        for (String part : s.split(";")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) return null;
            Kind k;
            try {
                k = Kind.valueOf(kv[0].trim().toUpperCase(Locale.UK));
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (!kv[1].trim().matches("\\d{1,3}")) return null;
            out.add(new Item(k, Integer.parseInt(kv[1].trim()), label));
        }
        return out;
    }

    /**
     * The step in CamAPS's own words, so it can't be mistaken for something else: the meal kind is the BOLUS
     * CALCULATOR's carbs (only filled in, nothing is recorded or delivered until you tap Next and Deliver), never
     * Add meal. The Add meal entries and Boost / Ease-off are recorded by CamAPS as soon as they are confirmed.
     */
    static String describe(Item it) {
        switch (it.kind) {
            case LOW: return "Add meal \u203a Hypoglycaemia treatment, " + it.amount + " g";
            case SLOW: return "Add meal \u203a Slowly absorbed meal, " + it.amount + " g";
            case BOOST: return "Boost, " + minutesText(it.amount) + ", starting now";
            case EASE: return "Ease-off, " + minutesText(it.amount) + ", starting now";
            default: return "Bolus calculator, carbs " + it.amount + " g";
        }
    }

    /** What CamAPS does with the step once it is confirmed. */
    static String effect(Item it) {
        return it.kind == Kind.MEAL ? "only filled in, nothing is recorded"
                : isMode(it.kind) ? "starts as soon as it is confirmed" : "recorded by CamAPS as soon as it is confirmed";
    }

    static String minutesText(int m) {
        return m % 60 == 0 ? (m / 60) + " h" : m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min";
    }

    static String why(Step s) {
        switch (s) {
            case ABORT_BUSY: return "a delivery is on screen";
            case ABORT_OTHER_DIALOG: return "a different box is open (not one I fill in)";
            case ABORT_NO_FIELD: return "the Add meal page has no Amount field";
            case ABORT_MISMATCH: return "what CamAPS shows is not what I entered";
            default: return "I couldn't tell which CamAPS screen this is";
        }
    }

    // ---- the request ----

    private static final class Req {
        final int grams;            // grams, or minutes for a mode
        final String label;
        final Kind kind;
        final long deadline;
        final boolean correction;
        int actions, backs, waits;
        boolean waitingLogged;
        long lastAt;
        Step lastStep;
        int sameStep;
        boolean finished;
        Phase phase = Phase.START;
        double calcGlucose = Double.NaN;      // the glucose the calculator showed before the Glucose box opened

        Req(int grams, String label, Kind kind, boolean correction, long deadline) {
            this.grams = grams;
            this.label = label;
            this.kind = kind;
            this.correction = correction && kind == Kind.MEAL;
            this.deadline = deadline;
        }
    }

    private static final class Plan {
        final List<Item> items;
        final boolean correction;
        String key;
        int idx;
        final List<String> done = new ArrayList<>();

        Plan(List<Item> items, boolean correction) {
            this.items = items;
            this.correction = correction;
        }
    }

    private static volatile Req req;
    private static volatile Plan plan;
    private static AccessibilityService svcRef;

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
                    : isMode(kind)
                    ? "Boost and Ease-off run from " + CarbOptions.MODE_MIN + " minutes to " + CarbOptions.MODE_MAX / 60 + " hours."
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
        return request(c, grams, label, kind, Store.p(c).getBoolean(Store.CARB_CORRECTION, false));
    }

    /** correction: for a meal, also confirm the glucose so CamAPS adds a correction. Ignored for the other kinds. */
    static String request(Context c, int grams, String label, Kind kind, boolean correction) {
        return runPlan(c, java.util.Collections.singletonList(new Item(kind, grams, label)), correction);
    }

    /**
     * A person approved a plan (the plan screen's Run button). The steps run in order in CamAPS, the last
     * one a meal if there is one, and stop at the first that fails. Returns a reason it did not start, or null.
     */
    static String runPlan(Context c, List<Item> items, boolean correction) {
        return runPlan(c, items, correction, null);
    }

    /** key: the sender's idempotency key. A fresh run of it is recorded, and each finished step is added to that record. */
    static String runPlan(Context c, List<Item> items, boolean correction, String key) {
        String bad = validatePlan(items);
        if (bad != null) return bad;
        for (Item it : items) {
            String no = refusal(c, it.amount, it.kind);
            if (no != null) return no;
        }
        long now = System.currentTimeMillis();
        plan = new Plan(new ArrayList<>(items), correction);
        if (PlanRuns.validKey(key)) {
            plan.key = key;
            Store.p(c).edit().putString(Store.CARB_RUNS, PlanRuns.start(Store.p(c).getString(Store.CARB_RUNS, ""), key, now)).apply();
        }
        Item first = items.get(0);
        req = new Req(first.amount, first.label, first.kind, correction, now + TTL_MS);
        StringBuilder sb = new StringBuilder();
        for (Item it : items) sb.append(sb.length() == 0 ? "" : ", ").append(describe(it));
        Store.logCam(c, "Carbs for CamAPS: asked for " + sb + (correction && items.get(items.size() - 1).kind == Kind.MEAL ? " WITH CORRECTION" : ""));
        Intent i = Calm.camapsLaunch(c);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            req = null;
            plan = null;
            return "Couldn't open CamAPS.";
        }
        progress(c, "FX Watcher working · hands off",
                "Opening CamAPS and filling in " + sb + ". It can take 10 to 20 seconds while CamAPS connects to the pump. "
                + "Please don't tap anything until you get a notification saying it's done.");
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
        svcRef = svc;
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
        AccessibilityNodeInfo boxField = dialogField(root, "Carbohydrates");
        AccessibilityNodeInfo glucoseBox = dialogField(root, "Glucose");
        Screen screen = classify(texts, boxField != null, glucoseBox != null);
        String carbsShown = screen == Screen.CALCULATOR ? valueText(root, "Carbs") : null;
        boolean carbsOnCalc = carbsShown != null && verified(carbsShown, r.grams);
        GlucoseBox gb = GlucoseBox.EMPTY;
        if (screen == Screen.GLUCOSE_DIALOG) {
            String held = glucoseBox.getText() == null ? "" : glucoseBox.getText().toString();
            gb = glucoseMatches(held, r.calcGlucose) ? GlucoseBox.MATCH : held.trim().matches(".*\\d.*") ? GlucoseBox.WRONG : GlucoseBox.EMPTY;
        }
        AccessibilityNodeInfo amountBox = screen == Screen.ADD_MEAL ? amountField(root) : null;
        boolean selected = screen == Screen.ADD_MEAL && optionKind(r.kind) && optionSelected(root, r.kind);
        boolean amountOk = amountBox != null && amountBox.getText() != null && verified(amountBox.getText().toString(), r.grams);
        boolean addConfirmOk = screen == Screen.ADD_CONFIRM && addConfirmMatches(root, texts, r.grams);
        boolean settled = now - r.lastAt >= 1_500L;
        int durationRead = screen == Screen.MODE_DURATION ? readDuration(root) : -1;
        boolean durationOk = durationRead == r.grams;
        boolean running = modeRunning(texts);
        boolean bannerOk = isMode(r.kind) && bannerMatches(texts, r.kind, r.grams);
        if (screen == Screen.UNKNOWN) {
            boolean acted = r.lastStep != null;
            long since = acted ? now - r.lastAt : now - (r.deadline - TTL_MS);
            if (keepWaiting(acted, since)) {
                if (!r.waitingLogged) {
                    r.waitingLogged = true;
                    Store.logCam(c, "Carbs for CamAPS: waiting, CamAPS shows a screen I don't know (probably connecting to the pump)" + dump(root));
                    progress(c, "Waiting for the pump · hands off",
                            "Waiting for CamAPS to finish connecting to the pump. Please don't tap anything, I'll carry on by myself.");
                }
                return;
            }
            if (acted) {
                finish(c, r, false, "CamAPS was still showing a screen I don't know after " + (LOADING_WAIT_MS / 1000) + " seconds (usually it is still connecting to the pump). "
                        + "I stopped without pressing anything", root);
                return;
            }
        }
        if (screen == Screen.HOME && isMode(r.kind) && (r.lastStep == Step.OPEN_MODE || r.lastStep == Step.OPEN_CANCEL)
                && now - r.lastAt < SETTLE_MS) return;       // the box is still opening: don't tap the button twice
        Step step = r.kind == Kind.MEAL
                ? decideMeal(screen, r.backs, r.phase, r.correction, gb, carbsOnCalc)
                : isMode(r.kind)
                ? decideMode(screen, r.backs, r.phase, running, dialogMatches(texts, r.kind), durationOk, bannerOk)
                : decideAdd(screen, r.backs, r.phase, selected, amountBox != null, amountOk, addConfirmOk, settled);
        if (step == Step.WAIT) {
            if (++r.waits > MAX_WAITS) finish(c, r, false, "CamAPS didn't move on after the last tap (" + screen + ")", root);
            return;
        }
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
        long actionsBefore = r.actions;
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
                String shown = type(box, String.valueOf(r.grams));
                if (verified(shown, r.grams)) {
                    r.phase = Phase.TYPED;
                    recheck(c, svc);
                } else {
                    finish(c, r, false, "the box shows \"" + shown + "\" instead of " + r.grams + ". Fix it before you confirm", root);
                }
                break;
            }
            case CONFIRM_CARBS: {
                // only here, only with the Carbohydrates box open and its number read back as asked
                String held = boxField == null || boxField.getText() == null ? "" : boxField.getText().toString();
                AccessibilityNodeInfo ok = boxField == null || !verified(held, r.grams) ? null : confirmButton(root);
                if (ok == null) {
                    finish(c, r, false, "the Carbohydrates box no longer shows " + r.grams + " or has no Confirm", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(ok);
                r.phase = Phase.CARBS_CONFIRMED;
                break;
            }
            case OPEN_GLUCOSE: {
                double shown = parseGlucose(valueText(root, "Glucose"));
                double last = Store.p(c).getFloat(Store.MMOL, -1f);
                if (!glucoseSane(shown, last)) {
                    finish(c, r, false, "the glucose on the calculator (" + valueText(root, "Glucose") + ") doesn't match the last reading (" + last + ")", root);
                    return;
                }
                AccessibilityNodeInfo glucose = glucoseField(root);
                if (glucose == null) {
                    finish(c, r, false, "I couldn't find the Glucose field", root);
                    return;
                }
                r.calcGlucose = shown;
                r.actions++;
                r.lastAt = now;
                tap(glucose);
                r.phase = Phase.GLUCOSE_OPENED;
                break;
            }
            case TYPE_GLUCOSE: {
                if (glucoseBox == null || Double.isNaN(r.calcGlucose)) {
                    finish(c, r, false, "the Glucose box has no field", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                String wanted = String.format(Locale.US, "%.1f", r.calcGlucose);
                String shown = type(glucoseBox, wanted);
                if (glucoseMatches(shown, r.calcGlucose)) {
                    r.phase = Phase.GLUCOSE_TYPED;
                    recheck(c, svc);
                } else {
                    finish(c, r, false, "the Glucose box shows \"" + shown + "\" instead of " + wanted, root);
                }
                break;
            }
            case CONFIRM_GLUCOSE: {
                String held = glucoseBox == null || glucoseBox.getText() == null ? "" : glucoseBox.getText().toString();
                AccessibilityNodeInfo ok = glucoseBox == null || !glucoseMatches(held, r.calcGlucose) ? null : confirmButton(root);
                if (ok == null) {
                    finish(c, r, false, "the Glucose box no longer shows the calculator's glucose or has no Confirm", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(ok);
                r.phase = Phase.GLUCOSE_CONFIRMED;
                break;
            }
            case FINISH:
                finish(c, r, true, null, null);
                return;
            case TYPE_LOW: {
                r.actions++;
                r.lastAt = now;
                String shown = type(amountBox, String.valueOf(r.grams));
                boolean stillRight = optionSelected(root, r.kind);
                if (verified(shown, r.grams) && stillRight) {
                    r.phase = Phase.TYPED;
                    recheck(c, svc);
                } else {
                    finish(c, r, false, "the Amount shows \"" + shown + "\" and " + optionOf(r.kind) + " is "
                            + (stillRight ? "selected" : "NOT selected") + ". Check both before you continue", root);
                }
                break;
            }
            case CONTINUE_ADD: {
                // only on the Add meal page, with this kind's option on and the amount read back
                AccessibilityNodeInfo cont = continueButton(root);
                if (cont == null) {
                    finish(c, r, false, "I couldn't find Continue on the Add meal page", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(cont);
                r.phase = Phase.CONTINUED;
                break;
            }
            case CONFIRM_ADD: {
                AccessibilityNodeInfo ok = addConfirmOk ? confirmButton(root) : null;
                if (ok == null) {
                    finish(c, r, false, "the final box doesn't show " + r.grams + " g as " + optionOf(r.kind).toLowerCase(Locale.UK), root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(ok);
                r.phase = Phase.ADD_CONFIRMED;
                break;
            }
            case OPEN_MODE: {
                AccessibilityNodeInfo modeBtn = modeButton(root, r.kind);
                if (modeBtn == null) {
                    finish(c, r, false, "I couldn't find the " + modeName(r.kind) + " button", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(modeBtn);
                break;
            }
            case OPEN_CANCEL: {
                // a Boost or Ease-off is already running: its X on the home card, then its Off box
                AccessibilityNodeInfo cancelBtn = modeCancelButton(root);
                if (cancelBtn == null) {
                    finish(c, r, false, "a mode is running and I couldn't find its X", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(cancelBtn);
                r.phase = Phase.CANCELLING;
                break;
            }
            case CONFIRM_OFF: {
                AccessibilityNodeInfo ok = confirmButton(root);
                if (ok == null) {
                    finish(c, r, false, "I couldn't find Confirm on the Off box", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(ok);
                r.phase = Phase.OFF_CONFIRMED;
                break;
            }
            case TYPE_DURATION: {
                List<AccessibilityNodeInfo> fields = durationFields(root);
                if (fields.size() != 2) {
                    finish(c, r, false, "I expected an Hrs and a Mins field and found " + fields.size(), root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                type(fields.get(0), String.valueOf(r.grams / 60));
                type(fields.get(1), String.valueOf(r.grams % 60));
                int got = readDuration(root);
                if (got == r.grams) {
                    r.phase = Phase.DURATION_TYPED;
                    recheck(c, svc);
                } else {
                    finish(c, r, false, "the duration reads back as " + (got < 0 ? "something I can't read" : minutesText(got)) + " instead of " + minutesText(r.grams), root);
                }
                break;
            }
            case NEXT_DURATION: {
                // only on the Boost or Ease-off Duration box, after Hrs and Mins read back as asked
                AccessibilityNodeInfo next = durationOk ? nextButton(root) : null;
                if (next == null) {
                    finish(c, r, false, "the duration isn't " + minutesText(r.grams) + " or there is no Next", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(next);
                r.phase = Phase.DURATION_NEXT;
                break;
            }
            case PICK_NOW: {
                AccessibilityNodeInfo now_ = nowChip(root);
                if (now_ == null) {
                    finish(c, r, false, "I couldn't find Now on the Start box", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(now_);
                r.phase = Phase.NOW_PICKED;
                break;
            }
            case CONFIRM_START: {
                AccessibilityNodeInfo ok = confirmButton(root);
                if (ok == null) {
                    finish(c, r, false, "I couldn't find Confirm on the Start box", root);
                    return;
                }
                r.actions++;
                r.lastAt = now;
                tap(ok);
                r.phase = Phase.START_CONFIRMED;
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
        if (r.actions > actionsBefore && !r.finished) recheck(c, svc);
    }

    private static boolean optionKind(Kind k) {
        return k == Kind.LOW || k == Kind.SLOW;
    }

    // ---- looking again without waiting for an event ----

    private static Handler mainHandler;

    private static synchronized Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    /** Three more looks at CamAPS's screen after an action, in case no further event comes. Does nothing once the request is over. */
    private static void recheck(Context c, AccessibilityService svc) {
        for (long delay : RECHECK_MS) {
            main().postDelayed(() -> {
                if (!active()) return;
                try {
                    AccessibilityNodeInfo root = camapsRoot(svc);
                    if (root != null) maybeAct(c, svc, root, visibleTexts(root));
                } catch (RuntimeException ignored) { }
            }, delay);
        }
    }

    /** CamAPS's window: the active one, or (the keyboard may be active) the CamAPS window in the list. */
    private static AccessibilityNodeInfo camapsRoot(AccessibilityService svc) {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root != null && root.getPackageName() != null && root.getPackageName().toString().startsWith("com.camdiab.")) return root;
        List<AccessibilityWindowInfo> windows = svc.getWindows();
        if (windows == null) return null;
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && r.getPackageName() != null && r.getPackageName().toString().startsWith("com.camdiab.")) return r;
        }
        return null;
    }

    /** The text or description of everything visible, in tree order (what the reader hands maybeAct). */
    static List<String> visibleTexts(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        List<String> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : all) {
            if (!n.isVisibleToUser()) continue;
            CharSequence t = n.getText() != null && n.getText().length() > 0 ? n.getText() : n.getContentDescription();
            if (t != null && t.length() > 0) out.add(t.toString().trim());
        }
        return out;
    }

    /** The only click in this file. */
    private static boolean tap(AccessibilityNodeInfo n) {
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /** The only text entry in this file: sets the text (grams, or the calculator's own glucose), then reads the field back. */
    private static String type(AccessibilityNodeInfo box, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        box.refresh();
        String shown = box.getText() == null ? "" : box.getText().toString();
        return ok ? shown : "(not set) " + shown;
    }

    private static void finish(Context c, Req r, boolean ok, String problem, AccessibilityNodeInfo root) {
        r.finished = true;
        req = null;
        Plan p = plan;
        if (!ok && root != null) Store.logCam(c, "Carbs for CamAPS: the screen was:" + dump(root));
        Store.logCam(c, ok ? "Carbs for CamAPS: " + describe(new Item(r.kind, r.grams, r.label)) + " done" + (r.correction ? ", with glucose" : "")
                : "Carbs for CamAPS: stopped, " + problem);
        if (ok && p != null && p.key != null) {
            Store.p(c).edit().putString(Store.CARB_RUNS, PlanRuns.stepDone(Store.p(c).getString(Store.CARB_RUNS, ""), p.key,
                    System.currentTimeMillis(), r.kind.name(), p.idx + 1 >= p.items.size())).apply();
        }
        if (ok && p != null && p.idx + 1 < p.items.size()) {
            // the next step of the plan carries on in the same CamAPS window
            p.done.add(describe(new Item(r.kind, r.grams, r.label)));
            p.idx++;
            Item nx = p.items.get(p.idx);
            req = new Req(nx.amount, nx.label, nx.kind, p.correction, System.currentTimeMillis() + TTL_MS);
            if (svcRef != null) recheck(c, svcRef);
            return;
        }
        plan = null;
        notifyResult(c, p, r, ok, problem);
    }

    // ---- finding things ----

    /**
     * The one field that may be typed into for a meal: an editable field that sits between the
     * "Carbohydrates" title and the Confirm button of the box that is open. Fields behind the box
     * are never chosen.
     */
    static AccessibilityNodeInfo dialogField(AccessibilityNodeInfo root, String titleText) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        AccessibilityNodeInfo title = null, confirm = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (!n.isVisibleToUser() || t == null) continue;
            String s = t.toString().trim();
            if (title == null && s.equalsIgnoreCase(titleText)) title = n;
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

    /** The Boost or Ease-off button on the home card (or its clickable parent). Only called on the home page. */
    static AccessibilityNodeInfo modeButton(AccessibilityNodeInfo root, Kind kind) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        return clickableUp(byText(all, modeName(kind)));
    }

    /** The X of the running mode's banner: the rightmost clickable node on the timer's row. */
    static AccessibilityNodeInfo modeCancelButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        AccessibilityNodeInfo timer = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (n.isVisibleToUser() && t != null && timerMinutes(t.toString()) >= 0) {
                timer = n;
                break;
            }
        }
        if (timer == null) return null;
        Rect tb = new Rect(), b = new Rect();
        timer.getBoundsInScreen(tb);
        AccessibilityNodeInfo best = null;
        int bestX = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : all) {
            if (!n.isVisibleToUser() || !n.isClickable()) continue;
            n.getBoundsInScreen(b);
            if (Math.abs(b.centerY() - tb.centerY()) <= tb.height() * 1.5 && b.centerX() > tb.centerX() && b.centerX() > bestX) {
                best = n;
                bestX = b.centerX();
            }
        }
        return best;
    }

    /** The Next button of the Boost or Ease-off Duration box. Only called with that box open and read back. */
    static AccessibilityNodeInfo nextButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        return clickableUp(byText(all, "Next"));
    }

    /** The Continue button on the Add meal page. Only called after the option and amount read back. */
    static AccessibilityNodeInfo continueButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        return clickableUp(byText(all, "Continue"));
    }

    /** The Now chip on the Boost or Ease-off Start box. */
    static AccessibilityNodeInfo nowChip(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        return clickableUp(byText(all, "Now"));
    }

    /** The Hrs and Mins fields of the Duration box: editable nodes between its title and its Next, left to right. */
    static List<AccessibilityNodeInfo> durationFields(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        AccessibilityNodeInfo title = null, next = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (!n.isVisibleToUser() || t == null) continue;
            String l = t.toString().trim().toLowerCase(Locale.UK);
            if (title == null && (l.equals("boost duration") || l.equals("ease-off duration"))) title = n;
            if (next == null && l.equals("next")) next = n;
        }
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (title == null || next == null) return out;
        Rect tb = new Rect(), nb = new Rect(), b = new Rect();
        title.getBoundsInScreen(tb);
        next.getBoundsInScreen(nb);
        for (AccessibilityNodeInfo n : all) {
            if (!n.isVisibleToUser() || !n.isEditable()) continue;
            n.getBoundsInScreen(b);
            if (b.centerY() > tb.bottom - 4 && b.centerY() < nb.top + 4) out.add(n);
        }
        java.util.Collections.sort(out, (x, y) -> {
            Rect bx = new Rect(), by = new Rect();
            x.getBoundsInScreen(bx);
            y.getBoundsInScreen(by);
            return Integer.compare(bx.centerX(), by.centerX());
        });
        return out;
    }

    /** What the Hrs and Mins fields hold, in minutes, or -1 if they aren't two plain numbers. */
    static int readDuration(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> f = durationFields(root);
        if (f.size() != 2) return -1;
        for (AccessibilityNodeInfo n : f) n.refresh();
        return durationMinutes(f.get(0).getText() == null ? null : f.get(0).getText().toString(),
                f.get(1).getText() == null ? null : f.get(1).getText().toString());
    }

    /**
     * The final box after Continue: it shows the same whole grams as asked.
     */
    static boolean addConfirmMatches(AccessibilityNodeInfo root, List<String> texts, int grams) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        AccessibilityNodeInfo unit = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (n.isVisibleToUser() && t != null && t.toString().trim().equalsIgnoreCase("g CHO")) unit = n;
        }
        if (unit == null) return false;
        Rect ub = new Rect(), b = new Rect();
        unit.getBoundsInScreen(ub);
        String best = null;
        int bestDy = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (n == unit || !n.isVisibleToUser() || t == null || !t.toString().trim().matches("\\d{1,3}")) continue;
            n.getBoundsInScreen(b);
            int dy = ub.top - b.bottom;
            if (dy < -10 || Math.abs(b.centerX() - ub.centerX()) > ub.width()) continue;
            if (dy < bestDy) {
                bestDy = dy;
                best = t.toString().trim();
            }
        }
        // CamAPS titles this box "Meal" for every kind, so the title can't tell them apart: the option and amount
        // were read back on the Add meal page just before Continue, and the grams are checked again here.
        return best != null && verified(best, grams);
    }

    private static AccessibilityNodeInfo clickableUp(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo t = n;
        for (int up = 0; t != null && !t.isClickable() && up < 3; up++) t = t.getParent();
        return t != null && t.isClickable() ? t : null;
    }

    private static final Pattern AMOUNT = Pattern.compile("^(---|\\d{1,3}([.,]\\d)?)$");

    /** The Carbs value on the calculator, or its clickable parent. */
    static AccessibilityNodeInfo carbsField(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo v = valueNode(root, "Carbs");
        return v == null ? null : clickableUp(v);
    }

    /** The Glucose value on the calculator, or its clickable parent. */
    static AccessibilityNodeInfo glucoseField(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo v = valueNode(root, "Glucose");
        return v == null ? null : clickableUp(v);
    }

    /** The text of the value on a calculator row ("20", "---", "5.9"), or null. */
    static String valueText(AccessibilityNodeInfo root, String label) {
        AccessibilityNodeInfo v = valueNode(root, label);
        return v == null || v.getText() == null ? null : v.getText().toString().trim();
    }

    static double parseGlucose(String s) {
        if (s == null) return Double.NaN;
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** The Confirm button of the box that is open (or its clickable parent). Only called with the Carbohydrates or Glucose box open. */
    static AccessibilityNodeInfo confirmButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        return clickableUp(byText(all, "Confirm"));
    }

    /**
     * The value on the row of a calculator label: a number or "---" on the same row as the label,
     * nearest to it.
     */
    private static AccessibilityNodeInfo valueNode(AccessibilityNodeInfo root, String labelText) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        AccessibilityNodeInfo label = null;
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (n.isVisibleToUser() && t != null && t.toString().trim().equalsIgnoreCase(labelText)) {
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
        return best;
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

    static final String PROGRESS_CH = "carbfill_progress";
    static final int PROGRESS_ID = 8;

    /**
     * While a run is in progress: the red line at the top and bottom of the screen (WorkingOverlay) with a
     * short label. Only if "Display over other apps" is off, a quiet ongoing notification with the long text.
     */
    static void progress(Context c, String label, String text) {
        if (WorkingOverlay.working(c, label)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(PROGRESS_CH, "Carbs for CamAPS, in progress", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(c, PROGRESS_CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Filling in CamAPS")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(2 * 60_000L)
                .build();
        nm.notify(PROGRESS_ID, n);
    }

    static void clearProgress(Context c) {
        c.getSystemService(NotificationManager.class).cancel(PROGRESS_ID);
    }

    static void notifyResult(Context c, Plan p, Req last, boolean ok, String problem) {
        clearProgress(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Carbs for CamAPS", NotificationManager.IMPORTANCE_DEFAULT));
        String lastDesc = describe(new Item(last.kind, last.grams, last.label));
        StringBuilder done = new StringBuilder();
        if (p != null) for (String d : p.done) done.append(done.length() == 0 ? "" : ", ").append(d);
        String title, text;
        if (ok) {
            boolean meal = last.kind == Kind.MEAL;
            title = meal ? "Bolus calculator ready in CamAPS" : "Done in CamAPS";
            text = (done.length() > 0 ? "Done: " + done + ". Then " : "")
                    + (done.length() > 0 ? lastDesc : lastDesc.substring(0, 1).toUpperCase(Locale.UK) + lastDesc.substring(1)) + ". "
                    + (meal ? (last.correction ? "Glucose is confirmed too, so CamAPS adds a correction. " : "")
                    + "Review it, then tap Next and Deliver yourself." : "");
        } else {
            title = "Not finished in CamAPS";
            text = "Couldn't finish " + lastDesc + ": " + problem + ". "
                    + (done.length() > 0 ? "Done before that: " + done + ". " : "")
                    + "Nothing was delivered. Open CamAPS and check what is on screen, then run it again from the plan.";
        }
        // the line turns green (your turn) or amber (stopped); a success needs no notification on top of that
        String turn = ok ? (last.kind == Kind.MEAL ? "Your turn · check, then Next and Deliver" : "Done · your turn")
                : "Stopped · your turn";
        if (WorkingOverlay.finished(c, ok, turn) && ok) return;
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .build();
        nm.notify(ID, n);
    }
}
