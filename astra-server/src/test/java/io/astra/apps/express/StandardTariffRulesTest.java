package io.astra.apps.express;

import io.astra.kernel.rules.RuleEngine;
import io.astra.spi.pricing.PriceLine;
import io.astra.spi.pricing.QuoteRequest;
import io.quarkus.bootstrap.logging.InitialConfigurator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** rules/standard-tariff.drl is the old hard-coded tariff, rule for rule: same fee on the whole grid. */
class StandardTariffRulesTest {

    @BeforeAll
    static void startLogging() {
        InitialConfigurator.DELAYED_HANDLER.setHandlers(new java.util.logging.Handler[] {new java.util.logging.ConsoleHandler()});
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = StandardTariffRulesTest.class.getClassLoader().getResourceAsStream("META-INF/astra/rules/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void theRuleSetReproducesTheHardCodedTariffOnEveryZoneServiceWeightAndCod() throws IOException {
        byte[] drl = resource("standard-tariff.drl");
        RuleEngine engine = new RuleEngine();
        List<String> mismatches = new ArrayList<>();
        int cases = 0;
        for (String zone : List.of("Same city", "Domestic", "Remote", "Island")) {
            for (String service : List.of("Standard", "Express")) {
                for (double kg : new double[] {0.1, 0.5, 1, 1.01, 1.5, 2, 2.3, 3, 4.75, 7, 12.5, 30}) {
                    for (long cod : new long[] {0, 1, 100_000, 499_999, 500_000, 850_000, 1_234_567, 3_333_333, 50_000_000}) {
                        QuoteRequest q = new QuoteRequest(zone, service, kg, 0, 0, 0, cod, null, null, null);
                        long rules = TariffLines.roundUp(engine.quote(drl, q).lines()).stream().mapToLong(PriceLine::amount).sum();
                        long old = ShippingFeeHook.fee(zone, service, kg, cod);
                        if (rules != old) mismatches.add(zone + "/" + service + "/" + kg + "kg/COD " + cod + ": rules " + rules + ", tariff " + old);
                        cases++;
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches, "of " + cases + " cases");
        assertEquals(864, cases);
    }

    @Test
    void theHardCodedTariffIsUnchanged() {
        // the numbers of the old ShippingFeeHook.TARIFF, so the comparison above compares against the real old table
        assertEquals(18_000, ShippingFeeHook.fee("Same city", "Standard", 1, 0));
        assertEquals(30_000, ShippingFeeHook.fee("Domestic", "Standard", 0.5, 0));
        assertEquals(59_000, ShippingFeeHook.fee("Remote", "Standard", 2, 0));
        assertEquals(80_000, ShippingFeeHook.fee("Domestic", "Express", 3, 500_000));   // (30,000 + 4 × 5,000) × 1.5 + 5,000
    }
}
