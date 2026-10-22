package io.astra.apps.stms.solver;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardSoftScore;

import java.util.List;

/** The Timefold planning solution: one hub's vehicles and the day's stops, with dispatcher-tunable soft weights. */
@PlanningSolution
public class RoutePlan {

    @PlanningEntityCollectionProperty
    private List<PlanVehicle> vehicles;

    @PlanningEntityCollectionProperty
    @ValueRangeProvider
    private List<PlanVisit> visits;

    @PlanningScore
    private HardSoftScore score;

    private ConstraintWeightOverrides<HardSoftScore> constraintWeightOverrides;

    public RoutePlan() { }

    public RoutePlan(List<PlanVehicle> vehicles, List<PlanVisit> visits, ConstraintWeightOverrides<HardSoftScore> weights) {
        this.vehicles = vehicles;
        this.visits = visits;
        this.constraintWeightOverrides = weights;
    }

    public List<PlanVehicle> getVehicles() { return vehicles; }
    public void setVehicles(List<PlanVehicle> vehicles) { this.vehicles = vehicles; }
    public List<PlanVisit> getVisits() { return visits; }
    public void setVisits(List<PlanVisit> visits) { this.visits = visits; }
    public HardSoftScore getScore() { return score; }
    public void setScore(HardSoftScore score) { this.score = score; }
    public ConstraintWeightOverrides<HardSoftScore> getConstraintWeightOverrides() { return constraintWeightOverrides; }
    public void setConstraintWeightOverrides(ConstraintWeightOverrides<HardSoftScore> overrides) { this.constraintWeightOverrides = overrides; }
}
