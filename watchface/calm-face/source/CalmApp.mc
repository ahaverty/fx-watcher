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

    var registered = false;

    function getInitialView() {
        return [new CalmView()];
    }

    // FX Watcher on the phone pushes the state the moment CamAPS has a reading. Registered from the
    // view once it is showing (and tried again each time), never while the face is still starting:
    // a message queued while the face was away is delivered the moment we register, and a failure
    // here used to take the whole face down ("IQ!" and a different face).
    function register() as Void {
        if (registered) {
            return;
        }
        try {
            Communications.registerForPhoneAppMessages(method(:onPhone));
            registered = true;
        } catch (ex) {
            registered = false;
        }
    }

    function onPhone(msg as Communications.PhoneAppMessage) as Void {
        try {
            var d = msg.data;
            if (d instanceof Lang.Dictionary && d.hasKey("state")) {
                remember(d);
            }
        } catch (ex) {
            // a bad message is dropped; the face keeps the last state
        }
    }

    // Keep a state, and when it arrived. The face computes the age from this.
    function remember(d as Dictionary) as Void {
        try {
            Application.Storage.setValue("state", d);
            Application.Storage.setValue("fetched", Time.now().value());
        } catch (ex) {
            return;
        }
        try {
            WatchUi.requestUpdate();
        } catch (ex) {
            // no view yet
        }
    }

    function getServiceDelegate() {
        return [new CalmService()];
    }

    // The background service finished: keep what it fetched, and when.
    function onBackgroundData(data) {
        try {
            if (data instanceof Lang.Dictionary && data.hasKey("state")) {
                remember(data);
            } else if (data instanceof Lang.Dictionary && data.hasKey("error")) {
                Application.Storage.setValue("error", data["error"]);
            }
            WatchUi.requestUpdate();
        } catch (ex) {
            // keep the last state
        }
    }
}
