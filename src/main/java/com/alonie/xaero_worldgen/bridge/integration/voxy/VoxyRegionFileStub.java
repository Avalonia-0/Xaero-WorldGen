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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VoxyRegionFileStub {
    private static final long STUB_REGION_FILE_SIZE = 12_288L;

    private VoxyRegionFileStub() {
    }

    public static void ensurePresent(ServerWorld world, int regionX, int regionZ) {
        Path regionFile = BridgePaths.getBridgeStubRegionFile(world, regionX, regionZ);

        try {
            if (Files.exists(regionFile)) {
                return;
            }

            Files.createDirectories(regionFile.getParent());
            try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "rw")) {
                raf.setLength(STUB_REGION_FILE_SIZE);
            }
        } catch (IOException exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed to create stub region file {}: {}",
                regionFile,
                exception.toString()
            );
        }
    }
}


