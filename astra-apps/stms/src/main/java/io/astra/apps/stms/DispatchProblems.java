package io.astra.apps.stms;

import io.astra.apps.stms.solver.PlanResult.RouteRow;
import io.astra.apps.stms.solver.RoutingProblem;
import io.astra.apps.stms.solver.RoutingProblem.Depot;
import io.astra.apps.stms.solver.RoutingProblem.StopSpec;
import io.astra.apps.stms.solver.RoutingProblem.TrafficArea;
import io.astra.apps.stms.solver.RoutingProblem.VehicleSpec;
import io.astra.apps.stms.solver.RoutingProblem.Weights;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns documents of the current tenant (Hub, Vehicle, DeliveryStop, the plan's own settings) into a solver
 * problem, and reads the plan's settings back. Every read goes through {@link DocumentService}, so it is scoped
 * to the caller's tenant by the kernel.
 */
@ApplicationScoped
public class DispatchProblems {

    static final int MAX_STOPS = 500;
    static final int MAX_VEHICLES = 100;

    @Inject DocumentService docs;

    /** The problem for a hub and a day, with the plan's settings (unavailable vehicles, traffic, weights, warm start). */
    public RoutingProblem load(String hub, String date, Set<String> unavailable, List<TrafficArea> traffic, Weights weights,
                               Map<String, List<String>> warmStart, int seconds) {
        Document h = docs.get("Hub", hub).orElseThrow(() -> new DocValidationException("hub " + hub + " not found"));
        Depot depot = new Depot(h.name(), Values.number(h.get("latitude"), "latitude"), Values.number(h.get("longitude"), "longitude"),
                Values.minutes(h.get("open_from") == null ? "07:00" : h.get("open_from"), "open_from"));

        List<VehicleSpec> vehicles = docs.find("Vehicle", Map.of("depot_hub", hub), MAX_VEHICLES).stream()
                .filter(v -> v.get("active") == null || Values.flag(v.get("active")))
                .filter(v -> !unavailable.contains(v.name()))
                .map(v -> new VehicleSpec(v.name(), Values.number(v.get("capacity_kg"), "capacity_kg")))
                .sorted((a, b) -> a.plate().compareTo(b.plate()))
                .toList();

        List<StopSpec> stops = docs.find("DeliveryStop", Map.of("hub", hub, "plan_date", date), MAX_STOPS).stream()
                .map(DispatchProblems::stop)
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .toList();
        return new RoutingProblem(depot, vehicles, stops, traffic, weights, warmStart, seconds);
    }

    /** The problem a draft plan describes, warm-started from the routes it already has. */
    public RoutingProblem forPlan(Document plan, int defaultSeconds) {
        int seconds = plan.get("solve_seconds") == null ? defaultSeconds : (int) Values.number(plan.get("solve_seconds"), "solve_seconds");
        return load(plan.getString("hub"), plan.getString("plan_date"), unavailable(plan), traffic(plan), weights(plan),
                warmStart(routes(plan)), seconds);
    }

    static StopSpec stop(Document d) {
        return new StopSpec(d.name(), d.getString("label"), d.getString("waybill"),
                d.get("kind") == null ? "Delivery" : d.getString("kind"),
                d.get("priority") == null ? "Normal" : d.getString("priority"),
                Values.number(d.get("latitude"), "latitude"), Values.number(d.get("longitude"), "longitude"),
                Values.number(d.get("demand_kg"), "demand_kg"),
                Values.minutes(d.get("window_from") == null ? "08:00" : d.get("window_from"), "window_from"),
                Values.minutes(d.get("window_to") == null ? "17:00" : d.get("window_to"), "window_to"),
                (int) Values.number(d.get("service_minutes"), "service_minutes", 5));
    }

    public static Set<String> unavailable(Document plan) {
        Set<String> out = new LinkedHashSet<>();
        if (plan.get("unavailable_vehicles") instanceof List<?> l) l.forEach(v -> out.add(String.valueOf(v)));
        else if (plan.get("unavailable_vehicles") != null) {
            throw new DocValidationException("'unavailable_vehicles' must be a list of plates");
        }
        return out;
    }

    public static List<TrafficArea> traffic(Document plan) {
        List<TrafficArea> out = new ArrayList<>();
        Object v = plan.get("traffic");
        if (v == null) return out;
        if (!(v instanceof List<?> list)) throw new DocValidationException("'traffic' must be a list of areas");
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new DocValidationException("each traffic area must be an object");
            double lat = Values.number(m.get("latitude"), "traffic.latitude");
            double lon = Values.number(m.get("longitude"), "traffic.longitude");
            Values.requireLatLon(lat, lon);
            double radius = Values.number(m.get("radius_km"), "traffic.radius_km", 3);
            double factor = Values.number(m.get("factor"), "traffic.factor", 2);
            if (radius <= 0 || radius > 50) throw new DocValidationException("traffic radius_km must be in (0, 50]");
            if (factor < 1 || factor > 10) throw new DocValidationException("traffic factor must be in [1, 10]");
            out.add(new TrafficArea(m.get("label") == null ? "" : m.get("label").toString(), lat, lon, radius, factor));
        }
        return out;
    }

    public static Weights weights(Document plan) {
        Object v = plan.get("weights");
        if (v == null) return Weights.DEFAULT;
        if (!(v instanceof Map<?, ?> m)) throw new DocValidationException("'weights' must be an object {distance, priority, balance}");
        for (Object k : m.keySet()) {
            if (!Set.of("distance", "priority", "balance").contains(String.valueOf(k))) {
                throw new DocValidationException("unknown weight '" + k + "'; use distance, priority, balance");
            }
        }
        try {
            return new Weights(weight(m.get("distance"), Weights.DEFAULT.distance()),
                    weight(m.get("priority"), Weights.DEFAULT.priority()),
                    weight(m.get("balance"), Weights.DEFAULT.balance()));
        } catch (IllegalArgumentException e) {
            throw new DocValidationException(e.getMessage());
        }
    }

    private static long weight(Object v, long fallback) {
        long w = Math.round(Values.number(v, "weight", fallback));
        if (w > 1_000_000) throw new DocValidationException("weights are capped at 1000000");
        return w;
    }

    public static List<RouteRow> routes(Document plan) {
        if (!(plan.get("routes") instanceof List<?> l)) return List.of();
        List<RouteRow> rows = new ArrayList<>();
        for (Object o : l) if (o instanceof Map<?, ?> m) rows.add(RouteRow.fromMap(m));
        return rows;
    }

    static Map<String, List<String>> warmStart(List<RouteRow> rows) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (RouteRow r : rows) m.computeIfAbsent(r.vehicle(), k -> new ArrayList<>()).add(r.stop());
        return m;
    }
}
