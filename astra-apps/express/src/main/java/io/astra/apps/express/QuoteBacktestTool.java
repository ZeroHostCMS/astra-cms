package io.astra.apps.express;

import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.pricing.PriceLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * MCP tool: runs a draft customer pricing module (slot {@code pricing.customer}) on the customer's orders of the last
 * 30 days, in the sandbox, against today's rule layer, and reports the price per order before and after the module
 * and the revenue difference. Reads only; nothing is priced or saved.
 */
@ApplicationScoped
public class QuoteBacktestTool implements McpTool {

    static final int MAX_ORDERS = 3_000;

    @Inject DocumentService docs;
    @Inject LayeredPricing pricing;

    @Override public String name() { return "quote_backtest"; }

    @Override public String description() {
        return "Backtest a customer pricing module (Wasm, slot pricing.customer, built with astra-pricing-pdk) on the customer's "
                + "submitted orders of the last 30 days: each order priced by today's rule layer, then with the module applied. "
                + "Returns revenue before and after, the difference, how often each operation fired, and sample orders. "
                + "Nothing is saved.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                        "customer", Map.of("type", "string", "description", "customer code"),
                        "module_base64", Map.of("type", "string", "description", "the .wasm module, base64-encoded"),
                        "samples", Map.of("type", "integer", "description", "orders to list in detail (default 8)")),
                "required", List.of("customer", "module_base64"));
    }

    @Override public List<String> reads() { return List.of("Customer", "CustomerOrder"); }

    @Override public Object call(Map<String, Object> args) {
        String code = String.valueOf(args.get("customer"));
        Optional<Document> customer = docs.get("Customer", code);
        if (customer.isEmpty()) throw new IllegalArgumentException("no customer " + code);
        byte[] module = Base64.getDecoder().decode(String.valueOf(args.get("module_base64")).trim());
        int samples = args.get("samples") instanceof Number n ? n.intValue() : 8;
        String since = Instant.now().minus(30, ChronoUnit.DAYS).toString();
        List<Document> orders = docs.find("CustomerOrder", Map.of("customer", code, "docstatus", "1", "created_at__gte", since), MAX_ORDERS);
        LayeredPricing.Plugin draft = new LayeredPricing.Plugin(module, "draft", "backtest", null);
        Optional<Document> rule = pricing.activeRule(customer.get().getString("segment"));
        long before = 0;
        long after = 0;
        int refused = 0;
        Map<String, Integer> ops = new TreeMap<>();
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Document o : orders) {
            var q = ShippingFeeHook.quote(o, customer.get().getString("segment"));
            LayeredPricing.Base base = pricing.base(q, customer, rule);        // the rule layer once per order
            LayeredPricing.Priced ruled = pricing.finish(base, q, customer, LayeredPricing.Plugin.NONE);
            LayeredPricing.Priced with = pricing.finish(base, q, customer, draft);
            if (with.pricedBy().contains("refused")) refused++;
            before += ruled.total();
            after += with.total();
            for (PriceLine l : with.lines()) {
                if (l.op() != null && l.op().contains(" by wasm:")) {
                    ops.merge(l.op().substring(0, l.op().indexOf(' ')) + " " + l.code(), 1, Integer::sum);   // e.g. "scale base"
                }
            }
            if (detail.size() < samples) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("order", o.name());
                m.put("zone", o.getString("zone"));
                m.put("weight_kg", o.get("weight_kg"));
                m.put("rule_price", ruled.total());
                m.put("with_module", with.total());
                m.put("lines", LayeredPricing.breakdown(with.lines()).stream()
                        .filter(l -> l.containsKey("op")).map(l -> l.get("code") + ": " + l.get("before") + " → " + l.get("amount")
                                + (l.get("reason") == null ? "" : " (" + l.get("reason") + ")")).toList());
                detail.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customer", code);
        out.put("orders", orders.size());
        out.put("monthly_orders", customer.get().get("monthly_orders"));
        out.put("return_rate", customer.get().get("return_rate"));
        out.put("revenue_rule_layer_vnd", before);
        out.put("revenue_with_module_vnd", after);
        out.put("difference_vnd", after - before);
        out.put("difference_pct", before == 0 ? 0 : Math.round(10_000.0 * (after - before) / before) / 100.0);
        out.put("operations", ops);
        out.put("orders_where_module_was_refused", refused);
        out.put("samples", detail);
        return out;
    }
}
