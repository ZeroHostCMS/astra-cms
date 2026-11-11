package io.astra.apps.express;

import io.astra.spi.pricing.PriceLine;
import io.astra.spi.pricing.QuoteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The standard tariff that used to be the whole price, now only the fallback for a tenant without a live rule set
 * (rules/standard-tariff.drl is the same table as rules). Same lines as the rule set, source {@code tariff}.
 */
final class TariffLines {

    /** Zone → {price for the first kg, price per additional 0.5 kg}, in VND. */
    static final Map<String, long[]> TARIFF = Map.of(
            "Same city", new long[] {18_000, 2_500},
            "Domestic", new long[] {30_000, 5_000},
            "Remote", new long[] {45_000, 7_000});

    private TariffLines() { }

    static List<PriceLine> of(QuoteRequest q) {
        long[] t = TARIFF.getOrDefault(q.getZone(), TARIFF.get("Domestic"));
        long base = t[0] + (long) Math.ceil(Math.max(0, q.getWeightKg() - 1) / 0.5) * t[1];
        List<PriceLine> lines = new ArrayList<>();
        lines.add(line("base", "Cước nền", base));
        if (q.isExpress()) lines.add(line("express", "Phụ phí hoả tốc (×1,5)", base / 2));
        if (q.getCodAmount() > 0) lines.add(line("cod_fee", "Phí thu hộ COD", Math.max(5_000, (long) Math.ceil(q.getCodAmount() * 0.01))));
        return lines;
    }

    private static PriceLine line(String code, String label, long amount) {
        return new PriceLine(code, label, amount, "tariff", false, null, amount, null);
    }

    /** Rounds the total up to the next thousand đồng with an explicit {@code rounding} line, as the tariff always did. */
    static List<PriceLine> roundUp(List<PriceLine> lines) {
        long total = lines.stream().mapToLong(PriceLine::amount).sum();
        long rounded = (total + 999) / 1_000 * 1_000;
        if (rounded == total) return lines;
        List<PriceLine> out = new ArrayList<>(lines);
        out.add(new PriceLine("rounding", "Làm tròn", rounded - total, "rounding", false, null, rounded - total, null));
        return out;
    }
}
