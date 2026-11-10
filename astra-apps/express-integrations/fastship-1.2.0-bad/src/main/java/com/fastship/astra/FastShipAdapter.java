package com.fastship.astra;

import io.astra.spi.app.AstraPlugin;
import io.astra.spi.app.PluginContext;
import io.astra.spi.integration.CapabilityDeniedException;
import io.astra.spi.integration.CarrierAdapter;
import io.astra.spi.integration.PartnerHttp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FastShip last-mile booking, version 1.2.0-bad: a deliberately broken build for the rollback demo. A refactoring
 * of the zone mapping lost the Remote zone, so every Remote parcel fails; everything else works like 1.1.
 */
public class FastShipAdapter implements CarrierAdapter, AstraPlugin {

    private static final String ADDRESS_HINTS = "https://example.com/geo/hints";

    private PartnerHttp http;
    private String baseUrl;
    private System.Logger log;

    @Override
    public void start(PluginContext context) {
        this.http = context.http();
        this.baseUrl = context.config().getOrDefault("base-url", "http://partner-sandbox:8090");
        this.log = context.log();
    }

    @Override
    public Booking book(Shipment s) {
        String service = switch (s.zone()) {
            case "Same city" -> "FAST-2H";
            case "Domestic" -> "STD";
            default -> throw new IllegalArgumentException("unknown zone " + s.zone());   // the bug: Remote is gone
        };
        addressHints(s);
        String body = String.format(java.util.Locale.ROOT,
                "{\"waybill\":\"%s\",\"zone\":\"%s\",\"service\":\"%s\",\"weight_kg\":%.2f,\"cod\":%d}",
                s.waybill(), s.zone(), service, s.weightKg(), s.codAmount());
        PartnerHttp.Response r = http.postJson(baseUrl + "/fastship/bookings", body);
        if (!r.ok()) throw new IllegalStateException("FastShip answered HTTP " + r.status() + ": " + r.body());
        return new Booking("FastShip", field(r.body(), "tracking"), Long.parseLong(field(r.body(), "fee")), service, field(r.body(), "eta"));
    }

    /** Optional: better routing with address hints. Not in capabilities.network, so the host says no. */
    private void addressHints(Shipment s) {
        try {
            http.get(ADDRESS_HINTS + "?waybill=" + s.waybill());
        } catch (CapabilityDeniedException e) {
            log.log(System.Logger.Level.DEBUG, "address hints unavailable: " + e.getMessage());
        }
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]*)").matcher(json);
        if (!m.find()) throw new IllegalStateException("FastShip answer has no '" + name + "': " + json);
        return m.group(1).trim();
    }
}
