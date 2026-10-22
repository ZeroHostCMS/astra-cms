package io.astra.apps.stms;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;

/** Field rules of the routing master data, checked on every save so the solver never sees a malformed input. */
public final class MasterDataHooks {

    private MasterDataHooks() { }

    @ApplicationScoped
    public static class HubHook implements DocLifecycleHook {
        @Override public String doctype() { return "Hub"; }

        @Override public void beforeSave(Document doc) {
            Values.requireLatLon(Values.number(doc.get("latitude"), "latitude"), Values.number(doc.get("longitude"), "longitude"));
            if (Values.minutes(doc.get("open_from"), "open_from") >= Values.minutes(doc.get("open_to"), "open_to")) {
                throw new DocValidationException("a hub must open before it closes");
            }
        }
    }

    @ApplicationScoped
    public static class VehicleHook implements DocLifecycleHook {
        @Override public String doctype() { return "Vehicle"; }

        @Override public void beforeSave(Document doc) {
            double capacity = Values.number(doc.get("capacity_kg"), "capacity_kg");
            if (capacity <= 0 || capacity > 40_000) throw new DocValidationException("capacity_kg must be in (0, 40000]");
        }
    }

    @ApplicationScoped
    public static class DeliveryStopHook implements DocLifecycleHook {
        @Override public String doctype() { return "DeliveryStop"; }

        @Override public void beforeSave(Document doc) {
            Values.requireLatLon(Values.number(doc.get("latitude"), "latitude"), Values.number(doc.get("longitude"), "longitude"));
            double demand = Values.number(doc.get("demand_kg"), "demand_kg");
            if (demand < 0 || demand > 40_000) throw new DocValidationException("demand_kg must be in [0, 40000]");
            int from = Values.minutes(doc.get("window_from"), "window_from");
            int to = Values.minutes(doc.get("window_to"), "window_to");
            if (from >= to) throw new DocValidationException("window_from must be before window_to");
            double service = Values.number(doc.get("service_minutes"), "service_minutes", 5);
            if (service < 0 || service > 240) throw new DocValidationException("service_minutes must be in [0, 240]");
            // normalise "8h30" to "08:30" so filters and the Desk see one format
            doc.set("window_from", Values.clock(from)).set("window_to", Values.clock(to));
        }
    }
}
