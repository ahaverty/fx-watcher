package studio.alans.fxwatcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the CGM sensor runs out, from CamAPS's "Information" notification (CamAPS 192, Dexcom G7):
 * "Sensor expires in less than 24 hours. \n\nThen you get a 12 hour grace period to replace the sensor."
 * CamAPS posts it as the time left crosses 24 h (with a glucose reading, so within ~5 min), so the
 * expiry is the notification's own time plus those hours, and the grace period ends that many
 * hours later again. Pure, so it can be tested off the phone.
 */
final class SensorExpiry {

    private static final Pattern EXPIRES = Pattern.compile(
            "Sensor expires in less than\\s+(\\d+)\\s*hours?", Pattern.CASE_INSENSITIVE);
    private static final Pattern GRACE = Pattern.compile(
            "(\\d+)\\s*hours?\\s+grace period", Pattern.CASE_INSENSITIVE);
    static final int DEF_GRACE_H = 12;   // G7: 12 h after the 10 days

    final long expires;    // ms
    final long graceEnd;   // ms

    SensorExpiry(long expires, long graceEnd) {
        this.expires = expires;
        this.graceEnd = graceEnd;
    }

    /** The expiry announced by this notification text posted at {@code at}, or null if it isn't one. */
    static SensorExpiry parse(String text, long at) {
        if (text == null) return null;
        Matcher m = EXPIRES.matcher(text);
        if (!m.find()) return null;
        long expires = at + Long.parseLong(m.group(1)) * 3_600_000L;
        Matcher g = GRACE.matcher(text);
        int grace = g.find() ? Integer.parseInt(g.group(1)) : DEF_GRACE_H;
        return new SensorExpiry(expires, expires + grace * 3_600_000L);
    }

    String save() {
        return expires + "," + graceEnd;
    }

    static SensorExpiry load(String s) {
        try {
            String[] a = s.split(",");
            return new SensorExpiry(Long.parseLong(a[0]), Long.parseLong(a[1]));
        } catch (Exception e) {
            return null;
        }
    }
}
