package io.astra.apps.express;

import io.astra.spi.integration.CarrierAdapter;
import io.astra.spi.integration.EInvoiceProvider;
import io.astra.spi.release.SlotContract;
import jakarta.enterprise.context.ApplicationScoped;

/** The two market-integration slots of Astra Express, filled by Tier 1 plugins released at run time. */
final class IntegrationSlots {

    private IntegrationSlots() { }

    /** {@code carrier.booking}: a last-mile carrier (3PL) books the waybill. */
    @ApplicationScoped
    static class Carrier implements SlotContract {
        @Override public String slot() { return CarrierBookingJob.SLOT; }
        @Override public String tier() { return "isolated-jvm"; }
        @Override public Class<?> spi() { return CarrierAdapter.class; }
    }

    /** {@code einvoice.issue}: the tenant's country plugin issues the electronic invoice. */
    @ApplicationScoped
    static class EInvoice implements SlotContract {
        @Override public String slot() { return EInvoiceJob.SLOT; }
        @Override public String tier() { return "isolated-jvm"; }
        @Override public Class<?> spi() { return EInvoiceProvider.class; }
    }
}
