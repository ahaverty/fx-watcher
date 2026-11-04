package studio.alans.fxwatcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Records sensor changes (SensorSwap) as Nightscout "Sensor Stop" / "Sensor Start" treatments and
 * Home Assistant {type: sensor_change, event: stop|start, at[, name]}.
 */
final class SensorWatch {

    private SensorWatch() { }

    /** A CamAPS screen read, or a notification's text. */
    static synchronized void texts(Context c, List<String> texts, long now) {
        SensorSwap w = load(c);
        String before = w.save();
        SensorSwap.Kind k = w.texts(texts, now);
        if (!before.equals(w.save())) save(c, w);
        record(c, w, k, "camaps-screen");
    }

    /** The stop confirmation was accepted on screen. */
    static synchronized void stopConfirmed(Context c, long now) {
        SensorSwap w = load(c);
        SensorSwap.Kind k = w.stopped(now);
        save(c, w);
        record(c, w, k, "camaps-screen");
    }

    /** True the first time CamAPS says "Sensor expired" for this sensor, false for its repeats. */
    static synchronized boolean firstExpired(Context c, long at) {
        SensorSwap w = load(c);
        boolean first = w.firstExpired(at);
        if (first) save(c, w);
        return first;
    }

    /**
     * Compares the phone's bonded DXCM devices with the ones known so far. The first run only
     * takes note of them; after that a new one is a new sensor. Called on every bond and every
     * regular check, so a bond the app was not running for still gets found (with a later time).
     */
    static synchronized void checkBonds(Context c) {
        if (!Phone.canSeeBluetooth(c)) return;
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null) return;
        try {
            if (!a.isEnabled()) return;
            SharedPreferences p = Store.p(c);
            boolean first = !p.contains(Store.SENSOR_BONDS);
            Set<String> known = new HashSet<>(p.getStringSet(Store.SENSOR_BONDS, new HashSet<>()));
            String added = null;
            boolean changed = false;
            for (BluetoothDevice d : a.getBondedDevices()) {
                String name = d.getName();
                if (name == null || !name.startsWith(SensorSwap.BOND_PREFIX)) continue;
                if (known.add(d.getAddress())) {
                    changed = true;
                    added = name;
                }
            }
            if (first || changed) p.edit().putStringSet(Store.SENSOR_BONDS, known).apply();
            if (first || added == null) return;
            SensorSwap w = load(c);
            SensorSwap.Kind k = w.started(System.currentTimeMillis(), added);
            save(c, w);
            record(c, w, k, "bluetooth");
        } catch (SecurityException ignored) {
            // no Bluetooth permission: the screen text still works
        }
    }

    private static void record(Context c, SensorSwap w, SensorSwap.Kind k, String source) {
        if (k == SensorSwap.Kind.NONE) return;
        boolean stop = k == SensorSwap.Kind.STOP;
        long at = stop ? w.stoppedAt : w.startedAt;
        if (k == SensorSwap.Kind.START) {
            // The old sensor's expiry no longer applies; CamAPS warns again before this one's.
            Store.p(c).edit().remove(Store.SENSOR_EXPIRY).apply();
        }
        if (!stop && w.expiresAt > 0) CamapsListener.sensorExpires(c, w.expiresAt);
        // The app log can be copied and shared, so the code goes to Nightscout only.
        if (k != SensorSwap.Kind.START_UPDATED) {
            Store.log(c, stop ? "Sensor stopped" : "New sensor started (" + source + ")");
            CamapsListener.sensorChange(c, stop, at, stop ? null : w.startName);
        }
        if (!Nightscout.configured(c)) return;
        try {
            JSONObject t = Nightscout.treatment(stop ? "Sensor Stop" : "Sensor Start", at, source);
            t.put("notes", stop ? "Sensor stopped" : w.startNotes());
            if (!stop && w.startCode != null) t.put("sensor_code", w.startCode);
            if (!stop && w.startName != null) t.put("sensor_name", w.startName);
            if (!stop && w.startSerial != null) t.put("sensor_serial", w.startSerial);
            if (!stop && w.activatedAt > 0) t.put("sensor_activated", w.activatedAt);
            if (!stop && w.expiresAt > 0) t.put("sensor_expires", w.expiresAt);
            Nightscout.postTreatment(c, t);
        } catch (Exception ignored) {
            // nothing to send
        }
    }

    private static SensorSwap load(Context c) {
        return SensorSwap.load(Store.p(c).getString(Store.SENSOR_SWAP, ""));
    }

    private static void save(Context c, SensorSwap w) {
        Store.p(c).edit().putString(Store.SENSOR_SWAP, w.save()).apply();
    }
}
