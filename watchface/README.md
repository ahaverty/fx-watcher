# Calm Garmin watch face (Forerunner 165)

Two small Connect IQ projects. They replace the closed-source xDrip face, which has no source to change.

* `calm-face/` the watch face: time, date, a coloured ring and one state word. **No glucose number, no graph.**
* `calm-glance/` a widget with a glance, for when you deliberately want the number: glucose, trend, and how old it is.

**Status: written, not built.** The Connect IQ SDK is not installed on this PC and installing it needs your OK,
so none of this has been compiled or run on a watch. Expect a first build to need a small fix or two.

## How it gets its data

FX Watcher on the phone serves `http://127.0.0.1:17590/state.json`, read only, on the phone itself (not reachable
from the network). The Garmin Connect app on the phone makes the request for the watch (it is the same trick
xDrip+'s own watch faces use with its local web server). Open that address in the phone's browser to check it.

```json
{"state":"steady","word":"Steady","colour":"#1FA89A","text":"","glucose":6.2,"trend":"flat","age_min":3,"updated":1795471429}
```

The face asks every 5 minutes, the fastest a watch face may. **Urgent things do not come through this**: they
arrive as notifications (the Low coming buzz, Treating, CamAPS alerts) through Garmin Connect as before.

| State | Ring | Word |
|---|---|---|
| steady | teal `#1FA89A` | steady |
| handled | amber `#F2A33A` | handled |
| check | coral `#F26B5B` | check |
| low_soon, treating | purple `#8A63D2` | low soon, treating |
| no reading for 15 min, or the phone is out of reach | grey | no data |

## Build and sideload (needs your OK to install the SDK)

1. Install the Connect IQ SDK Manager from developer.garmin.com/connect-iq/sdk, download the latest SDK and the
   **Forerunner 165** (and 165 Music) device files. Install the "Monkey C" extension in VS Code.
2. Make a developer key (VS Code command palette: "Monkey C: Generate a Developer Key"). Keep it out of git.
3. Build each project (VS Code: "Monkey C: Build for Device", choose `fr165`), or from a terminal in the project folder:
   ```
   monkeyc -f monkey.jungle -d fr165 -o bin/CalmFace.prg -y developer_key.der -w
   ```
   `calm-glance` the same way, output `bin/CalmNumber.prg`. To run in the simulator first:
   `connectiq` then `monkeydo bin/CalmFace.prg fr165`. The simulator can fetch the phone's address only if the
   phone is reachable at 127.0.0.1, so test the real thing on the watch.
4. Plug the watch in by USB. Copy each `.prg` to the watch's `GARMIN/APPS` folder. Eject, unplug.
5. On the watch: hold the watch face, pick the face **Calm**. For the number: the widget list, **Calm number**.
6. In FX Watcher on the phone: "Serve the calm state to the Garmin watch" is on by default. In Garmin Connect, make
   sure the watch is connected and the app is allowed to run in the background (no battery restriction).

If the face says "no data": open `http://127.0.0.1:17590/state.json` in the phone browser. If that works, the
phone side is fine and it is the Garmin Connect link (Bluetooth, background restriction). If not, check that FX
Watcher has notification access and the server switch is on.

## When it works

Keep the old xDrip face for a day or two. When you are happy, switch faces. Do not touch xDrip+'s collection or
upload: only the old face used its broadcast, and nothing else needs that.

## Notes for later

* The face draws a thinner dimmed ring and no word while the screen is in low power mode (AMOLED).
* To change the port, edit `URL` in `calm-face/source/CalmService.mc` and `calm-glance/source/GlanceApp.mc`
  and the port in FX Watcher's settings. They must match.
* Settings a user can change on the phone (port, stale time) are left out to keep the first build small.
