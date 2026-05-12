package com.alonie.xaero_worldgen.bridge.integration.xaero;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import com.alonie.xaero_worldgen.VwgXwmBridgeClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import xaero.map.MapProcessor;
import xaero.map.file.MapSaveLoad;
import xaero.map.file.RegionDetection;
import xaero.map.region.MapRegion;
import xaero.map.world.MapDimension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class XaeroBridgeSupport {
    private static final ThreadLocal<String> CLEAR_DIRTY_REASON = new ThreadLocal<>();

    private enum PostCacheOrigin {
        PRIMARY_CACHE_WRITE("primary"),
        AUXILIARY_POSTCACHE("aux_ignored");

        private final String id;

        PostCacheOrigin(String id) {
            this.id = id;
        }
    }

    private XaeroBridgeSupport() {
    }

    public static ServerWorld resolveWorld(MapRegion region) {
        if (region == null || region.isNormalMapData()) {
            return null;
        }

        MapDimension dimension = region.getDim();
        if (dimension == null || !dimension.isUsingWorldSave()) {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return null;
        }

        IntegratedServer server = client.getServer();
        if (server == null) {
            return null;
        }

        return server.getWorld(dimension.getDimId());
    }

    public static boolean hasRealRegionFile(MapRegion region) {
        ServerWorld world = resolveWorld(region);
        return world != null && hasRealRegionFile(world, region.getRegionX(), region.getRegionZ());
    }

    public static boolean hasRealRegionFile(ServerWorld world, int regionX, int regionZ) {
        return world != null && Files.isRegularFile(BridgePaths.getRegionFile(world, regionX, regionZ));
    }

    public static boolean shouldExposeBridgeSource(MapRegion region) {
        ServerWorld world = resolveWorld(region);
        if (world == null) {
            return false;
        }

        return BridgeSourcePolicy.classify(world, region.getRegionX(), region.getRegionZ())
            == BridgeSourcePolicy.SourcePolicy.BRIDGE_ONLY;
    }

    public static boolean ensureReleasedBridgeSource(MapRegion region) {
        ServerWorld world = resolveWorld(region);
        if (world == null) {
            return false;
        }

        int regionX = region.getRegionX();
        int regionZ = region.getRegionZ();
        BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(world, regionX, regionZ);
        if (sourcePolicy != BridgeSourcePolicy.SourcePolicy.BRIDGE_ONLY) {
            if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY) {
                BridgeSourcePolicy.recordMixedSourceGuardHit(world, regionX, regionZ, "ensure_released_on_vanilla_only");
            }
            return false;
        }

        if (!BridgeRegionReleaseManager.isReleased(world, regionX, regionZ)) {
            return false;
        }

        VoxyRegionFileStub.ensurePresent(world, regionX, regionZ);
        return true;
    }

    public static boolean ensureBridgeDetection(
        MapProcessor mapProcessor,
        MapDimension mapDimension,
        ServerWorld world,
        String worldId,
        String dimId,
        String mwId,
        int regionX,
        int regionZ
    ) {
        if (mapProcessor == null || mapDimension == null || world == null) {
            return false;
        }
        if (worldId == null || dimId == null || mwId == null) {
            return false;
        }

        BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(world, regionX, regionZ);
        if (sourcePolicy != BridgeSourcePolicy.SourcePolicy.BRIDGE_ONLY) {
            if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY) {
                BridgeSourcePolicy.recordMixedSourceGuardHit(world, regionX, regionZ, "ensure_bridge_detection_on_vanilla_only");
            }
            return false;
        }

        if (mapDimension.getWorldSaveRegionDetection(regionX, regionZ) != null) {
            return true;
        }

        VoxyRegionFileStub.ensurePresent(world, regionX, regionZ);
        RegionDetection detection = new RegionDetection(
            worldId,
            dimId,
            mwId,
            regionX,
            regionZ,
            BridgePaths.getBridgeStubRegionFile(world, regionX, regionZ).toFile(),
            mapProcessor.getGlobalVersion(),
            true
        );
        mapDimension.addWorldSaveRegionDetection(detection);
        return true;
    }

    public static boolean ensureVanillaDetection(
        MapProcessor mapProcessor,
        MapDimension mapDimension,
        ServerWorld world,
        String worldId,
        String dimId,
        String mwId,
        int regionX,
        int regionZ
    ) {
        if (mapProcessor == null || mapDimension == null || world == null) {
            return false;
        }
        if (worldId == null || dimId == null || mwId == null) {
            return false;
        }
        if (!hasRealRegionFile(world, regionX, regionZ)) {
            return false;
        }

        if (mapDimension.getWorldSaveRegionDetection(regionX, regionZ) != null) {
            return true;
        }

        RegionDetection detection = new RegionDetection(
            worldId,
            dimId,
            mwId,
            regionX,
            regionZ,
            BridgePaths.getRegionFile(world, regionX, regionZ).toFile(),
            mapProcessor.getGlobalVersion(),
            true
        );
        mapDimension.addWorldSaveRegionDetection(detection);
        return true;
    }

    public static boolean isBridgeRegionCandidate(MapRegion region) {
        ServerWorld world = resolveWorld(region);
        if (world == null) {
            return false;
        }

        int regionX = region.getRegionX();
        int regionZ = region.getRegionZ();
        if (BridgeSourcePolicy.classify(world, regionX, regionZ) == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY) {
            return false;
        }

        return BridgeDirtyRegionStore.isDirty(world, regionX, regionZ)
            || VoxyGeneratedRegionIndex.mayHaveRegion(world, regionX, regionZ)
            || BridgeRegionReleaseManager.isReleased(world, regionX, regionZ);
    }

    public static boolean shouldBypassCache(MapRegion region) {
        ServerWorld world = resolveWorld(region);
        if (world == null) {
            return false;
        }

        int regionX = region.getRegionX();
        int regionZ = region.getRegionZ();
        if (BridgeSourcePolicy.classify(world, regionX, regionZ) == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY) {
            return false;
        }

        long dirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        if (dirtyVersion <= 0L) {
            return false;
        }

        if (BridgeSuspectRetryTracker.hasActiveSuspect(world, regionX, regionZ, dirtyVersion)) {
            return true;
        }

        return BridgeRegionReleaseManager.hasCurrentCommittedDirtyVersion(world, regionX, regionZ);
    }

    public static void invalidateLegacyMixedCacheIfNeeded(MapRegion region, MapProcessor mapProcessor) {
        ServerWorld world = resolveWorld(region);
        if (world == null) {
            return;
        }

        int regionX = region.getRegionX();
        int regionZ = region.getRegionZ();
        if (BridgeSourcePolicy.classify(world, regionX, regionZ) != BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY) {
            return;
        }
        if (!BridgeDirtyRegionStore.isDirty(world, regionX, regionZ)) {
            return;
        }

        String legacyCacheKey = cacheRegionKey(world, region) + "|legacy_vanilla";
        if (!BridgeCacheInvalidationTracker.shouldInvalidate(legacyCacheKey, 1L)) {
            return;
        }

        traceRegionEvent(
            "CACHE_INVALIDATE_REASON",
            world,
            regionX,
            regionZ,
            "result=legacy_mixed_dirty,dirtyVersion=1"
        );
        if (deleteCacheFile(region, mapProcessor, null)) {
            BridgeCacheInvalidationTracker.markInvalidated(legacyCacheKey, 1L);
            traceRegionEvent(
                "CACHE_INVALIDATE_REASON",
                world,
                regionX,
                regionZ,
                "result=legacy_mixed_deleted,dirtyVersion=1"
            );
        } else {
            BridgeCacheInvalidationTracker.allowReinvalidate(legacyCacheKey, 1L);
            traceRegionEvent(
                "CACHE_INVALIDATE_REASON",
                world,
                regionX,
                regionZ,
                "result=legacy_mixed_delete_failed,dirtyVersion=1"
            );
        }
    }

    public static void invalidateDirtyCacheIfNeeded(MapRegion region, MapProcessor mapProcessor) {
        ServerWorld world = resolveWorld(region);
        if (world == null || mapProcessor == null) {
            return;
        }

        int regionX = region.getRegionX();
        int regionZ = region.getRegionZ();
        long dirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        if (dirtyVersion <= 0L) {
            return;
        }
        if (!shouldBypassCache(region)) {
            traceRegionEvent(
                "CACHE_INVALIDATE_REASON",
                world,
                regionX,
                regionZ,
                "result=skip_not_bypassed,dirtyVersion=" + dirtyVersion
            );
            return;
        }

        String cacheRegionKey = cacheRegionKey(world, region);
        if (!BridgeCacheInvalidationTracker.shouldInvalidate(cacheRegionKey, dirtyVersion)) {
            traceRegionEvent(
                "CACHE_INVALIDATE_REASON",
                world,
                regionX,
                regionZ,
                "result=skip_already_invalidated,dirtyVersion=" + dirtyVersion
            );
            return;
        }

        traceRegionEvent(
            "CACHE_INVALIDATE_REASON",
            world,
            regionX,
            regionZ,
            "result=dirty_bypass,dirtyVersion=" + dirtyVersion
        );
        boolean deleted = deleteCacheFile(region, mapProcessor, null);
        if (deleted) {
            BridgeCacheInvalidationTracker.markInvalidated(cacheRegionKey, dirtyVersion);
            traceRegionEvent(
                "CACHE_INVALIDATE_REASON",
                world,
                regionX,
                regionZ,
                "result=dirty_deleted,dirtyVersion=" + dirtyVersion
            );
        } else {
            BridgeCacheInvalidationTracker.allowReinvalidate(cacheRegionKey, dirtyVersion);
            traceRegionEvent(
                "CACHE_INVALIDATE_REASON",
                world,
                regionX,
                regionZ,
                "result=dirty_delete_failed,dirtyVersion=" + dirtyVersion
            );
        }
    }

    public static void invalidateAuditRepairCacheIfNeeded(MapRegion region, MapProcessor mapProcessor) {
        ServerWorld world = resolveWorld(region);
        if (world == null || mapProcessor == null) {
            return;
        }

        int regionX = region.getRegionX();
        int regionZ = region.getRegionZ();
        long dirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        if (!BridgeAuditRepairCacheInvalidation.consumeIfRequested(world, regionX, regionZ, dirtyVersion)) {
            return;
        }

        traceRegionEvent(
            "CACHE_INVALIDATE_REASON",
            world,
            regionX,
            regionZ,
            "result=audit_repair_requested,dirtyVersion=" + dirtyVersion
        );
        boolean deleted = deleteCacheFile(region, mapProcessor, null);
        traceRegionEvent(
            "CACHE_INVALIDATE_REASON",
            world,
            regionX,
            regionZ,
            "result=" + (deleted ? "audit_repair_deleted" : "audit_repair_delete_failed") + ",dirtyVersion=" + dirtyVersion
        );
    }

    public static BridgeDirtyRegionStore.DirtyClearResult clearDirtyAfterCacheWrite(MapRegion region, boolean success) {
        return clearDirtyAfterCacheWrite(region, null, success);
    }

    public static BridgeDirtyRegionStore.DirtyClearResult clearDirtyAfterCacheWrite(MapRegion region, File file, boolean success) {
        CLEAR_DIRTY_REASON.remove();
        ServerWorld world = resolveWorld(region);
        if (world == null) {
            CLEAR_DIRTY_REASON.set("world_null");
            traceRegionEvent("CACHE_WRITE_REASON", null, region.getRegionX(), region.getRegionZ(), "result=world_null,success=" + success);
            return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
        }

        int regionX = region.getRegionX();
        int regionZ = region.getRegionZ();
        long currentTick = XaeroLiveRegionQueue.currentTick();
        PostCacheOrigin origin = file != null
            ? PostCacheOrigin.PRIMARY_CACHE_WRITE
            : PostCacheOrigin.AUXILIARY_POSTCACHE;
        BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(world, regionX, regionZ);
        traceRegionEvent(
            "POST_CACHE_ORIGIN",
            world,
            regionX,
            regionZ,
            "origin=" + origin.id + ",success=" + success
        );
        if (origin == PostCacheOrigin.AUXILIARY_POSTCACHE) {
            CLEAR_DIRTY_REASON.set("auxiliary_post_cache_ignored");
            traceRegionEvent(
                "CACHE_WRITE_REASON",
                world,
                regionX,
                regionZ,
                "result=aux_ignored,success=" + success + ",sourcePolicy=" + sourcePolicy.name().toLowerCase()
            );
            return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
        }
        BridgeLoadLeaseTracker.recordCacheWriteResult(world, regionX, regionZ, success, currentTick);
        traceRegionEvent(
            "CACHE_WRITE_REASON",
            world,
            regionX,
            regionZ,
            "result=post_cache,success=" + success + ",sourcePolicy=" + sourcePolicy.name().toLowerCase()
        );

        if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY) {
            BridgeLoadLeaseTracker.RequestStatus leaseStatus = BridgeLoadLeaseTracker.getLeaseStatus(
                world,
                regionX,
                regionZ,
                currentTick
            );
            long requestVersion = leaseStatus == null ? -1L : leaseStatus.requestedDirtyVersion();
            long currentDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
            boolean debounced = BridgeDirtyRegionStore.wasLastDirtyMarkDebounced(world, regionX, regionZ);
            if (!success) {
                CLEAR_DIRTY_REASON.set("policy_vanilla_only_cache_write_failed");
                traceRegionEvent(
                    "CLEAR_DIRTY_GUARD",
                    world,
                    regionX,
                    regionZ,
                    "result=vanilla_only_cache_write_failed,requestVersion="
                        + requestVersion
                        + ",currentVersion="
                        + currentDirtyVersion
                        + ",debounced="
                        + debounced
                );
                if (leaseStatus != null) {
                    finalizeLease(
                        world,
                        regionX,
                        regionZ,
                        BridgeLoadLeaseTracker.TerminalState.FAIL,
                        currentTick,
                        "post_cache_primary_failed"
                    );
                    releaseBridgeOwnedWritePrime(region, leaseStatus);
                }
                return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
            }

            if (leaseStatus == null
                || !leaseStatus.active()
                || leaseStatus.leaseKind() != BridgeLoadLeaseTracker.LeaseKind.VANILLA_ASSIST_LOAD
                || leaseStatus.requestedDirtyVersion() <= 0L) {
                CLEAR_DIRTY_REASON.set("policy_vanilla_only_missing_assist_lease");
                traceRegionEvent(
                    "CLEAR_DIRTY_GUARD",
                    world,
                    regionX,
                    regionZ,
                    "result=skip_vanilla_only_missing_assist_lease,requestVersion="
                        + requestVersion
                        + ",currentVersion="
                        + currentDirtyVersion
                        + ",debounced="
                        + debounced
                );
                return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
            }

            long expectedVersion = leaseStatus.requestedDirtyVersion();
            traceRegionEvent(
                "ASSIST_LEASE",
                world,
                regionX,
                regionZ,
                "state=cache_write,dirtyVersion=" + expectedVersion + ",success=true"
            );
            BridgeDirtyRegionStore.DirtyClearResult assistClearResult = BridgeDirtyRegionStore.clearDirtyIfVersion(
                world,
                regionX,
                regionZ,
                expectedVersion
            );
            CLEAR_DIRTY_REASON.set("vanilla_assist_post_cache_clear");
            traceRegionEvent(
                "CLEAR_DIRTY_GUARD",
                world,
                regionX,
                regionZ,
                "result=vanilla_assist_post_cache_clear,clearResult="
                    + assistClearResult.name().toLowerCase()
                    + ",buildVersion="
                    + expectedVersion
                    + ",requestVersion="
                    + expectedVersion
                    + ",currentVersion="
                    + BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ)
                    + ",debounced="
                    + debounced
            );
            if (assistClearResult == BridgeDirtyRegionStore.DirtyClearResult.CLEARED) {
                BridgeXaeroLoadedChunkTracker.markVisibleRegionFromSession(world, regionX, regionZ, expectedVersion);
                finalizeLease(
                    world,
                    regionX,
                    regionZ,
                    BridgeLoadLeaseTracker.TerminalState.SUCCESS,
                    currentTick,
                    "post_cache_primary"
                );
                traceRegionEvent(
                    "ASSIST_LEASE",
                    world,
                    regionX,
                    regionZ,
                    "state=terminal_success"
                        + ",dirtyVersion="
                        + expectedVersion
                        + ",clearResult=cleared"
                        + ",loadResult="
                        + leaseStatus.loadResult().name().toLowerCase()
                        + ",source=post_cache"
                );
            } else {
                finalizeLease(
                    world,
                    regionX,
                    regionZ,
                    BridgeLoadLeaseTracker.TerminalState.STALE,
                    currentTick,
                    "post_cache_primary_stale"
                );
                traceRegionEvent(
                    "ASSIST_LEASE",
                    world,
                    regionX,
                    regionZ,
                    "state=terminal_fail,dirtyVersion="
                        + expectedVersion
                        + ",clearResult="
                        + assistClearResult.name().toLowerCase()
                        + ",loadResult="
                        + leaseStatus.loadResult().name().toLowerCase()
                        + ",source=post_cache"
                );
            }
            releaseBridgeOwnedWritePrime(region, leaseStatus);
            return assistClearResult;
        }
        String cacheRegionKey = cacheRegionKey(world, region);
        long currentDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        if (!success) {
            CLEAR_DIRTY_REASON.set("cache_write_failed");
            traceRegionEvent(
                "CLEAR_DIRTY_GUARD",
                world,
                regionX,
                regionZ,
                "result=cache_write_failed,dirtyVersion=" + currentDirtyVersion
            );
            finalizeLease(world, regionX, regionZ, BridgeLoadLeaseTracker.TerminalState.FAIL, currentTick, "post_cache_primary_failed");
            releaseBridgeOwnedWritePrime(region, BridgeLoadLeaseTracker.getLeaseStatus(world, regionX, regionZ, currentTick));
            return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
        }
        if (!BridgeDirtyRegionStore.isDirty(world, regionX, regionZ)) {
            CLEAR_DIRTY_REASON.set("not_dirty");
            traceRegionEvent(
                "CLEAR_DIRTY_GUARD",
                world,
                regionX,
                regionZ,
                "result=not_dirty"
            );
            finalizeLease(world, regionX, regionZ, BridgeLoadLeaseTracker.TerminalState.SUCCESS, currentTick, "post_cache_primary_not_dirty");
            releaseBridgeOwnedWritePrime(region, BridgeLoadLeaseTracker.getLeaseStatus(world, regionX, regionZ, currentTick));
            return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
        }

        long buildVersion = BridgeRegionBuildTracker.consumeTrackedVersion(world, regionX, regionZ);
        if (buildVersion <= 0L) {
            CLEAR_DIRTY_REASON.set("build_version_missing");
            traceRegionEvent(
                "CLEAR_DIRTY_GUARD",
                world,
                regionX,
                regionZ,
                "result=build_version_missing,dirtyVersion=" + currentDirtyVersion
            );
            if (currentDirtyVersion > 0L) {
            BridgeSuspectRetryTracker.markSuspect(world, regionX, regionZ, currentDirtyVersion, "build_version_missing");
                BridgeCacheInvalidationTracker.allowReinvalidate(cacheRegionKey, currentDirtyVersion);
            }
            finalizeLease(world, regionX, regionZ, BridgeLoadLeaseTracker.TerminalState.FAIL, currentTick, "post_cache_build_version_missing");
            releaseBridgeOwnedWritePrime(region, BridgeLoadLeaseTracker.getLeaseStatus(world, regionX, regionZ, currentTick));
            return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
        }

        BridgeBuildQualityTracker.BuildQuality quality = BridgeBuildQualityTracker.getLastQuality(world, regionX, regionZ);
        if (quality == null) {
            CLEAR_DIRTY_REASON.set("build_quality_missing");
            traceRegionEvent(
                "CLEAR_DIRTY_GUARD",
                world,
                regionX,
                regionZ,
                "result=build_quality_missing,buildVersion=" + buildVersion + ",dirtyVersion=" + currentDirtyVersion
            );
            deleteCacheFile(region, null, file);
            BridgeSuspectRetryTracker.markSuspect(world, regionX, regionZ, buildVersion, "build_quality_missing");
            BridgeCacheInvalidationTracker.allowReinvalidate(cacheRegionKey, currentDirtyVersion);
            finalizeLease(world, regionX, regionZ, BridgeLoadLeaseTracker.TerminalState.FAIL, currentTick, "post_cache_build_quality_missing");
            releaseBridgeOwnedWritePrime(region, BridgeLoadLeaseTracker.getLeaseStatus(world, regionX, regionZ, currentTick));
            return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
        }

        if (quality.dirtyVersion() != buildVersion) {
            CLEAR_DIRTY_REASON.set("build_quality_version_mismatch");
            traceRegionEvent(
                "CLEAR_DIRTY_GUARD",
                world,
                regionX,
                regionZ,
                "result=build_quality_version_mismatch,buildVersion="
                    + buildVersion
                    + ",qualityDirtyVersion="
                    + quality.dirtyVersion()
            );
            deleteCacheFile(region, null, file);
            BridgeCacheInvalidationTracker.allowReinvalidate(cacheRegionKey, currentDirtyVersion);
            finalizeLease(world, regionX, regionZ, BridgeLoadLeaseTracker.TerminalState.STALE, currentTick, "post_cache_build_quality_version_mismatch");
            releaseBridgeOwnedWritePrime(region, BridgeLoadLeaseTracker.getLeaseStatus(world, regionX, regionZ, currentTick));
            return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
        }

        if (quality.committedDirtyVersionUsedByBuild() != buildVersion) {
            CLEAR_DIRTY_REASON.set("committed_version_mismatch");
            traceRegionEvent(
                "CLEAR_DIRTY_GUARD",
                world,
                regionX,
                regionZ,
                "result=committed_version_mismatch,buildVersion="
                    + buildVersion
                    + ",committedDirtyVersion="
                    + quality.committedDirtyVersionUsedByBuild()
            );
            deleteCacheFile(region, null, file);
            BridgeSuspectRetryTracker.markSuspect(world, regionX, regionZ, buildVersion, "committed_version_mismatch");
            BridgeCacheInvalidationTracker.allowReinvalidate(cacheRegionKey, currentDirtyVersion);
            finalizeLease(world, regionX, regionZ, BridgeLoadLeaseTracker.TerminalState.FAIL, currentTick, "post_cache_committed_version_mismatch");
            releaseBridgeOwnedWritePrime(region, BridgeLoadLeaseTracker.getLeaseStatus(world, regionX, regionZ, currentTick));
            return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
        }

        if (quality.hasBadChunkSignal()) {
            CLEAR_DIRTY_REASON.set("suspect_bad_chunks");
            traceRegionEvent(
                "CLEAR_DIRTY_GUARD",
                world,
                regionX,
                regionZ,
                "result=suspect_bad_chunks,buildVersion="
                    + buildVersion
                    + ",retryReason="
                    + quality.retryReason()
            );
            deleteCacheFile(region, null, file);
            BridgeSuspectRetryTracker.markSuspect(world, regionX, regionZ, buildVersion, quality.retryReason());
            BridgeCacheInvalidationTracker.allowReinvalidate(cacheRegionKey, currentDirtyVersion);
            finalizeLease(world, regionX, regionZ, BridgeLoadLeaseTracker.TerminalState.FAIL, currentTick, "post_cache_suspect_bad_chunks");
            releaseBridgeOwnedWritePrime(region, BridgeLoadLeaseTracker.getLeaseStatus(world, regionX, regionZ, currentTick));
            return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
        }

        if (!quality.meetsClearGate()) {
            CLEAR_DIRTY_REASON.set("build_quality_gate_failed");
            traceRegionEvent(
                "CLEAR_DIRTY_GUARD",
                world,
                regionX,
                regionZ,
                "result=build_quality_gate_failed,buildVersion="
                    + buildVersion
                    + ",retryReason="
                    + quality.retryReason()
            );
            deleteCacheFile(region, null, file);
            BridgeSuspectRetryTracker.markSuspect(world, regionX, regionZ, buildVersion, quality.retryReason());
            BridgeCacheInvalidationTracker.allowReinvalidate(cacheRegionKey, currentDirtyVersion);
            finalizeLease(world, regionX, regionZ, BridgeLoadLeaseTracker.TerminalState.FAIL, currentTick, "post_cache_build_quality_gate_failed");
            return BridgeDirtyRegionStore.DirtyClearResult.NOT_DIRTY;
        }

        CLEAR_DIRTY_REASON.set("versioned_clear_attempt");
        BridgeDirtyRegionStore.DirtyClearResult clearResult = BridgeDirtyRegionStore.clearDirtyIfVersion(world, regionX, regionZ, buildVersion);
        traceRegionEvent(
            "CLEAR_DIRTY_GUARD",
            world,
            regionX,
            regionZ,
            "result=versioned_clear_attempt,clearResult="
                + clearResult.name().toLowerCase()
                + ",buildVersion="
                + buildVersion
                + ",dirtyVersion="
                + BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ)
        );
        if (clearResult == BridgeDirtyRegionStore.DirtyClearResult.CLEARED) {
            BridgeXaeroLoadedChunkTracker.markVisibleRegionFromSession(world, regionX, regionZ, buildVersion);
            BridgeSuspectRetryTracker.clearResolved(world, regionX, regionZ, buildVersion);
            finalizeLease(world, regionX, regionZ, BridgeLoadLeaseTracker.TerminalState.SUCCESS, currentTick, "post_cache_primary");
        } else {
            BridgeCacheInvalidationTracker.allowReinvalidate(cacheRegionKey, currentDirtyVersion);
            finalizeLease(world, regionX, regionZ, BridgeLoadLeaseTracker.TerminalState.STALE, currentTick, "post_cache_primary_clear_stale");
        }
        releaseBridgeOwnedWritePrime(region, BridgeLoadLeaseTracker.getLeaseStatus(world, regionX, regionZ, currentTick));
        return clearResult;
    }

    private static void finalizeLease(
        ServerWorld world,
        int regionX,
        int regionZ,
        BridgeLoadLeaseTracker.TerminalState terminalState,
        long currentTick,
        String terminalSource
    ) {
        boolean finalized = BridgeLoadLeaseTracker.tryFinalizeLease(
            world,
            regionX,
            regionZ,
            terminalState,
            currentTick,
            terminalSource
        );
        if (finalized) {
            return;
        }
        traceRegionEvent(
            "LEASE_FINALIZE",
            world,
            regionX,
            regionZ,
            "result=duplicated_ignored,terminalState="
                + (terminalState == null ? "unknown" : terminalState.name().toLowerCase())
                + ",source="
                + (terminalSource == null ? "unknown" : terminalSource)
        );
    }

    public static String consumeLastClearDirtyReason() {
        String reason = CLEAR_DIRTY_REASON.get();
        CLEAR_DIRTY_REASON.remove();
        return reason;
    }

    private static String cacheRegionKey(ServerWorld world, MapRegion region) {
        return BridgePaths.getRuntimeCacheKey(world)
            + "|"
            + region.getRegionX()
            + "|"
            + region.getRegionZ()
            + "|"
            + region.getCaveLayer();
    }

    private static boolean deleteCacheFile(MapRegion region, MapProcessor mapProcessor, File preferredFile) {
        File cacheFile = preferredFile;
        if (cacheFile == null && mapProcessor != null) {
            MapSaveLoad mapSaveLoad = mapProcessor.getMapSaveLoad();
            if (mapSaveLoad != null) {
                try {
                    cacheFile = region.findCacheFile(mapSaveLoad);
                } catch (IOException ignored) {
                    return false;
                }
            }
        }

        if (cacheFile == null || !cacheFile.exists()) {
            return true;
        }

        if (!cacheFile.delete()) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed to delete stale Xaero cache file {} for region {},{}",
                cacheFile,
                region.getRegionX(),
                region.getRegionZ()
            );
            return false;
        }
        return true;
    }

    private static void traceRegionEvent(String phase, ServerWorld world, int regionX, int regionZ, String details) {
        String dimension = world == null ? "unknown" : world.getRegistryKey().getValue().toString();
        if (world != null) {
            if ("CACHE_INVALIDATE_REASON".equals(phase) || "CACHE_WRITE_REASON".equals(phase)) {
                BridgeRegionAuditService.touchRegion(world, regionX, regionZ, "cache_event");
            } else if ("CLEAR_DIRTY_GUARD".equals(phase)) {
                BridgeRegionAuditService.touchRegion(world, regionX, regionZ, "clear_dirty");
            }
        }
        VwgXwmBridgeClient.LOGGER.info(
            "[VWG->XWM Bridge][Trace] phase={} dim={} regionX={} regionZ={} {}",
            phase,
            dimension,
            regionX,
            regionZ,
            details == null ? "" : details
        );
    }

    private static void releaseBridgeOwnedWritePrime(MapRegion region, BridgeLoadLeaseTracker.RequestStatus leaseStatus) {
        if (region == null || leaseStatus == null || !leaseStatus.bridgeOwnedWritePrime()) {
            return;
        }
        if (region.isBeingWritten()) {
            region.setBeingWritten(false);
        }
    }
}


