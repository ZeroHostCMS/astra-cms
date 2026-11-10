package io.astra.apps.express;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.pricing.PriceLine;
import io.astra.spi.pricing.PricingEngine;
import io.astra.spi.pricing.QuoteRequest;
import io.astra.spi.pricing.RuleQuote;
import io.astra.spi.release.SlotContract;
import io.astra.spi.script.ScriptException;
import io.astra.spi.script.ScriptRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** The two pricing slots: the tenant's rule set (Tier 0 Drools, rules as data) and a customer's own plugin (Tier 2). */
final class PricingSlots {

    /** A parcel every self-test prices: 2 kg, domestic, 500,000 VND cash on delivery. */
    static final QuoteRequest SAMPLE = new QuoteRequest("Domestic", "Standard", 2.0, 40, 30, 30, 500_000, null, "B2B", null);

    private PricingSlots() { }

    /** {@code pricing.base}: a DRL rule set, allowlisted, compiled and run once on the sample parcel. */
    @ApplicationScoped
    static class Rules implements SlotContract {
        @Inject PricingEngine engine;

        @Override public String slot() { return PricingEngine.SLOT; }
        @Override public String tier() { return "rules"; }

        @Override public String selfTest(byte[] drl) {
            int rules = engine.validate(drl);
            RuleQuote q;
            try {
                q = engine.quote(drl, SAMPLE);
            } catch (ScriptException e) {
                throw new DocValidationException("rule set failed on the sample parcel (" + e.reason() + "): " + e.getMessage());
            }
            return rules + " rules; sample 2 kg Domestic COD 500k = " + TariffLines.roundUp(q.lines()).stream().mapToLong(PriceLine::amount).sum()
                    + " VND in " + q.lines().size() + " lines";
        }
    }

    /** {@code pricing.customer}: a Wasm module with {@code alloc} and {@code adjust}, run on the sample parcel's tariff lines. */
    @ApplicationScoped
    static class Customer implements SlotContract {
        @Inject ScriptRuntime scripts;

        @Override public String slot() { return CustomerPricingPlugin.SLOT; }
        @Override public String tier() { return "wasm"; }

        @Override public String selfTest(byte[] module) {
            List<PriceLine> lines = TariffLines.of(SAMPLE);
            try {
                byte[] answer = scripts.callBytes(module, CustomerPricingPlugin.FUNCTION, CustomerPricingPlugin.TIMEOUT_MS,
                        CustomerPricingPlugin.encode(SAMPLE, 1_000, 0.03, 0, lines));
                List<PriceLine> after = CustomerPricingPlugin.apply(answer, lines, 0, "self-test");
                long ops = after.stream().filter(l -> l.op() != null).count();
                return "adjust(sample 2 kg Domestic COD 500k) → " + ops + " operations, "
                        + lines.stream().mapToLong(PriceLine::amount).sum() + " → " + after.stream().mapToLong(PriceLine::amount).sum() + " VND";
            } catch (ScriptException e) {
                throw new DocValidationException("module rejected (" + e.reason() + "): " + e.getMessage());
            } catch (CustomerPricingPlugin.Rejected e) {
                throw new DocValidationException("module answer rejected: " + e.getMessage());
            }
        }
    }
}
