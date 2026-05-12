package com.alonie.xaero_worldgen.bridge.state;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;

public final class BridgeFallbackMissTracker {
    private static final ConcurrentHashMap<String, TooFewState> TOO_FEW_MISS = new ConcurrentHashMap<>();
    private static final long TOO_FEW_WINDOW_MILLIS = 8_000L;
    private static final int TOO_FEW_THRESHOLD = 8;

    private BridgeFallbackMissTracker() {
    }

    public static void recordMiss(ServerWorld world, ChunkPos chunkPos, String reason) {
        if (world == null || chunkPos == null || reason == null) {
            return;
        }
        if (!"vanilla_missing_chunk_live_too_few_blocks".equals(reason)) {
            return;
        }

        long now = System.currentTimeMillis();
        String key = regionKey(world, chunkPos.x >> 5, chunkPos.z >> 5);
        TooFewState state = TOO_FEW_MISS.computeIfAbsent(key, ignored -> new TooFewState());
        synchronized (state) {
            if (state.lastMissEpoch <= 0L || now - state.lastMissEpoch > TOO_FEW_WINDOW_MILLIS) {
                state.count = 1;
            } else {
                state.count++;
            }
            state.lastMissEpoch = now;
        }
    }

    public static boolean hasRecentTooFewBlocks(ServerWorld world, int regionX, int regionZ) {
        TooFewState state = TOO_FEW_MISS.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.lastMissEpoch <= 0L || now - state.lastMissEpoch > TOO_FEW_WINDOW_MILLIS) {
                state.count = 0;
                return false;
            }
            return state.count >= TOO_FEW_THRESHOLD;
        }
    }

    public static void clearRuntimeState() {
        TOO_FEW_MISS.clear();
    }

    private static String regionKey(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }

    private static final class TooFewState {
        private int count;
        private long lastMissEpoch;
    }
}


