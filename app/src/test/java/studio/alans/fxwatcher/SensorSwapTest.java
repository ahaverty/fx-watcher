package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SensorSwapTest {

    private static final long MIN = 60_000L, H = 60 * MIN, T = 1_800_000_000_000L;

    @Test
    public void wholeSwap() {
        SensorSwap w = new SensorSwap();
        assertEquals(SensorSwap.Kind.STOP, w.stopped(T));
        assertEquals(SensorSwap.Kind.NONE, w.texts(Arrays.asList("Enter your sensor pairing code", "Pairing code", "8538", "OK"), T + MIN));
        assertEquals(SensorSwap.Kind.START, w.texts(Collections.singletonList("Sensor paired successfully."), T + 2 * MIN));
        assertEquals("8538", w.startCode);
        assertEquals(T + 2 * MIN, w.startedAt);
        assertEquals("New sensor, pairing code 8538", w.startNotes());
        // the bond and the warm-up message are the same start
        assertEquals(SensorSwap.Kind.START_UPDATED, w.started(T + 3 * MIN, "DXCMQL"));
        assertEquals(T + 2 * MIN, w.startedAt);
        assertEquals("New sensor, pairing code 8538, DXCMQL", w.startNotes());
        assertEquals(SensorSwap.Kind.NONE, w.texts(Collections.singletonList("Sensor warming up. Wait for 30 minutes."), T + 4 * MIN));
    }

    @Test
    public void bondAloneIsAStart() {
        SensorSwap w = new SensorSwap();
        assertEquals(SensorSwap.Kind.START, w.started(T, "DXCMQL"));
        assertEquals("New sensor, DXCMQL", w.startNotes());
    }

    @Test
    public void stopCountsOncePerSession() {
        SensorSwap w = new SensorSwap();
        assertEquals(SensorSwap.Kind.STOP, w.texts(Collections.singletonList("Sensor stopped. \n\nYou will not receive sensor related alerts."), T));
        assertEquals(SensorSwap.Kind.NONE, w.texts(Collections.singletonList("Sensor stopped."), T + MIN));
        w.started(T + 2 * MIN, null);
        // an old popup right after the start is not the new sensor stopping
        assertEquals(SensorSwap.Kind.NONE, w.stopped(T + 3 * MIN));
        assertEquals(SensorSwap.Kind.STOP, w.stopped(T + 5 * 24 * H));
    }

    @Test
    public void nextSensorIsANewStart() {
        SensorSwap w = new SensorSwap();
        w.started(T, "DXCMQL");
        assertEquals(SensorSwap.Kind.NONE, w.started(T + H, null));
        assertEquals(SensorSwap.Kind.START, w.started(T + 10 * 24 * H, "DXCMab"));
        assertEquals("DXCMab", w.startName);
    }

    @Test
    public void expiredRepeatsCountOnce() {
        SensorSwap w = new SensorSwap();
        assertTrue(w.firstExpired(T));
        assertFalse(w.firstExpired(T + 5 * MIN));
        w.started(T + H, null);
        assertTrue(w.firstExpired(T + 10 * 24 * H));
    }

    @Test
    public void pairingCodeOnlyOnTheSensorScreen() {
        assertEquals("8538", SensorSwap.pairingCode(Arrays.asList("Pairing code", "8538")));
        assertNull(SensorSwap.pairingCode(Arrays.asList("Pairing code", "Dexcom G7 pairing code input. Enter 4 digits.")));
        assertNull(SensorSwap.pairingCode(Arrays.asList("Activate pairing on your pump to receive pairing code.", "1234")));
        assertNull(SensorSwap.pairingCode(Arrays.asList("Insulin today", "2026")));
        // the FAQ's example is inside a sentence
        assertNull(SensorSwap.pairingCode(Collections.singletonList("It is a 4-digit code (for example 1234) used as the pairing code")));
    }

    @Test
    public void oldCodeIsNotAttached() {
        SensorSwap w = new SensorSwap();
        w.texts(Arrays.asList("Pairing code", "1111"), T);
        w.started(T + 2 * H, null);
        assertNull(w.startCode);
    }

    @Test
    public void stopDialogAndButtons() {
        assertTrue(SensorSwap.stopDialog(Collections.singletonList("Once your sensor has stopped, it cannot be restarted. \n\nYou will not receive alerts")));
        assertFalse(SensorSwap.stopDialog(Collections.singletonList("Stop sensor")));
        assertTrue(SensorSwap.confirmsStop("Stop sensor"));
        assertTrue(SensorSwap.confirmsStop("Confirm"));
        assertFalse(SensorSwap.confirmsStop("Cancel"));
        assertFalse(SensorSwap.confirmsStop(""));
    }

    @Test
    public void realTextsThatAreNotAChange() {
        SensorSwap w = new SensorSwap();
        for (String t : new String[]{"Stop sensor", "Start new sensor", "Sensor warmup", "Sensor info",
                "Sensor expired. \nReplace sensor. \nIn case you need more time to replace your sensor, we give you an extra time 12 hour grace period.",
                "Sensor expires in less than 24 hours."}) {
            assertEquals(t, SensorSwap.Kind.NONE, w.texts(Collections.singletonList(t), T));
        }
    }

    @Test
    public void saveAndLoad() {
        SensorSwap w = new SensorSwap();
        w.stopped(T);
        w.texts(Arrays.asList("Pairing code", "8538"), T + MIN);
        w.started(T + 2 * MIN, "DXCMQL");
        SensorSwap l = SensorSwap.load(w.save());
        assertEquals(T, l.stoppedAt);
        assertEquals(T + 2 * MIN, l.startedAt);
        assertEquals("8538", l.startCode);
        assertEquals("DXCMQL", l.startName);
        assertEquals(0, SensorSwap.load("rubbish").startedAt);
        assertEquals(0, SensorSwap.load("").startedAt);
    }
}
