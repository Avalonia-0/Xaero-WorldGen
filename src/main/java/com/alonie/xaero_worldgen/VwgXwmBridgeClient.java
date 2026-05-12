package com.alonie.xaero_worldgen;

import com.alonie.xaero_worldgen.bridge.state.BridgeDirtyRegionStore;
import com.alonie.xaero_worldgen.bridge.audit.BridgeAuditConfig;
import com.alonie.xaero_worldgen.bridge.audit.BridgeAuditLogger;
import com.alonie.xaero_worldgen.bridge.audit.BridgeAuditRepairConfig;
import com.alonie.xaero_worldgen.bridge.audit.BridgeAuditRepairService;
import com.alonie.xaero_worldgen.bridge.state.BridgeAuditRepairCacheInvalidation;
import com.alonie.xaero_worldgen.bridge.state.BridgeBuildQualityTracker;
import com.alonie.xaero_worldgen.bridge.state.BridgeCacheInvalidationTracker;
import com.alonie.xaero_worldgen.bridge.state.BridgeFallbackMissTracker;
import com.alonie.xaero_worldgen.bridge.audit.BridgeMcaHeaderCache;
import com.alonie.xaero_worldgen.bridge.state.BridgeRegionBuildTracker;
import com.alonie.xaero_worldgen.bridge.audit.BridgeRegionAuditService;
import com.alonie.xaero_worldgen.bridge.migration.BridgeLegacyRegionQuarantine;
import com.alonie.xaero_worldgen.bridge.state.BridgeStateFlushService;
import com.alonie.xaero_worldgen.bridge.state.BridgeSuspectRetryTracker;
import com.alonie.xaero_worldgen.bridge.snapshot.BridgeChunkSnapshotStore;
import com.alonie.xaero_worldgen.bridge.snapshot.BridgeLiveCaptureQueue;
import com.alonie.xaero_worldgen.bridge.state.BridgeLoadLeaseTracker;
import com.alonie.xaero_worldgen.bridge.core.BridgePerfBudget;
import com.alonie.xaero_worldgen.bridge.state.BridgeXaeroLoadedChunkTracker;
import com.alonie.xaero_worldgen.bridge.integration.voxy.VoxyChunkReadinessTracker;
import com.alonie.xaero_worldgen.bridge.integration.voxy.VoxyChunkNbtProvider;
import com.alonie.xaero_worldgen.bridge.integration.voxy.VoxyGeneratedRegionIndex;
import com.alonie.xaero_worldgen.bridge.integration.voxy.VoxyRegionCoverageTracker;
import com.alonie.xaero_worldgen.bridge.snapshot.BridgeRegionHydrator;
import com.alonie.xaero_worldgen.bridge.snapshot.BridgeRegionReleaseManager;
import com.alonie.xaero_worldgen.bridge.policy.BridgeSourcePolicy;
import com.alonie.xaero_worldgen.bridge.integration.xaero.XaeroLiveRegionQueue;
import com.alonie.xaero_worldgen.util.RuntimeFingerprint;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VwgXwmBridgeClient implements ClientModInitializer {
    public static final String MOD_ID = "xaero-worldgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[VWG->XWM Bridge] Initializing runtime Voxy/Xaero bridge.");
        BridgeAuditConfig.load();
        BridgeAuditRepairConfig.load();
        String version = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        LOGGER.info(
            "[VWG->XWM Bridge] Runtime fingerprint: {}",
            RuntimeFingerprint.describe(VwgXwmBridgeClient.class, version)
        );
        LOGGER.info(
            "[VWG->XWM Bridge][Trace] phase=EXPERIMENT_SWITCHES result=active assist_min_retry_ticks={} vanilla_missing_chunk_fallback={}",
            BridgePerfBudget.ASSIST_MIN_RETRY_TICKS,
            BridgePerfBudget.VANILLA_MISSING_CHUNK_FALLBACK
        );

        ClientTickEvents.END_CLIENT_TICK.register(XaeroLiveRegionQueue::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> XaeroLiveRegionQueue.handleDisconnect(client));
        ServerTickEvents.END_SERVER_TICK.register(BridgeStateFlushService::flushDue);
        ServerTickEvents.END_SERVER_TICK.register(BridgeLiveCaptureQueue::tickServer);
        ServerTickEvents.END_SERVER_TICK.register(BridgeRegionHydrator::tickServer);
        ServerTickEvents.END_SERVER_TICK.register(BridgeRegionAuditService::tickServer);
        ServerTickEvents.END_SERVER_TICK.register(BridgeAuditRepairService::tickServer);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BridgeAuditLogger.startSession();
            for (var world : server.getWorlds()) {
                VoxyGeneratedRegionIndex.bootstrap(world);
                BridgeLegacyRegionQuarantine.scanAndQuarantine(world);
                BridgeStateFlushService.requestFlush(world);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BridgeStateFlushService.flushNow(server);
            XaeroLiveRegionQueue.clear();
            BridgeAuditLogger.closeSession();
            VoxyGeneratedRegionIndex.clearRuntimeState();
            VoxyChunkReadinessTracker.clearRuntimeState();
            VoxyChunkNbtProvider.clearRuntimeState();
            VoxyRegionCoverageTracker.clearRuntimeState();
            BridgeFallbackMissTracker.clearRuntimeState();
            BridgeBuildQualityTracker.clearRuntimeState();
            BridgeCacheInvalidationTracker.clearRuntimeState();
            BridgeAuditRepairCacheInvalidation.clearRuntimeState();
            BridgeSuspectRetryTracker.clearRuntimeState();
            BridgeRegionBuildTracker.clearRuntimeState();
            BridgeLoadLeaseTracker.clearRuntimeState();
            BridgeChunkSnapshotStore.clearRuntimeState();
            BridgeLiveCaptureQueue.clearRuntimeState();
            BridgeRegionReleaseManager.clearRuntimeState();
            BridgeSourcePolicy.clearRuntimeState();
            BridgeRegionHydrator.clearRuntimeState();
            BridgeDirtyRegionStore.clearRuntimeState();
            BridgeStateFlushService.clearRuntimeState();
            BridgeRegionAuditService.clearRuntimeState();
            BridgeXaeroLoadedChunkTracker.clearRuntimeState();
            BridgeMcaHeaderCache.clearRuntimeState();
            BridgeAuditLogger.clearRuntimeState();
        });
    }
}

