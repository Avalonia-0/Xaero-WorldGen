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
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public final class VoxyChunkReadinessTracker {
    private static final ConcurrentHashMap<String, ChunkState> CHUNK_STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Completeness> LAST_COMPLETENESS = new ConcurrentHashMap<>();
    private static final int MIN_TRACKED_SECTIONS = 3;
    private static final int MIN_NON_AIR_BLOCKS = 256;
    private static final int MIN_SURFACE_COLUMNS = 48;

    private VoxyChunkReadinessTracker() {
    }

    public static void recordFullChunkIngest(World world, int chunkX, int chunkZ) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }

        ChunkState state = stateFor(serverWorld, chunkX, chunkZ);
        synchronized (state) {
            state.fullChunkIngested = true;
            state.sectionReadyAnnounced = true;
            state.lastIngestEpoch = System.currentTimeMillis();
        }
    }

    public static boolean recordSectionIngest(World world, int chunkX, int sectionY, int chunkZ) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }

        ChunkState state = stateFor(serverWorld, chunkX, chunkZ);
        synchronized (state) {
            state.sections.add(sectionY);
            state.lastIngestEpoch = System.currentTimeMillis();
            if (sectionY >= 0) {
                state.seenSurfaceCandidateSection = true;
            }
            if (!state.sectionReadyAnnounced
                && state.sections.size() >= MIN_TRACKED_SECTIONS
                && state.seenSurfaceCandidateSection) {
                state.sectionReadyAnnounced = true;
                return true;
            }
            return false;
        }
    }

    public static Snapshot snapshot(ServerWorld world, ChunkPos chunkPos) {
        ChunkState state = CHUNK_STATES.get(chunkKey(world, chunkPos.x, chunkPos.z));
        if (state == null) {
            return Snapshot.EMPTY;
        }

        synchronized (state) {
            return new Snapshot(
                true,
                state.fullChunkIngested,
                state.sections.size(),
                state.seenSurfaceCandidateSection,
                state.lastIngestEpoch
            );
        }
    }

    public static Completeness evaluateAndRecord(
        ServerWorld world,
        ChunkPos chunkPos,
        int presentSections,
        int nonAirBlocks,
        int highestNonAirY,
        int surfaceColumnCoverage
    ) {
        Snapshot snapshot = snapshot(world, chunkPos);
        int minimumSurfaceY = Math.max(0, world.getBottomY() + 32);
        String reason = "ready";
        boolean ready;

        if (presentSections <= 0) {
            ready = false;
            reason = "no_sections";
        } else if (nonAirBlocks < MIN_NON_AIR_BLOCKS) {
            ready = false;
            reason = "too_few_blocks";
        } else if (snapshot.fullChunkIngested()) {
            ready = true;
        } else if (snapshot.tracked() && snapshot.trackedSections() < MIN_TRACKED_SECTIONS) {
            ready = false;
            reason = "too_few_tracked_sections";
        } else if (snapshot.tracked() && !snapshot.seenSurfaceCandidateSection()) {
            ready = false;
            reason = "no_surface_section";
        } else if (highestNonAirY < minimumSurfaceY) {
            ready = false;
            reason = "highest_y_too_low";
        } else if (surfaceColumnCoverage < MIN_SURFACE_COLUMNS) {
            ready = false;
            reason = "surface_coverage_too_low";
        } else {
            ready = true;
        }

        Completeness completeness = new Completeness(
            chunkPos.x,
            chunkPos.z,
            presentSections,
            nonAirBlocks,
            highestNonAirY,
            surfaceColumnCoverage,
            minimumSurfaceY,
            snapshot.tracked(),
            snapshot.fullChunkIngested(),
            snapshot.trackedSections(),
            snapshot.seenSurfaceCandidateSection(),
            ready,
            reason
        );
        LAST_COMPLETENESS.put(chunkKey(world, chunkPos.x, chunkPos.z), completeness);
        return completeness;
    }

    public static Completeness getLastCompleteness(ServerWorld world, ChunkPos chunkPos) {
        return LAST_COMPLETENESS.get(chunkKey(world, chunkPos.x, chunkPos.z));
    }

    public static void clearRuntimeState() {
        CHUNK_STATES.clear();
        LAST_COMPLETENESS.clear();
    }

    private static ChunkState stateFor(ServerWorld world, int chunkX, int chunkZ) {
        return CHUNK_STATES.computeIfAbsent(chunkKey(world, chunkX, chunkZ), ignored -> new ChunkState());
    }

    private static String chunkKey(ServerWorld world, int chunkX, int chunkZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + chunkX + "|" + chunkZ;
    }

    private static final class ChunkState {
        private final HashSet<Integer> sections = new HashSet<>();
        private boolean fullChunkIngested;
        private boolean seenSurfaceCandidateSection;
        private boolean sectionReadyAnnounced;
        private long lastIngestEpoch = -1L;
    }

    public record Snapshot(
        boolean tracked,
        boolean fullChunkIngested,
        int trackedSections,
        boolean seenSurfaceCandidateSection,
        long lastIngestEpoch
    ) {
        private static final Snapshot EMPTY = new Snapshot(false, false, 0, false, -1L);
    }

    public record Completeness(
        int chunkX,
        int chunkZ,
        int presentSections,
        int nonAirBlocks,
        int highestNonAirY,
        int surfaceColumnCoverage,
        int minimumSurfaceY,
        boolean tracked,
        boolean fullChunkIngested,
        int trackedSections,
        boolean seenSurfaceCandidateSection,
        boolean ready,
        String reason
    ) {
    }
}


