package ac.grim.legacyac.data.state;

import ac.grim.legacyac.combat.HitboxFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Domain state aggregate for combat-related data.
 * Tracks attack targets, timing, hitbox history, click windows, etc.
 */
public final class CombatState {
    private static final long HITBOX_HISTORY_MAX_AGE_MS = 400L;
    private static final long OBSERVED_ENTITY_HISTORY_MAX_AGE_MS = 550L;
    private static final int OBSERVED_ENTITY_TRACK_LIMIT = 96;

    private long lastAttackAt;
    private int lastAttackTargetId;

    private int clickWindow;
    private long clickWindowStart;

    private final LinkedList<HitboxFrame> hitboxHistory = new LinkedList<HitboxFrame>();
    private final Map<Integer, ObservedEntityTrack> observedEntityTracks = new HashMap<Integer, ObservedEntityTrack>();

    public void recordAttack(int targetEntityId) {
        this.lastAttackAt = System.currentTimeMillis();
        this.lastAttackTargetId = targetEntityId;
    }

    public void setLastAttackAt(long millis) {
        this.lastAttackAt = millis;
    }

    public void setLastAttackTargetId(int id) {
        this.lastAttackTargetId = id;
    }

    public int incrementClickWindow() {
        long now = System.currentTimeMillis();
        if (clickWindowStart == 0L || now - clickWindowStart > 1000L) {
            clickWindowStart = now;
            clickWindow = 0;
        }
        clickWindow++;
        return clickWindow;
    }

    public void decayClickWindow() {
        if (clickWindowStart != 0L && System.currentTimeMillis() - clickWindowStart > 1500L) {
            clickWindow = 0;
            clickWindowStart = 0L;
        }
    }

    public synchronized void recordHitbox(double x, double y, double z, double width, double height,
            boolean teleportMarker, boolean transactionAligned, boolean enforceable) {
        recordHitbox(x, y, z, width, height, teleportMarker, transactionAligned, enforceable, System.nanoTime());
    }

    public synchronized void recordHitbox(double x, double y, double z, double width, double height,
            boolean teleportMarker, boolean transactionAligned, boolean enforceable, long timestampNanos) {
        long now = System.currentTimeMillis();
        appendHistory(hitboxHistory, x, y, z, width, height, teleportMarker, transactionAligned, enforceable,
                timestampNanos, now, HITBOX_HISTORY_MAX_AGE_MS);
    }

    public synchronized List<HitboxFrame> getHitboxHistorySnapshot(long maxAgeMillis) {
        return getHitboxHistorySnapshot(maxAgeMillis, System.currentTimeMillis(), 0L, 0L);
    }

    public synchronized List<HitboxFrame> getHitboxHistorySnapshot(long maxAgeMillis, long maxTimestampNanos,
            long futureSlackNanos) {
        return getHitboxHistorySnapshot(maxAgeMillis, System.currentTimeMillis(), maxTimestampNanos, futureSlackNanos);
    }

    public synchronized List<HitboxFrame> getHitboxHistorySnapshot(long maxAgeMillis, long referenceTimeMillis,
            long maxTimestampNanos, long futureSlackNanos) {
        return copyHistory(hitboxHistory, maxAgeMillis, referenceTimeMillis, maxTimestampNanos, futureSlackNanos);
    }

    public synchronized void recordObservedHitbox(int entityId, double x, double y, double z, double width,
            double height, boolean teleportMarker, boolean transactionAligned, boolean enforceable,
            long timestampNanos) {
        long now = System.currentTimeMillis();
        ObservedEntityTrack track = getOrCreateObservedTrack(entityId);
        track.initialized = true;
        track.lastCenterX = x;
        track.lastMinY = y;
        track.lastCenterZ = z;
        track.lastWidth = width;
        track.lastHeight = height;
        appendHistory(track.history, x, y, z, width, height, teleportMarker, transactionAligned, enforceable,
                timestampNanos, now, OBSERVED_ENTITY_HISTORY_MAX_AGE_MS);
        pruneObservedTracks(now);
    }

    public synchronized boolean recordObservedRelativeHitbox(int entityId, double deltaX, double deltaY, double deltaZ,
            double width, double height, boolean teleportMarker, boolean transactionAligned, boolean enforceable,
            long timestampNanos) {
        ObservedEntityTrack track = observedEntityTracks.get(entityId);
        if (track == null || !track.initialized) {
            return false;
        }

        double nextX = track.lastCenterX + deltaX;
        double nextY = track.lastMinY + deltaY;
        double nextZ = track.lastCenterZ + deltaZ;
        recordObservedHitbox(entityId, nextX, nextY, nextZ,
                width > 0.0D ? width : track.lastWidth,
                height > 0.0D ? height : track.lastHeight,
                teleportMarker, transactionAligned, enforceable, timestampNanos);
        return true;
    }

    public synchronized List<HitboxFrame> getObservedHitboxHistorySnapshot(int entityId, long maxAgeMillis) {
        return getObservedHitboxHistorySnapshot(entityId, maxAgeMillis, System.currentTimeMillis(), 0L, 0L);
    }

