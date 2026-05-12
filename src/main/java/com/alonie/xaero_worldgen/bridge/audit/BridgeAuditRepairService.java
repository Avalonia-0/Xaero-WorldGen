package com.alonie.xaero_worldgen.bridge.audit;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Locale;

public final class BridgeAuditRepairService {
    private static final long VANILLA_ONLY_REPAIR_DEBOUNCE_MILLIS = 120L * 50L;
    private static final long SUMMARY_INTERVAL_TICKS = 200L;

    private BridgeAuditRepairService() {
    }

    public static void tickServer(MinecraftServer server) {
        BridgeAuditRepairConfig.Config config = BridgeAuditRepairConfig.current();
        if (!config.enabled()) {
            return;
        }

        long tick = server.getTicks();
        if (tick <= 0L || tick % config.intervalTicks() != 0L) {
            return;
        }

        for (ServerWorld world : server.getWorlds()) {
            processWorld(world, tick, config);
        }
    }

    private static void processWorld(ServerWorld world, long tick, BridgeAuditRepairConfig.Config config) {
        long startedNanos = System.nanoTime();
        long timeBudgetNanos = Math.max(100_000L, config.timeBudgetMicros() * 1_000L);
        ArrayList<BridgeRegionAuditService.RepairCandidate> candidates = BridgeRegionAuditService.snapshotRepairCandidates(
            world,
            config.topK(),
            tick,
            config.repairCooldownTicks()
        );

        int processed = 0;
        int cooldownSkipped = 0;
        int policySkipped = 0;
        boolean budgetLimited = false;
        for (BridgeRegionAuditService.RepairCandidate candidate : candidates) {
            if (processed >= Math.max(1, config.maxRegionsPerTick()) || System.nanoTime() - startedNanos > timeBudgetNanos) {
                budgetLimited = true;
                break;
            }

            long cooldownRemaining = candidate.cooldownRemainingTicks();
            if (cooldownRemaining > 0L) {
                cooldownSkipped++;
                BridgeAuditLogger.log(
                    tick,
                    "REPAIR_COOLDOWN",
                    "skip",
                    world,
                    candidate.regionX(),
                    candidate.regionZ(),
                    "classification="
                        + candidate.classification()
                        + ",cooldownRemainingTicks="
                        + cooldownRemaining
                        + ",consecutiveMissing="
                        + candidate.consecutiveMissingWindows()
                        + ",holeRatio="
                        + formatRatio(candidate.holeRatio())
                );
                continue;
            }

            if (candidate.sourcePolicy() == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY && !config.vanillaOnlyRepairEnabled()) {
                policySkipped++;
                BridgeAuditLogger.log(
                    tick,
                    "REPAIR_SKIPPED",
                    "policy_vanilla_only_disabled",
                    world,
                    candidate.regionX(),
                    candidate.regionZ(),
                    "sourcePolicy=vanilla_only,classification="
                        + candidate.classification()
                        + ",expected="
                        + candidate.expectedCount()
                        + ",loaded="
                        + candidate.loadedCount()
                        + ",loadedSession="
                        + candidate.loadedSessionCount()
                        + ",loadedVisible="
                        + candidate.loadedVisibleCount()
                        + ",missing="
                        + candidate.missingCount()
                        + ",holeRatio="
                        + formatRatio(candidate.holeRatio())
                );
                continue;
            }

            BridgeRegionAuditService.markRepairAttempt(world, candidate.regionX(), candidate.regionZ(), tick, "requested");
            BridgeMcaHeaderCache.McaCoverage coverage = BridgeMcaHeaderCache.forceRefresh(
                world,
                candidate.regionX(),
                candidate.regionZ(),
                tick
            );
            BridgeDirtyRegionStore.DirtyMarkResult dirtyMarkResult = BridgeDirtyRegionStore.markDirtyDebounced(
                world,
                candidate.regionX(),
                candidate.regionZ(),
                VANILLA_ONLY_REPAIR_DEBOUNCE_MILLIS
            );
            XaeroLiveRegionQueue.enqueue(world, candidate.regionX(), candidate.regionZ());
            long dirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, candidate.regionX(), candidate.regionZ());
            boolean cacheInvalidateRequested = shouldRequestCacheInvalidation(candidate);
            if (cacheInvalidateRequested) {
                BridgeAuditRepairCacheInvalidation.requestInvalidation(
                    world,
                    candidate.regionX(),
                    candidate.regionZ(),
                    dirtyVersion
                );
            }

            BridgeAuditLogger.log(
                tick,
                "REPAIR_REQUEST",
                "issued",
                world,
                candidate.regionX(),
                candidate.regionZ(),
                "classification="
                    + candidate.classification()
                    + ",expected="
                    + candidate.expectedCount()
                    + ",loaded="
                    + candidate.loadedCount()
                    + ",loadedSession="
                    + candidate.loadedSessionCount()
                    + ",loadedVisible="
                    + candidate.loadedVisibleCount()
                    + ",missing="
                    + candidate.missingCount()
                    + ",holeRatio="
                    + formatRatio(candidate.holeRatio())
                    + ",consecutiveMissing="
                    + candidate.consecutiveMissingWindows()
            );
            BridgeAuditLogger.log(
                tick,
                "REPAIR_RESULT",
                "scheduled",
                world,
                candidate.regionX(),
                candidate.regionZ(),
                "dirtyVersion="
                    + dirtyVersion
                    + ",debounced="
                    + dirtyMarkResult.debounced()
                    + ",newlyDirty="
                    + dirtyMarkResult.newlyDirty()
                    + ",mcaChunkCount="
                    + coverage.chunkCount()
                    + ",cacheInvalidateRequested="
                    + cacheInvalidateRequested
            );
            processed++;
        }

        long elapsedMicros = (System.nanoTime() - startedNanos) / 1_000L;
        BridgeAuditLogger.log(
            tick,
            "REPAIR_ROLLUP",
            "window",
            world,
            null,
            null,
            "processed="
                + processed
                + ",candidates="
                + candidates.size()
                + ",cooldownSkipped="
                + cooldownSkipped
                + ",policySkipped="
                + policySkipped
                + ",budgetLimited="
                + budgetLimited
                + ",elapsedMicros="
                + elapsedMicros
                + ",timeBudgetMicros="
                + config.timeBudgetMicros()
                + ",maxRegionsPerTick="
                + config.maxRegionsPerTick()
        );

        if (tick % SUMMARY_INTERVAL_TICKS == 0L) {
            com.alonie.xaero_worldgen.VwgXwmBridgeClient.LOGGER.info(
                "[VWG->XWM Bridge][Trace] phase=REPAIR_ROLLUP dim={} result=window processed={} candidates={} cooldownSkipped={} policySkipped={} budgetLimited={} elapsedMicros={}",
                world.getRegistryKey().getValue(),
                processed,
                candidates.size(),
                cooldownSkipped,
                policySkipped,
                budgetLimited,
                elapsedMicros
            );
        }
    }

    private static boolean shouldRequestCacheInvalidation(BridgeRegionAuditService.RepairCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        if ("cache_churn".equals(candidate.classification())) {
            return false;
        }
        return candidate.holeRatio() >= 0.5D
            || "no_recent_load".equals(candidate.classification())
            || "fallback_too_few_blocks".equals(candidate.classification())
            || "fallback_gap_candidate".equals(candidate.classification());
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.ROOT, "%.3f", ratio);
    }
}


