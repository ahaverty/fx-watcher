import Toybox.Application;
import Toybox.Background;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

// Calm watch face: time, date, a coloured ring and one state word. No glucose number, no graph.
//
// The phone app (FX Watcher) serves http://127.0.0.1:17590/state.json on the phone only. The
// Garmin Connect app on the phone makes the request for the watch. The background service asks
// every 5 minutes (the shortest a watch face may), the result is kept, and the face colours
// the ring from it. After 15 minutes without a fresh reading the ring goes grey and says "no data".

(:background)
class CalmApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
        Background.registerForTemporalEvent(new Time.Duration(5 * 60));
    }

    function getInitialView() {
        return [new CalmView()];
    }

    function getServiceDelegate() {
        return [new CalmService()];
    }

    // The background service finished: keep what it fetched, and when.
    function onBackgroundData(data) {
        if (data instanceof Lang.Dictionary && data.hasKey("state")) {
            Application.Storage.setValue("state", data);
            Application.Storage.setValue("fetched", Time.now().value());
        } else if (data instanceof Lang.Dictionary && data.hasKey("error")) {
            Application.Storage.setValue("error", data["error"]);
        }
        WatchUi.requestUpdate();
    }
}
