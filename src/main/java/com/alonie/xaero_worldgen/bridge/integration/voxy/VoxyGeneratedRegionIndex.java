package com.alonie.xaero_worldgen.bridge.integration.voxy;


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

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class VoxyGeneratedRegionIndex {
    private static final ConcurrentHashMap<String, RegionSetState> KNOWN_REGIONS = new ConcurrentHashMap<>();

    private VoxyGeneratedRegionIndex() {
    }

    public static void bootstrap(ServerWorld world) {
        RegionSetState state = getState(world);
        Path indexFile = BridgePaths.getVoxyGenIndexFile(world);
        if (!Files.exists(indexFile)) {
            return;
        }

        Set<Long> currentRegions = readRegionKeys(indexFile);
        if (currentRegions.isEmpty()) {
            return;
        }

        int additions = 0;
        synchronized (state) {
            for (long packedRegion : currentRegions) {
                if (state.regions.add(packedRegion)) {
                    state.dirtyPersist = true;
                    additions++;
                }
            }
        }

        if (additions > 0) {
            BridgeStateFlushService.markKnownRegionRuntime(world);
        }

        int dirtyMarked = 0;
        for (long packedRegion : currentRegions) {
            int regionX = BridgeDirtyRegionStore.unpackRegionX(packedRegion);
            int regionZ = BridgeDirtyRegionStore.unpackRegionZ(packedRegion);
            if (BridgeDirtyRegionStore.markDirty(world, regionX, regionZ)) {
                dirtyMarked++;
            }
        }

        if (additions > 0 || dirtyMarked > 0) {
            VwgXwmBridgeClient.LOGGER.info(
                "[VWG->XWM Bridge] Bootstrapped {} Voxy-backed Xaero regions for {} (newKnown={}, newDirty={}).",
                currentRegions.size(),
                world.getRegistryKey().getValue(),
                additions,
                dirtyMarked
            );
        }
    }

    public static void registerChunk(ServerWorld world, int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long packedRegion = BridgeDirtyRegionStore.packRegion(regionX, regionZ);

        RegionSetState state = getState(world);
        boolean added = false;
        synchronized (state) {
            if (state.regions.add(packedRegion)) {
                state.dirtyPersist = true;
                added = true;
            }
        }

        if (added) {
            BridgeStateFlushService.markKnownRegionRuntime(world);
        }
    }

    public static boolean mayHaveRegion(ServerWorld world, int regionX, int regionZ) {
        RegionSetState state = getState(world);
        synchronized (state) {
            return state.regions.contains(BridgeDirtyRegionStore.packRegion(regionX, regionZ));
        }
    }

    public static Set<Long> snapshotKnownRegions(ServerWorld world) {
        RegionSetState state = getState(world);
        synchronized (state) {
            return new HashSet<>(state.regions);
        }
    }

    public static boolean flushWorld(ServerWorld world) {
        RegionSetState state = KNOWN_REGIONS.get(BridgePaths.getRuntimeCacheKey(world));
        if (state == null) {
            return false;
        }

        ArrayList<String> lines;
        synchronized (state) {
            if (!state.dirtyPersist) {
                return false;
            }
            lines = buildLines(state.regions);
        }

        if (!writeState(world, lines)) {
            return false;
        }

        synchronized (state) {
            state.dirtyPersist = false;
        }
        return true;
    }

    public static void clearRuntimeState() {
        KNOWN_REGIONS.clear();
    }

    private static RegionSetState getState(ServerWorld world) {
        return KNOWN_REGIONS.computeIfAbsent(BridgePaths.getRuntimeCacheKey(world), ignored -> loadState(world));
    }

    private static RegionSetState loadState(ServerWorld world) {
        RegionSetState state = new RegionSetState();
        Path knownFile = BridgePaths.getKnownRegionsFile(world);
        if (!Files.exists(knownFile)) {
            return state;
        }

        try {
            for (String line : Files.readAllLines(knownFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                String[] parts = trimmed.split(",");
                if (parts.length < 2) {
                    continue;
                }

                int regionX = Integer.parseInt(parts[0]);
                int regionZ = Integer.parseInt(parts[1]);
                state.regions.add(BridgeDirtyRegionStore.packRegion(regionX, regionZ));
            }
        } catch (Exception exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed to load known-region index {}: {}",
                knownFile,
                exception.toString()
            );
        }

        return state;
    }

    private static Set<Long> readRegionKeys(Path indexFile) {
        Set<Long> regionKeys = new HashSet<>();

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexFile)))) {
            int count = input.readInt();
            if (count < 0 || count > 50_000_000) {
                return regionKeys;
            }

            for (int i = 0; i < count; i++) {
                long packedChunk = input.readLong();
                int chunkX = (int) (packedChunk >> 32);
                int chunkZ = (int) packedChunk;
                regionKeys.add(BridgeDirtyRegionStore.packRegion(chunkX >> 5, chunkZ >> 5));
            }
        } catch (IOException exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed reading {}: {}",
                indexFile,
                exception.toString()
            );
        }

        return regionKeys;
    }

    private static ArrayList<String> buildLines(Set<Long> regions) {
        ArrayList<Long> ordered = new ArrayList<>(regions);
        ordered.sort(Comparator.naturalOrder());

        ArrayList<String> lines = new ArrayList<>(ordered.size());
        for (Long packedRegion : ordered) {
            lines.add(
                BridgeDirtyRegionStore.unpackRegionX(packedRegion)
                    + ","
                    + BridgeDirtyRegionStore.unpackRegionZ(packedRegion)
            );
        }
        return lines;
    }

    private static boolean writeState(ServerWorld world, ArrayList<String> lines) {
        Path knownFile = BridgePaths.getKnownRegionsFile(world);
        try {
            Files.createDirectories(knownFile.getParent());
            Files.write(knownFile, lines, StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed to persist known-region index {}: {}",
                knownFile,
                exception.toString()
            );
            return false;
        }
    }

    private static final class RegionSetState {
        private final Set<Long> regions = new HashSet<>();
        private boolean dirtyPersist;
    }
}


