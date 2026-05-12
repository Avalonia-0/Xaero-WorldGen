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

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeXaeroLoadedChunkTracker {
    private static final int REGION_SIDE = 32;
    private static final int REGION_CHUNK_COUNT = REGION_SIDE * REGION_SIDE;
    private static final int BIT_WORD_COUNT = REGION_CHUNK_COUNT / 64;
    private static final ConcurrentHashMap<String, ConcurrentHashMap<Long, RecentRegionState>> RECENT_LOADED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConcurrentHashMap<Long, SessionRegionState>> SESSION_LOADED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConcurrentHashMap<Long, VisibleRegionState>> VISIBLE_LOADED = new ConcurrentHashMap<>();

    private BridgeXaeroLoadedChunkTracker() {
    }

    public static void markLoadedChunk(ServerWorld world, int chunkX, int chunkZ, SourceKind sourceKind) {
        if (world == null) {
            return;
        }

        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        long packedRegion = BridgeDirtyRegionStore.packRegion(chunkX >> 5, chunkZ >> 5);
        int chunkIndex = localChunkIndex(chunkX, chunkZ);
        int tick = (int) Math.max(0L, XaeroLiveRegionQueue.currentTick());

        RecentRegionState recentState = RECENT_LOADED
            .computeIfAbsent(runtimeKey, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(packedRegion, ignored -> new RecentRegionState());
        recentState.mark(chunkIndex, tick, sourceKind);

        SessionRegionState sessionState = SESSION_LOADED
            .computeIfAbsent(runtimeKey, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(packedRegion, ignored -> new SessionRegionState());
        sessionState.mark(chunkIndex);
    }

    public static RegionCoverage getRecentCoverage(ServerWorld world, int regionX, int regionZ, long currentTick, int ttlTicks) {
        if (world == null) {
            return RegionCoverage.EMPTY;
        }

        ConcurrentHashMap<Long, RecentRegionState> runtimeMap = RECENT_LOADED.get(BridgePaths.getRuntimeCacheKey(world));
        if (runtimeMap == null) {
            return RegionCoverage.EMPTY;
        }

        long packedRegion = BridgeDirtyRegionStore.packRegion(regionX, regionZ);
        RecentRegionState state = runtimeMap.get(packedRegion);
        if (state == null) {
            return RegionCoverage.EMPTY;
        }

        RegionCoverage coverage = state.snapshotAndPrune((int) Math.max(0L, currentTick), Math.max(1, ttlTicks));
        if (coverage.count() <= 0) {
            runtimeMap.remove(packedRegion, state);
            return RegionCoverage.EMPTY;
        }
        return coverage;
    }

    public static RegionCoverage getSessionCoverage(ServerWorld world, int regionX, int regionZ) {
        if (world == null) {
            return RegionCoverage.EMPTY;
        }

        ConcurrentHashMap<Long, SessionRegionState> runtimeMap = SESSION_LOADED.get(BridgePaths.getRuntimeCacheKey(world));
        if (runtimeMap == null) {
            return RegionCoverage.EMPTY;
        }

        SessionRegionState state = runtimeMap.get(BridgeDirtyRegionStore.packRegion(regionX, regionZ));
        if (state == null) {
            return RegionCoverage.EMPTY;
        }
        return state.snapshot();
    }

    public static RegionCoverage getVisibleCoverage(ServerWorld world, int regionX, int regionZ, long expectedDirtyVersion) {
        if (world == null || expectedDirtyVersion <= 0L) {
            return RegionCoverage.EMPTY;
        }

        ConcurrentHashMap<Long, VisibleRegionState> runtimeMap = VISIBLE_LOADED.get(BridgePaths.getRuntimeCacheKey(world));
        if (runtimeMap == null) {
            return RegionCoverage.EMPTY;
        }

        VisibleRegionState state = runtimeMap.get(BridgeDirtyRegionStore.packRegion(regionX, regionZ));
        if (state == null) {
            return RegionCoverage.EMPTY;
        }
        return state.snapshot(expectedDirtyVersion);
    }

    public static void markVisibleRegionFromSession(ServerWorld world, int regionX, int regionZ, long dirtyVersion) {
        if (world == null || dirtyVersion <= 0L) {
            return;
        }

        RegionCoverage sessionCoverage = getSessionCoverage(world, regionX, regionZ);
        if (sessionCoverage.count() <= 0) {
            return;
        }

        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        long packedRegion = BridgeDirtyRegionStore.packRegion(regionX, regionZ);
        VISIBLE_LOADED
            .computeIfAbsent(runtimeKey, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(packedRegion, ignored -> new VisibleRegionState())
            .mark(dirtyVersion, sessionCoverage.words(), sessionCoverage.count(), XaeroLiveRegionQueue.currentTick());
    }

    public static void invalidateVisibleCoverage(ServerWorld world, int regionX, int regionZ) {
        if (world == null) {
            return;
        }

        ConcurrentHashMap<Long, VisibleRegionState> runtimeMap = VISIBLE_LOADED.get(BridgePaths.getRuntimeCacheKey(world));
        if (runtimeMap == null) {
            return;
        }

        runtimeMap.remove(BridgeDirtyRegionStore.packRegion(regionX, regionZ));
        if (runtimeMap.isEmpty()) {
            VISIBLE_LOADED.remove(BridgePaths.getRuntimeCacheKey(world), runtimeMap);
        }
    }

    public static RegionCoverage getRegionCoverage(ServerWorld world, int regionX, int regionZ, long currentTick, int ttlTicks) {
        return getRecentCoverage(world, regionX, regionZ, currentTick, ttlTicks);
    }

    public static void pruneExpired(long currentTick, int ttlTicks) {
        int tick = (int) Math.max(0L, currentTick);
        int ttl = Math.max(1, ttlTicks);
        for (Map.Entry<String, ConcurrentHashMap<Long, RecentRegionState>> runtimeEntry : RECENT_LOADED.entrySet()) {
            ConcurrentHashMap<Long, RecentRegionState> runtimeMap = runtimeEntry.getValue();
            runtimeMap.entrySet().removeIf(entry -> {
                RecentRegionState state = entry.getValue();
                return state.isRegionExpired(tick, ttl) || state.snapshotAndPrune(tick, ttl).count() <= 0;
            });
            if (runtimeMap.isEmpty()) {
                RECENT_LOADED.remove(runtimeEntry.getKey(), runtimeMap);
            }
        }
    }

    public static void clearRuntimeState() {
        RECENT_LOADED.clear();
        SESSION_LOADED.clear();
        VISIBLE_LOADED.clear();
    }

    private static int localChunkIndex(int chunkX, int chunkZ) {
        int localX = chunkX & 31;
        int localZ = chunkZ & 31;
        return localX | (localZ << 5);
    }

    public enum SourceKind {
        VANILLA_HIT,
        FALLBACK_HIT
    }

    public record RegionCoverage(int count, long[] words) {
        private static final RegionCoverage EMPTY = new RegionCoverage(0, new long[BIT_WORD_COUNT]);

        public boolean hasChunk(int localChunkIndex) {
            if (localChunkIndex < 0 || localChunkIndex >= REGION_CHUNK_COUNT) {
                return false;
            }
            int wordIndex = localChunkIndex >>> 6;
            long mask = 1L << (localChunkIndex & 63);
            return (words[wordIndex] & mask) != 0L;
        }
    }

    private static final class RecentRegionState {
        private final long[] words = new long[BIT_WORD_COUNT];
        private final int[] lastSeenTicks = new int[REGION_CHUNK_COUNT];
        private int count;
        private int lastSeenTick;
        private SourceKind lastSourceKind = SourceKind.VANILLA_HIT;

        private synchronized void mark(int chunkIndex, int tick, SourceKind sourceKind) {
            int wordIndex = chunkIndex >>> 6;
            long mask = 1L << (chunkIndex & 63);
            if ((words[wordIndex] & mask) == 0L) {
                words[wordIndex] |= mask;
                count++;
            }
            lastSeenTicks[chunkIndex] = tick;
            lastSeenTick = Math.max(lastSeenTick, tick);
            if (sourceKind != null) {
                lastSourceKind = sourceKind;
            }
        }

        private synchronized RegionCoverage snapshotAndPrune(int currentTick, int ttlTicks) {
            if (count <= 0) {
                return RegionCoverage.EMPTY;
            }

            int newCount = 0;
            for (int chunkIndex = 0; chunkIndex < REGION_CHUNK_COUNT; chunkIndex++) {
                int wordIndex = chunkIndex >>> 6;
                long mask = 1L << (chunkIndex & 63);
                if ((words[wordIndex] & mask) == 0L) {
                    continue;
                }

                if (currentTick - lastSeenTicks[chunkIndex] > ttlTicks) {
                    words[wordIndex] &= ~mask;
                    continue;
                }
                newCount++;
            }
            count = newCount;
            if (newCount <= 0) {
                Arrays.fill(words, 0L);
                return RegionCoverage.EMPTY;
            }
            return new RegionCoverage(newCount, Arrays.copyOf(words, words.length));
        }

        private synchronized boolean isRegionExpired(int currentTick, int ttlTicks) {
            return count <= 0 || currentTick - lastSeenTick > ttlTicks;
        }
    }

    private static final class SessionRegionState {
        private final long[] words = new long[BIT_WORD_COUNT];
        private int count;

        private synchronized void mark(int chunkIndex) {
            if (chunkIndex < 0 || chunkIndex >= REGION_CHUNK_COUNT) {
                return;
            }
            int wordIndex = chunkIndex >>> 6;
            long mask = 1L << (chunkIndex & 63);
            if ((words[wordIndex] & mask) != 0L) {
                return;
            }
            words[wordIndex] |= mask;
            count++;
        }

        private synchronized RegionCoverage snapshot() {
            if (count <= 0) {
                return RegionCoverage.EMPTY;
            }
            return new RegionCoverage(count, Arrays.copyOf(words, words.length));
        }
    }

    private static final class VisibleRegionState {
        private final long[] words = new long[BIT_WORD_COUNT];
        private long dirtyVersion;
        private int count;
        private long updatedTick;

        private synchronized void mark(long dirtyVersion, long[] sourceWords, int sourceCount, long currentTick) {
            if (sourceWords == null || sourceWords.length < BIT_WORD_COUNT || sourceCount <= 0 || dirtyVersion <= 0L) {
                return;
            }

            this.dirtyVersion = dirtyVersion;
            this.count = sourceCount;
            this.updatedTick = Math.max(0L, currentTick);
            System.arraycopy(sourceWords, 0, this.words, 0, BIT_WORD_COUNT);
        }

        private synchronized RegionCoverage snapshot(long expectedDirtyVersion) {
            if (expectedDirtyVersion <= 0L || this.dirtyVersion != expectedDirtyVersion || this.count <= 0) {
                return RegionCoverage.EMPTY;
            }
            return new RegionCoverage(this.count, Arrays.copyOf(this.words, this.words.length));
        }
    }
}


