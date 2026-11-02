package advanced_teleport.graph;

/** All distances are measured in blocks, within one dimension. */
public record GraphSettings(double roadConnectionDistance, double portalConnectionDistance,
                            double roadAccessDistance, double stationAccessDistance) {
    public GraphSettings {
        check(roadConnectionDistance);
        check(portalConnectionDistance);
        check(roadAccessDistance);
        check(stationAccessDistance);
    }

    private static void check(double distance) {
        if (!Double.isFinite(distance) || distance < 0)
            throw new IllegalArgumentException("Graph distances must be finite and nonnegative");
    }
}
