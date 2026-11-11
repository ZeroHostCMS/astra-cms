package com.fastship.astra;

import io.astra.spi.app.PluginContext;
import io.astra.spi.integration.CapabilityDeniedException;
import io.astra.spi.integration.PartnerHttp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.astra.spi.integration.CarrierAdapter.Booking;
import io.astra.spi.integration.CarrierAdapter.Shipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastShipAdapterTest {

    private static final String ANSWER = "{\"tracking\":\"FS26111000001\",\"fee\":21000,\"service\":\"FAST-2H\",\"eta\":\"2026-11-10T16:00\"}";

    @Test
    void sameCityGoesOnTheTwoHourServiceAndTheDeniedHintDoesNotStopTheBooking() {
        FakeContext ctx = new FakeContext(ANSWER);
        FastShipAdapter fastship = new FastShipAdapter();
        fastship.start(ctx);
        Booking b = fastship.book(new Shipment("WB-1", "ORD-1", "C001", "Same city", "Standard", 1.5, 0));
        assertEquals("FS26111000001", b.tracking());
        assertEquals("FAST-2H", b.service());
        assertEquals("2026-11-10T16:00", b.eta());
        assertTrue(ctx.calls.getFirst().startsWith("GET https://example.com/"), ctx.calls.toString());
        assertTrue(ctx.calls.get(1).contains("\"service\":\"FAST-2H\""), ctx.calls.toString());
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
