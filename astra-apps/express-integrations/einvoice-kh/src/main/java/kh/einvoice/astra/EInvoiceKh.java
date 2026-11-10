package kh.einvoice.astra;

import io.astra.spi.app.AstraPlugin;
import io.astra.spi.app.PluginContext;
import io.astra.spi.integration.EInvoiceProvider;
import io.astra.spi.integration.PartnerHttp;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cambodia electronic invoices: issued in USD with the KHR equivalent, VAT 10 %, bilingual Khmer and English line
 * descriptions. Order amounts arrive in VND and are converted at the configured demo rate.
 */
public class EInvoiceKh implements EInvoiceProvider, AstraPlugin {

    private PartnerHttp http;
    private String baseUrl;
    private double vndPerUsd;
    private double khrPerUsd;

    @Override
    public void start(PluginContext context) {
        this.http = context.http();
        this.baseUrl = context.config().getOrDefault("base-url", "http://partner-sandbox:8090");
        this.vndPerUsd = Double.parseDouble(context.config().getOrDefault("vnd-per-usd", "25400"));
        this.khrPerUsd = Double.parseDouble(context.config().getOrDefault("khr-per-usd", "4100"));
    }

    @Override
    public String country() { return "KH"; }

    @Override
    public IssuedInvoice issue(InvoiceRequest r) {
        long subtotalVnd = r.lines().stream().mapToLong(l -> l.quantity() * l.unitPrice()).sum();
        long subtotalCents = Math.round(subtotalVnd / vndPerUsd * 100);
        long vatCents = Math.round(subtotalCents * 0.10);
        long totalCents = subtotalCents + vatCents;
        long totalKhr = Math.round(totalCents / 100.0 * khrPerUsd);
        String body = String.format(Locale.ROOT,
                "{\"order\":\"%s\",\"buyer\":\"%s\",\"description\":\"ថ្លៃដឹកជញ្ជូន / Delivery service\",\"subtotal_usd\":%.2f,"
                        + "\"vat_rate\":10,\"vat_usd\":%.2f,\"total_usd\":%.2f,\"total_khr\":%d}",
                r.order(), r.buyerName() == null ? "" : r.buyerName().replace("\"", "'"),
                subtotalCents / 100.0, vatCents / 100.0, totalCents / 100.0, totalKhr);
        PartnerHttp.Response resp = http.postJson(baseUrl + "/tax/kh/invoices", body);
        if (!resp.ok()) throw new IllegalStateException("GDT sandbox answered HTTP " + resp.status() + ": " + resp.body());
        return new IssuedInvoice(field(resp.body(), "number"), field(resp.body(), "lookup"), field(resp.body(), "status"),
                subtotalCents, vatCents, totalCents, "USD");
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]*)").matcher(json);
        if (!m.find()) throw new IllegalStateException("GDT sandbox answer has no '" + name + "': " + json);
        return m.group(1).trim();
    }
}
