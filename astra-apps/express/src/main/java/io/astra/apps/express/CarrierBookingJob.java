package io.astra.apps.express;

import io.astra.spi.app.PluginException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.integration.CarrierAdapter;
import io.astra.spi.integration.CarrierAdapter.Booking;
import io.astra.spi.integration.CarrierAdapter.Shipment;
import io.astra.spi.job.TenantJob;
import io.astra.spi.release.Releases;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Books pending waybills with the carrier plugin the tenant's releases select ({@code carrier.booking}). Each pod
 * claims a waybill by moving it Pending → Booking (one pod wins), calls the plugin outside any user transaction,
 * and records the answer with {@code booked_by = plugin@version on pod}. A plugin failure leaves the waybill
 * Pending with the reason, to be retried on the next tick by whichever release serves then.
 */
@ApplicationScoped
public class CarrierBookingJob implements TenantJob {

    static final String SLOT = "carrier.booking";
    private static final Duration STALE_CLAIM = Duration.ofSeconds(30);

    @Inject DocumentService docs;
    @Inject Releases releases;

    @Override
    public String name() { return "carrier-booking"; }

    @Override
    public void run() {
        List<Document> waiting = new ArrayList<>(docs.find("Waybill", Map.of("carrier_status", "Pending"), 25));
        docs.find("Waybill", Map.of("carrier_status", "Booking"), 25).stream()
                .filter(w -> w.modifiedAt().isBefore(Instant.now().minus(STALE_CLAIM)))   // a pod died mid-booking
                .forEach(waiting::add);
        for (Document waybill : waiting) {
            if ("Cancelled".equals(waybill.getString("ops_status"))) continue;
            SystemFields.claim(docs, waybill, Map.of("carrier_status", "Booking")).ifPresent(this::book);
        }
    }

    private void book(Document waybill) {
        Optional<Document> order = docs.get("CustomerOrder", waybill.getString("order"));
        String customer = order.map(o -> o.getString("customer")).orElse(null);
        Optional<Releases.Bound<CarrierAdapter>> carrier = releases.plugin(SLOT, customer, CarrierAdapter.class);
        if (carrier.isEmpty()) {
            pending(waybill, "pending (no carrier release)");
            return;
        }
        Shipment shipment = new Shipment(waybill.name(), waybill.getString("order"), customer,
                order.map(o -> o.getString("zone")).orElse("Domestic"), order.map(o -> o.getString("service")).orElse("Standard"),
                order.map(o -> Numbers.toDouble(o.get("weight_kg"))).orElse(1.0),
                order.map(o -> Math.round(Numbers.toDouble(o.get("cod_amount")))).orElse(0L));
        try {
            Booking b = carrier.get().target().book(shipment);
            SystemFields.write(docs, "Waybill", waybill.name(), Map.of(
                    "carrier_status", "Booked", "carrier", b.carrier(), "carrier_tracking", b.tracking(),
                    "carrier_eta", b.eta() == null ? "" : b.eta(), "booked_by", carrier.get().label()));
        } catch (PluginException e) {
            pending(waybill, "pending (" + carrier.get().label() + " failed: " + e.reason() + ")");
        }
    }

    private void pending(Document waybill, String why) {
        SystemFields.write(docs, "Waybill", waybill.name(), Map.of("carrier_status", "Pending", "booked_by", why));
    }
}
