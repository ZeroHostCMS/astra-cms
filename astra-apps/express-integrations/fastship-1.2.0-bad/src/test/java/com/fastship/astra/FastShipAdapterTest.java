package com.fastship.astra;

import io.astra.spi.app.PluginContext;
import io.astra.spi.integration.CapabilityDeniedException;
import io.astra.spi.integration.PartnerHttp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.astra.spi.integration.CarrierAdapter.Shipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FastShipAdapterTest {

    @Test
    void theBrokenBuildFailsRemoteParcelsOnly() {
        FastShipAdapter fastship = new FastShipAdapter();
        fastship.start(new FakeContext("{\"tracking\":\"FS1\",\"fee\":30000,\"eta\":\"x\"}"));
        assertEquals("FS1", fastship.book(new Shipment("WB-1", "O", "C", "Domestic", "Standard", 1, 0)).tracking());
        assertThrows(IllegalArgumentException.class, () -> fastship.book(new Shipment("WB-2", "O", "C", "Remote", "Standard", 1, 0)));
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
