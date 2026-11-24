# Early low warning: backtest

Generated 23 Nov 2026 from Alan's Nightscout history with `tools/backtest` (`fetch_ns.py` pulls the readings, `Backtest.java` replays them through `LowWarning.java`, the same class the phone runs). Full grid of 880 settings: [early-low-backtest-grid.md](early-low-backtest-grid.md).

## Short answer

**The target (at least 80% of lows caught 15+ min ahead with at most 2 false warnings a day) cannot be met by a CGM-only rule on this history.** Catching 80% costs about 3 false warnings a day; staying at 2 a day catches about 73%. Confirming over two readings, capping the glucose it will warn from, or stricter thresholds move along the same curve, none of them off it. You choose the point on the curve; the app ships three.

## Method

* Data: 164,245 Nightscout readings, 1 Jan 2024 to 23 Nov 2026. 151k of them are the Glooko backfill (mixed sensors, gaps in 2024 to 2026); 13.3k are G7 readings uploaded by xDrip+ since 6 Oct 2026. Results are shown for all of it, for 2026 only, and for the G7 era alone (61 lows, so treat it as indicative).
* Readings are rounded to 0.1 mmol/L, as CamAPS's notification shows them, because that is what the app sees.
* A **low** is the first reading under 3.9 after 30 clean minutes at or above it (episodes within 30 min merge). 679 lows in all, about 1.2 a day. 11 lows right after a data gap were skipped.
* A low is **caught** if a warning came 15 to 60 min before it. **Median lead** is over the caught lows (first warning to first reading under 3.9). **Any warning** also counts warnings under 15 min ahead.
* A warning is **false** if no reading went under 3.9 in the 45 min after it. **Hard false** also never went under 4.5 (a near miss, 3.9 to 4.5, is false but not silly).
* After a warning: none for 30 min, and none until the rule has been quiet 15 min. **Rebuzz** is the one repeat 10 min later, only if the projection got 0.3 worse; it is not counted as a warning.

## The three levels in the app (fast rule on: glucose under 5.5 and falling 0.1/min also warns)

| Level | fit | ahead | projection below | caught 15+ min | median lead | any warning | warnings/day | false/day | hard false/day | rebuzz/day |
|---|---|---|---|---|---|---|---|---|---|---|
| **all (Jan 2024 to now)** | | | | | | | | | | |
| Early | 15 min | 25 min | 4.0 | 80% | 30 min | 94% | 3.9 | 2.9 | 1.9 | 1.5 |
| Calm | 20 min | 20 min | 3.8 | 67% | 25 min | 94% | 2.8 | 1.7 | 0.9 | 0.9 |
| Balanced (default) | 20 min | 20 min | 4.0 | 73% | 30 min | 95% | 3.1 | 2.0 | 1.1 | 1.3 |
| **2026 only** | | | | | | | | | | |
| Early | 15 min | 25 min | 4.0 | 80% | 30 min | 93% | 4.5 | 3.1 | 2.0 | 1.8 |
| Calm | 20 min | 20 min | 3.8 | 67% | 25 min | 94% | 3.3 | 1.9 | 1.0 | 1.1 |
| Balanced (default) | 20 min | 20 min | 4.0 | 73% | 30 min | 94% | 3.7 | 2.2 | 1.2 | 1.5 |
| **since 6 Oct 2026 (G7 + xDrip upload)** | | | | | | | | | | |
| Early | 15 min | 25 min | 4.0 | 79% | 35 min | 95% | 4.3 | 3.2 | 1.8 | 1.6 |
| Calm | 20 min | 20 min | 3.8 | 66% | 30 min | 95% | 3.3 | 1.9 | 0.8 | 1.0 |
| Balanced (default) | 20 min | 20 min | 4.0 | 75% | 30 min | 98% | 3.6 | 2.4 | 1.1 | 1.4 |

All levels only warn when glucose is 7.0 or lower (a projection from a high start is rarely a real low).

## Best catch rate for each false-warning budget (all data)

