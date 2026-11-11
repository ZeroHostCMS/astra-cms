package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A new waybill waits for a carrier: {@code carrier_status = Pending}, set inside the order's submit transaction.
 * The carrier is called later by {@link CarrierBookingJob}, never while that transaction is open.
 */
@ApplicationScoped
public class CarrierBookingHook implements DocLifecycleHook {

    @Override
    public String doctype() { return "Waybill"; }

    @Override
    public void beforeSave(Document waybill) {
        if (waybill.name() == null && waybill.get("carrier_status") == null) waybill.set("carrier_status", "Pending");
    }
}
