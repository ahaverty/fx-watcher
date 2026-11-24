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
        var v = new WidgetNumberView();
        return [v, new WidgetNumberDelegate(v)];
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

    // The state, and (when the background fetch got it) the history under "hist", kept apart.
    static function store(data) {
        try {
            var h = data["hist"];
            if (h != null) {
                Application.Storage.setValue("hist", h);
                data.remove("hist");
            }
            Application.Storage.setValue("state", data);
            Application.Storage.setValue("fetched", Time.now().value());
        } catch (ex) {
            // keep what was stored before
        }
    }
}

(:background)
class GlanceService extends System.ServiceDelegate {

    const URL = "http://127.0.0.1:17590/state.json";
    const HISTORY_URL = "http://127.0.0.1:17590/history.json";

    var _state = null;

    function initialize() {
        ServiceDelegate.initialize();
    }

    function options() {
        return {
            :method => Communications.HTTP_REQUEST_METHOD_GET,
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
        };
    }

    function onTemporalEvent() {
        Communications.makeWebRequest(URL, {}, options(), method(:onReceive));
    }

    // The state first, then the history; whatever arrives is handed back together.
    function onReceive(code as Number, data as Dictionary or String or Null) as Void {
        if (code == 200 && data instanceof Lang.Dictionary) {
            _state = data;
            Communications.makeWebRequest(HISTORY_URL, {}, options(), method(:onHistory));
        } else {
            Background.exit({ "error" => code });
        }
    }

    function onHistory(code as Number, data as Dictionary or String or Null) as Void {
        var out = _state;
        if (out != null && code == 200 && data instanceof Lang.Dictionary) {
            out["hist"] = data;
        }
        if (out == null) {
            Background.exit({ "error" => code });
        } else {
            Background.exit(out);
        }
    }
}
