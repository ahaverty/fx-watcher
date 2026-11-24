package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** What may be typed into CamAPS, when, and the limits on how it gets there. */
public class CarbFillTest {

    // ---- amounts: whole grams 1 to 150 ----

    @Test
    public void onlyWholeGramsFromOneTo150AreValid() {
        assertFalse(CarbOptions.valid(0));
        assertFalse(CarbOptions.valid(-5));
        assertTrue(CarbOptions.valid(1));
        assertTrue(CarbOptions.valid(150));
        assertFalse(CarbOptions.valid(151));
        assertFalse(CarbOptions.valid(1000));
    }

    @Test
    public void presetsKeepOnlyGoodAmountsInOrderWithoutRepeats() {
        assertEquals(Arrays.asList(10, 15, 20, 30), CarbOptions.parsePresets("10, 15 20;30"));
        assertEquals(Arrays.asList(5, 45), CarbOptions.parsePresets("5,0,45,200,abc,45,-3,2.5"));
        assertTrue(CarbOptions.parsePresets("").isEmpty());
        assertTrue(CarbOptions.parsePresets(null).isEmpty());
        assertEquals("20,30,45,60", CarbOptions.joinPresets(CarbOptions.parsePresets(CarbOptions.DEFAULT_PRESETS)));
    }

    @Test
    public void thePlanFromAnAppIsCheckedAndCapped() {
        CarbOptions.Plan p = CarbOptions.fromBroadcast("Plan|45;Usual|60;Third|20;Bad|999;|12", 1000);
        assertEquals(2, p.items.size());
        assertEquals("Plan", p.items.get(0).name);
        assertEquals(45, p.items.get(0).grams);
        assertTrue(CarbOptions.fromBroadcast("Plan|0", 1000).items.isEmpty());
        assertTrue(CarbOptions.fromBroadcast("Plan|200", 1000).items.isEmpty());
        assertTrue(CarbOptions.fromBroadcast("no bar", 1000).items.isEmpty());
        assertTrue(CarbOptions.fromBroadcast(null, 1000).items.isEmpty());
        assertNull(CarbOptions.parseItem("A very long name that is over the limit|10"));
    }

    @Test
    public void aPlanSurvivesSaveAndLoadAndGoesStaleAfterTwoHours() {
        CarbOptions.Plan p = CarbOptions.fromBroadcast("Plan|45", 5_000);
        CarbOptions.Plan q = CarbOptions.Plan.load(p.save());
        assertEquals(1, q.items.size());
        assertEquals(45, q.items.get(0).grams);
        assertTrue(q.fresh(5_000 + 3_600_000L));
        assertFalse(q.fresh(5_000 + CarbOptions.PLAN_TTL_MS + 1));
        assertFalse(CarbOptions.Plan.load("garbage").fresh(1));
    }

    @Test
    public void widgetShowsPlanFirstThenPresetsUpToSix() {
        CarbOptions.Plan p = CarbOptions.fromBroadcast("Plan|45", 1000);
        List<CarbOptions.Option> o = CarbOptions.options(p, CarbOptions.parsePresets("10,15,20,30,45,60,90"), 2000);
        assertEquals("10 and 15 are hypo-sized and never offered as a quick meal tap", 6, o.size());
        assertTrue(o.get(0).plan);
        assertEquals("Plan 45 g", o.get(0).label);
        assertEquals(45, o.get(0).grams);
        assertEquals(20, o.get(1).grams);
        assertFalse(o.get(1).plan);
        // a stale plan is not offered
        List<CarbOptions.Option> stale = CarbOptions.options(p, Arrays.asList(10, 20), 1000 + CarbOptions.PLAN_TTL_MS + 5);
        assertEquals("only 20 is meal-sized", 1, stale.size());
        assertFalse(stale.get(0).plan);
    }

    @Test
    public void amountsAreColouredBySize() {
        assertEquals(0, CarbOptions.band(10));
        assertEquals(0, CarbOptions.band(15));
        assertEquals(1, CarbOptions.band(16));
        assertEquals(1, CarbOptions.band(30));
        assertEquals(2, CarbOptions.band(31));
        assertEquals(2, CarbOptions.band(50));
        assertEquals(3, CarbOptions.band(51));
        assertEquals(3, CarbOptions.band(150));
    }

    @Test
    public void theStepperMovesInFivesAndStaysInRange() {
        assertEquals(25, CarbOptions.stepped(20, 5));
        assertEquals(15, CarbOptions.stepped(20, -5));
        assertEquals(5, CarbOptions.stepped(5, -5));
        assertEquals(120, CarbOptions.stepped(120, 5));
        assertEquals(20, CarbOptions.stepped(22, 0));
        assertEquals(5, CarbOptions.stepped(0, 0));
        assertTrue(CarbOptions.valid(CarbOptions.stepped(999, 0)));
    }

    // ---- which CamAPS screen is it ----

    private static List<String> t(String... s) {
        return Arrays.asList(s);
    }

    @Test
    public void screensAreToldApart() {
        assertEquals(CarbFill.Screen.HOME, CarbFill.classify(t("Auto mode", "On", "Boost"), false));
        assertEquals(CarbFill.Screen.CALCULATOR, CarbFill.classify(t("Meal size", "Glucose", "Carbs", "g CHO", "---", "Total Units", "Deliver"), false));
        assertEquals(CarbFill.Screen.CARBS_DIALOG, CarbFill.classify(t("Carbohydrates", "Confirm", "Cancel"), true));
    }

    @Test
    public void aBoxThatIsNotCarbohydratesIsNeverTypedInto() {
        assertEquals(CarbFill.Screen.ENTRY_DIALOG, CarbFill.classify(t("Glucose", "Confirm"), true));   // cancelled with Back, never typed into
        assertEquals("a Confirm box with no field", CarbFill.Screen.OTHER_DIALOG, CarbFill.classify(t("Carbohydrates", "Confirm"), false));
        assertEquals(CarbFill.Step.ABORT_OTHER_DIALOG, CarbFill.decide(CarbFill.Screen.OTHER_DIALOG, 0));
        assertEquals(CarbFill.Step.TYPE, CarbFill.decide(CarbFill.Screen.CARBS_DIALOG, 0));
    }

