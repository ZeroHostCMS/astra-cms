package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.job.TenantJob;
import io.astra.spi.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Keeps each customer's 30-day profile on the customer: submitted orders and the share of their parcels returned.
 * Customer pricing plugins read it (volume tiers, return-rate conditions). Recomputed every few minutes per tenant.
 */
@ApplicationScoped
public class CustomerProfileJob implements TenantJob {

    static final int MAX_ORDERS = 20_000;
    private static final long EVERY_MS = 5 * 60_000;

    @Inject DocumentService docs;

    private final Map<String, Long> lastRun = new ConcurrentHashMap<>();

    @Override
    public String name() { return "customer-profile"; }

    @Override
    public void run() {
        String tenant = TenantContext.require();
        long now = System.currentTimeMillis();
        Long last = lastRun.get(tenant);
        if (last != null && now - last < EVERY_MS) return;
        lastRun.put(tenant, now);
        refreshAll();
    }

    /** Recomputes every customer of the current tenant now. */
    public void refreshAll() {
        String since = Instant.now().minus(30, ChronoUnit.DAYS).toString();
        Set<String> returned = docs.find("Waybill", Map.of("ops_status", "Returned", "created_at__gte", since), MAX_ORDERS).stream()
                .map(Document::name).collect(Collectors.toSet());
        for (Document c : docs.find("Customer", Map.of(), 500)) {
            List<Document> orders = docs.find("CustomerOrder", Map.of("customer", c.name(), "docstatus", "1", "created_at__gte", since), MAX_ORDERS);
            long back = orders.stream().map(o -> o.getString("waybill")).filter(Objects::nonNull).filter(returned::contains).count();
            double rate = orders.isEmpty() ? 0 : Math.round(10_000.0 * back / orders.size()) / 10_000.0;
            if (Numbers.toDouble(c.get("monthly_orders")) == orders.size() && Numbers.toDouble(c.get("return_rate")) == rate) continue;
            SystemFields.write(docs, "Customer", c.name(), Map.of("monthly_orders", orders.size(), "return_rate", rate));
        }
    }
}
