# FX Watcher

An extra, phone-local alarm for people who use **CamAPS FX** on Android: it wakes you up when
CamAPS loses signal, drops out of auto mode, or stops running, as long as the phone has power.
No server, no account, no internet needed.

## ⬇️ Download

**[Download the latest FX Watcher (fx-watcher.apk)](https://github.com/ahaverty/fx-watcher/releases/latest/download/fx-watcher.apk)**
· [What's new in each version](https://github.com/ahaverty/fx-watcher/releases)

[![Latest version](https://img.shields.io/github/v/release/ahaverty/fx-watcher?label=latest%20version&cacheSeconds=300)](https://github.com/ahaverty/fx-watcher/releases/latest/download/fx-watcher.apk)

Open that link on your Android phone, then follow **[Install](#install)** and
**[Keep it running](#keep-it-running)** below. To update later, download and open it again:
your settings are kept. The app's version is shown in Android's Settings > Apps > FX Watcher.

> **Unofficial and not a medical device.** FX Watcher is not made by or affiliated with CamDiab
> or Ypsomed. It can fail (phone off, battery dead, a CamAPS update changing its notification).
> Keep CamAPS FX's own alerts on. FX Watcher only *reads* CamAPS (its notification, and its
> screen if you turn on the optional screen reader) and may reopen the app; it never taps
> anything in CamAPS, never changes CamAPS settings and never touches insulin delivery.

## What it alarms on

| Problem | Default |
|---|---|
| Auto mode not **On**, or no glucose shown | warning at 10 min, alarm at 20 min |
| CamAPS FX not running (its notification is gone) | alarm after 5 min |
| FX Watcher can't see CamAPS (lost notification access) | alarm after 15 min |
| Urgent low (3.0 mmol/L / 54 mg/dL) | alarm at once, can't be snoozed |
| Pump Bluetooth link off (YpsoPump by default; short gaps are normal) | warning at 25 min, alarm at 35 min |
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

1. [Download fx-watcher.apk](https://github.com/ahaverty/fx-watcher/releases/latest/download/fx-watcher.apk)
   on your phone and open it (allow installing from your browser when asked).
2. Open FX Watcher, accept the notice, and tap **Fix** on each line under **Setup**:
   notification access, notifications, full-screen alarms, display over other apps,
   battery unrestricted, and Bluetooth (for the pump link).
3. Tap **Test alarm in 20 s**, lock the phone, and check it wakes you.

4. Do every step in **Keep it running** below. Skipping them is the most likely way for FX Watcher
   to fail silently.

Needs Android 10 or later. CamAPS FX must be set to **English** for the auto mode check
(otherwise FX Watcher still alarms on missing glucose and CamAPS stopping, and tells you so).

## Keep it running

Android shuts down background apps to save battery. If it shuts down FX Watcher, **nothing alarms
and nothing tells you**. Do all of these for **both FX Watcher and CamAPS FX**. CamAPS needs them
just as much, and many signal losses start with CamAPS being put to sleep.

Samsung (One UI; names differ a little between versions):

1. **Settings > Apps > (app) > Battery > Unrestricted.** FX Watcher's Setup list covers this
   for FX Watcher. Do it by hand for CamAPS FX.
2. **Settings > Battery (or Device care > Battery) > Background usage limits:**
   - turn **off** "Put unused apps to sleep"
   - add both apps to **Never sleeping apps** (also called "Never auto sleeping apps")
   - make sure neither app is in "Sleeping apps" or "Deep sleeping apps"
3. **Settings > Apps > (app):** turn **off** "Pause app activity if unused" (or "Remove
   permissions if app is unused").
4. **Recent apps screen:** tap the app's icon at the top of its card and choose **Keep open**.
   "Close all" then leaves it alone.
5. **Notifications:** don't turn off or silence FX Watcher's notifications, including its
   status notification. Do Not Disturb, Sleep mode and Bedtime mode must still allow
   **Alarms**.
6. **After the phone restarts** (including Samsung's "Auto restart"/"Auto optimise"), unlock it
   once. Android only lets FX Watcher read notifications again after the first unlock.

Other brands (Xiaomi, OnePlus, Huawei, Oppo, Pixel...): see
[dontkillmyapp.com](https://dontkillmyapp.com) for your phone's equivalent steps, then do the
same for both apps.

**Check it works:** tap **Test alarm in 20 s**, lock the phone and put it down. Repeat after a
night where the phone sat locked for hours. If the status notification "FX Watcher is watching"
ever disappears, open the app and go through the Setup list again.

## Optional extras

All of these are **off until you turn them on** in the app. Without them FX Watcher is just the
alarm described above.

### Bedtime media volume

CamAPS FX plays most of its alerts (everything except Urgent Low) on the **media** volume. If
you turn media down in the evening, those alerts are silent all night. With this on, FX Watcher
raises media volume when you go to bed and puts it back in the morning.

- **When it starts:** whichever comes first: your phone's Sleep / Bedtime / Do not disturb mode
  turning on (counted from 21:00), Google's sleep detection (optional), or a set time (midnight).
- **It waits until you've put the phone down:** screen off for 10 minutes and nothing playing,
  so it never blasts a video or podcast you're listening to in bed. A quiet notification says
  it's waiting, with **Not tonight** and **Set it now** buttons.
- **It only ever raises the volume**, never lowers it.
- **Turned it down in the night?** If you turn media down again before morning (a video in bed),
  it's raised again each time you put the phone down for 10 minutes with nothing playing.
- **In the morning**, the first time you unlock after 07:00, it puts the volume back where it
  was before bedtime (unless you changed it after it was last raised).

Everything is adjustable under **Settings > Bedtime media volume**: the level, which signals
start it, the times, the idle minutes, the morning restore, and (Advanced) a different start
time for each day of the week, or "off" for nights you don't want it.
Google sleep detection asks for the *Physical activity* permission, only if you turn it on.

### CamAPS screen reader (read-only)

CamAPS's notification doesn't show Boost, Ease-off, meals or active insulin. The screen reader
reads them from the CamAPS screen **while you use CamAPS**, to send them to Nightscout (below).
Turn it on in **Settings > Accessibility > Installed apps > FX Watcher: CamAPS screen reader**
(the app's Setup list links there).

- It only listens to CamAPS FX and only **reads**: it can't tap, type or change anything, and
  every build is checked automatically for code that could.
- It records: Boost and Ease-off (start, length, planned, cancelled), Add meal entries (meal,
  hypo treatment, slowly absorbed), carbs from the bolus calculator, bolus deliveries, and
  active insulin, infusion rate, reservoir and pump battery from CamAPS's info panels.
- **Basal from the chart.** When you turn the phone sideways to CamAPS's chart, it reads the
  blue "insulin infusion" line: every auto-mode rate step on screen, with its time. It takes a
  screenshot of the chart (Android 11+) once you stop scrolling, reads it in memory and drops
  it: nothing is saved to your gallery or storage. Everything it reads is checked (the time and
  rate labels, the date, the "now" line against the clock) and a view it can't verify is
  skipped, never guessed. Zoom so about 9 hours or less fill the screen: further out the line
  is too crowded to read reliably, and it is skipped. One look backfills the whole view, so
  scrolling back over a day fills that day in.
- **Slowly absorbed meals from the chart.** A slow meal's carb box on the chart stretches to
  when CamAPS expects it absorbed. It reads when you entered it, the slow amount and that end
  time (matched Glooko's slow-meal portions to the minute in tests).
- Nothing it reads leaves the phone unless you set up Nightscout. It does not keep or send your
  account details or other CamAPS screens.
- Turning it off in Accessibility settings stops it at once.

### Nightscout

If you use [Nightscout](https://nightscout.github.io/), FX Watcher can send what it sees to it
within a minute, long before the same data reaches Glooko or other clouds:

| Sent as | From |
|---|---|
| `Bolus` treatment (insulin, plus carbs if the bolus calculator was used) | CamAPS's "Delivered X U" message |
| `Carbs` treatment with `meal_type` meal / hypo / slow | Add meal (screen reader) |
| `CamAPS Boost` / `CamAPS Ease-off` treatment with `duration` | screen reader |
| `CamAPS Auto mode` treatment (On, Off, Attempting...) | notification |
| device status with active insulin (`pump.iob`), reservoir, battery | screen reader |
| `Temp Basal` treatment per 5 min (`absolute` U/h, `duration` 5, `source: camaps-chart`) | the landscape chart (screen reader) |
| `CamAPS Slow meal` treatment (`slow_carbs_g`, `duration` until absorbed, `absorbed_by`) | the landscape chart (screen reader); no `carbs` field, so it never counts the carbs twice |

Set **Nightscout address** and **access token** under Settings. Make a Nightscout subject whose
role can only `api:treatments:create`, `api:treatments:read`, `api:devicestatus:create` and
`api:devicestatus:read`, and use its token. Events wait on the phone and are retried if
Nightscout can't be reached. Every event has `enteredBy: fx-watcher`.

## Home Assistant (optional, advanced)

**Step-by-step guide with copy-paste YAML: [docs/home-assistant.md](docs/home-assistant.md)**
(dashboard, escalating to lights and speakers, a backup alarm, and snooze both ways).

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
