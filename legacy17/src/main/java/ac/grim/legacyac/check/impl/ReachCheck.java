package ac.grim.legacyac.check.impl;

import ac.grim.legacyac.LegacyAntiCheatPlugin;
import ac.grim.legacyac.check.Check;
import ac.grim.legacyac.combat.HitboxFrame;
import ac.grim.legacyac.combat.RayTraceUtil;
import ac.grim.legacyac.data.FrameContextSnapshot;
import ac.grim.legacyac.data.PlayerData;
import ac.grim.legacyac.evidence.CombatEvidence;
import ac.grim.legacyac.tolerance.ToleranceBudgetEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

public final class ReachCheck extends Check {
    private static final double MOVEMENT_THRESHOLD = 0.03D;
    private static final long ATTACK_HISTORY_FUTURE_SLACK_NANOS = 75000000L;
    private static final long SWEEP_FRAME_MAX_GAP_MS = 175L;
    private static final String CANCEL_BUFFER_KEY = "Reach.cancelBuffer";
    private final Map<UUID, RecentPacketReach> recentPacketReach = new ConcurrentHashMap<UUID, RecentPacketReach>();

    public ReachCheck(LegacyAntiCheatPlugin plugin) {
        super(plugin, "Reach");
    }

    public enum ReachEvidenceType {
        NONE,
        REACH,
        HITBOX_MISS
    }

    public static final class AttackEvaluation {
        private final boolean legal;
        private final double directDistance;
        private final long boxTimeOffsetMs;
        private final boolean teleportMarkerHit;
        private final boolean enforceableWindow;
        private final boolean velocityGraceWindow;
        private final ReachEvidenceType evidenceType;

        public AttackEvaluation(boolean legal, double directDistance, long boxTimeOffsetMs, boolean teleportMarkerHit,
                boolean enforceableWindow, ReachEvidenceType evidenceType) {
            this(legal, directDistance, boxTimeOffsetMs, teleportMarkerHit, enforceableWindow, false, evidenceType);
        }

        public AttackEvaluation(boolean legal, double directDistance, long boxTimeOffsetMs, boolean teleportMarkerHit,
                boolean enforceableWindow, boolean velocityGraceWindow, ReachEvidenceType evidenceType) {
            this.legal = legal;
            this.directDistance = directDistance;
            this.boxTimeOffsetMs = boxTimeOffsetMs;
            this.teleportMarkerHit = teleportMarkerHit;
            this.enforceableWindow = enforceableWindow;
            this.velocityGraceWindow = velocityGraceWindow;
            this.evidenceType = evidenceType;
        }

        public boolean isLegal() { return legal; }
        public double getDirectDistance() { return directDistance; }
        public long getBoxTimeOffsetMs() { return boxTimeOffsetMs; }
        public boolean isTeleportMarkerHit() { return teleportMarkerHit; }
        public boolean isEnforceableWindow() { return enforceableWindow; }
        public boolean isVelocityGraceWindow() { return velocityGraceWindow; }
        public ReachEvidenceType getEvidenceType() { return evidenceType; }
    }

    public void onAttack(EntityDamageByEntityEvent event, Player attacker, Player victim, PlayerData data) {
        if (!isEnabled() || isExempt(attacker, data)) {
            return;
        }

        PlayerData victimData = plugin.getPlayerData(victim);
        if (!isTargetValidForReach(victim, victimData)) {
            coolDownScore(data);
            data.setLastAttackAt(System.currentTimeMillis());
            return;
        }

        RecentPacketReach recentPacket = recentPacketReach.get(attacker.getUniqueId());
        if (recentPacket != null && recentPacket.matches(victim, 175L)) {
            data.setLastAttackAt(System.currentTimeMillis());
            return;
        }

        double baseReach = plugin.getConfig().getDouble("checks.Reach.Ray-Distance", 3.0D);
        long teleportGrace = plugin.getConfig().getLong("combat.reach-teleport-grace-ms", 350L);
        double strafeSyncMargin = plugin.getConfig().getDouble("checks.Reach.strafe-sync-extra-margin", 0.05D);
        boolean recentTeleportOrPearl = System.currentTimeMillis() - victimData.getLastTeleportOrPearlAt() <= teleportGrace;
        double bonus = getAdaptiveReachBonus(data, victimData) + (recentTeleportOrPearl ? strafeSyncMargin : 0.0D);
        double maxReach = baseReach + bonus;
        AttackEvaluation eval = evaluate(attacker, data, victimData, victim.getEntityId(), maxReach, 400L,
                strafeSyncMargin, null);
        eval = softenGraceWindowFalsePositive(data, victimData, victim.getEntityId(), eval, maxReach,
                recentTeleportOrPearl, null);
        boolean velocityUncertain = isVelocityCombatWindowUncertain(data, victimData);
        if (!eval.isLegal() && velocityUncertain && !eval.isVelocityGraceWindow()) {
            eval = withVelocityGrace(eval, true);
        }
        if (!eval.isLegal()) {
            handleViolation(event, attacker, data, victimData, eval, maxReach, baseReach, bonus,
                    recentTeleportOrPearl, "event");
        } else {
            coolDownScore(data);
            double cb = Math.max(0.0D, data.getBuffer(CANCEL_BUFFER_KEY) - 0.25D);
            data.setBuffer(CANCEL_BUFFER_KEY, cb);
        }
        recordReachCombatEvidence(attacker, data, victimData, eval, maxReach, "event");
        data.setLastAttackAt(System.currentTimeMillis());
    }

