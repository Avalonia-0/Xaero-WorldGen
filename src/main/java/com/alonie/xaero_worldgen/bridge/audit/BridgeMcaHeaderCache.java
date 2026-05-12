package com.alonie.xaero_worldgen.bridge.audit;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import net.minecraft.server.world.ServerWorld;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeMcaHeaderCache {
    private static final int REGION_CHUNK_COUNT = 1_024;
    private static final int WORD_COUNT = REGION_CHUNK_COUNT / 64;
    private static final ConcurrentHashMap<String, ConcurrentHashMap<Long, CacheEntry>> CACHE = new ConcurrentHashMap<>();

    private BridgeMcaHeaderCache() {
    }

    public static McaCoverage getCoverage(ServerWorld world, int regionX, int regionZ, long currentTick, int refreshTicks) {
        if (world == null) {
            return McaCoverage.EMPTY;
        }

        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        long packedRegion = BridgeDirtyRegionStore.packRegion(regionX, regionZ);
        ConcurrentHashMap<Long, CacheEntry> runtimeCache = CACHE.computeIfAbsent(runtimeKey, ignored -> new ConcurrentHashMap<>());
        CacheEntry entry = runtimeCache.computeIfAbsent(packedRegion, ignored -> new CacheEntry());
        return entry.coverage(world, regionX, regionZ, currentTick, Math.max(1, refreshTicks));
    }

    public static boolean isChunkPresent(
        ServerWorld world,
        int regionX,
        int regionZ,
        int localChunkIndex,
        long currentTick,
        int refreshTicks
    ) {
        if (world == null || localChunkIndex < 0 || localChunkIndex >= REGION_CHUNK_COUNT) {
            return false;
        }

        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        long packedRegion = BridgeDirtyRegionStore.packRegion(regionX, regionZ);
        ConcurrentHashMap<Long, CacheEntry> runtimeCache = CACHE.computeIfAbsent(runtimeKey, ignored -> new ConcurrentHashMap<>());
        CacheEntry entry = runtimeCache.computeIfAbsent(packedRegion, ignored -> new CacheEntry());
        return entry.hasChunk(world, regionX, regionZ, localChunkIndex, currentTick, Math.max(1, refreshTicks));
    }

    public static McaCoverage forceRefresh(ServerWorld world, int regionX, int regionZ, long currentTick) {
        if (world == null) {
            return McaCoverage.EMPTY;
        }
        String runtimeKey = BridgePaths.getRuntimeCacheKey(world);
        long packedRegion = BridgeDirtyRegionStore.packRegion(regionX, regionZ);
        ConcurrentHashMap<Long, CacheEntry> runtimeCache = CACHE.computeIfAbsent(runtimeKey, ignored -> new ConcurrentHashMap<>());
        CacheEntry entry = runtimeCache.computeIfAbsent(packedRegion, ignored -> new CacheEntry());
        entry.invalidate();
        return entry.coverage(world, regionX, regionZ, currentTick, 1);
    }

    public static void clearRuntimeState() {
        CACHE.clear();
    }

    public record McaCoverage(
        boolean fileExists,
        int chunkCount,
        long[] words,
        long refreshedAtTick,
        long lastModifiedMillis
    ) {
        private static final McaCoverage EMPTY = new McaCoverage(false, 0, new long[WORD_COUNT], -1L, -1L);

        public boolean hasChunk(int localChunkIndex) {
            if (localChunkIndex < 0 || localChunkIndex >= REGION_CHUNK_COUNT) {
                return false;
            }
            int wordIndex = localChunkIndex >>> 6;
            long mask = 1L << (localChunkIndex & 63);
            return (words[wordIndex] & mask) != 0L;
        }
    }

    private static final class CacheEntry {
        private final long[] words = new long[WORD_COUNT];
        private boolean fileExists;
        private int chunkCount;
        private long refreshedAtTick = -1L;
        private long lastModifiedMillis = -1L;
        private long fileSize = -1L;

        private synchronized McaCoverage coverage(
            ServerWorld world,
            int regionX,
            int regionZ,
            long currentTick,
            int refreshTicks
        ) {
            if (refreshedAtTick < 0L || currentTick - refreshedAtTick >= refreshTicks) {
                refresh(world, regionX, regionZ, currentTick);
            }
            return new McaCoverage(
                fileExists,
                chunkCount,
                Arrays.copyOf(words, words.length),
                refreshedAtTick,
                lastModifiedMillis
            );
        }

        private synchronized boolean hasChunk(
            ServerWorld world,
            int regionX,
            int regionZ,
            int localChunkIndex,
            long currentTick,
            int refreshTicks
        ) {
            if (refreshedAtTick < 0L || currentTick - refreshedAtTick >= refreshTicks) {
                refresh(world, regionX, regionZ, currentTick);
            }
            int wordIndex = localChunkIndex >>> 6;
            long mask = 1L << (localChunkIndex & 63);
            return (words[wordIndex] & mask) != 0L;
        }

        private synchronized void invalidate() {
            this.refreshedAtTick = -1L;
        }

        private void refresh(ServerWorld world, int regionX, int regionZ, long currentTick) {
            Path regionFile = BridgePaths.getRegionFile(world, regionX, regionZ);
            if (!Files.isRegularFile(regionFile)) {
                Arrays.fill(words, 0L);
                fileExists = false;
                chunkCount = 0;
                refreshedAtTick = currentTick;
                lastModifiedMillis = -1L;
                fileSize = -1L;
                return;
            }

            try {
                long modified = Files.getLastModifiedTime(regionFile).toMillis();
                long size = Files.size(regionFile);
                if (modified == lastModifiedMillis && size == fileSize && chunkCount >= 0) {
                    refreshedAtTick = currentTick;
                    fileExists = true;
                    return;
                }

                byte[] header = new byte[4096];
                try (InputStream input = Files.newInputStream(regionFile)) {
                    int offset = 0;
                    while (offset < header.length) {
                        int read = input.read(header, offset, header.length - offset);
                        if (read < 0) {
                            break;
                        }
                        offset += read;
                    }
                    if (offset < header.length) {
                        invalidateFailedRead(currentTick, modified, size);
                        return;
                    }
                }

                Arrays.fill(words, 0L);
                int count = 0;
                for (int chunkIndex = 0; chunkIndex < REGION_CHUNK_COUNT; chunkIndex++) {
                    int headerOffset = chunkIndex << 2;
                    int value = ((header[headerOffset] & 0xFF) << 24)
                        | ((header[headerOffset + 1] & 0xFF) << 16)
                        | ((header[headerOffset + 2] & 0xFF) << 8)
                        | (header[headerOffset + 3] & 0xFF);
                    if (value == 0) {
                        continue;
                    }
                    words[chunkIndex >>> 6] |= (1L << (chunkIndex & 63));
                    count++;
                }

                fileExists = true;
                chunkCount = count;
                refreshedAtTick = currentTick;
                lastModifiedMillis = modified;
                fileSize = size;
            } catch (IOException ignored) {
                Arrays.fill(words, 0L);
                fileExists = true;
                chunkCount = -1;
                refreshedAtTick = currentTick;
                lastModifiedMillis = -1L;
                fileSize = -1L;
            }
        }

        private void invalidateFailedRead(long currentTick, long modified, long size) {
            Arrays.fill(words, 0L);
            fileExists = true;
            chunkCount = -1;
            refreshedAtTick = currentTick;
            lastModifiedMillis = modified;
            fileSize = size;
        }
    }
}