    @Test
    public void aDeliveryOnScreenStopsEverything() {
        for (String s : new String[]{"Delivery will start in 5 seconds", "Delivered 1.3 U of insulin.", "Delivering 2.0 U"}) {
            CarbFill.Screen sc = CarbFill.classify(t("Total Units", s, "Confirm"), true);
            assertEquals(s, CarbFill.Screen.BUSY, sc);
            assertEquals(CarbFill.Step.ABORT_BUSY, CarbFill.decide(sc, 0));
        }
    }

    @Test
    public void theStepsFromEachScreen() {
        assertEquals(CarbFill.Step.OPEN_TAB, CarbFill.decide(CarbFill.Screen.HOME, 0));
        assertEquals(CarbFill.Step.OPEN_CARBS, CarbFill.decide(CarbFill.Screen.CALCULATOR, 0));
        assertEquals(CarbFill.Step.BACK, CarbFill.decide(CarbFill.Screen.UNKNOWN, 0));
        assertEquals(CarbFill.Step.BACK, CarbFill.decide(CarbFill.Screen.UNKNOWN, 5));
        assertEquals("only 6 Backs", CarbFill.Step.ABORT_LOST, CarbFill.decide(CarbFill.Screen.UNKNOWN, 6));
    }

    // ---- the "delivered a while ago" information popup that blocked the first run ----

    @Test
    public void theDeliveredAgoPopupIsClosedWithItsXAndNothingElse() {
        List<String> pop = t("Information", "1.9 U delivered 8 min ago.");
        assertEquals(CarbFill.Screen.INFO_POPUP, CarbFill.classify(pop, false));
        assertEquals(CarbFill.Step.DISMISS_INFO, CarbFill.decide(CarbFill.Screen.INFO_POPUP, 0));
        assertEquals(CarbFill.Step.DISMISS_INFO, CarbFill.decide(CarbFill.Screen.INFO_POPUP, 0, CarbFill.Kind.LOW, false, false));
        assertEquals(CarbFill.Screen.INFO_POPUP, CarbFill.classify(t("Information", "0.5 U delivered 1 h 12 min ago"), false));
    }

    @Test
    public void anyOtherInformationPopupIsLeftAlone() {
        assertEquals(CarbFill.Screen.OTHER_DIALOG, CarbFill.classify(t("Information", "Unable to connect to your pump."), false));
        assertEquals(CarbFill.Screen.OTHER_DIALOG, CarbFill.classify(t("Information", "Very low level of insulin in reservoir."), false));
        assertEquals(CarbFill.Screen.OTHER_DIALOG, CarbFill.classify(t("Information", "Sensor expired."), false));
    }

    @Test
    public void aJustDeliveredPopupStillStopsEverything() {
        assertEquals(CarbFill.Screen.BUSY, CarbFill.classify(t("Information", "Delivered 1.9 U of insulin."), false));
        assertEquals(CarbFill.Screen.BUSY, CarbFill.classify(t("Delivery will start in 5 seconds"), false));
    }

    // ---- the other screens CamAPS may have been left on (seen on Alan's phone) ----

    @Test
    public void theSideMenuIsClosedWithBack() {
        List<String> drawer = t("CamAPS FX", "Add meal", "Alerts", "Share", "Statistics", "YpsoPump", "Active insulin", "Settings", "About");
        assertEquals(CarbFill.Screen.DRAWER, CarbFill.classify(drawer, false));
        assertEquals(CarbFill.Step.BACK, CarbFill.decide(CarbFill.Screen.DRAWER, 0));
    }

    @Test
    public void theAddMealPagesAreLeftWithBackNotContinued() {
        List<String> page = t("Add meal", "Amount", "g CHO", "Meal or snack", "Hypoglycaemia treatment", "Slowly absorbed meal", "Continue");
        assertEquals(CarbFill.Screen.ADD_MEAL, CarbFill.classify(page, false));
        assertEquals(CarbFill.Step.BACK, CarbFill.decide(CarbFill.Screen.ADD_MEAL, 0));
    }

    @Test
    public void aHalfEnteredBoostOrGlucoseBoxIsCancelledWithBack() {
        List<String> boost = t("Boost duration", "00", "Hrs", "00", "Mins", "Cancel", "Next");
        assertEquals(CarbFill.Screen.MODE_DURATION, CarbFill.classify(boost, true));
        // a meal or Add meal request that meets a half-entered Boost box backs out of it
        assertEquals(CarbFill.Step.BACK, CarbFill.decide(CarbFill.Screen.MODE_DURATION, 0));
        assertEquals(CarbFill.Step.BACK, CarbFill.decide(CarbFill.Screen.MODE_DURATION, 0, CarbFill.Kind.SLOW, false, false));
        assertEquals(CarbFill.Screen.MODE_DURATION, CarbFill.classify(t("Ease-off duration", "Cancel", "Next"), true));
        assertEquals(CarbFill.Step.BACK, CarbFill.decide(CarbFill.Screen.ENTRY_DIALOG, 0));
        assertEquals(CarbFill.Screen.ENTRY_DIALOG, CarbFill.classify(t("Glucose", "Value", "mmol/L", "Cancel", "Confirm"), true));
    }

    @Test
    public void anAlertOrWarningBoxIsNeverDismissed() {
        assertEquals(CarbFill.Screen.OTHER_DIALOG, CarbFill.classify(t("Urgent low glucose", "Treat now", "Confirm"), false));
        assertEquals(CarbFill.Screen.OTHER_DIALOG, CarbFill.classify(t("Confirm bolus", "Confirm", "Cancel"), false));
        assertEquals(CarbFill.Step.ABORT_OTHER_DIALOG, CarbFill.decide(CarbFill.Screen.OTHER_DIALOG, 0));
    }

