package io.astra.apps.express;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.pricing.QuoteRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Business rules for an order draft: receiver phone format, weight and COD limits, and the shipping fee, which
 * {@link LayeredPricing} computes in layers (rules, the tenant's PricingRule, the customer's own pricing plugin).
 * The fee is the sum of {@code price_breakdown}; {@code priced_by} names the layers that priced it.
 */
@ApplicationScoped
public class ShippingFeeHook implements DocLifecycleHook {

    static final double MAX_WEIGHT_KG = 30;
    static final long MAX_COD = 50_000_000;
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Inject DocumentService docs;
    @Inject LayeredPricing pricing;

    @Override
    public String doctype() { return "CustomerOrder"; }

    @Override
    public void beforeSave(Document doc) {
        List<String> errors = new ArrayList<>();
        String phone = doc.getString("receiver_phone");
        if (phone != null && !phone.matches("0\\d{9}")) errors.add("receiver_phone must be 10 digits starting with 0");
        double weight = Numbers.toDouble(doc.get("weight_kg"));
        if (weight <= 0 || weight > MAX_WEIGHT_KG) errors.add("weight_kg must be between 0 and " + (int) MAX_WEIGHT_KG);
        long cod = Math.round(Numbers.toDouble(doc.get("cod_amount")));
        if (cod < 0 || cod > MAX_COD) errors.add("cod_amount must be between 0 and " + MAX_COD);
        if (!errors.isEmpty()) throw new DocValidationException(errors);

        String code = doc.getString("customer");
        Optional<Document> customer = code == null ? Optional.empty() : docs.get("Customer", code);
        LayeredPricing.Priced priced = pricing.price(quote(doc, customer.map(c -> c.getString("segment")).orElse(null)), customer);
        doc.set("shipping_fee", priced.total());
        doc.set("price_breakdown", LayeredPricing.breakdown(priced.lines()));
        doc.set("priced_by", priced.pricedBy());
    }

    static QuoteRequest quote(Document doc, String segment) {
        return new QuoteRequest(doc.getString("zone"), doc.getString("service"), Numbers.toDouble(doc.get("weight_kg")),
                Numbers.toDouble(doc.get("length_cm")), Numbers.toDouble(doc.get("width_cm")), Numbers.toDouble(doc.get("height_cm")),
                Math.round(Numbers.toDouble(doc.get("cod_amount"))), doc.getString("customer"), segment,
                doc.createdAt() == null ? LocalDate.now(BUSINESS_ZONE) : LocalDate.ofInstant(doc.createdAt(), BUSINESS_ZONE));
    }

    /** The old hard-coded fee: the standard tariff summed and rounded, kept for the tests that pin the rules to it. */
    static long fee(String zone, String service, double weightKg, long cod) {
        QuoteRequest q = new QuoteRequest(zone, service, weightKg, 0, 0, 0, cod, null, null, null);
        return TariffLines.roundUp(TariffLines.of(q)).stream().mapToLong(io.astra.spi.pricing.PriceLine::amount).sum();
    }
}
