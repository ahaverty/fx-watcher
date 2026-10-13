package studio.alans.fxwatcher;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the app log (newest first, "dd/MM HH:mm:ss  message") into text for the clipboard: the last
 * few hours or all of it, oldest first so it reads top to bottom, with a short header saying which
 * version and phone it came from. Pure Java, so it is unit tested.
 */
final class LogExport {

    private LogExport() { }

    private static final Pattern STAMP = Pattern.compile("^(\\d\\d)/(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)\\s.*");

    /** The log lines from the last `hours` hours (0 = all), oldest first, one string. */
    static String lines(String log, long now, double hours) {
        if (log == null || log.isEmpty()) return "";
        String[] newestFirst = log.split("\n");
        long from = hours <= 0 ? Long.MIN_VALUE : now - Math.round(hours * 3600_000L);
        // Entries: a stamped line plus any unstamped lines under it. Newest first in, oldest first out.
        java.util.List<String> entries = new java.util.ArrayList<>();
        java.util.List<Boolean> kept = new java.util.ArrayList<>();
        for (String l : newestFirst) {
            if (l.isEmpty()) continue;
            Matcher m = STAMP.matcher(l);
            if (m.matches() || entries.isEmpty()) {
                entries.add(l);
                kept.add(m.matches() ? stamp(m, now) >= from : Boolean.TRUE);
            } else {
                entries.set(entries.size() - 1, entries.get(entries.size() - 1) + "\n" + l);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (kept.get(i)) sb.append(entries.get(i)).append('\n');
        }
        return sb.toString();
    }

    /** How many lines lines() produced (for the "Copied n lines" toast). */
    static int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    /** "FX Watcher v3.3.0 (build 19) on Samsung SM-S918B, Android 15, copied 13/10 15:30". */
    static String header(String version, String device, String android, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        return String.format(Locale.UK, "FX Watcher %s on %s, Android %s, copied %02d/%02d %02d:%02d",
                version, device, android, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** The time of a log line: this year, or last year if that would be in the future. */
    private static long stamp(Matcher m, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.MONTH, Integer.parseInt(m.group(2)) - 1);
        c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(1)));
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(3)));
        c.set(Calendar.MINUTE, Integer.parseInt(m.group(4)));
        c.set(Calendar.SECOND, Integer.parseInt(m.group(5)));
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() > now + 24 * 3600_000L) c.add(Calendar.YEAR, -1);
        return c.getTimeInMillis();
    }
}
