package com.alonie.xaero_worldgen.bridge.snapshot;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeChunkSnapshotStore {
    public static final int REGION_SIDE_CHUNKS = 32;
    public static final int REGION_CHUNK_COUNT = REGION_SIDE_CHUNKS * REGION_SIDE_CHUNKS;
    private static final long LIVE_COMMIT_QUIET_MILLIS = 2_000L;
    private static final ConcurrentHashMap<String, RegionSnapshotState> REGION_SNAPSHOTS = new ConcurrentHashMap<>();

    private BridgeChunkSnapshotStore() {
    }

    public static boolean captureLiveChunk(World world, int chunkX, int chunkZ) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }
        if (!BridgeSourcePolicy.allowsBridgeDataPipeline(serverWorld, chunkX >> 5, chunkZ >> 5)) {
            return false;
        }

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        NbtCompound snapshot = VoxyChunkNbtProvider.INSTANCE.createLiveChunkNbt(serverWorld, chunkPos);
        if (snapshot == null) {
            return false;
        }

        long dirtyVersion = Math.max(BridgeDirtyRegionStore.getDirtyVersion(serverWorld, chunkX >> 5, chunkZ >> 5), 1L);
        captureLiveChunkNbt(serverWorld, chunkPos, snapshot, dirtyVersion);
        tryCommitRegion(serverWorld, chunkX >> 5, chunkZ >> 5);
        return true;
    }

    public static void captureLiveChunkNbt(ServerWorld world, ChunkPos chunkPos, NbtCompound snapshotNbt, long dirtyVersion) {
        if (world == null || chunkPos == null || snapshotNbt == null) {
            return;
        }
        if (!BridgeSourcePolicy.allowsBridgeDataPipeline(world, chunkPos.x >> 5, chunkPos.z >> 5)) {
            return;
        }

        RegionSnapshotState state = regionState(world, chunkPos.x >> 5, chunkPos.z >> 5);
        long now = System.currentTimeMillis();
        int localIndex = localChunkIndex(chunkPos.x, chunkPos.z);
        synchronized (state) {
            state.liveChunkNbt[localIndex] = snapshotNbt.copy();
            state.liveCoverage.set(localIndex);
            state.liveCoverageCount = state.liveCoverage.cardinality();
            state.liveSnapshotVersion++;
            state.liveDirtyVersion = Math.max(state.liveDirtyVersion, dirtyVersion);
            state.lastLiveUpdateEpoch = now;
        }
    }

    public static boolean tryCommitRegion(ServerWorld world, int regionX, int regionZ) {
        if (world == null) {
            return false;
        }
        if (!BridgeSourcePolicy.allowsBridgeDataPipeline(world, regionX, regionZ)) {
            return false;
        }

        RegionSnapshotState state = regionState(world, regionX, regionZ);
        long now = System.currentTimeMillis();
        long currentDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        synchronized (state) {
            if (state.liveCoverageCount < REGION_CHUNK_COUNT) {
                return false;
            }
            if (state.lastLiveUpdateEpoch <= 0L || now - state.lastLiveUpdateEpoch < LIVE_COMMIT_QUIET_MILLIS) {
                return false;
            }
            if (currentDirtyVersion <= 0L || state.liveDirtyVersion != currentDirtyVersion) {
                return false;
            }
            if (!allChunksPresent(state.liveChunkNbt)) {
                return false;
            }
            if (state.committedDirtyVersion == currentDirtyVersion && state.committedSnapshotVersion >= state.liveSnapshotVersion) {
                return false;
            }

            state.committedChunkNbt = copyChunks(state.liveChunkNbt);
            state.committedSnapshotVersion++;
            state.committedDirtyVersion = currentDirtyVersion;
            state.committedAtEpoch = now;
            state.committedCoverageCount = REGION_CHUNK_COUNT;
            clearLiveLocked(state);
        }

        BridgeRegionReleaseManager.markReleasedOnCommit(
            world,
            regionX,
            regionZ,
            currentDirtyVersion,
            getCommitState(world, regionX, regionZ).committedSnapshotVersion()
        );
        return true;
    }

    public static boolean commitHydratedRegion(ServerWorld world, int regionX, int regionZ, NbtCompound[] snapshots, long dirtyVersion) {
        if (world == null || snapshots == null || snapshots.length != REGION_CHUNK_COUNT || dirtyVersion <= 0L) {
            return false;
        }
        if (!BridgeSourcePolicy.allowsBridgeDataPipeline(world, regionX, regionZ)) {
            return false;
        }
        if (!allChunksPresent(snapshots)) {
            return false;
        }

        RegionSnapshotState state = regionState(world, regionX, regionZ);
        long now = System.currentTimeMillis();
        synchronized (state) {
            state.committedChunkNbt = copyChunks(snapshots);
            state.committedSnapshotVersion++;
            state.committedDirtyVersion = dirtyVersion;
            state.committedAtEpoch = now;
            state.committedCoverageCount = REGION_CHUNK_COUNT;
            clearLiveLocked(state);
        }

        BridgeRegionReleaseManager.markReleasedOnCommit(
            world,
            regionX,
            regionZ,
            dirtyVersion,
            getCommitState(world, regionX, regionZ).committedSnapshotVersion()
        );
        return true;
    }

    public static NbtCompound getCommittedChunkNbt(ServerWorld world, ChunkPos chunkPos) {
        if (world == null || chunkPos == null) {
            return null;
        }
        if (!BridgeSourcePolicy.allowsBridgeFallback(world, chunkPos.x, chunkPos.z)) {
            return null;
        }
        if (!BridgeRegionReleaseManager.hasCurrentCommittedDirtyVersion(world, chunkPos.x >> 5, chunkPos.z >> 5)) {
            return null;
        }

        RegionSnapshotState state = REGION_SNAPSHOTS.get(regionKey(world, chunkPos.x >> 5, chunkPos.z >> 5));
        if (state == null) {
            return null;
        }

        synchronized (state) {
            if (state.committedChunkNbt == null) {
                return null;
            }
            NbtCompound committed = state.committedChunkNbt[localChunkIndex(chunkPos.x, chunkPos.z)];
            return committed == null ? null : committed.copy();
        }
    }

    public static CommitState getCommitState(ServerWorld world, int regionX, int regionZ) {
        if (world == null) {
            return CommitState.EMPTY;
        }

        RegionSnapshotState state = REGION_SNAPSHOTS.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return CommitState.EMPTY;
        }

        synchronized (state) {
            long now = System.currentTimeMillis();
            long quietMillis = state.lastLiveUpdateEpoch <= 0L ? Long.MAX_VALUE : Math.max(0L, now - state.lastLiveUpdateEpoch);
            return new CommitState(
                state.liveCoverageCount,
                state.liveDirtyVersion,
                state.liveSnapshotVersion,
                quietMillis,
                LIVE_COMMIT_QUIET_MILLIS,
                state.committedChunkNbt != null && state.committedCoverageCount == REGION_CHUNK_COUNT,
                state.committedCoverageCount,
                state.committedDirtyVersion,
                state.committedSnapshotVersion,
                state.committedAtEpoch
            );
        }
    }

    public static void clearRuntimeState() {
        REGION_SNAPSHOTS.clear();
    }

    private static RegionSnapshotState regionState(ServerWorld world, int regionX, int regionZ) {
        return REGION_SNAPSHOTS.computeIfAbsent(regionKey(world, regionX, regionZ), ignored -> new RegionSnapshotState());
    }

    private static String regionKey(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }

    private static int localChunkIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) | ((chunkZ & 31) << 5);
    }

    private static boolean allChunksPresent(NbtCompound[] chunks) {
        for (NbtCompound chunk : chunks) {
            if (chunk == null) {
                return false;
            }
        }
        return true;
    }

    private static NbtCompound[] copyChunks(NbtCompound[] source) {
        NbtCompound[] copy = new NbtCompound[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].copy();
        }
        return copy;
    }

    private static void clearLiveLocked(RegionSnapshotState state) {
        Arrays.fill(state.liveChunkNbt, null);
        state.liveCoverage.clear();
        state.liveCoverageCount = 0;
        state.liveDirtyVersion = -1L;
        state.lastLiveUpdateEpoch = -1L;
    }

    public record CommitState(
        int liveCoverageCount,
        long liveDirtyVersion,
        long liveSnapshotVersion,
        long liveQuietMillis,
        long requiredQuietMillis,
        boolean committed,
        int committedCoverageCount,
        long committedDirtyVersion,
        long committedSnapshotVersion,
        long committedAtEpoch
    ) {
        private static final CommitState EMPTY = new CommitState(0, -1L, -1L, 0L, LIVE_COMMIT_QUIET_MILLIS, false, 0, -1L, -1L, -1L);

        public String details() {
            return "liveCoverageCount="
                + liveCoverageCount
                + ",liveDirtyVersion="
                + liveDirtyVersion
                + ",liveSnapshotVersion="
                + liveSnapshotVersion
                + ",liveQuietMillis="
                + liveQuietMillis
                + ",requiredQuietMillis="
                + requiredQuietMillis
                + ",committed="
                + committed
                + ",committedCoverageCount="
                + committedCoverageCount
                + ",committedDirtyVersion="
                + committedDirtyVersion
                + ",committedSnapshotVersion="
                + committedSnapshotVersion
                + ",committedAtEpoch="
                + committedAtEpoch;
        }
    }

    private static final class RegionSnapshotState {
        private final NbtCompound[] liveChunkNbt = new NbtCompound[REGION_CHUNK_COUNT];
        private final BitSet liveCoverage = new BitSet(REGION_CHUNK_COUNT);
        private int liveCoverageCount;
        private long liveDirtyVersion = -1L;
        private long liveSnapshotVersion;
        private long lastLiveUpdateEpoch = -1L;
        private NbtCompound[] committedChunkNbt;
        private int committedCoverageCount;
        private long committedDirtyVersion = -1L;
        private long committedSnapshotVersion = -1L;
        private long committedAtEpoch = -1L;
    }
}


