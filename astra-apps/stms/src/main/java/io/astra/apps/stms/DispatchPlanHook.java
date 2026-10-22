package io.astra.apps.stms;

import io.astra.apps.stms.solver.PlanDiff;
import io.astra.apps.stms.solver.PlanResult;
import io.astra.apps.stms.solver.PlanResult.RouteRow;
import io.astra.apps.stms.solver.RoutePlanner;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The solve action and the dispatch lifecycle. Saving a draft with {@code solve: true} routes it: the hub's active
 * vehicles (minus the plan's unavailable ones) and the day's stops go to Timefold, warm-started from the routes the
 * plan already has, and the result is written back in the same save. Submit dispatches a feasible plan and freezes
 * it (the kernel makes submitted documents immutable).
 */
@ApplicationScoped
public class DispatchPlanHook implements DocLifecycleHook {

    private static final Logger LOG = Logger.getLogger(DispatchPlanHook.class);

    @Inject DispatchProblems problems;
    @Inject RoutePlanner planner;

    @Override
    public String doctype() { return "DispatchPlan"; }

    @Override
    public void beforeSave(Document doc) {
        // settings are validated on every save, not only when solving
        DispatchProblems.unavailable(doc);
        DispatchProblems.traffic(doc);
        DispatchProblems.weights(doc);
        if (doc.get("solve_seconds") != null) {
            double s = Values.number(doc.get("solve_seconds"), "solve_seconds");
            if (s < 1 || s > RoutePlanner.MAX_SECONDS) throw new DocValidationException("solve_seconds must be in [1, " + RoutePlanner.MAX_SECONDS + "]");
        }
        if (!Values.flag(doc.get("solve"))) return;
        doc.set("solve", false);
        solve(doc);
    }

    private void solve(Document doc) {
        List<RouteRow> before = DispatchProblems.routes(doc);
        String scoreBefore = doc.getString("score");
        Object kmBefore = doc.get("distance_km");
        PlanResult r;
        try {
            r = planner.solve(problems.forPlan(doc, planner.defaultSeconds()));
        } catch (IllegalArgumentException e) {
            throw new DocValidationException(e.getMessage());
        }
        LOG.infof("Dispatch plan %s for %s on %s: %s, %.1f km, %d stops on %d vehicle(s), solved in %d ms",
                doc.name() == null ? "(new)" : doc.name(), doc.getString("hub"), doc.getString("plan_date"), r.score(), r.distanceKm(), r.rows().size(),
                r.vehiclesUsed(), r.solveMillis());

        PlanDiff diff = PlanDiff.between(before, r.rows());
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("score_before", scoreBefore);
        change.put("score_after", r.score());
        change.put("distance_km_before", kmBefore);
        change.put("distance_km_after", r.distanceKm());
        change.put("moved", before.isEmpty() ? List.of() : diff.moved().stream().map(PlanDiff.Move::toMap).toList());
        change.put("resequenced", before.isEmpty() ? 0 : diff.resequenced());
        change.put("solve_ms", r.solveMillis());
        change.put("violations", r.violations());

        doc.set("routes", r.rowMaps())
                .set("route_summary", r.summary())
                .set("score", r.score())
                .set("feasible", r.feasible())
                .set("distance_km", r.distanceKm())
                .set("vehicles_used", r.vehiclesUsed())
                .set("solve_ms", r.solveMillis())
                .set("status", r.feasible() ? "Solved" : "Infeasible")
                .set("solved_for", solvedFor(doc))
                .set("solved_at", Instant.now().toString())
                .set("last_change", change);
    }

    @Override
    public void onSubmit(Document doc) {
        if (!"Solved".equals(doc.getString("status")) || !Values.flag(doc.get("feasible"))) {
            throw new DocValidationException("only a solved, feasible plan can be dispatched (status is " + doc.getString("status") + ")");
        }
        if (!solvedFor(doc).equals(doc.getString("solved_for"))) {
            throw new DocValidationException("hub or date changed since the plan was solved; solve it again");
        }
        doc.set("status", "Dispatched");
    }

    @Override
    public void onCancel(Document doc) {
        doc.set("status", "Cancelled");
    }

    private static String solvedFor(Document doc) {
        return doc.getString("hub") + "|" + doc.getString("plan_date");
    }
}
