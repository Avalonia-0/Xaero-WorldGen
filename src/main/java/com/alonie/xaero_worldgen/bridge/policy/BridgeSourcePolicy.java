package com.alonie.xaero_worldgen.bridge.policy;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import com.alonie.xaero_worldgen.VwgXwmBridgeClient;
import net.minecraft.server.world.ServerWorld;

import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeSourcePolicy {
    private static final ConcurrentHashMap<String, SourcePolicy> LAST_POLICY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> LAST_EVENT_EPOCH = new ConcurrentHashMap<>();
    private static final long EVENT_LOG_THROTTLE_MILLIS = 5_000L;

    private BridgeSourcePolicy() {
    }

    public static SourcePolicy classify(ServerWorld world, int regionX, int regionZ) {
        if (world == null) {
            return SourcePolicy.NO_SOURCE;
        }

        SourcePolicy policy = computePolicy(world, regionX, regionZ);
        emitPolicyIfChanged(world, regionX, regionZ, policy);
        return policy;
    }

    public static boolean allowsBridgeFallback(ServerWorld world, int chunkX, int chunkZ) {
        if (world == null) {
            return false;
        }

        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        SourcePolicy policy = classify(world, regionX, regionZ);
        if (policy == SourcePolicy.BRIDGE_ONLY) {
            return true;
        }

        if (policy == SourcePolicy.VANILLA_ONLY) {
            if (!BridgeAuditRepairConfig.current().vanillaOnlyFallbackUpgradeEnabled()) {
                emitThrottled(
                    "FALLBACK_SKIPPED",
                    world,
                    regionX,
                    regionZ,
                    "result=policy_vanilla_only,chunkX="
                        + chunkX
                        + ",chunkZ="
                        + chunkZ
                        + ",reason=policy_vanilla_only_upgrade_disabled"
                );
                return false;
            }
            boolean missingChunk = isVanillaChunkHeaderMissing(world, chunkX, chunkZ);
            BridgeRegionAuditService.EscalationSignal signal = BridgeRegionAuditService.snapshotEscalationSignal(
                world,
                regionX,
                regionZ,
                XaeroLiveRegionQueue.currentTick()
            );
            BridgeFallbackEscalationPolicy.FallbackDecision decision = BridgeFallbackEscalationPolicy.evaluateVanillaGapFallback(
                world,
                chunkX,
                chunkZ,
                missingChunk
            );
            if (decision.allowed()) {
                emitThrottled(
                    "FALLBACK_ALLOWED",
                    world,
                    regionX,
                    regionZ,
                    "result=policy_vanilla_only_missing_chunk,chunkX="
                        + chunkX
                        + ",chunkZ="
                        + chunkZ
                        + ",reason="
                        + decision.reason()
                        + ",assistFailureStreak="
                        + decision.assistFailureStreak()
                        + ",chunkAttemptCount="
                        + decision.chunkAttemptCount()
                        + ",consecutiveMissingWindows="
                        + decision.consecutiveMissingWindows()
                        + ",holeRatio="
                        + String.format(java.util.Locale.ROOT, "%.3f", decision.holeRatio())
                        + ",nearPlayer="
                        + decision.nearPlayer()
                        + ",auditMissingCount="
                        + signal.missingCount()
                        + ",auditExpectedCount="
                        + signal.expectedCount()
                );
                return true;
            }
            emitThrottled(
                "FALLBACK_SKIPPED",
                world,
                regionX,
                regionZ,
                "result=policy_vanilla_only,chunkX="
                    + chunkX
                    + ",chunkZ="
                    + chunkZ
                    + ",reason="
                    + decision.reason()
                    + ",assistFailureStreak="
                    + decision.assistFailureStreak()
                    + ",chunkAttemptCount="
                    + decision.chunkAttemptCount()
                    + ",cooldownRemainingMillis="
                    + decision.cooldownRemainingMillis()
                    + ",consecutiveMissingWindows="
                    + decision.consecutiveMissingWindows()
                    + ",holeRatio="
                    + String.format(java.util.Locale.ROOT, "%.3f", decision.holeRatio())
                    + ",nearPlayer="
                    + decision.nearPlayer()
                    + ",auditMissingCount="
                    + signal.missingCount()
                    + ",auditExpectedCount="
                    + signal.expectedCount()
            );
        }
        return false;
    }

    public static boolean isVanillaChunkHeaderMissing(ServerWorld world, int chunkX, int chunkZ) {
        if (world == null) {
            return false;
        }

        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        if (!Files.isRegularFile(BridgePaths.getRegionFile(world, regionX, regionZ))) {
            return true;
        }

        int localX = chunkX & 31;
        int localZ = chunkZ & 31;
        int localChunkIndex = localX + (localZ << 5);
        return !BridgeMcaHeaderCache.isChunkPresent(
            world,
            regionX,
            regionZ,
            localChunkIndex,
            XaeroLiveRegionQueue.currentTick(),
            BridgeAuditConfig.current().mcaHeaderRefreshTicks()
        );
    }

    public static boolean allowsBridgeDataPipeline(ServerWorld world, int regionX, int regionZ) {
        return classify(world, regionX, regionZ) != SourcePolicy.VANILLA_ONLY;
    }

    public static boolean allowsVanillaAssistLoad(ServerWorld world, int regionX, int regionZ) {
        SourcePolicy policy = classify(world, regionX, regionZ);
        return policy == SourcePolicy.VANILLA_ONLY || policy == SourcePolicy.BRIDGE_ONLY;
    }

    public static void recordQueueDrop(ServerWorld world, int regionX, int regionZ, String reason) {
        if (world == null) {
            return;
        }

        emitThrottled(
            "QUEUE_DROP",
            world,
            regionX,
            regionZ,
            "result=policy_vanilla_only,reason=" + normalizeReason(reason)
        );
    }

    public static void recordMixedSourceGuardHit(ServerWorld world, int regionX, int regionZ, String reason) {
        if (world == null) {
            return;
        }

        emitThrottled(
            "MIXED_SOURCE_GUARD_HIT",
            world,
            regionX,
            regionZ,
            "result=blocked,reason=" + normalizeReason(reason)
        );
    }

    public static void clearRuntimeState() {
        LAST_POLICY.clear();
        LAST_EVENT_EPOCH.clear();
        BridgeFallbackEscalationPolicy.clearRuntimeState();
    }

    @Deprecated
    public static boolean allowsFallback(ServerWorld world, int chunkX, int chunkZ) {
        return allowsBridgeFallback(world, chunkX, chunkZ);
    }

    @Deprecated
    public static boolean allowsBridgeQueue(ServerWorld world, int regionX, int regionZ) {
        return allowsBridgeDataPipeline(world, regionX, regionZ);
    }

    private static SourcePolicy computePolicy(ServerWorld world, int regionX, int regionZ) {
        if (Files.isRegularFile(BridgePaths.getRegionFile(world, regionX, regionZ))) {
            return SourcePolicy.VANILLA_ONLY;
        }

        if (BridgeRegionReleaseManager.hasCurrentCommittedDirtyVersion(world, regionX, regionZ)) {
            return SourcePolicy.BRIDGE_ONLY;
        }

        return SourcePolicy.NO_SOURCE;
    }

    private static void emitPolicyIfChanged(ServerWorld world, int regionX, int regionZ, SourcePolicy policy) {
        String key = regionKey(world, regionX, regionZ);
        SourcePolicy previous = LAST_POLICY.put(key, policy);
        if (previous == policy) {
            return;
        }

        BridgeMcaHeaderCache.McaCoverage coverage = BridgeMcaHeaderCache.getCoverage(
            world,
            regionX,
            regionZ,
            XaeroLiveRegionQueue.currentTick(),
            BridgeAuditConfig.current().mcaHeaderRefreshTicks()
        );
        boolean regionFileExists = coverage.fileExists();
        int mcaChunkCount = coverage.chunkCount();
        VwgXwmBridgeClient.LOGGER.info(
            "[VWG->XWM Bridge][Trace] phase=SOURCE_POLICY result={} dim={} regionX={} regionZ={} regionFileExists={} mcaChunkCount={}",
            policy.id,
            world.getRegistryKey().getValue(),
            regionX,
            regionZ,
            regionFileExists,
            mcaChunkCount
        );
        VwgXwmBridgeClient.LOGGER.info(
            "[VWG->XWM Bridge][Trace] phase=SOURCE_POLICY_DETAIL result={} dim={} regionX={} regionZ={} regionFileExists={} mcaChunkCount={}",
            policy.id,
            world.getRegistryKey().getValue(),
            regionX,
            regionZ,
            regionFileExists,
            mcaChunkCount
        );
    }

    private static void emitThrottled(String phase, ServerWorld world, int regionX, int regionZ, String details) {
        long now = System.currentTimeMillis();
        String key = phase + "|" + regionKey(world, regionX, regionZ);
        long previousEpoch = LAST_EVENT_EPOCH.getOrDefault(key, -1L);
        if (previousEpoch >= 0L && now - previousEpoch < EVENT_LOG_THROTTLE_MILLIS) {
            return;
        }
        LAST_EVENT_EPOCH.put(key, now);

        VwgXwmBridgeClient.LOGGER.info(
            "[VWG->XWM Bridge][Trace] phase={} dim={} regionX={} regionZ={} {}",
            phase,
            world.getRegistryKey().getValue(),
            regionX,
            regionZ,
            details
        );
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason;
    }

    private static String regionKey(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }

    public enum SourcePolicy {
        VANILLA_ONLY("vanilla_only"),
        BRIDGE_ONLY("bridge_only"),
        NO_SOURCE("no_source");

        private final String id;

        SourcePolicy(String id) {
            this.id = id;
        }
    }
}


