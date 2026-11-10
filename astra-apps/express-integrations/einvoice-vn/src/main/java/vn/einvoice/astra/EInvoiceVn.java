package vn.einvoice.astra;

import io.astra.spi.app.AstraPlugin;
import io.astra.spi.app.PluginContext;
import io.astra.spi.integration.EInvoiceProvider;
import io.astra.spi.integration.PartnerHttp;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vietnam electronic invoices: amounts in VND, VAT at the configured rate (8 % for transport services by default),
 * invoice template symbol from the configuration, issued through the tax authority's sandbox.
 */
public class EInvoiceVn implements EInvoiceProvider, AstraPlugin {

    private PartnerHttp http;
    private String baseUrl;
    private int vatPercent;
    private String symbol;

    @Override
    public void start(PluginContext context) {
        this.http = context.http();
        this.baseUrl = context.config().getOrDefault("base-url", "http://partner-sandbox:8090");
        this.vatPercent = Integer.parseInt(context.config().getOrDefault("vat-percent", "8"));
        this.symbol = context.config().getOrDefault("symbol", "1C26TAS");
    }

    @Override
    public String country() { return "VN"; }

    @Override
    public IssuedInvoice issue(InvoiceRequest r) {
        if (!"VND".equals(r.currency())) throw new IllegalArgumentException("Vietnamese invoices are in VND, not " + r.currency());
        long subtotal = r.lines().stream().mapToLong(l -> l.quantity() * l.unitPrice()).sum();
        long vat = Math.round(subtotal * vatPercent / 100.0);
        String body = String.format(Locale.ROOT,
                "{\"order\":\"%s\",\"symbol\":\"%s\",\"buyer\":\"%s\",\"tax_code\":\"%s\",\"subtotal\":%d,\"vat_rate\":%d,\"vat\":%d,\"total\":%d,\"currency\":\"VND\"}",
                r.order(), symbol, escape(r.buyerName()), r.buyerTaxCode(), subtotal, vatPercent, vat, subtotal + vat);
        PartnerHttp.Response resp = http.postJson(baseUrl + "/tax/vn/invoices", body);
        if (!resp.ok()) throw new IllegalStateException("tax sandbox answered HTTP " + resp.status() + ": " + resp.body());
        return new IssuedInvoice(field(resp.body(), "number"), field(resp.body(), "lookup"), field(resp.body(), "status"),
                subtotal, vat, subtotal + vat, "VND");
    }

    static String escape(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]*)").matcher(json);
        if (!m.find()) throw new IllegalStateException("tax sandbox answer has no '" + name + "': " + json);
        return m.group(1).trim();
    }
}
