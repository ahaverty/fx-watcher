package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class MealDialogTest {

    @Test
    public void plainMealDialog() {
        List<String> t = Arrays.asList("Meal", "17", "g CHO", "Cancel", "Confirm");
        assertTrue(CamapsReader.isMealDialog(t));
        assertEquals(17.0, CamapsReader.mealDialogAmount(t), 1e-9);
    }

    @Test
    public void mealDialogUnderAPopupOrCountdown() {
        List<String> t = Arrays.asList("Delivering bolus", "2.1 U", "Meal", "17", "g CHO", "Cancel", "Confirm");
        assertTrue(CamapsReader.isMealDialog(t));
        assertEquals(17.0, CamapsReader.mealDialogAmount(t), 1e-9);
    }

    @Test
    public void notTheCalculatorOrAddMealScreen() {
        assertFalse(CamapsReader.isMealDialog(Arrays.asList("Meal", "Carbs", "g CHO", "32", "=", "4.6", "Total Units", "Confirm")));
        assertFalse(CamapsReader.isMealDialog(Arrays.asList("Add meal", "17", "g CHO", "Meal or snack", "Slowly absorbed meal", "Next")));
        assertFalse(CamapsReader.isMealDialog(Arrays.asList("Boost start", "Now", "Confirm")));
    }

    @Test
    public void commaDecimal() {
        assertEquals(12.5, CamapsReader.mealDialogAmount(Arrays.asList("Meal", "12,5", "g CHO", "Confirm")), 1e-9);
    }
}
