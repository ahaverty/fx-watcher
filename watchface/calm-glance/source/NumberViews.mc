import Toybox.Application;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

// What the number widget and its glance show: glucose, trend and age, plus (from the phone's
// /history.json) the last hours as dots, the rate, and how long until a low at that rate.
// Grey and "no data" after 15 minutes. The text is built once, here, so the glance and the widget agree.

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

// The history the phone sent: points as [minutes before now (0 or negative), mmol/L], oldest first.
(:glance)
class NumberSeries {

    static const LOW = 3.9;
    static const PURPLE = 0x9B7BE0;
    static const TEAL = 0x1FA89A;
    static const AMBER = 0xF2A33A;
    static const BAND = 0x0F3A36;

    static function stored() {
        var h = Application.Storage.getValue("hist");
        if (h != null && h instanceof Lang.Dictionary) {
            return h;
        }
        return null;
    }

    static function points() {
        var h = stored();
        if (h == null) { return null; }
        var arr = h["h"];
        var end = h["end"];
        if (arr == null || end == null || !(arr instanceof Lang.Array)) { return null; }
        var now = Time.now().value();
        var n = arr.size();
        var out = [];
        for (var i = 0; i < n; i++) {
            var v = arr[i];
            if (v == null || v < 0) { continue; }
            var t = (end - (n - 1 - i) * 300 - now) / 60;
            out.add([t, v / 10.0]);
        }
        return out;
    }

    // mmol/L per minute, or null when the phone had no fit.
    static function slope() {
        var h = stored();
        if (h == null) { return null; }
        var s = h["slope"];
        if (s == null || s == 0) { return null; }
        return s / 1000.0;
    }

    // Minutes until 3.9 at the current rate, counted from now, or null when not falling or unknown.
    static function lowIn(ageMin) {
        var h = stored();
        if (h == null) { return null; }
        var m = h["lowin"];
        if (m == null || m < 0) { return null; }
        var left = m - ageMin;
        if (left < 0) { left = 0; }
        return left;
    }

    static function colour(v) {
        if (v < LOW) { return PURPLE; }
        if (v > 10.0) { return AMBER; }
        return TEAL;
    }

    // The one sentence, its colour, and a shorter form for the glance: [text, colour, short].
    static function message(g, trend, ageMin) {
        if (g < LOW) {
            return ["Low now", PURPLE, "Low now"];
        }
        var m = lowIn(ageMin);
        if (m != null && m <= 90) {
            if (m < 3) {
                return ["Low very soon", PURPLE, "Low soon"];
            }
            var n = (((m + 2) / 5) * 5).format("%d");
            return ["Low in about " + n + " min", PURPLE, "Low in " + n + " min"];
        }
        var s = slope();
        if (g > 10.0) {
            if (s != null && s <= -0.02) { return ["High, coming down", AMBER, "High, easing"]; }
            if (s != null && s >= 0.02) { return ["High and rising", AMBER, "High, rising"]; }
            return ["High", AMBER, "High"];
        }
        if (s != null && s >= 0.02) {
            var up = (10.0 - g) / s;
            if (up <= 90) {
                var k = (((up.toNumber() + 2) / 5) * 5).format("%d");
                return ["High in about " + k + " min", AMBER, "High in " + k + " min"];
            }
            return ["Heading up", TEAL, "Rising"];
        }
        if (s != null && s <= -0.02) {
            return ["Easing down", TEAL, "Easing down"];
        }
        return ["Steady in range", TEAL, "In range"];
    }

    static function rateText() {
        var s = slope();
        if (s == null) { return ""; }
        var per15 = s * 15.0;
        var sign = per15 >= 0 ? "+" : "-";
        var a = per15 < 0 ? -per15 : per15;
        return sign + a.format("%.1f") + " per 15 min";
    }
}

