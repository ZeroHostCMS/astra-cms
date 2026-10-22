package io.astra.apps.stms.solver;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import io.astra.apps.stms.solver.PlanResult.RouteRow;
import io.astra.apps.stms.solver.RoutingProblem.Depot;
import io.astra.apps.stms.solver.RoutingProblem.StopSpec;
import io.astra.apps.stms.solver.RoutingProblem.TrafficArea;
import io.astra.apps.stms.solver.RoutingProblem.VehicleSpec;
import io.astra.apps.stms.solver.RoutingProblem.Weights;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The solver itself, outside Quarkus: feasibility, infeasibility, and every live replanning event. */
class RoutePlannerTest {

    static final RoutePlanner PLANNER = new RoutePlanner(SolverFactory.create(new SolverConfig()
            .withSolutionClass(RoutePlan.class)
            .withEntityClasses(PlanVehicle.class, PlanVisit.class)
            .withConstraintProviderClass(RoutingConstraints.class)), 25, 1.3, 2);

    static final Depot TAN_BINH = new Depot("HCM-TB", 10.8016, 106.6527, 7 * 60);

    /** 20 stops around Ho Chi Minh City: 452 kg in total, a few tight windows, three VIP stops. */
    static List<StopSpec> hcmcStops() {
        double[][] points = {
                {10.7769, 106.7009}, {10.7626, 106.6602}, {10.7867, 106.6779}, {10.8505, 106.7717}, {10.7380, 106.7220},
                {10.8231, 106.6297}, {10.7546, 106.6370}, {10.7950, 106.7218}, {10.8700, 106.8030}, {10.7300, 106.7040},
                {10.8120, 106.7100}, {10.7720, 106.6880}, {10.8390, 106.6650}, {10.7600, 106.6800}, {10.8050, 106.6380},
                {10.7900, 106.6550}, {10.8450, 106.7550}, {10.7480, 106.6950}, {10.8000, 106.7400}, {10.7680, 106.6950}};
        List<StopSpec> stops = new ArrayList<>();
        for (int i = 0; i < points.length; i++) {
            String priority = i % 7 == 3 ? "VIP" : "Normal";
            int from = i % 5 == 0 ? 9 * 60 : 8 * 60;
            int to = i % 5 == 0 ? 11 * 60 : 17 * 60;
            stops.add(new StopSpec("S%02d".formatted(i + 1), "Stop " + (i + 1), null, "Delivery", priority,
                    points[i][0], points[i][1], 15 + i * 0.8, from, to, 5));
        }
        return stops;
    }

    static List<VehicleSpec> fleet() {
        return List.of(new VehicleSpec("51C-12345", 300), new VehicleSpec("51C-67890", 250), new VehicleSpec("51D-24680", 250));
    }

    static RoutingProblem problem(List<VehicleSpec> vehicles, List<StopSpec> stops, List<TrafficArea> traffic,
                                  Weights weights, Map<String, List<String>> warm) {
        return new RoutingProblem(TAN_BINH, vehicles, stops, traffic, weights, warm, 2);
    }

