package io.astra.apps.stms.solver;

import io.astra.apps.stms.solver.PlanResult.RouteRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** What a replan changed: stops that changed vehicle (or are new), and stops that kept their vehicle but moved in its order. */
public record PlanDiff(List<Move> moved, int resequenced) {

    /** {@code eta} is the arrival, {@code start} the service start (later when the vehicle waits for the window). */
    public record Move(String stop, String label, String from, String to, String eta, String start) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stop", stop);
            m.put("label", label);
            m.put("from", from);
            m.put("to", to);
            m.put("eta", eta);
            m.put("start", start);
            return m;
        }
    }

    public static PlanDiff between(List<RouteRow> before, List<RouteRow> after) {
        Map<String, RouteRow> old = new HashMap<>();
        before.forEach(r -> old.put(r.stop(), r));
        List<Move> moved = new ArrayList<>();
        int resequenced = 0;
        for (RouteRow r : after) {
            RouteRow was = old.get(r.stop());
            if (was == null || !Objects.equals(was.vehicle(), r.vehicle())) {
                moved.add(new Move(r.stop(), r.label(), was == null ? null : was.vehicle(), r.vehicle(), r.eta(), r.start()));
            } else if (was.sequence() != r.sequence()) {
                resequenced++;
            }
        }
        return new PlanDiff(List.copyOf(moved), resequenced);
    }
}
