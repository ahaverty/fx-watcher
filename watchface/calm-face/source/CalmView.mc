import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;
import Toybox.WatchUi;

class CalmView extends WatchUi.WatchFace {

    const STALE_MIN = 15;
    const GREY = 0x9AA0A6;

    var asleep = false;

    function initialize() {
        WatchFace.initialize();
    }

    function onEnterSleep() {
        asleep = true;
        WatchUi.requestUpdate();
    }

    function onExitSleep() {
        asleep = false;
        WatchUi.requestUpdate();
    }

    // The ring colour and the word. Never a number.
    function look() {
        var s = Application.Storage.getValue("state");
        var fetched = Application.Storage.getValue("fetched");
        if (s == null || fetched == null) {
            return [GREY, "no data"];
        }
        var age = s["age_min"];
        if (age == null || age < 0) {
            return [GREY, "no data"];
        }
        var minutes = age + (Time.now().value() - fetched) / 60;
        if (minutes > STALE_MIN) {
            return [GREY, "no data"];
        }
        var colour = colourOf(s["state"], s["colour"]);
        var word = s["word"];
        if (word == null) {
            word = "";
        }
        return [colour, word.toLower()];
    }

    // Use the phone's colour if it parses, else the colour for the state.
    function colourOf(state, hex) {
        if (hex != null && hex.length() == 7) {
            var n = hex.substring(1, 7).toNumberWithBase(16);
            if (n != null) {
                return n;
            }
        }
        if (state != null) {
            if (state.equals("handled")) { return 0xF2A33A; }
            if (state.equals("check")) { return 0xF26B5B; }
            if (state.equals("low_soon") || state.equals("treating")) { return 0x8A63D2; }
        }
        return 0x1FA89A;
    }

    function onUpdate(dc) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;
        var cy = h / 2;

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var l = look();
        var colour = l[0];
        var word = l[1];

        // The ring. Thinner and dimmer when the watch is asleep (AMOLED).
        dc.setColor(asleep ? dim(colour) : colour, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(asleep ? 4 : 14);
        dc.drawCircle(cx, cy, cx - 9);

        // Time
        var clock = System.getClockTime();
        var hour = clock.hour;
        if (!System.getDeviceSettings().is24Hour) {
            hour = hour % 12;
            if (hour == 0) { hour = 12; }
        }
        var time = hour.format("%02d") + ":" + clock.min.format("%02d");
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy - (h * 0.08), Graphics.FONT_NUMBER_HOT, time,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Date, small, above the time
        var now = Gregorian.info(Time.now(), Time.FORMAT_MEDIUM);
        var date = now.day_of_week + " " + now.day + " " + now.month;
        dc.setColor(0xB0B4B8, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy - (h * 0.30), Graphics.FONT_XTINY, date,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // The one word, in the ring's colour, below the time (hidden asleep to save the screen)
        if (!asleep) {
            dc.setColor(colour, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, cy + (h * 0.22), Graphics.FONT_SMALL, word,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    // Half brightness, for the always-on screen.
    function dim(c) {
        var r = ((c >> 16) & 0xFF) / 2;
        var g = ((c >> 8) & 0xFF) / 2;
        var b = (c & 0xFF) / 2;
        return (r << 16) | (g << 8) | b;
    }
}
