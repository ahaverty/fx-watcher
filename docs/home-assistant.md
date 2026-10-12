# FX Watcher + Home Assistant

FX Watcher works on its own; Home Assistant is optional. Connecting them lets your house help
when the phone alone isn't enough, for example:

- see CamAPS's auto mode, glucose and FX Watcher's alarm state on a dashboard,
- flash a bedside light or announce on a speaker if a phone alarm goes unanswered,
- a backup alarm in Home Assistant if the phone itself goes quiet (dead, off, app killed),
- one snooze for everything: snoozing on the phone snoozes your HA alarms, and the other way round.

Everything below is copy-paste YAML. Replace the parts in `<angle brackets>`.

**How it works.** FX Watcher sends small JSON messages to a Home Assistant **webhook**.
Home Assistant can send a snooze back to the phone through the **Home Assistant companion app**.
FX Watcher never receives anything else from Home Assistant, and nothing leaves the phone unless
you enter a webhook address.

---

## 1. Pick a webhook id

The webhook id is the secret in the address: anyone who knows it can post to it. Use a long
random one, for example `fx-watcher-` plus 24 random letters and digits:

```
fx-watcher-k3j9x2m8q7w4v6b1n5c0z8a2
```

Your webhook address is then:

- away from home too (recommended, so it keeps working on mobile data):
  `https://<your-nabu-casa-or-external-address>/api/webhook/<webhook id>`
- home Wi-Fi only: `http://<home-assistant-ip>:8123/api/webhook/<webhook id>`

## 2. Create the helpers

**Settings > Devices & services > Helpers > Create helper**, or in YAML:

```yaml
input_text:
  camaps_auto_mode:
    name: CamAPS auto mode
    max: 100
  camaps_notification_text:
    name: CamAPS notification text
    max: 255
  fx_watcher_level:
    name: FX Watcher level
    max: 10
  fx_watcher_problem:
    name: FX Watcher problem
    max: 255

input_datetime:
  fx_watcher_last_seen:
    name: FX Watcher last seen
    has_date: true
    has_time: true

timer:
  glucose_alarm_snooze:
    name: Glucose alarm snooze
```

## 3. The webhook automation

**Settings > Automations > Create > Edit in YAML**, paste, and put your webhook id in:

```yaml
alias: FX Watcher webhook
description: Receives CamAPS state, FX Watcher's alarm state and snoozes from the phone.
mode: queued
max: 10
triggers:
  - trigger: webhook
    webhook_id: <webhook id>
    allowed_methods: [POST]
    local_only: false      # true if the phone only reaches HA on your home Wi-Fi
actions:
  # Any message at all means the phone and app are alive.
  - action: input_datetime.set_datetime
    target: {entity_id: input_datetime.fx_watcher_last_seen}
    data: {timestamp: "{{ now().timestamp() }}"}
  - choose:
      - alias: Snooze set or cancelled on the phone
        conditions: "{{ trigger.json.type | default('') == 'snooze' }}"
        sequence:
          - variables:
              secs: "{{ ((trigger.json.until | int(0)) / 1000 - as_timestamp(now())) | int }}"
          - if: "{{ secs > 60 }}"
            then:
              - action: timer.start
                target: {entity_id: timer.glucose_alarm_snooze}
                data: {duration: "{{ secs }}"}
            else:
              - action: timer.cancel
                target: {entity_id: timer.glucose_alarm_snooze}
      - alias: FX Watcher state and heartbeat
        conditions: "{{ trigger.json.type | default('') == 'state' }}"
        sequence:
          - action: input_text.set_value
            target: {entity_id: input_text.fx_watcher_level}
            data: {value: "{{ trigger.json.level | default('NONE') }}"}
          - action: input_text.set_value
            target: {entity_id: input_text.fx_watcher_problem}
            data: {value: "{{ (trigger.json.title | default('OK') | string) | truncate(250, true, '') }}"}
    default:
      # A CamAPS reading (no "type")
      - action: input_text.set_value
        target: {entity_id: input_text.camaps_auto_mode}
        data: {value: "{{ trigger.json.status | default('Unknown') | string | truncate(100, true, '') }}"}
      - action: input_text.set_value
        target: {entity_id: input_text.camaps_notification_text}
        data: {value: "{{ (trigger.json.texts | default([]) | join(' | ')) | truncate(255, true, '') }}"}
```

## 4. Tell FX Watcher

In FX Watcher: **Settings > Home Assistant webhook**, paste the full webhook address, **Save
settings**. Then tap **Test alarm now** and silence it: within a few seconds
`input_text.fx_watcher_level` should go `ALARM` and back to `NONE`.

> Home Assistant answers `200 OK` even when it ignores a webhook (wrong id, or `local_only: true`
> while the phone is on mobile data). If nothing changes, look in **Settings > System > Logs** for
> "Received remote request for local webhook", and check the automation's **Traces**.

## 5. Useful sensors

A "phone alive" sensor: FX Watcher reports at least every 9 minutes, so 25 minutes of silence
means the phone or the app is gone.

```yaml
template:
  - binary_sensor:
      - name: FX Watcher alive
        unique_id: fx_watcher_alive
        device_class: connectivity
        state: >
          {{ (now() - states('input_datetime.fx_watcher_last_seen') | as_datetime
              | as_local).total_seconds() < 25 * 60 }}
      - name: CamAPS auto mode on
        unique_id: camaps_auto_mode_on
        state: "{{ is_state('input_text.camaps_auto_mode', 'On') }}"
```

