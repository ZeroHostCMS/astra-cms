package io.astra.apps.express;

import io.astra.spi.pricing.PriceLine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host's guardrails on a customer plugin's answer: it may only change the rule layer's lines within bounds. */
class CustomerPricingPluginTest {

    private static final List<PriceLine> RULES = List.of(
            new PriceLine("base", "Cước nền", 45_000, "rule:base-remote", false, null, 45_000, null),
            new PriceLine("cod_fee", "Phí COD", 5_000, "rule:cod-fee", false, null, 5_000, null),
            new PriceLine("peak", "Phụ phí 11.11", 5_000, "rule:peak-1111", true, null, 5_000, null));

    private static List<PriceLine> apply(String answer, long floor) {
        return CustomerPricingPlugin.apply(answer.getBytes(StandardCharsets.UTF_8), RULES, floor, "wasm:khaigia@1.0.0");
    }

    @Test
    void operationsChangeOnlyTheLinesTheyNameAndRecordWhy() {
        List<PriceLine> out = apply("scale\tbase\t920\t§2 volume tier\nremove\tcod_fee\t§3 returns under 5 %\nadd\tbulky\t8000\tCồng kềnh\t§4\n", 0);
        assertEquals(41_400, out.get(0).amount());
        assertEquals("scale 0.92 by wasm:khaigia@1.0.0", out.get(0).op());
        assertEquals(45_000, out.get(0).before());
        assertEquals("rule:base-remote", out.get(0).source());
        assertEquals(0, out.get(1).amount());
        assertEquals(5_000, out.get(2).amount(), "the locked peak line is inherited untouched");
        assertEquals("bulky", out.get(3).code());
        assertEquals(54_400, out.stream().mapToLong(PriceLine::amount).sum());
    }

    @Test
    void lockedLinesUnknownCodesDuplicatesBadFactorsAndTheFloorAreRefused() {
        for (String bad : List.of("remove\tpeak\twe do not pay surcharges", "scale\tvat\t900\tno such line", "add\tbase\t1\tx\tdup",
                "scale\tbase\t5000\tfive times", "set\tbase\t-1\tnegative", "explode\tbase", "add\tBad Code\t1\tx\ty", "set\tbase\tabc\tx")) {
            assertThrows(CustomerPricingPlugin.Rejected.class, () -> apply(bad, 0), bad);
        }
        CustomerPricingPlugin.Rejected e = assertThrows(CustomerPricingPlugin.Rejected.class, () -> apply("set\tbase\t1000\ttoo cheap", 20_000));
        assertTrue(e.getMessage().contains("floor"), e.getMessage());
    }
}
