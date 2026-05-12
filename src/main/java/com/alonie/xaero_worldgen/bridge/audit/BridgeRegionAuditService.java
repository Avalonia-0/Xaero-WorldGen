package com.alonie.xaero_worldgen.bridge.audit;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import com.alonie.xaero_worldgen.VwgXwmBridgeClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeRegionAuditService {
    private static final ConcurrentHashMap<String, ConcurrentHashMap<Long, TouchState>> HOT_REGIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConcurrentHashMap<Long, GapState>> GAP_LEDGER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> LAST_SUMMARY_TICK = new ConcurrentHashMap<>();
    private static final long[] FULL_REGION_WORDS = buildFullRegionWords();
    private static final long SUMMARY_INTERVAL_TICKS = 200L;
    private static final int REPAIR_WINDOW_THRESHOLD = 3;
    private static final int VISIBILITY_GAP_WINDOW_THRESHOLD = 3;
    private static final long VISIBILITY_GAP_EVENT_COOLDOWN_TICKS = 80L;
    private static final long ZOOM_GAP_EVENT_COOLDOWN_TICKS = 80L;

    private BridgeRegionAuditService() {
    }

    public static void touchRegion(ServerWorld world, int regionX, int regionZ, String reason) {
        if (world == null) {
            return;
        }

        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        long packedRegion = BridgeDirtyRegionStore.packRegion(regionX, regionZ);
        long tick = XaeroLiveRegionQueue.currentTick();
        ConcurrentHashMap<Long, TouchState> runtimeMap = HOT_REGIONS.computeIfAbsent(runtimeKey, ignored -> new ConcurrentHashMap<>());
        TouchState state = runtimeMap.computeIfAbsent(packedRegion, ignored -> new TouchState());
        state.touch(tick, reason);
    }

    public static void tickServer(MinecraftServer server) {
        BridgeAuditConfig.Config config = BridgeAuditConfig.current();
        if (!config.enabled()) {
            return;
        }

        long tick = server.getTicks();
        if (tick <= 0L || tick % config.intervalTicks() != 0L) {
            return;
        }

        for (ServerWorld world : server.getWorlds()) {
            scanWorld(world, tick, config);
        }
    }

    public static ArrayList<RepairCandidate> snapshotRepairCandidates(
        ServerWorld world,
        int limit,
        long currentTick,
        long repairCooldownTicks
    ) {
        ArrayList<RepairCandidate> candidates = new ArrayList<>();
        if (world == null || limit <= 0) {
            return candidates;
        }

        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        ConcurrentHashMap<Long, GapState> runtimeLedger = GAP_LEDGER.get(runtimeKey);
        if (runtimeLedger == null || runtimeLedger.isEmpty()) {
            return candidates;
        }

        for (Map.Entry<Long, GapState> entry : runtimeLedger.entrySet()) {
            GapState gapState = entry.getValue();
            if (gapState == null || gapState.missingCount <= 0 || gapState.expectedCount <= 0) {
                continue;
            }
            if (gapState.sourcePolicy == BridgeSourcePolicy.SourcePolicy.NO_SOURCE) {
                continue;
            }
            if (gapState.consecutiveMissingWindows < REPAIR_WINDOW_THRESHOLD) {
                continue;
            }
            if (gapState.visibilityDesync || "visibility_desync".equals(gapState.classification)) {
                continue;
            }

            long cooldownRemaining = 0L;
            if (gapState.lastRepairAttemptTick >= 0L) {
                long elapsed = Math.max(0L, currentTick - gapState.lastRepairAttemptTick);
                cooldownRemaining = Math.max(0L, repairCooldownTicks - elapsed);
            }

            candidates.add(
                new RepairCandidate(
                    BridgeDirtyRegionStore.unpackRegionX(entry.getKey()),
                    BridgeDirtyRegionStore.unpackRegionZ(entry.getKey()),
                    gapState.sourcePolicy,
                    gapState.expectedCount,
                    gapState.loadedCount,
                    gapState.loadedSessionCount,
                    gapState.loadedVisibleCount,
                    gapState.loadedRecentCount,
                    gapState.missingCount,
                    gapState.holeRatio,
                    gapState.classification,
                    gapState.visibilityDesync,
                    gapState.near,
                    gapState.consecutiveMissingWindows,
                    gapState.lastRepairAttemptTick,
                    cooldownRemaining
                )
            );
        }

        candidates.sort(
            Comparator.comparing(RepairCandidate::near).reversed()
                .thenComparingInt(RepairCandidate::consecutiveMissingWindows).reversed()
                .thenComparingDouble(RepairCandidate::holeRatio).reversed()
                .thenComparingInt(RepairCandidate::missingCount).reversed()
                .thenComparingLong(RepairCandidate::lastRepairAttemptTick)
        );
        if (candidates.size() > limit) {
            return new ArrayList<>(candidates.subList(0, limit));
        }
        return candidates;
    }

    public static void markRepairAttempt(ServerWorld world, int regionX, int regionZ, long currentTick, String reason) {
        if (world == null) {
            return;
        }
        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        long packedRegion = BridgeDirtyRegionStore.packRegion(regionX, regionZ);
        ConcurrentHashMap<Long, GapState> runtimeLedger = GAP_LEDGER.computeIfAbsent(runtimeKey, ignored -> new ConcurrentHashMap<>());
        GapState state = runtimeLedger.computeIfAbsent(packedRegion, ignored -> new GapState());
        state.markRepairAttempt(currentTick, reason);
    }

    public static void clearRuntimeState() {
        HOT_REGIONS.clear();
        GAP_LEDGER.clear();
        LAST_SUMMARY_TICK.clear();
    }

    public static EscalationSignal snapshotEscalationSignal(ServerWorld world, int regionX, int regionZ, long tick) {
        if (world == null) {
            return EscalationSignal.empty();
        }

        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        long packedRegion = BridgeDirtyRegionStore.packRegion(regionX, regionZ);
        ConcurrentHashMap<Long, GapState> runtimeLedger = GAP_LEDGER.get(runtimeKey);
        GapState state = runtimeLedger == null ? null : runtimeLedger.get(packedRegion);
        if (state != null) {
            return state.toEscalationSignal(tick);
        }

        long currentTick = tick > 0L ? tick : XaeroLiveRegionQueue.currentTick();
        int nearRadius = Math.max(0, BridgeAuditConfig.current().nearRadius());
        BridgeMcaHeaderCache.McaCoverage coverage = BridgeMcaHeaderCache.getCoverage(
            world,
            regionX,
            regionZ,
            currentTick,
            BridgeAuditConfig.current().mcaHeaderRefreshTicks()
        );
        int expectedCount = Math.max(0, coverage.chunkCount());
        long[] expectedWords = coverage.words();
        BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(world, regionX, regionZ);
        long currentDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        BridgeXaeroLoadedChunkTracker.RegionCoverage sessionCoverage = BridgeXaeroLoadedChunkTracker.getSessionCoverage(world, regionX, regionZ);
        BridgeXaeroLoadedChunkTracker.RegionCoverage visibleCoverage = BridgeXaeroLoadedChunkTracker.getVisibleCoverage(
            world,
            regionX,
            regionZ,
            currentDirtyVersion
        );
        BridgeXaeroLoadedChunkTracker.RegionCoverage recentCoverage = BridgeXaeroLoadedChunkTracker.getRecentCoverage(
            world,
            regionX,
            regionZ,
            currentTick,
            BridgeAuditConfig.current().loadedTtlTicks()
        );
        int loadedSessionCount = countIntersection(expectedWords, sessionCoverage.words());
        int loadedVisibleCount = countIntersection(expectedWords, visibleCoverage.words());
        int loadedRecentCount = countIntersection(expectedWords, recentCoverage.words());
        int loadedEffectiveCount = pickLoadedCoverageCount(
            sourcePolicy,
            currentDirtyVersion,
            expectedCount,
            loadedSessionCount,
            loadedVisibleCount,
            loadedRecentCount
        );
        int missingCount = Math.max(0, expectedCount - loadedEffectiveCount);
        double holeRatio = expectedCount > 0 ? (double) missingCount / (double) expectedCount : 0.0D;
        boolean visibilityDesync = isVisibilityDesync(
            sourcePolicy,
            expectedCount,
            loadedSessionCount,
            loadedVisibleCount,
            loadedRecentCount
        );

        boolean vanillaSparse = expectedCount > 0 && expectedCount <= 512;
        boolean noRecentLoad = loadedEffectiveCount > 0 && loadedRecentCount <= 0;
        boolean leasePending = BridgeLoadLeaseTracker.isLeaseActive(world, regionX, regionZ, currentTick);
        boolean near = isNearAnyPlayer(regionX, regionZ, collectPlayerRegions(world), nearRadius);
        boolean fallbackGapCandidate = !visibilityDesync && missingCount > 0 && near && holeRatio >= 0.25D;

        return new EscalationSignal(
            near,
            noRecentLoad,
            vanillaSparse,
            fallbackGapCandidate,
            leasePending,
            0,
            expectedCount,
            loadedSessionCount,
            loadedVisibleCount,
            loadedEffectiveCount,
            missingCount,
            holeRatio,
            currentTick
        );
    }

    private static void scanWorld(ServerWorld world, long tick, BridgeAuditConfig.Config config) {
        long startedNanos = System.nanoTime();
        long timeBudgetNanos = Math.max(100_000L, config.timeBudgetMicros() * 1_000L);
        int maxRegions = Math.max(1, config.maxRegionsPerTick());
        int nearRadius = Math.max(0, config.nearRadius());

        BridgeXaeroLoadedChunkTracker.pruneExpired(tick, config.loadedTtlTicks());

        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        ConcurrentHashMap<Long, TouchState> hotRuntimeMap = HOT_REGIONS.computeIfAbsent(runtimeKey, ignored -> new ConcurrentHashMap<>());
        pruneHotRegions(hotRuntimeMap, tick, config.loadedTtlTicks());
        pruneGapLedger(runtimeKey, tick, config.loadedTtlTicks());

        HashSet<Long> candidates = new HashSet<>();
        ArrayList<int[]> playerRegions = collectPlayerRegions(world);
        collectNearRegions(candidates, playerRegions, nearRadius);
        for (BridgeDirtyRegionStore.DirtyRegion dirtyRegion : BridgeDirtyRegionStore.snapshotDirtyRegions(world)) {
            candidates.add(BridgeDirtyRegionStore.packRegion(dirtyRegion.regionX(), dirtyRegion.regionZ()));
        }
        for (BridgeSuspectRetryTracker.SuspectRegion suspectRegion : BridgeSuspectRetryTracker.snapshotActiveRegions(world)) {
            candidates.add(BridgeDirtyRegionStore.packRegion(suspectRegion.regionX(), suspectRegion.regionZ()));
        }
        candidates.addAll(hotRuntimeMap.keySet());

        ArrayList<Long> orderedCandidates = new ArrayList<>(candidates);
        orderedCandidates.sort(
            Comparator.comparingInt((Long packed) -> nearDistanceRank(packed, playerRegions))
                .thenComparingLong(packed -> {
                    TouchState touchState = hotRuntimeMap.get(packed);
                    return touchState == null ? Long.MAX_VALUE : touchState.lastTouchTick();
                })
                .thenComparingLong(Long::longValue)
        );

        int processed = 0;
        boolean budgetLimited = false;
        int considered = orderedCandidates.size();
        long nearExpected = 0L;
        long nearLoaded = 0L;
        long nearMissing = 0L;
        long farExpected = 0L;
        long farLoaded = 0L;
        long farMissing = 0L;
        HashMap<String, Integer> classifyCounts = new HashMap<>();
        ArrayList<RegionAuditResult> missingRegions = new ArrayList<>();

        for (Long packedRegion : orderedCandidates) {
            if (processed >= maxRegions || System.nanoTime() - startedNanos > timeBudgetNanos) {
                budgetLimited = true;
                break;
            }

            int regionX = BridgeDirtyRegionStore.unpackRegionX(packedRegion);
            int regionZ = BridgeDirtyRegionStore.unpackRegionZ(packedRegion);
            boolean near = isNearAnyPlayer(regionX, regionZ, playerRegions, nearRadius);
            TouchState touchState = hotRuntimeMap.get(packedRegion);
            RegionAuditResult result = auditRegion(world, regionX, regionZ, tick, config, touchState, near);
            if (result == null) {
                continue;
            }

            processed++;
            updateGapLedger(world, result, tick, config.intervalTicks());
            emitRegionEvent(world, tick, result);
            if (result.classification() != null && !result.classification().isBlank()) {
                classifyCounts.merge(result.classification(), 1, Integer::sum);
            }

            if (near) {
                nearExpected += result.expectedCount();
                nearLoaded += result.loadedCount();
                nearMissing += result.missingCount();
            } else {
                farExpected += result.expectedCount();
                farLoaded += result.loadedCount();
                farMissing += result.missingCount();
            }

            if (result.missingCount() > 0) {
                if (!result.visibilityDesync()) {
                    missingRegions.add(result);
                }
            }
        }

        missingRegions.sort(
            Comparator.comparingInt(RegionAuditResult::missingCount).reversed()
                .thenComparingDouble(RegionAuditResult::holeRatio).reversed()
        );
        int top = Math.min(config.topK(), missingRegions.size());
        for (int rank = 0; rank < top; rank++) {
            emitTopEvent(world, tick, rank + 1, missingRegions.get(rank));
        }

        long elapsedMicros = (System.nanoTime() - startedNanos) / 1_000L;
        BridgeAuditLogger.log(
            tick,
            "AUDITV2_SCAN",
            "window",
            world,
            null,
            null,
            "processed="
                + processed
                + ",considered="
                + considered
                + ",budgetLimited="
                + budgetLimited
                + ",elapsedMicros="
                + elapsedMicros
                + ",timeBudgetMicros="
                + config.timeBudgetMicros()
                + ",maxRegionsPerTick="
                + maxRegions
                + ",intervalTicks="
                + config.intervalTicks()
        );
        BridgeAuditLogger.log(
            tick,
            "AUDITV2_ROLLUP",
            "window",
            world,
            null,
            null,
            "nearExpected="
                + nearExpected
                + ",nearLoaded="
                + nearLoaded
                + ",nearMissing="
                + nearMissing
                + ",nearMissingRatio="
                + ratioText(nearMissing, nearExpected)
                + ",farExpected="
                + farExpected
                + ",farLoaded="
                + farLoaded
                + ",farMissing="
                + farMissing
                + ",farMissingRatio="
                + ratioText(farMissing, farExpected)
                + ",classify="
                + classifySummary(classifyCounts)
                + ",stalledTop="
                + stalledTopSummary(missingRegions, config.topK())
        );

        long lastSummaryTick = LAST_SUMMARY_TICK.getOrDefault(runtimeKey, -1L);
        if (lastSummaryTick < 0L || tick - lastSummaryTick >= SUMMARY_INTERVAL_TICKS) {
            LAST_SUMMARY_TICK.put(runtimeKey, tick);
            VwgXwmBridgeClient.LOGGER.info(
                "[VWG->XWM Bridge][Trace] phase=AUDIT_ROLLUP dim={} result=window processed={} considered={} near_missing_ratio={} far_missing_ratio={} classify={} stalled_top={}",
                world.getRegistryKey().getValue(),
                processed,
                considered,
                ratioText(nearMissing, nearExpected),
                ratioText(farMissing, farExpected),
                classifySummary(classifyCounts),
                stalledTopSummary(missingRegions, config.topK())
            );
        }
    }

    private static RegionAuditResult auditRegion(
        ServerWorld world,
        int regionX,
        int regionZ,
        long tick,
        BridgeAuditConfig.Config config,
        TouchState touchState,
        boolean near
    ) {
        BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(world, regionX, regionZ);
        if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.NO_SOURCE) {
            return null;
        }

        long[] expectedWords;
        int expectedCount;
        String classification = "";

        if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.BRIDGE_ONLY) {
            boolean released = BridgeRegionReleaseManager.hasCurrentCommittedDirtyVersion(world, regionX, regionZ);
            if (!released) {
                classification = "bridge_unreleased_or_stale";
                expectedWords = new long[FULL_REGION_WORDS.length];
                expectedCount = 0;
            } else {
                expectedWords = FULL_REGION_WORDS;
                expectedCount = 1024;
            }
        } else {
            BridgeMcaHeaderCache.McaCoverage coverage = BridgeMcaHeaderCache.getCoverage(
                world,
                regionX,
                regionZ,
                tick,
                config.mcaHeaderRefreshTicks()
            );
            expectedWords = coverage.words();
            expectedCount = Math.max(0, coverage.chunkCount());
            if (expectedCount > 0 && expectedCount <= 512) {
                classification = "vanilla_sparse";
            }
        }

        long currentDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        BridgeXaeroLoadedChunkTracker.RegionCoverage sessionCoverage = BridgeXaeroLoadedChunkTracker.getSessionCoverage(
            world,
            regionX,
            regionZ
        );
        BridgeXaeroLoadedChunkTracker.RegionCoverage visibleCoverage = BridgeXaeroLoadedChunkTracker.getVisibleCoverage(
            world,
            regionX,
            regionZ,
            currentDirtyVersion
        );
        BridgeXaeroLoadedChunkTracker.RegionCoverage recentCoverage = BridgeXaeroLoadedChunkTracker.getRecentCoverage(
            world,
            regionX,
            regionZ,
            tick,
            config.loadedTtlTicks()
        );
        int loadedSessionCount = countIntersection(expectedWords, sessionCoverage.words());
        int loadedVisibleCount = countIntersection(expectedWords, visibleCoverage.words());
        int loadedRecentCount = countIntersection(expectedWords, recentCoverage.words());
        int loadedCount = pickLoadedCoverageCount(
            sourcePolicy,
            currentDirtyVersion,
            expectedCount,
            loadedSessionCount,
            loadedVisibleCount,
            loadedRecentCount
        );
        int missingCount = Math.max(0, expectedCount - loadedCount);
        double holeRatio = expectedCount > 0 ? (double) missingCount / (double) expectedCount : 0.0D;
        boolean visibilityDesync = isVisibilityDesync(
            sourcePolicy,
            expectedCount,
            loadedSessionCount,
            loadedVisibleCount,
            loadedRecentCount
        );

        if (missingCount > 0) {
            BridgeLoadLeaseTracker.RequestStatus leaseStatus = BridgeLoadLeaseTracker.getLeaseStatus(world, regionX, regionZ, tick);
            if (visibilityDesync) {
                classification = "visibility_desync";
            } else if (leaseStatus != null && leaseStatus.active()) {
                classification = "lease_pending";
            } else if (BridgeFallbackEscalationPolicy.isFallbackGapCandidate(world, regionX, regionZ)) {
                classification = "fallback_gap_candidate";
            } else if (BridgeFallbackMissTracker.hasRecentTooFewBlocks(world, regionX, regionZ)) {
                classification = "fallback_too_few_blocks";
            } else if (isCacheChurn(touchState, tick, config.intervalTicks())) {
                classification = "cache_churn";
            } else if (loadedCount > 0 && loadedRecentCount <= 0) {
                classification = "no_recent_load";
            } else if (isNoRecentLoad(touchState, tick, config.loadedTtlTicks())) {
                classification = "no_recent_load";
            } else if (classification.isBlank()) {
                classification = "no_recent_load";
            }
        }

        return new RegionAuditResult(
            regionX,
            regionZ,
            sourcePolicy,
            expectedCount,
            loadedCount,
            loadedSessionCount,
            loadedVisibleCount,
            loadedRecentCount,
            missingCount,
            holeRatio,
            near,
            classification,
            visibilityDesync
        );
    }

    private static boolean isNoRecentLoad(TouchState touchState, long tick, int loadedTtlTicks) {
        if (touchState == null) {
            return true;
        }
        long threshold = Math.max(80L, loadedTtlTicks / 2L);
        long lastLoad = touchState.lastLoadOrBuildTick();
        return lastLoad <= 0L || tick - lastLoad > threshold;
    }

    private static boolean isCacheChurn(TouchState touchState, long tick, int intervalTicks) {
        if (touchState == null) {
            return false;
        }
        long cacheAge = tick - touchState.lastCacheTick();
        return touchState.cacheTouchWindowCount() >= 3 && cacheAge >= 0L && cacheAge <= Math.max(80L, intervalTicks * 4L);
    }

    private static void emitRegionEvent(ServerWorld world, long tick, RegionAuditResult result) {
        BridgeAuditLogger.log(
            tick,
            "AUDITV2_REGION",
            result.missingCount() > 0 ? "missing" : "ok",
            world,
            result.regionX(),
            result.regionZ(),
            "sourcePolicy="
                + result.sourcePolicy().name().toLowerCase(Locale.ROOT)
                + ",expected="
                + result.expectedCount()
                + ",loaded="
                + result.loadedCount()
                + ",loadedSession="
                + result.loadedSessionCount()
                + ",loadedVisible="
                + result.loadedVisibleCount()
                + ",loadedRecent="
                + result.loadedRecentCount()
                + ",missing="
                + result.missingCount()
                + ",holeRatio="
                + String.format(Locale.ROOT, "%.3f", result.holeRatio())
                + ",near="
                + result.near()
                + ",classify="
                + result.classification()
                + ",desync="
                + result.visibilityDesync()
        );
        if (result.visibilityDesync()) {
            BridgeAuditLogger.log(
                tick,
                "AUDIT_CLASSIFY",
                "visibility_desync",
                world,
                result.regionX(),
                result.regionZ(),
                "sourcePolicy="
                    + result.sourcePolicy().name().toLowerCase(Locale.ROOT)
                    + ",expected="
                    + result.expectedCount()
                    + ",loadedSession="
                    + result.loadedSessionCount()
                    + ",loadedVisible="
                    + result.loadedVisibleCount()
                    + ",loadedRecent="
                    + result.loadedRecentCount()
                    + ",holeRatio="
                    + String.format(Locale.ROOT, "%.3f", result.holeRatio())
            );
        }
    }

    private static void emitTopEvent(ServerWorld world, long tick, int rank, RegionAuditResult result) {
        BridgeAuditLogger.log(
            tick,
            "AUDITV2_TOP",
            "missing",
            world,
            result.regionX(),
            result.regionZ(),
            "rank="
                + rank
                + ",sourcePolicy="
                + result.sourcePolicy().name().toLowerCase(Locale.ROOT)
                + ",expected="
                + result.expectedCount()
                + ",loaded="
                + result.loadedCount()
                + ",loadedSession="
                + result.loadedSessionCount()
                + ",loadedVisible="
                + result.loadedVisibleCount()
                + ",loadedRecent="
                + result.loadedRecentCount()
                + ",missing="
                + result.missingCount()
                + ",holeRatio="
                + String.format(Locale.ROOT, "%.3f", result.holeRatio())
                + ",classify="
                + result.classification()
        );
    }

    private static void collectNearRegions(Set<Long> sink, ArrayList<int[]> playerRegions, int nearRadius) {
        for (int[] playerRegion : playerRegions) {
            int baseX = playerRegion[0];
            int baseZ = playerRegion[1];
            for (int dx = -nearRadius; dx <= nearRadius; dx++) {
                for (int dz = -nearRadius; dz <= nearRadius; dz++) {
                    sink.add(BridgeDirtyRegionStore.packRegion(baseX + dx, baseZ + dz));
                }
            }
        }
    }

    private static ArrayList<int[]> collectPlayerRegions(ServerWorld world) {
        ArrayList<int[]> players = new ArrayList<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            ChunkPos chunkPos = player.getChunkPos();
            players.add(new int[] {chunkPos.x >> 5, chunkPos.z >> 5});
        }
        return players;
    }

    private static boolean isNearAnyPlayer(int regionX, int regionZ, ArrayList<int[]> players, int nearRadius) {
        for (int[] playerRegion : players) {
            if (Math.abs(regionX - playerRegion[0]) <= nearRadius && Math.abs(regionZ - playerRegion[1]) <= nearRadius) {
                return true;
            }
        }
        return false;
    }

    private static int nearDistanceRank(long packedRegion, ArrayList<int[]> players) {
        if (players.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int regionX = BridgeDirtyRegionStore.unpackRegionX(packedRegion);
        int regionZ = BridgeDirtyRegionStore.unpackRegionZ(packedRegion);
        int best = Integer.MAX_VALUE;
        for (int[] playerRegion : players) {
            int dx = Math.abs(regionX - playerRegion[0]);
            int dz = Math.abs(regionZ - playerRegion[1]);
            int dist = Math.max(dx, dz);
            if (dist < best) {
                best = dist;
            }
        }
        return best;
    }

    private static void pruneHotRegions(ConcurrentHashMap<Long, TouchState> runtimeMap, long tick, int ttlTicks) {
        runtimeMap.entrySet().removeIf(entry -> {
            TouchState touchState = entry.getValue();
            return touchState == null || tick - touchState.lastTouchTick() > Math.max(200L, ttlTicks);
        });
    }

    private static void pruneGapLedger(String runtimeKey, long tick, int ttlTicks) {
        ConcurrentHashMap<Long, GapState> runtimeLedger = GAP_LEDGER.get(runtimeKey);
        if (runtimeLedger == null) {
            return;
        }
        long staleThreshold = Math.max(400L, ttlTicks * 3L);
        runtimeLedger.entrySet().removeIf(entry -> {
            GapState state = entry.getValue();
            return state == null || tick - state.lastSeenTick > staleThreshold;
        });
        if (runtimeLedger.isEmpty()) {
            GAP_LEDGER.remove(runtimeKey, runtimeLedger);
        }
    }

    private static void updateGapLedger(ServerWorld world, RegionAuditResult result, long tick, int intervalTicks) {
        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        ConcurrentHashMap<Long, GapState> runtimeLedger = GAP_LEDGER.computeIfAbsent(runtimeKey, ignored -> new ConcurrentHashMap<>());
        long packedRegion = BridgeDirtyRegionStore.packRegion(result.regionX(), result.regionZ());
        GapState state = runtimeLedger.computeIfAbsent(packedRegion, ignored -> new GapState());
        state.observe(result, tick, intervalTicks);
        state.emitDerivedSignals(world, result.regionX(), result.regionZ(), tick);
    }

    private static int countIntersection(long[] expectedWords, long[] loadedWords) {
        if (expectedWords == null || loadedWords == null) {
            return 0;
        }
        int length = Math.min(expectedWords.length, loadedWords.length);
        int count = 0;
        for (int i = 0; i < length; i++) {
            count += Long.bitCount(expectedWords[i] & loadedWords[i]);
        }
        return count;
    }

    private static int pickLoadedCoverageCount(
        BridgeSourcePolicy.SourcePolicy sourcePolicy,
        long currentDirtyVersion,
        int expectedCount,
        int loadedSessionCount,
        int loadedVisibleCount,
        int loadedRecentCount
    ) {
        if (expectedCount <= 0) {
            return 0;
        }

        // For dirty vanilla-only regions, visible coverage (version-locked) better
        // represents the currently renderable map than historical session hits.
        if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY && currentDirtyVersion > 0L) {
            if (isVisibilityDesync(sourcePolicy, expectedCount, loadedSessionCount, loadedVisibleCount, loadedRecentCount)) {
                return Math.max(0, Math.min(expectedCount, loadedSessionCount));
            }
            return Math.max(0, Math.min(expectedCount, loadedVisibleCount));
        }

        return Math.max(0, Math.min(expectedCount, loadedSessionCount));
    }

    private static boolean isVisibilityDesync(
        BridgeSourcePolicy.SourcePolicy sourcePolicy,
        int expectedCount,
        int loadedSessionCount,
        int loadedVisibleCount,
        int loadedRecentCount
    ) {
        return sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY
            && expectedCount > 0
            && loadedVisibleCount <= 0
            && loadedSessionCount * 100 >= expectedCount * 95
            && loadedRecentCount * 100 >= expectedCount * 95;
    }

    private static long[] buildFullRegionWords() {
        long[] words = new long[16];
        Arrays.fill(words, -1L);
        return words;
    }

    private static String ratioText(long missing, long expected) {
        if (expected <= 0L) {
            return "0.000";
        }
        double ratio = (double) missing / (double) expected;
        return String.format(Locale.ROOT, "%.3f", ratio);
    }

    private static String classifySummary(HashMap<String, Integer> classifyCounts) {
        if (classifyCounts.isEmpty()) {
            return "none";
        }
        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(classifyCounts.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        ArrayList<String> parts = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> entry : entries) {
            parts.add(entry.getKey() + ":" + entry.getValue());
        }
        return String.join("|", parts);
    }

    private static String stalledTopSummary(ArrayList<RegionAuditResult> missingRegions, int topK) {
        if (missingRegions.isEmpty()) {
            return "none";
        }
        int limit = Math.min(Math.max(1, topK), missingRegions.size());
        ArrayList<String> parts = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            RegionAuditResult result = missingRegions.get(i);
            parts.add(
                result.regionX()
                    + ","
                    + result.regionZ()
                    + "("
                    + result.missingCount()
                    + "/"
                    + result.expectedCount()
                    + ","
                    + result.classification()
                    + ")"
            );
        }
        return String.join("|", parts);
    }

    public record RepairCandidate(
        int regionX,
        int regionZ,
        BridgeSourcePolicy.SourcePolicy sourcePolicy,
        int expectedCount,
        int loadedCount,
        int loadedSessionCount,
        int loadedVisibleCount,
        int loadedRecentCount,
        int missingCount,
        double holeRatio,
        String classification,
        boolean desyncFlag,
        boolean near,
        int consecutiveMissingWindows,
        long lastRepairAttemptTick,
        long cooldownRemainingTicks
    ) {
    }

    public record EscalationSignal(
        boolean nearPlayer,
        boolean noRecentLoad,
        boolean vanillaSparse,
        boolean fallbackGapCandidate,
        boolean leasePending,
        int consecutiveMissingWindows,
        int expectedCount,
        int loadedSessionCount,
        int loadedVisibleCount,
        int loadedEffectiveCount,
        int missingCount,
        double holeRatio,
        long observedTick
    ) {
        public static EscalationSignal empty() {
            return new EscalationSignal(false, false, false, false, false, 0, 0, 0, 0, 0, 0, 0.0D, -1L);
        }
    }

    private record RegionAuditResult(
        int regionX,
        int regionZ,
        BridgeSourcePolicy.SourcePolicy sourcePolicy,
        int expectedCount,
        int loadedCount,
        int loadedSessionCount,
        int loadedVisibleCount,
        int loadedRecentCount,
        int missingCount,
        double holeRatio,
        boolean near,
        String classification,
        boolean visibilityDesync
    ) {
    }

    private static final class GapState {
        private long lastSeenTick = -1L;
        private BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.SourcePolicy.NO_SOURCE;
        private int expectedCount;
        private int loadedCount;
        private int loadedSessionCount;
        private int loadedVisibleCount;
        private int loadedRecentCount;
        private int missingCount;
        private double holeRatio;
        private String classification = "";
        private boolean visibilityDesync;
        private boolean near;
        private int consecutiveMissingWindows;
        private int consecutiveFallbackTooFewWindows;
        private int consecutiveVisibilityGapWindows;
        private long lastRepairAttemptTick = -1L;
        private String lastRepairReason = "";
        private long lastVisibilityGapEventTick = -1L;
        private long lastZoomGapEventTick = -1L;
        private int previousLoadedSessionCount = -1;
        private int previousLoadedVisibleCount = -1;
        private int previousMissingCount = -1;
        private boolean zoomGapCandidate;

        private synchronized void observe(RegionAuditResult result, long tick, int intervalTicks) {
            boolean contiguous = lastSeenTick >= 0L && tick - lastSeenTick <= Math.max(2L, intervalTicks * 2L);
            int prevSession = this.loadedSessionCount;
            int prevVisible = this.loadedVisibleCount;
            int prevMissing = this.missingCount;
            this.lastSeenTick = tick;
            this.sourcePolicy = result.sourcePolicy();
            this.expectedCount = result.expectedCount();
            this.loadedCount = result.loadedCount();
            this.loadedSessionCount = result.loadedSessionCount();
            this.loadedVisibleCount = result.loadedVisibleCount();
            this.loadedRecentCount = result.loadedRecentCount();
            this.missingCount = result.missingCount();
            this.holeRatio = result.holeRatio();
            this.classification = result.classification() == null ? "" : result.classification();
            this.visibilityDesync = result.visibilityDesync();
            this.near = result.near();

            if (this.missingCount > 0) {
                this.consecutiveMissingWindows = contiguous ? this.consecutiveMissingWindows + 1 : 1;
                if ("fallback_too_few_blocks".equals(this.classification)) {
                    this.consecutiveFallbackTooFewWindows = contiguous ? this.consecutiveFallbackTooFewWindows + 1 : 1;
                } else {
                    this.consecutiveFallbackTooFewWindows = 0;
                }
            } else {
                this.consecutiveMissingWindows = 0;
                this.consecutiveFallbackTooFewWindows = 0;
            }

            int visibilityGapCount = Math.max(0, this.loadedSessionCount - this.loadedVisibleCount);
            boolean visibilityGapActive = this.expectedCount > 0
                && visibilityGapCount >= Math.max(8, this.expectedCount / 10);
            if (visibilityGapActive) {
                this.consecutiveVisibilityGapWindows = contiguous ? this.consecutiveVisibilityGapWindows + 1 : 1;
            } else {
                this.consecutiveVisibilityGapWindows = 0;
            }

            boolean sessionStable = prevSession > 0
                && Math.abs(this.loadedSessionCount - prevSession) <= Math.max(8, prevSession / 10);
            boolean visibleDropped = prevVisible > 0 && this.loadedVisibleCount <= prevVisible / 2;
            boolean missingIncreased = prevMissing >= 0 && this.missingCount >= prevMissing + Math.max(8, this.expectedCount / 16);
            this.zoomGapCandidate = this.near && sessionStable && visibleDropped && missingIncreased;
            this.previousLoadedSessionCount = prevSession;
            this.previousLoadedVisibleCount = prevVisible;
            this.previousMissingCount = prevMissing;
        }

        private synchronized void markRepairAttempt(long tick, String reason) {
            this.lastRepairAttemptTick = tick;
            this.lastRepairReason = reason == null ? "" : reason;
        }

        private synchronized EscalationSignal toEscalationSignal(long tick) {
            long currentTick = tick > 0L ? tick : XaeroLiveRegionQueue.currentTick();
            boolean noRecentLoad = "no_recent_load".equals(this.classification)
                || (this.loadedCount > 0 && this.loadedRecentCount <= 0);
            boolean vanillaSparse = "vanilla_sparse".equals(this.classification)
                || (this.expectedCount > 0 && this.expectedCount <= 512);
            boolean leasePending = "lease_pending".equals(this.classification);
            boolean fallbackCandidate = !this.visibilityDesync
                && this.missingCount > 0
                && this.consecutiveMissingWindows >= REPAIR_WINDOW_THRESHOLD;
            return new EscalationSignal(
                this.near,
                noRecentLoad,
                vanillaSparse,
                fallbackCandidate,
                leasePending,
                this.consecutiveMissingWindows,
                this.expectedCount,
                this.loadedSessionCount,
                this.loadedVisibleCount,
                this.loadedCount,
                this.missingCount,
                this.holeRatio,
                currentTick
            );
        }

        private synchronized void emitDerivedSignals(ServerWorld world, int regionX, int regionZ, long tick) {
            if (world == null) {
                return;
            }

            if (consecutiveVisibilityGapWindows >= VISIBILITY_GAP_WINDOW_THRESHOLD
                && (lastVisibilityGapEventTick < 0L || tick - lastVisibilityGapEventTick >= VISIBILITY_GAP_EVENT_COOLDOWN_TICKS)) {
                int gapCount = Math.max(0, loadedSessionCount - loadedVisibleCount);
                BridgeAuditLogger.log(
                    tick,
                    "VISIBILITY_GAP",
                    "sustained",
                    world,
                    regionX,
                    regionZ,
                    "sourcePolicy="
                        + sourcePolicy.name().toLowerCase(Locale.ROOT)
                        + ",windows="
                        + consecutiveVisibilityGapWindows
                        + ",loadedSession="
                        + loadedSessionCount
                        + ",loadedVisible="
                        + loadedVisibleCount
                        + ",gapCount="
                        + gapCount
                        + ",holeRatio="
                        + String.format(Locale.ROOT, "%.3f", holeRatio)
                        + ",classify="
                        + classification
                );
                VwgXwmBridgeClient.LOGGER.info(
                    "[VWG->XWM Bridge][Trace] phase=VISIBILITY_GAP dim={} regionX={} regionZ={} windows={} loaded_session={} loaded_visible={} gap_count={} hole_ratio={} classify={}",
                    world.getRegistryKey().getValue(),
                    regionX,
                    regionZ,
                    consecutiveVisibilityGapWindows,
                    loadedSessionCount,
                    loadedVisibleCount,
                    gapCount,
                    String.format(Locale.ROOT, "%.3f", holeRatio),
                    classification
                );
                lastVisibilityGapEventTick = tick;
            }

            if (zoomGapCandidate
                && (lastZoomGapEventTick < 0L || tick - lastZoomGapEventTick >= ZOOM_GAP_EVENT_COOLDOWN_TICKS)) {
                BridgeAuditLogger.log(
                    tick,
                    "ZOOM_GAP_CORRELATION",
                    "suspected",
                    world,
                    regionX,
                    regionZ,
                    "sourcePolicy="
                        + sourcePolicy.name().toLowerCase(Locale.ROOT)
                        + ",loadedSession="
                        + loadedSessionCount
                        + ",loadedVisible="
                        + loadedVisibleCount
                        + ",previousLoadedVisible="
                        + previousLoadedVisibleCount
                        + ",previousLoadedSession="
                        + previousLoadedSessionCount
                        + ",missing="
                        + missingCount
                        + ",previousMissing="
                        + previousMissingCount
                        + ",classify="
                        + classification
                );
                VwgXwmBridgeClient.LOGGER.info(
                    "[VWG->XWM Bridge][Trace] phase=ZOOM_GAP_CORRELATION dim={} regionX={} regionZ={} loaded_session={} loaded_visible={} prev_visible={} prev_session={} missing={} prev_missing={} classify={}",
                    world.getRegistryKey().getValue(),
                    regionX,
                    regionZ,
                    loadedSessionCount,
                    loadedVisibleCount,
                    previousLoadedVisibleCount,
                    previousLoadedSessionCount,
                    missingCount,
                    previousMissingCount,
                    classification
                );
                lastZoomGapEventTick = tick;
                zoomGapCandidate = false;
            }
        }
    }

    private static final class TouchState {
        private long lastTouchTick;
        private long lastLoadTick;
        private long lastBuildTick;
        private long lastCacheTick;
        private int cacheTouchWindowCount;
        private long cacheWindowStartTick;

        private synchronized void touch(long tick, String reason) {
            lastTouchTick = Math.max(lastTouchTick, tick);
            String normalized = reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("load")) {
                lastLoadTick = tick;
            } else if (normalized.startsWith("build")) {
                lastBuildTick = tick;
            } else if (normalized.startsWith("cache") || normalized.startsWith("clear")) {
                lastCacheTick = tick;
                if (cacheWindowStartTick <= 0L || tick - cacheWindowStartTick > 200L) {
                    cacheWindowStartTick = tick;
                    cacheTouchWindowCount = 1;
                } else {
                    cacheTouchWindowCount++;
                }
            } else if (normalized.startsWith("request")) {
                lastLoadTick = Math.max(lastLoadTick, tick - 1);
            }
        }

        private synchronized long lastTouchTick() {
            return lastTouchTick;
        }

        private synchronized long lastLoadOrBuildTick() {
            return Math.max(lastLoadTick, lastBuildTick);
        }

        private synchronized long lastCacheTick() {
            return lastCacheTick;
        }

        private synchronized int cacheTouchWindowCount() {
            return cacheTouchWindowCount;
        }
    }
}


