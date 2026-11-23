# Calm setup notes

Short notes for turning the calm setup on. Nothing here needs more than normal app permissions.

## 1. FX Watcher (phone)

1. Install the build (it asks first, as always) and open the app.
2. **Early low warning**: pick a level. Balanced is the default. The table of what each level costs is in
   `docs/early-low-backtest.md`.
3. **Calm view**: leave the calm status notification on. Add the **FX Watcher** widget to the home screen
   (long press, Widgets, FX Watcher): a coloured dot and one word, tap opens CamAPS.
4. **Garmin Connect**: allow notifications from FX Watcher, and leave the watch's own Do Not Disturb off.
   The Low coming notification is high importance with no sound, so the watch gives one strong buzz.
5. **Treating mode** is armed by you, from the app, from the "I'm treating" button on the Low coming
   notification, or from Home Assistant. In the app's text, and in the README, it says exactly what still
   rings: pump alarms always, and CamAPS Urgent low if the volume trick loses its race.

## 2. States and colours

| State | Colour | Word | When |
|---|---|---|---|
| steady | teal | Steady | nothing needs you |
| handled | amber | Handled | above 10, and Boost is on or a bolus or correction was delivered in the last 2 h and glucose is flat or falling |
| check | coral | Check | "Did you bolus?" (rising, no bolus), "Check the site?" (above 12 for 90+ min after a correction), "CamAPS isn't looping" (auto mode not On for 15+ min) |
| low_soon | purple | Low soon | the early warning is active, or glucose is under 3.9 |
| treating | purple | Treating | Treating mode is on |

The status notification is silent and low importance. It is not shown while steady, and it never shows a
glucose number. Its text changes only when the state changes, so a watch is not alerted again every few
minutes. The widget and the watch face show a grey dot and "No data" when the last reading is over 15
minutes old.

## 3. xDrip+: what to turn off, and what to leave alone

xDrip+ stays as the collector and the Nightscout uploader. Do **not** touch: Hardware Data Source,
Cloud Upload (Nightscout Sync), Inter-app settings (the closed-source Garmin face reads those until the new
face replaces it), Bluetooth or Companion settings, "Collector in foreground".

**The number in the status bar and the graph notification.** Both live in xDrip+'s ongoing notification
(its titles in the app are "Number Icon in Notification Area", "Show Small Number Icon", "Use Large Number
Icon", "Compact Persistent Notification"). The graph notification is also what keeps Android from killing
the collector ("Collector in foreground"), so do not switch it off inside xDrip+. Hide it in Android instead,
which leaves the service running:

1. Long press the xDrip+ app icon, App info, Notifications.
2. Switch off the **Ongoing notification** channel (that is the name xDrip+'s source gives it; I could not confirm it on the phone from here. If it is not listed, open xDrip+ Settings and make sure
   "Notification Channels" is on, then look again). Leave the other channels as they are for now.
3. If a number icon still shows, in xDrip+ turn off **Show Small Number Icon** and **Use Large Number Icon**.
   Those are display only and do not affect collection or upload. They are under xDrip+ settings; use the
   search icon at the top of Settings and type "number icon", because the exact menu differs by version.
4. Also off, in the same search: "Number wall on lock screen" and "High Priority Notifications".

**Glucose alerts.** CamAPS already alarms, so xDrip+'s own are duplicates:

1. Settings, Alarms and Alerts, Glucose Alerts List: switch off every alert (urgent low, low, high, urgent
   high), or delete them.
2. Settings, Alarms and Alerts, Extra Alerts, **Persistent High Alert**: off.
3. Settings, Alarms and Alerts, Other Alerts: turn off "Bg falling fast", "Bg rising fast", forecast lows and
   any other glucose-based ones. Leave the alerts that are not about glucose (phone battery, missed readings
   if you want to hear that xDrip+ lost the sensor) as you like.

None of that changes collection, the Dexcom connection or what is uploaded to Nightscout. Check once afterwards that Nightscout still gets a new reading every 5 minutes.

## 4. Home Assistant (optional)

Treating mode can be armed from a Home Assistant button, the same way snooze works: send the companion
app's `command_broadcast_intent` with `intent_action: studio.alans.fxwatcher.HA_TREAT` and
`intent_extras: "key:<webhook id>"`. Use `action:end,key:<webhook id>` to end it. Only wire it to something
you press yourself. Nothing in FX Watcher starts it on its own.

## 5. The watch

See `watchface/README.md` for the Garmin watch face and glance, and the phone's local `state.json`.
