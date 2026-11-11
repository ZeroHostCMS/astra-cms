package vn.einvoice.astra;

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

class EInvoiceVnTest {

    @Test
    void vatAtEightPercentInDong() {
        FakeContext ctx = new FakeContext("{\"number\":\"1C26TAS-00000001\",\"lookup\":\"VN-1\",\"status\":\"issued\"}");
        EInvoiceVn vn = new EInvoiceVn();
        vn.start(ctx);
        IssuedInvoice inv = vn.issue(new InvoiceRequest("ORD-1", "Shop Hoa Mai", "", "VND", List.of(new Line("Cước", 1, 30_000))));
        assertEquals(2_400, inv.vat());
        assertEquals(32_400, inv.total());
        assertEquals("1C26TAS-00000001", inv.number());
        assertTrue(ctx.calls.getFirst().contains("/tax/vn/invoices"));
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
