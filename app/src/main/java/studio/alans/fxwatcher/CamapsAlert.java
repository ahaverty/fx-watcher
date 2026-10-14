package studio.alans.fxwatcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one of CamAPS's one-off notifications (or the same message as an in-app popup) is about.
 * Texts seen so far (CamAPS 192, mmol/L, English):
 *   Alert:       "Very low level of insulin in reservoir. \n9.8 U of insulin left in reservoir."
 *   Information: "Unable to connect to your pump. ... nearby, turn off and then turn on Bluetooth
 *                 on your smart device ... persists, restart your smart device"
 *   Information: "Sensor expires in less than 24 hours. ..." (SensorExpiry)
 *   Information: "Delivered 1.3 U of insulin." (BolusTracker)
 * Anything else is kind "other" and still goes to Nightscout and Home Assistant with its text,
 * so new messages show up. Pure, so it can be tested off the phone.
 */
final class CamapsAlert {

    static final String RESERVOIR_LOW = "reservoir_low";
    static final String RESERVOIR_VERY_LOW = "reservoir_very_low";
    static final String PUMP_CONNECT = "pump_connect";
    static final String SENSOR_EXPIRY = "sensor_expiry";
    static final String DELIVERED = "delivered";
    static final String OTHER = "other";

    private static final Pattern LEFT = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*U of insulin left", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOW_LEVEL = Pattern.compile(
            "(very\\s+)?low level of insulin", Pattern.CASE_INSENSITIVE);

    final String kind;
    final boolean alert;     // CamAPS titled it "Alert" (not "Information")
    final double units;      // insulin left in the reservoir, NaN if not given

    private CamapsAlert(String kind, boolean alert, double units) {
        this.kind = kind;
        this.alert = alert;
        this.units = units;
    }

    /** Classifies a CamAPS message; never null. */
    static CamapsAlert parse(String title, String text) {
        String t = text == null ? "" : text;
        boolean alert = title != null && title.trim().equalsIgnoreCase("Alert");
        double units = Double.NaN;
        Matcher left = LEFT.matcher(t);
        if (left.find()) units = Double.parseDouble(left.group(1).replace(',', '.'));
        Matcher low = LOW_LEVEL.matcher(t);
        if (low.find()) return new CamapsAlert(low.group(1) != null ? RESERVOIR_VERY_LOW : RESERVOIR_LOW, alert, units);
        if (!Double.isNaN(units)) return new CamapsAlert(RESERVOIR_LOW, alert, units);
        if (t.toLowerCase(java.util.Locale.UK).contains("unable to connect to your pump")) {
            return new CamapsAlert(PUMP_CONNECT, alert, units);
        }
        if (SensorExpiry.parse(t, 0) != null) return new CamapsAlert(SENSOR_EXPIRY, alert, units);
        if (BolusTracker.units(t) >= 0) return new CamapsAlert(DELIVERED, alert, units);
        return new CamapsAlert(OTHER, alert, units);
    }

    boolean reservoir() {
        return RESERVOIR_LOW.equals(kind) || RESERVOIR_VERY_LOW.equals(kind);
    }

    /** The reservoir as last known: units, when, and where it was read. */
    static final class Reservoir {
        final double units;
        final long at;
        final String source;    // "alert" (CamAPS low reservoir message) or "panel" (home info panel)

        Reservoir(double units, long at, String source) {
            this.units = units;
            this.at = at;
            this.source = source;
        }

        String save() {
            return units + "," + at + "," + source;
        }

        static Reservoir load(String s) {
            try {
                String[] a = s.split(",");
                return new Reservoir(Double.parseDouble(a[0]), Long.parseLong(a[1]), a[2]);
            } catch (Exception e) {
                return null;
            }
        }

        /** Whether a new reading should replace this one: newer, and not the same value. */
        boolean changedBy(double u, long when) {
            return when > at && Math.abs(u - units) >= 0.05;
        }
    }
}
