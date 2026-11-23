package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AlertAudioTest {

    @Test
    public void usageAndContentHaveNames() {
        assertEquals("ALARM", AlertAudio.usage(4));
        assertEquals("MEDIA", AlertAudio.usage(1));
        assertEquals("UNKNOWN", AlertAudio.usage(0));
        assertEquals("usage99", AlertAudio.usage(99));
        assertEquals("MUSIC", AlertAudio.content(2));
        assertEquals("SONIFICATION", AlertAudio.content(4));
    }

    @Test
    public void notificationWithNoActionsSaysSo() {
        String s = AlertAudio.describe("com.camdiab.fx_alert.mmoll", 7, "notification_alarm_channel", 2, false,
                false, false, 0x10, "alarm", "Alert", null, true, false, false, false);
        assertTrue(s, s.contains("channel notification_alarm_channel importance 2"));
        assertTrue(s, s.contains("no DND bypass"));
        assertTrue(s, s.contains("contentIntent yes"));
        assertTrue(s, s.contains("deleteIntent no"));
        assertTrue(s, s.contains("actions none"));
        assertFalse(s, s.contains("(ongoing)"));
    }

    @Test
    public void actionsAreListedByTitle() {
        String s = AlertAudio.describe("com.camdiab.fx_alert.mmoll", 3, "x", -1, false, false, false, 0, null,
                "", new String[]{"OK", "Snooze"}, false, true, true, true);
        assertTrue(s, s.contains("actions 2 [OK | Snooze]"));
        assertTrue(s, s.contains("(ongoing)"));
        assertFalse("no channel known, so no importance text", s.contains("importance"));
    }

    @Test
    public void sampleShowsPlayersAndVolumes() {
        String s = AlertAudio.sampleLine(400, Arrays.asList("usage UNKNOWN, content UNKNOWN, flags 0x0"),
                7, 15, 5, 7, 0, 7, 0, 7, "vibrate");
        assertEquals("audio +400ms players 1 [usage UNKNOWN, content UNKNOWN, flags 0x0] | music 7/15 "
                + "alarm 5/7 notif 0/7 ring 0/7 ringer vibrate", s);
        assertTrue(AlertAudio.sampleLine(0, Collections.<String>emptyList(), 0, 15, 0, 7, 0, 7, 0, 7, "normal")
                .contains("players 0"));
    }

    @Test
    public void onlyVeryLowGlucoseStartsARecording() {
        assertTrue(AlertAudio.looksUrgent(3.1));
        assertTrue(AlertAudio.looksUrgent(2.2));
        assertFalse(AlertAudio.looksUrgent(3.3));
        assertFalse(AlertAudio.looksUrgent(5.6));
        assertFalse(AlertAudio.looksUrgent(0));
    }
}
