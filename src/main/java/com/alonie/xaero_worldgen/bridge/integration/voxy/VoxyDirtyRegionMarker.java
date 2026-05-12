package com.alonie.xaero_worldgen.bridge.integration.voxy;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.concurrent.ConcurrentHashMap;

public final class VoxyDirtyRegionMarker {
    private static final long VANILLA_ONLY_DIRTY_DEBOUNCE_MILLIS = 120L * 50L;
    private static final long DIRTY_TOUCH_LOG_COOLDOWN_TICKS = 40L;
    private static final ConcurrentHashMap<String, Long> LAST_DIRTY_TOUCH_LOG_TICKS = new ConcurrentHashMap<>();

    private VoxyDirtyRegionMarker() {
    }

    public static boolean markChunkDirty(World world, int chunkX, int chunkZ) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }

        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        VoxyRegionCoverageTracker.markChunkIngested(serverWorld, chunkX, chunkZ);
        VoxyGeneratedRegionIndex.registerChunk(serverWorld, chunkX, chunkZ);
        BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(serverWorld, regionX, regionZ);
        BridgeDirtyRegionStore.DirtyMarkResult dirtyMarkResult;
        if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY) {
            dirtyMarkResult = BridgeDirtyRegionStore.touchDirtyWithoutInvalidate(
                serverWorld,
                regionX,
                regionZ,
                VANILLA_ONLY_DIRTY_DEBOUNCE_MILLIS
            );
            maybeLogVanillaNoInvalidateTouch(serverWorld, regionX, regionZ, dirtyMarkResult);
        } else {
            dirtyMarkResult = BridgeDirtyRegionStore.markDirtyDebounced(serverWorld, regionX, regionZ, 0L);
        }
        BridgeRegionAuditService.touchRegion(serverWorld, regionX, regionZ, "dirty_mark");
        XaeroLiveRegionQueue.enqueue(serverWorld, regionX, regionZ);
        return BridgeSourcePolicy.allowsBridgeDataPipeline(serverWorld, regionX, regionZ);
    }

    private static void maybeLogVanillaNoInvalidateTouch(
        ServerWorld world,
        int regionX,
        int regionZ,
        BridgeDirtyRegionStore.DirtyMarkResult dirtyMarkResult
    ) {
        if (world == null || dirtyMarkResult == null || !dirtyMarkResult.debounced()) {
            return;
        }

        long tick = XaeroLiveRegionQueue.currentTick();
        String key = BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
        Long lastLoggedTick = LAST_DIRTY_TOUCH_LOG_TICKS.get(key);
        if (lastLoggedTick != null && tick - lastLoggedTick < DIRTY_TOUCH_LOG_COOLDOWN_TICKS) {
            return;
        }
        LAST_DIRTY_TOUCH_LOG_TICKS.put(key, tick);
        BridgeAuditLogger.log(
            tick,
            "DIRTY_TOUCH",
            "vanilla_only_no_invalidate",
            world,
            regionX,
            regionZ,
            "debounced=true,dirtyVersion="
                + dirtyMarkResult.dirtyVersion()
                + ",lastDirtyEpoch="
                + dirtyMarkResult.lastDirtyEpoch()
        );
    }
}


