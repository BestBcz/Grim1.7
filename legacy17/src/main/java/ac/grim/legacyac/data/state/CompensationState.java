package ac.grim.legacyac.data.state;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Domain state aggregate for latency-compensation-related data.
 * Tracks teleport sync, velocity windows, and transaction-anchored world updates.
 */
public final class CompensationState {
    private static final long TELEPORT_SYNC_TIMEOUT_MS = 1500L;
    private static final long PENDING_CHANGE_TIMEOUT_MS = 2000L;
    private final Object lock = new Object();

    // Teleport sync
    private boolean teleportSyncPending;
    private boolean teleportPositionConfirmed;
    private double pendingTeleportX;
    private double pendingTeleportY;
    private double pendingTeleportZ;
    private long lastTeleportAt;
    private long lastTeleportOrPearlAt;
    private boolean movementUnconfirmed;

    // Velocity
    private long lastVelocityAt;
    private double lastVelocityXZ;
    private int velocityTicksRemaining;
    private double expectedVelocityXZ;
    private double expectedVelocityY;
    private double expectedVelX;
    private double expectedVelY;
    private double expectedVelZ;
    private double observedVelocityXZ;
    private double observedVelocityY;

    // Pending world changes
    private final LinkedList<PendingWorldChange> pendingWorldChanges = new LinkedList<PendingWorldChange>();

    // Slot switch grace
    private long lastSlotSwitchAt;
    private int slotSwitchGraceTicksRemaining;

    public void beginTeleportSync(double x, double y, double z) {
        beginTeleportSync(x, y, z, (short) 0);
    }

    public void beginTeleportSync(double x, double y, double z, short anchorTransactionId) {
        synchronized (lock) {
            teleportSyncPending = true;
            teleportPositionConfirmed = false;
            pendingTeleportX = x;
            pendingTeleportY = y;
            pendingTeleportZ = z;
            movementUnconfirmed = true;
            lastTeleportAt = System.currentTimeMillis();
            lastTeleportOrPearlAt = lastTeleportAt;
            enqueuePendingWorldChangeLocked(PendingWorldChangeType.TELEPORT, "server-position-sync", anchorTransactionId);
        }
    }

    public void tryConfirmTeleportSync(double x, double y, double z, boolean hasRecentTxAck) {
        tryConfirmTeleportSync(x, y, z);
    }

    public void tryConfirmTeleportSync(double x, double y, double z) {
        synchronized (lock) {
            if (!teleportSyncPending) {
                return;
            }
            double dx = Math.abs(x - pendingTeleportX);
            double dy = Math.abs(y - pendingTeleportY);
            double dz = Math.abs(z - pendingTeleportZ);
            if (dx > 0.03125D || dy > 0.03125D || dz > 0.03125D) {
                return;
            }
            teleportPositionConfirmed = true;
            completeTeleportSyncIfReadyLocked();
        }
    }

    public boolean isTeleportSyncPending() {
        synchronized (lock) {
            expirePendingWorldChangesLocked();
            if (teleportSyncPending) {
                long elapsed = System.currentTimeMillis() - lastTeleportAt;
                if (elapsed > TELEPORT_SYNC_TIMEOUT_MS) {
                    teleportSyncPending = false;
                    teleportPositionConfirmed = false;
                    movementUnconfirmed = false;
                }
            }
            return teleportSyncPending;
        }
    }

    public void setLastTeleportAt(long millis) {
        synchronized (lock) {
            this.lastTeleportAt = millis;
            this.lastTeleportOrPearlAt = millis;
        }
    }

    public void setLastTeleportOrPearlAt(long millis) {
        synchronized (lock) {
            this.lastTeleportOrPearlAt = millis;
        }
    }

