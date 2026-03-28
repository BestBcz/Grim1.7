package ac.grim.legacyac.data.state;

import ac.grim.legacyac.combat.HitboxFrame;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Domain state aggregate for combat-related data.
 * Tracks attack targets, timing, hitbox history, click windows, etc.
 */
public final class CombatState {
    private long lastAttackAt;
    private int lastAttackTargetId;

    private int clickWindow;
    private long clickWindowStart;

    private final LinkedList<HitboxFrame> hitboxHistory = new LinkedList<HitboxFrame>();

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
        if (!hitboxHistory.isEmpty() && timestampNanos > 0L) {
            HitboxFrame newest = hitboxHistory.getFirst();
            if (newest.getTimestampNanos() == timestampNanos) {
                now = newest.getTimestampMillis();
                hitboxHistory.removeFirst();
            }
        }
        double halfWidth = width * 0.5D;
        hitboxHistory.addFirst(new HitboxFrame(now, timestampNanos, teleportMarker, transactionAligned, enforceable,
                x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth));
        while (!hitboxHistory.isEmpty() && now - hitboxHistory.getLast().getTimestampMillis() > 400L) {
            hitboxHistory.removeLast();
        }
    }

    public synchronized List<HitboxFrame> getHitboxHistorySnapshot(long maxAgeMillis) {
        return getHitboxHistorySnapshot(maxAgeMillis, 0L, 0L);
    }

    public synchronized List<HitboxFrame> getHitboxHistorySnapshot(long maxAgeMillis, long maxTimestampNanos,
            long futureSlackNanos) {
        long now = System.currentTimeMillis();
        long allowedMaxNanos = maxTimestampNanos > 0L ? maxTimestampNanos + Math.max(0L, futureSlackNanos) : 0L;
        List<HitboxFrame> copy = new ArrayList<HitboxFrame>();
        for (HitboxFrame frame : hitboxHistory) {
            if (now - frame.getTimestampMillis() > maxAgeMillis) {
                continue;
            }
            if (allowedMaxNanos > 0L && frame.getTimestampNanos() > 0L && frame.getTimestampNanos() > allowedMaxNanos) {
                continue;
            }
            copy.add(frame);
        }
        return copy;
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
}