    public AttackEvaluation onUseEntityAttack(Player attacker, Player target, PlayerData attackerData,
            long backtrackMillis) {
        return onUseEntityAttack(attacker, target, attackerData, backtrackMillis, null);
    }

    public AttackEvaluation onUseEntityAttack(Player attacker, Player target, PlayerData attackerData,
            long backtrackMillis, PlayerData.QueuedAttackSnapshot snapshot) {
        if (!isEnabled() || isExempt(attacker, attackerData)) {
            return new AttackEvaluation(true, 0.0D, 0L, false, true, ReachEvidenceType.NONE);
        }

        PlayerData targetData = plugin.getPlayerData(target);
        if (!isTargetValidForReach(target, targetData)) {
            coolDownScore(attackerData);
            return new AttackEvaluation(true, 0.0D, 0L, false, false, ReachEvidenceType.NONE);
        }

        double baseReach = plugin.getConfig().getDouble("checks.Reach.Ray-Distance", 3.0D);
        long teleportGrace = plugin.getConfig().getLong("combat.reach-teleport-grace-ms", 350L);
        double strafeSyncMargin = plugin.getConfig().getDouble("checks.Reach.strafe-sync-extra-margin", 0.05D);
        boolean recentTeleportOrPearl = System.currentTimeMillis() - targetData.getLastTeleportOrPearlAt() <= teleportGrace;
        double bonus = getAdaptiveReachBonus(attackerData, targetData) + (recentTeleportOrPearl ? strafeSyncMargin : 0.0D);
        double maxReach = baseReach + bonus;
        AttackEvaluation eval = evaluate(attacker, attackerData, targetData, target.getEntityId(), maxReach,
                backtrackMillis, strafeSyncMargin, snapshot);
        eval = softenGraceWindowFalsePositive(attackerData, targetData, target.getEntityId(), eval, maxReach,
                recentTeleportOrPearl, snapshot);
        boolean velocityUncertain = isVelocityCombatWindowUncertain(attackerData, targetData);
        if (!eval.isLegal() && velocityUncertain && !eval.isVelocityGraceWindow()) {
            eval = withVelocityGrace(eval, true);
        }
        recentPacketReach.put(attacker.getUniqueId(), new RecentPacketReach(target.getUniqueId(), System.currentTimeMillis()));

        if (!eval.isLegal()) {
            handleViolation(null, attacker, attackerData, targetData, eval, maxReach, baseReach, bonus,
                    recentTeleportOrPearl, "packet");
        } else {
            coolDownScore(attackerData);
            double cb = Math.max(0.0D, attackerData.getBuffer(CANCEL_BUFFER_KEY) - 0.25D);
            attackerData.setBuffer(CANCEL_BUFFER_KEY, cb);
        }
        recordReachCombatEvidence(attacker, attackerData, targetData, eval, maxReach, "packet");
        return eval;
    }

