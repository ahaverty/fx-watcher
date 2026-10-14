package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CamapsAlertTest {

    @Test
    public void veryLowReservoir() {
        // The real notification, 15/10/2026 00:05
        CamapsAlert a = CamapsAlert.parse("Alert",
                "Very low level of insulin in reservoir. \n9.8 U of insulin left in reservoir.");
        assertEquals(CamapsAlert.RESERVOIR_VERY_LOW, a.kind);
        assertTrue(a.alert);
        assertTrue(a.reservoir());
        assertEquals(9.8, a.units, 1e-9);
    }

    @Test
    public void lowReservoirAndCommaDecimal() {
        CamapsAlert a = CamapsAlert.parse("Alert", "Low level of insulin in reservoir. 20,5 U of insulin left in reservoir.");
        assertEquals(CamapsAlert.RESERVOIR_LOW, a.kind);
        assertEquals(20.5, a.units, 1e-9);
    }

    @Test
    public void pumpConnect() {
        CamapsAlert a = CamapsAlert.parse("Information", "Unable to connect to your pump. \n\nIf the pump is "
                + "nearby, turn off and then turn on Bluetooth on your smart device.");
        assertEquals(CamapsAlert.PUMP_CONNECT, a.kind);
        assertFalse(a.alert);
        assertTrue(Double.isNaN(a.units));
    }

    @Test
    public void knownInformation() {
        assertEquals(CamapsAlert.SENSOR_EXPIRY, CamapsAlert.parse("Information", "Sensor expires in less than 2 hours. ").kind);
        assertEquals(CamapsAlert.DELIVERED, CamapsAlert.parse("Information", "Delivered 1.4 U of insulin.").kind);
        CamapsAlert o = CamapsAlert.parse(null, "Something new");
        assertEquals(CamapsAlert.OTHER, o.kind);
        assertFalse(o.reservoir());
    }

    @Test
    public void reservoirSaveLoadAndChange() {
        CamapsAlert.Reservoir r = CamapsAlert.Reservoir.load(new CamapsAlert.Reservoir(9.8, 1000, "alert").save());
        assertEquals(9.8, r.units, 1e-9);
        assertEquals(1000, r.at);
        assertEquals("alert", r.source);
        assertFalse(r.changedBy(9.8, 2000));    // the popup repeating the notification
        assertFalse(r.changedBy(150, 500));     // older than what is kept
        assertTrue(r.changedBy(150, 2000));     // new cartridge
        assertNull(CamapsAlert.Reservoir.load(""));
    }
}
