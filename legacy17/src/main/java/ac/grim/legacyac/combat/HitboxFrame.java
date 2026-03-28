package ac.grim.legacyac.combat;

public final class HitboxFrame {
    private final long timestampMillis;
    private final long timestampNanos;
    private final boolean teleportMarker;
    private final boolean transactionAligned;
    private final boolean enforceable;
    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;

    public HitboxFrame(long timestampMillis, boolean teleportMarker, boolean transactionAligned, boolean enforceable,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this(timestampMillis, 0L, teleportMarker, transactionAligned, enforceable,
                minX, minY, minZ, maxX, maxY, maxZ);
    }

    public HitboxFrame(long timestampMillis, long timestampNanos, boolean teleportMarker, boolean transactionAligned,
            boolean enforceable,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.timestampMillis = timestampMillis;
        this.timestampNanos = timestampNanos;
        this.teleportMarker = teleportMarker;
        this.transactionAligned = transactionAligned;
        this.enforceable = enforceable;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public boolean isTeleportMarker() {
        return teleportMarker;
    }

    public boolean isTransactionAligned() {
        return transactionAligned;
    }

    public boolean isEnforceable() {
        return enforceable;
    }

    public double getMinX() { return minX; }
    public double getMinY() { return minY; }
    public double getMinZ() { return minZ; }
    public double getMaxX() { return maxX; }
    public double getMaxY() { return maxY; }
    public double getMaxZ() { return maxZ; }
}
