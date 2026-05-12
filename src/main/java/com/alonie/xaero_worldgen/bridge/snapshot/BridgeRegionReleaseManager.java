package com.alonie.xaero_worldgen.bridge.snapshot;


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

public final class BridgeRegionReleaseManager {
    private static final ConcurrentHashMap<String, ReleaseState> RELEASES = new ConcurrentHashMap<>();

    private BridgeRegionReleaseManager() {
    }

    public static void markReleasedOnCommit(
        ServerWorld world,
        int regionX,
        int regionZ,
        long committedDirtyVersion,
        long committedSnapshotVersion
    ) {
        if (world == null || committedDirtyVersion <= 0L) {
            return;
        }

        ReleaseState state = RELEASES.computeIfAbsent(regionKey(world, regionX, regionZ), ignored -> new ReleaseState());
        synchronized (state) {
            state.released = true;
            state.committedDirtyVersion = committedDirtyVersion;
            state.committedSnapshotVersion = committedSnapshotVersion;
            state.committedAtEpoch = System.currentTimeMillis();
        }
    }

    public static void revokeOnDirtyAdvance(ServerWorld world, int regionX, int regionZ) {
        if (world == null) {
            return;
        }

        ReleaseState state = RELEASES.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return;
        }

        long currentDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        synchronized (state) {
            if (currentDirtyVersion > 0L && currentDirtyVersion > state.committedDirtyVersion) {
                state.released = false;
            }
        }
    }

    public static boolean isReleased(ServerWorld world, int regionX, int regionZ) {
        if (world == null) {
            return false;
        }

        ReleaseState state = RELEASES.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return false;
        }

        long currentDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        synchronized (state) {
            if (currentDirtyVersion > 0L && currentDirtyVersion > state.committedDirtyVersion) {
                state.released = false;
            }
            return state.released && state.committedDirtyVersion > 0L;
        }
    }

    public static boolean hasCurrentCommittedDirtyVersion(ServerWorld world, int regionX, int regionZ) {
        if (!isReleased(world, regionX, regionZ)) {
            return false;
        }

        long currentDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        if (currentDirtyVersion <= 0L) {
            return true;
        }

        return currentCommittedDirtyVersion(world, regionX, regionZ) == currentDirtyVersion;
    }

    public static long currentCommittedDirtyVersion(ServerWorld world, int regionX, int regionZ) {
        ReleaseState state = RELEASES.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return -1L;
        }

        synchronized (state) {
            return state.committedDirtyVersion;
        }
    }

    public static void clearRuntimeState() {
        RELEASES.clear();
    }

    private static String regionKey(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }

    private static final class ReleaseState {
        private boolean released;
        private long committedDirtyVersion = -1L;
        private long committedSnapshotVersion = -1L;
        private long committedAtEpoch = -1L;
    }
}