## 6. Example: escalate an unanswered phone alarm

If the phone has been alarming for 10 minutes and nobody silenced or snoozed it, flash a light and
announce on a speaker every 5 minutes until it's answered. Change the entities and the
conditions (who's home, which hours) to suit you.

```yaml
alias: FX Watcher escalation
mode: single
max_exceeded: silent
triggers:
  - trigger: state
    entity_id: input_text.fx_watcher_level
    to: ALARM
    for: {minutes: 10}
conditions:
  - condition: time
    after: "21:00:00"
    before: "10:00:00"
  - condition: state
    entity_id: timer.glucose_alarm_snooze
    state: idle
actions:
  - repeat:
      while:
        - condition: state
          entity_id: input_text.fx_watcher_level
          state: ALARM
      sequence:
        - repeat:
            count: 8
            sequence:
              - action: light.turn_on
                target: {entity_id: <light.bedside>}
                data: {brightness_pct: 100, transition: 0}
              - delay: {seconds: 1}
              - action: light.turn_off
                target: {entity_id: <light.bedside>}
                data: {transition: 0}
              - delay: {seconds: 1}
        - action: tts.speak
          target: {entity_id: <tts.your_tts>}
          data:
            media_player_entity_id: <media_player.bedroom_speaker>
            message: >
              Your phone is alarming: {{ states('input_text.fx_watcher_problem') }}.
              Check your phone and pump.
          continue_on_error: true
        - wait_for_trigger:
            - trigger: state
              entity_id: input_text.fx_watcher_level
              from: ALARM
          timeout: "00:05:00"
```

To make Home Assistant a **backup** alarm (phone dead, app killed), trigger on
`binary_sensor.fx_watcher_alive` turning `off` for a few minutes, with whatever conditions suit
you (night time, someone home).

## 7. Snooze from Home Assistant to the phone

Needs the **Home Assistant companion app** on the same phone. When `timer.glucose_alarm_snooze`
starts or is cancelled (from a bedside button, a dashboard, an HA notification...), this sends it
to FX Watcher. FX Watcher only accepts it if `key` is the end of its webhook address, so other
apps can't silence it.

```yaml
alias: Glucose snooze to FX Watcher
mode: queued
max: 10
triggers:
  - trigger: event
    event_type: timer.started
    event_data: {entity_id: timer.glucose_alarm_snooze}
  - trigger: event
    event_type: timer.restarted
    event_data: {entity_id: timer.glucose_alarm_snooze}
  - trigger: event
    event_type: timer.cancelled
    event_data: {entity_id: timer.glucose_alarm_snooze}
actions:
  - variables:
      until_ms: >
        {% if trigger.event.event_type == 'timer.cancelled' %}0{% else %}{{
        (as_timestamp(state_attr('timer.glucose_alarm_snooze', 'finishes_at'), 0) * 1000) | int }}{% endif %}
  - action: notify.mobile_app_<your_phone>
    data:
      message: command_broadcast_intent
      data:
        intent_package_name: studio.alans.fxwatcher
        intent_action: studio.alans.fxwatcher.HA_SNOOZE
        intent_extras: "until:{{ until_ms }}:long,key:<webhook id>"
```

A snooze set on the phone comes back to Home Assistant through the webhook (step 3); FX Watcher
ignores the echo of its own snooze, so the two don't loop.

---

## Message reference

Every message is a JSON `POST` to your webhook. `ts` is the phone's time in epoch milliseconds.

**CamAPS reading** (no `type`): sent when CamAPS's notification text changes, about every 5 min.

```json
{"status": "On", "glucose": 6.2, "texts": ["CamAPS FX", "Auto mode", "On", "6.2", "mmol/L"],
 "package": "com.camdiab.fx_alert.mmoll", "ts": 1791830000000}
```

- `status`: CamAPS's auto mode text: `On`, `Attempting`, `Starting`, `Stopping`, `Off`, or
  `Unknown` (CamAPS not in English), or `No notification` when CamAPS's notification disappears.
- `glucose`: mmol/L, missing when CamAPS shows none.
- `texts`: everything CamAPS's notification shows, in order.

**FX Watcher state** (`type: state`): on every change, and every 9 minutes as a heartbeat.

```json
{"type": "state", "level": "ALARM", "problem": "SIGNAL",
 "title": "Auto mode: Attempting", "snoozed": false,
 "pump": "on", "battery": 72, "charging": false, "ts": 1791830000000}
```

- `level`: `NONE` (all fine), `WARN` (early warning), `ACKED` (alarm silenced, will come back),
  `ALARM` (sounding).
- `problem`: `SIGNAL`, `MISSING` (CamAPS not running), `STALE` (FX Watcher can't see CamAPS),
  `LOW` (urgent low), `PUMP` (pump Bluetooth link off), `BATTERY`, `TEST`, or empty.
- `title`: the same text the phone shows, e.g. `Auto mode: Attempting`, `No glucose reading`,
  `CamAPS FX isn't running`, `Urgent low: 2.9`, `Pump link off for 40 min`, or `OK`.
- `pump`: `on`, `off` or `unknown`.

**Snooze** (`type: snooze`): when a snooze is set or cancelled on the phone.

```json
{"type": "snooze", "until": 1791837200000, "ts": 1791830000000}
```

`until` is when the snooze ends (epoch ms); `0` means cancelled.
