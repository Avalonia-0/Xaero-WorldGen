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

import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeBuildQualityTracker {
    private static final ConcurrentHashMap<String, ActiveBuild> ACTIVE_BUILDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, BuildQuality> LAST_QUALITIES = new ConcurrentHashMap<>();

    private BridgeBuildQualityTracker() {
    }

    public static void beginRegionBuild(ServerWorld world, int regionX, int regionZ, long dirtyVersion) {
        ACTIVE_BUILDS.put(
            regionKey(world, regionX, regionZ),
            new ActiveBuild(
                dirtyVersion,
                BridgeRegionReleaseManager.currentCommittedDirtyVersion(world, regionX, regionZ)
            )
        );
    }

    public static void recordVanillaHit(ServerWorld world, ChunkPos chunkPos) {
        ActiveBuild activeBuild = ACTIVE_BUILDS.get(regionKey(world, chunkPos.x >> 5, chunkPos.z >> 5));
        if (activeBuild == null) {
            return;
        }

        activeBuild.recordVanillaHit(packChunk(chunkPos.x, chunkPos.z));
    }

    public static void recordFallbackResult(ServerWorld world, ChunkPos chunkPos, boolean hit, boolean coordMismatch) {
        ActiveBuild activeBuild = ACTIVE_BUILDS.get(regionKey(world, chunkPos.x >> 5, chunkPos.z >> 5));
        if (activeBuild == null) {
            return;
        }

        activeBuild.recordFallback(packChunk(chunkPos.x, chunkPos.z), hit, coordMismatch);
    }

    public static BuildQuality endRegionBuild(ServerWorld world, int regionX, int regionZ, boolean built) {
        String regionKey = regionKey(world, regionX, regionZ);
        ActiveBuild activeBuild = ACTIVE_BUILDS.remove(regionKey);
        if (activeBuild == null) {
            BuildQuality fallbackQuality = new BuildQuality(
                BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ),
                BridgeRegionReleaseManager.currentCommittedDirtyVersion(world, regionX, regionZ),
                0,
                0,
                0,
                0,
                0,
                0,
                VoxyRegionCoverageTracker.getRegionCoverage(world, regionX, regionZ),
                built
            );
            LAST_QUALITIES.put(regionKey, fallbackQuality);
            return fallbackQuality;
        }

        BuildQuality quality = activeBuild.toQuality(
            VoxyRegionCoverageTracker.getRegionCoverage(world, regionX, regionZ),
            built
        );
        LAST_QUALITIES.put(regionKey, quality);
        return quality;
    }

    public static BuildQuality getLastQuality(ServerWorld world, int regionX, int regionZ) {
        return LAST_QUALITIES.get(regionKey(world, regionX, regionZ));
    }

    public static void clearRuntimeState() {
        ACTIVE_BUILDS.clear();
        LAST_QUALITIES.clear();
    }

    private static String regionKey(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public record BuildQuality(
        long dirtyVersion,
        long committedDirtyVersionUsedByBuild,
        int observedTotal,
        int vanillaHit,
        int vanillaMiss,
        int fallbackHit,
        int fallbackMiss,
        int coordMismatch,
        int coverageAtBuild,
        boolean built
    ) {
        private static final int BAD_CHUNK_MIN_FALLBACK_MISS = 4;
        private static final double BAD_CHUNK_MIN_MISS_RATIO = 0.20D;

        public boolean meetsClearGate() {
            return built
                && dirtyVersion > 0L
                && committedDirtyVersionUsedByBuild == dirtyVersion
                && observedTotal > 0
                && coordMismatch == 0
                && fallbackMiss == 0
                && (fallbackHit + vanillaHit) > 0;
        }

        public boolean hasBadChunkSignal() {
            if (!built || observedTotal <= 0) {
                return false;
            }
            if (coordMismatch > 0) {
                return true;
            }
            if (fallbackHit <= 0) {
                return false;
            }
            return fallbackMiss >= BAD_CHUNK_MIN_FALLBACK_MISS && fallbackMissRatio() >= BAD_CHUNK_MIN_MISS_RATIO;
        }

        public String retryReason() {
            if (!built) {
                return "build_not_complete";
            }
            if (coordMismatch > 0) {
                return "coord_mismatch";
            }
            if (observedTotal <= 0) {
                return "no_observed_chunks";
            }
            if (fallbackHit <= 0 && fallbackMiss > 0) {
                return "fallback_all_miss";
            }
            if (fallbackMiss > 0) {
                return "fallback_partial_miss";
            }
            if ((fallbackHit + vanillaHit) <= 0) {
                return "no_chunk_hits";
            }
            return "quality_gate_failed";
        }

        public double fallbackMissRatio() {
            if (observedTotal <= 0) {
                return 1.0D;
            }
            return (double) fallbackMiss / (double) observedTotal;
        }
    }

    private static final class ActiveBuild {
        private final long dirtyVersion;
        private final long committedDirtyVersionUsedByBuild;
        private final HashSet<Long> observedChunks = new HashSet<>();
        private int observedTotal;
        private int vanillaHit;
        private int vanillaMiss;
        private int fallbackHit;
        private int fallbackMiss;
        private int coordMismatch;

        private ActiveBuild(long dirtyVersion, long committedDirtyVersionUsedByBuild) {
            this.dirtyVersion = dirtyVersion;
            this.committedDirtyVersionUsedByBuild = committedDirtyVersionUsedByBuild;
        }

        private synchronized void recordVanillaHit(long packedChunk) {
            if (!observedChunks.add(packedChunk)) {
                return;
            }

            observedTotal++;
            vanillaHit++;
        }

        private synchronized void recordFallback(long packedChunk, boolean hit, boolean hasCoordMismatch) {
            if (!observedChunks.add(packedChunk)) {
                return;
            }

            observedTotal++;
            vanillaMiss++;
            if (hit) {
                fallbackHit++;
                if (hasCoordMismatch) {
                    coordMismatch++;
                }
            } else {
                fallbackMiss++;
            }
        }

        private synchronized BuildQuality toQuality(int coverageAtBuild, boolean built) {
            return new BuildQuality(
                dirtyVersion,
                committedDirtyVersionUsedByBuild,
                observedTotal,
                vanillaHit,
                vanillaMiss,
                fallbackHit,
                fallbackMiss,
                coordMismatch,
                coverageAtBuild,
                built
            );
        }
    }
}


