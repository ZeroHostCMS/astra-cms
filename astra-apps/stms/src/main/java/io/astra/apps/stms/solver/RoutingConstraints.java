package io.astra.apps.stms.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;

/**
 * Hard: no vehicle carries more than its capacity, no stop is served after its time window closes.
 * Soft (weights tunable per plan through {@link RoutePlan#getConstraintWeightOverrides()}): total road distance,
 * priority stops (VIP, urgent) served early, and load spread evenly across vehicles.
 */
public class RoutingConstraints implements ConstraintProvider {

    public static final String CAPACITY = "Vehicle capacity";
    public static final String TIME_WINDOW = "Time window";
    public static final String DISTANCE = "Minimize distance";
    public static final String PRIORITY = "Priority stops early";
    public static final String BALANCE = "Balance load";

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                // per kilogram over capacity
                factory.forEach(PlanVehicle.class)
                        .filter(v -> v.totalDemandGrams() > v.getCapacityGrams())
                        .penalize(HardSoftScore.ONE_HARD, v -> ceilDiv(v.totalDemandGrams() - v.getCapacityGrams(), 1000))
                        .asConstraint(CAPACITY),
                // per minute late
                factory.forEach(PlanVisit.class)
                        .filter(v -> v.lateSeconds() > 0)
                        .penalize(HardSoftScore.ONE_HARD, v -> ceilDiv(v.lateSeconds(), 60))
                        .asConstraint(TIME_WINDOW),
                // per road metre
                factory.forEach(PlanVehicle.class)
                        .filter(v -> !v.getVisits().isEmpty())
                        .penalize(HardSoftScore.ONE_SOFT, PlanVehicle::totalMeters)
                        .asConstraint(DISTANCE),
                // per minute between leaving the depot and starting service at a priority stop
                factory.forEach(PlanVisit.class)
                        .filter(v -> v.isPriority() && v.startSecond() != null)
                        .penalize(HardSoftScore.ONE_SOFT, v -> (v.startSecond() - v.getVehicle().getDepartureSecond()) / 60)
                        .asConstraint(PRIORITY),
                // squared load per vehicle: for a fixed total it is smallest when loads are equal
                factory.forEach(PlanVehicle.class)
                        .filter(v -> !v.getVisits().isEmpty())
                        .penalize(HardSoftScore.ONE_SOFT, v -> v.loadKg() * v.loadKg())
                        .asConstraint(BALANCE)
        };
    }

    private static long ceilDiv(long a, long b) {
        return Math.ceilDiv(a, b);
    }
}
