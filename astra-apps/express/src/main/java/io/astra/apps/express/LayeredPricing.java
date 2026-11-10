package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.pricing.PriceLine;
import io.astra.spi.pricing.PricingEngine;
import io.astra.spi.pricing.QuoteRequest;
import io.astra.spi.pricing.RuleQuote;
import io.astra.spi.release.Releases;
import io.astra.spi.script.ScriptException;
import io.astra.spi.script.ScriptRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prices a parcel in three layers, each one inheriting the lines of the one before:
 * <ol>
 *   <li>the rule layer (Drools rule set of slot {@code pricing.base}; the standard tariff when the tenant has none);</li>
 *   <li>the tenant's active {@code PricingRule} for the customer's segment, whose {@code fee} replaces the base line;</li>
 *   <li>the customer's own pricing plugin (slot {@code pricing.customer}), which only declares changes to lines.</li>
 * </ol>
 * The fee is the sum of the lines, rounded up to the thousand. A layer that fails is skipped and says why in
 * {@code priced_by}; it never blocks the order.
 */
@ApplicationScoped
public class LayeredPricing {

    private static final System.Logger LOG = System.getLogger(LayeredPricing.class.getName());

    @Inject DocumentService docs;
    @Inject PricingEngine rules;
    @Inject ScriptRuntime scripts;
    @Inject Releases releases;

    record Priced(List<PriceLine> lines, String pricedBy) {
        long total() { return lines.stream().mapToLong(PriceLine::amount).sum(); }
    }

    /** A customer plugin to run instead of the one the releases select: a draft module in a backtest, or none. */
    record Plugin(byte[] module, String app, String version, Releases.Artifact release) {
        static final Plugin NONE = new Plugin(null, null, null, null);
        String source() { return "wasm:" + app + "@" + version; }
    }

    Priced price(QuoteRequest q, Optional<Document> customer) {
        Plugin plugin = releases.artifact(CustomerPricingPlugin.SLOT, q.getCustomer())
                .map(a -> new Plugin(a.bytes(), a.app(), a.version(), a)).orElse(Plugin.NONE);
        return price(q, customer, plugin);
    }

    Priced price(QuoteRequest q, Optional<Document> customer, Plugin plugin) {
        return finish(base(q, customer, activeRule(customer.map(c -> c.getString("segment")).orElse(null))), q, customer, plugin);
    }

    /** The first two layers (rules, then the PricingRule's base line), before any customer plugin and rounding. */
    record Base(List<PriceLine> lines, long floor, List<String> by) { }

    Base base(QuoteRequest q, Optional<Document> customer, Optional<Document> rule) {
        List<String> by = new ArrayList<>();
        List<PriceLine> lines;
        long floor = 0;
        Optional<RuleQuote> ruled;
        try {
            ruled = rules.quote(q);
        } catch (RuntimeException e) {
            // Any failure of the rule layer (a timeout, a trap, or a live rule set that no longer compiles) falls back
            // to the standard tariff: pricing a rule set must never stop an order being saved.
            String reason = e instanceof ScriptException se ? se.reason() : "error";
            LOG.log(System.Logger.Level.WARNING, "rule set failed ({0}); standard tariff used: {1}", reason, e.getMessage());
            ruled = Optional.empty();
            by.add("Standard tariff (rules failed: " + reason + ")");
        }
        if (ruled.isPresent()) {
            lines = ruled.get().lines();
            floor = ruled.get().floor();
            by.add("Rules " + ruled.get().ruleset());
        } else {
            lines = TariffLines.of(q);
            if (by.isEmpty()) by.add("Standard tariff");
        }
        if (rule.isPresent()) {
            try {
                long fee = PricingRules.fee(scripts, rule.get(), q.getWeightKg(), q.getZone(), q.isExpress(), q.getCodAmount());
                lines = replaceBase(lines, fee, "pricing-rule:" + rule.get().getString("rule_name"), rule.get().name());
                by.add(rule.get().name() + " " + rule.get().getString("rule_name") + " (Wasm)");
            } catch (ScriptException e) {
                LOG.log(System.Logger.Level.WARNING, "Pricing rule {0} failed ({1}): {2}", rule.get().name(), e.reason(), e.getMessage());
                by.add(rule.get().name() + " failed: " + e.reason());
            }
        }
        return new Base(lines, floor, by);
    }

