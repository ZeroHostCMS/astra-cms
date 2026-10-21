package io.astra.apps.express;

import io.astra.spi.app.AstraApp;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Astra Express: a courier demo (docs/use-case-01.md) that spans three modules of one business —
 * orders (oms), waybills and scans (ops), customer care (care), billing and COD (bms) — through the public SPI only.
 */
@ApplicationScoped
public class ExpressApp implements AstraApp {

    @Override
    public String id() { return "io.astra.express"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public List<String> doctypeResources() {
        return List.of(
                "META-INF/astra/doctypes/customer.doctype.yaml",
                "META-INF/astra/doctypes/customer-order.doctype.yaml",
                "META-INF/astra/doctypes/waybill.doctype.yaml",
                "META-INF/astra/doctypes/support-ticket.doctype.yaml",
                "META-INF/astra/doctypes/account.doctype.yaml",
                "META-INF/astra/doctypes/cod-remittance.doctype.yaml",
                "META-INF/astra/doctypes/pricing-rule.doctype.yaml");
    }
}
