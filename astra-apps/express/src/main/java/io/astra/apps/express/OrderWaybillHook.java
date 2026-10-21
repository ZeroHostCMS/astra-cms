package io.astra.apps.express;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

/**
 * Hands an order from OMS to OPS. Submitting an order creates its waybill; cancelling it cancels the
 * waybill, or is refused once the parcel has entered the network. Both run inside the order's own
 * transaction, so the two documents can never disagree.
 */
@ApplicationScoped
public class OrderWaybillHook implements DocLifecycleHook {

    private static final Set<String> CANCELLABLE = Set.of("Created", "Picked Up");

    @Inject DocumentService docs;

    @Override
    public String doctype() { return "CustomerOrder"; }

    @Override
    public void onSubmit(Document order) {
        Document waybill = docs.insert("Waybill", Map.of(
                "order", order.name(),
                "receiver_name", order.getString("receiver_name")), order.owner());
        order.set("waybill", waybill.name());
    }

    @Override
    public void onCancel(Document order) {
        String name = order.getString("waybill");
        if (name == null) return;
        Document waybill = docs.get("Waybill", name).orElseThrow(() -> new DocValidationException("waybill " + name + " not found"));
        String status = waybill.getString("ops_status");
        if (!CANCELLABLE.contains(status)) {
            throw new DocValidationException("cannot cancel " + order.name() + ": waybill " + name + " is already '" + status + "'");
        }
        docs.update("Waybill", name, Map.of("ops_status", "Cancelled"));
    }
}
