import Toybox.Application;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

// Calm number: a widget for when you deliberately want the glucose number. It is not on the watch
// face. The glance (in the widget list) shows the last number, trend and how old it is; opening
// the widget fetches a fresh one from FX Watcher on the phone (http://127.0.0.1:17590/state.json).

(:background, :glance)
class GlanceApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
        Background.registerForTemporalEvent(new Time.Duration(5 * 60));
    }

    function getInitialView() {
        return [new WidgetNumberView(), new WidgetNumberDelegate()];
    }

    function getGlanceView() {
        return [new NumberGlance()];
    }

    function getServiceDelegate() {
        return [new GlanceService()];
    }

    function onBackgroundData(data) {
        if (data instanceof Lang.Dictionary && data.hasKey("state")) {
            store(data);
        }
        WatchUi.requestUpdate();
    }

    static function store(data) {
        Application.Storage.setValue("state", data);
        Application.Storage.setValue("fetched", Time.now().value());
    }
}

(:background)
class GlanceService extends System.ServiceDelegate {

    const URL = "http://127.0.0.1:17590/state.json";

    function initialize() {
        ServiceDelegate.initialize();
    }

    function onTemporalEvent() {
        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_GET,
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
        };
        Communications.makeWebRequest(URL, {}, options, method(:onReceive));
    }

    function onReceive(code, data) {
        if (code == 200 && data instanceof Lang.Dictionary) {
            Background.exit(data);
        } else {
            Background.exit({ "error" => code });
        }
    }
}
