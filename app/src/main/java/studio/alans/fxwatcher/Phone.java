package studio.alans.fxwatcher;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;

/**
 * Things FX Watcher checks on the phone itself: the pump's Bluetooth link and the battery.
 *
 * The pump link is only up while CamAPS is talking to the pump, so short gaps are normal;
 * the Monitor only complains when it has been down for a long time.
 */
final class Phone {

    private Phone() { }

    static final int UNKNOWN = -1, DOWN = 0, UP = 1;

    static boolean canSeeBluetooth(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isPump(Context c, BluetoothDevice d) {
        if (d == null || !canSeeBluetooth(c)) return false;
        try {
            String want = Store.p(c).getString(Store.PUMP_NAME, Store.DEF_PUMP_NAME).trim();
            if (want.isEmpty()) return false;
            String name = d.getName();
            return (name != null && name.toLowerCase().contains(want.toLowerCase()))
                    || want.equalsIgnoreCase(d.getAddress());
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Asks Bluetooth whether the pump is connected right now. UNKNOWN if we can't tell. */
    static int pumpNow(Context c) {
        if (!canSeeBluetooth(c)) return UNKNOWN;
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null) return UNKNOWN;
        try {
            if (!a.isEnabled()) return isPumpPaired(c, a) ? DOWN : UNKNOWN;
            boolean paired = false;
            for (BluetoothDevice d : a.getBondedDevices()) {
                if (!isPump(c, d)) continue;
                paired = true;
                Boolean on = connected(d);
                if (on == null) return UNKNOWN;
                if (on) return UP;
            }
            return paired ? DOWN : UNKNOWN;
        } catch (SecurityException e) {
            return UNKNOWN;
        }
    }

    private static boolean isPumpPaired(Context c, BluetoothAdapter a) {
        try {
            for (BluetoothDevice d : a.getBondedDevices()) if (isPump(c, d)) return true;
        } catch (SecurityException ignored) { }
        return false;
    }

    /** BluetoothDevice.isConnected() is hidden but callable (the Home Assistant app uses it too). */
    private static Boolean connected(BluetoothDevice d) {
        try {
            Object r = d.getClass().getMethod("isConnected").invoke(d);
            return r instanceof Boolean ? (Boolean) r : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Updates the stored pump link state. A Bluetooth broadcast can pass what it saw
     * (UP or DOWN); otherwise Bluetooth is asked directly.
     */
    static void refreshPump(Context c, int seen) {
        SharedPreferences p = Store.p(c);
        int now = seen != UNKNOWN ? seen : pumpNow(c);
        if (now == UNKNOWN && seen == UNKNOWN) {
            // Can't ask Bluetooth; keep whatever the broadcasts last told us.
            if (!p.contains(Store.PUMP_STATE)) return;
            now = p.getInt(Store.PUMP_STATE, UNKNOWN);
        }
        int old = p.getInt(Store.PUMP_STATE, UNKNOWN);
        long t = System.currentTimeMillis();
        SharedPreferences.Editor e = p.edit().putInt(Store.PUMP_STATE, now);
        if (now == DOWN) {
            if (p.getLong(Store.PUMP_DOWN_SINCE, 0) == 0) e.putLong(Store.PUMP_DOWN_SINCE, t);
        } else {
            e.putLong(Store.PUMP_DOWN_SINCE, 0);
        }
        if (now == UP) e.putLong(Store.PUMP_UP_AT, t);
        e.apply();
        if (old != now) {
            long nudged = p.getLong(Store.LAST_NUDGE, 0);
            String after = now == UP && t - nudged < 3 * 60_000L
                    ? " (" + (t - nudged) / 1000 + " s after FX reopened CamAPS)" : "";
            Store.log(c, "Pump Bluetooth link " + new String[]{"unknown", "off", "on"}[now + 1] + after);
        }
    }

    /** Battery percent, or -1 if unknown. */
    static int battery(Context c) {
        Intent b = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return -1;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
    }

    static boolean charging(Context c) {
        Intent b = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** True between the configured night hours (default 23:00 to 08:00). */
    static boolean night(Context c, long t) {
        SharedPreferences p = Store.p(c);
        int from = p.getInt(Store.NIGHT_FROM, Store.DEF_NIGHT_FROM);
        int to = p.getInt(Store.NIGHT_TO, Store.DEF_NIGHT_TO);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(t);
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        return from <= to ? h >= from && h < to : h >= from || h < to;
    }
}
