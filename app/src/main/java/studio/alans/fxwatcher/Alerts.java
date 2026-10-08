package studio.alans.fxwatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Notifications and the alarm sound.
 *
 * Problem notifications use MessagingStyle with reply and mark-as-read actions: that is what
 * Android Auto requires before it will show (and read out) a notification, and smartwatches mirror it
 * like a message. The alarm itself plays on the alarm stream, so media volume and silent mode
 * don't matter, and it bypasses Do Not Disturb as long as DND allows alarms (the default).
 */
final class Alerts {

    private Alerts() { }

    static final String CH_STATUS = "status";
    static final String CH_WARN = "warning";
    static final String CH_ALARM = "alarm";
    static final String CH_INFO = "info";

    static final int ID_STATUS = 1;
    static final int ID_PROBLEM = 2;
    static final int ID_RESOLVED = 3;

    static final String KEY_REPLY = "reply";

    private static MediaPlayer player;
    private static PowerManager.WakeLock wake;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel s = new NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_MIN);
        s.setDescription("Quiet ongoing notification with the current CamAPS state");
        s.setShowBadge(false);

        NotificationChannel w = new NotificationChannel(CH_WARN, "Warnings", NotificationManager.IMPORTANCE_HIGH);
        w.setDescription("Early warning that something is wrong. Shows on your watch and in the car.");
        w.enableVibration(true);

        NotificationChannel a = new NotificationChannel(CH_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Signal loss alarms. The sound is played by FX Watcher on the alarm volume.");
        a.setSound(null, null);
        a.enableVibration(true);
        a.setBypassDnd(true);
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel i = new NotificationChannel(CH_INFO, "Back to normal", NotificationManager.IMPORTANCE_DEFAULT);
        i.setDescription("Tells you when a problem has cleared");

        nm.createNotificationChannel(s);
        nm.createNotificationChannel(w);
        nm.createNotificationChannel(a);
        nm.createNotificationChannel(i);
    }

    private static PendingIntent action(Context c, String act, int req, double hours) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(act).putExtra("hours", hours);
        // A reply action must be mutable so the system can attach the typed or spoken text.
        int mut = ActionReceiver.REPLY.equals(act) ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, req, i, mut | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent openApp(Context c) {
        return PendingIntent.getActivity(c, 10, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent alarmScreen(Context c) {
        Intent i = new Intent(c, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 11, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The warning or alarm notification. */
    static void problem(Context c, Monitor.Problem pr, int level, boolean alert) {
        channels(c);
        boolean alarm = level == Monitor.ALARM;
        long now = System.currentTimeMillis();

        Person me = new Person.Builder().setName("You").build();
        Person bot = new Person.Builder().setName("FX Watcher")
                .setIcon(Icon.createWithResource(c, R.drawable.ic_launcher)).build();
        String head = level == Monitor.ACKED ? pr.title + " (silenced)" : pr.title;
        Notification.MessagingStyle style = new Notification.MessagingStyle(me)
                .setConversationTitle("FX Watcher")
                .addMessage(head + ". " + pr.detail, now, bot);

        // Android Auto needs a reply action and a mark-as-read action (both background broadcasts).
        RemoteInput ri = new RemoteInput.Builder(KEY_REPLY)
                .setLabel("Reply 'snooze 2' or 'ok'").build();
        Notification.Action reply = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_stat), "Reply",
                action(c, ActionReceiver.REPLY, 20, 0))
                .addRemoteInput(ri)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .build();
        Notification.Action silence = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_stat), "Silence 15m",
                action(c, ActionReceiver.SILENCE, 21, 0))
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .build();
        Notification.Action snooze = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_stat), "Snooze 2h",
                action(c, ActionReceiver.SNOOZE, 22, 2)).build();
        Notification.Action open = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_stat), "Open CamAPS",
                action(c, ActionReceiver.OPEN_CAMAPS, 23, 0)).build();

        Notification.Builder b = new Notification.Builder(c, alarm ? CH_ALARM : CH_WARN)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFFC62828)
                .setStyle(style)
                .setContentTitle(head)
                .setContentText(pr.detail)
                .setCategory(alarm ? Notification.CATEGORY_ALARM : Notification.CATEGORY_MESSAGE)
                .setContentIntent(alarm ? alarmScreen(c) : openApp(c))
                .setOnlyAlertOnce(!alert && !alarm)
                .setOngoing(level != Monitor.WARN)
                .setShowWhen(true)
                .setWhen(pr.since)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(alarm || level == Monitor.ACKED ? silence : reply)
                .addAction(pr.snoozable ? snooze : open)
                .addAction(alarm || level == Monitor.ACKED ? reply : silence);
        if (alarm) b.setFullScreenIntent(alarmScreen(c), true);
        if (level == Monitor.ACKED) b.setOnlyAlertOnce(true);
        c.getSystemService(NotificationManager.class).notify(ID_PROBLEM, b.build());
    }

    /** Full alarm: notification, full-screen alert, sound and vibration. */
    static void alarm(Context c, Monitor.Problem pr, boolean fresh) {
        problem(c, pr, Monitor.ALARM, true);
        startSound(c);
        if (fresh) {
            try {
                c.startActivity(new Intent(c, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // Background start blocked; the full-screen intent covers it.
            }
        }
    }

    static void cancelProblem(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID_PROBLEM);
        AlarmActivity.closeIfOpen();
    }

    static void resolved(Context c, String title) {
        channels(c);
        Notification n = new Notification.Builder(c, CH_INFO)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFF2E7D32)
                .setContentTitle("Back to normal")
                .setContentText((title.isEmpty() ? "The problem" : title) + " has cleared.")
                .setContentIntent(openApp(c))
                .setAutoCancel(true)
                .setTimeoutAfter(30 * 60_000L)
                .build();
        c.getSystemService(NotificationManager.class).notify(ID_RESOLVED, n);
    }

    /** Quiet ongoing status line: what CamAPS is showing and whether anything is snoozed. */
    static void status(Context c, Monitor.Problem pr, int level, boolean snoozed) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        SharedPreferences p = Store.p(c);
        if (!p.getBoolean(Store.STATUS_NOTIF, true)) {
            nm.cancel(ID_STATUS);
            return;
        }
        channels(c);
        long now = System.currentTimeMillis();
        // Kept free of glucose, times and minute counts so it only changes when the state does:
        // Watches re-announce the notification every time its text changes.
        String title;
        if (pr == null) title = p.getBoolean(Store.PRESENT, false) ? "CamAPS OK" : summary(c, now);
        else if ("PUMP".equals(pr.code)) title = (now < pr.warnAt ? "Pump link quiet since " : "Pump link off since ")
                + Store.hm(pr.since);
        else title = pr.title;
        long snooze = p.getLong(Store.SNOOZE_UNTIL, 0);
        String text = snooze > now ? "Snoozed until " + Store.hm(snooze) : "FX Watcher is watching";
        String key = title + "|" + text;
        if (key.equals(lastStatus) && isShowing(nm, ID_STATUS)) return;
        lastStatus = key;
        Notification.Builder b = new Notification.Builder(c, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(c))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (snooze > now) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat),
                    "Cancel snooze", action(c, ActionReceiver.UNSNOOZE, 30, 0)).build());
        } else {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat),
                    "Snooze 1h", action(c, ActionReceiver.SNOOZE, 31, 1)).build());
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat),
                    "Snooze 3h", action(c, ActionReceiver.SNOOZE, 32, 3)).build());
        }
        nm.notify(ID_STATUS, b.build());
    }

    private static String lastStatus;

    private static boolean isShowing(NotificationManager nm, int id) {
        try {
            for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications()) {
                if (s.getId() == id) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** One line: "Auto mode On · 12.4 · 18:48" or what's wrong. */
    static String summary(Context c, long now) {
        SharedPreferences p = Store.p(c);
        long seen = p.getLong(Store.LAST_SEEN, 0);
        if (seen == 0) return "Waiting for CamAPS FX";
        if (!p.getBoolean(Store.PRESENT, false)) return "CamAPS FX not running";
        String g = p.getString(Store.GLUCOSE, "");
        return "Auto mode " + p.getString(Store.STATUS, "?")
                + (g.isEmpty() ? " · no glucose" : " · " + g)
                + " · " + Store.hm(seen);
    }

    // ---- Sound ----

    static synchronized void startSound(Context c) {
        if (player != null) return;
        Context app = c.getApplicationContext();
        AudioManager am = app.getSystemService(AudioManager.class);
        SharedPreferences p = Store.p(app);
        if (p.getBoolean(Store.MAX_VOLUME, true)) {
            int cur = am.getStreamVolume(AudioManager.STREAM_ALARM);
            if (!p.contains(Store.SAVED_VOLUME)) p.edit().putInt(Store.SAVED_VOLUME, cur).apply();
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            } catch (Exception ignored) { }
        }

        PowerManager pm = app.getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FXWatcher:alarm");
        wake.acquire(20 * 60_000L);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        try {
            MediaPlayer mp;
            try {
                mp = prepare(app, attrs, soundUri(app));
            } catch (Exception chosen) {
                // The picked tone may have been deleted; fall back to the system alarm sound.
                Store.log(app, "Chosen alarm tone failed, using default: " + chosen);
                mp = prepare(app, attrs, defaultSound());
            }
            final MediaPlayer playing = mp;
            mp.setVolume(0.4f, 0.4f);
            mp.start();
            player = mp;
            // Ramp up to full over 30 seconds.
            for (int i = 1; i <= 6; i++) {
                final float v = 0.4f + 0.1f * i;
                main.postDelayed(() -> {
                    synchronized (Alerts.class) {
                        if (player == playing) playing.setVolume(v, v);
                    }
                }, i * 5000L);
            }
        } catch (Exception e) {
            Store.log(app, "Alarm sound failed: " + e);
        }

        Vibrator vib = app.getSystemService(Vibrator.class);
        if (vib != null) {
            long[] pattern = {0, 800, 400, 800, 400, 1500, 1200};
            vib.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs);
        }
    }

    private static MediaPlayer prepare(Context c, AudioAttributes attrs, Uri uri) throws Exception {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(attrs);
            mp.setDataSource(c, uri);
            mp.setLooping(true);
            mp.prepare();
            return mp;
        } catch (Exception e) {
            mp.release();
            throw e;
        }
    }

    static Uri defaultSound() {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return uri != null ? uri : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    }

    /** The tone picked in the app, or the system alarm sound. */
    static Uri soundUri(Context c) {
        String s = Store.p(c).getString(Store.SOUND, "");
        return s.isEmpty() ? defaultSound() : Uri.parse(s);
    }

    static synchronized void stopSound(Context c) {
        Context app = c.getApplicationContext();
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
            Vibrator vib = app.getSystemService(Vibrator.class);
            if (vib != null) vib.cancel();
        }
        SharedPreferences p = Store.p(app);
        if (p.contains(Store.SAVED_VOLUME)) {
            try {
                app.getSystemService(AudioManager.class)
                        .setStreamVolume(AudioManager.STREAM_ALARM, p.getInt(Store.SAVED_VOLUME, 5), 0);
            } catch (Exception ignored) { }
            p.edit().remove(Store.SAVED_VOLUME).apply();
        }
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
    }

    static boolean sounding() {
        return player != null;
    }
}
