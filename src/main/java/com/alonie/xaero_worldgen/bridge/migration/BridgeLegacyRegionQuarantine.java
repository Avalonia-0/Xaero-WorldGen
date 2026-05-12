package com.alonie.xaero_worldgen.bridge.migration;


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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;

public final class BridgeLegacyRegionQuarantine {
    private static final long LEGACY_STUB_SIZE = 12_288L;
    private static final int LEGACY_STUB_ZERO_HEADER_SIZE = 8_192;

    private BridgeLegacyRegionQuarantine() {
    }

    public static void scanAndQuarantine(ServerWorld world) {
        Set<Long> knownRegions = VoxyGeneratedRegionIndex.snapshotKnownRegions(world);
        if (knownRegions.isEmpty()) {
            return;
        }

        int scanned = 0;
        int moved = 0;
        int skipped = 0;
        ArrayList<String> manifestLines = new ArrayList<>();
        for (Long packedRegion : knownRegions) {
            int regionX = BridgeDirtyRegionStore.unpackRegionX(packedRegion);
            int regionZ = BridgeDirtyRegionStore.unpackRegionZ(packedRegion);
            Path source = BridgePaths.getRegionFile(world, regionX, regionZ);
            if (!Files.isRegularFile(source)) {
                continue;
            }

            scanned++;
            if (!matchesLegacyBridgeStubSignature(source)) {
                skipped++;
                continue;
            }

            try {
                Path destination = resolveQuarantineDestination(world, regionX, regionZ);
                Files.createDirectories(destination.getParent());
                long size = Files.size(source);
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                moved++;
                manifestLines.add(
                    "time="
                        + Instant.now()
                        + ",src="
                        + source
                        + ",dst="
                        + destination
                        + ",size="
                        + size
                );
            } catch (IOException exception) {
                VwgXwmBridgeClient.LOGGER.warn(
                    "[VWG->XWM Bridge] Failed quarantining legacy stub region {}: {}",
                    source,
                    exception.toString()
                );
            }
        }

        appendManifest(world, manifestLines);
        if (moved > 0) {
            VwgXwmBridgeClient.LOGGER.info(
                "[VWG->XWM Bridge] Quarantined {} legacy bridge stub region files for {} (scanned={}, skipped_non_stub={}).",
                moved,
                world.getRegistryKey().getValue(),
                scanned,
                skipped
            );
        }
    }

    private static boolean matchesLegacyBridgeStubSignature(Path source) {
        try {
            if (Files.size(source) != LEGACY_STUB_SIZE) {
                return false;
            }

            try (InputStream inputStream = Files.newInputStream(source)) {
                byte[] header = inputStream.readNBytes(LEGACY_STUB_ZERO_HEADER_SIZE);
                if (header.length < LEGACY_STUB_ZERO_HEADER_SIZE) {
                    return false;
                }

                for (byte value : header) {
                    if (value != 0) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed to inspect candidate legacy region file {}: {}",
                source,
                exception.toString()
            );
            return false;
        }
    }

    private static Path resolveQuarantineDestination(ServerWorld world, int regionX, int regionZ) {
        Path quarantineDirectory = BridgePaths.getQuarantineDirectory(world);
        String baseName = "r." + regionX + "." + regionZ + ".mca";
        Path destination = quarantineDirectory.resolve(baseName);
        if (!Files.exists(destination)) {
            return destination;
        }

        int suffix = 1;
        while (true) {
            Path candidate = quarantineDirectory.resolve("r." + regionX + "." + regionZ + ".mca." + suffix);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            suffix++;
        }
    }

    private static void appendManifest(ServerWorld world, ArrayList<String> manifestLines) {
        if (manifestLines.isEmpty()) {
            return;
        }

        Path manifest = BridgePaths.getQuarantineManifestFile(world);
        try {
            Files.createDirectories(manifest.getParent());
            Files.write(
                manifest,
                manifestLines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed writing quarantine manifest {}: {}",
                manifest,
                exception.toString()
            );
        }
    }
}