(:glance)
class NumberGlance extends WatchUi.GlanceView {

    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var d = NumberText.line();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        if (d == null || d[:minutes] > 15) {
            dc.drawText(0, h / 2, Graphics.FONT_GLANCE, "no data",
                Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }
        var text = d[:glucose].format("%.1f") + "  " + NumberText.trendWord(d[:trend]);
        dc.drawText(0, h * 0.30, Graphics.FONT_GLANCE, text,
            Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        try {
            var m = NumberSeries.message(d[:glucose], d[:trend], d[:minutes]);
            dc.setColor(m[1], Graphics.COLOR_TRANSPARENT);
            dc.drawText(0, h * 0.74, Graphics.FONT_GLANCE, m[2],
                Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        } catch (ex) {
            // the number above is enough if the history is odd
        }
    }
}

class WidgetNumberView extends WatchUi.View {

    const SPANS = [60, 180, 360];
    var spanIndex = 1;
    // The vertical range: 2 to 12 normally, taller when the readings go outside it, so nothing is clipped.
    var yMin = 2.0;
    var yMax = 12.0;

    function initialize() {
        View.initialize();
    }

    function onShow() {
        fetch();
    }

    // Up and Down change how much history the chart shows: 1, 3 or 6 hours.
    function changeSpan(by) {
        spanIndex = (spanIndex + by + SPANS.size()) % SPANS.size();
        WatchUi.requestUpdate();
    }

    // Ask the phone for a fresh state right now, then the history.
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
        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_GET,
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
        };
        Communications.makeWebRequest("http://127.0.0.1:17590/history.json", {}, options, method(:onHistory));
    }

