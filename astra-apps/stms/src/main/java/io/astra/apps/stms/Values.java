package io.astra.apps.stms;

import io.astra.spi.doc.DocValidationException;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reading loosely typed document and tool-argument values (JSON numbers may arrive as strings). */
public final class Values {

    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})(?::|h)(\\d{2})?");

    private Values() { }

    public static double number(Object v, String field) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null || v.toString().isBlank()) throw new DocValidationException("'" + field + "' is required");
        try {
            return Double.parseDouble(v.toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new DocValidationException("'" + field + "' must be a number");
        }
    }

    public static double number(Object v, String field, double fallback) {
        return v == null || v.toString().isBlank() ? fallback : number(v, field);
    }

    public static String text(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || v.toString().isBlank()) throw new DocValidationException("'" + key + "' is required");
        return v.toString().trim();
    }

    public static boolean flag(Object v) {
        return v instanceof Boolean b ? b : v != null && ("true".equalsIgnoreCase(v.toString()) || "1".equals(v.toString()));
    }

    /** "08:30", "8:30" or "8h30" → minutes after midnight. */
    public static int minutes(Object v, String field) {
        if (v == null) throw new DocValidationException("'" + field + "' is required (HH:mm)");
        Matcher m = CLOCK.matcher(v.toString().trim());
        if (!m.matches()) throw new DocValidationException("'" + field + "' must be a time HH:mm, got '" + v + "'");
        int h = Integer.parseInt(m.group(1));
        int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) throw new DocValidationException("'" + field + "' must be a time HH:mm, got '" + v + "'");
        return h * 60 + min;
    }

    public static String clock(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    public static void requireLatLon(double lat, double lon) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new DocValidationException("latitude must be within ±90 and longitude within ±180");
        }
    }
}
