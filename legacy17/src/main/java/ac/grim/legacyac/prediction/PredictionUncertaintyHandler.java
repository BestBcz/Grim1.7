package ac.grim.legacyac.prediction;

import ac.grim.legacyac.LegacyAntiCheatPlugin;
import ac.grim.legacyac.data.PlayerData;

/**
 * Small 1.7.10-specific correction layer that sits on top of the unified
 * tolerance budget. This no longer acts as a second full budget system.
 */
public final class PredictionUncertaintyHandler {
    private static final double MAX_TOTAL_CORRECTION = 0.12D;
    private static final double MAX_TERRAIN_CORRECTION = 0.05D;
    private static final double MAX_UTILITY_CORRECTION = 0.05D;
    private static final double MAX_PENDING_CORRECTION = 0.06D;
    private static final double MAX_RECENT_VELOCITY_CORRECTION = 0.18D;
    private static final double MAX_JUMP_TRANSITION_CORRECTION = 0.04D;

    private PredictionUncertaintyHandler() {
    }

    public static double resolveLegacyCorrection(PlayerData.PredictionContext context, PlayerData data,
            PlayerData.MovementStateSnapshot state, LegacyAntiCheatPlugin plugin, double deltaY) {
        double terrainLandingCorrection = 0.0D;
        if (context.isRecentUnevenGround() || context.isRecentSnowLayerGround() || context.isNearPartialGround()
                || context.isRecentIceGround()) {
            terrainLandingCorrection += Math.abs(deltaY) > 0.20D ? 0.05D : 0.03D;
        }
        if (context.isRecentHeadHit()) {
            terrainLandingCorrection += 0.02D;
        }
        if (context.isRecentHighFall()) {
            terrainLandingCorrection += plugin.getConfig().getDouble("prediction.budget.high-fall-recovery", 0.16D);
        }
        terrainLandingCorrection = Math.min(MAX_TERRAIN_CORRECTION, terrainLandingCorrection);

        double utilityCorrection = 0.0D;
        if (data.isInventoryOpen() && Math.abs(deltaY) > 0.05D) {
            utilityCorrection += plugin.getConfig().getDouble("prediction.budget.inventory-air", 0.05D);
        }
        if (data.hasRecentUseItemPacket(plugin.getConfig().getLong("checks.NoSlow.use-packet-max-age-ms", 250L))
                && Math.abs(deltaY) > 0.05D) {
            utilityCorrection += plugin.getConfig().getDouble("prediction.budget.use-item-air", 0.05D);
        }
        if (context.isRecentRodPull()) {
            utilityCorrection += Math.min(0.03D, plugin.getConfig().getDouble("prediction.budget.rod-pull", 0.08D));
        }
        utilityCorrection = Math.min(MAX_UTILITY_CORRECTION, utilityCorrection);

        double pendingStateCorrection = 0.0D;
        if (state != null && !state.isEnforceable()) {
            pendingStateCorrection = Math.min(MAX_PENDING_CORRECTION,
                    plugin.getConfig().getDouble("adaptive-lag.pending-state-margin", 0.06D));
        }

        double recentVelocityCorrection = 0.0D;
        if (context.isRecentVelocity()) {
            recentVelocityCorrection = Math.min(MAX_RECENT_VELOCITY_CORRECTION,
                    plugin.getConfig().getDouble("prediction.budget.recent-velocity", 0.18D));
        }

        double jumpTransitionCorrection = 0.0D;
        boolean jumpTakeoff = data.wasOnGround() && !data.isOnGroundNow()
                && deltaY > 0.34D && data.getLastDeltaXZ() > 0.14D;
        if (jumpTakeoff) {
            jumpTransitionCorrection += 0.025D;
        }
        boolean landingTransition = !data.wasOnGround() && data.isOnGroundNow()
                && Math.abs(deltaY) < 0.085D && data.getLastDeltaXZ() > 0.12D;
        if (landingTransition) {
            jumpTransitionCorrection += 0.020D;
        }
        boolean canonicalJumpArc = data.getLastDeltaXZ() > 0.10D
                && ((deltaY >= 0.39D && deltaY <= 0.44D)
                || (deltaY >= 0.30D && deltaY <= 0.35D)
                || (deltaY >= -0.10D && deltaY <= -0.06D));
        if (canonicalJumpArc) {
            jumpTransitionCorrection += 0.018D;
        }
        long timeSinceAttack = System.currentTimeMillis() - data.getLastAttackAt();
        boolean airborneCombatTransition = timeSinceAttack <= 225L && !data.isOnGroundNow()
                && data.getLastDeltaXZ() > 0.10D
                && ((deltaY >= 0.30D && deltaY <= 0.44D)
                || (deltaY >= -0.10D && deltaY <= 0.05D));
        if (airborneCombatTransition) {
            jumpTransitionCorrection += 0.022D;
        }
        if ((jumpTakeoff || landingTransition) && (context.isRecentUnevenGround() || context.isNearPartialGround()
                || context.isRecentHeadHit())) {
            jumpTransitionCorrection += 0.010D;
        }
        jumpTransitionCorrection = Math.min(MAX_JUMP_TRANSITION_CORRECTION, jumpTransitionCorrection);

        double total = terrainLandingCorrection + utilityCorrection + pendingStateCorrection
                + recentVelocityCorrection + jumpTransitionCorrection;
        return Math.min(MAX_TOTAL_CORRECTION, total);
    }
}
