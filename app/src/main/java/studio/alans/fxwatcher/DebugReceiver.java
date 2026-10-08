package studio.alans.fxwatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Test hooks, reachable only from adb (protected by the DUMP permission). Never touches CamAPS.
 *
 *   adb shell am broadcast -n studio.alans.fxwatcher/.DebugReceiver --es status Attempting --es glucose 5.5 --ei hold 30
 *   adb shell am broadcast -n studio.alans.fxwatcher/.DebugReceiver --ez absent true --ei hold 30
 *   adb shell am broadcast -n studio.alans.fxwatcher/.DebugReceiver --ei backdate 25   (pretend it started 25 min ago)
 *   adb shell am broadcast -n studio.alans.fxwatcher/.DebugReceiver --es pump off --ei hold 60   (pump link off)
 *   adb shell am broadcast -n studio.alans.fxwatcher/.DebugReceiver --es status clear (back to the real CamAPS state)
 *   adb shell am broadcast -n studio.alans.fxwatcher/.DebugReceiver --es cmd test|check|silence|snooze|unsnooze
 *   adb shell am broadcast -n studio.alans.fxwatcher/.DebugReceiver --es url https://...   (webhook)
 */
public class DebugReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String url = i.getStringExtra("url");
        if (url != null) {
            Store.p(c).edit().putString(Store.URL, url.trim()).apply();
            Store.log(c, "Webhook URL set over adb");
        }
        String cmd = i.getStringExtra("cmd");
        if (cmd != null) {
            switch (cmd) {
                case "test": Monitor.test(c); break;
                case "check": Monitor.check(c); break;
                case "silence": Monitor.silence(c); break;
                case "snooze": Monitor.snooze(c, i.getDoubleExtra("hours", 2)); break;
                case "unsnooze": Monitor.unsnooze(c); break;                default: break;
            }
            return;
        }

        String status = i.getStringExtra("status");
        boolean absent = i.getBooleanExtra("absent", false);
        int backdate = i.getIntExtra("backdate", 0);
        if ("clear".equals(status)) {
            Store.p(c).edit().putLong(Store.SIM_UNTIL, 0).putLong(Store.BAD_SINCE, 0)
                    .putLong(Store.LOW_SINCE, 0).putLong(Store.MISSING_SINCE, 0)
                    .putLong(Store.PUMP_DOWN_SINCE, 0).remove(Store.PUMP_STATE).apply();
            Store.log(c, "Simulation cleared");
            Monitor.check(c);
            return;
        }
        if ("off".equals(i.getStringExtra("pump"))) {
            long hold = i.getIntExtra("hold", 30) * 60_000L;
            long now = System.currentTimeMillis();
            Store.p(c).edit().putLong(Store.SIM_UNTIL, now + hold).putInt(Store.PUMP_STATE, Phone.DOWN)
                    .putLong(Store.PUMP_DOWN_SINCE, now).apply();
            Store.log(c, "Simulating pump link off");
            Monitor.evaluate(c);
        }
        if (status != null || absent) {
            long hold = i.getIntExtra("hold", 30) * 60_000L;
            Store.p(c).edit().putLong(Store.SIM_UNTIL, System.currentTimeMillis() + hold).apply();
            Store.log(c, "Simulating " + (absent ? "CamAPS gone" : status + " " + i.getStringExtra("glucose")));
            String g = i.getStringExtra("glucose");
            ArrayList<String> texts = absent ? new ArrayList<>()
                    : new ArrayList<>(Arrays.asList("CamAPS FX", "Auto mode", status, g == null ? "---" : g, "mmol/L"));
            Monitor.onCamaps(c, !absent, status, g, texts, Store.p(c).getString(Store.PKG, null), true);
        }
        if (backdate > 0) {
            long shift = backdate * 60_000L;
            android.content.SharedPreferences p = Store.p(c);
            android.content.SharedPreferences.Editor e = p.edit();
            for (String k : new String[]{Store.BAD_SINCE, Store.LOW_SINCE, Store.MISSING_SINCE, Store.PUMP_DOWN_SINCE}) {
                long v = p.getLong(k, 0);
                if (v > 0) e.putLong(k, v - shift);
            }
            if (i.getBooleanExtra("stale", false)) e.putLong(Store.LAST_SEEN, p.getLong(Store.LAST_SEEN, 0) - shift);
            e.apply();
            Store.log(c, "Backdated by " + backdate + " min");
            Monitor.evaluate(c);
        }
    }
}