| false/day | best catch 15+ min | setting |
|---|---|---|
| 1.24 | 51% (median lead 25 min) | fit 15, ahead 20, below 3.8, confirm 2, only under 7.0 |
| 1.53 | 57% (median lead 25 min) | fit 15, ahead 20, below 4.0, confirm 2, only under 7.0 |
| 1.56 | 58% (median lead 25 min) | fit 15, ahead 20, below 4.0, confirm 2, only under 8.0 |
| 1.58 | 59% (median lead 25 min) | fit 20, ahead 20, below 4.0, confirm 2, only under 7.0 |
| 1.65 | 62% (median lead 25 min) | fit 15, ahead 25, below 3.8, confirm 2, only under 7.0 |
| 1.69 | 67% (median lead 25 min) | fit 20, ahead 20, below 3.8, confirm 1, only under 7.0 |
| 1.72 | 68% (median lead 25 min) | fit 20, ahead 20, below 3.8, confirm 1, only under 8.0 |
| 1.88 | 71% (median lead 25 min) | fit 15, ahead 20, below 3.8, confirm 1, only under 7.0 |
| 1.95 | 72% (median lead 25 min) | fit 15, ahead 20, below 3.8, confirm 1, only under 8.0 |
| 2.00 | 73% (median lead 30 min) | fit 20, ahead 20, below 4.0, confirm 1, only under 7.0 |
| 2.05 | 74% (median lead 30 min) | fit 20, ahead 20, below 4.0, confirm 1, only under 8.0 |
| 2.16 | 75% (median lead 30 min) | fit 20, ahead 25, below 3.8, confirm 1, only under 7.0 |
| 2.24 | 76% (median lead 30 min) | fit 15, ahead 20, below 4.0, confirm 1, only under 7.0 |
| 2.33 | 77% (median lead 30 min) | fit 15, ahead 20, below 4.0, confirm 1, only under 8.0 |
| 2.65 | 78% (median lead 35 min) | fit 20, ahead 30, below 3.8, confirm 1, only under 7.0 |
| 2.86 | 80% (median lead 30 min) | fit 15, ahead 25, below 4.0, confirm 1, only under 7.0 |
| 3.20 | 81% (median lead 35 min) | fit 15, ahead 20, below 4.5, confirm 1, only under 7.0 |

## What the numbers do not say

* **False warnings are an upper bound.** The history has no warnings in it, so a "false" one is any point where the rule would have fired and glucose then stayed up. Some of those are lows that would have happened, if nobody had eaten and CamAPS had not cut insulin: in real use the warning is acted on, so a true save looks like a false alarm. The real number will be lower.
* The reverse also holds: lows you had already seen coming and treated early are in the "lows" count here only if glucose still went under 3.9.
* About one low in five is warned, but under 15 min ahead (the any-warning column minus the caught column): fast drops after a bolus. No CGM-only rule fixes that. Using insulin on board is the next lever, and is not in this backtest because Nightscout's pump history is hours late.
* Different sensors across 2024 to 2026 behave differently, but the 2026 and G7-only rows land in the same place as the full history.

## Retuning

Every warning goes into the app's log and a journal with what came of it ("glucose went under 3.9 22 min later" or "no low within 45 min, lowest 4.6"), shown under Early low warning in the app. After a few weeks that gives a real false-warning rate for your decisions; re-run the backtest after changing `LowWarning.preset`.

To re-run: `python tools/backtest/fetch_ns.py http://<nightscout> sgv.csv`, then
`javac --release 11 -d out app/src/main/java/studio/alans/fxwatcher/LowWarning.java tools/backtest/Backtest.java` and `java -cp out studio.alans.fxwatcher.Backtest sgv.csv`.

## Fast lows (added 23 Nov, the default level)

Fast low: a fall of 2.0 or more in the 20 minutes before the first reading under 3.9. 151 of the 679 lows (0.26 a day, about 1 in 4). A fast drop shows nothing in the glucose line until it starts, so about 10 minutes of notice is the most a glucose-only rule can give. Grid of fall-rate rules: [early-low-fast-grid.md](early-low-fast-grid.md) (`tools/backtest/FastBacktest.java`).

The "Fast lows only" level warns when glucose falls 0.10 mmol/L per minute or more over 15 minutes, is under 6.0, and there is at least 0.5 U on board (unknown insulin does not block it). It also warns when CamAPS itself says "Urgent Low Soon", "Glucose falling fast / very fast" or posts a falling glucose alert. Same debounce, same Treating suppression.

Insulin on board, tested on the 46 days with logged boluses (`tools/backtest/IobBacktest.java`, 61 lows, only 7 of them fast, so this is a direction and not a proof):

since 6 Oct: 46 days, 61 lows, 7 fast, of which 5 had >= 1.0 U on board
| rule | fast lows warned 5+ min | 10+ min | all lows warned 10+ min | warnings/day | false/day |
|---|---|---|---|---|---|
| A fall >= 0.10/min, under 6.0 (no insulin info) | 100% (7/7) | 43% | 23% (14/61) | 0.94 | 0.53 |
| B fall >= 0.12/min, under 6.0 (no insulin info) | 71% (5/7) | 43% | 15% (9/61) | 0.53 | 0.24 |
| C 0.07/min if >= 1.0 U on board, else 0.12 | 100% (7/7) | 57% | 36% (22/61) | 1.35 | 0.77 |
| D 0.08/min if >= 1.0 U on board, else 0.15 | 86% (6/7) | 43% | 26% (16/61) | 1.03 | 0.58 |
| E 0.10/min only if >= 0.5 U on board | 100% (7/7) | 43% | 20% (12/61) | 0.73 | 0.38 |
| F 0.08/min if >= 1.5 U on board, else off | 71% (5/7) | 43% | 21% (13/61) | 1.01 | 0.71 |
| G 0.12/min if < 0.5 U on board, 0.08 if >= 0.5 U, under 6.5 | 100% (7/7) | 71% | 38% (23/61) | 1.50 | 0.96 |
