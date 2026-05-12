package com.alonie.xaero_worldgen.bridge.policy;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import net.minecraft.server.world.ServerWorld;

import java.util.concurrent.ConcurrentHashMap;

public final class BridgeFallbackEscalationPolicy {
    private static final int ASSIST_FAILURE_THRESHOLD = getInt(
        "vwgxwm.vanilla_gap_fallback_assist_failure_threshold",
        2,
        1,
        16
    );
    private static final int ASSIST_FAILURE_THRESHOLD_NEAR = getInt(
        "vwgxwm.vanilla_gap_fallback_assist_failure_threshold_near",
        1,
        1,
        16
    );
    private static final int GAP_WINDOW_THRESHOLD = getInt(
        "vwgxwm.vanilla_gap_fallback_gap_window_threshold",
        3,
        1,
        12
    );
    private static final int GAP_WINDOW_THRESHOLD_NEAR = getInt(
        "vwgxwm.vanilla_gap_fallback_gap_window_threshold_near",
        2,
        1,
        12
    );
    private static final double NEAR_HOLE_RATIO_THRESHOLD = getDouble(
        "vwgxwm.vanilla_gap_fallback_near_hole_ratio_threshold",
        0.25D,
        0.0D,
        1.0D
    );
    private static final long ASSIST_FAILURE_WINDOW_MILLIS = getLong(
        "vwgxwm.vanilla_gap_fallback_assist_failure_window_millis",
        20_000L,
        500L,
        600_000L
    );
    private static final int CHUNK_ATTEMPT_LIMIT_FAR = getInt(
        "vwgxwm.vanilla_gap_fallback_chunk_attempt_limit_far",
        3,
        1,
        32
    );
    private static final int CHUNK_ATTEMPT_LIMIT_NEAR = getInt(
        "vwgxwm.vanilla_gap_fallback_chunk_attempt_limit_near",
        6,
        1,
        32
    );
    private static final long CHUNK_ATTEMPT_WINDOW_MILLIS = getLong(
        "vwgxwm.vanilla_gap_fallback_chunk_attempt_window_millis",
        12_000L,
        500L,
        600_000L
    );
    private static final long CHUNK_COOLDOWN_FAR_MILLIS = getLong(
        "vwgxwm.vanilla_gap_fallback_chunk_cooldown_far_millis",
        8_000L,
        500L,
        600_000L
    );
    private static final long CHUNK_COOLDOWN_NEAR_MILLIS = getLong(
        "vwgxwm.vanilla_gap_fallback_chunk_cooldown_near_millis",
        2_500L,
        500L,
        600_000L
    );
    private static final long CHUNK_HIT_SUPPRESS_MILLIS = getLong(
        "vwgxwm.vanilla_gap_fallback_chunk_hit_suppress_millis",
        2_000L,
        0L,
        60_000L
    );
    private static final long STALE_STATE_MILLIS = getLong(
        "vwgxwm.vanilla_gap_fallback_state_ttl_millis",
        180_000L,
        10_000L,
        3_600_000L
    );
    private static final long PRUNE_INTERVAL_MILLIS = 30_000L;

    private static final ConcurrentHashMap<String, AssistRegionState> ASSIST_REGION_STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ChunkFallbackState> CHUNK_STATES = new ConcurrentHashMap<>();
    private static volatile long lastPruneEpoch = 0L;

    private BridgeFallbackEscalationPolicy() {
    }

