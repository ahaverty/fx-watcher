package studio.alans.fxwatcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pushes the calm state to the Calm watch face the moment it changes, through Garmin Connect's
 * Connect IQ phone SDK (the same way xDrip+ feeds its watch faces). The face keeps its own 5 min
 * poll of the local state.json as a fallback, so a missed push only costs a little time.
 *
 * Only the Calm face is addressed, only on Garmin devices that are connected right now, and what
 * is sent is the same words and colour as the phone shows plus the glucose number the face never
 * draws. Needs Garmin Connect on the phone. Turn it off in Settings (Calm view).
 */
final class GarminPush {

    private GarminPush() { }

    /** The Calm watch face's app id (its manifest id). */
    static final String FACE_APP_ID = "ee691ea38b34412999c7870f00ac265b";

    private static final long MIN_GAP_MS = 20_000L, RESEND_MS = 4 * 60_000L;

    private static ConnectIQ iq;
    private static boolean ready;
    private static boolean starting;
    private static String lastKey = "";
    private static long lastSentAt;
    private static Handler mainHandler;

    private static synchronized Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    // ---- pure (unit tested) ----

    /** What is worth re-sending: a change in anything but the age. */
    static String key(Map<String, Object> m) {
        return m.get("state") + "|" + m.get("word") + "|" + m.get("colour") + "|" + m.get("text") + "|"
                + m.get("glucose") + "|" + m.get("trend");
    }

    /** Whether to send now: something changed (and not twice within 20 s), or 4 min have passed. */
    static boolean due(String key, String lastKey, long now, long lastSentAt) {
        if (now - lastSentAt < MIN_GAP_MS) return false;
        return !key.equals(lastKey) || now - lastSentAt >= RESEND_MS;
    }

    // ---- Android ----

    static boolean enabled(Context c) {
        return Store.p(c).getBoolean(Store.GARMIN_PUSH, true);
    }

    /** Connects to Garmin Connect. Safe to call again. */
    static synchronized void sync(Context c) {
        if (!enabled(c)) {
            stop(c);
            return;
        }
        if (iq != null || starting) return;
        final Context app = c.getApplicationContext();
        starting = true;
        main().post(() -> {
            try {
                ConnectIQ q = ConnectIQ.getInstance(app, ConnectIQ.IQConnectType.WIRELESS);
                q.initialize(app, false, new ConnectIQ.ConnectIQListener() {
                    @Override
                    public void onSdkReady() {
                        synchronized (GarminPush.class) {
                            iq = q;
                            ready = true;
                            starting = false;
                        }
                        Store.log(app, "Garmin push ready");
                        Calm.refresh(app);
                    }

                    @Override
                    public void onInitializeError(ConnectIQ.IQSdkErrorStatus status) {
                        synchronized (GarminPush.class) {
                            iq = null;
                            ready = false;
                            starting = false;
                        }
                        Store.log(app, "Garmin push not available: " + status);
                    }

                    @Override
                    public void onSdkShutDown() {
                        synchronized (GarminPush.class) {
                            ready = false;
                            iq = null;
                        }
                    }
                });
            } catch (Exception e) {
                synchronized (GarminPush.class) {
                    starting = false;
                }
                Store.log(app, "Garmin push could not start: " + e.getClass().getSimpleName());
            }
        });
    }

    static synchronized void stop(Context c) {
        if (iq == null) return;
        try {
            iq.shutdown(c.getApplicationContext());
        } catch (Exception ignored) { }
        iq = null;
        ready = false;
    }

    /** Called with every recompute of the calm state. Sends only when due. */
    static void push(Context c, Map<String, Object> message) {
        final ConnectIQ q;
        synchronized (GarminPush.class) {
            if (!ready || iq == null || !enabled(c)) return;
            q = iq;
        }
        String key = key(message);
        long now = SystemClock.elapsedRealtime();
        if (!due(key, lastKey, now, lastSentAt)) return;
        lastKey = key;
        lastSentAt = now;
        final Context app = c.getApplicationContext();
        main().post(() -> {
            try {
                List<IQDevice> devices = q.getConnectedDevices();
                if (devices == null) return;
                final IQApp face = new IQApp(FACE_APP_ID);
                for (IQDevice d : devices) {
                    if (d.getStatus() != IQDevice.IQDeviceStatus.CONNECTED) continue;
                    q.sendMessage(d, face, new HashMap<>(message), (device, a, status) -> {
                        if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                            Store.log(app, "Garmin push to " + device.getFriendlyName() + ": " + status);
                        }
                    });
                }
            } catch (Exception e) {
                Store.log(app, "Garmin push failed: " + e.getClass().getSimpleName());
            }
        });
    }
}