    private void handleViolation(EntityDamageByEntityEvent event, Player attacker, PlayerData attackerData,
            PlayerData targetData,
            AttackEvaluation eval, double maxReach, double baseReach, double bonus,
            boolean recentTeleportOrPearl, String source) {
        if (eval.getEvidenceType() == ReachEvidenceType.HITBOX_MISS) {
            double closeMissGrace = plugin.getConfig().getDouble("checks.Reach.hitbox-miss-close-margin", 0.18D);
            closeMissGrace += Math.min(0.08D, attackerData.getLastDeltaXZ() * 0.10D);
            closeMissGrace += Math.min(0.05D, Math.abs(attackerData.getLastDeltaY()) * 0.08D);
            closeMissGrace += attackerData.getSpeedLevel() > 0 ? 0.03D * attackerData.getSpeedLevel() : 0.0D;
            if (eval.getDirectDistance() <= maxReach + closeMissGrace) {
                coolDownScore(attackerData);
                return;
            }
        }

        String evidencePrefix = eval.getEvidenceType() == ReachEvidenceType.HITBOX_MISS ? "HITBOX_MISS" : "REACH";
        String evidenceTag = evidencePrefix + "_" + source.toUpperCase(Locale.ROOT);
        boolean velocityUncertain = eval.isVelocityGraceWindow() || isVelocityCombatWindowUncertain(attackerData, targetData);

        if (!eval.isEnforceableWindow() || recentTeleportOrPearl || eval.isTeleportMarkerHit()) {
            String verbose = eval.getEvidenceType() == ReachEvidenceType.HITBOX_MISS
                    ? "type=" + eval.getEvidenceType().name()
                    : String.format(Locale.ROOT, "%.5f", eval.getDirectDistance()) + " blocks";
            plugin.alerts().alert(attacker, getName(), attackerData.getViolation(getName()),
                    source + "-teleport-grace-only " + verbose + " max=" + String.format(Locale.ROOT, "%.3f", maxReach),
                    null, source);
            return;
        }
        if (velocityUncertain) {
            String verbose = eval.getEvidenceType() == ReachEvidenceType.HITBOX_MISS
                    ? "type=" + eval.getEvidenceType().name()
                    : String.format(Locale.ROOT, "%.5f", eval.getDirectDistance()) + " blocks";
            plugin.alerts().alert(attacker, getName(), attackerData.getViolation(getName()),
                    source + "-velocity-grace-only " + verbose + " max=" + String.format(Locale.ROOT, "%.3f", maxReach),
                    null, source);
            return;
        }

        attackerData.setBuffer(CANCEL_BUFFER_KEY, 1.0D);

        String verbose = eval.getEvidenceType() == ReachEvidenceType.HITBOX_MISS
                ? "type=" + eval.getEvidenceType().name()
                : String.format(Locale.ROOT, "%.5f", eval.getDirectDistance()) + " blocks";

        recordEvidence(attackerData, eval.getDirectDistance() - maxReach, evidenceTag);

        String reason = source + "-" + eval.getEvidenceType().name().toLowerCase(Locale.ROOT)
                + " " + verbose + " max="
                + String.format(Locale.ROOT, "%.3f", baseReach)
                + (bonus > 0 ? "+" + String.format(Locale.ROOT, "%.2f", bonus) + "(comp)" : "");
        double add = eval.getEvidenceType() == ReachEvidenceType.HITBOX_MISS
                ? plugin.getConfig().getDouble("checks.Reach.hitbox-miss-add", 0.18D)
                : Math.max(0.15D, eval.getDirectDistance() - maxReach);
        flag(attacker, attackerData, add, reason);
        if (event != null) {
            event.setCancelled(true);
        }
    }

    private boolean isTargetValidForReach(Player target, PlayerData targetData) {
        if (target == null || !target.isOnline()) return false;
        if (target.isDead() || target.getHealth() <= 0.0D) return false;
        PlayerData.MovementStateSnapshot movementSnapshot = targetData.getMovementStateSnapshot();
        if (!movementSnapshot.isTeleportAligned()) return false;
        return !targetData.isTeleportSyncPending();
    }

