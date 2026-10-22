package io.astra.apps.stms;

import io.astra.apps.stms.solver.PlanResult;
import io.astra.apps.stms.solver.RoutePlanner;
import io.astra.apps.stms.solver.RoutingProblem.Weights;
import io.astra.spi.ai.McpTool;
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
 * MCP tools that solve and replan dispatch plans. Each runs in the caller's tenant; the MCP server checks the caller
 * may read every DocType in {@link #reads()}, and {@code DispatchEvent} there limits replanning to admin and dispatcher.
 */
public final class ReplanTools {

    private ReplanTools() { }

    static Map<String, Object> schema(List<String> required, Map<String, Object> properties) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (!required.isEmpty()) s.put("required", required);
        return s;
    }

    static Map<String, Object> str(String description) { return Map.of("type", "string", "description", description); }

    static Map<String, Object> num(String description) { return Map.of("type", "number", "description", description); }

    static final Map<String, Object> PLAN = str("dispatch plan name, e.g. DP-2610-0001");

    /** Solve or inspect a plan, or propose routes for a hub and day without saving anything. */
    @ApplicationScoped
    public static class OptimizeRoute implements McpTool {
        @Inject Replanner replanner;
        @Inject DispatchProblems problems;
        @Inject RoutePlanner planner;

        @Override public String name() { return "stms_optimize_route"; }

        @Override public String description() {
            return "Vehicle routing with capacity and time windows (Timefold). With 'plan': re-solve that draft dispatch plan, "
                    + "warm-started from its current routes, and save the result (a dispatched plan is only shown). With 'hub' "
                    + "and 'date': propose routes for that day's stops without saving. Returns score (hard/soft; hard 0 = feasible), "
                    + "distance and one row per stop: vehicle, sequence, ETA, load.";
        }

        @Override public Map<String, Object> inputSchema() {
            return schema(List.of(), Map.of("plan", PLAN, "hub", str("hub code, e.g. HCM-TB"),
                    "date", str("plan date YYYY-MM-DD"),
                    "seconds", Map.of("type", "integer", "minimum", 1, "maximum", RoutePlanner.MAX_SECONDS,
                            "description", "solving time budget for a proposal")));
        }

        @Override public List<String> reads() { return Replanner.REPLAN_READS; }

        @Override public Object call(Map<String, Object> args) {
            if (args.get("plan") != null) {
                String name = Values.text(args, "plan");
                Document plan = replanner.plan(name);
                if (plan.docstatus() != 0) return Replanner.view(plan);
                Map<String, Object> out = new LinkedHashMap<>(replanner.replan(name, "Solve", Map.of(), Map.of()));
                out.put("routes", replanner.plan(name).get("routes"));
                return out;
            }
            String hub = Values.text(args, "hub");
            String date = Values.text(args, "date");
            int seconds = (int) Values.number(args.get("seconds"), "seconds", planner.defaultSeconds());
            PlanResult r = planner.solve(problems.load(hub, date, Set.of(), List.of(), Weights.DEFAULT, Map.of(),
                    RoutePlanner.clampSeconds(seconds)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("proposal", true);
            out.put("hub", hub);
            out.put("plan_date", date);
            out.put("score", r.score());
            out.put("feasible", r.feasible());
            out.put("distance_km", r.distanceKm());
            out.put("vehicles_used", r.vehiclesUsed());
            out.put("solve_ms", r.solveMillis());
            out.put("violations", r.violations());
            out.put("routes", r.rowMaps());
            out.put("note", "Not saved. Create a DispatchPlan for this hub and date and solve it to keep these routes.");
            return out;
        }
    }

    /** A vehicle breaks down: take it off this plan and move its stops to the others. */
    @ApplicationScoped
    public static class VehicleUnavailable implements McpTool {
        @Inject Replanner replanner;
        @Inject DocumentService docs;

        @Override public String name() { return "stms_vehicle_unavailable"; }

        @Override public String description() {
            return "Mark a vehicle unavailable for a draft dispatch plan (breakdown, accident, driver absent) and replan: "
                    + "its stops are reassigned to the other vehicles. Returns score before/after and the stops that moved.";
        }

        @Override public Map<String, Object> inputSchema() {
            return schema(List.of("plan", "vehicle"), Map.of("plan", PLAN, "vehicle", str("licence plate, e.g. 51C-12345"),
                    "reason", str("why, e.g. 'hỏng máy ở Thủ Đức'")));
        }

        @Override public List<String> reads() { return Replanner.REPLAN_READS; }

        @Override public Object call(Map<String, Object> args) {
            String planName = Values.text(args, "plan");
            String plate = Values.text(args, "vehicle");
            Document plan = replanner.draftPlan(planName);
            Document vehicle = docs.get("Vehicle", plate).orElseThrow(() -> new DocValidationException("vehicle " + plate + " not found"));
            if (!plan.getString("hub").equals(vehicle.getString("depot_hub"))) {
                throw new DocValidationException("vehicle " + plate + " belongs to hub " + vehicle.getString("depot_hub")
                        + ", not " + plan.getString("hub"));
            }
            Set<String> off = new LinkedHashSet<>(DispatchProblems.unavailable(plan));
            off.add(plate);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("vehicle", plate);
            if (args.get("reason") != null) detail.put("reason", args.get("reason").toString());
            return replanner.replan(planName, "Vehicle unavailable", detail, Map.of("unavailable_vehicles", List.copyOf(off)));
        }
    }

    /** An urgent order comes in during the day: add the stop and fit it into the routes. */
    @ApplicationScoped
    public static class AddUrgentStop implements McpTool {
        @Inject Replanner replanner;
        @Inject DocumentService docs;

        @Override public String name() { return "stms_add_urgent_stop"; }

        @Override public String description() {
            return "Add an urgent stop to a draft dispatch plan (same hub and day) and replan so it is served inside its time window.";
        }

        @Override public Map<String, Object> inputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("plan", PLAN);
            p.put("label", str("shop or place name"));
            p.put("latitude", num("latitude"));
            p.put("longitude", num("longitude"));
            p.put("demand_kg", num("weight to carry, kg"));
            p.put("window_from", str("earliest service time HH:mm, default 08:00"));
            p.put("window_to", str("latest service time HH:mm"));
            p.put("service_minutes", Map.of("type", "integer", "description", "minutes on site, default 5"));
            p.put("priority", Map.of("type", "string", "enum", List.of("Urgent", "VIP", "Normal")));
            p.put("waybill", str("linked waybill number, optional"));
            return schema(List.of("plan", "label", "latitude", "longitude", "demand_kg", "window_to"), p);
        }

        @Override public List<String> reads() { return Replanner.REPLAN_READS; }

        @Override public Object call(Map<String, Object> args) {
            String planName = Values.text(args, "plan");
            Document plan = replanner.draftPlan(planName);
            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("hub", plan.getString("hub"));
            stop.put("plan_date", plan.getString("plan_date"));
            stop.put("kind", "Delivery");
            stop.put("priority", args.get("priority") == null ? "Urgent" : args.get("priority").toString());
            stop.put("label", Values.text(args, "label"));
            stop.put("latitude", Values.number(args.get("latitude"), "latitude"));
            stop.put("longitude", Values.number(args.get("longitude"), "longitude"));
            stop.put("demand_kg", Values.number(args.get("demand_kg"), "demand_kg"));
            stop.put("window_from", args.get("window_from") == null ? "08:00" : args.get("window_from").toString());
            stop.put("window_to", Values.text(args, "window_to"));
            stop.put("service_minutes", (int) Values.number(args.get("service_minutes"), "service_minutes", 5));
            if (args.get("waybill") != null) stop.put("waybill", args.get("waybill").toString());
            Document saved = docs.insert("DeliveryStop", stop, Replanner.OWNER);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("stop", saved.name());
            detail.put("label", saved.getString("label"));
            detail.put("demand_kg", saved.get("demand_kg"));
            detail.put("window", saved.getString("window_from") + "-" + saved.getString("window_to"));
            Map<String, Object> out = new LinkedHashMap<>(replanner.replan(planName, "Urgent stop", detail, Map.of()));
            out.put("stop", saved.name());
            return out;
        }
    }

    /** Congestion in an area: travel times on legs touching it are multiplied, then the plan is repaired. */
    @ApplicationScoped
    public static class TrafficDelay implements McpTool {
        @Inject Replanner replanner;

        @Override public String name() { return "stms_traffic_delay"; }

        @Override public String description() {
            return "Report congestion around a point: travel time on every leg that starts or ends within radius_km is "
                    + "multiplied by factor (e.g. 2 = twice as slow), then the draft plan is replanned to keep time windows.";
        }

        @Override public Map<String, Object> inputSchema() {
            return schema(List.of("plan", "latitude", "longitude"), Map.of("plan", PLAN, "label", str("area name"),
                    "latitude", num("centre latitude"), "longitude", num("centre longitude"),
                    "radius_km", num("radius in km, default 3"), "factor", num("slowdown factor 1-10, default 2")));
        }

        @Override public List<String> reads() { return Replanner.REPLAN_READS; }

        @Override public Object call(Map<String, Object> args) {
            String planName = Values.text(args, "plan");
            Document plan = replanner.draftPlan(planName);
            Map<String, Object> area = new LinkedHashMap<>();
            area.put("label", args.get("label") == null ? "" : args.get("label").toString());
            area.put("latitude", Values.number(args.get("latitude"), "latitude"));
            area.put("longitude", Values.number(args.get("longitude"), "longitude"));
            area.put("radius_km", Values.number(args.get("radius_km"), "radius_km", 3));
            area.put("factor", Values.number(args.get("factor"), "factor", 2));
            List<Object> traffic = new ArrayList<>(plan.get("traffic") instanceof List<?> l ? l : List.of());
            traffic.add(area);
            return replanner.replan(planName, "Traffic delay", area, Map.of("traffic", traffic));
        }
    }

    /** A customer moves their delivery window. */
    @ApplicationScoped
    public static class ChangeTimeWindow implements McpTool {
        @Inject Replanner replanner;
        @Inject DocumentService docs;

        @Override public String name() { return "stms_change_time_window"; }

        @Override public String description() {
            return "Change the time window of one stop of a draft dispatch plan (the customer asked for another time) and replan.";
        }

        @Override public Map<String, Object> inputSchema() {
            return schema(List.of("plan", "stop", "window_from", "window_to"), Map.of("plan", PLAN,
                    "stop", str("delivery stop name, e.g. DS-2610-00007"),
                    "window_from", str("HH:mm"), "window_to", str("HH:mm")));
        }

        @Override public List<String> reads() { return Replanner.REPLAN_READS; }

        @Override public Object call(Map<String, Object> args) {
            String planName = Values.text(args, "plan");
            Document plan = replanner.draftPlan(planName);
            String stopName = Values.text(args, "stop");
            Document stop = docs.get("DeliveryStop", stopName).orElseThrow(() -> new DocValidationException("stop " + stopName + " not found"));
            if (!plan.getString("hub").equals(stop.getString("hub")) || !plan.getString("plan_date").equals(stop.getString("plan_date"))) {
                throw new DocValidationException("stop " + stopName + " is not part of plan " + planName);
            }
            String from = Values.clock(Values.minutes(Values.text(args, "window_from"), "window_from"));
            String to = Values.clock(Values.minutes(Values.text(args, "window_to"), "window_to"));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("stop", stopName);
            detail.put("label", stop.getString("label"));
            detail.put("window_before", stop.getString("window_from") + "-" + stop.getString("window_to"));
            detail.put("window_after", from + "-" + to);
            docs.update("DeliveryStop", stopName, Map.of("window_from", from, "window_to", to));
            return replanner.replan(planName, "Time window", detail, Map.of());
        }
    }

    /** Re-weight the soft goals: distance, priority stops early, balanced load. */
    @ApplicationScoped
    public static class SetWeights implements McpTool {
        @Inject Replanner replanner;

        @Override public String name() { return "stms_set_weights"; }

        @Override public String description() {
            return "Change the soft-constraint weights of a draft dispatch plan and replan. distance: per road metre (default 1); "
                    + "priority: per minute a VIP or urgent stop waits after departure (default 20); balance: per kg² of load on "
                    + "each vehicle (default 0, raise to spread load). Hard constraints (capacity, time windows) never change.";
        }

        @Override public Map<String, Object> inputSchema() {
            return schema(List.of("plan"), Map.of("plan", PLAN, "distance", num("weight per metre"),
                    "priority", num("weight per minute for VIP/urgent stops"), "balance", num("weight per kg² of vehicle load")));
        }

        @Override public List<String> reads() { return Replanner.REPLAN_READS; }

        @Override public Object call(Map<String, Object> args) {
            String planName = Values.text(args, "plan");
            Document plan = replanner.draftPlan(planName);
            Map<String, Object> weights = new LinkedHashMap<>(DispatchProblems.weights(plan).toMap());
            boolean any = false;
            for (String k : List.of("distance", "priority", "balance")) {
                if (args.get(k) != null) {
                    weights.put(k, Math.round(Values.number(args.get(k), k)));
                    any = true;
                }
            }
            if (!any) throw new DocValidationException("give at least one of distance, priority, balance");
            return replanner.replan(planName, "Weights", Map.of("weights", weights), Map.of("weights", weights));
        }
    }
}