    /** The customer plugin's layer on top of {@code base}, then rounding; {@code base} is not changed. */
    Priced finish(Base base, QuoteRequest q, Optional<Document> customer, Plugin plugin) {
        List<String> by = new ArrayList<>(base.by());
        List<PriceLine> lines = base.lines();
        if (plugin.module() != null) {
            long started = System.nanoTime();
            try {
                byte[] answer = scripts.callBytes(plugin.module(), CustomerPricingPlugin.FUNCTION, CustomerPricingPlugin.TIMEOUT_MS,
                        CustomerPricingPlugin.encode(q, monthlyOrders(customer), returnRate(customer), base.floor(), lines));
                lines = CustomerPricingPlugin.apply(answer, lines, base.floor(), plugin.source());
                if (plugin.release() != null) releases.record(plugin.release(), true, System.nanoTime() - started);
                by.add(plugin.release() != null ? plugin.release().label() : plugin.app() + "@" + plugin.version());
            } catch (RuntimeException e) {
                // The module missed its deadline, trapped, or the host refused its answer (CustomerPricingPlugin.Rejected,
                // including an overflow past the floor): keep the rule-layer price. The customer plugin never fails an order.
                if (plugin.release() != null) releases.record(plugin.release(), false, System.nanoTime() - started);
                String reason = e instanceof ScriptException se ? se.reason() : e.getMessage();
                LOG.log(System.Logger.Level.WARNING, "customer pricing {0} refused; rule price kept: {1}", plugin.source(), e.getMessage());
                by.add(plugin.app() + "@" + plugin.version() + " refused (" + reason + ")");
            }
        }
        return new Priced(TariffLines.roundUp(lines), String.join(" · ", by));
    }

    static List<Map<String, Object>> breakdown(List<PriceLine> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PriceLine l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", l.code());
            m.put("label", l.label());
            m.put("amount", l.amount());
            m.put("source", l.source());
            if (l.locked()) m.put("locked", true);
            if (l.op() != null) {
                m.put("op", l.op());
                m.put("before", l.before());
                if (l.reason() != null && !l.reason().isBlank()) m.put("reason", l.reason());
            }
            out.add(m);
        }
        return out;
    }

    private static List<PriceLine> replaceBase(List<PriceLine> lines, long fee, String source, String reason) {
        List<PriceLine> out = new ArrayList<>();
        boolean replaced = false;
        for (PriceLine l : lines) {
            if (l.code().equals("base")) {
                out.add(new PriceLine("base", l.label(), fee, source, l.locked(), "replace", l.amount(), reason));
                replaced = true;
            } else {
                out.add(l);
            }
        }
        if (!replaced) out.addFirst(new PriceLine("base", "Cước nền", fee, source, false, "replace", 0, reason));
        return out;
    }

    /** The newest active rule for the customer's segment, or for all customers. */
    Optional<Document> activeRule(String segment) {
        return docs.find("PricingRule", Map.of("active", "true"), 20).stream()
                .filter(r -> "All".equals(r.getString("segment")) || r.getString("segment").equals(segment))
                .findFirst();
    }

    private static long monthlyOrders(Optional<Document> customer) {
        return customer.map(c -> Math.round(Numbers.toDouble(c.get("monthly_orders")))).orElse(0L);
    }

    private static double returnRate(Optional<Document> customer) {
        return customer.map(c -> Numbers.toDouble(c.get("return_rate"))).orElse(0.0);
    }
}
