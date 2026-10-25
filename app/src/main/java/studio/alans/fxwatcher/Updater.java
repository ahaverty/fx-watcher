package studio.alans.fxwatcher;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Self-update from the latest GitHub release, which the build workflow publishes with version.json
 * and fx-watcher.apk on every v* tag. Checked at most every 6 hours (app opened, watchdog); a newer
 * build posts a quiet notification, and installing goes through Android's own installer (one
 * confirmation, the signature must match).
 */
final class Updater {

    private Updater() { }

    static final String RELEASES = "https://github.com/ahaverty/fx-watcher/releases";
    private static final String BASE = RELEASES + "/latest/download";
    static final String ACTION_INSTALL = "studio.alans.fxwatcher.INSTALL_UPDATE";
    private static final String CH_UPDATE = "update";
    private static final int ID_UPDATE = 6;

    private static final String CHECKED = "update_checked";
    private static final String CODE = "update_code";
    private static final String NAME = "update_name";
    private static final String NOTES = "update_notes";
    private static final String NOTIFIED = "update_notified";

    private static final ExecutorService net = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static long installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /** The newer release found by the last check, or null: {versionName, notes}. */
    static String[] available(Context c) {
        SharedPreferences p = Store.p(c);
        if (p.getLong(CODE, 0) <= installed(c)) return null;
        return new String[]{p.getString(NAME, ""), p.getString(NOTES, "")};
    }

    /** force: the button in the app (no 6 h wait); done gets a line for the user, on the main thread. */
    static void check(Context c, boolean force, Consumer<String> done) {
        Context app = c.getApplicationContext();
        SharedPreferences p = Store.p(app);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong(CHECKED, 0) < 6 * 3_600_000L) return;
        p.edit().putLong(CHECKED, now).apply();
        net.execute(() -> {
            String msg;
            try {
                JSONObject j = new JSONObject(new String(get(BASE + "/version.json"), "UTF-8"));
                long code = j.getLong("versionCode");
                p.edit().putLong(CODE, code).putString(NAME, j.optString("versionName"))
                        .putString(NOTES, j.optString("notes")).apply();
                if (code > installed(app)) {
                    msg = "v" + j.optString("versionName") + " is ready to install";
                    if (p.getLong(NOTIFIED, 0) != code) {
                        p.edit().putLong(NOTIFIED, code).apply();
                        notify(app, j.optString("versionName"), j.optString("notes"));
                    }
                } else {
                    msg = "You have the latest version";
                }
            } catch (Exception e) {
                msg = "Couldn't check for updates: " + e.getMessage();
                Store.log(app, "Update check failed: " + e);
            }
            if (done != null) {
                String m = msg;
                main.post(() -> done.accept(m));
            }
        });
    }

    private static void notify(Context c, String name, String notes) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH_UPDATE, "Updates", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("A new version of FX Watcher is ready");
        nm.createNotificationChannel(ch);
        PendingIntent tap = PendingIntent.getActivity(c, 40, new Intent(c, MainActivity.class)
                        .setAction(ACTION_INSTALL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String body = notes.isEmpty() ? "Tap to install." : notes;
        nm.notify(ID_UPDATE, new Notification.Builder(c, CH_UPDATE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("FX Watcher v" + name + " is ready")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body + "\nTap to install."))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build());
        Store.log(c, "Update available: v" + name);
    }

    /** Downloads the APK and hands it to Android's installer. Progress and errors go to say. */
    static void install(Activity a, Consumer<String> say) {
        Context app = a.getApplicationContext();
        say.accept("Downloading the update…");
        net.execute(() -> {
            try {
                byte[] apk = get(BASE + "/fx-watcher.apk");
                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                int id = installer.createSession(params);
                try (PackageInstaller.Session s = installer.openSession(id)) {
                    try (OutputStream out = s.openWrite("fx-watcher.apk", 0, apk.length)) {
                        out.write(apk);
                        s.fsync(out);
                    }
                    PendingIntent status = PendingIntent.getBroadcast(app, 41,
                            new Intent(app, InstallStatus.class),
                            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    s.commit(status.getIntentSender());
                }
                app.getSystemService(NotificationManager.class).cancel(ID_UPDATE);
                Store.log(app, "Update downloaded, installing");
                main.post(() -> say.accept("Installing… confirm on the next screen."));
            } catch (Exception e) {
                Store.log(app, "Update failed: " + e);
                main.post(() -> say.accept("Update failed: " + e.getMessage()));
            }
        });
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("GitHub said " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Android asks for the user's OK through this; anything else is just logged. */
    public static class InstallStatus extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @SuppressWarnings("deprecation")
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                Store.log(c, "Update install status " + st + ": "
                        + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }
}
