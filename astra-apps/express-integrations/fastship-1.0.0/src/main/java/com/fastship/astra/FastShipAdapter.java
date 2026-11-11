package com.fastship.astra;

import io.astra.spi.app.AstraPlugin;
import io.astra.spi.app.PluginContext;
import io.astra.spi.integration.CarrierAdapter;
import io.astra.spi.integration.PartnerHttp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FastShip last-mile booking, version 1.0: one service, a tracking number and the carrier's fee. */
public class FastShipAdapter implements CarrierAdapter, AstraPlugin {

    private PartnerHttp http;
    private String baseUrl;

    @Override
    public void start(PluginContext context) {
        this.http = context.http();
        this.baseUrl = context.config().getOrDefault("base-url", "http://partner-sandbox:8090");
    }

    @Override
    public Booking book(Shipment s) {
        String body = String.format(java.util.Locale.ROOT,
                "{\"waybill\":\"%s\",\"zone\":\"%s\",\"service\":\"STD\",\"weight_kg\":%.2f,\"cod\":%d}",
                s.waybill(), s.zone(), s.weightKg(), s.codAmount());
        PartnerHttp.Response r = http.postJson(baseUrl + "/fastship/bookings", body);
        if (!r.ok()) throw new IllegalStateException("FastShip answered HTTP " + r.status() + ": " + r.body());
        return new Booking("FastShip", field(r.body(), "tracking"), Long.parseLong(field(r.body(), "fee")), "STD", "");
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]*)").matcher(json);
        if (!m.find()) throw new IllegalStateException("FastShip answer has no '" + name + "': " + json);
        return m.group(1).trim();
    }
}