    public static FallbackDecision evaluateVanillaGapFallback(
        ServerWorld world,
        int chunkX,
        int chunkZ,
        boolean vanillaChunkMissing
    ) {
        if (world == null) {
            return FallbackDecision.blocked("world_null");
        }
        if (!BridgePerfBudget.VANILLA_MISSING_CHUNK_FALLBACK) {
            return FallbackDecision.blocked("feature_disabled");
        }
        if (!BridgeAuditRepairConfig.current().vanillaOnlyFallbackUpgradeEnabled()) {
            return FallbackDecision.blocked("policy_vanilla_only_upgrade_disabled");
        }
        if (!vanillaChunkMissing) {
            return FallbackDecision.blocked("vanilla_chunk_present");
        }

        long now = System.currentTimeMillis();
        pruneIfDue(now);
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        BridgeRegionAuditService.EscalationSignal signal = BridgeRegionAuditService.snapshotEscalationSignal(
            world,
            regionX,
            regionZ,
            XaeroLiveRegionQueue.currentTick()
        );
        int consecutiveMissingWindows = Math.max(0, signal.consecutiveMissingWindows());
        double holeRatio = Math.max(0.0D, signal.holeRatio());
        boolean nearPlayer = signal.nearPlayer();
        int requiredGapWindows = nearPlayer ? GAP_WINDOW_THRESHOLD_NEAR : GAP_WINDOW_THRESHOLD;
        int requiredFailureStreak = nearPlayer ? ASSIST_FAILURE_THRESHOLD_NEAR : ASSIST_FAILURE_THRESHOLD;

        AssistRegionState assistState = ASSIST_REGION_STATES.get(regionKey(world, regionX, regionZ));
        int failureStreak = assistState == null ? 0 : assistState.failureStreak(now);
        boolean assistReady = assistState != null
            && failureStreak >= requiredFailureStreak
            && assistState.hasRecentFailure(now);
        boolean nearHoleReady = nearPlayer && holeRatio >= NEAR_HOLE_RATIO_THRESHOLD;

        if (signal.expectedCount() > 0 && signal.missingCount() <= 0) {
            return FallbackDecision.blocked(
                "gap_not_confirmed",
                failureStreak,
                0,
                0L,
                consecutiveMissingWindows,
                holeRatio,
                nearPlayer
            );
        }
        if (consecutiveMissingWindows < requiredGapWindows) {
            return FallbackDecision.blocked(
                "gap_windows_insufficient",
                failureStreak,
                0,
                0L,
                consecutiveMissingWindows,
                holeRatio,
                nearPlayer
            );
        }
        if (!assistReady && !nearHoleReady) {
            return FallbackDecision.blocked(
                "assist_or_hole_insufficient",
                failureStreak,
                0,
                0L,
                consecutiveMissingWindows,
                holeRatio,
                nearPlayer
            );
        }

        int chunkAttemptLimit = nearPlayer ? CHUNK_ATTEMPT_LIMIT_NEAR : CHUNK_ATTEMPT_LIMIT_FAR;
        long chunkCooldownMillis = nearPlayer ? CHUNK_COOLDOWN_NEAR_MILLIS : CHUNK_COOLDOWN_FAR_MILLIS;

        String chunkKey = chunkKey(world, chunkX, chunkZ);
        ChunkFallbackState chunkState = CHUNK_STATES.computeIfAbsent(chunkKey, ignored -> new ChunkFallbackState());
        synchronized (chunkState) {
            if (chunkState.hitSuppressUntilEpoch > now) {
                return FallbackDecision.blocked(
                    "chunk_hit_suppress",
                    failureStreak,
                    chunkState.attemptsInWindow,
                    chunkState.hitSuppressUntilEpoch - now,
                    consecutiveMissingWindows,
                    holeRatio,
                    nearPlayer
                );
            }
            if (chunkState.cooldownUntilEpoch > now) {
                return FallbackDecision.blocked(
                    "chunk_cooldown",
                    failureStreak,
                    chunkState.attemptsInWindow,
                    chunkState.cooldownUntilEpoch - now,
                    consecutiveMissingWindows,
                    holeRatio,
                    nearPlayer
                );
            }

            if (chunkState.windowStartEpoch <= 0L || now - chunkState.windowStartEpoch > CHUNK_ATTEMPT_WINDOW_MILLIS) {
                chunkState.windowStartEpoch = now;
                chunkState.attemptsInWindow = 0;
            }
            if (chunkState.attemptsInWindow >= chunkAttemptLimit) {
                chunkState.cooldownUntilEpoch = now + chunkCooldownMillis;
                return FallbackDecision.blocked(
                    "chunk_rate_limited",
                    failureStreak,
                    chunkState.attemptsInWindow,
                    chunkCooldownMillis,
                    consecutiveMissingWindows,
                    holeRatio,
                    nearPlayer
                );
            }

            chunkState.attemptsInWindow++;
            chunkState.lastAttemptEpoch = now;
            return FallbackDecision.allowed(
                "gap_escalated",
                failureStreak,
                chunkState.attemptsInWindow,
                0L,
                consecutiveMissingWindows,
                holeRatio,
                nearPlayer
            );
        }
    }

