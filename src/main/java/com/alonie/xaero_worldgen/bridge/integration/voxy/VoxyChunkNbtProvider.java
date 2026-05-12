package com.alonie.xaero_worldgen.bridge.integration.voxy;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class VoxyChunkNbtProvider {
    public static final VoxyChunkNbtProvider INSTANCE = new VoxyChunkNbtProvider();
    private static final String DEFAULT_BIOME_ID = "minecraft:plains";
    private static final ThreadLocal<FallbackAttemptReport> LAST_ATTEMPT_REPORT = new ThreadLocal<>();
    private static final long VANILLA_LIVE_RETRY_COOLDOWN_MILLIS = 1_000L;
    private static final ConcurrentHashMap<String, Long> VANILLA_LIVE_RETRY_AFTER = new ConcurrentHashMap<>();

    private VoxyChunkNbtProvider() {
    }

    public static FallbackAttemptReport consumeLastAttemptReport() {
        FallbackAttemptReport report = LAST_ATTEMPT_REPORT.get();
        LAST_ATTEMPT_REPORT.remove();
        return report;
    }

    public static void clearRuntimeState() {
        LAST_ATTEMPT_REPORT.remove();
        VANILLA_LIVE_RETRY_AFTER.clear();
    }

    public NbtCompound createChunkNbt(ServerWorld world, ChunkPos chunkPos) {
        LAST_ATTEMPT_REPORT.remove();
        NbtCompound committedSnapshot = BridgeChunkSnapshotStore.getCommittedChunkNbt(world, chunkPos);
        if (committedSnapshot != null) {
            recordAttempt(chunkPos, true, "snapshot_committed_hit");
            return committedSnapshot;
        }

        int regionX = chunkPos.x >> 5;
        int regionZ = chunkPos.z >> 5;
        BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(world, regionX, regionZ);
        if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY
            && BridgePerfBudget.VANILLA_MISSING_CHUNK_FALLBACK
            && BridgeSourcePolicy.isVanillaChunkHeaderMissing(world, chunkPos.x, chunkPos.z)) {
            return attemptVanillaMissingChunkLiveFallback(world, chunkPos);
        }

        BridgeChunkSnapshotStore.CommitState commitState = BridgeChunkSnapshotStore.getCommitState(world, regionX, regionZ);
        String reason = commitState.committed() ? "snapshot_committed_chunk_missing" : "snapshot_not_committed";
        recordAttempt(chunkPos, false, reason);
        return null;
    }

    private NbtCompound attemptVanillaMissingChunkLiveFallback(ServerWorld world, ChunkPos chunkPos) {
        long now = System.currentTimeMillis();
        String retryKey = fallbackRetryKey(world, chunkPos);
        long retryAfterEpoch = VANILLA_LIVE_RETRY_AFTER.getOrDefault(retryKey, -1L);
        if (retryAfterEpoch > now) {
            recordAttempt(chunkPos, false, "vanilla_missing_chunk_live_cooldown");
            return null;
        }

        NbtCompound liveChunkNbt = createLiveChunkNbt(world, chunkPos);
        VoxyChunkReadinessTracker.Completeness completeness = VoxyChunkReadinessTracker.getLastCompleteness(world, chunkPos);
        if (liveChunkNbt != null) {
            VANILLA_LIVE_RETRY_AFTER.remove(retryKey);
            recordAttempt(chunkPos, true, "vanilla_missing_chunk_live_hit", completeness);
            BridgeFallbackEscalationPolicy.onFallbackAttemptResult(world, chunkPos.x, chunkPos.z, true);
            return liveChunkNbt;
        }

        VANILLA_LIVE_RETRY_AFTER.put(retryKey, now + VANILLA_LIVE_RETRY_COOLDOWN_MILLIS);
        String reason = completeness == null
            ? "vanilla_missing_chunk_live_miss"
            : "vanilla_missing_chunk_live_" + completeness.reason();
        recordAttempt(chunkPos, false, reason, completeness);
        BridgeFallbackEscalationPolicy.onFallbackAttemptResult(world, chunkPos.x, chunkPos.z, false);
        return null;
    }

    public NbtCompound createLiveChunkNbt(ServerWorld world, ChunkPos chunkPos) {
        CompletenessReport report = evaluateCompleteness(world, chunkPos);
        if (report == null || report.chunkNbt() == null) {
            return null;
        }

        return report.chunkNbt();
    }

    private CompletenessReport evaluateCompleteness(ServerWorld world, ChunkPos chunkPos) {
        WorldIdentifier identifier = WorldIdentifier.of(world);
        WorldEngine engine = identifier.getNullable();
        if (engine == null) {
            engine = identifier.getOrCreateEngine();
        }
        if (engine == null) {
            return new CompletenessReport(null, null, "engine_unavailable");
        }

        Mapper mapper = engine.getMapper();
        if (mapper == null) {
            return new CompletenessReport(null, null, "mapper_unavailable");
        }
        int bottomY = world.getBottomY();
        int minSectionY = Math.floorDiv(bottomY, 16);
        int sectionCount = (world.getHeight() + 15) / 16;
        int localChunkXOffset = Math.floorMod(chunkPos.x, 2) * 16;
        int localChunkZOffset = Math.floorMod(chunkPos.z, 2) * 16;
        int[] topPlusOne = new int[256];
        Arrays.fill(topPlusOne, bottomY);
        NbtList sections = new NbtList();
        ChunkScanStats scanStats = new ChunkScanStats(bottomY);

        for (int sectionY = minSectionY; sectionY < minSectionY + sectionCount; sectionY++) {
            long[] sectionData = getSectionData(engine, chunkPos, sectionY);
            if (sectionData == null) {
                continue;
            }

            scanStats.presentSections++;
            sections.add(buildSectionNbt(sectionData, mapper, sectionY, localChunkXOffset, localChunkZOffset, topPlusOne, scanStats));
        }

        scanStats.surfaceColumnCoverage = countSurfaceColumns(topPlusOne, Math.max(0, bottomY + 32));
        VoxyChunkReadinessTracker.Completeness completeness = VoxyChunkReadinessTracker.evaluateAndRecord(
            world,
            chunkPos,
            scanStats.presentSections,
            scanStats.nonAirBlocks,
            scanStats.highestNonAirY,
            scanStats.surfaceColumnCoverage
        );
        if (!completeness.ready()) {
            return new CompletenessReport(null, completeness, completeness.reason());
        }

        NbtCompound chunkNbt = new NbtCompound();
        String statusId = ChunkStatus.FULL.getId();
        chunkNbt.putInt("xPos", chunkPos.x);
        chunkNbt.putInt("zPos", chunkPos.z);
        chunkNbt.putString("Status", statusId);
        chunkNbt.putString("target_status", statusId);
        chunkNbt.putInt("yPos", minSectionY);
        chunkNbt.putBoolean("isLightOn", true);
        chunkNbt.putLong("LastUpdate", 0L);
        chunkNbt.putLong("InhabitedTime", 0L);
        chunkNbt.put("sections", sections);

        NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("WORLD_SURFACE", packHeightmap(topPlusOne, bottomY, world.getHeight()));
        chunkNbt.put("Heightmaps", heightmaps);

        // Xaero may parse legacy-style NBT in some paths, so include both modern root fields and a legacy Level wrapper.
        NbtCompound root = new NbtCompound();
        root.putInt("DataVersion", SharedConstants.getGameVersion().dataVersion().id());
        root.copyFrom(chunkNbt);
        root.put("Level", chunkNbt.copy());
        return new CompletenessReport(root, completeness, "ready");
    }

    private void recordAttempt(ChunkPos chunkPos, boolean hit, String reason) {
        recordAttempt(chunkPos, hit, reason, null);
    }

    private void recordAttempt(
        ChunkPos chunkPos,
        boolean hit,
        String reason,
        VoxyChunkReadinessTracker.Completeness completeness
    ) {
        int presentSections = -1;
        int nonAirBlocks = -1;
        int highestNonAirY = Integer.MIN_VALUE;
        int surfaceColumnCoverage = -1;
        int minimumSurfaceY = Integer.MIN_VALUE;
        boolean tracked = false;
        boolean fullChunkIngested = false;
        int trackedSections = 0;
        boolean seenSurfaceCandidateSection = false;

        if (completeness != null) {
            presentSections = completeness.presentSections();
            nonAirBlocks = completeness.nonAirBlocks();
            highestNonAirY = completeness.highestNonAirY();
            surfaceColumnCoverage = completeness.surfaceColumnCoverage();
            minimumSurfaceY = completeness.minimumSurfaceY();
            tracked = completeness.tracked();
            fullChunkIngested = completeness.fullChunkIngested();
            trackedSections = completeness.trackedSections();
            seenSurfaceCandidateSection = completeness.seenSurfaceCandidateSection();
        }

        LAST_ATTEMPT_REPORT.set(
            new FallbackAttemptReport(
                hit,
                chunkPos.x,
                chunkPos.z,
                reason == null || reason.isBlank() ? "unknown" : reason,
                presentSections,
                nonAirBlocks,
                highestNonAirY,
                surfaceColumnCoverage,
                minimumSurfaceY,
                tracked,
                fullChunkIngested,
                trackedSections,
                seenSurfaceCandidateSection
            )
        );
        ServerWorld world = BridgeContext.getCurrentWorld();
        if (!hit && world != null) {
            BridgeFallbackMissTracker.recordMiss(world, chunkPos, reason);
        }
    }

    private long[] getSectionData(WorldEngine engine, ChunkPos chunkPos, int sectionY) {
        int worldSectionX = Math.floorDiv(chunkPos.x, 2);
        int worldSectionY = Math.floorDiv(sectionY, 2);
        int worldSectionZ = Math.floorDiv(chunkPos.z, 2);
        WorldSection worldSection = engine.acquireIfExists(0, worldSectionX, worldSectionY, worldSectionZ);
        if (worldSection == null) {
            return null;
        }

        try {
            return worldSection.copyData();
        } finally {
            worldSection.release();
        }
    }

    private NbtCompound buildSectionNbt(
        long[] sectionData,
        Mapper mapper,
        int sectionY,
        int localChunkXOffset,
        int localChunkZOffset,
        int[] topPlusOne,
        ChunkScanStats scanStats
    ) {
        int localSectionYOffset = Math.floorMod(sectionY, 2) * 16;
        LinkedHashMap<BlockState, Integer> blockPalette = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> biomePalette = new LinkedHashMap<>();
        int[] blockIndices = new int[4096];
        int[] biomeIndices = new int[64];
        byte[] blockLight = new byte[2048];
        byte[] skyLight = new byte[2048];
        Mapper.BiomeEntry[] biomeEntries = mapper.getBiomeEntries();

        for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int blockIndex = getBlockIndex(localX, localY, localZ);
                    long mapping = sectionData[
                        WorldSection.getIndex(
                            localChunkXOffset + localX,
                            localSectionYOffset + localY,
                            localChunkZOffset + localZ
                        )
                    ];

                    BlockState state = resolveState(mapper, mapping);
                    blockIndices[blockIndex] = blockPalette.computeIfAbsent(state, ignored -> blockPalette.size());

                    int light = Mapper.getLightId(mapping);
                    setNibble(blockLight, blockIndex, (light >>> 4) & 15);
                    setNibble(skyLight, blockIndex, light & 15);

                    if (!state.isAir()) {
                        scanStats.nonAirBlocks++;
                        int xzIndex = (localZ << 4) | localX;
                        int absoluteTop = sectionY * 16 + localY + 1;
                        if (absoluteTop > scanStats.highestNonAirY) {
                            scanStats.highestNonAirY = absoluteTop;
                        }
                        if (absoluteTop > topPlusOne[xzIndex]) {
                            topPlusOne[xzIndex] = absoluteTop;
                        }
                    }
                }
            }
        }

        for (int biomeY = 0; biomeY < 4; biomeY++) {
            for (int biomeZ = 0; biomeZ < 4; biomeZ++) {
                for (int biomeX = 0; biomeX < 4; biomeX++) {
                    int sampleX = Math.min(15, biomeX * 4 + 2);
                    int sampleY = Math.min(15, biomeY * 4 + 2);
                    int sampleZ = Math.min(15, biomeZ * 4 + 2);
                    long mapping = sectionData[
                        WorldSection.getIndex(
                            localChunkXOffset + sampleX,
                            localSectionYOffset + sampleY,
                            localChunkZOffset + sampleZ
                        )
                    ];
                    String biomeId = resolveBiomeId(biomeEntries, Mapper.getBiomeId(mapping));
                    biomeIndices[getBiomeIndex(biomeX, biomeY, biomeZ)] = biomePalette.computeIfAbsent(
                        biomeId,
                        ignored -> biomePalette.size()
                    );
                }
            }
        }

        NbtCompound sectionTag = new NbtCompound();
        sectionTag.putByte("Y", (byte) sectionY);

        NbtCompound blockStatesTag = new NbtCompound();
        NbtList blockPaletteTag = new NbtList();
        for (BlockState state : blockPalette.keySet()) {
            blockPaletteTag.add(NbtHelper.fromBlockState(state));
        }
        blockStatesTag.put("palette", blockPaletteTag);
        long[] blockData = packPaletteData(blockIndices, blockPalette.size(), 4096, 4);
        if (blockData != null) {
            blockStatesTag.putLongArray("data", blockData);
        }
        sectionTag.put("block_states", blockStatesTag);

        NbtCompound biomesTag = new NbtCompound();
        NbtList biomePaletteTag = new NbtList();
        for (String biomeId : biomePalette.keySet()) {
            biomePaletteTag.add(NbtString.of(biomeId));
        }
        biomesTag.put("palette", biomePaletteTag);
        long[] biomeData = packPaletteData(biomeIndices, biomePalette.size(), 64, 0);
        if (biomeData != null) {
            biomesTag.putLongArray("data", biomeData);
        }
        sectionTag.put("biomes", biomesTag);

        sectionTag.putByteArray("BlockLight", blockLight);
        sectionTag.putByteArray("SkyLight", skyLight);
        return sectionTag;
    }

    private int countSurfaceColumns(int[] topPlusOne, int minimumSurfaceY) {
        int columns = 0;
        for (int topY : topPlusOne) {
            if (topY >= minimumSurfaceY) {
                columns++;
            }
        }
        return columns;
    }

    private BlockState resolveState(Mapper mapper, long mapping) {
        if (Mapper.isAir(mapping)) {
            return Blocks.AIR.getDefaultState();
        }

        BlockState state = mapper.getBlockStateFromBlockId(Mapper.getBlockId(mapping));
        return state == null ? Blocks.AIR.getDefaultState() : state;
    }

    private String resolveBiomeId(Mapper.BiomeEntry[] biomeEntries, int biomeId) {
        if (biomeId < 0 || biomeId >= biomeEntries.length) {
            return DEFAULT_BIOME_ID;
        }

        Mapper.BiomeEntry biomeEntry = biomeEntries[biomeId];
        if (biomeEntry == null || biomeEntry.biome == null || biomeEntry.biome.isBlank()) {
            return DEFAULT_BIOME_ID;
        }

        return biomeEntry.biome;
    }

    private long[] packHeightmap(int[] topPlusOne, int bottomY, int worldHeight) {
        int[] relativeHeights = new int[topPlusOne.length];
        for (int i = 0; i < topPlusOne.length; i++) {
            relativeHeights[i] = clamp(topPlusOne[i] - bottomY, 0, worldHeight);
        }
        return packValues(relativeHeights, Math.max(1, ceilLog2(worldHeight + 1)), relativeHeights.length);
    }

    private long[] packPaletteData(int[] values, int paletteSize, int arraySize, int minBits) {
        if (paletteSize <= 1) {
            return null;
        }

        int bits = Math.max(minBits, ceilLog2(paletteSize));
        if (bits <= 0) {
            bits = 1;
        }
        return packValues(values, bits, arraySize);
    }

    private long[] packValues(int[] values, int bits, int arraySize) {
        long[] packed = new long[(arraySize * bits + 63) / 64];
        long mask = (1L << bits) - 1L;

        for (int i = 0; i < arraySize; i++) {
            long value = values[i] & mask;
            int bitIndex = i * bits;
            int longIndex = bitIndex >>> 6;
            int bitOffset = bitIndex & 63;
            packed[longIndex] |= value << bitOffset;

            int spill = bitOffset + bits - 64;
            if (spill > 0) {
                packed[longIndex + 1] |= value >>> (bits - spill);
            }
        }

        return packed;
    }

    private void setNibble(byte[] target, int valueIndex, int value) {
        int byteIndex = valueIndex >> 1;
        int nibble = value & 15;
        int current = target[byteIndex] & 0xFF;
        if ((valueIndex & 1) == 0) {
            current = (current & 0xF0) | nibble;
        } else {
            current = (current & 0x0F) | (nibble << 4);
        }
        target[byteIndex] = (byte) current;
    }

    private String fallbackRetryKey(ServerWorld world, ChunkPos chunkPos) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + chunkPos.x + "|" + chunkPos.z;
    }

    private int getBlockIndex(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    private int getBiomeIndex(int localX, int localY, int localZ) {
        return (localY << 4) | (localZ << 2) | localX;
    }

    private int ceilLog2(int value) {
        if (value <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ChunkScanStats {
        private int presentSections;
        private int nonAirBlocks;
        private int highestNonAirY;
        private int surfaceColumnCoverage;

        private ChunkScanStats(int bottomY) {
            this.highestNonAirY = bottomY;
        }
    }

    public record FallbackAttemptReport(
        boolean hit,
        int chunkX,
        int chunkZ,
        String reason,
        int presentSections,
        int nonAirBlocks,
        int highestNonAirY,
        int surfaceColumnCoverage,
        int minimumSurfaceY,
        boolean tracked,
        boolean fullChunkIngested,
        int trackedSections,
        boolean seenSurfaceCandidateSection
    ) {
    }

    private record CompletenessReport(
        NbtCompound chunkNbt,
        VoxyChunkReadinessTracker.Completeness completeness,
        String reason
    ) {
    }
}


