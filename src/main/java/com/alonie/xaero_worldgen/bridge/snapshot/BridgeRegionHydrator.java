package com.alonie.xaero_worldgen.bridge.snapshot;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import com.alonie.xaero_worldgen.VwgXwmBridgeClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeRegionHydrator {
    private static final ConcurrentHashMap<String, HydrateTask> TASKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> ACTIVE_BY_RUNTIME = new ConcurrentHashMap<>();
    private static final long RETRY_DELAY_MILLIS = 2_000L;

    private BridgeRegionHydrator() {
    }

    public static void requestHydrate(ServerWorld world, int regionX, int regionZ, Priority priority) {
        if (world == null) {
            return;
        }

        if (!BridgeSourcePolicy.allowsBridgeDataPipeline(world, regionX, regionZ)) {
            return;
        }

        long dirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        if (dirtyVersion <= 0L) {
            return;
        }

        String key = regionKey(world, regionX, regionZ);
        TASKS.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return new HydrateTask(world, regionX, regionZ, dirtyVersion, priority);
            }
            existing.touch(world, dirtyVersion, priority);
            return existing;
        });
    }

    public static boolean isHydrating(ServerWorld world, int regionX, int regionZ) {
        HydrateTask task = TASKS.get(regionKey(world, regionX, regionZ));
        return task != null && !task.completed;
    }

    public static void tickServer(MinecraftServer server) {
        long serverTick = server.getTicks();
        for (ServerWorld world : server.getWorlds()) {
            tickWorld(world, serverTick);
        }
    }

    public static void clearRuntimeState() {
        TASKS.clear();
        ACTIVE_BY_RUNTIME.clear();
    }

    private static void tickWorld(ServerWorld world, long serverTick) {
        String runtimeCacheKey = BridgePaths.getRuntimeCacheKey(world);
        String activeKey = ACTIVE_BY_RUNTIME.get(runtimeCacheKey);
        HydrateTask activeTask = activeKey == null ? null : TASKS.get(activeKey);

        if (activeTask == null || activeTask.completed || activeTask.shouldPause()) {
            activeTask = selectNextTask(world);
            if (activeTask == null) {
                ACTIVE_BY_RUNTIME.remove(runtimeCacheKey);
                return;
            }
            ACTIVE_BY_RUNTIME.put(runtimeCacheKey, activeTask.key);
        }

        long startedAt = System.nanoTime();
        ProcessResult result = processTask(world, activeTask, startedAt);
        long elapsedNanos = System.nanoTime() - startedAt;
        if (activeTask.completed) {
            TASKS.remove(activeTask.key, activeTask);
            ACTIVE_BY_RUNTIME.remove(runtimeCacheKey, activeTask.key);
        }

        if (serverTick % BridgePerfBudget.PERF_LOG_INTERVAL_TICKS == 0L) {
            int runtimeTaskCount = 0;
            int pausedTaskCount = 0;
            for (HydrateTask task : TASKS.values()) {
                if (!runtimeCacheKey.equals(task.runtimeCacheKey)) {
                    continue;
                }
                runtimeTaskCount++;
                if (task.shouldPause()) {
                    pausedTaskCount++;
                }
            }

            if (result.processedChunks > 0 || runtimeTaskCount > 0) {
                int activeLeases = BridgeLoadLeaseTracker.countActiveNoPurge(runtimeCacheKey);
                VwgXwmBridgeClient.LOGGER.info(
                    "[VWG->XWM Bridge][Perf] hydrator dim={} processed={} pending={} paused={} active={} elapsedMs={} leaseActive={}",
                    world.getRegistryKey().getValue(),
                    result.processedChunks,
                    runtimeTaskCount,
                    pausedTaskCount,
                    activeTask == null ? "-" : activeTask.regionX + "," + activeTask.regionZ,
                    String.format(Locale.ROOT, "%.3f", elapsedNanos / 1_000_000.0D),
                    activeLeases
                );
            }
        }
    }

    private static HydrateTask selectNextTask(ServerWorld world) {
        String runtimeCacheKey = BridgePaths.getRuntimeCacheKey(world);
        ArrayList<HydrateTask> candidates = new ArrayList<>();
        for (Map.Entry<String, HydrateTask> entry : TASKS.entrySet()) {
            HydrateTask task = entry.getValue();
            ServerWorld taskWorld = task.worldRef.get();
            if (taskWorld == null || !runtimeCacheKey.equals(task.runtimeCacheKey) || task.completed || task.shouldPause()) {
                continue;
            }
            candidates.add(task);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(
            Comparator.comparingInt((HydrateTask task) -> task.priority.rank)
                .thenComparingLong(task -> task.requestedDirtyVersion)
                .thenComparingInt(task -> task.regionX)
                .thenComparingInt(task -> task.regionZ)
        );
        return candidates.get(0);
    }

    private static ProcessResult processTask(ServerWorld world, HydrateTask task, long startedAtNanos) {
        if (!BridgeSourcePolicy.allowsBridgeDataPipeline(world, task.regionX, task.regionZ)) {
            task.completed = true;
            return ProcessResult.EMPTY;
        }

        long currentDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, task.regionX, task.regionZ);
        if (currentDirtyVersion <= 0L) {
            task.completed = true;
            return ProcessResult.EMPTY;
        }

        if (currentDirtyVersion != task.requestedDirtyVersion) {
            task.reset(currentDirtyVersion);
        }

        int processed = 0;
        while (processed < BridgePerfBudget.HYDRATE_CHUNKS_PER_TICK
            && task.nextChunkIndex < BridgeChunkSnapshotStore.REGION_CHUNK_COUNT
            && (System.nanoTime() - startedAtNanos) < BridgePerfBudget.HYDRATE_TIME_BUDGET_NANOS) {
            int localX = task.nextChunkIndex & 31;
            int localZ = task.nextChunkIndex >> 5;
            ChunkPos chunkPos = new ChunkPos((task.regionX << 5) + localX, (task.regionZ << 5) + localZ);
            task.buffer[task.nextChunkIndex] = VoxyChunkNbtProvider.INSTANCE.createLiveChunkNbt(world, chunkPos);
            if (task.buffer[task.nextChunkIndex] == null) {
                task.missingChunks++;
            }
            task.nextChunkIndex++;
            task.lastWorkEpoch = System.currentTimeMillis();
            processed++;
        }

        if (task.nextChunkIndex < BridgeChunkSnapshotStore.REGION_CHUNK_COUNT) {
            return new ProcessResult(processed);
        }

        if (task.missingChunks == 0) {
            BridgeChunkSnapshotStore.commitHydratedRegion(world, task.regionX, task.regionZ, task.buffer, task.requestedDirtyVersion);
            task.completed = true;
            return new ProcessResult(processed);
        }

        task.deferUntilEpoch = System.currentTimeMillis() + RETRY_DELAY_MILLIS;
        task.reset(task.requestedDirtyVersion);
        return new ProcessResult(processed);
    }

    private static String regionKey(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }

    public enum Priority {
        NEAR(0),
        SUSPECT(1),
        BOOTSTRAP(2),
        FAR(3);

        private final int rank;

        Priority(int rank) {
            this.rank = rank;
        }
    }

    private static final class HydrateTask {
        private final String runtimeCacheKey;
        private final String key;
        private final int regionX;
        private final int regionZ;
        private WeakReference<ServerWorld> worldRef;
        private Priority priority;
        private long requestedDirtyVersion;
        private NbtCompound[] buffer;
        private int nextChunkIndex;
        private int missingChunks;
        private long deferUntilEpoch;
        private long lastWorkEpoch;
        private boolean completed;

        private HydrateTask(ServerWorld world, int regionX, int regionZ, long requestedDirtyVersion, Priority priority) {
            this.runtimeCacheKey = BridgePaths.getRuntimeCacheKey(world);
            this.key = regionKey(world, regionX, regionZ);
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.worldRef = new WeakReference<>(world);
            this.priority = priority;
            this.requestedDirtyVersion = requestedDirtyVersion;
            this.buffer = new NbtCompound[BridgeChunkSnapshotStore.REGION_CHUNK_COUNT];
            this.nextChunkIndex = 0;
            this.missingChunks = 0;
            this.deferUntilEpoch = 0L;
            this.lastWorkEpoch = 0L;
            this.completed = false;
        }

        private void touch(ServerWorld world, long dirtyVersion, Priority priority) {
            this.worldRef = new WeakReference<>(world);
            if (priority.rank < this.priority.rank) {
                this.priority = priority;
            }
            if (dirtyVersion > this.requestedDirtyVersion) {
                reset(dirtyVersion);
            }
        }

        private void reset(long dirtyVersion) {
            this.requestedDirtyVersion = dirtyVersion;
            this.buffer = new NbtCompound[BridgeChunkSnapshotStore.REGION_CHUNK_COUNT];
            this.nextChunkIndex = 0;
            this.missingChunks = 0;
            this.completed = false;
        }

        private boolean shouldPause() {
            return System.currentTimeMillis() < this.deferUntilEpoch;
        }
    }

    private record ProcessResult(int processedChunks) {
        private static final ProcessResult EMPTY = new ProcessResult(0);
    }
}


