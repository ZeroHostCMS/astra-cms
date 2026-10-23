package io.astra.doctype.store;

import io.astra.spi.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Naming series such as {@code ART-.#####} or {@code ORD-.YYYYMMDD.-.#####}: literal parts and date
 * tokens ({@code YYYY}, {@code YY}, {@code MM}, {@code DD}) separated by dots, then a zero-padded
 * counter whose width is the number of {@code #}. Counters live per tenant in {@code astra_series},
 * keyed by the pattern with its date tokens resolved, so dated series restart every period.
 *
 * <p>Gapless series (the default): the upsert's row lock makes the sequence gap-free within the caller's
 * transaction and safe across pods, and it is held until that transaction commits, so creations of one
 * series in one tenant are serialised (measured: about 300 orders per second per tenant, p95 200 ms, with
 * 16 concurrent writers; docs/benchmark-doctype-jsonb-the-right-way.md).
 *
 * <p>Series with {@code gapless: false}: each instance hands out numbers from blocks it reserved in their
 * own committed transactions ({@code current = current + block}), refilled in the background before they
 * run out. Creations do not touch the counter row at all while a block lasts. If a block is empty when a
 * number is needed, that creation increments the counter inside its own transaction as a gapless series
 * would, which stays correct because every reservation and every increment goes through the same row.
 */
@ApplicationScoped
public class SeriesService {

    private static final Logger LOG = Logger.getLogger(SeriesService.class);
    private static final Pattern DATE_TOKEN = Pattern.compile("YYYY|YY|MM|DD");
    private static final Pattern DATE_PART = Pattern.compile("(YYYY|YY|MM|DD)+");
    private static final String UPSERT = "INSERT INTO astra_series (tenant_id, series_key, current) VALUES (?, ?, ?) "
            + "ON CONFLICT (tenant_id, series_key) DO UPDATE SET current = astra_series.current + EXCLUDED.current "
            + "RETURNING current";

    @Inject TenantTransactions tx;

    @ConfigProperty(name = "astra.naming.block-size", defaultValue = "100")
    int blockSize;

    /** Reserved, not yet used numbers per tenant and resolved series key (non-gapless series only). */
    private final Map<String, Block> blocks = new ConcurrentHashMap<>();

    String next(Connection conn, String pattern) throws SQLException {
        return next(conn, pattern, true, LocalDate.now());
    }

    String next(Connection conn, String pattern, boolean gapless) throws SQLException {
        return next(conn, pattern, gapless, LocalDate.now());
    }

    String next(Connection conn, String pattern, LocalDate today) throws SQLException {
        return next(conn, pattern, true, today);
    }

    String next(Connection conn, String pattern, boolean gapless, LocalDate today) throws SQLException {
        int hashAt = pattern.indexOf('#');
        String head = hashAt < 0 ? pattern : pattern.substring(0, hashAt);
        String resolvedHead = resolveDates(head, today);
        String prefix = resolvedHead.replace(".", "");
        int width = hashAt < 0 ? 5 : pattern.length() - hashAt;
        String key = resolvedHead + (hashAt < 0 ? "" : pattern.substring(hashAt));
        String tenant = TenantContext.require();

        long number = -1;
        if (!gapless && blockSize > 1) {
            Block block = block(tenant, key);
            number = block.take();
            if (block.shouldRefill(blockSize)) refillInBackground(tenant, key, block);
        }
        if (number < 0) number = increment(conn, tenant, key, 1);
        return prefix + String.format("%0" + width + "d", number);
    }

    /** Adds {@code by} to the counter in the given transaction and returns the new value. */
    private static long increment(Connection conn, String tenant, String key, int by) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setString(1, tenant);
            ps.setString(2, key);
            ps.setLong(3, by);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private Block block(String tenant, String key) {
        if (blocks.size() > 10_000) blocks.clear(); // old periods' keys; dropping them only leaves gaps
        return blocks.computeIfAbsent(tenant + '\u0000' + key, k -> new Block());
    }

    /**
     * Reserves the next block in a transaction of its own, on a virtual thread: the caller never waits for it,
     * so a busy connection pool delays the refill but cannot deadlock against callers holding connections.
     */
    private void refillInBackground(String tenant, String key, Block block) {
        if (!block.startRefill()) return;
        Thread.ofVirtual().name("astra-series-refill").start(() -> {
            try {
                long last = TenantContext.call(tenant, () -> tx.inTenantTx(conn -> increment(conn, tenant, key, blockSize)));
                block.add(last - blockSize + 1, last);
            } catch (RuntimeException e) {
                LOG.warnf("Series %s of tenant %s: block not reserved (%s); creations increment the counter until it is", key, tenant, e.getMessage());
            } finally {
                block.endRefill();
            }
        });
    }

    /** Numbers reserved by this instance for one series key, as ranges in reservation order. */
    static final class Block {
        private final ArrayDeque<long[]> ranges = new ArrayDeque<>();
        private boolean refilling;

        /** The next reserved number, or -1 when none is left. */
        synchronized long take() {
            while (!ranges.isEmpty()) {
                long[] r = ranges.peekFirst();
                if (r[0] <= r[1]) return r[0]++;
                ranges.pollFirst();
            }
            return -1;
        }

        synchronized boolean shouldRefill(int blockSize) {
            if (refilling) return false;
            long left = 0;
            for (long[] r : ranges) left += Math.max(0, r[1] - r[0] + 1);
            return left < Math.max(1, blockSize / 4);
        }

        synchronized boolean startRefill() {
            if (refilling) return false;
            refilling = true;
            return true;
        }

        synchronized void endRefill() {
            refilling = false;
        }

        synchronized void add(long from, long to) {
            ranges.addLast(new long[] {from, to});
        }
    }

    private static final Map<String, Optional<Pattern>> NAME_DATE = new ConcurrentHashMap<>();

    /**
     * The date a series name was issued on, read back from the name: {@code ORD-20261023-0000001} for
     * {@code ORD-.YYYYMMDD.-.#######} is 2026-10-23, {@code WB-2610-00000001} for {@code WB-.YYMM.-.########} is
     * 2026-10-01. Empty when the pattern has no year or the name does not follow it. Amended names
     * ({@code <name>-1}) keep the date of the original.
     */
    static Optional<LocalDate> dateOf(String pattern, String name) {
        Optional<Pattern> regex = NAME_DATE.computeIfAbsent(pattern, SeriesService::nameRegex);
        if (regex.isEmpty() || name == null) return Optional.empty();
        java.util.regex.Matcher m = regex.get().matcher(name);
        if (!m.lookingAt()) return Optional.empty();
        try {
            int year = group(m, "Y4") != null ? Integer.parseInt(group(m, "Y4")) : 2000 + Integer.parseInt(group(m, "Y2"));
            int month = group(m, "M") != null ? Integer.parseInt(group(m, "M")) : 1;
            int day = group(m, "D") != null ? Integer.parseInt(group(m, "D")) : 1;
            return Optional.of(LocalDate.of(year, month, day));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String group(java.util.regex.Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException noSuchGroup) {
            return null;
        }
    }

    private static Optional<Pattern> nameRegex(String pattern) {
        int hashAt = pattern.indexOf('#');
        String head = hashAt < 0 ? pattern : pattern.substring(0, hashAt);
        if (!head.contains("YY")) return Optional.empty();
        StringBuilder re = new StringBuilder();
        for (String part : head.split("\\.", -1)) {
            if (DATE_PART.matcher(part).matches()) {
                java.util.regex.Matcher t = DATE_TOKEN.matcher(part);
                while (t.find()) {
                    re.append(switch (t.group()) {
                        case "YYYY" -> "(?<Y4>\\d{4})";
                        case "YY" -> "(?<Y2>\\d{2})";
                        case "MM" -> "(?<M>\\d{2})";
                        default -> "(?<D>\\d{2})";
                    });
                }
            } else {
                re.append(Pattern.quote(part));
            }
        }
        return Optional.of(Pattern.compile(re.toString()));
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
