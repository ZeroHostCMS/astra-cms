package io.astra.apps.express;

import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.DocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** MCP tool: 30 days of operations in numbers, where an analyst (or an agent) starts looking for anomalies. */
@ApplicationScoped
public class OpsSnapshotTool implements McpTool {

    @Inject DocumentService docs;

    @Override public String name() { return "ops_snapshot"; }

    @Override public String description() {
        return "Operations of the last 30 days, aggregated: weight measured at each hub versus the weight declared on the order "
                + "(scans, average difference, parcels more than 30 % heavier), COD payouts to shops (count, paid at least 10 % "
                + "less than owed, paid more than 7 days after collection) and failed deliveries per courier (attempts, failures, rate). "
                + "These are the event kinds 1 (hub scan), 2 (COD payout) and 3 (failed delivery) of the ops.anomaly ABI.";
    }

    @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }

    @Override public List<String> reads() { return List.of("Waybill", "CODRemittance"); }

    @Override public Object call(Map<String, Object> args) {
        List<OpsEvents.Event> events = OpsEvents.load(docs);
        Map<String, Map<String, Object>> hubs = new TreeMap<>();
        Map<String, Map<String, Object>> couriers = new TreeMap<>();
        int payouts = 0;
        int short10 = 0;
        int late7 = 0;
        long shortfall = 0;
        for (OpsEvents.Event e : events) {
            switch (e.kind()) {
                case WEIGHT -> {
                    Map<String, Object> h = hubs.computeIfAbsent(e.group(), k -> new LinkedHashMap<>(Map.of("scans", 0, "sum_diff_pct", 0.0, "over_30_pct", 0)));
                    double diff = 100.0 * (e.b() - e.a()) / e.a();
                    h.merge("scans", 1, (x, y) -> (int) x + (int) y);
                    h.merge("sum_diff_pct", diff, (x, y) -> (double) x + (double) y);
                    if (diff > 30) h.merge("over_30_pct", 1, (x, y) -> (int) x + (int) y);
                }
                case COD -> {
                    payouts++;
                    if (e.b() * 100 < e.a() * 90) { short10++; shortfall += e.a() - e.b(); }
                    if (e.c() > 7) late7++;
                }
                case DELIVERY -> couriers.computeIfAbsent(e.group(), k -> new LinkedHashMap<>()).merge("failures", 1, (x, y) -> (int) x + (int) y);
            }
        }
        hubs.values().forEach(h -> h.put("avg_diff_pct", Math.round((double) h.remove("sum_diff_pct") / (int) h.get("scans") * 10) / 10.0));
        // attempts per courier over the 30 days, for the failure rate
        docs.find("Waybill", Map.of("created_at__gte", java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS).toString()), OpsEvents.MAX)
                .forEach(w -> {
                    String c = w.getString("courier");
                    if (c == null) return;
                    if (OpsEvents.failed(w) || "Delivered".equals(w.getString("ops_status"))) {
                        couriers.computeIfAbsent(c, k -> new LinkedHashMap<>()).merge("attempts", 1, (x, y) -> (int) x + (int) y);
                    }
                });
        couriers.values().forEach(c -> {
            int attempts = (int) c.getOrDefault("attempts", 0);
            int failures = (int) c.getOrDefault("failures", 0);
            c.put("failures", failures);
            c.put("failure_rate", attempts == 0 ? 0 : Math.round(1000.0 * failures / attempts) / 1000.0);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_days", 30);
        out.put("hub_weight_checks", hubs);
        out.put("cod_payouts", Map.of("payouts", payouts, "paid_under_90_pct", short10, "paid_after_7_days", late7, "shortfall_vnd", shortfall));
        out.put("deliveries_by_courier", couriers);
        return out;
    }
}
