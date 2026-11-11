package io.astra.spi.pricing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a pricing rule may do: add price lines, read the lines added so far, lock a line against later layers and
 * set a floor under the total. Rules see it as the global {@code lines}. The engine records which rule added each
 * line. Amounts are whole đồng.
 */
public final class QuoteLines {

    private final Map<String, PriceLine> lines = new LinkedHashMap<>();
    private String currentRule = "";
    private long floor;

    /** Adds a line; adding the same code twice is an error in the rule set. */
    public void add(String code, String label, long amount) {
        if (lines.containsKey(code)) throw new IllegalStateException("line '" + code + "' added twice (rule " + currentRule + ")");
        if (amount < 0) throw new IllegalStateException("line '" + code + "' has a negative amount (rule " + currentRule + ")");
        lines.put(code, new PriceLine(code, label, amount, "rule:" + currentRule, false, null, amount, null));
    }

    public boolean has(String code) { return lines.containsKey(code); }

    /** The amount of a line added so far, 0 when there is none. */
    public long amount(String code) {
        PriceLine l = lines.get(code);
        return l == null ? 0 : l.amount();
    }

    public long total() { return lines.values().stream().mapToLong(PriceLine::amount).sum(); }

    /** Later layers (a customer's plugin) may not change this line, for example a fee the law requires. */
    public void lock(String code) {
        PriceLine l = lines.get(code);
        if (l != null) lines.put(code, new PriceLine(l.code(), l.label(), l.amount(), l.source(), true, null, l.amount(), null));
    }

    /** Later layers may not bring the total below this amount. */
    public void floor(long amount) { floor = Math.max(floor, amount); }

    public long getFloor() { return floor; }

    public List<PriceLine> lines() { return new ArrayList<>(lines.values()); }

    /** Set by the engine before each rule's consequence runs, so lines carry the rule that added them. */
    public void currentRule(String rule) { this.currentRule = rule; }
}
