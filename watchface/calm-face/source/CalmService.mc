import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;

// Runs in the background every 5 minutes: fetch the calm state from the phone.
// If the phone is out of reach or the app is off, the request fails and the face just keeps
// the last state, which goes grey after 15 minutes.

(:background)
class CalmService extends System.ServiceDelegate {

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
