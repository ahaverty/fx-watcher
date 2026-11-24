import Toybox.Application;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

// What the number widget and its glance show: glucose, trend and age. Grey and "no data" after
// 15 minutes. The text is built once, here, so the glance and the widget agree.

(:glance)
class NumberText {

    static function line() {
        var s = Application.Storage.getValue("state") as Dictionary?;
        var fetched = Application.Storage.getValue("fetched");
        if (s == null || fetched == null) {
            return null;
        }
        var g = s["glucose"];
        var age = s["age_min"];
        if (g == null || age == null || age < 0) {
            return null;
        }
        var minutes = age + (Time.now().value() - fetched) / 60;
        return { :glucose => g, :trend => s["trend"], :minutes => minutes };
    }

    static function trendWord(t) {
        if (t == null) { return ""; }
        if (t.equals("rising")) { return "rising"; }
        if (t.equals("falling")) { return "falling"; }
        if (t.equals("flat")) { return "steady"; }
        return "";
    }
}

(:glance)
class NumberGlance extends WatchUi.GlanceView {

    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        var d = NumberText.line();
        var text = "no data";
        if (d != null && d[:minutes] <= 15) {
            text = d[:glucose].format("%.1f") + "  " + NumberText.trendWord(d[:trend]) + "  " + d[:minutes] + " min";
        }
        dc.drawText(0, dc.getHeight() / 2, Graphics.FONT_GLANCE, text,
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class WidgetNumberView extends WatchUi.View {

    function initialize() {
        View.initialize();
    }

    function onShow() {
        fetch();
    }

    // Ask the phone for a fresh state right now.
    function fetch() {
        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_GET,
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
        };
        Communications.makeWebRequest("http://127.0.0.1:17590/state.json", {}, options, method(:onReceive));
    }

    function onReceive(code as Number, data as Dictionary or String or Null) as Void {
        if (code == 200 && data instanceof Lang.Dictionary && data.hasKey("state")) {
            GlanceApp.store(data);
        }
        WatchUi.requestUpdate();
    }

    function onUpdate(dc) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        var d = NumberText.line();
        var fresh = d != null && d[:minutes] <= 15;
        dc.setColor(fresh ? Graphics.COLOR_WHITE : 0x9AA0A6, Graphics.COLOR_TRANSPARENT);
        if (!fresh) {
            dc.drawText(w / 2, h / 2, Graphics.FONT_MEDIUM, "no data",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }
        dc.drawText(w / 2, h * 0.42, Graphics.FONT_NUMBER_THAI_HOT, d[:glucose].format("%.1f"),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.drawText(w / 2, h * 0.68, Graphics.FONT_SMALL, NumberText.trendWord(d[:trend]),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(0xB0B4B8, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 0.82, Graphics.FONT_XTINY, d[:minutes] + " min ago",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class WidgetNumberDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    // Select (the top right button or a tap) asks again.
    function onSelect() {
        var v = WatchUi.getCurrentView()[0];
        if (v instanceof WidgetNumberView) {
            v.fetch();
        }
        return true;
    }
}
