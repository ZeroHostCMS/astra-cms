package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The tenant's operational events of the last 30 days, in the shape of the {@code ops.anomaly} ABI, for the snapshot
 * and backtest tools: each event carries the same five numbers the live hooks pass to {@code check}, its document,
 * and the label of a planted anomaly when the demo seed data has one.
 */
final class OpsEvents {

    static final int MAX = 20_000;

    record Event(AnomalyDetector.Kind kind, long a, long b, long c, String ref, String detail, String label, String group) { }

    private OpsEvents() { }

    static List<Event> load(DocumentService docs) {
        String since = Instant.now().minus(30, ChronoUnit.DAYS).toString();
        List<Event> out = new ArrayList<>();
        List<Document> parcels = docs.find("Waybill", Map.of("created_at__gte", since), MAX);
        for (Document w : parcels) {
            double declared = Numbers.toDouble(w.get("declared_weight_kg"));
            double measured = Numbers.toDouble(w.get("measured_weight_kg"));
            if (declared > 0 && measured > 0) {
                out.add(new Event(AnomalyDetector.Kind.WEIGHT, Math.round(declared * 1000), Math.round(measured * 1000), 0, w.name(),
                        String.format("khai %.2f kg, cân %.2f kg", declared, measured), label(w, "weight"), String.valueOf(w.getString("current_hub"))));
            }
        }
        // failed deliveries, with the courier's failures and attempts in the 7 days before each one
        Map<String, List<Document>> byCourier = new HashMap<>();
        for (Document w : parcels) if (w.getString("courier") != null) byCourier.computeIfAbsent(w.getString("courier"), k -> new ArrayList<>()).add(w);
        for (var e : byCourier.entrySet()) {
            for (Document w : e.getValue()) {
                if (!failed(w)) continue;
                Instant at = w.modifiedAt();
                long fails = 0;
                long attempts = 0;
                for (Document o : e.getValue()) {
                    if (o == w || o.modifiedAt().isAfter(at) || o.modifiedAt().isBefore(at.minus(7, ChronoUnit.DAYS))) continue;
                    if (failed(o)) fails++;
                    if (failed(o) || "Delivered".equals(o.getString("ops_status"))) attempts++;
                }
                out.add(new Event(AnomalyDetector.Kind.DELIVERY, 1, fails + 1, attempts + 1, w.name(),
                        "bưu tá " + e.getKey() + ": " + (fails + 1) + "/" + (attempts + 1) + " thất bại trong 7 ngày", label(w, "courier"), e.getKey()));
            }
        }
        for (Document r : docs.find("CODRemittance", Map.of("created_at__gte", since), MAX)) {
            long owed = Math.round(Numbers.toDouble(r.get("outstanding")));
            long paid = Math.round(Numbers.toDouble(r.get("amount")));
            String until = r.getString("collected_until");
            long days = until == null || until.isBlank() ? 0
                    : ChronoUnit.DAYS.between(LocalDate.parse(until.substring(0, 10)), r.createdAt().atOffset(ZoneOffset.UTC).toLocalDate());
            out.add(new Event(AnomalyDetector.Kind.COD, owed, paid, days, r.name(),
                    String.format("%s: nợ %,d, chi %,d, sau %d ngày", r.getString("customer"), owed, paid, days), label(r, "cod"),
                    String.valueOf(r.getString("customer"))));
        }
        return out;
    }

    static boolean failed(Document w) {
        String s = w.getString("ops_status");
        return "Failed Delivery".equals(s) || "Returned".equals(s);
    }

    private static String label(Document d, String prefix) {
        String l = d.getString("seed_label");
        return l != null && l.startsWith(prefix) ? l : null;
    }
}
