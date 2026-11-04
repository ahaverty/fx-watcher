package studio.alans.fxwatcher;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A CGM sensor change, from what CamAPS shows and what Bluetooth does. Stopping a Dexcom G7 and
 * starting the next one happens on CamAPS's own screens, with no notification, so
 * CamapsListener alone never sees it. Wording from CamAPS 1.4(192)'s resources:
 *   stop:   "Stop sensor" -> "Once your sensor has stopped, it cannot be restarted." -> "Sensor stopped."
 *   code:   "Enter your sensor pairing code", "Pairing code", 4 digits
 *   start:  "Sensor paired successfully.", "Your sensor is now warming up.", "Sensor warming up."
 *   and a new Bluetooth bond with a "DXCMxx" device, which does not depend on the wording at all.
 *   info:   the "Dexcom G7" page: "Dexcom G7 information", "Pairing code", "9995", "Serial number",
 *           "...", "Activated on", "2026-11-03 22:46:05", "Expires on", "2026-11-13 22:46:05"
 *           (seen 4 Nov 2026). A G7 is activated when applied and can be paired an hour or more
 *           later, so this is the real session and expiry, but only if the page gets opened.
 * Each session gives one stop and one start, whichever signal comes first. Pure, so it can be
 * tested off the phone. Read only: nothing here touches CamAPS.
 */
final class SensorSwap {

    enum Kind { NONE, STOP, START, START_UPDATED }

    static final String BOND_PREFIX = "DXCM";

    private static final long START_WINDOW = 12 * 3_600_000L;  // the same start's signals, repeated
    private static final long STOP_AFTER_START = 10 * 60_000L; // an old popup left over from the swap
    private static final long CODE_WINDOW = 30 * 60_000L;      // code typed, then pairing finishes

    private static final Pattern CODE = Pattern.compile("^\\d{4}$");

    private static final String[] STOPPED = {
            "sensor stopped.",
            "your sensor ended.",
            "your sensor session has ended.",
            "grace period expired.",
            "sensor failure.",
            "your sensor is not working.",
            "sensor insertion failed.",
    };
    private static final String[] STARTED = {
            "sensor paired successfully",
            "your sensor is now warming up",
            "sensor warming up.",
    };

    long stoppedAt;
    long startedAt;
    long expiredAt;      // first "Sensor expired" of this session; CamAPS repeats it every 5 min
    String code;         // pairing code last seen on screen
    long codeAt;
    String startCode;    // what the current session started with
    String startName;
    String startSerial;
    long activatedAt;
    long expiresAt;

    /** What the "Dexcom G7" page says about the sensor in use. */
    static final class Info {
        final String code, serial;
        final long activated, expires;

        Info(String code, String serial, long activated, long expires) {
            this.code = code;
            this.serial = serial;
            this.activated = activated;
            this.expires = expires;
        }
    }

    /** The "Dexcom G7" page's details, or null if this is not that page. */
    static Info info(List<String> texts) {
        if (!texts.contains("Dexcom G7 information")) return null;
        String serial = after(texts, "Serial number");
        if (serial == null) return null;
        return new Info(after(texts, "Pairing code"), serial,
                time(after(texts, "Activated on")), time(after(texts, "Expires on")));
    }

    private static String after(List<String> texts, String label) {
        int i = texts.indexOf(label);
        return i >= 0 && i + 1 < texts.size() ? texts.get(i + 1).trim() : null;
    }

