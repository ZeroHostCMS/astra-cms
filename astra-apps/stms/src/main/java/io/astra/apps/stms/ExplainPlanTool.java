package io.astra.apps.stms;

import io.astra.apps.stms.solver.PlanResult.RouteRow;
import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool: why the plan looks the way it does — per-vehicle load and timing, priority stops, the live events applied. */
@ApplicationScoped
public class ExplainPlanTool implements McpTool {

    @Inject Replanner replanner;
    @Inject DocumentService docs;

    @Override public String name() { return "stms_explain_plan"; }

    @Override public String description() {
        return "Explain a dispatch plan: score, distance, each vehicle's stops, load against capacity and first/last ETA, "
                + "how many VIP/urgent stops are served before a deadline, the weights in force and the events applied so far.";
    }

    @Override public Map<String, Object> inputSchema() {
        return ReplanTools.schema(List.of("plan"), Map.of("plan", ReplanTools.PLAN,
                "priority_deadline", ReplanTools.str("HH:mm; count VIP/urgent stops served by then, default 10:00")));
    }

    @Override public List<String> reads() { return List.of("DispatchPlan", "DispatchEvent", "Vehicle"); }

    @Override public Object call(Map<String, Object> args) {
        Document plan = replanner.plan(Values.text(args, "plan"));
        String deadline = Values.clock(Values.minutes(args.get("priority_deadline") == null ? "10:00" : args.get("priority_deadline"),
                "priority_deadline"));
        List<RouteRow> rows = DispatchProblems.routes(plan);

        Map<String, Map<String, Object>> vehicles = new LinkedHashMap<>();
        for (RouteRow r : rows) {
            Map<String, Object> v = vehicles.computeIfAbsent(r.vehicle(), plate -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("vehicle", plate);
                m.put("capacity_kg", docs.get("Vehicle", plate).map(d -> d.get("capacity_kg")).orElse(null));
                m.put("stops", 0);
                m.put("first_eta", r.eta());
                return m;
            });
            v.put("stops", (int) v.get("stops") + 1);
            v.put("load_kg", r.loadKg());
            v.put("last_eta", r.eta());
        }
        List<RouteRow> priority = rows.stream().filter(r -> "VIP".equals(r.priority()) || "Urgent".equals(r.priority())).toList();
        List<String> lateForDeadline = new ArrayList<>();
        for (RouteRow r : priority) if (r.start().compareTo(deadline) > 0) lateForDeadline.add(r.stop() + " " + r.label() + " @" + r.start());

        Map<String, Object> out = new LinkedHashMap<>(Replanner.view(plan));
        out.remove("routes");
        out.put("vehicles", List.copyOf(vehicles.values()));
        out.put("priority_stops", Map.of("total", priority.size(), "deadline", deadline,
                "served_by_deadline", priority.size() - lateForDeadline.size(), "after_deadline", lateForDeadline));
        out.put("last_change", plan.get("last_change"));
        out.put("events", docs.find("DispatchEvent", Map.of("plan", plan.name()), 10).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", e.name());
            m.put("event_type", e.getString("event_type"));
            m.put("source", e.getString("source"));
            m.put("score_before", e.getString("score_before"));
            m.put("score_after", e.getString("score_after"));
            m.put("moved_stops", e.get("moved_stops"));
            return m;
        }).toList());
        return out;
    }
}
