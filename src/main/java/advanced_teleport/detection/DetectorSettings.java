package advanced_teleport.detection;

/** Gaps count intervening blocks; corner gaps follow the horizontal taxicab distance. */
public record DetectorSettings(int railStraightGap, int railCornerMinGap, int railCornerMaxGap,
                               int iceMinWidth, int iceMaxWidth) {
    public DetectorSettings {
        if (railStraightGap < 0 || railStraightGap > 8
                || railCornerMinGap < 0 || railCornerMaxGap < railCornerMinGap || railCornerMaxGap > 8
                || iceMinWidth < 1 || iceMaxWidth < iceMinWidth || iceMaxWidth > 9)
            throw new IllegalArgumentException("Invalid road detection dimensions");
    }

    public static DetectorSettings defaults() { return new DetectorSettings(1, 2, 3, 3, 5); }

    public int scanRadius() {
        // A changed partner corner can affect an approach on the far side of the other corner.
        return Math.max(iceMaxWidth + 1, Math.max(railStraightGap + 2, railCornerMaxGap * 2 + 1));
    }

    public int verticalScanRadius() { return Math.max(2,railCornerMaxGap+1); }
}
