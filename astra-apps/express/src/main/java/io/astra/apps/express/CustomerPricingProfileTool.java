package io.astra.apps.express;

import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** MCP tool: a customer's shipping profile over the last 30 days, what a pricing analyst reads before writing terms. */
@ApplicationScoped
public class CustomerPricingProfileTool implements McpTool {

    @Inject DocumentService docs;

    @Override public String name() { return "customer_pricing_profile"; }

    @Override public String description() {
        return "Shipping profile of one customer over the last 30 days: submitted orders (the volume that contract tiers count), "
                + "return rate, orders by zone and by weight band, share of COD parcels, parcels whose volumetric weight "
                + "(L×W×H/6000) exceeds the real weight, and the average fee the orders paid.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("customer", Map.of("type", "string", "description", "customer code, e.g. ABC")),
                "required", List.of("customer"));
    }

    @Override public List<String> reads() { return List.of("Customer", "CustomerOrder"); }

    @Override public Object call(Map<String, Object> args) {
        String code = String.valueOf(args.get("customer"));
        Document customer = docs.get("Customer", code).orElseThrow(() -> new IllegalArgumentException("no customer " + code));
        String since = Instant.now().minus(30, ChronoUnit.DAYS).toString();
        List<Document> orders = docs.find("CustomerOrder", Map.of("customer", code, "docstatus", "1", "created_at__gte", since),
                CustomerProfileJob.MAX_ORDERS);
        Map<String, Integer> zones = new TreeMap<>();
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String band : List.of("≤1 kg", "1–3 kg", "3–5 kg", ">5 kg")) weights.put(band, 0);
        int cod = 0;
        int bulky = 0;
        long fees = 0;
        for (Document o : orders) {
            zones.merge(String.valueOf(o.getString("zone")), 1, Integer::sum);
            double kg = Numbers.toDouble(o.get("weight_kg"));
            weights.merge(kg <= 1 ? "≤1 kg" : kg <= 3 ? "1–3 kg" : kg <= 5 ? "3–5 kg" : ">5 kg", 1, Integer::sum);
            if (Numbers.toDouble(o.get("cod_amount")) > 0) cod++;
            double volumetric = Numbers.toDouble(o.get("length_cm")) * Numbers.toDouble(o.get("width_cm")) * Numbers.toDouble(o.get("height_cm")) / 6000;
            if (volumetric > kg) bulky++;
            fees += Math.round(Numbers.toDouble(o.get("shipping_fee")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customer", code);
        out.put("customer_name", customer.getString("customer_name"));
        out.put("segment", customer.getString("segment"));
        out.put("orders_30_days", orders.size());
        out.put("return_rate", customer.get("return_rate"));
        out.put("orders_by_zone", zones);
        out.put("orders_by_weight", weights);
        out.put("cod_share", orders.isEmpty() ? 0 : Math.round(1000.0 * cod / orders.size()) / 1000.0);
        out.put("bulky_share", orders.isEmpty() ? 0 : Math.round(1000.0 * bulky / orders.size()) / 1000.0);
        out.put("average_fee_vnd", orders.isEmpty() ? 0 : fees / orders.size());
        return out;
    }
}
