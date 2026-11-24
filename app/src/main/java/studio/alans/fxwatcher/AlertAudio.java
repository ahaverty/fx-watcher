package studio.alans.fxwatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Debug record of how a CamAPS alert makes its sound (Part 1 of the calm setup). Read only: it
 * changes no volume and touches nothing of CamAPS.
 *
 * Whenever CamAPS posts a one-off notification (or its status card shows a very low glucose) it
 * writes, to its own log (Store.AUDIOLOG, not the 1500-line main log):
 *   - the notification: channel (importance, sound, vibration, bypass DND), flags, category,
 *     contentIntent / deleteIntent / fullScreenIntent, and the titles of any actions;
 *   - the active audio players at 0, 0.4, 1.2, 3 and 7 s: usage and content type of each (the
 *     package is not available to a normal app) plus every stream's volume;
 *   - every volume change in the first 15 s with its time in ms.
 * A real Urgent low then shows whether CamAPS lifts the media volume (and to what), whether a
 * player with alarm usage exists, and whether the notification has anything to acknowledge.
 */
final class AlertAudio {

    private AlertAudio() { }

    private static final long[] SAMPLES_MS = {0, 400, 1200, 3000, 7000};
    private static final long WINDOW_MS = 15_000L;
    private static final long MIN_GAP_MS = 20_000L;

    private static long lastCapture;
    private static BroadcastReceiver volumes;

    // ---- pure formatting (unit tested) ----

    static String usage(int u) {
        switch (u) {
            case AudioAttributes.USAGE_UNKNOWN: return "UNKNOWN";
            case AudioAttributes.USAGE_MEDIA: return "MEDIA";
            case AudioAttributes.USAGE_VOICE_COMMUNICATION: return "VOICE_COMM";
            case AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING: return "VOICE_SIGNAL";
            case AudioAttributes.USAGE_ALARM: return "ALARM";
            case AudioAttributes.USAGE_NOTIFICATION: return "NOTIFICATION";
            case AudioAttributes.USAGE_NOTIFICATION_RINGTONE: return "RINGTONE";
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST: return "NOTIF_COMM_REQ";
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT: return "NOTIF_COMM_INSTANT";
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED: return "NOTIF_COMM_DELAYED";
            case AudioAttributes.USAGE_NOTIFICATION_EVENT: return "NOTIF_EVENT";
            case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY: return "ACCESSIBILITY";
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE: return "NAVIGATION";
            case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION: return "SONIFICATION";
            case AudioAttributes.USAGE_GAME: return "GAME";
            case AudioAttributes.USAGE_ASSISTANT: return "ASSISTANT";
            default: return "usage" + u;
        }
    }

    static String content(int t) {
        switch (t) {
            case AudioAttributes.CONTENT_TYPE_UNKNOWN: return "UNKNOWN";
            case AudioAttributes.CONTENT_TYPE_SPEECH: return "SPEECH";
            case AudioAttributes.CONTENT_TYPE_MUSIC: return "MUSIC";
            case AudioAttributes.CONTENT_TYPE_MOVIE: return "MOVIE";
            case AudioAttributes.CONTENT_TYPE_SONIFICATION: return "SONIFICATION";
            default: return "content" + t;
        }
    }

    /** One line about a notification. Pure. */
    static String describe(String pkg, int id, String channelId, int importance, boolean bypassDnd,
                           boolean channelSound, boolean channelVibrates, int flags, String category,
                           String title, String[] actions, boolean contentIntent, boolean deleteIntent,
                           boolean fullScreen, boolean ongoing) {
        StringBuilder s = new StringBuilder("Notification ").append(pkg).append(" id ").append(id)
                .append(ongoing ? " (ongoing)" : "")
                .append(", channel ").append(channelId)
                .append(importance >= 0 ? " importance " + importance : "")
                .append(importance >= 0 ? (bypassDnd ? ", bypasses DND" : ", no DND bypass") : "")
                .append(importance >= 0 ? ", channel sound " + (channelSound ? "yes" : "no")
                        + ", vibrate " + (channelVibrates ? "yes" : "no") : "")
                .append(", flags 0x").append(Integer.toHexString(flags))
                .append(category == null ? "" : ", category " + category)
                .append(title == null || title.isEmpty() ? "" : ", title \"" + title + "\"")
                .append(", contentIntent ").append(contentIntent ? "yes" : "no")
                .append(", deleteIntent ").append(deleteIntent ? "yes" : "no")
                .append(", fullScreenIntent ").append(fullScreen ? "yes" : "no");
        if (actions == null || actions.length == 0) {
            s.append(", actions none");
        } else {
            s.append(", actions ").append(actions.length).append(" [");
            for (int i = 0; i < actions.length; i++) s.append(i > 0 ? " | " : "").append(actions[i]);
            s.append(']');
        }
        return s.toString();
    }

