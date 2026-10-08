package studio.alans.fxwatcher;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notices the pump's Bluetooth link coming and going straight away, rather than at the next check. */
public class BtReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (!Phone.isPump(c, d)) return;
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) Phone.refreshPump(c, Phone.UP);
        else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) Phone.refreshPump(c, Phone.DOWN);
        else return;
        Monitor.evaluate(c);
    }
}
