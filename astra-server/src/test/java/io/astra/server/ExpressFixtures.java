package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.spi.tenant.TenantContext;

import java.util.List;
import java.util.Map;

/** Master data every Express test tenant needs, written straight through the kernel. */
final class ExpressFixtures {

    static final List<List<String>> CHART = List.of(
            List.of("112", "Tiền gửi ngân hàng", "Asset"),
            List.of("131", "Phải thu của khách hàng", "Asset"),
            List.of("1388", "Phải thu khác (COD bưu tá giữ)", "Asset"),
            List.of("3388", "Phải trả khác (COD trả shop)", "Liability"),
            List.of("511", "Doanh thu cung cấp dịch vụ", "Income"));

    private ExpressFixtures() { }

    /** Customer C001 and the chart of accounts, once per tenant. */
    static void tenant(DocumentStore store, String tenant) {
        TenantContext.run(tenant, () -> {
            if (store.get("Customer", "C001").isPresent()) return;
            store.insert("Customer", Map.of("code", "C001", "customer_name", "Shop Hoa Mai", "segment", "B2B"), "fixture");
            for (List<String> a : CHART) {
                store.insert("Account", Map.of("account_number", a.get(0), "account_name", a.get(1), "root_type", a.get(2)), "fixture");
            }
        });
    }
}
