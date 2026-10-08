# FX Watcher

A phone-local backup alarm for CamAPS FX signal loss. Not affiliated with CamDiab; works with CamAPS FX.

It reads the CamAPS FX ongoing notification and alarms when:

- auto mode is not **On**, or no glucose is shown, for 20 min (warning at 10 min)
- CamAPS FX is no longer running (notification gone; alarm after 5 min)
- FX Watcher itself can't see CamAPS for 15 min (lost notification access)
- urgent low at or below 3.0 mmol/L (not snoozable)

The alarm plays on the **alarm** stream at full volume (media volume and silent mode don't matter),
vibrates, and shows full screen over the lock screen. It re-buzzes every 2 minutes until silenced, and comes back
15 min after silencing if the problem is still there. Alerts are MessagingStyle notifications so they
mirror to Garmin watches and show in Android Auto (reply "snooze 2" or "ok"). Snooze 1-8 h for activities.
When something's wrong it reopens CamAPS FX (needs "Display over other apps"). It never stops or reconfigures CamAPS.

Optionally forwards each reading to a Home Assistant webhook (same payload as the old CamAPS Bridge).

## Build and install

Push, then `gh run download -n fx-watcher-apk`, then `adb install -r app-release.apk`.

One-time grants over adb (the app's Setup section has buttons for each too):

    adb shell cmd notification allow_listener studio.alans.fxwatcher/.CamapsListener
    adb shell pm grant studio.alans.fxwatcher android.permission.POST_NOTIFICATIONS
    adb shell appops set studio.alans.fxwatcher SYSTEM_ALERT_WINDOW allow
    adb shell appops set studio.alans.fxwatcher USE_FULL_SCREEN_INTENT allow
    adb shell dumpsys deviceidle whitelist +studio.alans.fxwatcher

Test hooks (adb only) are documented in `DebugReceiver.java`.
