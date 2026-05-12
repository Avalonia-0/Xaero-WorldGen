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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class BridgeAuditConfig {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("xaero-worldgen-audit.properties");
    private static final long RELOAD_INTERVAL_MILLIS = 5_000L;
    private static volatile Config CURRENT = Config.defaults();
    private static volatile long lastLoadedEpochMillis = -1L;
    private static volatile long lastKnownModifiedMillis = -1L;

    private BridgeAuditConfig() {
    }

    public static Config current() {
        reloadIfDue();
        return CURRENT;
    }

    public static synchronized Config load() {
        return loadInternal(true);
    }

    public static synchronized void reloadIfDue() {
        long now = System.currentTimeMillis();
        if (lastLoadedEpochMillis > 0L && now - lastLoadedEpochMillis < RELOAD_INTERVAL_MILLIS) {
            return;
        }
        loadInternal(false);
    }

    private static Config loadInternal(boolean forced) {
        Config defaults = Config.defaults();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (!Files.exists(CONFIG_PATH)) {
                writeDefaults(defaults);
            }
        } catch (IOException exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed preparing audit config {}: {}",
                CONFIG_PATH,
                exception.toString()
            );
            CURRENT = defaults;
            lastLoadedEpochMillis = System.currentTimeMillis();
            return CURRENT;
        }

        long modified = readModifiedMillis();
        if (!forced && modified >= 0L && modified == lastKnownModifiedMillis && lastLoadedEpochMillis > 0L) {
            lastLoadedEpochMillis = System.currentTimeMillis();
            return CURRENT;
        }

        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(CONFIG_PATH)) {
            properties.load(stream);
            CURRENT = parse(properties, defaults);
            lastKnownModifiedMillis = modified;
            lastLoadedEpochMillis = System.currentTimeMillis();
            VwgXwmBridgeClient.LOGGER.info(
                "[VWG->XWM Bridge][Trace] phase=AUDIT_CONFIG result=loaded path={} enabled={} interval_ticks={} max_regions_per_tick={} near_radius={} top_k={} loaded_ttl_ticks={} mca_header_refresh_ticks={} time_budget_micros={}",
                CONFIG_PATH,
                CURRENT.enabled(),
                CURRENT.intervalTicks(),
                CURRENT.maxRegionsPerTick(),
                CURRENT.nearRadius(),
                CURRENT.topK(),
                CURRENT.loadedTtlTicks(),
                CURRENT.mcaHeaderRefreshTicks(),
                CURRENT.timeBudgetMicros()
            );
            return CURRENT;
        } catch (IOException exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed loading audit config {}: {}",
                CONFIG_PATH,
                exception.toString()
            );
            CURRENT = defaults;
            lastLoadedEpochMillis = System.currentTimeMillis();
            return CURRENT;
        }
    }

    private static long readModifiedMillis() {
        try {
            return Files.getLastModifiedTime(CONFIG_PATH).toMillis();
        } catch (IOException ignored) {
            return -1L;
        }
    }

    private static Config parse(Properties properties, Config defaults) {
        boolean enabled = parseBoolean(properties, "enabled", defaults.enabled());
        int intervalTicks = parseInt(properties, "interval_ticks", defaults.intervalTicks(), 5, 1_200);
        int maxRegionsPerTick = parseInt(properties, "max_regions_per_tick", defaults.maxRegionsPerTick(), 1, 32);
        int nearRadius = parseInt(properties, "near_radius", defaults.nearRadius(), 0, 8);
        int topK = parseInt(properties, "top_k", defaults.topK(), 1, 32);
        int loadedTtlTicks = parseInt(properties, "loaded_ttl_ticks", defaults.loadedTtlTicks(), 100, 20_000);
        int mcaHeaderRefreshTicks = parseInt(properties, "mca_header_refresh_ticks", defaults.mcaHeaderRefreshTicks(), 20, 20_000);
        int timeBudgetMicros = parseInt(properties, "time_budget_micros", defaults.timeBudgetMicros(), 200, 20_000);

        return new Config(
            enabled,
            intervalTicks,
            maxRegionsPerTick,
            nearRadius,
            topK,
            loadedTtlTicks,
            mcaHeaderRefreshTicks,
            timeBudgetMicros
        );
    }

    private static void writeDefaults(Config defaults) throws IOException {
        Properties defaultsProperties = new Properties();
        defaultsProperties.setProperty("enabled", Boolean.toString(defaults.enabled()));
        defaultsProperties.setProperty("interval_ticks", Integer.toString(defaults.intervalTicks()));
        defaultsProperties.setProperty("max_regions_per_tick", Integer.toString(defaults.maxRegionsPerTick()));
        defaultsProperties.setProperty("near_radius", Integer.toString(defaults.nearRadius()));
        defaultsProperties.setProperty("top_k", Integer.toString(defaults.topK()));
        defaultsProperties.setProperty("loaded_ttl_ticks", Integer.toString(defaults.loadedTtlTicks()));
        defaultsProperties.setProperty("mca_header_refresh_ticks", Integer.toString(defaults.mcaHeaderRefreshTicks()));
        defaultsProperties.setProperty("time_budget_micros", Integer.toString(defaults.timeBudgetMicros()));

        try (OutputStream stream = Files.newOutputStream(CONFIG_PATH)) {
            defaultsProperties.store(
                stream,
                "VWG->Xaero Audit-only v1\n"
                    + "Low-overhead defaults. Adjust carefully if server thread is sensitive."
            );
        }
    }

    private static boolean parseBoolean(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return "1".equals(raw.trim()) || Boolean.parseBoolean(raw.trim());
    }

    private static int parseInt(Properties properties, String key, int fallback, int min, int max) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record Config(
        boolean enabled,
        int intervalTicks,
        int maxRegionsPerTick,
        int nearRadius,
        int topK,
        int loadedTtlTicks,
        int mcaHeaderRefreshTicks,
        int timeBudgetMicros
    ) {
        public static Config defaults() {
            return new Config(
                true,
                40,
                2,
                2,
                6,
                1_200,
                200,
                1_000
            );
        }
    }
}