    public void armVelocityWindow(double vx, double vz, double vy, int ticks) {
        synchronized (lock) {
            expectedVelX = vx;
            expectedVelY = vy;
            expectedVelZ = vz;
            expectedVelocityXZ = Math.sqrt(vx * vx + vz * vz);
            expectedVelocityY = Math.abs(vy);
            observedVelocityXZ = 0.0D;
            observedVelocityY = 0.0D;
            velocityTicksRemaining = ticks;
        }
    }

    public void tickVelocityWindow(double deltaXZ, double deltaY) {
        synchronized (lock) {
            if (velocityTicksRemaining <= 0) {
                return;
            }
            if (deltaXZ > observedVelocityXZ) {
                observedVelocityXZ = deltaXZ;
            }
            double absY = Math.abs(deltaY);
            if (absY > observedVelocityY) {
                observedVelocityY = absY;
            }
            velocityTicksRemaining--;
        }
    }

    public void clearVelocityWindow() {
        synchronized (lock) {
            velocityTicksRemaining = 0;
            expectedVelocityXZ = 0.0D;
            expectedVelocityY = 0.0D;
            expectedVelX = 0.0D;
            expectedVelY = 0.0D;
            expectedVelZ = 0.0D;
            observedVelocityXZ = 0.0D;
            observedVelocityY = 0.0D;
        }
    }

    public void setLastVelocityAt(long millis) {
        synchronized (lock) {
            this.lastVelocityAt = millis;
        }
    }

    public void setLastVelocityXZ(double val) {
        synchronized (lock) {
            this.lastVelocityXZ = val;
        }
    }

    public void recordPendingVelocityChange(long oneWayDelayMs) {
        recordPendingVelocityChange((short) 0);
    }

    public void recordPendingVelocityChange(short anchorTransactionId) {
        synchronized (lock) {
            enqueuePendingWorldChangeLocked(PendingWorldChangeType.VELOCITY, "entity-velocity", anchorTransactionId);
        }
    }

    public void recordPendingBlockChange(String reason, long oneWayDelayMs) {
        recordPendingBlockChange(reason, (short) 0);
    }

    public void recordPendingBlockChange(String reason, short anchorTransactionId) {
        synchronized (lock) {
            enqueuePendingWorldChangeLocked(PendingWorldChangeType.BLOCK_CHANGE, reason, anchorTransactionId);
        }
    }

    public void acknowledgeTransaction(short actionId) {
        synchronized (lock) {
            Iterator<PendingWorldChange> iterator = pendingWorldChanges.iterator();
            while (iterator.hasNext()) {
                PendingWorldChange change = iterator.next();
                if (change.getAnchorTransactionId() != actionId) {
                    continue;
                }
                if (change.getType() == PendingWorldChangeType.TELEPORT) {
                    iterator.remove();
                    completeTeleportSyncIfReadyLocked();
                    continue;
                }
                iterator.remove();
            }
        }
    }

    public void markSlotSwitch() {
        synchronized (lock) {
            lastSlotSwitchAt = System.currentTimeMillis();
        }
    }

    public void startSlotSwitchGrace(int ticks) {
        synchronized (lock) {
            if (ticks > slotSwitchGraceTicksRemaining) {
                slotSwitchGraceTicksRemaining = ticks;
            }
        }
    }

    public void tickSlotSwitchGrace() {
        synchronized (lock) {
            if (slotSwitchGraceTicksRemaining > 0) {
                slotSwitchGraceTicksRemaining--;
            }
        }
    }

    public boolean isInSlotSwitchGrace() {
        synchronized (lock) {
            return slotSwitchGraceTicksRemaining > 0;
        }
    }

    public long getLastSlotSwitchAt() {
        synchronized (lock) {
            return lastSlotSwitchAt;
        }
    }

    private void enqueuePendingWorldChangeLocked(PendingWorldChangeType type, String reason, short anchorTransactionId) {
        long now = System.currentTimeMillis();
        pendingWorldChanges.add(new PendingWorldChange(type, now, now + PENDING_CHANGE_TIMEOUT_MS, anchorTransactionId, reason));
        while (pendingWorldChanges.size() > 32) {
            pendingWorldChanges.removeFirst();
        }
    }

