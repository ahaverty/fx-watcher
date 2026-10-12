> ⚠️ **Read the whole [README](https://github.com/ahaverty/fx-watcher#readme) before relying on this
> app overnight.** Installing it is not enough. Android will quietly shut it down to save battery
> unless you follow the **Keep it running** steps, and if that happens **it won't alarm and won't
> tell you**.

1. Download **fx-watcher.apk** below on your Android phone and open it to install.
2. Open FX Watcher, accept the notice, and fix every line in its **Setup** list.
3. Follow **every** step in [Keep it running](https://github.com/ahaverty/fx-watcher#keep-it-running),
   for both FX Watcher **and CamAPS FX**.
4. Tap **Test alarm in 20 s**, lock the phone, and check it wakes you.

Unofficial and not a medical device: keep CamAPS FX's own alerts on.

**New in v3.2:** slowly absorbed meals from CamAPS's chart: when it was entered, the slow amount and when CamAPS expects it absorbed, sent to Nightscout as a "CamAPS Slow meal" (without a carbs field, so carbs are never counted twice).

**New in v3.1:** basal from CamAPS's chart. Turn the phone sideways to CamAPS's chart and the optional screen reader reads the auto-mode basal line (every rate step on screen) and sends it to Nightscout as 5-minute Temp Basals, hours before Glooko has it. The chart is read from a screenshot that stays in memory and is dropped straight after; nothing is saved. Views it can't fully verify, or zoomed out past about 9 hours, are skipped. After updating, turn the screen reader off and on again in Accessibility settings so Android gives it the new screenshot permission.

**New in v3.0.3:** more reliable Nightscout records from the optional screen reader: a Boost or Ease-off can no longer be logged as cancelled (and then twice) when CamAPS redraws its home screen, a planned Boost is recorded once, a bolus read late is timed when it was delivered, and an updated CamAPS notification can't count as a second bolus.

**New in v3.0.2:** fixes a bolus being sent to Nightscout twice when CamAPS's "Delivered" message was still on screen after reopening CamAPS. Only matters if you use the optional Nightscout and screen reader features.

**New in v3.0:** optional extras, all off until you turn them on:
- **Bedtime media volume.** CamAPS plays most alerts on the media volume, so they're silent if media is turned down. FX Watcher can raise it when you go to bed (after your phone's sleep mode or a set time, once the phone has been put down for 10 min with nothing playing) and put it back in the morning. Fully adjustable, including per day of the week.
- **CamAPS screen reader (read-only).** Records Boost, Ease-off, meals, bolus deliveries and active insulin while you use CamAPS. It cannot tap or change anything.
- **Nightscout.** Sends those events to your Nightscout within a minute.
- Nightscout and the screen reader are set up inside the app (Settings and the Setup list).

See the [README](https://github.com/ahaverty/fx-watcher#optional-extras) for details.

**New in v2.6:** a CamAPS low alert no longer makes auto mode show as "Unknown". CamAPS shows the alert as a second notification next to its status one; FX Watcher now ignores it and keeps reading the status.
