package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SensorExpiryTest {

    private static final long H = 3_600_000L;
    // 13/10/2026 23:34:02 Irish time, the real notification's `when`
    private static final long AT = 1791930842051L;

    @Test
    public void realNotification() {
        SensorExpiry s = SensorExpiry.parse("Sensor expires in less than 24 hours. \n\n"
                + "Then you get a 12 hour grace period to replace the sensor.", AT);
        assertEquals(AT + 24 * H, s.expires);
        assertEquals(AT + 36 * H, s.graceEnd);
    }

    @Test
    public void otherHoursAndDefaultGrace() {
        SensorExpiry s = SensorExpiry.parse("Sensor expires in less than 2 hours.", AT);
        assertEquals(AT + 2 * H, s.expires);
        assertEquals(AT + 14 * H, s.graceEnd);
    }

    @Test
    public void notAnExpiry() {
        assertNull(SensorExpiry.parse("Delivered 1.3 U of insulin.", AT));
        assertNull(SensorExpiry.parse(null, AT));
    }

    @Test
    public void saveLoad() {
        SensorExpiry s = SensorExpiry.load(new SensorExpiry(5, 7).save());
        assertEquals(5, s.expires);
        assertEquals(7, s.graceEnd);
        assertNull(SensorExpiry.load(""));
    }
}