    public void applyPendingWorldChanges() {
        synchronized (lock) {
            expirePendingWorldChangesLocked();
        }
    }

    public int getPendingWorldChangesCount() {
        synchronized (lock) {
            expirePendingWorldChangesLocked();
            return pendingWorldChanges.size();
        }
    }

    public List<String> getPendingWorldChangeDebugSnapshot() {
        synchronized (lock) {
            expirePendingWorldChangesLocked();
            List<String> snapshot = new ArrayList<String>();
            for (PendingWorldChange change : pendingWorldChanges) {
                snapshot.add(change.getType().name() + "#"
                        + change.getAnchorTransactionId() + ":" + change.getReason());
            }
            return snapshot;
        }
    }

    private int countPendingChangesLocked(PendingWorldChangeType type) {
        int count = 0;
        for (PendingWorldChange change : pendingWorldChanges) {
            if (change.getType() == type) {
                count++;
            }
        }
        return count;
    }

    public MovementStateSnapshot getMovementStateSnapshot() {
        synchronized (lock) {
            expirePendingWorldChangesLocked();
            return createMovementStateSnapshotLocked();
        }
    }

    public long getLastTeleportAt() {
        synchronized (lock) {
            return lastTeleportAt;
        }
    }

    public long getLastTeleportOrPearlAt() {
        synchronized (lock) {
            return lastTeleportOrPearlAt;
        }
    }

    public long getLastVelocityAt() {
        synchronized (lock) {
            return lastVelocityAt;
        }
    }

    public double getLastVelocityXZ() {
        synchronized (lock) {
            return lastVelocityXZ;
        }
    }

    public boolean hasPendingVelocityWindow() {
        synchronized (lock) {
            return velocityTicksRemaining > 0;
        }
    }

    public boolean hasCompletedVelocityWindow() {
        synchronized (lock) {
            return velocityTicksRemaining <= 0 && (expectedVelocityXZ > 0.0D || expectedVelocityY > 0.0D);
        }
    }

    public double getExpectedVelocityXZ() {
        synchronized (lock) {
            return expectedVelocityXZ;
        }
    }

    public double getExpectedVelocityY() {
        synchronized (lock) {
            return expectedVelocityY;
        }
    }

    public double getExpectedVelX() {
        synchronized (lock) {
            return expectedVelX;
        }
    }

    public double getExpectedVelY() {
        synchronized (lock) {
            return expectedVelY;
        }
    }

    public double getExpectedVelZ() {
        synchronized (lock) {
            return expectedVelZ;
        }
    }

    public double getObservedVelocityXZ() {
        synchronized (lock) {
            return observedVelocityXZ;
        }
    }

    public double getObservedVelocityY() {
        synchronized (lock) {
            return observedVelocityY;
        }
    }

    public boolean isMovementUnconfirmed() {
        synchronized (lock) {
            return movementUnconfirmed;
        }
    }

    public void setMovementUnconfirmed(boolean val) {
        synchronized (lock) {
            this.movementUnconfirmed = val;
        }
    }

    private void expirePendingWorldChangesLocked() {
        long now = System.currentTimeMillis();
        Iterator<PendingWorldChange> iterator = pendingWorldChanges.iterator();
        while (iterator.hasNext()) {
            PendingWorldChange change = iterator.next();
            if (change.getExpiresAtMillis() > now) {
                continue;
            }
            if (change.getType() == PendingWorldChangeType.TELEPORT) {
                teleportSyncPending = false;
                teleportPositionConfirmed = false;
                movementUnconfirmed = false;
            }
            iterator.remove();
        }
    }