    public static void onAssistLeaseTerminal(
        ServerWorld world,
        int regionX,
        int regionZ,
        BridgeLoadLeaseTracker.TerminalState terminalState
    ) {
        if (world == null || terminalState == null) {
            return;
        }
        long now = System.currentTimeMillis();
        pruneIfDue(now);
        AssistRegionState state = ASSIST_REGION_STATES.computeIfAbsent(regionKey(world, regionX, regionZ), ignored -> new AssistRegionState());
        synchronized (state) {
            state.lastUpdatedEpoch = now;
            switch (terminalState) {
                case SUCCESS -> {
                    state.consecutiveFailures = 0;
                    state.lastSuccessEpoch = now;
                }
                case FAIL, TIMEOUT -> {
                    if (state.lastFailureEpoch <= 0L || now - state.lastFailureEpoch > ASSIST_FAILURE_WINDOW_MILLIS) {
                        state.consecutiveFailures = 1;
                    } else {
                        state.consecutiveFailures++;
                    }
                    state.lastFailureEpoch = now;
                }
                case STALE -> {
                    // Stale is usually version churn; keep existing streak to avoid overreacting.
                }
            }
        }
    }

    public static void onFallbackAttemptResult(ServerWorld world, int chunkX, int chunkZ, boolean hit) {
        if (world == null) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneIfDue(now);
        ChunkFallbackState state = CHUNK_STATES.computeIfAbsent(chunkKey(world, chunkX, chunkZ), ignored -> new ChunkFallbackState());
        synchronized (state) {
            state.lastAttemptEpoch = now;
            if (hit) {
                state.consecutiveMisses = 0;
                state.cooldownUntilEpoch = 0L;
                state.hitSuppressUntilEpoch = now + CHUNK_HIT_SUPPRESS_MILLIS;
                return;
            }

            state.consecutiveMisses++;
            if (state.consecutiveMisses >= CHUNK_ATTEMPT_LIMIT_FAR) {
                state.consecutiveMisses = 0;
                state.cooldownUntilEpoch = now + CHUNK_COOLDOWN_FAR_MILLIS;
            }
        }
    }

    public static boolean isFallbackGapCandidate(ServerWorld world, int regionX, int regionZ) {
        if (world == null) {
            return false;
        }
        if (!BridgeAuditRepairConfig.current().vanillaOnlyFallbackUpgradeEnabled()) {
            return false;
        }
        BridgeRegionAuditService.EscalationSignal signal = BridgeRegionAuditService.snapshotEscalationSignal(
            world,
            regionX,
            regionZ,
            XaeroLiveRegionQueue.currentTick()
        );
        int requiredGapWindows = signal.nearPlayer() ? GAP_WINDOW_THRESHOLD_NEAR : GAP_WINDOW_THRESHOLD;
        if (signal.missingCount() > 0 && signal.consecutiveMissingWindows() >= requiredGapWindows) {
            return true;
        }
        AssistRegionState state = ASSIST_REGION_STATES.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        int requiredFailureStreak = signal.nearPlayer() ? ASSIST_FAILURE_THRESHOLD_NEAR : ASSIST_FAILURE_THRESHOLD;
        return state.failureStreak(now) >= requiredFailureStreak && state.hasRecentFailure(now);
    }

