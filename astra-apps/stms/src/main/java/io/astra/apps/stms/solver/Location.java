package io.astra.apps.stms.solver;

/**
 * A point on the map. Road distance and travel time to every other point of the same problem are
 * precomputed by {@link RoutePlanner} (haversine × road factor, scaled by traffic areas), so the solver
 * only does array lookups.
 */
public final class Location {

    private final int index;
    private final double latitude;
    private final double longitude;
    long[] metersTo;
    long[] secondsTo;

    Location(int index, double latitude, double longitude) {
        this.index = index;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public int index() { return index; }
    public double latitude() { return latitude; }
    public double longitude() { return longitude; }

    public long metersTo(Location other) { return metersTo[other.index]; }

    public long secondsTo(Location other) { return secondsTo[other.index]; }

    /** Great-circle distance in metres. */
    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_008.8 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
