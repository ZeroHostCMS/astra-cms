package io.astra.apps.stms.solver;

import java.util.List;
import java.util.Map;

/**
 * Solver input, independent of Timefold and of documents. Times are minutes after midnight on the plan date.
 *
 * @param warmStart vehicle plate → ordered stop ids of the current plan; the solver starts from it and only has
 *                  to place what changed (stops of an unavailable vehicle, new stops), which is how live events
 *                  are replanned without throwing the dispatcher's plan away
 * @param seconds   solving time budget
 */
public record RoutingProblem(Depot depot, List<VehicleSpec> vehicles, List<StopSpec> stops, List<TrafficArea> traffic,
                             Weights weights, Map<String, List<String>> warmStart, int seconds) {

    public RoutingProblem {
        if (depot == null) throw new IllegalArgumentException("depot is required");
        vehicles = List.copyOf(vehicles);
        stops = List.copyOf(stops);
        traffic = traffic == null ? List.of() : List.copyOf(traffic);
        weights = weights == null ? Weights.DEFAULT : weights;
        warmStart = warmStart == null ? Map.of() : Map.copyOf(warmStart);
    }

    public record Depot(String hub, double latitude, double longitude, int openMinute) { }

    public record VehicleSpec(String plate, double capacityKg) { }

    /** {@code priority} is Normal, VIP or Urgent; VIP and Urgent stops are pulled early by the priority weight. */
    public record StopSpec(String id, String label, String waybill, String kind, String priority,
                           double latitude, double longitude, double demandKg,
                           int windowFrom, int windowTo, int serviceMinutes) {
        public boolean isPriority() { return "VIP".equals(priority) || "Urgent".equals(priority); }
    }

    /** Travel time on legs that start or end within {@code radiusKm} of the centre is multiplied by {@code factor}. */
    public record TrafficArea(String label, double latitude, double longitude, double radiusKm, double factor) { }

    /**
     * Soft constraint weights: per road metre, per minute a priority stop waits after departure, per kg² of load
     * on each vehicle. Zero switches a constraint off.
     */
    public record Weights(long distance, long priority, long balance) {
        public static final Weights DEFAULT = new Weights(1, 20, 0);

        public Weights {
            if (distance < 0 || priority < 0 || balance < 0) throw new IllegalArgumentException("weights cannot be negative");
        }

        public Map<String, Object> toMap() {
            return Map.of("distance", distance, "priority", priority, "balance", balance);
        }
    }
}
