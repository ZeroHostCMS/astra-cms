package io.astra.spi.pricing;

import java.util.List;

/** The rule layer's quote: its lines, the floor its rules set, the rule set that produced it and how many rules fired. */
public record RuleQuote(List<PriceLine> lines, long floor, String ruleset, int fired) {

    public long total() { return lines.stream().mapToLong(PriceLine::amount).sum(); }
}
