package io.astra.doctype.store;

import io.astra.spi.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Naming series such as {@code ART-.#####} or {@code ORD-.YYYYMMDD.-.#####}: literal parts and date
 * tokens ({@code YYYY}, {@code YY}, {@code MM}, {@code DD}) separated by dots, then a zero-padded
 * counter whose width is the number of {@code #}. Counters live per tenant in {@code astra_series},
 * keyed by the pattern with its date tokens resolved, so dated series restart every period. The row
 * lock taken by the upsert makes the sequence gap-free within the caller's transaction and safe across pods.
 */
@ApplicationScoped
public class SeriesService {

    private static final Pattern DATE_TOKEN = Pattern.compile("YYYY|YY|MM|DD");
    private static final Pattern DATE_PART = Pattern.compile("(YYYY|YY|MM|DD)+");

    String next(Connection conn, String pattern) throws SQLException {
        return next(conn, pattern, LocalDate.now());
    }

    String next(Connection conn, String pattern, LocalDate today) throws SQLException {
        int hashAt = pattern.indexOf('#');
        String head = hashAt < 0 ? pattern : pattern.substring(0, hashAt);
        String resolvedHead = resolveDates(head, today);
        String prefix = resolvedHead.replace(".", "");
        int width = hashAt < 0 ? 5 : pattern.length() - hashAt;
        String key = resolvedHead + (hashAt < 0 ? "" : pattern.substring(hashAt));

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO astra_series (tenant_id, series_key, current) VALUES (?, ?, 1) "
                        + "ON CONFLICT (tenant_id, series_key) DO UPDATE SET current = astra_series.current + 1 "
                        + "RETURNING current")) {
            ps.setString(1, TenantContext.require());
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return prefix + String.format("%0" + width + "d", rs.getLong(1));
            }
        }
    }

    /** Resolves a dot-separated part only when it consists entirely of date tokens, so literals like {@code ADD-} stay intact. */
    static String resolveDates(String head, LocalDate d) {
        return Arrays.stream(head.split("\\.", -1))
                .map(part -> DATE_PART.matcher(part).matches() ? DATE_TOKEN.matcher(part).replaceAll(t -> switch (t.group()) {
                    case "YYYY" -> String.format("%04d", d.getYear());
                    case "YY" -> String.format("%02d", d.getYear() % 100);
                    case "MM" -> String.format("%02d", d.getMonthValue());
                    default -> String.format("%02d", d.getDayOfMonth());
                }) : part)
                .collect(Collectors.joining("."));
    }
}
