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

import java.util.concurrent.ConcurrentHashMap;

public final class VoxyRegionCoverageTracker {
    private static final int REGION_SIDE_CHUNKS = 32;
    private static final int REGION_BITS = REGION_SIDE_CHUNKS * REGION_SIDE_CHUNKS;
    private static final int WORD_BITS = 64;
    private static final ConcurrentHashMap<String, ConcurrentHashMap<Long, RegionCoverageState>> COVERAGE = new ConcurrentHashMap<>();

    private VoxyRegionCoverageTracker() {
    }

    public static void markChunkIngested(ServerWorld world, int chunkX, int chunkZ) {
        String runtimeCacheKey = BridgePaths.getRuntimeCacheKey(world);
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long packedRegion = BridgeDirtyRegionStore.packRegion(regionX, regionZ);
        int localX = Math.floorMod(chunkX, REGION_SIDE_CHUNKS);
        int localZ = Math.floorMod(chunkZ, REGION_SIDE_CHUNKS);
        int chunkIndex = (localZ << 5) | localX;

        RegionCoverageState state = COVERAGE
            .computeIfAbsent(runtimeCacheKey, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(packedRegion, ignored -> new RegionCoverageState());
        state.mark(chunkIndex);
    }

    public static int getRegionCoverage(ServerWorld world, int regionX, int regionZ) {
        ConcurrentHashMap<Long, RegionCoverageState> worldCoverage = COVERAGE.get(BridgePaths.getRuntimeCacheKey(world));
        if (worldCoverage == null) {
            return 0;
        }

        RegionCoverageState state = worldCoverage.get(BridgeDirtyRegionStore.packRegion(regionX, regionZ));
        return state != null ? state.count() : 0;
    }

    public static void clearRuntimeState() {
        COVERAGE.clear();
    }

    private static final class RegionCoverageState {
        private final long[] words = new long[REGION_BITS / WORD_BITS];
        private int count;

        private synchronized void mark(int chunkIndex) {
            if (chunkIndex < 0 || chunkIndex >= REGION_BITS) {
                return;
            }

            int wordIndex = chunkIndex >>> 6;
            int bitIndex = chunkIndex & 63;
            long mask = 1L << bitIndex;
            if ((words[wordIndex] & mask) != 0L) {
                return;
            }

            words[wordIndex] |= mask;
            count++;
        }

        private synchronized int count() {
            return count;
        }
    }
}