    static Map<String, List<String>> warm(PlanResult r) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (RouteRow row : r.rows()) m.computeIfAbsent(row.vehicle(), k -> new ArrayList<>()).add(row.stop());
        return m;
    }

    static void assertRespectsHardConstraints(PlanResult r, List<VehicleSpec> vehicles, int stops) {
        assertTrue(r.feasible(), () -> "infeasible: " + r.violations());
        assertEquals(0, r.hardScore());
        assertEquals(stops, r.rows().size(), "every stop is routed");
        Map<String, Double> load = new HashMap<>();
        for (RouteRow row : r.rows()) {
            load.merge(row.vehicle(), row.demandKg(), Double::sum);
            assertEquals(0, row.lateMinutes(), () -> row.stop() + " is late");
            assertTrue(row.start().compareTo(row.window().substring(6)) <= 0, () -> row.stop() + " starts after " + row.window());
        }
        for (VehicleSpec v : vehicles) assertTrue(load.getOrDefault(v.plate(), 0.0) <= v.capacityKg() + 1e-9, v.plate() + " overloaded");
    }

    @Test
    void solvesAFeasiblePlanRespectingCapacityAndTimeWindows() {
        PlanResult r = PLANNER.solve(problem(fleet(), hcmcStops(), null, null, null));
        assertRespectsHardConstraints(r, fleet(), 20);
        assertTrue(r.distanceKm() > 0);
        assertTrue(r.solveMillis() <= 2_500, "bounded by the time budget");
        assertTrue(r.summary().contains("feasible"));
    }

    @Test
    void anOverloadedFleetIsReportedThroughTheHardScore() {
        List<VehicleSpec> tiny = List.of(new VehicleSpec("51C-00001", 100));
        PlanResult r = PLANNER.solve(problem(tiny, hcmcStops(), null, null, null));
        assertFalse(r.feasible());
        assertTrue(r.hardScore() <= -(452 - 100), "every kilogram over capacity costs one hard point: " + r.score());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("capacity")));
    }

    @Test
    void aBrokenDownVehicleIsReplacedFromTheCurrentPlan() {
        PlanResult before = PLANNER.solve(problem(fleet(), hcmcStops(), null, null, null));
        List<VehicleSpec> remaining = fleet().stream().filter(v -> !v.plate().equals("51C-12345")).toList();
        PlanResult after = PLANNER.solve(problem(remaining, hcmcStops(), null, null, warm(before)));
        assertRespectsHardConstraints(after, remaining, 20);
        PlanDiff diff = PlanDiff.between(before.rows(), after.rows());
        long fromBroken = before.rows().stream().filter(r -> r.vehicle().equals("51C-12345")).count();
        assertTrue(diff.moved().size() >= fromBroken, "every stop of the broken vehicle moved");
        assertTrue(diff.moved().stream().allMatch(m -> !"51C-12345".equals(m.to())));
    }

    @Test
    void anUrgentStopIsInsertedAndServedInItsWindow() {
        PlanResult before = PLANNER.solve(problem(fleet(), hcmcStops(), null, null, null));
        List<StopSpec> stops = new ArrayList<>(hcmcStops());
        stops.add(new StopSpec("URG", "Urgent", null, "Delivery", "Urgent", 10.7721, 106.6983, 12, 9 * 60, 10 * 60, 5));
        PlanResult after = PLANNER.solve(problem(fleet(), stops, null, null, warm(before)));
        assertRespectsHardConstraints(after, fleet(), 21);
        assertTrue(PlanDiff.between(before.rows(), after.rows()).moved().stream().anyMatch(m -> m.stop().equals("URG") && m.from() == null));
    }

    @Test
    void trafficDelaysAndTimeWindowChangesAreReplannedFeasibly() {
        PlanResult before = PLANNER.solve(problem(fleet(), hcmcStops(), null, null, null));
        List<TrafficArea> jam = List.of(new TrafficArea("Thủ Đức", 10.8494, 106.7537, 4, 2.5));
        PlanResult jammed = PLANNER.solve(problem(fleet(), hcmcStops(), jam, null, warm(before)));
        assertRespectsHardConstraints(jammed, fleet(), 20);

        List<StopSpec> changed = new ArrayList<>(hcmcStops());
        StopSpec s = changed.get(7);
        changed.set(7, new StopSpec(s.id(), s.label(), null, s.kind(), s.priority(), s.latitude(), s.longitude(), s.demandKg(), 8 * 60, 9 * 60, 5));
        PlanResult rewindowed = PLANNER.solve(problem(fleet(), changed, jam, null, warm(jammed)));
        assertRespectsHardConstraints(rewindowed, fleet(), 20);
        RouteRow moved = rewindowed.rows().stream().filter(r -> r.stop().equals(s.id())).findFirst().orElseThrow();
        assertTrue(moved.start().compareTo("09:00") <= 0, "S08 now starts by 09:00, was " + moved.start());
    }

    @Test
    void raisingThePriorityWeightBringsVipStopsForward() {
        PlanResult base = PLANNER.solve(problem(fleet(), hcmcStops(), null, new Weights(1, 0, 0), null));
        PlanResult vip = PLANNER.solve(problem(fleet(), hcmcStops(), null, new Weights(1, 2_000, 0), warm(base)));
        assertRespectsHardConstraints(vip, fleet(), 20);
        assertTrue(vipMinutes(vip) <= vipMinutes(base), "VIP stops start no later in total: " + vipMinutes(vip) + " vs " + vipMinutes(base));
    }

    @Test
    void theBalanceWeightSpreadsLoad() {
        PlanResult base = PLANNER.solve(problem(fleet(), hcmcStops(), null, new Weights(1, 0, 0), null));
        PlanResult balanced = PLANNER.solve(problem(fleet(), hcmcStops(), null, new Weights(1, 0, 200), warm(base)));
        assertRespectsHardConstraints(balanced, fleet(), 20);
        assertTrue(spread(balanced) <= spread(base) + 1e-9, "max-min load " + spread(balanced) + " vs " + spread(base));
    }

    static int vipMinutes(PlanResult r) {
        return r.rows().stream().filter(x -> "VIP".equals(x.priority()))
                .mapToInt(x -> Integer.parseInt(x.start().substring(0, 2)) * 60 + Integer.parseInt(x.start().substring(3))).sum();
    }

    static double spread(PlanResult r) {
        Map<String, Double> load = new HashMap<>();
        for (String v : List.of("51C-12345", "51C-67890", "51D-24680")) load.put(v, 0.0);
        r.rows().forEach(x -> load.merge(x.vehicle(), x.demandKg(), Double::sum));
        return load.values().stream().mapToDouble(Double::doubleValue).max().orElse(0)
                - load.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
    }
}
