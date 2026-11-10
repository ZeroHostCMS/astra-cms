package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parcel events for the anomaly detector: a hub scan with a measured weight (kind 1) and a failed delivery (kind 3).
 * A new waybill also records the weight the order declared, the baseline the hub's scale is compared with.
 */
@ApplicationScoped
public class ParcelAnomalyHook implements DocLifecycleHook {

    @Inject DocumentService docs;
    @Inject AnomalyDetector detector;

    @Override
    public String doctype() { return "Waybill"; }

    @Override
    public void beforeSave(Document waybill) {
        if (waybill.name() == null) {
            docs.get("CustomerOrder", waybill.getString("order"))
                    .ifPresent(o -> waybill.set("declared_weight_kg", Numbers.toDouble(o.get("weight_kg"))));
            return;
        }
        Document before = docs.get("Waybill", waybill.name()).orElse(null);
        if (before == null) return;
        String status = waybill.getString("ops_status");
        double measured = Numbers.toDouble(waybill.get("measured_weight_kg"));
        if ("At Hub".equals(status) && measured > 0 && measured != Numbers.toDouble(before.get("measured_weight_kg"))) {
            double declared = Numbers.toDouble(waybill.get("declared_weight_kg"));
            detector.check(AnomalyDetector.Kind.WEIGHT, Math.round(declared * 1000), Math.round(measured * 1000), 0, 0, "Waybill",
                    waybill.name(), String.format("khai %.2f kg, cân tại %s %.2f kg", declared, waybill.getString("current_hub"), measured));
        }
        if ("Failed Delivery".equals(status) && !status.equals(before.getString("ops_status"))) {
            String courier = waybill.getString("courier");
            long parcelFailures = 1 + failures(before.get("scan_log"));   // the stored history, whichever hook appends first
            long[] week = courier == null ? new long[] {0, 0} : courierWeek(courier, waybill.name());
            detector.check(AnomalyDetector.Kind.DELIVERY, parcelFailures, week[0] + 1, week[1] + 1, 0, "Waybill", waybill.name(),
                    "bưu tá " + courier + ": " + (week[0] + 1) + "/" + (week[1] + 1) + " lần giao thất bại trong 7 ngày; kiện thất bại lần " + parcelFailures);
        }
    }

    private static long failures(Object scanLog) {
        if (!(scanLog instanceof List<?> log)) return 0;
        return log.stream().filter(e -> e instanceof Map<?, ?> m && "Failed Delivery".equals(m.get("status"))).count();
    }

    /** {failed, attempted} deliveries of a courier in the last 7 days, other parcels than this one. */
    private long[] courierWeek(String courier, String self) {
        String since = Instant.now().minus(7, ChronoUnit.DAYS).toString();
        List<Document> parcels = docs.find("Waybill", Map.of("courier", courier, "modified_at__gte", since), 5_000);
        long failed = 0;
        long attempts = 0;
        for (Document p : parcels) {
            if (Objects.equals(p.name(), self)) continue;
            String s = p.getString("ops_status");
            if ("Failed Delivery".equals(s) || "Returned".equals(s)) failed++;
            if ("Failed Delivery".equals(s) || "Returned".equals(s) || "Delivered".equals(s)) attempts++;
        }
        return new long[] {failed, attempts};
    }
}