    public synchronized List<HitboxFrame> getObservedHitboxHistorySnapshot(int entityId, long maxAgeMillis,
            long referenceTimeMillis, long maxTimestampNanos, long futureSlackNanos) {
        ObservedEntityTrack track = observedEntityTracks.get(entityId);
        if (track == null) {
            return new ArrayList<HitboxFrame>();
        }
        return copyHistory(track.history, maxAgeMillis, referenceTimeMillis, maxTimestampNanos, futureSlackNanos);
    }

    public synchronized void clearObservedEntity(int entityId) {
        observedEntityTracks.remove(entityId);
    }

    public long getLastAttackAt() {
        return lastAttackAt;
    }

    public int getLastAttackTargetId() {
        return lastAttackTargetId;
    }

    public int getClickWindow() {
        return clickWindow;
    }

    public long getClickWindowStart() {
        return clickWindowStart;
    }

    public void setClickWindow(int clickWindow) {
        this.clickWindow = clickWindow;
    }

    public void setClickWindowStart(long clickWindowStart) {
        this.clickWindowStart = clickWindowStart;
    }

    private ObservedEntityTrack getOrCreateObservedTrack(int entityId) {
        ObservedEntityTrack track = observedEntityTracks.get(entityId);
        if (track == null) {
            track = new ObservedEntityTrack();
            observedEntityTracks.put(entityId, track);
        }
        return track;
    }

    private void pruneObservedTracks(long nowMillis) {
        java.util.Iterator<Map.Entry<Integer, ObservedEntityTrack>> iterator = observedEntityTracks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ObservedEntityTrack> entry = iterator.next();
            ObservedEntityTrack track = entry.getValue();
            while (!track.history.isEmpty()
                    && nowMillis - track.history.getLast().getTimestampMillis() > OBSERVED_ENTITY_HISTORY_MAX_AGE_MS) {
                track.history.removeLast();
            }
            if (track.history.isEmpty()) {
                iterator.remove();
            }
        }

        if (observedEntityTracks.size() <= OBSERVED_ENTITY_TRACK_LIMIT) {
            return;
        }

        Integer stalestKey = null;
        long stalestMillis = Long.MAX_VALUE;
        for (Map.Entry<Integer, ObservedEntityTrack> entry : observedEntityTracks.entrySet()) {
            LinkedList<HitboxFrame> history = entry.getValue().history;
            if (history.isEmpty()) {
                stalestKey = entry.getKey();
                break;
            }
            long newest = history.getFirst().getTimestampMillis();
            if (newest < stalestMillis) {
                stalestMillis = newest;
                stalestKey = entry.getKey();
            }
        }
        if (stalestKey != null) {
            observedEntityTracks.remove(stalestKey);
        }
    }

    private static void appendHistory(LinkedList<HitboxFrame> history, double x, double y, double z, double width,
            double height, boolean teleportMarker, boolean transactionAligned, boolean enforceable, long timestampNanos,
            long nowMillis, long maxAgeMillis) {
        long timestampMillis = nowMillis;
        if (!history.isEmpty() && timestampNanos > 0L) {
            HitboxFrame newest = history.getFirst();
            if (newest.getTimestampNanos() == timestampNanos) {
                timestampMillis = newest.getTimestampMillis();
                history.removeFirst();
            }
        }

        double halfWidth = width * 0.5D;
        history.addFirst(new HitboxFrame(timestampMillis, timestampNanos, teleportMarker, transactionAligned,
                enforceable, x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth));
        while (!history.isEmpty() && timestampMillis - history.getLast().getTimestampMillis() > maxAgeMillis) {
            history.removeLast();
        }
    }

    private static List<HitboxFrame> copyHistory(LinkedList<HitboxFrame> history, long maxAgeMillis,
            long referenceTimeMillis, long maxTimestampNanos, long futureSlackNanos) {
        long now = referenceTimeMillis > 0L ? referenceTimeMillis : System.currentTimeMillis();
        long allowedMaxNanos = maxTimestampNanos > 0L ? maxTimestampNanos + Math.max(0L, futureSlackNanos) : 0L;
        List<HitboxFrame> copy = new ArrayList<HitboxFrame>();
        for (HitboxFrame frame : history) {
            if (now - frame.getTimestampMillis() > maxAgeMillis) {
                continue;
            }
            if (allowedMaxNanos > 0L && frame.getTimestampNanos() > 0L
                    && frame.getTimestampNanos() > allowedMaxNanos) {
                continue;
            }
            copy.add(frame);
        }
        return copy;
    }

    private static final class ObservedEntityTrack {
        private final LinkedList<HitboxFrame> history = new LinkedList<HitboxFrame>();
        private double lastCenterX;
        private double lastMinY;
        private double lastCenterZ;
        private double lastWidth = 0.6D;
        private double lastHeight = 1.8D;
        private boolean initialized;
    }
}
