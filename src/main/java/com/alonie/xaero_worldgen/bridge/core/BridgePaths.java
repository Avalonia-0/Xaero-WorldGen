package com.alonie.xaero_worldgen.bridge.core;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.dimension.DimensionType;

import java.nio.file.Path;

public final class BridgePaths {
    private BridgePaths() {
    }

    public static Path getSaveRoot(ServerWorld world) {
        return world.getServer().getSavePath(WorldSavePath.ROOT);
    }

    public static String getWorldHash(ServerWorld world) {
        return WorldIdentifier.of(world).getWorldId();
    }

    public static String getDimensionToken(ServerWorld world) {
        return sanitizeDimensionToken(world.getRegistryKey().toString());
    }

    public static String sanitizeDimensionToken(String rawDimensionKey) {
        return rawDimensionKey
            .replace("ResourceKey[", "")
            .replace("]", "")
            .replace("/", "_")
            .replace(":", "_")
            .trim();
    }

    public static Path getBridgeRoot(ServerWorld world) {
        return getSaveRoot(world).resolve("voxy").resolve("bridge");
    }

    public static Path getBridgeStubRegionDirectory(ServerWorld world) {
        return getBridgeRoot(world)
            .resolve("stub_regions")
            .resolve(getWorldHash(world))
            .resolve(getDimensionToken(world));
    }

    public static Path getBridgeStubRegionFile(ServerWorld world, int regionX, int regionZ) {
        return getBridgeStubRegionDirectory(world).resolve("r." + regionX + "." + regionZ + ".mca");
    }

    public static Path getQuarantineDirectory(ServerWorld world) {
        return getBridgeRoot(world)
            .resolve("quarantine")
            .resolve(getWorldHash(world))
            .resolve(getDimensionToken(world));
    }

    public static Path getQuarantineManifestFile(ServerWorld world) {
        return getQuarantineDirectory(world).resolve("manifest.log");
    }

    public static Path getDirtyFile(ServerWorld world) {
        return getBridgeRoot(world)
            .resolve("dirty")
            .resolve(getWorldHash(world))
            .resolve(getDimensionToken(world) + ".txt");
    }

    public static Path getKnownRegionsFile(ServerWorld world) {
        return getBridgeRoot(world)
            .resolve("known")
            .resolve(getWorldHash(world))
            .resolve(getDimensionToken(world) + ".txt");
    }

    public static Path getVoxyGenIndexFile(ServerWorld world) {
        return getSaveRoot(world).resolve("voxy_gen_" + getDimensionToken(world) + ".bin");
    }

    public static Path getRegionDirectory(ServerWorld world) {
        return DimensionType.getSaveDirectory(world.getRegistryKey(), getSaveRoot(world)).resolve("region");
    }

    public static Path getRegionFile(ServerWorld world, int regionX, int regionZ) {
        return getRegionDirectory(world).resolve("r." + regionX + "." + regionZ + ".mca");
    }

    public static String getRuntimeCacheKey(ServerWorld world) {
        return getSaveRoot(world) + "|" + getWorldHash(world) + "|" + getDimensionToken(world);
    }
}


