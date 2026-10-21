package io.astra.apps.express;

/** JSON numbers arrive as Integer, Long, Double or String depending on the client. */
final class Numbers {

    private Numbers() { }

    static double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
