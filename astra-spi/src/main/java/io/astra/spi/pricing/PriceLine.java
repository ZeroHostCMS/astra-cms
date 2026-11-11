package io.astra.spi.pricing;

/**
 * One line of a quote. {@code code} is the stable key the layers agree on ({@code base}, {@code express},
 * {@code cod_fee}, {@code peak}…); {@code source} says who produced the line ({@code rule:<rule name>},
 * {@code pricing-rule:<name>}, {@code wasm:<app>@<version>}, {@code tariff}); {@code op} and {@code before}
 * record what a later layer did to it, with {@code reason}.
 */
public record PriceLine(String code, String label, long amount, String source, boolean locked,
                        String op, long before, String reason) {

    public PriceLine with(long newAmount, String newOp, String newReason) {
        return new PriceLine(code, label, newAmount, source, locked, newOp, amount, newReason);
    }
}
