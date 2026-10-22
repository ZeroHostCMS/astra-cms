package io.astra.apps.stms;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies one live event to a draft dispatch plan: patches the plan's settings, re-solves it warm-started from its
 * current routes (through {@link DispatchPlanHook}) and records a DispatchEvent with the score before and after and
 * the stops that changed vehicle.
 */
@ApplicationScoped
public class Replanner {

    /**
     * DocTypes a replanning tool declares it reads. DispatchEvent is readable only by roles that may replan
     * (admin, dispatcher), so the MCP server's read check is what keeps other roles from replanning.
     */
    public static final List<String> REPLAN_READS = List.of("DispatchPlan", "DispatchEvent", "Vehicle", "DeliveryStop", "Hub");

    /** Who asked: the copilot binds "Copilot" while it executes its tool calls; direct MCP calls are "MCP". */
    public static final ScopedValue<String> SOURCE = ScopedValue.newInstance();

    /** Owner of the event documents: the SPI does not hand app tools the caller's identity (the MCP server logs it). */
    static final String OWNER = "stms-mcp";

    @Inject DocumentService docs;

    public Document plan(String name) {
        return docs.get("DispatchPlan", name).orElseThrow(() -> new DocValidationException("dispatch plan " + name + " not found"));
    }

    public Document draftPlan(String name) {
        Document plan = plan(name);
        if (plan.docstatus() != 0) {
            throw new DocValidationException("dispatch plan " + name + " is " + (plan.docstatus() == 1 ? "dispatched" : "cancelled")
                    + " and frozen; amend it to replan");
        }
        return plan;
    }

    /** Patches the plan, re-solves it and logs the event; returns what changed. */
    public Map<String, Object> replan(String planName, String eventType, Map<String, Object> detail, Map<String, Object> planPatch) {
        draftPlan(planName);
        Map<String, Object> patch = new LinkedHashMap<>(planPatch);
        patch.put("solve", true);
        Document after = docs.update("DispatchPlan", planName, patch)
                .orElseThrow(() -> new DocValidationException("dispatch plan " + planName + " not found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> change = after.get("last_change") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<?> moved = change.get("moved") instanceof List<?> l ? l : List.of();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("plan", planName);
        event.put("event_type", eventType);
        event.put("source", SOURCE.isBound() ? SOURCE.get() : "MCP");
        event.put("detail", detail);
        event.put("score_before", change.get("score_before"));
        event.put("score_after", change.get("score_after"));
        event.put("moved_stops", moved.size());
        event.put("solve_ms", change.get("solve_ms"));
        Document logged = docs.insert("DispatchEvent", event, OWNER);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", planName);
        out.put("event", logged.name());
        out.put("event_type", eventType);
        out.put("detail", detail);
        out.put("status", after.getString("status"));
        out.put("feasible", Values.flag(after.get("feasible")));
        out.put("score_before", change.get("score_before"));
        out.put("score_after", change.get("score_after"));
        out.put("distance_km_before", change.get("distance_km_before"));
        out.put("distance_km_after", change.get("distance_km_after"));
        out.put("moved", moved);
        out.put("resequenced", change.getOrDefault("resequenced", 0));
        out.put("solve_ms", change.get("solve_ms"));
        out.put("violations", change.getOrDefault("violations", List.of()));
        return out;
    }

    /** The plan as an agent sees it: settings, score and the route rows. */
    public static Map<String, Object> view(Document plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", plan.name());
        m.put("docstatus", plan.docstatus());
        m.put("hub", plan.getString("hub"));
        m.put("plan_date", plan.getString("plan_date"));
        m.put("status", plan.getString("status"));
        m.put("score", plan.getString("score"));
        m.put("feasible", Values.flag(plan.get("feasible")));
        m.put("distance_km", plan.get("distance_km"));
        m.put("vehicles_used", plan.get("vehicles_used"));
        m.put("solve_ms", plan.get("solve_ms"));
        m.put("weights", DispatchProblems.weights(plan).toMap());
        m.put("unavailable_vehicles", DispatchProblems.unavailable(plan));
        m.put("traffic", plan.get("traffic") == null ? List.of() : plan.get("traffic"));
        m.put("routes", plan.get("routes") == null ? List.of() : plan.get("routes"));
        return m;
    }
}