    @Test
    public void statisticsAndInfoPagesAreLeftWithBack() {
        for (List<String> page : Arrays.asList(
                t("Statistics duration", "Day", "Week", "Month", "Generate PDF report"),
                t("Statistics", "Today", "Average glucose", "GMI", "Time in target"),
                t("Dexcom G7", "Dexcom G7 information", "Pairing code", "Connected devices"))) {
            CarbFill.Screen sc = CarbFill.classify(page, false);
            assertEquals(CarbFill.Screen.UNKNOWN, sc);
            assertEquals(CarbFill.Step.BACK, CarbFill.decide(sc, 2));
        }
    }

    @Test
    public void theCalculatorAndHomeAreNotMistakenForOtherScreens() {
        // the calculator also has Cancel and Next buttons
        assertEquals(CarbFill.Screen.CALCULATOR, CarbFill.classify(t("CamAPS FX", "Meal size", "Glucose", "mmol/L", "5.9", "Carbs", "g CHO", "---", "Total Units", "Cancel", "Next"), false));
        assertEquals(CarbFill.Screen.HOME, CarbFill.classify(t("CamAPS FX", "5.9", "mmol/L", "Boost", "Ease-off", "Auto mode", "On"), false));
    }

    @Test
    public void theCarbohydratesBoxIsTheOnlyOneTypedInto() {
        assertEquals(CarbFill.Screen.CARBS_DIALOG, CarbFill.classify(t("Carbohydrates", "Value", "g CHO", "Cancel", "Confirm"), true));
        assertEquals("a Glucose box is never the Carbohydrates box", CarbFill.Screen.ENTRY_DIALOG,
                CarbFill.classify(t("Glucose", "Value", "mmol/L", "Cancel", "Confirm"), true));
    }

    @Test
    public void theTypedNumberMustReadBackExactly() {
        assertTrue(CarbFill.verified("30", 30));
        assertTrue(CarbFill.verified(" 30 ", 30));
        assertTrue(CarbFill.verified("30.0", 30));
        assertTrue(CarbFill.verified("30,0", 30));
        assertFalse(CarbFill.verified("3", 30));
        assertFalse(CarbFill.verified("300", 30));
        assertFalse(CarbFill.verified("", 30));
        assertFalse(CarbFill.verified(null, 30));
        assertFalse(CarbFill.verified("30 g", 30));
        assertFalse(CarbFill.verified("30.5", 30));
    }

    @Test
    public void theScreenIsReLookedAtThriceAfterEachAction() {
        assertEquals(3, CarbFill.RECHECK_MS.length);
        assertTrue(CarbFill.RECHECK_MS[0] > CarbFill.STEP_GAP_MS);
        assertTrue(CarbFill.RECHECK_MS[1] > CarbFill.RECHECK_MS[0]);
        assertTrue("the last look is after the settle time, so a page still loading is seen again", CarbFill.RECHECK_MS[2] > CarbFill.SETTLE_MS);
    }

    @Test
    public void limitsAreTightAndFixed() {
        assertEquals(60_000L, CarbFill.TTL_MS);
        assertEquals(12, CarbFill.MAX_ACTIONS);
        assertEquals(6, CarbFill.MAX_BACKS);
    }

    // ---- the source can only do what it says ----

    // ---- low treatment: a separate path, never mixed with a meal ----

    @Test
    public void aLowTreatmentIsWholeGramsFiveToThirty() {
        assertFalse(CarbOptions.validLow(4));
        assertTrue(CarbOptions.validLow(5));
        assertTrue(CarbOptions.validLow(15));
        assertTrue(CarbOptions.validLow(30));
        assertFalse(CarbOptions.validLow(31));
        assertFalse("a meal-sized amount is never a low treatment", CarbOptions.validLow(45));
    }

    @Test
    public void lowTreatmentIsOnlyOfferedWhenLowOrHeadingLow() {
        long now = 10_000_000L;
        assertTrue(CarbOptions.lowOffered(4.6, now - 60_000L, CalmState.STEADY, now));
        assertTrue(CarbOptions.lowOffered(6.5, now - 60_000L, CalmState.LOW_SOON, now));
        assertTrue(CarbOptions.lowOffered(5.5, now - 60_000L, CalmState.TREATING, now));
        assertFalse("in range and steady", CarbOptions.lowOffered(6.0, now - 60_000L, CalmState.STEADY, now));
        assertFalse("high", CarbOptions.lowOffered(12.0, now - 60_000L, CalmState.HANDLED, now));
        assertFalse("no fresh reading", CarbOptions.lowOffered(4.0, now - 20 * 60_000L, CalmState.STEADY, now));
        assertFalse("no reading", CarbOptions.lowOffered(-1, 0, CalmState.STEADY, now));
    }

    @Test
    public void aLowRequestGoesViaTheMenuToAddMealAndNeverTouchesTheBolusCalculator() {
        CarbFill.Kind low = CarbFill.Kind.LOW;
        assertEquals(CarbFill.Step.OPEN_MENU, CarbFill.decide(CarbFill.Screen.HOME, 0, low, false, false));
        assertEquals(CarbFill.Step.OPEN_MENU, CarbFill.decide(CarbFill.Screen.CALCULATOR, 0, low, false, false));
        assertEquals(CarbFill.Step.OPEN_ADD_MEAL, CarbFill.decide(CarbFill.Screen.DRAWER, 0, low, false, false));
        assertEquals("the page opens on Meal or snack: select Hypoglycaemia first", CarbFill.Step.PICK_HYPO,
                CarbFill.decide(CarbFill.Screen.ADD_MEAL, 0, low, false, true));
        assertEquals(CarbFill.Step.TYPE_LOW, CarbFill.decide(CarbFill.Screen.ADD_MEAL, 0, low, true, true));
        assertEquals("selected but no Amount field: stop", CarbFill.Step.ABORT_NO_FIELD,
                CarbFill.decide(CarbFill.Screen.ADD_MEAL, 0, low, true, false));
        assertEquals("a bolus Carbohydrates box left open is cancelled, never typed into", CarbFill.Step.BACK,
                CarbFill.decide(CarbFill.Screen.CARBS_DIALOG, 0, low, false, false));
        assertEquals(CarbFill.Step.ABORT_BUSY, CarbFill.decide(CarbFill.Screen.BUSY, 0, low, true, true));
        assertEquals(CarbFill.Step.ABORT_OTHER_DIALOG, CarbFill.decide(CarbFill.Screen.OTHER_DIALOG, 0, low, true, true));
    }

