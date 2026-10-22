package io.astra.apps.stms.solver;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import io.astra.apps.stms.solver.PlanResult.RouteRow;
import io.astra.apps.stms.solver.RoutingProblem.StopSpec;
import io.astra.apps.stms.solver.RoutingProblem.TrafficArea;
import io.astra.apps.stms.solver.RoutingProblem.VehicleSpec;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Builds the Timefold problem (distance and time matrices, warm start, weights), solves it on a virtual thread
 * within a time budget, and turns the best solution into route rows. Pure computation: no tenant, no documents.
 */
@Singleton
public class RoutePlanner {

    /** Upper bound on any budget a caller asks for, so a request cannot hold a thread for long. */
    public static final int MAX_SECONDS = 30;

    private final SolverFactory<RoutePlan> solverFactory;
    private final double metresPerSecond;
    private final double roadFactor;
    private final int defaultSeconds;

    @Inject
    public RoutePlanner(SolverFactory<RoutePlan> solverFactory,
                        @ConfigProperty(name = "astra.stms.speed-kmh", defaultValue = "25") double speedKmh,
                        @ConfigProperty(name = "astra.stms.road-factor", defaultValue = "1.3") double roadFactor,
                        @ConfigProperty(name = "astra.stms.solve-seconds", defaultValue = "5") int defaultSeconds) {
        if (speedKmh <= 0 || roadFactor < 1) throw new IllegalArgumentException("speed must be positive and road factor at least 1");
        this.solverFactory = solverFactory;
        this.metresPerSecond = speedKmh / 3.6;
        this.roadFactor = roadFactor;
        this.defaultSeconds = clampSeconds(defaultSeconds);
    }

    public int defaultSeconds() { return defaultSeconds; }

    public static int clampSeconds(int seconds) { return Math.max(1, Math.min(MAX_SECONDS, seconds)); }

    public PlanResult solve(RoutingProblem problem) {
        if (problem.vehicles().isEmpty()) throw new IllegalArgumentException("no vehicle is available at hub " + problem.depot().hub());
        if (problem.stops().isEmpty()) throw new IllegalArgumentException("no stop to plan at hub " + problem.depot().hub());
        RoutePlan plan = build(problem);
        int seconds = problem.seconds() > 0 ? clampSeconds(problem.seconds()) : defaultSeconds;
        long started = System.nanoTime();
        RoutePlan best = solveOnVirtualThread(plan, seconds);
        long millis = (System.nanoTime() - started) / 1_000_000;
        return result(problem, best, millis);
    }