    private double getAdaptiveReachBonus(PlayerData attackerData, PlayerData targetData) {
        FrameContextSnapshot frameContext = attackerData.getCurrentFrameContext();
        ToleranceBudgetEngine.BudgetSnapshot budget = frameContext != null ? frameContext.getBudgetSnapshot() : getBudget(attackerData);

        double bonus = 0.0D;
        if (budget != null) {
            bonus += Math.min(0.06D, budget.getCombatReachMargin() * 0.35D);
        } else if (isLagging(attackerData)) {
            bonus += Math.min(0.08D, plugin.getConfig().getDouble("adaptive-lag.reach-extra-distance", 0.12D));
        }

        long timeSinceVelocity = System.currentTimeMillis() - attackerData.getLastVelocityAt();
        if (timeSinceVelocity < 400L && attackerData.getLastVelocityXZ() > 0.12D) {
            double decay = 1.0D - (timeSinceVelocity / 400.0D);
            bonus += Math.min(0.05D, attackerData.getLastVelocityXZ() * decay * 0.12D);
        }
        if (attackerData.getSpeedLevel() > 0) {
            bonus += Math.min(0.02D, attackerData.getSpeedLevel() * 0.01D);
        }
        if (targetData != null && targetData.getSpeedLevel() > 0) {
            bonus += Math.min(0.02D, targetData.getSpeedLevel() * 0.01D);
        }
        return Math.min(0.12D, bonus);
    }

    private AttackEvaluation softenGraceWindowFalsePositive(PlayerData attackerData, PlayerData targetData,
            int targetEntityId, AttackEvaluation eval, double maxReach, boolean recentTeleportOrPearl,
            PlayerData.QueuedAttackSnapshot snapshot) {
        if (eval == null || eval.isLegal()) {
            return eval;
        }

        boolean velocityUncertain = isVelocityCombatWindowUncertain(attackerData, targetData);
        boolean teleportUncertain = !eval.isEnforceableWindow() || recentTeleportOrPearl || eval.isTeleportMarkerHit();
        if (!velocityUncertain && !teleportUncertain) {
            return eval;
        }

        double graceMotionAllowance = resolveGraceMotionAllowance(attackerData, targetData, eval,
                targetEntityId, velocityUncertain, teleportUncertain, snapshot);
        if (eval.getDirectDistance() > maxReach + graceMotionAllowance) {
            return eval;
        }

        return new AttackEvaluation(true, eval.getDirectDistance(), eval.getBoxTimeOffsetMs(),
                eval.isTeleportMarkerHit(), eval.isEnforceableWindow(), ReachEvidenceType.NONE);
    }

    private double resolveGraceMotionAllowance(PlayerData attackerData, PlayerData targetData,
            AttackEvaluation eval, int targetEntityId, boolean velocityUncertain, boolean teleportUncertain,
            PlayerData.QueuedAttackSnapshot snapshot) {
        double allowance = 0.04D;
        allowance += Math.min(0.05D, attackerData.getLastDeltaXZ() * 0.10D);

        double targetMotionEnvelope = estimateTargetMotionEnvelope(attackerData, targetData, targetEntityId, snapshot);
        allowance += Math.min(0.22D, targetMotionEnvelope * 0.85D);

        if (velocityUncertain) {
            double velocityMagnitude = Math.max(targetData.getExpectedVelocityXZ(), targetData.getLastVelocityXZ());
            allowance += Math.min(0.24D, velocityMagnitude * 0.38D);
        }
        if (teleportUncertain) {
            long offsetMs = Math.max(0L, eval.getBoxTimeOffsetMs());
            allowance += Math.min(0.18D, offsetMs / 250.0D);
        }
        if (eval.getEvidenceType() == ReachEvidenceType.HITBOX_MISS) {
            allowance += 0.05D;
        }

        return Math.min(0.48D, allowance);
    }

    private double estimateTargetMotionEnvelope(PlayerData attackerData, PlayerData targetData, int targetEntityId,
            PlayerData.QueuedAttackSnapshot snapshot) {
        long referenceTimeMillis = snapshot != null ? snapshot.getCreatedAtMillis() : System.currentTimeMillis();
        long referenceTimeNanos = snapshot != null ? snapshot.getCreatedAtNanos() : 0L;
        List<HitboxFrame> history = referenceTimeNanos > 0L
                ? attackerData.getObservedHitboxHistorySnapshot(targetEntityId, 225L, referenceTimeMillis,
                        referenceTimeNanos, ATTACK_HISTORY_FUTURE_SLACK_NANOS)
                : attackerData.getObservedHitboxHistorySnapshot(targetEntityId, 225L);
        if (history.isEmpty()) {
            history = referenceTimeNanos > 0L
                    ? targetData.getHitboxHistorySnapshot(225L, referenceTimeMillis, referenceTimeNanos,
                            ATTACK_HISTORY_FUTURE_SLACK_NANOS)
                    : targetData.getHitboxHistorySnapshot(225L);
        }
        if (history.size() < 2) {
            return 0.0D;
        }

        double maxHorizontal = 0.0D;
        double maxVertical = 0.0D;
        int considered = 0;
        for (int i = 0; i + 1 < history.size() && considered < 4; i++) {
            HitboxFrame newer = history.get(i);
            HitboxFrame older = history.get(i + 1);
            if (!shouldSweepFrames(newer, older)) {
                continue;
            }

            double dx = frameCenterX(newer) - frameCenterX(older);
            double dy = frameCenterY(newer) - frameCenterY(older);
            double dz = frameCenterZ(newer) - frameCenterZ(older);
            double horizontal = Math.sqrt((dx * dx) + (dz * dz));
            if (horizontal > maxHorizontal) {
                maxHorizontal = horizontal;
            }
            double vertical = Math.abs(dy);
            if (vertical > maxVertical) {
                maxVertical = vertical;
            }
            considered++;
        }

        return maxHorizontal + Math.min(0.08D, maxVertical * 0.35D);
    }