    @Test
    public void aMealRequestNeverTypesIntoAddMealAndNeverPicksTheHypoOption() {
        CarbFill.Kind meal = CarbFill.Kind.MEAL;
        for (CarbFill.Screen sc : CarbFill.Screen.values()) {
            CarbFill.Step st = CarbFill.decide(sc, 0, meal, true, true);
            assertTrue(sc + " -> " + st, st != CarbFill.Step.TYPE_LOW && st != CarbFill.Step.PICK_HYPO
                    && st != CarbFill.Step.OPEN_MENU && st != CarbFill.Step.OPEN_ADD_MEAL);
        }
        assertEquals(CarbFill.Step.BACK, CarbFill.decide(CarbFill.Screen.ADD_MEAL, 0, meal, true, true));
    }

    @Test
    public void aLowRequestNeverTypesIntoTheBolusBox() {
        CarbFill.Kind low = CarbFill.Kind.LOW;
        for (CarbFill.Screen sc : CarbFill.Screen.values()) {
            CarbFill.Step st = CarbFill.decide(sc, 0, low, true, true);
            assertTrue(sc + " -> " + st, st != CarbFill.Step.TYPE && st != CarbFill.Step.OPEN_CARBS && st != CarbFill.Step.OPEN_TAB);
        }
    }

    @Test
    public void theSheetStartsInTheRightPlace() {
        assertEquals(15, CarbSheetActivity.start(CarbSheetActivity.LOW, -1));
        assertEquals(10, CarbSheetActivity.start(CarbSheetActivity.LOW, 10));
        assertEquals("a meal amount is not a low amount", 15, CarbSheetActivity.start(CarbSheetActivity.LOW, 45));
        assertEquals(30, CarbSheetActivity.start(CarbSheetActivity.MEAL, -1));
        assertEquals(45, CarbSheetActivity.start(CarbSheetActivity.MEAL, 45));
        assertEquals(60, CarbSheetActivity.start(CarbSheetActivity.SLOW, 60));
    }

    @Test
    public void everyModeHasItsOwnRangeAndKind() {
        assertEquals(CarbFill.Kind.MEAL, CarbSheetActivity.kind(CarbSheetActivity.MEAL));
        assertEquals(CarbFill.Kind.SLOW, CarbSheetActivity.kind(CarbSheetActivity.SLOW));
        assertEquals(CarbFill.Kind.LOW, CarbSheetActivity.kind(CarbSheetActivity.LOW));
        assertEquals(5, CarbSheetActivity.min(CarbSheetActivity.LOW));
        assertEquals(30, CarbSheetActivity.max(CarbSheetActivity.LOW));
        assertEquals(120, CarbSheetActivity.max(CarbSheetActivity.MEAL));
        assertEquals(120, CarbSheetActivity.max(CarbSheetActivity.SLOW));
    }

    @Test
    public void eachKindChecksItsOwnRange() {
        assertTrue(CarbOptions.validFor(CarbFill.Kind.MEAL, 150));
        assertFalse(CarbOptions.validFor(CarbFill.Kind.MEAL, 151));
        assertTrue(CarbOptions.validFor(CarbFill.Kind.SLOW, 120));
        assertFalse(CarbOptions.validFor(CarbFill.Kind.SLOW, 121));
        assertFalse(CarbOptions.validFor(CarbFill.Kind.SLOW, 4));
        assertTrue(CarbOptions.validFor(CarbFill.Kind.LOW, 30));
        assertFalse(CarbOptions.validFor(CarbFill.Kind.LOW, 31));
    }

    @Test
    public void aSlowMealTakesTheSameAddMealRouteWithItsOwnOption() {
        CarbFill.Kind slow = CarbFill.Kind.SLOW;
        assertEquals(CarbFill.Step.OPEN_MENU, CarbFill.decide(CarbFill.Screen.HOME, 0, slow, false, false));
        assertEquals(CarbFill.Step.OPEN_ADD_MEAL, CarbFill.decide(CarbFill.Screen.DRAWER, 0, slow, false, false));
        assertEquals(CarbFill.Step.PICK_HYPO, CarbFill.decide(CarbFill.Screen.ADD_MEAL, 0, slow, false, true));
        assertEquals(CarbFill.Step.TYPE_LOW, CarbFill.decide(CarbFill.Screen.ADD_MEAL, 0, slow, true, true));
        assertEquals(CarbFill.Step.BACK, CarbFill.decide(CarbFill.Screen.CARBS_DIALOG, 0, slow, false, false));
        assertEquals("Slowly absorbed meal", CarbFill.optionOf(slow));
        assertEquals("Hypoglycaemia treatment", CarbFill.optionOf(CarbFill.Kind.LOW));
    }

