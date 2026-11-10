package kh.einvoice.astra;

import io.astra.spi.app.PluginContext;
import io.astra.spi.integration.CapabilityDeniedException;
import io.astra.spi.integration.PartnerHttp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.astra.spi.integration.EInvoiceProvider.InvoiceRequest;
import io.astra.spi.integration.EInvoiceProvider.IssuedInvoice;
import io.astra.spi.integration.EInvoiceProvider.Line;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EInvoiceKhTest {

    @Test
    void usdWithVatAtTenPercentAndTheRielEquivalent() {
        FakeContext ctx = new FakeContext("{\"number\":\"KH-INV-2026-000001\",\"lookup\":\"KH-1\",\"status\":\"issued\"}");
        EInvoiceKh kh = new EInvoiceKh();
        kh.start(ctx);
        IssuedInvoice inv = kh.issue(new InvoiceRequest("ORD-1", "Angkor Mart", "", "VND", List.of(new Line("Delivery", 1, 50_800))));
        assertEquals(200, inv.subtotal());      // 50,800 VND at 25,400 per USD = 2.00 USD
        assertEquals(20, inv.vat());
        assertEquals(220, inv.total());
        assertEquals("USD", inv.currency());
        assertTrue(ctx.calls.getFirst().contains("\"total_khr\":9020"), ctx.calls.getFirst());
    }

    /** A context whose HTTP answers like the sandbox and records what was asked. */
    static final class FakeContext implements PluginContext {
        final List<String> calls = new ArrayList<>();
        final String answer;
        FakeContext(String answer) { this.answer = answer; }
        @Override public String identity() { return "test@0"; }
        @Override public Map<String, String> config() { return Map.of(); }
        @Override public System.Logger log() { return System.getLogger("test"); }
        @Override public PartnerHttp http() {
            return new PartnerHttp() {
                @Override public Response get(String url) {
                    calls.add("GET " + url);
                    if (!url.startsWith("http://partner-sandbox:8090")) throw new CapabilityDeniedException("not granted: " + url);
                    return new Response(200, answer);
                }
                @Override public Response postJson(String url, String json) { calls.add("POST " + url + " " + json); return new Response(200, answer); }
            };
        }
    }
}
