package io.astra.apps.stms.solver;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.variable.PlanningListVariable;

import java.util.ArrayList;
import java.util.List;

/** A vehicle leaving its depot at hub opening time; the solver decides which visits it makes and in what order. */
@PlanningEntity
public class PlanVehicle {

    @PlanningId
    private String id;
    private long capacityGrams;
    private Location depot;
    private long departureSecond;

    @PlanningListVariable
    private List<PlanVisit> visits = new ArrayList<>();

    public PlanVehicle() { }

    public PlanVehicle(String id, long capacityGrams, Location depot, long departureSecond) {
        this.id = id;
        this.capacityGrams = capacityGrams;
        this.depot = depot;
        this.departureSecond = departureSecond;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getCapacityGrams() { return capacityGrams; }
    public void setCapacityGrams(long capacityGrams) { this.capacityGrams = capacityGrams; }
    public Location getDepot() { return depot; }
    public void setDepot(Location depot) { this.depot = depot; }
    public long getDepartureSecond() { return departureSecond; }
    public void setDepartureSecond(long departureSecond) { this.departureSecond = departureSecond; }
    public List<PlanVisit> getVisits() { return visits; }
    public void setVisits(List<PlanVisit> visits) { this.visits = visits; }

    /** Everything the vehicle carries over the trip: deliveries leave the depot with it, pickups come back. */
    public long totalDemandGrams() {
        long sum = 0;
        for (PlanVisit v : visits) sum += v.getDemandGrams();
        return sum;
    }

    /** Depot → visits → depot, in road metres. */
    public long totalMeters() {
        if (visits.isEmpty()) return 0;
        long meters = 0;
        Location at = depot;
        for (PlanVisit v : visits) {
            meters += at.metersTo(v.getLocation());
            at = v.getLocation();
        }
        return meters + at.metersTo(depot);
    }

    /** Load in whole kilograms, used by the load-balancing constraint. */
    public long loadKg() { return Math.round(totalDemandGrams() / 1000.0); }
}
