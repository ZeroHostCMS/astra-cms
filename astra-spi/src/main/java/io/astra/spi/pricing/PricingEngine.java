package io.astra.spi.pricing;

import java.util.Optional;

/**
 * Tier 0 rule engine for prices: rule sets are data (DRL text released through slot {@code pricing.base}), compiled
 * at run time, checked against an allowlist before they are accepted, and run with a deadline.
 */
public interface PricingEngine {

    /** Slot of the tenant's rule set. */
    String SLOT = "pricing.base";

    /** Quotes with the rule set the tenant's releases select on this pod; empty when the tenant has none. */
    Optional<RuleQuote> quote(QuoteRequest request);

    /** Quotes with the given rule set, for a self-test or a backtest. */
    RuleQuote quote(byte[] drl, QuoteRequest request);

    /**
     * Checks a rule set without running it: the allowlist (what a rule may reference) and compilation.
     *
     * @return the number of rules
     * @throws io.astra.spi.doc.DocValidationException with the offending line when it is refused
     */
    int validate(byte[] drl);
}