    function onHistory(code as Number, data as Dictionary or String or Null) as Void {
        if (code == 200 && data instanceof Lang.Dictionary) {
            Application.Storage.setValue("hist", data);
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
        if (!fresh) {
            dc.setColor(0x9AA0A6, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Graphics.FONT_MEDIUM, "no data",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }
        try {
            draw(dc, w, h, d);
        } catch (ex) {
            // never take the widget down for a bad history: just the number
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Graphics.FONT_NUMBER_THAI_HOT, d[:glucose].format("%.1f"),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    function draw(dc, w, h, d) {
        var g = d[:glucose];
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 0.14, Graphics.FONT_NUMBER_MEDIUM, g.format("%.1f"),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var rate = NumberSeries.rateText();
        var word = NumberText.trendWord(d[:trend]);
        dc.setColor(0xB0B4B8, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 0.265, Graphics.FONT_XTINY, word + (rate.length() > 0 ? "  " + rate : ""),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        var span = SPANS[spanIndex];
        var future = 30;
        var left = w * 0.12;
        var right = w * 0.88;
        var top = h * 0.31;
        var bottom = h * 0.56;
        var pts = NumberSeries.points();
        yMin = 2.0;
        yMax = 12.0;
        if (pts != null) {
            for (var k = 0; k < pts.size(); k++) {
                if (pts[k][0] < -span) { continue; }
                var pv = pts[k][1];
                if (pv + 0.3 > yMax) { yMax = Math.ceil(pv + 0.3); }
                if (pv - 0.3 < yMin) { yMin = Math.floor(pv - 0.3); }
            }
        }
        if (pts != null && pts.size() > 1) {
            // the target band and the low line
            dc.setColor(NumberSeries.BAND, Graphics.COLOR_TRANSPARENT);
            var yTop = yOf(10.0, top, bottom);
            var yLow = yOf(NumberSeries.LOW, top, bottom);
            dc.fillRectangle(left, yTop, right - left, yLow - yTop);
            dc.setColor(NumberSeries.PURPLE, Graphics.COLOR_TRANSPARENT);
            dashed(dc, left, yLow, right, yLow, 3, 4);
            // the dots
            var r = span <= 60 ? 4 : (span <= 180 ? 3 : 2);
            for (var i = 0; i < pts.size(); i++) {
                var t = pts[i][0];
                if (t < -span) { continue; }
                var x = left + (t + span) / (span + future * 1.0) * (right - left);
                dc.setColor(NumberSeries.colour(pts[i][1]), Graphics.COLOR_TRANSPARENT);
                dc.fillCircle(x, yOf(pts[i][1], top, bottom), r);
            }
            // where it goes if it carries on
            var s = NumberSeries.slope();
            if (s != null && (s > 0.012 || s < -0.012)) {
                var xNow = left + span / (span + future * 1.0) * (right - left);
                var endT = future;
                var endV = g + s * future;
                var hitsLow = false;
                if (s < 0 && endV < NumberSeries.LOW) {
                    endT = (g - NumberSeries.LOW) / (-s);
                    endV = NumberSeries.LOW;
                    hitsLow = true;
                }
                var xEnd = left + (span + endT) / (span + future * 1.0) * (right - left);
                dc.setColor(hitsLow ? NumberSeries.PURPLE : 0xBBBBBB, Graphics.COLOR_TRANSPARENT);
                dc.setPenWidth(2);
                dashed(dc, xNow, yOf(g, top, bottom), xEnd, yOf(endV, top, bottom), 4, 4);
                dc.setPenWidth(1);
                if (hitsLow) {
                    dc.drawCircle(xEnd, yOf(endV, top, bottom), 5);
                }
            }
            // now, and how far back
            dc.setColor(0x777777, Graphics.COLOR_TRANSPARENT);
            var xn = left + span / (span + future * 1.0) * (right - left);
            dc.drawLine(xn, top, xn, bottom);
            dc.setColor(0x888888, Graphics.COLOR_TRANSPARENT);
            dc.drawText(left, bottom + 4, Graphics.FONT_XTINY, "-" + (span / 60).format("%d") + "h", Graphics.TEXT_JUSTIFY_LEFT);
            dc.drawText(xn, bottom + 4, Graphics.FONT_XTINY, "now", Graphics.TEXT_JUSTIFY_CENTER);
        } else {
            dc.setColor(0x888888, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, (top + bottom) / 2, Graphics.FONT_XTINY, "waiting for history", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        var m = NumberSeries.message(g, d[:trend], d[:minutes]);
        dc.setColor(m[1], Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 0.725, fitFont(dc, m[0], w * 0.82), m[0], Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        legend(dc, w, h * 0.865);
    }

    // The biggest of three sizes the text fits in.
    function fitFont(dc, text, maxW) {
        if (dc.getTextWidthInPixels(text, Graphics.FONT_SMALL) <= maxW) { return Graphics.FONT_SMALL; }
        if (dc.getTextWidthInPixels(text, Graphics.FONT_TINY) <= maxW) { return Graphics.FONT_TINY; }
        return Graphics.FONT_XTINY;
    }

    function yOf(v, top, bottom) {
        var c = v;
        if (c < yMin) { c = yMin; }
        if (c > yMax) { c = yMax; }
        return bottom - (c - yMin) / (yMax - yMin) * (bottom - top);
    }

    // A dashed line from (x1, y1) to (x2, y2): dash pixels on, gap pixels off.
    function dashed(dc, x1, y1, x2, y2, dash, gap) {
        var dx = x2 - x1;
        var dy = y2 - y1;
        var len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) { return; }
        var ux = dx / len;
        var uy = dy / len;
        var p = 0.0;
        while (p < len) {
            var q = p + dash;
            if (q > len) { q = len; }
            dc.drawLine(x1 + ux * p, y1 + uy * p, x1 + ux * q, y1 + uy * q);
            p = q + gap;
        }
    }

    // low, in range, high, centred.
    function legend(dc, w, y) {
        var labels = ["low", "in range", "high"];
        var colours = [NumberSeries.PURPLE, NumberSeries.TEAL, NumberSeries.AMBER];
        var total = 0;
        var widths = [];
        for (var i = 0; i < 3; i++) {
            var tw = dc.getTextWidthInPixels(labels[i], Graphics.FONT_XTINY);
            widths.add(tw);
            total += 10 + tw + 12;
        }
        var x = (w - total) / 2 + 4;
        for (var i = 0; i < 3; i++) {
            dc.setColor(colours[i], Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(x + 3, y, 3);
            dc.setColor(0xAAAAAA, Graphics.COLOR_TRANSPARENT);
            dc.drawText(x + 10, y, Graphics.FONT_XTINY, labels[i], Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
            x += 10 + widths[i] + 12;
        }
    }
}

class WidgetNumberDelegate extends WatchUi.BehaviorDelegate {

    var view;

    function initialize(v) {
        BehaviorDelegate.initialize();
        view = v;
    }

    // Select (the top right button or a tap) asks again.
    function onSelect() {
        view.fetch();
        return true;
    }

    // Down and Up: 1, 3 or 6 hours.
    function onNextPage() {
        view.changeSpan(1);
        return true;
    }

    function onPreviousPage() {
        view.changeSpan(-1);
        return true;
    }
}
