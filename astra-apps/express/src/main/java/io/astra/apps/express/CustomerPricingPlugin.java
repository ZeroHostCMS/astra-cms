package io.astra.apps.express;

import io.astra.spi.pricing.PriceLine;
import io.astra.spi.pricing.QuoteRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The host side of slot {@code pricing.customer}: encodes the rule layer's lines for the customer's Wasm plugin
 * (astra-pricing-pdk wire format) and applies the operations it answers. The plugin inherits the rule price and
 * declares only its differences; the host enforces the guardrails: no change to a locked line, no unknown code, no
 * second line with an existing code, factors between 0 and 2, no negative amounts, the total not under the rule
 * layer's floor. One bad operation drops the plugin's whole answer and the rule price stands.
 */
final class CustomerPricingPlugin {

    static final String SLOT = "pricing.customer";
    static final String FUNCTION = "adjust";
    static final long TIMEOUT_MS = 50;

    private CustomerPricingPlugin() { }

    /** The plugin refused to price, with why; the caller keeps the rule layer's lines. */
    static final class Rejected extends RuntimeException {
        Rejected(String message) { super(message); }
    }

    static byte[] encode(QuoteRequest q, long monthlyOrders, double returnRate, long floor, List<PriceLine> lines) {
        StringBuilder in = new StringBuilder("ctx");
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("zone", q.getZone());
        ctx.put("service", q.getService());
        ctx.put("weight_g", Math.round(q.getWeightKg() * 1000));
        ctx.put("volumetric_g", Math.round(q.getVolumetricKg() * 1000));
        ctx.put("cod", q.getCodAmount());
        ctx.put("month", q.getMonth());
        ctx.put("day", q.getDay());
        ctx.put("monthly_orders", monthlyOrders);
        ctx.put("return_rate_bp", Math.round(returnRate * 10_000));
        ctx.put("floor", floor);
        ctx.forEach((k, v) -> in.append('\t').append(k).append('=').append(clean(String.valueOf(v))));
        in.append('\n');
        for (PriceLine l : lines) {
            in.append("line\t").append(l.code()).append('\t').append(l.amount()).append('\t').append(l.locked() ? 1 : 0)
                    .append('\t').append(clean(l.source())).append('\t').append(clean(l.label())).append('\n');
        }
        return in.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Applies the plugin's answer; every line it touches records the operation, {@code source} and the reason. */
    static List<PriceLine> apply(byte[] answer, List<PriceLine> lines, long floor, String source) {
        Map<String, PriceLine> byCode = new LinkedHashMap<>();
        lines.forEach(l -> byCode.put(l.code(), l));
        for (String row : new String(answer, StandardCharsets.UTF_8).split("\n")) {
            if (row.isBlank()) continue;
            String[] f = row.split("\t", -1);
            String op = f[0];
            String code = f.length > 1 ? f[1] : "";
            switch (op) {
                case "scale" -> {
                    PriceLine l = existing(byCode, code, op);
                    long permille = number(f, 2, row);
                    if (permille < 0 || permille > 2_000) throw new Rejected("scale " + code + " by " + permille + "‰ is outside 0–2000‰");
                    byCode.put(code, l.with(capped(code, Math.round(l.amount() * permille / 1000.0)), "scale " + permille / 1000.0 + " by " + source, text(f, 3)));
                }
                case "set" -> {
                    PriceLine l = existing(byCode, code, op);
                    byCode.put(code, l.with(capped(code, number(f, 2, row)), "set by " + source, text(f, 3)));
                }
                case "remove" -> {
                    PriceLine l = existing(byCode, code, op);
                    byCode.put(code, l.with(0, "remove by " + source, text(f, 2)));
                }
                case "add" -> {
                    if (byCode.containsKey(code)) throw new Rejected("add " + code + ": the rule layer already has that line");
                    if (!code.matches("[a-z][a-z0-9_]{0,31}")) throw new Rejected("add: '" + code + "' is not a line code");
                    byCode.put(code, new PriceLine(code, text(f, 3), capped(code, number(f, 2, row)), source, false, "add by " + source, 0, text(f, 4)));
                }
                default -> throw new Rejected("unknown operation '" + op + "'");
            }
        }
        List<PriceLine> out = new ArrayList<>(byCode.values());
        long total = 0;
        for (PriceLine l : out) total = Math.addExact(total, l.amount());   // capped lines cannot overflow, but be explicit
        if (total < floor) throw new Rejected("total " + total + " would be under the rule layer's floor " + floor);
        return out;
    }

    /** One price line is at most 1 billion đồng, far above any real shipping fee; so a line is never negative and the
     * sum of the lines on one parcel cannot overflow a long and slip under the floor check. */
    static final long MAX_LINE = 1_000_000_000L;

    private static long capped(String code, long amount) {
        if (amount < 0) throw new Rejected("line '" + code + "' would be negative (" + amount + ")");
        if (amount > MAX_LINE) throw new Rejected("line '" + code + "' of " + amount + " exceeds the cap " + MAX_LINE);
        return amount;
    }

    private static PriceLine existing(Map<String, PriceLine> byCode, String code, String op) {
        PriceLine l = byCode.get(code);
        if (l == null) throw new Rejected(op + " " + code + ": no such line in the rule layer's price");
        if (l.locked()) throw new Rejected(op + " " + code + ": the line is locked by " + l.source());
        return l;
    }

    private static long number(String[] f, int i, String row) {
        try {
            return Long.parseLong(f[i].trim());
        } catch (RuntimeException e) {
            throw new Rejected("not a number in '" + row + "'");
        }
    }

    private static String text(String[] f, int i) {
        return f.length > i ? f[i] : "";
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }
}
