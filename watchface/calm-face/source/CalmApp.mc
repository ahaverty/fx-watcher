import Toybox.Application;
import Toybox.Background;
import Toybox.Communications;
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
        // FX Watcher on the phone pushes the state the moment CamAPS has a reading.
        Communications.registerForPhoneAppMessages(method(:onPhone));
        return [new CalmView()];
    }

    function onPhone(msg as Communications.PhoneAppMessage) as Void {
        var d = msg.data;
        if (d instanceof Lang.Dictionary && d.hasKey("state")) {
            remember(d);
        }
    }

    // Keep a state, and when it arrived. The face computes the age from this.
    function remember(d as Dictionary) as Void {
        Application.Storage.setValue("state", d);
        Application.Storage.setValue("fetched", Time.now().value());
        WatchUi.requestUpdate();
    }

    function getServiceDelegate() {
        return [new CalmService()];
    }

    // The background service finished: keep what it fetched, and when.
    function onBackgroundData(data) {
        if (data instanceof Lang.Dictionary && data.hasKey("state")) {
            remember(data);
        } else if (data instanceof Lang.Dictionary && data.hasKey("error")) {
            Application.Storage.setValue("error", data["error"]);
        }
        WatchUi.requestUpdate();
    }
}