    private static String source(String name) throws Exception {
        for (String p : new String[]{"src/main/java/studio/alans/fxwatcher/", "app/src/main/java/studio/alans/fxwatcher/"}) {
            File f = new File(p + name);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new AssertionError(name + " not found");
    }

    @Test
    public void carbFillHasExactlyThreeActionsAndNeverNamesConfirmOrDeliverAsATarget() throws Exception {
        String code = source("CarbFill.java").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertEquals(2, count(code, "performAction("));
        assertEquals(1, count(code, "performGlobalAction("));
        assertEquals(1, count(code, "ACTION_CLICK"));
        assertEquals(1, count(code, "AccessibilityNodeInfo.ACTION_SET_TEXT,"));
        assertFalse(code.contains("dispatchGesture"));
        assertFalse(code.contains("ACTION_PASTE"));
        assertFalse(code.contains("ACTION_SCROLL"));
        // the click goes to the Carbs field the finder returned, and nothing is looked up by the words Confirm or Deliver to press
        assertTrue(code.contains("return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);"));
        // tap() is only ever called on what one of four finders returned
        java.util.TreeSet<String> targets = new java.util.TreeSet<>();
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("\\btap\\((\\w+)\\)").matcher(code);
        while (tm.find()) if (!tm.group(1).equals("AccessibilityNodeInfo")) targets.add(tm.group(1));
        assertEquals(new java.util.TreeSet<>(Arrays.asList("cancelBtn", "close", "cont", "field", "glucose", "item", "menu", "modeBtn", "next", "now_", "ok", "radio")), targets);
        // the words looked up to find something to press or read: never Confirm, Continue, Deliver, Next or Cancel
        java.util.TreeSet<String> looked = new java.util.TreeSet<>();
        java.util.regex.Matcher bm = java.util.regex.Pattern.compile("byText\\(all, \"([^\"]+)\"\\)").matcher(code);
        while (bm.find()) looked.add(bm.group(1));
        assertEquals(new java.util.TreeSet<>(Arrays.asList("Add meal", "Confirm", "Continue", "Information", "Next", "Now")), looked);
        // Confirm is looked up in one place only, and only the two steps that follow a verified read-back call it
        assertEquals(1, count(code, "byText(all, \"Confirm\")"));
        // five Confirm taps: carbs box, glucose box, the Add meal final box, the Off box, the Start box
        assertEquals(5, count(code, "confirmButton(root)"));
        assertTrue(code.contains("verified(held, r.grams) ? null : confirmButton(root)"));
        assertTrue(code.contains("glucoseMatches(held, r.calcGlucose) ? null : confirmButton(root)"));
        assertTrue(code.contains("addConfirmOk ? confirmButton(root) : null"));
        // Next only on the Duration box after Hrs and Mins read back; Continue only on Add meal after the amount did
        assertTrue(code.contains("durationOk ? nextButton(root) : null"));
        assertEquals(1, count(code, "nextButton(root)") - 0);
        assertEquals(1, count(code, "continueButton(root)"));
        for (String never : new String[]{"\"Deliver\"", "\"Cancel\""}) {
            assertFalse("never looked up to press: " + never, code.contains("byText(all, " + never + ")"));
        }
        // the three option labels on the Add meal page are constants, and nothing else is looked up by a variable except those
        java.util.TreeSet<String> opts = new java.util.TreeSet<>();
        java.util.regex.Matcher om = java.util.regex.Pattern.compile("OPT_\\w+ = \"([^\"]+)\"").matcher(code);
        while (om.find()) opts.add(om.group(1));
        assertEquals(new java.util.TreeSet<>(Arrays.asList("Hypoglycaemia treatment", "Meal or snack", "Slowly absorbed meal")), opts);
        assertFalse(code.contains("findAccessibilityNodeInfosByText"));
        // the field typed into is the one inside the Carbohydrates box, never "the first editable field on screen"
        assertTrue(code.contains("AccessibilityNodeInfo box = boxField;"));
        assertTrue(code.contains("static AccessibilityNodeInfo dialogField("));
        assertFalse(code.contains("firstEditable"));
        // typing happens only in the TYPE step, which decide() returns only for CARBS_DIALOG
        assertTrue(code.contains("case CARBS_DIALOG: return Step.TYPE;"));
    }

    // ---- confirming the carbs box and the optional glucose correction ----

    private static CarbFill.Step meal(CarbFill.Screen s, CarbFill.Phase ph, boolean corr, CarbFill.GlucoseBox gb, boolean carbsOk) {
        return CarbFill.decideMeal(s, 0, ph, corr, gb, carbsOk);
    }

    @Test
    public void typedCarbsAreConfirmedOnlyInTheCarbohydratesBox() {
        assertEquals(CarbFill.Step.CONFIRM_CARBS, meal(CarbFill.Screen.CARBS_DIALOG, CarbFill.Phase.TYPED, false, CarbFill.GlucoseBox.EMPTY, false));
        // anywhere else after typing it backs out and never confirms
        assertEquals(CarbFill.Step.BACK, meal(CarbFill.Screen.HOME, CarbFill.Phase.TYPED, false, CarbFill.GlucoseBox.EMPTY, false));
        assertEquals(CarbFill.Step.ABORT_LOST, meal(CarbFill.Screen.CALCULATOR, CarbFill.Phase.TYPED, false, CarbFill.GlucoseBox.EMPTY, true));
    }

    @Test
    public void withoutACorrectionItStopsOnTheCalculatorOnceTheCarbsReadBack() {
        assertEquals(CarbFill.Step.FINISH, meal(CarbFill.Screen.CALCULATOR, CarbFill.Phase.CARBS_CONFIRMED, false, CarbFill.GlucoseBox.EMPTY, true));
        assertEquals(CarbFill.Step.ABORT_MISMATCH, meal(CarbFill.Screen.CALCULATOR, CarbFill.Phase.CARBS_CONFIRMED, false, CarbFill.GlucoseBox.EMPTY, false));
    }

    @Test
    public void aCorrectionOpensTheGlucoseBoxThenFillsOrChecksItThenConfirms() {
        assertEquals(CarbFill.Step.OPEN_GLUCOSE, meal(CarbFill.Screen.CALCULATOR, CarbFill.Phase.CARBS_CONFIRMED, true, CarbFill.GlucoseBox.EMPTY, true));
        assertEquals(CarbFill.Step.TYPE_GLUCOSE, meal(CarbFill.Screen.GLUCOSE_DIALOG, CarbFill.Phase.GLUCOSE_OPENED, true, CarbFill.GlucoseBox.EMPTY, true));
        assertEquals(CarbFill.Step.CONFIRM_GLUCOSE, meal(CarbFill.Screen.GLUCOSE_DIALOG, CarbFill.Phase.GLUCOSE_OPENED, true, CarbFill.GlucoseBox.MATCH, true));
        assertEquals(CarbFill.Step.CONFIRM_GLUCOSE, meal(CarbFill.Screen.GLUCOSE_DIALOG, CarbFill.Phase.GLUCOSE_TYPED, true, CarbFill.GlucoseBox.MATCH, true));
        assertEquals(CarbFill.Step.FINISH, meal(CarbFill.Screen.CALCULATOR, CarbFill.Phase.GLUCOSE_CONFIRMED, true, CarbFill.GlucoseBox.EMPTY, true));
    }

    @Test
    public void aGlucoseBoxHoldingAnythingElseStopsEverything() {
        assertEquals(CarbFill.Step.ABORT_MISMATCH, meal(CarbFill.Screen.GLUCOSE_DIALOG, CarbFill.Phase.GLUCOSE_OPENED, true, CarbFill.GlucoseBox.WRONG, true));
        // and it is never confirmed unless the box matched
        assertEquals(CarbFill.Step.ABORT_LOST, meal(CarbFill.Screen.CALCULATOR, CarbFill.Phase.GLUCOSE_TYPED, true, CarbFill.GlucoseBox.MATCH, true));
    }

    @Test
    public void blockingPopupsStopOrClearAtEveryPhase() {
        for (CarbFill.Phase ph : CarbFill.Phase.values()) {
            assertEquals(CarbFill.Step.ABORT_BUSY, meal(CarbFill.Screen.BUSY, ph, true, CarbFill.GlucoseBox.EMPTY, true));
            assertEquals(CarbFill.Step.ABORT_OTHER_DIALOG, meal(CarbFill.Screen.OTHER_DIALOG, ph, true, CarbFill.GlucoseBox.EMPTY, true));
            assertEquals(CarbFill.Step.DISMISS_INFO, meal(CarbFill.Screen.INFO_POPUP, ph, true, CarbFill.GlucoseBox.EMPTY, true));
        }
    }

    @Test
    public void glucoseChecks() {
        assertTrue(CarbFill.glucoseMatches("5.9", 5.9));
        assertTrue(CarbFill.glucoseMatches(" 5,9 ", 5.9));
        assertFalse(CarbFill.glucoseMatches("5.8", 5.9));
        assertFalse(CarbFill.glucoseMatches("", 5.9));
        assertFalse(CarbFill.glucoseMatches("59", 5.9));
        assertFalse(CarbFill.glucoseMatches("5.9", Double.NaN));
        assertTrue(CarbFill.glucoseSane(5.9, 6.1));
        assertFalse(CarbFill.glucoseSane(5.9, 7.0));
        assertFalse(CarbFill.glucoseSane(0, 6.0));
        assertFalse(CarbFill.glucoseSane(5.9, -1));
    }

    @Test
    public void glucoseDialogIsOnlyRecognisedWithItsOwnField() {
        List<String> g = t("Glucose", "mmol/L", "Confirm");
        assertEquals(CarbFill.Screen.GLUCOSE_DIALOG, CarbFill.classify(g, false, true));
        assertEquals(CarbFill.Screen.ENTRY_DIALOG, CarbFill.classify(g, false, false));
    }

    // ---- Boost and Ease-off ----

    private static CarbFill.Step mode(CarbFill.Screen s, CarbFill.Phase ph, boolean running, boolean matches, boolean durOk, boolean banner) {
        return CarbFill.decideMode(s, 0, ph, running, matches, durOk, banner);
    }

    @Test
    public void modeScreensAreRecognisedFromCamapsOwnWords() {
        assertEquals(CarbFill.Screen.MODE_DURATION, CarbFill.classify(t("Boost duration", "Hrs", "Mins", "Cancel", "Next"), true));
        assertEquals(CarbFill.Screen.MODE_START, CarbFill.classify(t("Boost start", "Now", "Later", "Cancel", "Confirm"), false));
        assertEquals(CarbFill.Screen.MODE_START, CarbFill.classify(t("Ease-off start", "Now", "08", "36", "Cancel", "Confirm"), false));
        assertEquals(CarbFill.Screen.MODE_OFF, CarbFill.classify(t("Boost", "Off", "Cancel", "Confirm", "Auto mode", "02:00:00"), false));
        // the bolus calculator's own Next and Confirm boxes are not mode screens
        assertEquals(CarbFill.Screen.CARBS_DIALOG, CarbFill.classify(t("Carbohydrates", "Cancel", "Confirm"), true));
        assertEquals(CarbFill.Screen.HOME, CarbFill.classify(t("Auto mode", "On", "Boost", "Ease-off"), false));
    }

    @Test
    public void aBoostGoesThroughDurationNowAndConfirm() {
        assertEquals(CarbFill.Step.OPEN_MODE, mode(CarbFill.Screen.HOME, CarbFill.Phase.START, false, true, false, false));
        assertEquals(CarbFill.Step.TYPE_DURATION, mode(CarbFill.Screen.MODE_DURATION, CarbFill.Phase.START, false, true, false, false));
        assertEquals(CarbFill.Step.NEXT_DURATION, mode(CarbFill.Screen.MODE_DURATION, CarbFill.Phase.DURATION_TYPED, false, true, true, false));
        assertEquals(CarbFill.Step.ABORT_MISMATCH, mode(CarbFill.Screen.MODE_DURATION, CarbFill.Phase.DURATION_TYPED, false, true, false, false));
        assertEquals(CarbFill.Step.PICK_NOW, mode(CarbFill.Screen.MODE_START, CarbFill.Phase.DURATION_NEXT, false, true, false, false));
        assertEquals(CarbFill.Step.CONFIRM_START, mode(CarbFill.Screen.MODE_START, CarbFill.Phase.NOW_PICKED, false, true, false, false));
        assertEquals(CarbFill.Step.WAIT, mode(CarbFill.Screen.HOME, CarbFill.Phase.START_CONFIRMED, true, true, false, false));
        assertEquals(CarbFill.Step.FINISH, mode(CarbFill.Screen.HOME, CarbFill.Phase.START_CONFIRMED, true, true, false, true));
    }

    @Test
    public void theOtherModesBoxStopsEverything() {
        assertEquals(CarbFill.Step.ABORT_MISMATCH, mode(CarbFill.Screen.MODE_DURATION, CarbFill.Phase.START, false, false, false, false));
        assertEquals(CarbFill.Step.ABORT_MISMATCH, mode(CarbFill.Screen.MODE_START, CarbFill.Phase.DURATION_NEXT, false, false, false, false));
    }

    @Test
    public void aRunningModeIsCancelledFirstAndTheOffBoxConfirmedOnlyAfterOurOwnTap() {
        assertEquals(CarbFill.Step.OPEN_CANCEL, mode(CarbFill.Screen.HOME, CarbFill.Phase.START, true, true, false, false));
        assertEquals(CarbFill.Step.CONFIRM_OFF, mode(CarbFill.Screen.MODE_OFF, CarbFill.Phase.CANCELLING, true, true, false, false));
        // an Off box nobody asked us to open is closed, not confirmed
        assertEquals(CarbFill.Step.BACK, mode(CarbFill.Screen.MODE_OFF, CarbFill.Phase.START, true, true, false, false));
        assertEquals(CarbFill.Step.WAIT, mode(CarbFill.Screen.HOME, CarbFill.Phase.OFF_CONFIRMED, true, true, false, false));
        assertEquals(CarbFill.Step.OPEN_MODE, mode(CarbFill.Screen.HOME, CarbFill.Phase.OFF_CONFIRMED, false, true, false, false));
        assertEquals(CarbFill.Step.TYPE_DURATION, mode(CarbFill.Screen.MODE_DURATION, CarbFill.Phase.OFF_CONFIRMED, false, true, false, false));
    }

    @Test
    public void modeAlertsAndDeliveriesStillStopEverything() {
        for (CarbFill.Phase ph : CarbFill.Phase.values()) {
            assertEquals(CarbFill.Step.ABORT_BUSY, mode(CarbFill.Screen.BUSY, ph, false, true, true, true));
            assertEquals(CarbFill.Step.ABORT_OTHER_DIALOG, mode(CarbFill.Screen.OTHER_DIALOG, ph, false, true, true, true));
        }
    }

    @Test
    public void durationAndBannerChecks() {
        assertEquals(120, CarbFill.durationMinutes("2", "00"));
        assertEquals(90, CarbFill.durationMinutes("01", "30"));
        assertEquals(-1, CarbFill.durationMinutes("", "30"));
        assertEquals(-1, CarbFill.durationMinutes("2", "75"));
        assertEquals(120, CarbFill.timerMinutes("02:00:00"));
        assertEquals(119, CarbFill.timerMinutes("01:59:56"));
        assertEquals(-1, CarbFill.timerMinutes("5.5"));
        assertTrue(CarbFill.bannerMatches(t("02:00:00", "Boost"), CarbFill.Kind.BOOST, 120));
        assertTrue(CarbFill.bannerMatches(t("01:59:56", "Boost"), CarbFill.Kind.BOOST, 120));
        assertFalse("a longer boost than asked is wrong", CarbFill.bannerMatches(t("03:00:00", "Boost"), CarbFill.Kind.BOOST, 120));
        assertFalse(CarbFill.bannerMatches(t("02:00:00", "Ease-off"), CarbFill.Kind.BOOST, 120));
        assertFalse(CarbFill.bannerMatches(t("Boost"), CarbFill.Kind.BOOST, 120));
        assertTrue(CarbFill.dialogMatches(t("Boost duration", "Boost", "Ease-off"), CarbFill.Kind.BOOST));
        assertFalse(CarbFill.dialogMatches(t("Ease-off duration", "Boost"), CarbFill.Kind.BOOST));
        assertTrue(CarbFill.modeRunning(t("02:00:00", "Boost")));
        assertFalse(CarbFill.modeRunning(t("Boost", "Ease-off")));
    }

    @Test
    public void modeAmountsAreMinutesInSteps() {
        assertTrue(CarbOptions.validFor(CarbFill.Kind.BOOST, 120));
        assertTrue(CarbOptions.validFor(CarbFill.Kind.EASE, 45));
        assertFalse(CarbOptions.validFor(CarbFill.Kind.BOOST, 10));
        assertFalse(CarbOptions.validFor(CarbFill.Kind.BOOST, 361));
        assertFalse(CarbOptions.validFor(CarbFill.Kind.EASE, 47));
    }

    // ---- Add meal through to its final box ----

    private static CarbFill.Step add(CarbFill.Screen s, CarbFill.Phase ph, boolean sel, boolean amountOk, boolean confirmOk, boolean settled) {
        return CarbFill.decideAdd(s, 0, ph, sel, true, amountOk, confirmOk, settled);
    }

    @Test
    public void aSlowMealIsContinuedAndConfirmedOnlyAfterItReadsBack() {
        assertEquals(CarbFill.Step.CONTINUE_ADD, add(CarbFill.Screen.ADD_MEAL, CarbFill.Phase.TYPED, true, true, false, false));
        assertEquals(CarbFill.Step.ABORT_MISMATCH, add(CarbFill.Screen.ADD_MEAL, CarbFill.Phase.TYPED, false, true, false, false));
        assertEquals(CarbFill.Step.ABORT_MISMATCH, add(CarbFill.Screen.ADD_MEAL, CarbFill.Phase.TYPED, true, false, false, false));
        assertEquals(CarbFill.Step.CONFIRM_ADD, add(CarbFill.Screen.ADD_CONFIRM, CarbFill.Phase.CONTINUED, true, true, true, false));
        assertEquals(CarbFill.Step.ABORT_MISMATCH, add(CarbFill.Screen.ADD_CONFIRM, CarbFill.Phase.CONTINUED, true, true, false, false));
        assertEquals(CarbFill.Step.WAIT, add(CarbFill.Screen.ADD_MEAL, CarbFill.Phase.CONTINUED, true, true, false, false));
        assertEquals(CarbFill.Step.WAIT, add(CarbFill.Screen.ADD_CONFIRM, CarbFill.Phase.ADD_CONFIRMED, true, true, true, true));
        assertEquals(CarbFill.Step.WAIT, add(CarbFill.Screen.HOME, CarbFill.Phase.ADD_CONFIRMED, true, true, true, false));
        assertEquals(CarbFill.Step.FINISH, add(CarbFill.Screen.HOME, CarbFill.Phase.ADD_CONFIRMED, true, true, true, true));
    }

    @Test
    public void theFinalBoxIsRecognisedWhateverItsTitle() {
        List<String> box = t("Meal", "1", "g CHO", "Cancel", "Confirm", "Add meal");
        assertEquals(CarbFill.Screen.ADD_CONFIRM, CarbFill.classify(box, false));
        // behind-the-box editable fields (the dimmed Add meal page) don't stop it being recognised
        assertEquals(CarbFill.Screen.ADD_CONFIRM, CarbFill.classify(box, true));
        assertEquals(CarbFill.Screen.CARBS_DIALOG, CarbFill.classify(t("Carbohydrates", "g CHO", "Cancel", "Confirm"), true));
    }

    // ---- plans ----

    private static CarbFill.Item item(CarbFill.Kind k, int a) {
        return new CarbFill.Item(k, a, "t");
    }

    @Test
    public void aPlanRunsInTheFixedOrderAndEndsOnTheMeal() {
        assertNull(CarbFill.validatePlan(Arrays.asList(item(CarbFill.Kind.SLOW, 15), item(CarbFill.Kind.BOOST, 120), item(CarbFill.Kind.MEAL, 45))));
        assertNull(CarbFill.validatePlan(Arrays.asList(item(CarbFill.Kind.EASE, 60), item(CarbFill.Kind.MEAL, 45))));
        assertNull(CarbFill.validatePlan(Arrays.asList(item(CarbFill.Kind.MEAL, 45))));
        assertNotNull("the meal must be last", CarbFill.validatePlan(Arrays.asList(item(CarbFill.Kind.MEAL, 45), item(CarbFill.Kind.BOOST, 120))));
        assertNotNull(CarbFill.validatePlan(Arrays.asList(item(CarbFill.Kind.BOOST, 120), item(CarbFill.Kind.SLOW, 15))));
        assertNotNull("both modes", CarbFill.validatePlan(Arrays.asList(item(CarbFill.Kind.BOOST, 120), item(CarbFill.Kind.EASE, 60))));
        assertNotNull("twice", CarbFill.validatePlan(Arrays.asList(item(CarbFill.Kind.MEAL, 45), item(CarbFill.Kind.MEAL, 30))));
        assertNotNull("low alone", CarbFill.validatePlan(Arrays.asList(item(CarbFill.Kind.LOW, 15), item(CarbFill.Kind.MEAL, 45))));
        assertNull(CarbFill.validatePlan(Arrays.asList(item(CarbFill.Kind.LOW, 15))));
        assertNotNull("out of range", CarbFill.validatePlan(Arrays.asList(item(CarbFill.Kind.BOOST, 7))));
        assertNotNull(CarbFill.validatePlan(new java.util.ArrayList<CarbFill.Item>()));
    }

    @Test
    public void stepsAreParsedFromTheirOneStringAndNothingElse() {
        List<CarbFill.Item> p = CarbFill.parseSteps("SLOW:15;BOOST:120;MEAL:45", "x");
        assertEquals(3, p.size());
        assertEquals(CarbFill.Kind.BOOST, p.get(1).kind);
        assertEquals(120, p.get(1).amount);
        assertNull(CarbFill.parseSteps("DELIVER:3", "x"));
        assertNull(CarbFill.parseSteps("MEAL:abc", "x"));
        assertNull(CarbFill.parseSteps("MEAL", "x"));
        assertNull(CarbFill.parseSteps("", "x"));
        assertNull(CarbFill.parseSteps("MEAL:1000", "x"));
    }

    @Test
    public void onlyThePlanScreenAndTheSheetsStartAPlan() throws Exception {
        java.util.TreeSet<String> callers = new java.util.TreeSet<>();
        File dir = null;
        for (String q : new String[]{"src/main/java/studio/alans/fxwatcher", "app/src/main/java/studio/alans/fxwatcher"}) {
            if (new File(q).isDirectory()) dir = new File(q);
        }
        for (File f : dir.listFiles((d, n) -> n.endsWith(".java"))) {
            if (f.getName().equals("CarbFill.java")) continue;
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (text.contains("CarbFill.runPlan(")) callers.add(f.getName());
        }
        // the plan screen starts it, and only from its own Run button
        assertEquals(new java.util.TreeSet<>(Arrays.asList("CarbPlanActivity.java")), callers);
        String src = new String(Files.readAllBytes(new File(dir, "CarbPlanActivity.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(src.contains("setPositiveButton(\"Run\""));
    }

    @Test
    public void onlyAPersonsTapStartsARequest() throws Exception {
        java.util.TreeSet<String> callers = new java.util.TreeSet<>();
        File dir = null;
        for (String p : new String[]{"src/main/java/studio/alans/fxwatcher", "app/src/main/java/studio/alans/fxwatcher"}) {
            if (new File(p).isDirectory()) dir = new File(p);
        }
        for (File f : dir.listFiles((d, n) -> n.endsWith(".java"))) {
            if (f.getName().equals("CarbFill.java")) continue;
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (text.contains("CarbFill.request(")) callers.add(f.getName());
        }
        // the app's own buttons, and the activity a widget tap lands on
        assertEquals(new java.util.TreeSet<>(Arrays.asList("CarbFillActivity.java", "CarbMenuActivity.java", "CarbSheetActivity.java", "MainActivity.java")), callers);
    }

    private static int count(String s, String what) {
        int n = 0, i = 0;
        while ((i = s.indexOf(what, i)) >= 0) {
            n++;
            i += what.length();
        }
        return n;
    }
}
