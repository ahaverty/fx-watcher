package studio.alans.fxwatcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Turns the screen on for a few seconds over the lock screen, then goes away.
 *
 * The pump's Bluetooth link tends to come back when the phone wakes (Android pauses some
 * Bluetooth scanning while the screen is off), so after a long quiet spell FX Watcher wakes
 * the screen briefly to give CamAPS a chance to reconnect, before it resorts to alarming.
 */
public class WakeActivity extends Activity {

    static void nudge(Context c) {
        try {
            c.startActivity(new Intent(c, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (Exception e) {
            Store.log(c, "Could not wake the screen: " + e.getClass().getSimpleName());
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        View v = new View(this);
        v.setBackgroundColor(Color.BLACK);
        setContentView(v);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 5000L);
    }
}
