package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.spi.tenant.TenantContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Ho Chi Minh City hub with three vehicles and twenty stops for one day, written straight through the kernel. */
final class StmsFixtures {

    static final String HUB = "HCM-TB";
    static final String DATE = "2026-10-23";
    static final List<String> PLATES = List.of("51C-12345", "51C-67890", "51D-24680");

    /** 20 stops, 452 kg in total; every fifth stop must be served between 09:00 and 11:00; stops 4, 11 and 18 are VIP. */
    static final double[][] POINTS = {
            {10.7769, 106.7009}, {10.7626, 106.6602}, {10.7867, 106.6779}, {10.8505, 106.7717}, {10.7380, 106.7220},
            {10.8231, 106.6297}, {10.7546, 106.6370}, {10.7950, 106.7218}, {10.8700, 106.8030}, {10.7300, 106.7040},
            {10.8120, 106.7100}, {10.7720, 106.6880}, {10.8390, 106.6650}, {10.7600, 106.6800}, {10.8050, 106.6380},
            {10.7900, 106.6550}, {10.8450, 106.7550}, {10.7480, 106.6950}, {10.8000, 106.7400}, {10.7680, 106.6950}};

    private StmsFixtures() { }

    /** Hub, vehicles of the given capacities and the day's stops, once per tenant. */
    static void tenant(DocumentStore store, String tenant, double... capacities) {
        TenantContext.run(tenant, () -> {
            if (store.get("Hub", HUB).isPresent()) return;
            store.insert("Hub", Map.of("code", HUB, "hub_name", "Kho Tân Bình", "city", "TP.HCM",
                    "latitude", 10.8016, "longitude", 106.6527, "open_from", "07:00", "open_to", "19:00"), "fixture");
            for (int i = 0; i < capacities.length; i++) {
                store.insert("Vehicle", Map.of("plate", PLATES.get(i), "depot_hub", HUB, "capacity_kg", capacities[i]), "fixture");
            }
            for (int i = 0; i < POINTS.length; i++) {
                Map<String, Object> stop = new LinkedHashMap<>();
                stop.put("hub", HUB);
                stop.put("plan_date", DATE);
                stop.put("label", "Shop " + (i + 1));
                stop.put("priority", i % 7 == 3 ? "VIP" : "Normal");
                stop.put("latitude", POINTS[i][0]);
                stop.put("longitude", POINTS[i][1]);
                stop.put("demand_kg", 15 + i * 0.8);
                stop.put("window_from", i % 5 == 0 ? "09:00" : "08:00");
                stop.put("window_to", i % 5 == 0 ? "11:00" : "17:00");
                store.insert("DeliveryStop", stop, "fixture");
            }
        });
    }

    /** A draft plan for the fixture hub and day, solved within a two-second budget. */
    static String solvedPlan(DocumentStore store, String tenant) {
        return TenantContext.call(tenant, () -> store.insert("DispatchPlan",
                Map.of("plan_date", DATE, "hub", HUB, "solve", true, "solve_seconds", 2), "fixture").name());
    }
}
