package io.astra.apps.express;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.script.ScriptException;
import io.astra.spi.script.ScriptRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Business rules for an order draft: receiver phone format, weight and COD limits, and the shipping
 * fee. The fee comes from the tenant's active {@code PricingRule} (a WebAssembly module run in the
 * Tier 2 sandbox) when one matches the customer's segment, otherwise from the standard tariff. A rule
 * that fails at run time never blocks an order: the standard tariff applies and {@code priced_by} says why.
 */
@ApplicationScoped
public class ShippingFeeHook implements DocLifecycleHook {

    static final double MAX_WEIGHT_KG = 30;
    static final long MAX_COD = 50_000_000;

    /** Zone → {price for the first kg, price per additional 0.5 kg}, in VND. */
    private static final Map<String, long[]> TARIFF = Map.of(
            "Same city", new long[] {18_000, 2_500},
            "Domestic", new long[] {30_000, 5_000},
            "Remote", new long[] {45_000, 7_000});

    private static final System.Logger LOG = System.getLogger(ShippingFeeHook.class.getName());

    @Inject DocumentService docs;
    @Inject ScriptRuntime scripts;

    @Override
    public String doctype() { return "CustomerOrder"; }

    @Override
    public void beforeSave(Document doc) {
        List<String> errors = new ArrayList<>();
        String phone = doc.getString("receiver_phone");
        if (phone != null && !phone.matches("0\\d{9}")) errors.add("receiver_phone must be 10 digits starting with 0");
        double weight = Numbers.toDouble(doc.get("weight_kg"));
        if (weight <= 0 || weight > MAX_WEIGHT_KG) errors.add("weight_kg must be between 0 and " + (int) MAX_WEIGHT_KG);
        long cod = Math.round(Numbers.toDouble(doc.get("cod_amount")));
        if (cod < 0 || cod > MAX_COD) errors.add("cod_amount must be between 0 and " + MAX_COD);
        if (!errors.isEmpty()) throw new DocValidationException(errors);

        String zone = doc.getString("zone");
        boolean express = "Express".equals(doc.getString("service"));
        Optional<Document> rule = activeRule(doc.getString("customer"));
        if (rule.isPresent()) {
            try {
                doc.set("shipping_fee", PricingRules.fee(scripts, rule.get(), weight, zone, express, cod));
                doc.set("priced_by", rule.get().name() + " " + rule.get().getString("rule_name") + " (Wasm)");
                return;
            } catch (ScriptException e) {
                LOG.log(System.Logger.Level.WARNING, "Pricing rule {0} failed ({1}); standard tariff used: {2}",
                        rule.get().name(), e.reason(), e.getMessage());
                doc.set("priced_by", "Standard tariff (" + rule.get().name() + " failed: " + e.reason() + ")");
                doc.set("shipping_fee", fee(zone, doc.getString("service"), weight, cod));
                return;
            }
        }
        doc.set("shipping_fee", fee(zone, doc.getString("service"), weight, cod));
        doc.set("priced_by", "Standard tariff");
    }

    /** The newest active rule for the customer's segment, or for all customers. */
    private Optional<Document> activeRule(String customer) {
        String segment = customer == null ? null
                : docs.get("Customer", customer).map(c -> c.getString("segment")).orElse(null);
        return docs.find("PricingRule", Map.of("active", "true"), 20).stream()
                .filter(r -> "All".equals(r.getString("segment")) || r.getString("segment").equals(segment))
                .findFirst();
    }

    static long fee(String zone, String service, double weightKg, long cod) {
        long[] t = TARIFF.getOrDefault(zone, TARIFF.get("Domestic"));
        long extraSteps = (long) Math.ceil(Math.max(0, weightKg - 1) / 0.5);
        double fee = t[0] + extraSteps * t[1];
        if ("Express".equals(service)) fee *= 1.5;
        if (cod > 0) fee += Math.max(5_000, cod * 0.01);
        return (long) Math.ceil(fee / 1_000) * 1_000;
    }
}
