package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Bolus dedupe (BolusTracker). Times are ms from an arbitrary start; screens are text lists. */
public class BolusTrackerTest {

    private static final long MIN = 60_000L;
    private static final double NONE = Double.NaN;

    private static final List<String> POPUP_44 =
            Arrays.asList("Information", "Acknowledge and dismiss", "Delivered 4.4 U of insulin.");
    private static final List<String> HOME =
            Arrays.asList("Open options", "CamAPS FX", "7.5", "mmol/L", "Boost", "Ease-off", "Auto mode", "On");
    private static final List<String> COUNTDOWN =
            Arrays.asList("Open options", "CamAPS FX", "4.4", "Units", "Delivery will start in", "5 seconds", "Back", "Cancel");

    /** Runs reads through a save/load each time, like the app does between events. */
    private static final class Phone {
        String state = "";
        int created;
        int updated;
        double lastCarbs = NONE;

        BolusTracker.Result screen(List<String> texts, long at, double carbs) {
            BolusTracker t = BolusTracker.load(state);
            BolusTracker.Result r = t.screen(texts, at, carbs);
            state = t.save();
            count(r);
            return r;
        }

        BolusTracker.Result notification(String text, long at, double carbs) {
            BolusTracker t = BolusTracker.load(state);
            BolusTracker.Result r = t.notification(text, at, carbs);
            state = t.save();
            count(r);
            return r;
        }

        private void count(BolusTracker.Result r) {
            if (r.kind == BolusTracker.Kind.NEW) created++;
            if (r.kind == BolusTracker.Kind.UPDATE) updated++;
            if (r.kind != BolusTracker.Kind.NONE && !Double.isNaN(r.carbs)) lastCarbs = r.carbs;
        }
    }

    @Test
    public void samePopupRereadAfterThreeMinutesIsOneBolus() {
        Phone p = new Phone();
        p.screen(COUNTDOWN, 0, 35);
        p.screen(POPUP_44, 30_000, 35);
        p.screen(POPUP_44, 30_500, NONE);              // more events while it is up
        p.screen(POPUP_44, 30_000 + 3 * MIN + 1_000, NONE);
        assertEquals(1, p.created);
        assertEquals(35.0, p.lastCarbs, 0);
    }

    @Test
    public void popupStillUpAfterReopeningCamapsIsOneBolus() {
        // 12 Oct 2026: 20:56 bolus, CamAPS left with the popup up, reopened at 20:59.
        Phone p = new Phone();
        p.screen(COUNTDOWN, 0, 35);
        p.screen(POPUP_44, 28_000, 35);
        // CamAPS in the background: no reads at all. Reopened 181 s later:
        p.screen(POPUP_44, 28_000 + 181_000, NONE);
        assertEquals(1, p.created);
    }

    @Test
    public void reopenThatShowsHomeBeforeThePopupIsStillOneBolus() {
        // If the activity is read before the popup on reopening, the popup "appears" again;
        // same amount with no delivery countdown since the bolus = the same bolus.
        Phone p = new Phone();
        p.screen(COUNTDOWN, 0, 35);
        p.screen(POPUP_44, 28_000, 35);
        p.screen(HOME, 28_000 + 3 * MIN, NONE);
        p.screen(POPUP_44, 28_500 + 3 * MIN, NONE);
        assertEquals(1, p.created);
        assertEquals(0, p.updated);
    }

    @Test
    public void twoIdenticalBolusesFiveMinutesApartWithADeliveryBetweenAreTwo() {
        Phone p = new Phone();
        p.screen(COUNTDOWN, 0, NONE);
        p.screen(POPUP_44, 30_000, NONE);
        p.screen(HOME, 60_000, NONE);                   // popup dismissed
        p.screen(COUNTDOWN, 5 * MIN, NONE);             // a second delivery starts
        p.screen(POPUP_44, 5 * MIN + 30_000, NONE);
        assertEquals(2, p.created);
    }

    @Test
    public void twoIdenticalBolusesEvenWithThePopupNeverDismissed() {
        // The countdown screen replaces the popup, so the second popup is a fresh appearance.
        Phone p = new Phone();
        p.screen(COUNTDOWN, 0, NONE);
        p.screen(POPUP_44, 30_000, NONE);
        p.screen(COUNTDOWN, 5 * MIN, NONE);
        p.screen(POPUP_44, 5 * MIN + 30_000, NONE);
        assertEquals(2, p.created);
    }

