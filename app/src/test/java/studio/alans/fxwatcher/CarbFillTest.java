package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertEquals("10,15,20,30,45,60", CarbOptions.joinPresets(CarbOptions.parsePresets(CarbOptions.DEFAULT_PRESETS)));
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
        assertEquals(6, o.size());
        assertTrue(o.get(0).plan);
        assertEquals("Plan 45 g", o.get(0).label);
        assertEquals(45, o.get(0).grams);
        assertEquals(10, o.get(1).grams);
        assertFalse(o.get(1).plan);
        // a stale plan is not offered
        List<CarbOptions.Option> stale = CarbOptions.options(p, Arrays.asList(10, 20), 1000 + CarbOptions.PLAN_TTL_MS + 5);
        assertEquals(2, stale.size());
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
        assertEquals(CarbFill.Screen.ENTRY_DIALOG, CarbFill.classify(boost, true));
        assertEquals(CarbFill.Step.BACK, CarbFill.decide(CarbFill.Screen.ENTRY_DIALOG, 0));
        assertEquals(CarbFill.Screen.ENTRY_DIALOG, CarbFill.classify(t("Ease-off duration", "Cancel", "Next"), true));
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
    public void limitsAreTightAndFixed() {
        assertEquals(60_000L, CarbFill.TTL_MS);
        assertEquals(12, CarbFill.MAX_ACTIONS);
        assertEquals(6, CarbFill.MAX_BACKS);
    }

    // ---- the source can only do what it says ----

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
        assertTrue(code.contains("field.performAction(AccessibilityNodeInfo.ACTION_CLICK)"));
        assertFalse(code.contains("findAccessibilityNodeInfosByText"));
        // the field typed into is the one inside the Carbohydrates box, never "the first editable field on screen"
        assertTrue(code.contains("AccessibilityNodeInfo box = boxField;"));
        assertTrue(code.contains("static AccessibilityNodeInfo dialogField("));
        assertFalse(code.contains("firstEditable"));
        // typing happens only in the TYPE step, which decide() returns only for CARBS_DIALOG
        assertTrue(code.contains("case CARBS_DIALOG: return Step.TYPE;"));
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
        assertEquals(new java.util.TreeSet<>(Arrays.asList("CarbFillActivity.java", "MainActivity.java")), callers);
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
