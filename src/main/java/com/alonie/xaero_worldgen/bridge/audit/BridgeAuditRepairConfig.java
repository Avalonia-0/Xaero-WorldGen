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

public final class BridgeAuditRepairConfig {
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("xaero-worldgen-audit-repair.properties");
    private static final long RELOAD_INTERVAL_MILLIS = 5_000L;
    private static volatile Config CURRENT = Config.defaults();
    private static volatile long lastLoadedEpochMillis = -1L;
    private static volatile long lastKnownModifiedMillis = -1L;

    private BridgeAuditRepairConfig() {
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
                "[VWG->XWM Bridge] Failed preparing audit-repair config {}: {}",
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
                "[VWG->XWM Bridge][Trace] phase=AUDIT_REPAIR_CONFIG result=loaded path={} enabled={} interval_ticks={} max_regions_per_tick={} near_radius={} top_k={} repair_cooldown_ticks={} time_budget_micros={} vanilla_only_repair_enabled={} vanilla_only_fallback_upgrade_enabled={}",
                CONFIG_PATH,
                CURRENT.enabled(),
                CURRENT.intervalTicks(),
                CURRENT.maxRegionsPerTick(),
                CURRENT.nearRadius(),
                CURRENT.topK(),
                CURRENT.repairCooldownTicks(),
                CURRENT.timeBudgetMicros(),
                CURRENT.vanillaOnlyRepairEnabled(),
                CURRENT.vanillaOnlyFallbackUpgradeEnabled()
            );
            return CURRENT;
        } catch (IOException exception) {
            VwgXwmBridgeClient.LOGGER.warn(
                "[VWG->XWM Bridge] Failed loading audit-repair config {}: {}",
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
        int topK = parseInt(properties, "top_k", defaults.topK(), 1, 64);
        int repairCooldownTicks = parseInt(properties, "repair_cooldown_ticks", defaults.repairCooldownTicks(), 20, 20_000);
        int timeBudgetMicros = parseInt(properties, "time_budget_micros", defaults.timeBudgetMicros(), 200, 20_000);
        boolean vanillaOnlyRepairEnabled = parseBoolean(
            properties,
            "vanilla_only_repair_enabled",
            defaults.vanillaOnlyRepairEnabled()
        );
        boolean vanillaOnlyFallbackUpgradeEnabled = parseBoolean(
            properties,
            "vanilla_only_fallback_upgrade_enabled",
            defaults.vanillaOnlyFallbackUpgradeEnabled()
        );

        return new Config(
            enabled,
            intervalTicks,
            maxRegionsPerTick,
            nearRadius,
            topK,
            repairCooldownTicks,
            timeBudgetMicros,
            vanillaOnlyRepairEnabled,
            vanillaOnlyFallbackUpgradeEnabled
        );
    }

    private static void writeDefaults(Config defaults) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(defaults.enabled()));
        properties.setProperty("interval_ticks", Integer.toString(defaults.intervalTicks()));
        properties.setProperty("max_regions_per_tick", Integer.toString(defaults.maxRegionsPerTick()));
        properties.setProperty("near_radius", Integer.toString(defaults.nearRadius()));
        properties.setProperty("top_k", Integer.toString(defaults.topK()));
        properties.setProperty("repair_cooldown_ticks", Integer.toString(defaults.repairCooldownTicks()));
        properties.setProperty("time_budget_micros", Integer.toString(defaults.timeBudgetMicros()));
        properties.setProperty("vanilla_only_repair_enabled", Boolean.toString(defaults.vanillaOnlyRepairEnabled()));
        properties.setProperty(
            "vanilla_only_fallback_upgrade_enabled",
            Boolean.toString(defaults.vanillaOnlyFallbackUpgradeEnabled())
        );
        try (OutputStream stream = Files.newOutputStream(CONFIG_PATH)) {
            properties.store(
                stream,
                "VWG->Xaero Audit-only v2 Repair\nLow-overhead defaults; tweak carefully."
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
        int repairCooldownTicks,
        int timeBudgetMicros,
        boolean vanillaOnlyRepairEnabled,
        boolean vanillaOnlyFallbackUpgradeEnabled
    ) {
        public static Config defaults() {
            return new Config(
                true,
                40,
                2,
                2,
                6,
                200,
                1_000,
                false,
                false
            );
        }
    }
}