    private void completeTeleportSyncIfReadyLocked() {
        if (!teleportSyncPending) {
            return;
        }
        if (!teleportPositionConfirmed) {
            return;
        }
        if (countPendingChangesLocked(PendingWorldChangeType.TELEPORT) > 0) {
            return;
        }
        teleportSyncPending = false;
        teleportPositionConfirmed = false;
        movementUnconfirmed = false;
    }

    private MovementStateSnapshot createMovementStateSnapshotLocked() {
        int pendingTeleport = countPendingChangesLocked(PendingWorldChangeType.TELEPORT);
        int pendingVelocity = countPendingChangesLocked(PendingWorldChangeType.VELOCITY);
        int pendingBlock = countPendingChangesLocked(PendingWorldChangeType.BLOCK_CHANGE);
        boolean teleportAligned = pendingTeleport == 0 && !teleportSyncPending;
        boolean velocityAligned = pendingVelocity == 0;
        boolean blockAligned = pendingBlock == 0;
        int pendingChanges = pendingTeleport + pendingVelocity + pendingBlock;
        AlignmentBlocker primaryBlocker;
        if (!teleportAligned) {
            primaryBlocker = AlignmentBlocker.TELEPORT;
        } else if (!blockAligned) {
            primaryBlocker = AlignmentBlocker.BLOCK;
        } else if (!velocityAligned) {
            primaryBlocker = AlignmentBlocker.VELOCITY;
        } else {
            primaryBlocker = AlignmentBlocker.NONE;
        }
        return new MovementStateSnapshot(teleportAligned, velocityAligned, blockAligned, pendingChanges,
                primaryBlocker, primaryBlocker == AlignmentBlocker.NONE);
    }

    public static final class MovementStateSnapshot {
        private final boolean teleportAligned;
        private final boolean velocityAligned;
        private final boolean blockAligned;
        private final int pendingChanges;
        private final AlignmentBlocker primaryBlocker;
        private final boolean enforceable;

        MovementStateSnapshot(boolean teleportAligned, boolean velocityAligned, boolean blockAligned,
                int pendingChanges, AlignmentBlocker primaryBlocker, boolean enforceable) {
            this.teleportAligned = teleportAligned;
            this.velocityAligned = velocityAligned;
            this.blockAligned = blockAligned;
            this.pendingChanges = pendingChanges;
            this.primaryBlocker = primaryBlocker;
            this.enforceable = enforceable;
        }

        public boolean isTeleportAligned() {
            return teleportAligned;
        }

        public boolean isVelocityAligned() {
            return velocityAligned;
        }

        public boolean isBlockAligned() {
            return blockAligned;
        }

        public boolean isFullyAligned() {
            return teleportAligned && velocityAligned && blockAligned;
        }

        public int getPendingChanges() {
            return pendingChanges;
        }

        public AlignmentBlocker getPrimaryBlocker() {
            return primaryBlocker;
        }

        public boolean isEnforceable() {
            return enforceable;
        }
    }

    public enum AlignmentBlocker {
        NONE,
        TELEPORT,
        BLOCK,
        VELOCITY,
        DEGRADED_PIPELINE
    }

    enum PendingWorldChangeType {
        TELEPORT,
        VELOCITY,
        BLOCK_CHANGE
    }

    static final class PendingWorldChange {
        private final PendingWorldChangeType type;
        private final long createdAtMillis;
        private final long expiresAtMillis;
        private final short anchorTransactionId;
        private final String reason;

        PendingWorldChange(PendingWorldChangeType type, long createdAtMillis, long expiresAtMillis,
                short anchorTransactionId, String reason) {
            this.type = type;
            this.createdAtMillis = createdAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.anchorTransactionId = anchorTransactionId;
            this.reason = reason;
        }

        PendingWorldChangeType getType() {
            return type;
        }

        long getCreatedAtMillis() {
            return createdAtMillis;
        }

        long getExpiresAtMillis() {
            return expiresAtMillis;
        }

        short getAnchorTransactionId() {
            return anchorTransactionId;
        }

        String getReason() {
            return reason;
        }
    }
}