    private boolean isVelocityCombatWindowUncertain(PlayerData attackerData, PlayerData targetData) {
        long now = System.currentTimeMillis();
        long graceMillis = plugin.getConfig().getLong("combat.reach-velocity-grace-ms", 400L);
        if (attackerData.hasPendingVelocityWindow() || targetData.hasPendingVelocityWindow()) {
            return true;
        }
        if (now - attackerData.getLastVelocityAt() <= graceMillis) {
            return true;
        }
        return now - targetData.getLastVelocityAt() <= graceMillis;
    }

    private void recordReachCombatEvidence(Player attacker, PlayerData attackerData, PlayerData targetData,
            AttackEvaluation eval, double maxReach, String source) {
        Location eye = attacker.getEyeLocation();
        FrameContextSnapshot frameContext = attackerData.getCurrentFrameContext();
        CombatEvidence evidence = CombatEvidence.builder(
                CombatEvidence.CombatCheckType.REACH, attacker.getName(),
                targetData != null ? "target" : "unknown")
                .actorPos(eye.getX(), eye.getY(), eye.getZ())
                .actorLook(eye.getYaw(), eye.getPitch())
                .localAttackTime(System.currentTimeMillis())
                .boxTimeOffset(eval.getBoxTimeOffsetMs())
                .directDistance(eval.getDirectDistance())
                .closestHitboxDistance(eval.getDirectDistance())
                .hitboxIntersects(eval.isLegal())
                .teleportMarkerHit(eval.isTeleportMarkerHit())
                .enforceableWindow(eval.isEnforceableWindow())
                .scoring(eval.isLegal() ? 0.0D : Math.max(0.0D, eval.getDirectDistance() - maxReach), maxReach, !eval.isLegal())
                .detail(source + "-" + eval.getEvidenceType().name())
                .frameLink(frameContext == null ? -1L : frameContext.getFrameId(),
                        frameContext == null ? -1 : frameContext.getTxWindowId())
                .build();
        attackerData.recordCombatEvidence(evidence);
    }

