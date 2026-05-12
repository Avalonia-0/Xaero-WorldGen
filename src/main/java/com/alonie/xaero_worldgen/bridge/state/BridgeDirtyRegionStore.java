package com.alonie.xaero_worldgen.bridge.state;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import com.alonie.xaero_worldgen.VwgXwmBridgeClient;
import net.minecraft.server.world.ServerWorld;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeDirtyRegionStore {
    private static final Map<String, DirtyState> STATES = new ConcurrentHashMap<>();

    private BridgeDirtyRegionStore() {
    }

    public static boolean isDirty(ServerWorld world, int regionX, int regionZ) {
        DirtyState state = getState(world);
        synchronized (state) {
            return state.dirtyRegions.containsKey(packRegion(regionX, regionZ));
        }
    }

    public static boolean markDirty(ServerWorld world, int regionX, int regionZ) {
        return markDirtyDebounced(world, regionX, regionZ, 0L).newlyDirty();
    }

    public static DirtyMarkResult markDirtyDebounced(
        ServerWorld world,
        int regionX,
        int regionZ,
        long debounceWindowMillis
    ) {
        DirtyState state = getState(world);
        DirtyMarkResult result;
        boolean shouldRevokeRelease = false;
        synchronized (state) {
            long packedRegion = packRegion(regionX, regionZ);
            long now = System.currentTimeMillis();
            DirtyRegionState existing = state.dirtyRegions.get(packedRegion);
            if (existing != null) {
                long elapsed = now - existing.lastDirtyEpoch;
                boolean debounced = debounceWindowMillis > 0L && elapsed >= 0L && elapsed < debounceWindowMillis;
                if (debounced) {
                    existing.markDebounced(now);
                } else {
                    long nextVersion = Math.max(existing.dirtyVersion + 1L, state.getVersionSeed(packedRegion) + 1L);
                    existing.markAgain(now, nextVersion);
                    state.versionSeeds.put(packedRegion, nextVersion);
                    shouldRevokeRelease = true;
                }
                state.dirtyPersist = true;
                BridgeStateFlushService.markDirtyRuntime(world);
                result = new DirtyMarkResult(false, debounced, existing.dirtyVersion, existing.lastDirtyEpoch);
            } else {
                long nextVersion = Math.max(1L, state.getVersionSeed(packedRegion) + 1L);
                DirtyRegionState created = new DirtyRegionState(now, now, nextVersion);
                state.dirtyRegions.put(packedRegion, created);
                state.versionSeeds.put(packedRegion, nextVersion);
                state.dirtyPersist = true;
                BridgeStateFlushService.markDirtyRuntime(world);
                result = new DirtyMarkResult(true, false, created.dirtyVersion, created.lastDirtyEpoch);
                shouldRevokeRelease = true;
            }
        }
        if (shouldRevokeRelease) {
            BridgeRegionReleaseManager.revokeOnDirtyAdvance(world, regionX, regionZ);
            BridgeXaeroLoadedChunkTracker.invalidateVisibleCoverage(world, regionX, regionZ);
        }
        if (!result.debounced()) {
            emitDirtyVersionTrace(world, regionX, regionZ, "mark", result.dirtyVersion());
        }
        return result;
    }

    public static DirtyMarkResult touchDirtyWithoutInvalidate(
        ServerWorld world,
        int regionX,
        int regionZ,
        long debounceWindowMillis
    ) {
        if (world == null) {
            return new DirtyMarkResult(false, false, -1L, -1L);
        }
        DirtyState state = getState(world);
        synchronized (state) {
            long packedRegion = packRegion(regionX, regionZ);
            long now = System.currentTimeMillis();
            DirtyRegionState existing = state.dirtyRegions.get(packedRegion);
            if (existing != null) {
                long elapsed = now - existing.lastDirtyEpoch;
                boolean debounced = debounceWindowMillis > 0L && elapsed >= 0L && elapsed < debounceWindowMillis;
                if (debounced) {
                    existing.markDebounced(now);
                    state.dirtyPersist = true;
                    BridgeStateFlushService.markDirtyRuntime(world);
                    return new DirtyMarkResult(false, true, existing.dirtyVersion, existing.lastDirtyEpoch);
                }
            }
        }
        return markDirtyDebounced(world, regionX, regionZ, 0L);
    }

    public static long getDirtyEpoch(ServerWorld world, int regionX, int regionZ) {
        DirtyState state = getState(world);
        synchronized (state) {
            DirtyRegionState dirtyRegion = state.dirtyRegions.get(packRegion(regionX, regionZ));
            return dirtyRegion != null ? dirtyRegion.firstDirtyEpoch : -1L;
        }
    }

    public static long getLastDirtyEpoch(ServerWorld world, int regionX, int regionZ) {
        DirtyState state = getState(world);
        synchronized (state) {
            DirtyRegionState dirtyRegion = state.dirtyRegions.get(packRegion(regionX, regionZ));
            return dirtyRegion != null ? dirtyRegion.lastDirtyEpoch : -1L;
        }
    }

    public static long getDirtyVersion(ServerWorld world, int regionX, int regionZ) {
        DirtyState state = getState(world);
        synchronized (state) {
            DirtyRegionState dirtyRegion = state.dirtyRegions.get(packRegion(regionX, regionZ));
            return dirtyRegion != null ? dirtyRegion.dirtyVersion : -1L;
        }
    }

    public static long getVersionSeed(ServerWorld world, int regionX, int regionZ) {
        DirtyState state = getState(world);
        synchronized (state) {
            return state.getVersionSeed(packRegion(regionX, regionZ));
        }
    }

    public static boolean wasLastDirtyMarkDebounced(ServerWorld world, int regionX, int regionZ) {
        DirtyState state = getState(world);
        synchronized (state) {
            DirtyRegionState dirtyRegion = state.dirtyRegions.get(packRegion(regionX, regionZ));
            return dirtyRegion != null && dirtyRegion.lastMarkDebounced;
        }
    }

    public static ArrayList<DirtyRegion> snapshotDirtyRegions(ServerWorld world) {
        DirtyState state = getState(world);
        synchronized (state) {
            ArrayList<DirtyRegion> snapshot = new ArrayList<>(state.dirtyRegions.size());
            for (Map.Entry<Long, DirtyRegionState> entry : state.dirtyRegions.entrySet()) {
                DirtyRegionState dirtyRegion = entry.getValue();
                snapshot.add(
                    new DirtyRegion(
                        unpackRegionX(entry.getKey()),
                        unpackRegionZ(entry.getKey()),
                        dirtyRegion.firstDirtyEpoch,
                        dirtyRegion.lastDirtyEpoch,
                        dirtyRegion.dirtyVersion
                    )
                );
            }

            snapshot.sort(
                Comparator.comparingLong(DirtyRegion::dirtyEpoch)
                    .thenComparingInt(DirtyRegion::regionX)
                    .thenComparingInt(DirtyRegion::regionZ)
            );
            return snapshot;
        }
    }

    public static void clearDirty(ServerWorld world, int regionX, int regionZ) {
        DirtyState state = getState(world);
        DirtyRegionState removed = null;
        synchronized (state) {
            long packedRegion = packRegion(regionX, regionZ);
            removed = state.dirtyRegions.remove(packedRegion);
            if (removed != null) {
                state.versionSeeds.put(packedRegion, Math.max(state.getVersionSeed(packedRegion), removed.dirtyVersion));
                state.dirtyPersist = true;
                BridgeStateFlushService.markDirtyRuntime(world);
            }
        }
        if (removed != null) {
            emitDirtyVersionTrace(world, regionX, regionZ, "clear", -1L);
        }
    }

    public static DirtyClearResult clearDirtyIfVersion(ServerWorld world, int regionX, int regionZ, long expectedDirtyVersion) {
        DirtyState state = getState(world);
        DirtyClearResult clearResult;
        synchronized (state) {
            long packedRegion = packRegion(regionX, regionZ);
            DirtyRegionState dirtyRegion = state.dirtyRegions.get(packedRegion);
            if (dirtyRegion == null) {
                return DirtyClearResult.NOT_DIRTY;
            }

            if (expectedDirtyVersion <= 0L || dirtyRegion.dirtyVersion != expectedDirtyVersion) {
                return DirtyClearResult.SKIPPED_STALE;
            }

            state.dirtyRegions.remove(packedRegion);
            state.versionSeeds.put(packedRegion, Math.max(state.getVersionSeed(packedRegion), dirtyRegion.dirtyVersion));
            state.dirtyPersist = true;
            BridgeStateFlushService.markDirtyRuntime(world);
            clearResult = DirtyClearResult.CLEARED;
        }
        emitDirtyVersionTrace(world, regionX, regionZ, "clear", -1L);
        return clearResult;
    }

    static boolean flushWorld(ServerWorld world) {
        DirtyState state = STATES.get(BridgePaths.getRuntimeCacheKey(world));
        if (state == null) {
            return false;
        }

        ArrayList<String> lines;
        synchronized (state) {
            if (!state.dirtyPersist) {
                return false;
            }
            lines = buildLines(state);
        }

        if (!writeStateToDisk(world, lines)) {
            return false;
        }

        synchronized (state) {
            state.dirtyPersist = false;
        }
        return true;
    }

    public static void clearRuntimeState() {
        STATES.clear();
    }

    public static long packRegion(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    public static int unpackRegionX(long packedRegion) {
        return (int) (packedRegion >> 32);
    }

    public static int unpackRegionZ(long packedRegion) {
        return (int) packedRegion;
    }

    public enum DirtyClearResult {
        CLEARED,
        SKIPPED_STALE,
        NOT_DIRTY
    }

    public record DirtyRegion(int regionX, int regionZ, long dirtyEpoch, long lastDirtyEpoch, long dirtyVersion) {
    }

    public record DirtyMarkResult(boolean newlyDirty, boolean debounced, long dirtyVersion, long lastDirtyEpoch) {
    }

    private static DirtyState getState(ServerWorld world) {
        return STATES.computeIfAbsent(BridgePaths.getRuntimeCacheKey(world), ignored -> load(world));
    }

    private static DirtyState load(ServerWorld world) {
        DirtyState state = new DirtyState();
        Path dirtyFile = BridgePaths.getDirtyFile(world);
        if (!Files.exists(dirtyFile)) {
            return state;
        }

        try {
            for (String line : Files.readAllLines(dirtyFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                String[] parts = trimmed.split(",");
                if (parts.length < 3) {
                    continue;
                }

                long firstDirtyEpoch = Long.parseLong(parts[2]);
                long lastDirtyEpoch = parts.length >= 5 ? Long.parseLong(parts[3]) : firstDirtyEpoch;
                long dirtyVersion = parts.length >= 5 ? Long.parseLong(parts[4]) : 1L;
                state.dirtyRegions.put(
                    packRegion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])),
                    new DirtyRegionState(firstDirtyEpoch, lastDirtyEpoch, dirtyVersion)
                );
                long packedRegion = packRegion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                state.versionSeeds.put(packedRegion, Math.max(state.getVersionSeed(packedRegion), dirtyVersion));
            }
        } catch (Exception exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed to load dirty regions from {}: {}",
                dirtyFile,
                exception.toString()
            );
        }

        return state;
    }

    private static void emitDirtyVersionTrace(ServerWorld world, int regionX, int regionZ, String action, long activeVersion) {
        if (world == null) {
            return;
        }
        long seed = getVersionSeed(world, regionX, regionZ);
        VwgXwmBridgeClient.LOGGER.info(
            "[VWG->XWM Bridge][Trace] phase=DIRTY_VERSION dim={} regionX={} regionZ={} action={} seed={} active={}",
            world.getRegistryKey().getValue(),
            regionX,
            regionZ,
            action == null ? "unknown" : action,
            seed,
            activeVersion
        );
    }

    private static ArrayList<String> buildLines(DirtyState state) {
        ArrayList<Map.Entry<Long, DirtyRegionState>> entries = new ArrayList<>(state.dirtyRegions.entrySet());
        entries.sort(Comparator.comparingLong(Map.Entry::getKey));

        ArrayList<String> lines = new ArrayList<>(entries.size());
        for (Map.Entry<Long, DirtyRegionState> entry : entries) {
            DirtyRegionState dirtyRegion = entry.getValue();
            lines.add(
                unpackRegionX(entry.getKey())
                    + ","
                    + unpackRegionZ(entry.getKey())
                    + ","
                    + dirtyRegion.firstDirtyEpoch
                    + ","
                    + dirtyRegion.lastDirtyEpoch
                    + ","
                    + dirtyRegion.dirtyVersion
            );
        }
        return lines;
    }

    private static boolean writeStateToDisk(ServerWorld world, ArrayList<String> lines) {
        Path dirtyFile = BridgePaths.getDirtyFile(world);
        try {
            Files.createDirectories(dirtyFile.getParent());
            Files.write(dirtyFile, lines, StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed to persist dirty regions to {}: {}",
                dirtyFile,
                exception.toString()
            );
            return false;
        }
    }

    private static final class DirtyState {
        private final Map<Long, DirtyRegionState> dirtyRegions = new HashMap<>();
        private final Map<Long, Long> versionSeeds = new HashMap<>();
        private boolean dirtyPersist;

        private long getVersionSeed(long packedRegion) {
            return versionSeeds.getOrDefault(packedRegion, 0L);
        }
    }

    private static final class DirtyRegionState {
        private final long firstDirtyEpoch;
        private long lastDirtyEpoch;
        private long dirtyVersion;
        private boolean lastMarkDebounced;

        private DirtyRegionState(long firstDirtyEpoch, long lastDirtyEpoch, long dirtyVersion) {
            this.firstDirtyEpoch = firstDirtyEpoch;
            this.lastDirtyEpoch = lastDirtyEpoch;
            this.dirtyVersion = dirtyVersion;
            this.lastMarkDebounced = false;
        }

        private void markAgain(long dirtyEpoch, long nextVersion) {
            this.lastDirtyEpoch = dirtyEpoch;
            this.dirtyVersion = nextVersion;
            this.lastMarkDebounced = false;
        }

        private void markDebounced(long dirtyEpoch) {
            this.lastDirtyEpoch = dirtyEpoch;
            this.lastMarkDebounced = true;
        }
    }
}