    @Test
    public void carbslessRereadOfABolusWithCarbsIsOneBolusAndKeepsTheCarbs() {
        Phone p = new Phone();
        p.screen(COUNTDOWN, 0, 35);
        p.screen(POPUP_44, 28_000, 35);
        p.screen(HOME, 2 * MIN, NONE);
        p.screen(POPUP_44, 3 * MIN, NONE);              // reappears, no carbs this time
        p.notification("Delivered 4.4 U of insulin.", 4 * MIN, NONE);
        assertEquals(1, p.created);
        assertEquals(0, p.updated);
        assertEquals(35.0, p.lastCarbs, 0);
    }

    @Test
    public void laterReadWithCarbsUpdatesTheSameBolus() {
        Phone p = new Phone();
        BolusTracker.Result first = p.notification("Delivered 4.4 U of insulin.", 10_000, NONE);
        BolusTracker.Result again = p.screen(POPUP_44, 2 * MIN, 35);
        assertEquals(1, p.created);
        assertEquals(1, p.updated);
        assertEquals(first.at, again.at);               // same created_at: Nightscout upserts it
        assertEquals(35.0, again.carbs, 0);
    }

    @Test
    public void differentAmountIsANewBolus() {
        Phone p = new Phone();
        p.screen(POPUP_44, 0, NONE);
        p.screen(HOME, MIN, NONE);
        p.screen(Arrays.asList("Information", "Delivered 1.3 U of insulin."), 2 * MIN, NONE);
        assertEquals(2, p.created);
    }

    @Test
    public void popupReappearingHoursLaterWithoutADeliveryIsStillOneBolus() {
        // Flick away, come back hours later, and the reopen reads another screen first.
        Phone p = new Phone();
        p.screen(COUNTDOWN, 0, NONE);
        p.screen(POPUP_44, 30_000, NONE);
        p.screen(HOME, 5 * 60 * MIN, NONE);
        p.screen(POPUP_44, 5 * 60 * MIN + 500, NONE);
        assertEquals(1, p.created);
    }

    @Test
    public void popupLeftUpForHoursIsStillOneBolus() {
        Phone p = new Phone();
        p.screen(COUNTDOWN, 0, NONE);
        p.screen(POPUP_44, 30_000, NONE);
        p.screen(POPUP_44, 8 * 60 * MIN, NONE);
        assertEquals(1, p.created);
    }

    @Test
    public void sameAmountHoursLaterWithADeliveryIsANewBolus() {
        Phone p = new Phone();
        p.screen(COUNTDOWN, 0, NONE);
        p.screen(POPUP_44, 30_000, NONE);
        p.screen(HOME, MIN, NONE);
        p.screen(COUNTDOWN, 4 * 60 * MIN, NONE);
        p.screen(POPUP_44, 4 * 60 * MIN + 30_000, NONE);
        assertEquals(2, p.created);
    }

    @Test
    public void twoSeparateNotificationsAreTwoBoluses() {
        Phone p = new Phone();
        p.notification("Delivered 4.4 U of insulin.", 0, NONE);   // a post at time 0 still counts
        p.notification("Delivered 4.4 U of insulin.", 0, NONE);   // the same post seen twice
        p.notification("Delivered 4.4 U of insulin.", 5 * MIN, NONE);
        assertEquals(2, p.created);
    }

    @Test
    public void popupReadLateIsTimedAtItsCountdown() {
        // Left CamAPS during the countdown, came back 20 min later: the bolus is at the countdown.
        Phone p = new Phone();
        p.screen(COUNTDOWN, 10 * MIN, NONE);
        BolusTracker.Result r = p.screen(POPUP_44, 30 * MIN, NONE);
        assertEquals(1, p.created);
        assertEquals(10 * MIN, r.at);
    }

    @Test
    public void popupWithoutASeenCountdownIsTimedWhenRead() {
        Phone p = new Phone();
        BolusTracker.Result r = p.screen(POPUP_44, 30 * MIN, NONE);
        assertEquals(30 * MIN, r.at);
    }

