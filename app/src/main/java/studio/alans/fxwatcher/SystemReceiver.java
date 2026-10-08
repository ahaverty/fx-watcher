package studio.alans.fxwatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot or an app update, restart the watchdog. */
public class SystemReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Store.log(c, Intent.ACTION_BOOT_COMPLETED.equals(a) ? "Phone restarted" : "App updated");
            Monitor.check(c);
        }
    }
}