    private static long time(String s) {
        if (s == null) return 0;
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** The page was open: a new serial is a new sensor, otherwise it fills in the details. */
    Kind info(Info i) {
        boolean same = i.serial.equals(startSerial);
        if (same) return Kind.NONE;
        boolean sameSession = startSerial == null && startedAt > 0 && i.activated > 0
                && Math.abs(startedAt - i.activated) < START_WINDOW;
        Kind k = sameSession ? Kind.START_UPDATED : Kind.START;
        if (k == Kind.START) {
            startedAt = i.activated > 0 ? i.activated : System.currentTimeMillis();
            startName = null;
            expiredAt = 0;
        }
        startSerial = i.serial;
        startCode = i.code;
        activatedAt = i.activated;
        expiresAt = i.expires;
        return k;
    }

    /** Whether the stop confirmation is on screen (its button's label is not known). */
    static boolean stopDialog(List<String> texts) {
        for (String t : texts) {
            if (lower(t).contains("once your sensor has stopped")) return true;
        }
        return false;
    }

    /** A click on the stop confirmation that goes ahead, rather than backing out. */
    static boolean confirmsStop(String label) {
        String l = lower(label).trim();
        return !l.isEmpty() && !l.equals("cancel") && !l.equals("no") && !l.equals("back")
                && !l.equals("close") && !l.equals("dismiss");
    }

    /** The 4-digit code being typed, or null if this is not the sensor's pairing code screen. */
    static String pairingCode(List<String> texts) {
        boolean pairing = false;
        String digits = null;
        for (String t : texts) {
            String l = lower(t);
            if (l.contains("pump")) return null;   // CamAPS also pairs the pump with a code
            if (l.contains("pairing code")) pairing = true;
            if (CODE.matcher(t.trim()).matches()) digits = t.trim();
        }
        return pairing ? digits : null;
    }

    static boolean stoppedText(String text) {
        return containsAny(text, STOPPED);
    }

    static boolean startedText(String text) {
        return containsAny(text, STARTED);
    }

    /** A CamAPS screen or notification's texts. Tells the caller what to record. */
    Kind texts(List<String> texts, long now) {
        Info page = info(texts);
        if (page != null) return info(page);
        String c = pairingCode(texts);
        if (c != null) {
            code = c;
            codeAt = now;
        }
        Kind k = Kind.NONE;
        for (String t : texts) {
            if (stoppedText(t) && stopped(now) == Kind.STOP) k = Kind.STOP;
            if (startedText(t)) {
                Kind s = started(now, null);
                if (s != Kind.NONE) k = s;
            }
        }
        return k;
    }

    /** The sensor was stopped or ended. One per session. */
    Kind stopped(long now) {
        if (stoppedAt > startedAt) return Kind.NONE;
        if (startedAt > 0 && now - startedAt < STOP_AFTER_START) return Kind.NONE;
        stoppedAt = now;
        return Kind.STOP;
    }

    /** A new sensor started, or a new bond (name set). One per session; later signals add detail. */
    Kind started(long now, String name) {
        boolean again = startedAt > 0 && stoppedAt <= startedAt && now - startedAt < START_WINDOW;
        if (!again) {
            startedAt = now;
            expiredAt = 0;
            startCode = null;
            startName = name;
            startSerial = null;
            activatedAt = 0;
            expiresAt = 0;
            attachCode(now);
            return Kind.START;
        }
        boolean changed = name != null && !name.equals(startName);
        if (changed) startName = name;
        if (startCode == null && attachCode(now)) changed = true;
        return changed ? Kind.START_UPDATED : Kind.NONE;
    }

    private boolean attachCode(long now) {
        if (code == null || now - codeAt > CODE_WINDOW) return false;
        startCode = code;
        code = null;
        return true;
    }

    /** First "Sensor expired" of a session, as opposed to CamAPS's 5 minute repeats. */
    boolean firstExpired(long at) {
        if (expiredAt > startedAt) return false;
        if (startedAt > 0 && at - startedAt < START_WINDOW) return false;   // a late re-post of the old one
        expiredAt = at;
        return true;
    }

    /** "New sensor, pairing code 8538, DXCMQL". The upsert on (time, type) lets detail arrive later. */
    String startNotes() {
        StringBuilder s = new StringBuilder("New sensor");
        if (startCode != null) s.append(", pairing code ").append(startCode);
        if (startName != null) s.append(", ").append(startName);
        if (startSerial != null) s.append(", serial ").append(startSerial);
        return s.toString();
    }

    String save() {
        return stoppedAt + "," + startedAt + "," + expiredAt + "," + codeAt + ","
                + (code == null ? "" : code) + "," + (startCode == null ? "" : startCode) + ","
                + (startName == null ? "" : startName) + ","
                + (startSerial == null ? "" : startSerial) + "," + activatedAt + "," + expiresAt;
    }

    static SensorSwap load(String s) {
        SensorSwap w = new SensorSwap();
        if (s == null || s.isEmpty()) return w;
        try {
            String[] a = s.split(",", -1);
            w.stoppedAt = Long.parseLong(a[0]);
            w.startedAt = Long.parseLong(a[1]);
            w.expiredAt = Long.parseLong(a[2]);
            w.codeAt = Long.parseLong(a[3]);
            w.code = a[4].isEmpty() ? null : a[4];
            w.startCode = a[5].isEmpty() ? null : a[5];
            w.startName = a[6].isEmpty() ? null : a[6];
            if (a.length > 9) {
                w.startSerial = a[7].isEmpty() ? null : a[7];
                w.activatedAt = Long.parseLong(a[8]);
                w.expiresAt = Long.parseLong(a[9]);
            }
        } catch (Exception e) {
            return new SensorSwap();
        }
        return w;
    }

    private static boolean containsAny(String text, String[] phrases) {
        String l = lower(text);
        for (String p : phrases) if (l.contains(p)) return true;
        return false;
    }

    private static String lower(String t) {
        return t == null ? "" : t.toLowerCase(Locale.UK);
    }
}