    private AttackEvaluation evaluate(Player attacker, PlayerData attackerData, PlayerData targetData,
            int targetEntityId, double maxReach, long backtrackMillis, double strafeSyncMargin,
            PlayerData.QueuedAttackSnapshot snapshot) {
        Location eyeLoc;
        Vector primaryDir;
        if (snapshot != null) {
            eyeLoc = new Location(attacker.getWorld(), snapshot.getOriginX(),
                    snapshot.getOriginY() + attacker.getEyeHeight(), snapshot.getOriginZ(),
                    snapshot.getYaw(), snapshot.getPitch());
            primaryDir = eyeLoc.getDirection();
        } else {
            eyeLoc = attacker.getEyeLocation();
            primaryDir = eyeLoc.getDirection();
        }
        double originX = eyeLoc.getX();
        double originY = snapshot != null ? snapshot.getOriginY() : attacker.getLocation().getY();
        double originZ = eyeLoc.getZ();
        if (primaryDir.lengthSquared() < 1.0E-9D) {
            return new AttackEvaluation(false, 999.0D, -1L, false, false, ReachEvidenceType.REACH);
        }
        primaryDir = primaryDir.normalize();
        double[] possibleEyeHeights = resolvePossibleEyeHeights(attacker);
        List<Vector> originBases = new ArrayList<Vector>(2);
        originBases.add(new Vector(originX, originY, originZ));
        if (snapshot != null && attackerData.isShadowInitialized()) {
            double motionX = attackerData.getShadowMotionX();
            double motionY = attackerData.getShadowMotionY();
            double motionZ = attackerData.getShadowMotionZ();
            if ((motionX * motionX) + (motionY * motionY) + (motionZ * motionZ) > 1.0E-4D) {
                originBases.add(new Vector(originX - motionX, originY - motionY, originZ - motionZ));
            }
        }
        float currentYaw = eyeLoc.getYaw();
        float currentPitch = eyeLoc.getPitch();

        float lastYaw = attackerData.getPrevYaw();
        float lastPitch = attackerData.getPrevPitch();
        Vector altDir = getDirection(lastYaw, lastPitch);
        altDir = altDir.lengthSquared() < 1.0E-9D ? primaryDir : altDir.normalize();

        Vector strafeDir = new Vector(primaryDir.getZ(), 0.0D, -primaryDir.getX());
        if (strafeDir.lengthSquared() > 1.0E-9D) {
            strafeDir = strafeDir.normalize();
        }
        List<Vector> lookDirections = new ArrayList<Vector>(9);
        lookDirections.add(primaryDir);
        lookDirections.add(altDir);
        Vector averagedDir = primaryDir.clone().add(altDir);
        if (averagedDir.lengthSquared() > 1.0E-9D) {
            lookDirections.add(averagedDir.normalize());
        }
        if (strafeDir.lengthSquared() > 1.0E-9D) {
            lookDirections.add(primaryDir.clone().add(strafeDir.clone().multiply(strafeSyncMargin)).normalize());
            lookDirections.add(primaryDir.clone().subtract(strafeDir.clone().multiply(strafeSyncMargin)).normalize());
        }
        lookDirections.add(getDirection(currentYaw + 1.0F, currentPitch).normalize());
        lookDirections.add(getDirection(currentYaw - 1.0F, currentPitch).normalize());
        lookDirections.add(getDirection(currentYaw, currentPitch + 0.8F).normalize());
        lookDirections.add(getDirection(currentYaw, currentPitch - 0.8F).normalize());

        double hitboxExpand = resolveHitboxExpand(attackerData);
        double rayLength = maxReach + 3.0D;

        double closestIntersection = Double.MAX_VALUE;
        long hitOffset = -1L;
        boolean markerHit = false;
        boolean enforceableWindow = true;
        long now = System.currentTimeMillis();
        long referenceTimeMillis = snapshot != null ? snapshot.getCreatedAtMillis() : now;

        List<HitboxFrame> frames = buildAttackFrames(attackerData, targetData, targetEntityId, backtrackMillis, snapshot);
        if (frames.isEmpty()) {
            return new AttackEvaluation(true, 0.0D, 0L, false, false, ReachEvidenceType.NONE);
        }
        for (HitboxFrame frame : frames) {
            HitboxFrame expanded = expandedFrame(frame, hitboxExpand);
            for (Vector originBase : originBases) {
                for (double eyeHeight : possibleEyeHeights) {
                    Vector origin = originBase.clone().add(new Vector(0.0D, eyeHeight, 0.0D));
                    if (RayTraceUtil.isVecInside(origin, expanded)) {
                        return new AttackEvaluation(true, 0.0D, temporalOffsetMillis(referenceTimeMillis, frame),
                                frame.isTeleportMarker(),
                                isMovementFrameTrusted(frame), ReachEvidenceType.NONE);
                    }

                    for (Vector lookDir : lookDirections) {
                        double dist = RayTraceUtil.intersectionDistance(origin, lookDir, rayLength, expanded);
                        if (dist < closestIntersection) {
                            closestIntersection = dist;
                            hitOffset = temporalOffsetMillis(referenceTimeMillis, frame);
                            markerHit = frame.isTeleportMarker();
                            enforceableWindow = isMovementFrameTrusted(frame);
                        }
                    }
                }
            }
        }

        if (closestIntersection <= maxReach) {
            return new AttackEvaluation(true, closestIntersection, hitOffset, markerHit, enforceableWindow, ReachEvidenceType.NONE);
        }
        if (closestIntersection < Double.MAX_VALUE) {
            return new AttackEvaluation(false, closestIntersection, hitOffset, markerHit, enforceableWindow, ReachEvidenceType.REACH);
        }

        double minReachToBox = Double.MAX_VALUE;
        boolean closestBoxEnforceable = true;
        for (HitboxFrame frame : frames) {
            HitboxFrame expanded = expandedFrame(frame, hitboxExpand);
            for (Vector originBase : originBases) {
                for (double eyeHeight : possibleEyeHeights) {
                    Vector origin = originBase.clone().add(new Vector(0.0D, eyeHeight, 0.0D));
                    double dist = closestPointDistance(origin, expanded);
                    if (dist < minReachToBox) {
                        minReachToBox = dist;
                        markerHit = frame.isTeleportMarker();
                        hitOffset = temporalOffsetMillis(referenceTimeMillis, frame);
                        closestBoxEnforceable = isMovementFrameTrusted(frame);
                    }
                }
            }
        }

        return new AttackEvaluation(false, minReachToBox, hitOffset, markerHit, closestBoxEnforceable, ReachEvidenceType.HITBOX_MISS);
    }

