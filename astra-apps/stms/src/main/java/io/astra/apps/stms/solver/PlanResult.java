package io.astra.apps.stms.solver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Solver output: the score, one row per visit in route order, and the hard-constraint violations in words. */
public record PlanResult(String score, long hardScore, long softScore, boolean feasible, double distanceKm,
                         int vehiclesUsed, long solveMillis, List<RouteRow> rows, List<String> violations) {

    /** One stop on one vehicle's route. ETA is the arrival, start the service start (later when the vehicle waits). */
    public record RouteRow(String vehicle, int sequence, String stop, String label, String waybill, String priority,
                           String eta, String start, String window, double demandKg, double loadKg, double legKm,
                           int lateMinutes) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vehicle", vehicle);
            m.put("sequence", sequence);
            m.put("stop", stop);
            m.put("label", label);
            m.put("waybill", waybill);
            m.put("priority", priority);
            m.put("eta", eta);
            m.put("start", start);
            m.put("window", window);
            m.put("demand_kg", demandKg);
            m.put("load_kg", loadKg);
            m.put("leg_km", legKm);
            m.put("late_min", lateMinutes);
            return m;
        }

        public static RouteRow fromMap(Map<?, ?> m) {
            return new RouteRow(str(m.get("vehicle")), num(m.get("sequence")).intValue(), str(m.get("stop")),
                    str(m.get("label")), str(m.get("waybill")), str(m.get("priority")), str(m.get("eta")),
                    str(m.get("start")), str(m.get("window")), num(m.get("demand_kg")).doubleValue(),
                    num(m.get("load_kg")).doubleValue(), num(m.get("leg_km")).doubleValue(),
                    num(m.get("late_min")).intValue());
        }

        private static String str(Object o) { return o == null ? null : o.toString(); }

        private static Number num(Object o) {
            if (o instanceof Number n) return n;
            if (o == null) return 0;
            try {
                return Double.parseDouble(o.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    public List<Map<String, Object>> rowMaps() {
        return rows.stream().map(RouteRow::toMap).toList();
    }

    /** A fixed-width text table, one line per stop, grouped by vehicle; the Desk shows it as is. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Score %s · %s · %.1f km · %d vehicle(s) · solved in %d ms%n",
                score, feasible ? "feasible" : "INFEASIBLE", distanceKm, vehiclesUsed, solveMillis));
        String current = null;
        for (RouteRow r : rows) {
            if (!r.vehicle().equals(current)) {
                current = r.vehicle();
                sb.append(String.format("%n%s%n", current));
                sb.append(String.format("  %-3s %-16s %-7s %-5s %-5s %-11s %7s %7s %6s%n",
                        "#", "stop", "prio", "eta", "start", "window", "kg", "load", "km"));
            }
            sb.append(String.format("  %-3d %-16s %-7s %-5s %-5s %-11s %7.1f %7.1f %6.1f%s%n",
                    r.sequence(), r.stop(), r.priority(), r.eta(), r.start(), r.window(), r.demandKg(), r.loadKg(),
                    r.legKm(), r.lateMinutes() > 0 ? "  LATE " + r.lateMinutes() + " min" : ""));
        }
        for (String v : violations) sb.append(String.format("%n! %s", v));
        return sb.toString().strip();
    }
}