    /** Stops when the budget is spent, or earlier once the score has not improved for a fifth of it. */
    private RoutePlan solveOnVirtualThread(RoutePlan plan, int seconds) {
        Duration limit = Duration.ofSeconds(seconds);
        Duration unimproved = Duration.ofMillis(Math.max(500, limit.toMillis() / 5));
        Solver<RoutePlan> solver = solverFactory.buildSolver(new SolverConfigOverride()
                .withTerminationConfig(new TerminationConfig().withSpentLimit(limit).withUnimprovedSpentLimit(unimproved)));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<RoutePlan> future = executor.submit(() -> solver.solve(plan));
            try {
                return future.get(seconds + 30L, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                solver.terminateEarly();
                return future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            solver.terminateEarly();
            throw new IllegalStateException("route solving was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("route solving failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    RoutePlan build(RoutingProblem problem) {
        List<Location> locations = new ArrayList<>();
        Location depot = new Location(0, problem.depot().latitude(), problem.depot().longitude());
        locations.add(depot);
        long departure = problem.depot().openMinute() * 60L;

        List<PlanVisit> visits = new ArrayList<>();
        Map<String, PlanVisit> byId = new HashMap<>();
        for (StopSpec s : problem.stops()) {
            Location at = new Location(locations.size(), s.latitude(), s.longitude());
            locations.add(at);
            PlanVisit v = new PlanVisit(s.id(), at, Math.round(s.demandKg() * 1000), s.windowFrom() * 60L,
                    s.windowTo() * 60L, s.serviceMinutes() * 60L, s.isPriority());
            if (byId.putIfAbsent(s.id(), v) != null) throw new IllegalArgumentException("duplicate stop " + s.id());
            visits.add(v);
        }
        fillMatrices(locations, problem.traffic());

        List<PlanVehicle> vehicles = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (VehicleSpec spec : problem.vehicles()) {
            PlanVehicle vehicle = new PlanVehicle(spec.plate(), Math.round(spec.capacityKg() * 1000), depot, departure);
            for (String stop : problem.warmStart().getOrDefault(spec.plate(), List.of())) {
                PlanVisit v = byId.get(stop);
                if (v != null && placed.add(stop)) vehicle.getVisits().add(v);
            }
            vehicles.add(vehicle);
        }
        RoutingProblem.Weights w = problem.weights();
        ConstraintWeightOverrides<HardSoftScore> weights = ConstraintWeightOverrides.of(Map.of(
                RoutingConstraints.DISTANCE, HardSoftScore.ofSoft(w.distance()),
                RoutingConstraints.PRIORITY, HardSoftScore.ofSoft(w.priority()),
                RoutingConstraints.BALANCE, HardSoftScore.ofSoft(w.balance())));
        return new RoutePlan(vehicles, visits, weights);
    }

    private void fillMatrices(List<Location> locations, List<TrafficArea> traffic) {
        int n = locations.size();
        double[] slowdown = new double[n];
        for (Location l : locations) {
            double factor = 1;
            for (TrafficArea area : traffic) {
                double km = Location.haversineMeters(l.latitude(), l.longitude(), area.latitude(), area.longitude()) / 1000;
                if (km <= area.radiusKm()) factor = Math.max(factor, area.factor());
            }
            slowdown[l.index()] = factor;
        }
        for (Location from : locations) {
            from.metersTo = new long[n];
            from.secondsTo = new long[n];
            for (Location to : locations) {
                if (from == to) continue;
                double metres = Location.haversineMeters(from.latitude(), from.longitude(), to.latitude(), to.longitude()) * roadFactor;
                from.metersTo[to.index()] = Math.round(metres);
                double factor = Math.max(slowdown[from.index()], slowdown[to.index()]);
                from.secondsTo[to.index()] = Math.round(metres / metresPerSecond * factor);
            }
        }
    }

    private PlanResult result(RoutingProblem problem, RoutePlan best, long millis) {
        Map<String, StopSpec> stops = new HashMap<>();
        problem.stops().forEach(s -> stops.put(s.id(), s));
        List<RouteRow> rows = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        long metres = 0;
        int used = 0;
        for (PlanVehicle vehicle : best.getVehicles()) {
            if (vehicle.getVisits().isEmpty()) continue;
            used++;
            metres += vehicle.totalMeters();
            double load = 0;
            Location at = vehicle.getDepot();
            int seq = 0;
            for (PlanVisit v : vehicle.getVisits()) {
                StopSpec s = stops.get(v.getId());
                load += s.demandKg();
                int late = (int) Math.ceilDiv(v.lateSeconds(), 60);
                rows.add(new RouteRow(vehicle.getId(), ++seq, s.id(), s.label(), s.waybill(), s.priority(),
                        clock(v.getArrivalSecond()), clock(v.startSecond()),
                        clock(s.windowFrom() * 60L) + "-" + clock(s.windowTo() * 60L), s.demandKg(), round1(load),
                        round1(at.metersTo(v.getLocation()) / 1000.0), late));
                if (late > 0) violations.add("stop " + s.id() + " is served " + late + " min after its window closes");
                at = v.getLocation();
            }
            if (vehicle.totalDemandGrams() > vehicle.getCapacityGrams()) {
                violations.add(String.format("vehicle %s carries %.1f kg, capacity %.1f kg", vehicle.getId(),
                        vehicle.totalDemandGrams() / 1000.0, vehicle.getCapacityGrams() / 1000.0));
            }
        }
        HardSoftScore score = best.getScore();
        return new PlanResult(score.toString(), score.hardScore(), score.softScore(), score.isFeasible(),
                round1(metres / 1000.0), used, millis, rows, violations);
    }

    static String clock(Long secondOfDay) {
        if (secondOfDay == null) return "";
        long minutes = Math.floorDiv(secondOfDay, 60);
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