    private double resolveHitboxExpand(PlayerData attackerData) {
        double expand = plugin.getConfig().getDouble("checks.Reach.hitbox-threshold", 0.0005D) + 0.1D;
        PlayerData.MovementStateSnapshot movementSnapshot = attackerData.getMovementStateSnapshot();
        if (!movementSnapshot.isEnforceable() || isLagging(attackerData)) {
            expand += 0.02D;
        } else if (attackerData.getLastDeltaXZ() <= MOVEMENT_THRESHOLD
                && Math.abs(attackerData.getLastDeltaY()) <= MOVEMENT_THRESHOLD) {
            expand += 0.01D;
        }
        if (attackerData.getSpeedLevel() > 0) {
            expand += Math.min(0.015D, attackerData.getSpeedLevel() * 0.005D);
        }
        return Math.min(0.135D, expand);
    }

    private static double closestPointDistance(Vector point, HitboxFrame box) {
        double cx = Math.max(box.getMinX(), Math.min(point.getX(), box.getMaxX()));
        double cy = Math.max(box.getMinY(), Math.min(point.getY(), box.getMaxY()));
        double cz = Math.max(box.getMinZ(), Math.min(point.getZ(), box.getMaxZ()));
        double dx = point.getX() - cx;
        double dy = point.getY() - cy;
        double dz = point.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static HitboxFrame expandedFrame(HitboxFrame frame, double expand) {
        return new HitboxFrame(frame.getTimestampMillis(), frame.getTimestampNanos(),
                frame.isTeleportMarker(), frame.isTransactionAligned(),
                frame.isEnforceable(), frame.getMinX() - expand, frame.getMinY(), frame.getMinZ() - expand,
                frame.getMaxX() + expand, frame.getMaxY(), frame.getMaxZ() + expand);
    }

    private List<HitboxFrame> buildAttackFrames(PlayerData attackerData, PlayerData targetData, int targetEntityId,
            long backtrackMillis, PlayerData.QueuedAttackSnapshot snapshot) {
        List<HitboxFrame> history = snapshot != null
                ? attackerData.getObservedHitboxHistorySnapshot(targetEntityId, backtrackMillis,
                        snapshot.getCreatedAtMillis(), snapshot.getCreatedAtNanos(),
                        ATTACK_HISTORY_FUTURE_SLACK_NANOS)
                : attackerData.getObservedHitboxHistorySnapshot(targetEntityId, backtrackMillis);
        if (history.isEmpty()) {
            history = snapshot != null
                    ? targetData.getHitboxHistorySnapshot(backtrackMillis, snapshot.getCreatedAtMillis(),
                            snapshot.getCreatedAtNanos(),
                            ATTACK_HISTORY_FUTURE_SLACK_NANOS)
                    : targetData.getHitboxHistorySnapshot(backtrackMillis);
        }
        if (history.isEmpty()) {
            return history;
        }

        List<HitboxFrame> expanded = new ArrayList<HitboxFrame>(history.size() * 2);
        for (int i = 0; i < history.size(); i++) {
            HitboxFrame current = history.get(i);
            expanded.add(current);
            if (i + 1 >= history.size()) {
                continue;
            }

            HitboxFrame previous = history.get(i + 1);
            if (!shouldSweepFrames(current, previous)) {
                continue;
            }
            expanded.add(sweptFrame(current, previous));
        }
        return expanded;
    }

    private static long temporalOffsetMillis(long referenceTimeMillis, HitboxFrame frame) {
        return Math.abs(referenceTimeMillis - frame.getTimestampMillis());
    }

    private static boolean shouldSweepFrames(HitboxFrame newer, HitboxFrame older) {
        if (newer.isTeleportMarker() || older.isTeleportMarker()) {
            return false;
        }
        if (Math.abs(newer.getTimestampMillis() - older.getTimestampMillis()) > SWEEP_FRAME_MAX_GAP_MS) {
            return false;
        }

        double centerDx = frameCenterX(newer) - frameCenterX(older);
        double centerDy = frameCenterY(newer) - frameCenterY(older);
        double centerDz = frameCenterZ(newer) - frameCenterZ(older);
        return (centerDx * centerDx) + (centerDy * centerDy) + (centerDz * centerDz) > 1.0E-4D;
    }

    private static HitboxFrame sweptFrame(HitboxFrame newer, HitboxFrame older) {
        long timestampMillis = Math.max(newer.getTimestampMillis(), older.getTimestampMillis());
        long timestampNanos = Math.max(newer.getTimestampNanos(), older.getTimestampNanos());
        boolean teleportMarker = newer.isTeleportMarker() || older.isTeleportMarker();
        boolean transactionAligned = newer.isTransactionAligned() && older.isTransactionAligned();
        boolean enforceable = newer.isEnforceable() && older.isEnforceable();
        return new HitboxFrame(timestampMillis, timestampNanos, teleportMarker, transactionAligned, enforceable,
                Math.min(newer.getMinX(), older.getMinX()),
                Math.min(newer.getMinY(), older.getMinY()),
                Math.min(newer.getMinZ(), older.getMinZ()),
                Math.max(newer.getMaxX(), older.getMaxX()),
                Math.max(newer.getMaxY(), older.getMaxY()),
                Math.max(newer.getMaxZ(), older.getMaxZ()));
    }

    private static boolean isMovementFrameTrusted(HitboxFrame frame) {
        return frame.isEnforceable() && frame.isTransactionAligned();
    }

    private static double[] resolvePossibleEyeHeights(Player attacker) {
        double currentEyeHeight = attacker.getEyeHeight();
        if (attacker.isSneaking()) {
            if (Math.abs(currentEyeHeight - 1.54D) < 1.0E-3D) {
                return new double[] { currentEyeHeight, 1.62D };
            }
            return new double[] { currentEyeHeight, 1.54D, 1.62D };
        }
        if (Math.abs(currentEyeHeight - 1.62D) < 1.0E-3D) {
            return new double[] { currentEyeHeight };
        }
        return new double[] { currentEyeHeight, 1.62D };
    }

    private static double frameCenterX(HitboxFrame frame) {
        return (frame.getMinX() + frame.getMaxX()) * 0.5D;
    }

    private static double frameCenterY(HitboxFrame frame) {
        return (frame.getMinY() + frame.getMaxY()) * 0.5D;
    }

    private static double frameCenterZ(HitboxFrame frame) {
        return (frame.getMinZ() + frame.getMaxZ()) * 0.5D;
    }

    private static Vector getDirection(float yaw, float pitch) {
        double yawRad = Math.toRadians(-yaw - 180.0F);
        double pitchRad = Math.toRadians(-pitch);
        double pitchCos = Math.cos(pitchRad);
        double x = Math.sin(yawRad) * pitchCos;
        double y = Math.sin(pitchRad);
        double z = Math.cos(yawRad) * pitchCos;
        return new Vector(x, y, z);
    }

    private static AttackEvaluation withVelocityGrace(AttackEvaluation eval, boolean velocityGraceWindow) {
        return new AttackEvaluation(eval.isLegal(), eval.getDirectDistance(), eval.getBoxTimeOffsetMs(),
                eval.isTeleportMarkerHit(), eval.isEnforceableWindow(), velocityGraceWindow,
                eval.getEvidenceType());
    }

    private static final class RecentPacketReach {
        private final UUID targetUuid;
        private final long createdAtMillis;

        private RecentPacketReach(UUID targetUuid, long createdAtMillis) {
            this.targetUuid = targetUuid;
            this.createdAtMillis = createdAtMillis;
        }

        private boolean matches(Player target, long maxAgeMillis) {
            return target != null && target.getUniqueId().equals(targetUuid)
                    && System.currentTimeMillis() - createdAtMillis <= maxAgeMillis;
        }
    }
}