    @Test
    public void stateSurvivesSaveAndLoad() {
        BolusTracker t = new BolusTracker();
        t.screen(POPUP_44, 1_000, 35);
        BolusTracker u = BolusTracker.load(t.save());
        assertEquals(t.save(), u.save());
        assertEquals(BolusTracker.Kind.NONE, u.screen(POPUP_44, 200_000, NONE).kind);
        assertEquals(BolusTracker.Kind.NONE, BolusTracker.load("4.4@123").screen(HOME, 0, NONE).kind);
    }

    // ── CamAPS re-posting "Delivered" (screen reader on) ──

    private static final String DELIVERED_14 = "Delivered 1.4 U of insulin.";

    private static BolusTracker.Result note(BolusTracker t, String text, long at, double carbs) {
        return t.notification(text, at, carbs, true);
    }

    @Test
    public void rePost_1Nov_noCalculatorSeen_isNotASecondBolus() {
        // 1 Nov 2026 17:51 and 17:54, 1.4 U: one bolus in the pump's records, no calculator before the second.
        BolusTracker t = new BolusTracker();
        t.calculator(-MIN);
        assertEquals(BolusTracker.Kind.NEW, note(t, DELIVERED_14, 0, 10).kind);
        BolusTracker.Result second = note(t, DELIVERED_14, 3 * MIN, NONE);
        assertEquals(BolusTracker.Kind.NONE, second.kind);
        assertEquals(DELIVERED_14, second.text);    // for the log
    }

    @Test
    public void rePost_25Oct_theUsualCycle_countsOnce() {
        // 25 Oct 2026: 6.9 U / 45 g at 14:54, re-posted at 15:07 and 15:15.
        String d = "Delivered 6.9 U of insulin.";
        BolusTracker t = new BolusTracker();
        t.calculator(-MIN);
        assertEquals(BolusTracker.Kind.NEW, note(t, d, 0, 45).kind);
        assertEquals(BolusTracker.Kind.NONE, note(t, d, 13 * MIN, NONE).kind);
        assertEquals(BolusTracker.Kind.NONE, note(t, d, 21 * MIN, NONE).kind);
    }

    @Test
    public void realSecondBolus_calculatorSeen_counts() {
        // 16 Oct 2026: 1.5 U / 10 g at 14:53 and again at 15:03, both in the pump's records.
        String d = "Delivered 1.5 U of insulin.";
        BolusTracker t = new BolusTracker();
        assertEquals(BolusTracker.Kind.NEW, note(t, d, 0, 10).kind);
        t.calculator(8 * MIN);
        BolusTracker.Result second = note(t, d, 10 * MIN, 10);
        assertEquals(BolusTracker.Kind.NEW, second.kind);
        assertEquals(10 * MIN, second.at);
    }

    @Test
    public void realSecondBolus_25Oct_countdownSeen_counts() {
        BolusTracker t = BolusTracker.load(new BolusTracker().save());
        t.screen(Arrays.asList("Delivered 2.1 U of insulin."), 0, 15);
        t.screen(HOME, MIN, NONE);
        t.screen(COUNTDOWN, 3 * MIN, NONE);
        assertEquals(BolusTracker.Kind.NEW, note(t, "Delivered 2.1 U of insulin.", 4 * MIN, 15).kind);
    }

    @Test
    public void calculatorStillUpJustAfterTheBolus_isNotEvidence() {
        BolusTracker t = new BolusTracker();
        note(t, DELIVERED_14, 0, 10);
        t.calculator(30_000);
        assertEquals(BolusTracker.Kind.NONE, note(t, DELIVERED_14, 3 * MIN, NONE).kind);
    }

    @Test
    public void readerOff_aSecondNotificationStillCounts() {
        BolusTracker t = new BolusTracker();
        assertEquals(BolusTracker.Kind.NEW, t.notification(DELIVERED_14, 0, NONE).kind);
        assertEquals(BolusTracker.Kind.NEW, t.notification(DELIVERED_14, 3 * MIN, NONE).kind);
    }

    @Test
    public void calculatorRead_survivesSaving_andOldStateLoads() {
        BolusTracker t = new BolusTracker();
        t.calculator(10 * MIN);
        assertEquals(10 * MIN, BolusTracker.load(t.save()).calculatorAt);
        assertEquals(1.5, BolusTracker.load("1.5|100||notification|-1|0|").lastUnits, 0);
        assertEquals(0, BolusTracker.load("1.5|100||notification|-1|0|").calculatorAt);
    }
}
