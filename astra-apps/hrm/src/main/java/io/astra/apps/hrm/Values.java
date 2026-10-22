package io.astra.apps.hrm;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reading document values: JSON numbers arrive as Integer, Long, Double or String, dates as ISO strings. */
final class Values {

    private Values() { }

    static double number(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A Currency field in whole VND. */
    static BigDecimal vnd(Object v) {
        double d = number(v);
        if (Double.isNaN(d)) throw new DocValidationException("'" + v + "' is not an amount");
        return BigDecimal.valueOf(d).setScale(0, RoundingMode.HALF_UP);
    }

    /** A Date field, or null when empty; rejects anything that is not {@code yyyy-MM-dd}. */
    static LocalDate date(Document doc, String field) {
        String v = doc.getString(field);
        if (v == null || v.isBlank()) return null;
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw new DocValidationException("field '" + field + "' must be a date (yyyy-MM-dd), got '" + v + "'");
        }
    }

    static LocalDate requireDate(Document doc, String field) {
        LocalDate d = date(doc, field);
        if (d == null) throw new DocValidationException("field '" + field + "' is required");
        return d;
    }

    /** Monday to Saturday: the six-day week common at Vietnamese couriers. Public holidays are not modelled. */
    static boolean workingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    static int workingDays(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) if (workingDay(d)) n++;
        return n;
    }

    /** The {@code yyyy-MM} keys a period spans, matching the projected {@code month} fields. */
    static List<String> months(LocalDate start, LocalDate end) {
        List<String> out = new ArrayList<>();
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) out.add(m.toString());
        return out;
    }

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    static String plain(double v) {
        return plain(BigDecimal.valueOf(v));
    }

    /** Rows of a JSON child-table field: a list of objects, each with a non-blank {@code component}. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Document doc, String field) {
        Object v = doc.get(field);
        if (v == null) return List.of();
        if (!(v instanceof List<?> list)) throw new DocValidationException("field '" + field + "' must be a list of rows");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> m) || m.get("component") == null || m.get("component").toString().isBlank()) {
                throw new DocValidationException("field '" + field + "': every row needs a component");
            }
            out.add((Map<String, Object>) m);
        }
        return out;
    }
}