    /** One audio sample: players then volumes. Pure. */
    static String sampleLine(long ms, List<String> players, int music, int musicMax, int alarm, int alarmMax,
                             int notif, int notifMax, int ring, int ringMax, String ringer) {
        StringBuilder s = new StringBuilder("audio +").append(ms).append("ms players ").append(players.size());
        for (String p : players) s.append(" [").append(p).append(']');
        s.append(" | music ").append(music).append('/').append(musicMax)
                .append(" alarm ").append(alarm).append('/').append(alarmMax)
                .append(" notif ").append(notif).append('/').append(notifMax)
                .append(" ring ").append(ring).append('/').append(ringMax)
                .append(" ringer ").append(ringer);
        return s.toString();
    }

    static String streamName(int stream) {
        switch (stream) {
            case AudioManager.STREAM_MUSIC: return "music";
            case AudioManager.STREAM_ALARM: return "alarm";
            case AudioManager.STREAM_NOTIFICATION: return "notification";
            case AudioManager.STREAM_RING: return "ring";
            case AudioManager.STREAM_SYSTEM: return "system";
            case AudioManager.STREAM_VOICE_CALL: return "voice";
            default: return "stream" + stream;
        }
    }

    /** Whether a status-card glucose is low enough that an Urgent low may be sounding (CamAPS default 3.1). */
    static boolean looksUrgent(double mmol) {
        return mmol > 0 && mmol < 3.3;
    }

    // ---- Android side ----

    /** A one-off CamAPS notification was posted. */
    static void onNotification(Context c, StatusBarNotification sbn, NotificationChannel ch) {
        Notification n = sbn.getNotification();
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        String[] actions = null;
        if (n.actions != null) {
            actions = new String[n.actions.length];
            for (int i = 0; i < actions.length; i++) {
                actions[i] = String.valueOf(n.actions[i].title);
            }
        }
        boolean sound = ch != null && ch.getSound() != null;
        add(c, describe(sbn.getPackageName(), sbn.getId(), n.getChannelId(),
                ch == null ? -1 : ch.getImportance(), ch != null && ch.canBypassDnd(), sound,
                ch != null && ch.shouldVibrate(), n.flags, n.category, t == null ? "" : t.toString(),
                actions, n.contentIntent != null, n.deleteIntent != null, n.fullScreenIntent != null,
                sbn.isOngoing()));
        capture(c, "notification id " + sbn.getId());
    }

    /** The status card shows a glucose that could be an Urgent low: record how it sounds. */
    static void onLowGlucose(Context c, double mmol) {
        if (looksUrgent(mmol)) capture(c, "status card glucose " + mmol);
    }

    private static synchronized void add(Context c, String msg) {
        android.content.SharedPreferences p = Store.p(c);
        String old = p.getString(Store.AUDIOLOG, "");
        String line = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.UK).format(new Date()) + "  " + msg;
        StringBuilder sb = new StringBuilder(line);
        int lines = 1;
        for (String l : old.split("\n")) {
            if (l.isEmpty()) continue;
            if (++lines > 400) break;
            sb.append('\n').append(l);
        }
        p.edit().putString(Store.AUDIOLOG, sb.toString()).apply();
    }

    /** Starts the 15 s recording: samples of the players and volumes, and every volume change. */
    static synchronized void capture(Context c, String why) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCapture < MIN_GAP_MS) return;
        lastCapture = now;
        Context app = c.getApplicationContext();
        add(app, "Recording audio: " + why);
        Handler h = new Handler(Looper.getMainLooper());
        for (long ms : SAMPLES_MS) h.postDelayed(() -> sample(app, ms), ms);
        watchVolumes(app, now);
        h.postDelayed(() -> stopWatching(app), WINDOW_MS);
    }

    private static void sample(Context c, long ms) {
        try {
            AudioManager a = c.getSystemService(AudioManager.class);
            java.util.ArrayList<String> players = new java.util.ArrayList<>();
            for (AudioPlaybackConfiguration pc : a.getActivePlaybackConfigurations()) {
                AudioAttributes at = pc.getAudioAttributes();
                players.add("usage " + usage(at.getUsage()) + ", content " + content(at.getContentType())
                        + ", flags 0x" + Integer.toHexString(at.getFlags()));
            }
            String ringer = a.getRingerMode() == AudioManager.RINGER_MODE_NORMAL ? "normal"
                    : a.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "silent";
            add(c, sampleLine(ms, players,
                    a.getStreamVolume(AudioManager.STREAM_MUSIC), a.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    a.getStreamVolume(AudioManager.STREAM_ALARM), a.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    a.getStreamVolume(AudioManager.STREAM_NOTIFICATION), a.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION),
                    a.getStreamVolume(AudioManager.STREAM_RING), a.getStreamMaxVolume(AudioManager.STREAM_RING),
                    ringer));
        } catch (Exception e) {
            add(c, "audio sample failed: " + e.getClass().getSimpleName());
        }
    }

    private static void watchVolumes(Context c, long start) {
        stopWatching(c);
        volumes = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                int stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                int value = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                int prev = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
                add(ctx, "volume " + streamName(stream) + " " + prev + " -> " + value
                        + " at +" + (SystemClock.elapsedRealtime() - start) + "ms");
            }
        };
        try {
            c.registerReceiver(volumes, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                    Context.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            volumes = null;
        }
    }

    private static synchronized void stopWatching(Context c) {
        if (volumes == null) return;
        try {
            c.unregisterReceiver(volumes);
        } catch (Exception ignored) { }
        volumes = null;
    }
}
