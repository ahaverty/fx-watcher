package studio.alans.fxwatcher;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.SleepClassifyEvent;
import com.google.android.gms.location.SleepSegmentRequest;

import java.util.List;

/**
 * Google Play services sleep detection (optional, for bedtime media volume). Every ~10 min
 * Google sends a "probably asleep" confidence from motion and light; Bedtime arms when it is
 * high enough. Needs the Physical activity permission; registered only while the option is on.
 */
public class SleepReceiver extends BroadcastReceiver {

    private static PendingIntent pi(Context c) {
        // Mutable: Play services attaches the events to it.
        return PendingIntent.getBroadcast(c, 44, new Intent(c, SleepReceiver.class),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean permitted(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts or stops the updates to match the setting. Safe to call often. */
    @android.annotation.SuppressLint("MissingPermission")   // checked by permitted() first
    static void sync(Context c) {
        boolean want = Bedtime.on(c) && Store.p(c).getBoolean(Store.BED_USE_GOOGLE, false);
        try {
            if (want && permitted(c)) {
                ActivityRecognition.getClient(c).requestSleepSegmentUpdates(pi(c),
                        new SleepSegmentRequest(SleepSegmentRequest.CLASSIFY_EVENTS_ONLY));
            } else {
                ActivityRecognition.getClient(c).removeSleepSegmentUpdates(pi(c));
            }
        } catch (SecurityException | IllegalStateException e) {
            Store.log(c, "Google sleep detection unavailable: " + e.getMessage());
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!SleepClassifyEvent.hasEvents(i)) return;
        List<SleepClassifyEvent> events = SleepClassifyEvent.extractEvents(i);
        if (events.isEmpty()) return;
        int conf = events.get(events.size() - 1).getConfidence();
        Bedtime.onSleepConfidence(c, conf);
    }
}
