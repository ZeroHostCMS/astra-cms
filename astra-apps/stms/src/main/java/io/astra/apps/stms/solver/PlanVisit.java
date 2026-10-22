package io.astra.apps.stms.solver;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.variable.InverseRelationShadowVariable;
import ai.timefold.solver.core.api.domain.variable.PreviousElementShadowVariable;
import ai.timefold.solver.core.api.domain.variable.ShadowSources;
import ai.timefold.solver.core.api.domain.variable.ShadowVariable;

/**
 * One stop to serve (a delivery or a pickup). Its vehicle, predecessor and arrival time are shadow variables
 * that Timefold keeps consistent as it moves the stop between and within routes. Times are seconds after midnight.
 */
@PlanningEntity
public class PlanVisit {

    @PlanningId
    private String id;
    private Location location;
    private long demandGrams;
    private long windowStart;
    private long windowEnd;
    private long serviceSeconds;
    private boolean priority;

    @InverseRelationShadowVariable(sourceVariableName = "visits")
    private PlanVehicle vehicle;

    @PreviousElementShadowVariable(sourceVariableName = "visits")
    private PlanVisit previousVisit;

    @ShadowVariable(supplierName = "arrivalSecondSupplier")
    private Long arrivalSecond;

    public PlanVisit() { }

    public PlanVisit(String id, Location location, long demandGrams, long windowStart, long windowEnd,
                     long serviceSeconds, boolean priority) {
        this.id = id;
        this.location = location;
        this.demandGrams = demandGrams;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.serviceSeconds = serviceSeconds;
        this.priority = priority;
    }

    @ShadowSources({"vehicle", "previousVisit.arrivalSecond"})
    public Long arrivalSecondSupplier() {
        if (vehicle == null) return null;
        if (previousVisit == null) return vehicle.getDepartureSecond() + vehicle.getDepot().secondsTo(location);
        Long before = previousVisit.departureSecond();
        return before == null ? null : before + previousVisit.getLocation().secondsTo(location);
    }

    /** Service starts on arrival, or at the window opening when the vehicle is early and waits. */
    public Long startSecond() {
        return arrivalSecond == null ? null : Math.max(arrivalSecond, windowStart);
    }

    public Long departureSecond() {
        Long start = startSecond();
        return start == null ? null : start + serviceSeconds;
    }

    /** How long after the window closed service starts; 0 when on time or not yet routed. */
    public long lateSeconds() {
        Long start = startSecond();
        return start == null ? 0 : Math.max(0, start - windowEnd);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Location getLocation() { return location; }
    public void setLocation(Location location) { this.location = location; }
    public long getDemandGrams() { return demandGrams; }
    public void setDemandGrams(long demandGrams) { this.demandGrams = demandGrams; }
    public long getWindowStart() { return windowStart; }
    public void setWindowStart(long windowStart) { this.windowStart = windowStart; }
    public long getWindowEnd() { return windowEnd; }
    public void setWindowEnd(long windowEnd) { this.windowEnd = windowEnd; }
    public long getServiceSeconds() { return serviceSeconds; }
    public void setServiceSeconds(long serviceSeconds) { this.serviceSeconds = serviceSeconds; }
    public boolean isPriority() { return priority; }
    public void setPriority(boolean priority) { this.priority = priority; }
    public PlanVehicle getVehicle() { return vehicle; }
    public void setVehicle(PlanVehicle vehicle) { this.vehicle = vehicle; }
    public PlanVisit getPreviousVisit() { return previousVisit; }
    public void setPreviousVisit(PlanVisit previousVisit) { this.previousVisit = previousVisit; }
    public Long getArrivalSecond() { return arrivalSecond; }
    public void setArrivalSecond(Long arrivalSecond) { this.arrivalSecond = arrivalSecond; }
}
