# FX Watcher

An extra, phone-local alarm for people who use **CamAPS FX** on Android: it wakes you up when
CamAPS loses signal, drops out of auto mode, or stops running, as long as the phone has power.
No server, no account, no internet needed.

> **Unofficial and not a medical device.** FX Watcher is not made by or affiliated with CamDiab
> or Ypsomed. It can fail (phone off, battery dead, a CamAPS update changing its notification).
> Keep CamAPS FX's own alerts on. FX Watcher only *reads* CamAPS's notification and may reopen the
> app; it never changes CamAPS settings or insulin delivery.

## What it alarms on

| Problem | Default |
|---|---|
| Auto mode not **On**, or no glucose shown | warning at 10 min, alarm at 20 min |
| CamAPS FX not running (its notification is gone) | alarm after 5 min |
| FX Watcher can't see CamAPS (lost notification access) | alarm after 15 min |
| Urgent low (3.0 mmol/L / 54 mg/dL) | alarm at once, can't be snoozed |
| Pump Bluetooth link off (YpsoPump by default; short gaps are normal) | warning at 30 min, alarm at 40 min |
| Phone battery at night (23:00-08:00) and not charging | warning below 30 %, alarm below 15 % |

All times and thresholds can be changed in the app.

- The alarm plays on the **alarm** volume at full level, so media volume and silent mode don't matter,
  vibrates, and shows full screen over the lock screen. It comes back 15 min after you silence it
  if the problem is still there.
- **Snooze** 1-8 hours for sport or when you're away from the phone (urgent lows still alarm).
- Alerts are message-style notifications, so they show on smartwatches and in Android Auto,
  with Silence / Snooze buttons (or reply "snooze 2" / "ok").
- When something's wrong it reopens CamAPS FX to help it recover.

## Install

1. Download `fx-watcher.apk` from the [latest release](../../releases/latest) on your phone and open it
   (allow installing from your browser when asked).
2. Open FX Watcher, accept the notice, and tap **Fix** on each line under **Setup**:
   notification access, notifications, full-screen alarms, display over other apps,
   battery unrestricted, and Bluetooth (for the pump link).
3. Tap **Test alarm in 20 s**, lock the phone, and check it wakes you.

Needs Android 10 or later. CamAPS FX must be set to **English** for the auto mode check
(otherwise FX Watcher still alarms on missing glucose and CamAPS stopping, and tells you so).

## Home Assistant (optional, advanced)

Leave the webhook empty and nothing leaves the phone. If you set a Home Assistant webhook URL,
FX Watcher POSTs to it:

- each CamAPS reading: `{status, glucose, texts, package, ts}`
- its own state, on every change and every 9 min as a heartbeat:
  `{type: "state", level: NONE|WARN|ACKED|ALARM, problem, title, snoozed, pump, battery, charging, ts}`
- snoozes set on the phone: `{type: "snooze", until: <epoch ms, 0 = cancelled>}`

Home Assistant can snooze the phone back through the companion app's `command_broadcast_intent`:
action `studio.alans.fxwatcher.HA_SNOOZE`, extras `until:<epoch ms>:long,key:<your webhook id>`
(the key must match the end of the webhook URL).

## Building

GitHub Actions builds every push (artifact `fx-watcher-apk`); pushing a `v*` tag publishes a release.
Release signing uses the `FX_KEYSTORE_B64` / `FX_KEYSTORE_PASSWORD` repository secrets; without them
the build is signed with a debug key. adb test hooks are documented in `DebugReceiver.java`.
