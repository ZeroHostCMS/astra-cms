package io.astra.spi.integration;

import java.util.List;

/**
 * Tier 1 extension point (slot {@code einvoice.issue}): issues an electronic invoice with one country's tax authority.
 * Each country is its own plugin; a tenant pins the one for its country, so adding a country is adding a module.
 */
public interface EInvoiceProvider {

    /** ISO 3166-1 alpha-2 country code, for example {@code VN} or {@code KH}. */
    String country();

    IssuedInvoice issue(InvoiceRequest request);

    record Line(String description, long quantity, long unitPrice) { }

    /** Amounts are integers in the currency's minor unit: đồng for VND, cents for USD. */
    record InvoiceRequest(String order, String buyerName, String buyerTaxCode, String currency, List<Line> lines) { }

    record IssuedInvoice(String number, String lookupCode, String status, long subtotal, long vat, long total, String currency) { }
}