    public static void clearRuntimeState() {
        ASSIST_REGION_STATES.clear();
        CHUNK_STATES.clear();
        lastPruneEpoch = 0L;
    }

    private static void pruneIfDue(long now) {
        long lastPrune = lastPruneEpoch;
        if (lastPrune > 0L && now - lastPrune < PRUNE_INTERVAL_MILLIS) {
            return;
        }
        synchronized (BridgeFallbackEscalationPolicy.class) {
            if (lastPruneEpoch > 0L && now - lastPruneEpoch < PRUNE_INTERVAL_MILLIS) {
                return;
            }
            long staleBefore = now - STALE_STATE_MILLIS;
            ASSIST_REGION_STATES.entrySet().removeIf(entry -> entry.getValue().lastUpdatedEpoch <= 0L || entry.getValue().lastUpdatedEpoch < staleBefore);
            CHUNK_STATES.entrySet().removeIf(entry -> {
                ChunkFallbackState state = entry.getValue();
                long latestEpoch = Math.max(state.lastAttemptEpoch, Math.max(state.cooldownUntilEpoch, state.hitSuppressUntilEpoch));
                return latestEpoch <= 0L || latestEpoch < staleBefore;
            });
            lastPruneEpoch = now;
        }
    }

    private static String regionKey(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }

    private static String chunkKey(ServerWorld world, int chunkX, int chunkZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + chunkX + "|" + chunkZ;
    }

    private static int getInt(String key, int fallback, int min, int max) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long getLong(String key, long fallback, long min, long max) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double getDouble(String key, double fallback, double min, double max) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record FallbackDecision(
        boolean allowed,
        String reason,
        int assistFailureStreak,
        int chunkAttemptCount,
        long cooldownRemainingMillis,
        int consecutiveMissingWindows,
        double holeRatio,
        boolean nearPlayer
    ) {
        private static FallbackDecision blocked(String reason) {
            return new FallbackDecision(false, reason, 0, 0, 0L, 0, 0.0D, false);
        }

        private static FallbackDecision blocked(
            String reason,
            int failureStreak,
            int attemptCount,
            long cooldownRemainingMillis,
            int consecutiveMissingWindows,
            double holeRatio,
            boolean nearPlayer
        ) {
            return new FallbackDecision(
                false,
                reason,
                failureStreak,
                attemptCount,
                Math.max(0L, cooldownRemainingMillis),
                Math.max(0, consecutiveMissingWindows),
                Math.max(0.0D, holeRatio),
                nearPlayer
            );
        }

        private static FallbackDecision allowed(
            String reason,
            int failureStreak,
            int attemptCount,
            long cooldownRemainingMillis,
            int consecutiveMissingWindows,
            double holeRatio,
            boolean nearPlayer
        ) {
            return new FallbackDecision(
                true,
                reason,
                failureStreak,
                attemptCount,
                Math.max(0L, cooldownRemainingMillis),
                Math.max(0, consecutiveMissingWindows),
                Math.max(0.0D, holeRatio),
                nearPlayer
            );
        }
    }

    private static final class AssistRegionState {
        private int consecutiveFailures;
        private long lastFailureEpoch;
        private long lastSuccessEpoch;
        private long lastUpdatedEpoch;

        private int failureStreak(long now) {
            if (lastFailureEpoch <= 0L || now - lastFailureEpoch > ASSIST_FAILURE_WINDOW_MILLIS) {
                return 0;
            }
            return consecutiveFailures;
        }

        private boolean hasRecentFailure(long now) {
            return lastFailureEpoch > 0L && now - lastFailureEpoch <= ASSIST_FAILURE_WINDOW_MILLIS;
        }
    }

    private static final class ChunkFallbackState {
        private long windowStartEpoch;
        private int attemptsInWindow;
        private int consecutiveMisses;
        private long cooldownUntilEpoch;
        private long hitSuppressUntilEpoch;
        private long lastAttemptEpoch;
    }
}


