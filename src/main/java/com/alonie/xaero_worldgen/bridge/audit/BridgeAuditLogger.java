package com.alonie.xaero_worldgen.bridge.audit;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import com.alonie.xaero_worldgen.VwgXwmBridgeClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.world.ServerWorld;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class BridgeAuditLogger {
    private static final DateTimeFormatter SESSION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());
    private static final Path LOG_PATH = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("vwg-xwm-audit.log");

    private static PrintWriter writer;
    private static String sessionId;

    private BridgeAuditLogger() {
    }

    public static synchronized void startSession() {
        if (writer != null) {
            return;
        }

        sessionId = SESSION_FORMAT.format(Instant.now());
        try {
            Files.createDirectories(LOG_PATH.getParent());
            BufferedWriter bufferedWriter = Files.newBufferedWriter(
                LOG_PATH,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            writer = new PrintWriter(bufferedWriter, true);
            writeRaw("===== VWG-XWM Audit Session " + sessionId + " started at " + TIME_FORMAT.format(Instant.now()) + " =====");
        } catch (IOException exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed to open audit log {}: {}",
                LOG_PATH,
                exception.toString()
            );
            writer = null;
        }
    }

    public static synchronized void closeSession() {
        if (writer == null) {
            return;
        }
        writeRaw("===== VWG-XWM Audit Session " + sessionId + " ended at " + TIME_FORMAT.format(Instant.now()) + " =====");
        writer.close();
        writer = null;
    }

    public static synchronized void clearRuntimeState() {
        closeSession();
        sessionId = null;
    }

    public static void log(
        long tick,
        String phase,
        String result,
        ServerWorld world,
        Integer regionX,
        Integer regionZ,
        String details
    ) {
        String dimension = world == null ? "-" : world.getRegistryKey().getValue().toString();
        String region = regionX == null || regionZ == null ? "-,-" : regionX + "," + regionZ;
        String payload = "session="
            + sanitize(sessionId == null ? "unknown" : sessionId)
            + " tick="
            + tick
            + " time="
            + TIME_FORMAT.format(Instant.now())
            + " phase="
            + sanitize(phase)
            + " result="
            + sanitize(result)
            + " dimension="
            + sanitize(dimension)
            + " region="
            + sanitize(region)
            + " details="
            + sanitize(details);
        synchronized (BridgeAuditLogger.class) {
            startSession();
            writeRaw(payload);
        }
    }

    public static Path getLogPath() {
        return LOG_PATH;
    }

    private static void writeRaw(String line) {
        if (writer != null) {
            writer.println(line);
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}


