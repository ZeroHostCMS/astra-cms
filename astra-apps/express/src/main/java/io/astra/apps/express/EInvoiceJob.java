package io.astra.apps.express;

import io.astra.spi.app.PluginException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.integration.EInvoiceProvider;
import io.astra.spi.integration.EInvoiceProvider.InvoiceRequest;
import io.astra.spi.integration.EInvoiceProvider.IssuedInvoice;
import io.astra.spi.integration.EInvoiceProvider.Line;
import io.astra.spi.job.TenantJob;
import io.astra.spi.release.Releases;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issues the electronic invoices of submitted orders with the tenant's country plugin ({@code einvoice.issue}, pinned
 * per tenant). Without a plugin the order stays {@code Pending (no provider)} and is picked up as soon as one is
 * approved and pinned: adding a country is adding a module, with no restart.
 */
@ApplicationScoped
public class EInvoiceJob implements TenantJob {

    static final String SLOT = "einvoice.issue";
    static final String NO_PROVIDER = "Pending (no provider)";

    @Inject DocumentService docs;
    @Inject Releases releases;

    @Override
    public String name() { return "einvoice"; }

    @Override
    public void run() {
        List<Document> waiting = new ArrayList<>(docs.find("CustomerOrder", Map.of("einvoice_status", "Pending", "docstatus", "1"), 25));
        Optional<Releases.Bound<EInvoiceProvider>> provider = releases.plugin(SLOT, null, EInvoiceProvider.class);
        if (provider.isPresent()) {
            waiting.addAll(docs.find("CustomerOrder", Map.of("einvoice_status", NO_PROVIDER, "docstatus", "1"), 25));
            // An order left "Issuing" by a pod that died mid-call is retried after a grace period (a successful issue
            // moves it to "Issued"). The sandbox tax service returns a fresh number each call, so a demo re-issue is harmless.
            docs.find("CustomerOrder", Map.of("einvoice_status", "Issuing", "docstatus", "1"), 25).stream()
                    .filter(o -> o.modifiedAt().isBefore(java.time.Instant.now().minus(java.time.Duration.ofSeconds(30))))
                    .forEach(waiting::add);
        }
        for (Document order : waiting) {
            if (provider.isEmpty()) {
                SystemFields.claim(docs, order, Map.of("einvoice_status", NO_PROVIDER));
                continue;
            }
            SystemFields.claim(docs, order, Map.of("einvoice_status", "Issuing")).ifPresent(o -> issue(o, provider.get()));
        }
    }

    /** Minor units to text: cents for USD, đồng for VND. */
    static String amount(long minor, String currency) {
        return "USD".equals(currency) ? String.format(java.util.Locale.ROOT, "%.2f USD", minor / 100.0) : minor + " " + currency;
    }

    private void issue(Document order, Releases.Bound<EInvoiceProvider> provider) {
        Document customer = docs.get("Customer", order.getString("customer")).orElse(null);
        long fee = Math.round(Numbers.toDouble(order.get("shipping_fee")));
        InvoiceRequest request = new InvoiceRequest(order.name(),
                customer == null ? order.getString("customer") : customer.getString("customer_name"),
                "", "VND",                                       // the demo customers carry no tax code
                List.of(new Line("Cước vận chuyển " + order.getString("zone") + " / " + order.getString("service"), 1, fee)));
        try {
            IssuedInvoice inv = provider.target().issue(request);
            SystemFields.write(docs, "CustomerOrder", order.name(), Map.of("einvoice_status", "Issued",
                    "einvoice_no", inv.number(), "einvoice_lookup", inv.lookupCode(),
                    "einvoice_by", provider.label() + " · " + amount(inv.total(), inv.currency())));
        } catch (PluginException e) {
            SystemFields.write(docs, "CustomerOrder", order.name(), Map.of("einvoice_status", "Pending",
                    "einvoice_by", provider.label() + " failed: " + e.reason()));
        }
    }
}
