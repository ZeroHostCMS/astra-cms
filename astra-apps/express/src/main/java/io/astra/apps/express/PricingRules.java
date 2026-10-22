package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.script.ScriptRuntime;

import java.util.Base64;
import java.util.List;

/** The pricing-script ABI shared by the rule's self-test and order pricing. */
final class PricingRules {

    static final String FUNCTION = "fee";
    private static final List<String> ZONES = List.of("Same city", "Domestic", "Remote", "Island");

    private PricingRules() { }

    /** fee(weight_g, zone, express, cod) in VND, run in the Tier 2 sandbox under the rule's deadline. */
    static long fee(ScriptRuntime scripts, Document rule, double weightKg, String zone, boolean express, long cod) {
        byte[] module = Base64.getDecoder().decode(rule.getString("module_wasm"));
        long timeout = Math.round(Numbers.toDouble(rule.get("timeout_ms")));
        return scripts.callLong(module, FUNCTION, timeout <= 0 ? 50 : timeout,
                Math.round(weightKg * 1000), Math.max(0, ZONES.indexOf(zone)), express ? 1 : 0, cod);
    }
}
