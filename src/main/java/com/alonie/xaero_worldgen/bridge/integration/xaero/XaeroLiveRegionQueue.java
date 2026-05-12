package com.alonie.xaero_worldgen.bridge.integration.xaero;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import com.alonie.xaero_worldgen.VwgXwmBridgeClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class XaeroLiveRegionQueue {
    private static final ConcurrentHashMap<String, PendingRegion> PENDING = new ConcurrentHashMap<>();
    private static final Set<String> BOOTSTRAPPED_RUNTIME_KEYS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong CLIENT_TICK_COUNTER = new AtomicLong();
    private static final int MAX_ACTIVE_LEASES = 3;
    private static final int MAX_NEAR_REQUESTS_PER_TICK = 2;
    private static final int SURFACE_CAVE_LAYER = Integer.MAX_VALUE;
    private static final int PLAYER_PRIORITY_RADIUS = 2;
    private static final long RETRY_INTERVAL_TICKS = 20L;
    private static final long ASSIST_INGEST_SETTLE_TICKS = 20L;
    private static final long ASSIST_RECENT_SUCCESS_SUPPRESS_TICKS = 120L;
    private static final long ASSIST_SINGLEFLIGHT_LOG_COOLDOWN_TICKS = 20L;
    private static final long HYDRATE_RETRY_TICKS = 40L;
    private static final long REQUEST_ACK_TIMEOUT_TICKS = 200L;
    private static final long MAX_TIMEOUT_BACKOFF_TICKS = 320L;
    private static final String REQUEST_REASON_BRIDGE = "bridge_live";
    private static final String REQUEST_REASON_ASSIST = "bridge_assist_vanilla";
    private static final Object TIMEOUT_TRACKER_LOCK = new Object();
    private static final ArrayDeque<Long> RECENT_TIMEOUT_TICKS = new ArrayDeque<>();
    private static final ConcurrentHashMap<String, AssistStallState> ASSIST_STALL_STATES = new ConcurrentHashMap<>();
    private static final int ASSIST_STALL_FAILURE_THRESHOLD = 3;
    private static final long ASSIST_STALL_QUIET_TICKS = 120L;
    private static final long ASSIST_STALL_EVENT_COOLDOWN_TICKS = 80L;

    private XaeroLiveRegionQueue() {
    }

    public static long currentTick() {
        return CLIENT_TICK_COUNTER.get();
    }

    public static void enqueue(ServerWorld world, int regionX, int regionZ) {
        if (world == null) {
            return;
        }
        BridgeRegionAuditService.touchRegion(world, regionX, regionZ, "request_enqueue");
        mergePending(PendingRegion.from(world, regionX, regionZ, CLIENT_TICK_COUNTER.get()));
    }

    public static void clear() {
        PENDING.clear();
        BOOTSTRAPPED_RUNTIME_KEYS.clear();
        CLIENT_TICK_COUNTER.set(0L);
        ASSIST_STALL_STATES.clear();
        synchronized (TIMEOUT_TRACKER_LOCK) {
            RECENT_TIMEOUT_TICKS.clear();
        }
    }

    public static void handleDisconnect(MinecraftClient client) {
        clear();
    }

    public static void tick(MinecraftClient client) {
        long currentTick = CLIENT_TICK_COUNTER.incrementAndGet();
        if (client == null) {
            return;
        }

        if (client.player == null || client.world == null) {
            clear();
            return;
        }

        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null || !session.isUsable()) {
            return;
        }

        MapProcessor mapProcessor = session.getMapProcessor();
        if (mapProcessor == null || mapProcessor.getMapSaveLoad() == null || !mapProcessor.getMapSaveLoad().isRegionDetectionComplete()) {
            return;
        }

        MapWorld mapWorld = mapProcessor.getMapWorld();
        if (mapWorld == null) {
            return;
        }

        MapDimension mapDimension = mapWorld.getCurrentDimension();
        if (mapDimension == null || !mapDimension.isUsingWorldSave()) {
            return;
        }

        IntegratedServer server = client.getServer();
        if (server == null || server.isStopping()) {
            clear();
            return;
        }

        ServerWorld serverWorld = server.getWorld(mapDimension.getDimId());
        if (serverWorld == null) {
            return;
        }

        int playerRegionX = client.player.getChunkPos().x >> 5;
        int playerRegionZ = client.player.getChunkPos().z >> 5;
        String runtimeCacheKey = BridgePaths.getRuntimeCacheKey(serverWorld);
        syncDirtyReplay(serverWorld, runtimeCacheKey);
        if (PENDING.isEmpty()) {
            return;
        }

        ArrayList<PendingRegion> orderedPending = orderedPending(runtimeCacheKey, playerRegionX, playerRegionZ);
        int requestBudget = currentRequestBudget(currentTick);
        int nearRequestBudget = Math.min(MAX_NEAR_REQUESTS_PER_TICK, Math.max(0, requestBudget - 1));
        int requestedCount = 0;
        int nearRequested = 0;
        for (PendingRegion snapshot : orderedPending) {
            if (requestedCount >= requestBudget) {
                break;
            }

            PendingRegion pendingRegion = PENDING.get(snapshot.key());
            if (pendingRegion == null || !pendingRegion.runtimeCacheKey().equals(runtimeCacheKey)) {
                continue;
            }

            pendingRegion.refreshFromDirtyStore(serverWorld);
            if (isTerminal(serverWorld, pendingRegion)) {
                PENDING.remove(pendingRegion.key(), pendingRegion);
                continue;
            }

            if (currentTick < pendingRegion.nextAttemptTick()) {
                continue;
            }

            boolean nearPlayer = isNearPlayer(pendingRegion.regionX(), pendingRegion.regionZ(), playerRegionX, playerRegionZ);
            if (nearPlayer && nearRequested >= nearRequestBudget && requestedCount < requestBudget - 1) {
                continue;
            }

            if (handleOutstandingRequest(mapProcessor, serverWorld, pendingRegion, currentTick)) {
                continue;
            }

            if (currentTick < pendingRegion.timeoutBackoffUntilTick()) {
                pendingRegion.scheduleRetry(pendingRegion.timeoutBackoffUntilTick());
                continue;
            }

            BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(
                serverWorld,
                pendingRegion.regionX(),
                pendingRegion.regionZ()
            );
            if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY) {
                if (BridgeLoadLeaseTracker.isLeaseActive(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), currentTick)) {
                    maybeLogAssistSingleflightSkip(
                        serverWorld,
                        pendingRegion,
                        currentTick,
                        "skip_active",
                        "reason=active_lease"
                    );
                    pendingRegion.scheduleRetry(currentTick + assistRetryTicks());
                    continue;
                }
                if (BridgeLoadLeaseTracker.countActive(runtimeCacheKey, currentTick) >= MAX_ACTIVE_LEASES) {
                    maybeLogAssistSingleflightSkip(
                        serverWorld,
                        pendingRegion,
                        currentTick,
                        "skip_active",
                        "reason=global_inflight_limit"
                    );
                    pendingRegion.scheduleRetry(currentTick + assistRetryTicks());
                    continue;
                }
                long ticksSinceLastDirtyTouch = Math.max(0L, currentTick - pendingRegion.lastTouchedTick());
                if (ticksSinceLastDirtyTouch < ASSIST_INGEST_SETTLE_TICKS) {
                    long waitTicks = ASSIST_INGEST_SETTLE_TICKS - ticksSinceLastDirtyTouch;
                    maybeLogAssistSingleflightSkip(
                        serverWorld,
                        pendingRegion,
                        currentTick,
                        "skip_ingest_settling",
                        "waitTicks=" + waitTicks + ",sinceDirtyTouch=" + ticksSinceLastDirtyTouch
                    );
                    pendingRegion.scheduleRetry(currentTick + Math.max(1L, waitTicks));
                    continue;
                }
                if (pendingRegion.shouldSkipRecentAssistSuccess(currentTick, ASSIST_RECENT_SUCCESS_SUPPRESS_TICKS)) {
                    long waitTicks = pendingRegion.remainingRecentSuccessSuppressTicks(
                        currentTick,
                        ASSIST_RECENT_SUCCESS_SUPPRESS_TICKS
                    );
                    maybeLogAssistSingleflightSkip(
                        serverWorld,
                        pendingRegion,
                        currentTick,
                        "skip_recent_success",
                        "requestVersion="
                            + pendingRegion.dirtyVersion()
                            + ",lastSuccessVersion="
                            + pendingRegion.lastAssistSuccessVersion()
                            + ",waitTicks="
                            + waitTicks
                    );
                    pendingRegion.scheduleRetry(currentTick + Math.max(1L, waitTicks));
                    continue;
                }

                RequestAttempt assistAttempt = requestVanillaAssistLoad(
                    mapProcessor,
                    mapDimension,
                    serverWorld,
                    pendingRegion.regionX(),
                    pendingRegion.regionZ()
                );
                if (assistAttempt.outcome() == RequestOutcome.REQUESTED_LOAD) {
                    pendingRegion.onLoadRequested(currentTick, assistAttempt.bridgeOwnedWritePrime(), assistAttempt.requestKind());
                    requestedCount++;
                    if (nearPlayer) {
                        nearRequested++;
                    }
                    continue;
                }

                pendingRegion.scheduleRetry(currentTick + assistRetryTicks());
                continue;
            }

            if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.BRIDGE_ONLY) {
                BridgeChunkSnapshotStore.tryCommitRegion(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ());
                if (!BridgeRegionReleaseManager.hasCurrentCommittedDirtyVersion(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ())) {
                    BridgeRegionHydrator.requestHydrate(
                        serverWorld,
                        pendingRegion.regionX(),
                        pendingRegion.regionZ(),
                        nearPlayer ? BridgeRegionHydrator.Priority.NEAR : BridgeRegionHydrator.Priority.FAR
                    );
                    pendingRegion.scheduleRetry(currentTick + HYDRATE_RETRY_TICKS);
                    continue;
                }

                if (applySuspectRetryGate(serverWorld, pendingRegion, currentTick)) {
                    continue;
                }
            } else {
                pendingRegion.scheduleRetry(currentTick + HYDRATE_RETRY_TICKS);
                continue;
            }

            if (BridgeLoadLeaseTracker.isLeaseActive(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), currentTick)
                || BridgeLoadLeaseTracker.countActive(runtimeCacheKey, currentTick) >= MAX_ACTIVE_LEASES) {
                pendingRegion.scheduleRetry(currentTick + RETRY_INTERVAL_TICKS);
                continue;
            }

            RequestAttempt bridgeAttempt = requestBridgeLoad(
                mapProcessor,
                mapDimension,
                serverWorld,
                pendingRegion.regionX(),
                pendingRegion.regionZ()
            );
            if (bridgeAttempt.outcome() == RequestOutcome.REQUESTED_LOAD) {
                pendingRegion.onLoadRequested(currentTick, bridgeAttempt.bridgeOwnedWritePrime(), bridgeAttempt.requestKind());
                long requestedVersion = pendingRegion.outstandingRequestVersion();
                if (requestedVersion > 0L && BridgeSuspectRetryTracker.hasActiveSuspect(
                    serverWorld,
                    pendingRegion.regionX(),
                    pendingRegion.regionZ(),
                    requestedVersion
                )) {
                    BridgeSuspectRetryTracker.onRetryRequested(
                        serverWorld,
                        pendingRegion.regionX(),
                        pendingRegion.regionZ(),
                        requestedVersion
                    );
                }
                requestedCount++;
                if (nearPlayer) {
                    nearRequested++;
                }
                continue;
            }

            pendingRegion.scheduleRetry(currentTick + bridgeRetryTicksForFailure(serverWorld, pendingRegion));
        }
    }

    private static boolean applySuspectRetryGate(ServerWorld serverWorld, PendingRegion pendingRegion, long currentTick) {
        long dirtyVersion = pendingRegion.dirtyVersion();
        if (dirtyVersion <= 0L) {
            return false;
        }

        if (!BridgeSuspectRetryTracker.hasActiveSuspect(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), dirtyVersion)) {
            return false;
        }

        long recommendedDelay = Math.max(
            RETRY_INTERVAL_TICKS,
            BridgeSuspectRetryTracker.recommendedRetryDelayTicks(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), dirtyVersion)
        );
        if (!BridgeSuspectRetryTracker.canIssueRetry(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), dirtyVersion)) {
            pendingRegion.scheduleRetry(currentTick + recommendedDelay);
            return true;
        }

        long lastRetryRequestTick = pendingRegion.lastBridgeRequestTickForVersion(dirtyVersion);
        if (lastRetryRequestTick >= 0L && currentTick - lastRetryRequestTick < recommendedDelay) {
            pendingRegion.scheduleRetry(lastRetryRequestTick + recommendedDelay);
            return true;
        }
        return false;
    }

    private static long bridgeRetryTicksForFailure(ServerWorld serverWorld, PendingRegion pendingRegion) {
        long dirtyVersion = pendingRegion.dirtyVersion();
        if (dirtyVersion > 0L && BridgeSuspectRetryTracker.hasActiveSuspect(
            serverWorld,
            pendingRegion.regionX(),
            pendingRegion.regionZ(),
            dirtyVersion
        )) {
            return Math.max(
                RETRY_INTERVAL_TICKS,
                BridgeSuspectRetryTracker.recommendedRetryDelayTicks(
                    serverWorld,
                    pendingRegion.regionX(),
                    pendingRegion.regionZ(),
                    dirtyVersion
                )
            );
        }
        return RETRY_INTERVAL_TICKS;
    }

    private static ArrayList<PendingRegion> orderedPending(String runtimeCacheKey, int playerRegionX, int playerRegionZ) {
        ArrayList<PendingRegion> near = new ArrayList<>();
        ArrayList<PendingRegion> far = new ArrayList<>();
        Comparator<PendingRegion> comparator = Comparator.comparingLong(PendingRegion::dirtyEpoch)
            .thenComparingLong(PendingRegion::lastDirtyEpoch)
            .thenComparingInt(PendingRegion::regionX)
            .thenComparingInt(PendingRegion::regionZ);

        for (PendingRegion pendingRegion : PENDING.values()) {
            if (!runtimeCacheKey.equals(pendingRegion.runtimeCacheKey())) {
                continue;
            }
            if (isNearPlayer(pendingRegion.regionX(), pendingRegion.regionZ(), playerRegionX, playerRegionZ)) {
                near.add(pendingRegion);
            } else {
                far.add(pendingRegion);
            }
        }

        near.sort(comparator);
        far.sort(comparator);

        ArrayList<PendingRegion> ordered = new ArrayList<>(near.size() + far.size());
        int nearSeed = Math.min(MAX_NEAR_REQUESTS_PER_TICK, near.size());
        for (int i = 0; i < nearSeed; i++) {
            ordered.add(near.get(i));
        }
        if (!far.isEmpty()) {
            ordered.add(far.get(0));
        }
        for (int i = nearSeed; i < near.size(); i++) {
            ordered.add(near.get(i));
        }
        for (int i = 1; i < far.size(); i++) {
            ordered.add(far.get(i));
        }
        return ordered;
    }

    private static void syncDirtyReplay(ServerWorld serverWorld, String runtimeCacheKey) {
        if (!BOOTSTRAPPED_RUNTIME_KEYS.add(runtimeCacheKey)) {
            return;
        }

        for (BridgeDirtyRegionStore.DirtyRegion dirtyRegion : BridgeDirtyRegionStore.snapshotDirtyRegions(serverWorld)) {
            enqueue(serverWorld, dirtyRegion.regionX(), dirtyRegion.regionZ());
        }
    }

    private static void mergePending(PendingRegion incoming) {
        PENDING.compute(incoming.key(), (ignored, existing) -> existing == null ? incoming : existing.touch(incoming));
    }

    private static boolean isTerminal(ServerWorld serverWorld, PendingRegion pendingRegion) {
        boolean dirty = BridgeDirtyRegionStore.isDirty(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ());
        if (!dirty) {
            BridgeSuspectRetryTracker.clearRegion(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ());
        }
        return !dirty;
    }

    private static boolean isNearPlayer(int regionX, int regionZ, int playerRegionX, int playerRegionZ) {
        return Math.abs(regionX - playerRegionX) <= PLAYER_PRIORITY_RADIUS
            && Math.abs(regionZ - playerRegionZ) <= PLAYER_PRIORITY_RADIUS;
    }

    private static boolean handleOutstandingRequest(
        MapProcessor mapProcessor,
        ServerWorld serverWorld,
        PendingRegion pendingRegion,
        long currentTick
    ) {
        if (!pendingRegion.hasOutstandingRequest()) {
            return false;
        }

        BridgeLoadLeaseTracker.RequestStatus requestStatus = BridgeLoadLeaseTracker.getLeaseStatus(
            serverWorld,
            pendingRegion.regionX(),
            pendingRegion.regionZ(),
            currentTick
        );
        if (pendingRegion.outstandingRequestKind() == RequestKind.VANILLA_ASSIST_LOAD) {
            return handleOutstandingAssistRequest(mapProcessor, serverWorld, pendingRegion, requestStatus, currentTick);
        }

        if (requestStatus != null && (requestStatus.loadStartTick() >= 0L || requestStatus.buildStartTick() >= 0L)) {
            BridgeRegionAuditService.touchRegion(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), "request_ack");
            pendingRegion.markRequestAcknowledged();
            pendingRegion.clearOutstandingRequest();
            return false;
        }

        long requestAge = currentTick - pendingRegion.outstandingRequestTick();
        if (requestAge < REQUEST_ACK_TIMEOUT_TICKS) {
            pendingRegion.scheduleRetry(currentTick + RETRY_INTERVAL_TICKS);
            return true;
        }

        releaseBridgeOwnedWriteFlag(mapProcessor, pendingRegion);
        pendingRegion.markLoadRequestTimedOut(currentTick);
        RequestKind timedOutRequestKind = pendingRegion.outstandingRequestKind();
        long timedOutDirtyVersion = pendingRegion.outstandingRequestVersion();
        pendingRegion.clearOutstandingRequest();
        recordTimeout(currentTick);
        if (timedOutRequestKind == RequestKind.VANILLA_ASSIST_LOAD) {
            logAssistEvent(
                "ASSIST_LOAD_TIMEOUT",
                serverWorld,
                pendingRegion.regionX(),
                pendingRegion.regionZ(),
                "dirtyVersion=" + timedOutDirtyVersion + ",ageTicks=" + requestAge
            );
            BridgeRegionAuditService.touchRegion(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), "request_timeout");
            logAssistLease(
                serverWorld,
                pendingRegion.regionX(),
                pendingRegion.regionZ(),
                "timeout",
                timedOutDirtyVersion,
                "ageTicks=" + requestAge
            );
        } else {
            BridgeRegionAuditService.touchRegion(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), "request_timeout");
            BridgeSuspectRetryTracker.markSuspect(
                serverWorld,
                pendingRegion.regionX(),
                pendingRegion.regionZ(),
                pendingRegion.dirtyVersion(),
                "lease_timeout"
            );
        }
        if (requestStatus != null) {
            BridgeLoadLeaseTracker.completeLease(
                serverWorld,
                pendingRegion.regionX(),
                pendingRegion.regionZ(),
                BridgeLoadLeaseTracker.TerminalState.TIMEOUT,
                currentTick,
                "queue_outstanding_timeout"
            );
        }
        return false;
    }

    private static boolean handleOutstandingAssistRequest(
        MapProcessor mapProcessor,
        ServerWorld serverWorld,
        PendingRegion pendingRegion,
        BridgeLoadLeaseTracker.RequestStatus requestStatus,
        long currentTick
    ) {
        if (requestStatus != null) {
            if (requestStatus.started() && !pendingRegion.outstandingRequestStarted()) {
                logAssistLease(
                    serverWorld,
                    pendingRegion.regionX(),
                    pendingRegion.regionZ(),
                    "started",
                    pendingRegion.outstandingRequestVersion(),
                    "loadStartTick=" + requestStatus.loadStartTick()
                        + ",buildStartTick="
                        + requestStatus.buildStartTick()
                        + ",startedTick="
                        + requestStatus.startedTick()
                );
                logAssistEvent(
                    "ASSIST_LOAD_ACK",
                    serverWorld,
                    pendingRegion.regionX(),
                    pendingRegion.regionZ(),
                    "ack=started,dirtyVersion=" + pendingRegion.outstandingRequestVersion()
                );
                BridgeRegionAuditService.touchRegion(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), "request_started");
                pendingRegion.markRequestStarted();
            }

            if (requestStatus.terminal()) {
                BridgeLoadLeaseTracker.TerminalState terminalState = requestStatus.terminalState();
                long requestVersion = pendingRegion.outstandingRequestVersion();
                long requestAge = currentTick - pendingRegion.outstandingRequestTick();
                emitAssistLoadResult(
                    serverWorld,
                    pendingRegion.regionX(),
                    pendingRegion.regionZ(),
                    requestVersion,
                    requestStatus.loadResult()
                );
                releaseBridgeOwnedWriteFlag(mapProcessor, pendingRegion);
                pendingRegion.clearOutstandingRequest();
                if (terminalState == BridgeLoadLeaseTracker.TerminalState.SUCCESS) {
                    pendingRegion.markRequestAcknowledged();
                    pendingRegion.recordAssistSuccess(
                        requestVersion,
                        pendingRegion.lastDirtyEpoch(),
                        currentTick
                    );
                    recordAssistTerminal(
                        serverWorld,
                        pendingRegion.regionX(),
                        pendingRegion.regionZ(),
                        BridgeLoadLeaseTracker.TerminalState.SUCCESS,
                        currentTick,
                        requestVersion
                    );
                    logAssistLease(
                        serverWorld,
                        pendingRegion.regionX(),
                        pendingRegion.regionZ(),
                        "terminal_success",
                        requestVersion,
                        "terminalTick="
                            + requestStatus.terminalTick()
                            + ",source="
                            + requestStatus.terminalSource()
                    );
                } else {
                    BridgeLoadLeaseTracker.TerminalState effectiveState = terminalState == BridgeLoadLeaseTracker.TerminalState.TIMEOUT
                        ? BridgeLoadLeaseTracker.TerminalState.TIMEOUT
                        : BridgeLoadLeaseTracker.TerminalState.FAIL;
                    recordAssistTerminal(
                        serverWorld,
                        pendingRegion.regionX(),
                        pendingRegion.regionZ(),
                        effectiveState,
                        currentTick,
                        requestVersion
                    );
                    if (terminalState == BridgeLoadLeaseTracker.TerminalState.TIMEOUT) {
                        recordTimeout(currentTick);
                        logAssistEvent(
                            "ASSIST_LOAD_TIMEOUT",
                            serverWorld,
                            pendingRegion.regionX(),
                            pendingRegion.regionZ(),
                            "dirtyVersion=" + requestVersion + ",ageTicks=" + requestAge
                        );
                        BridgeRegionAuditService.touchRegion(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), "request_timeout");
                    } else {
                        BridgeRegionAuditService.touchRegion(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), "request_fail");
                    }
                    logAssistLease(
                        serverWorld,
                        pendingRegion.regionX(),
                        pendingRegion.regionZ(),
                        terminalState == BridgeLoadLeaseTracker.TerminalState.TIMEOUT ? "terminal_timeout" : "terminal_fail",
                        requestVersion,
                        "terminalState=" + terminalState.name().toLowerCase()
                            + ",terminalTick="
                            + requestStatus.terminalTick()
                            + ",source="
                            + requestStatus.terminalSource()
                    );
                }
                pendingRegion.scheduleRetry(currentTick + assistRetryTicks());
                return true;
            }

            // For assist requests we wait for terminal callbacks instead of timing out at "started".
            if (requestStatus.started()) {
                pendingRegion.scheduleRetry(currentTick + RETRY_INTERVAL_TICKS);
                return true;
            }
        }

        long requestAge = currentTick - pendingRegion.outstandingRequestTick();
        if (requestAge < REQUEST_ACK_TIMEOUT_TICKS) {
            pendingRegion.scheduleRetry(currentTick + RETRY_INTERVAL_TICKS);
            return true;
        }

        releaseBridgeOwnedWriteFlag(mapProcessor, pendingRegion);
        pendingRegion.markLoadRequestTimedOut(currentTick);
        long timedOutDirtyVersion = pendingRegion.outstandingRequestVersion();
        pendingRegion.clearOutstandingRequest();
        recordTimeout(currentTick);
        logAssistEvent(
            "ASSIST_LOAD_TIMEOUT",
            serverWorld,
            pendingRegion.regionX(),
            pendingRegion.regionZ(),
            "dirtyVersion=" + timedOutDirtyVersion + ",ageTicks=" + requestAge
        );
        BridgeRegionAuditService.touchRegion(serverWorld, pendingRegion.regionX(), pendingRegion.regionZ(), "request_timeout");
        logAssistLease(
            serverWorld,
            pendingRegion.regionX(),
            pendingRegion.regionZ(),
            "terminal_timeout",
            timedOutDirtyVersion,
            "ageTicks=" + requestAge + ",source=outstanding_timeout"
        );
        boolean hadLeaseStatus = requestStatus != null;
        BridgeLoadLeaseTracker.completeLease(
            serverWorld,
            pendingRegion.regionX(),
            pendingRegion.regionZ(),
            BridgeLoadLeaseTracker.TerminalState.TIMEOUT,
            currentTick,
            "assist_outstanding_timeout"
        );
        if (!hadLeaseStatus) {
            BridgeFallbackEscalationPolicy.onAssistLeaseTerminal(
                serverWorld,
                pendingRegion.regionX(),
                pendingRegion.regionZ(),
                BridgeLoadLeaseTracker.TerminalState.TIMEOUT
            );
        }
        recordAssistTerminal(
            serverWorld,
            pendingRegion.regionX(),
            pendingRegion.regionZ(),
            BridgeLoadLeaseTracker.TerminalState.TIMEOUT,
            currentTick,
            timedOutDirtyVersion
        );
        return true;
    }

    private static void emitAssistLoadResult(
        ServerWorld world,
        int regionX,
        int regionZ,
        long dirtyVersion,
        BridgeLoadLeaseTracker.LoadResult loadResult
    ) {
        if (world == null) {
            return;
        }
        String result = switch (loadResult) {
            case HIT -> "hit";
            case MISS -> "miss";
            case UNKNOWN -> "unknown";
        };
        logAssistEvent(
            "ASSIST_LOAD_RESULT",
            world,
            regionX,
            regionZ,
            "result=" + result + ",dirtyVersion=" + dirtyVersion
        );
    }

    private static int currentRequestBudget(long currentTick) {
        int budget = BridgePerfBudget.LOAD_REQUESTS_PER_TICK;
        if (recentTimeoutCount(currentTick) >= BridgePerfBudget.RECENT_TIMEOUT_THROTTLE_THRESHOLD) {
            budget = Math.min(budget, BridgePerfBudget.LOAD_REQUESTS_PER_TICK_THROTTLED);
        }
        return Math.max(1, budget);
    }

    private static void recordTimeout(long currentTick) {
        synchronized (TIMEOUT_TRACKER_LOCK) {
            purgeStaleTimeouts(currentTick);
            RECENT_TIMEOUT_TICKS.addLast(currentTick);
        }
    }

    private static int recentTimeoutCount(long currentTick) {
        synchronized (TIMEOUT_TRACKER_LOCK) {
            purgeStaleTimeouts(currentTick);
            return RECENT_TIMEOUT_TICKS.size();
        }
    }

    private static void purgeStaleTimeouts(long currentTick) {
        while (!RECENT_TIMEOUT_TICKS.isEmpty()) {
            long timeoutTick = RECENT_TIMEOUT_TICKS.peekFirst();
            if (currentTick - timeoutTick <= BridgePerfBudget.RECENT_TIMEOUT_WINDOW_TICKS) {
                return;
            }
            RECENT_TIMEOUT_TICKS.removeFirst();
        }
    }

    private static void releaseBridgeOwnedWriteFlag(MapProcessor mapProcessor, PendingRegion pendingRegion) {
        if (!pendingRegion.outstandingBridgeOwnedWritePrime()) {
            return;
        }

        MapRegion region = mapProcessor.getLeafMapRegion(SURFACE_CAVE_LAYER, pendingRegion.regionX(), pendingRegion.regionZ(), false);
        if (region == null) {
            region = mapProcessor.getLeafMapRegion(SURFACE_CAVE_LAYER, pendingRegion.regionX(), pendingRegion.regionZ(), true);
        }
        if (region != null && region.isBeingWritten()) {
            region.setBeingWritten(false);
        }
    }

    private static RequestAttempt requestBridgeLoad(
        MapProcessor mapProcessor,
        MapDimension mapDimension,
        ServerWorld serverWorld,
        int regionX,
        int regionZ
    ) {
        BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(serverWorld, regionX, regionZ);
        if (sourcePolicy != BridgeSourcePolicy.SourcePolicy.BRIDGE_ONLY) {
            if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY) {
                BridgeSourcePolicy.recordMixedSourceGuardHit(serverWorld, regionX, regionZ, "request_load_on_vanilla_only");
            }
            return RequestAttempt.policyBlocked(RequestKind.BRIDGE_LOAD);
        }

        MapRegion region = mapProcessor.getLeafMapRegion(SURFACE_CAVE_LAYER, regionX, regionZ, false);
        if (region == null) {
            region = mapProcessor.getLeafMapRegion(SURFACE_CAVE_LAYER, regionX, regionZ, true);
        }
        if (region == null) {
            return RequestAttempt.missing(RequestKind.BRIDGE_LOAD);
        }

        if (!XaeroBridgeSupport.ensureReleasedBridgeSource(region)) {
            return RequestAttempt.notReleased();
        }

        String worldId = mapProcessor.getCurrentWorldId();
        String dimId = mapProcessor.getCurrentDimId();
        String mwId = mapProcessor.getCurrentMWId();
        if (worldId == null || dimId == null || mwId == null) {
            return RequestAttempt.missing(RequestKind.BRIDGE_LOAD);
        }
        if (!XaeroBridgeSupport.ensureBridgeDetection(mapProcessor, mapDimension, serverWorld, worldId, dimId, mwId, regionX, regionZ)) {
            return RequestAttempt.missing(RequestKind.BRIDGE_LOAD);
        }

        boolean bridgeOwnedWritePrime = primeRegionForLoad(region, true);
        mapProcessor.getMapSaveLoad().requestLoad(region, REQUEST_REASON_BRIDGE, true);
        long requestTick = currentTick();
        long requestedDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(serverWorld, regionX, regionZ);
        if (requestedDirtyVersion <= 0L) {
            return RequestAttempt.notReady(RequestKind.BRIDGE_LOAD);
        }
        BridgeLoadLeaseTracker.requestLease(
            serverWorld,
            regionX,
            regionZ,
            requestedDirtyVersion,
            requestTick,
            bridgeOwnedWritePrime,
            BridgeLoadLeaseTracker.LeaseKind.BRIDGE_LOAD
        );
        BridgeRegionBuildTracker.recordLoadRequested(serverWorld, regionX, regionZ, requestTick, bridgeOwnedWritePrime);
        BridgeRegionAuditService.touchRegion(serverWorld, regionX, regionZ, "request_load");
        return RequestAttempt.requested(RequestKind.BRIDGE_LOAD, bridgeOwnedWritePrime);
    }

    private static RequestAttempt requestVanillaAssistLoad(
        MapProcessor mapProcessor,
        MapDimension mapDimension,
        ServerWorld serverWorld,
        int regionX,
        int regionZ
    ) {
        if (!BridgeSourcePolicy.allowsVanillaAssistLoad(serverWorld, regionX, regionZ)) {
            return RequestAttempt.policyBlocked(RequestKind.VANILLA_ASSIST_LOAD);
        }

        MapRegion region = mapProcessor.getLeafMapRegion(SURFACE_CAVE_LAYER, regionX, regionZ, false);
        if (region == null) {
            region = mapProcessor.getLeafMapRegion(SURFACE_CAVE_LAYER, regionX, regionZ, true);
        }
        if (region == null) {
            return RequestAttempt.missing(RequestKind.VANILLA_ASSIST_LOAD);
        }

        String worldId = mapProcessor.getCurrentWorldId();
        String dimId = mapProcessor.getCurrentDimId();
        String mwId = mapProcessor.getCurrentMWId();
        if (worldId == null || dimId == null || mwId == null) {
            return RequestAttempt.missing(RequestKind.VANILLA_ASSIST_LOAD);
        }
        if (!XaeroBridgeSupport.ensureVanillaDetection(mapProcessor, mapDimension, serverWorld, worldId, dimId, mwId, regionX, regionZ)) {
            return RequestAttempt.missing(RequestKind.VANILLA_ASSIST_LOAD);
        }

        boolean bridgeOwnedWritePrime = primeRegionForLoad(region, true);
        mapProcessor.getMapSaveLoad().requestLoad(region, REQUEST_REASON_ASSIST, true);
        long requestTick = currentTick();
        long requestedDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(serverWorld, regionX, regionZ);
        if (requestedDirtyVersion <= 0L) {
            return RequestAttempt.notReady(RequestKind.VANILLA_ASSIST_LOAD);
        }
        BridgeLoadLeaseTracker.requestLease(
            serverWorld,
            regionX,
            regionZ,
            requestedDirtyVersion,
            requestTick,
            bridgeOwnedWritePrime,
            BridgeLoadLeaseTracker.LeaseKind.VANILLA_ASSIST_LOAD
        );
        BridgeRegionBuildTracker.recordLoadRequested(serverWorld, regionX, regionZ, requestTick, bridgeOwnedWritePrime);
        logAssistEvent(
            "ASSIST_LOAD_REQUESTED",
            serverWorld,
            regionX,
            regionZ,
            "dirtyVersion=" + requestedDirtyVersion
        );
        BridgeRegionAuditService.touchRegion(serverWorld, regionX, regionZ, "request_load");
        logAssistLease(
            serverWorld,
            regionX,
            regionZ,
            "requested",
            requestedDirtyVersion,
            "bridgeOwnedWritePrime=" + bridgeOwnedWritePrime
        );
        return RequestAttempt.requested(RequestKind.VANILLA_ASSIST_LOAD, bridgeOwnedWritePrime);
    }

    private static boolean primeRegionForLoad(MapRegion region, boolean allowBridgeOwnedWritePrime) {
        boolean bridgeOwnedWritePrime = allowBridgeOwnedWritePrime && !region.isBeingWritten();
        region.setSaveExists(Boolean.TRUE);
        if (region.getLoadState() != 4) {
            region.setLoadState((byte) 4);
        }
        if (bridgeOwnedWritePrime) {
            region.setBeingWritten(true);
        }
        region.setHasHadTerrain();
        return bridgeOwnedWritePrime;
    }

    private static void logAssistEvent(
        String phase,
        ServerWorld world,
        int regionX,
        int regionZ,
        String details
    ) {
        if (world == null || phase == null || phase.isBlank()) {
            return;
        }

        VwgXwmBridgeClient.LOGGER.info(
            "[VWG->XWM Bridge][Trace] phase={} dim={} regionX={} regionZ={} {}",
            phase,
            world.getRegistryKey().getValue(),
            regionX,
            regionZ,
            details == null ? "" : details
        );
    }

    private static void logAssistLease(
        ServerWorld world,
        int regionX,
        int regionZ,
        String state,
        long dirtyVersion,
        String details
    ) {
        if (world == null) {
            return;
        }

        StringBuilder payload = new StringBuilder("state=").append(state).append(",dirtyVersion=").append(dirtyVersion);
        if (details != null && !details.isBlank()) {
            payload.append(",").append(details);
        }
        logAssistEvent("ASSIST_LEASE", world, regionX, regionZ, payload.toString());
    }

    private static void maybeLogAssistSingleflightSkip(
        ServerWorld world,
        PendingRegion pendingRegion,
        long currentTick,
        String result,
        String details
    ) {
        if (world == null || pendingRegion == null) {
            return;
        }
        if (!pendingRegion.shouldLogSingleflight(result, currentTick, ASSIST_SINGLEFLIGHT_LOG_COOLDOWN_TICKS)) {
            return;
        }
        logAssistEvent(
            "ASSIST_SINGLEFLIGHT",
            world,
            pendingRegion.regionX(),
            pendingRegion.regionZ(),
            "result=" + result + (details == null || details.isBlank() ? "" : "," + details)
        );
    }

    private static void recordAssistTerminal(
        ServerWorld world,
        int regionX,
        int regionZ,
        BridgeLoadLeaseTracker.TerminalState terminalState,
        long currentTick,
        long dirtyVersion
    ) {
        if (world == null || terminalState == null) {
            return;
        }

        String key = BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
        AssistStallState state = ASSIST_STALL_STATES.computeIfAbsent(key, ignored -> new AssistStallState());
        if (terminalState == BridgeLoadLeaseTracker.TerminalState.SUCCESS) {
            state.onSuccess(currentTick);
            return;
        }

        if (terminalState != BridgeLoadLeaseTracker.TerminalState.TIMEOUT
            && terminalState != BridgeLoadLeaseTracker.TerminalState.FAIL
            && terminalState != BridgeLoadLeaseTracker.TerminalState.STALE) {
            return;
        }

        AssistStallSignal signal = state.onFailure(currentTick, terminalState);
        if (!signal.stalled()) {
            return;
        }

        logAssistEvent(
            "ASSIST_STALL",
            world,
            regionX,
            regionZ,
            "dirtyVersion="
                + dirtyVersion
                + ",reason="
                + signal.reason()
                + ",consecutiveFailures="
                + signal.consecutiveFailures()
                + ",sinceSuccessTicks="
                + signal.sinceSuccessTicks()
        );
        BridgeRegionAuditService.touchRegion(world, regionX, regionZ, "assist_stall");
    }

    private static long assistRetryTicks() {
        return Math.max(RETRY_INTERVAL_TICKS, BridgePerfBudget.ASSIST_MIN_RETRY_TICKS);
    }

    private enum RequestOutcome {
        REQUESTED_LOAD,
        MISSING_REGION,
        NOT_RELEASED,
        POLICY_BLOCKED,
        NOT_READY
    }

    private enum RequestKind {
        BRIDGE_LOAD,
        VANILLA_ASSIST_LOAD
    }

    private record RequestAttempt(RequestOutcome outcome, RequestKind requestKind, boolean bridgeOwnedWritePrime) {
        private static RequestAttempt requested(RequestKind requestKind, boolean bridgeOwnedWritePrime) {
            return new RequestAttempt(RequestOutcome.REQUESTED_LOAD, requestKind, bridgeOwnedWritePrime);
        }

        private static RequestAttempt missing(RequestKind requestKind) {
            return new RequestAttempt(RequestOutcome.MISSING_REGION, requestKind, false);
        }

        private static RequestAttempt notReady(RequestKind requestKind) {
            return new RequestAttempt(RequestOutcome.NOT_READY, requestKind, false);
        }

        private static RequestAttempt policyBlocked(RequestKind requestKind) {
            return new RequestAttempt(RequestOutcome.POLICY_BLOCKED, requestKind, false);
        }

        private static RequestAttempt notReleased() {
            return new RequestAttempt(RequestOutcome.NOT_RELEASED, RequestKind.BRIDGE_LOAD, false);
        }
    }

    private record AssistStallSignal(boolean stalled, int consecutiveFailures, long sinceSuccessTicks, String reason) {
    }

    private static final class AssistStallState {
        private int consecutiveFailures;
        private long lastSuccessTick = -1L;
        private long lastEventTick = -1L;

        private synchronized void onSuccess(long tick) {
            consecutiveFailures = 0;
            lastSuccessTick = tick;
        }

        private synchronized AssistStallSignal onFailure(long tick, BridgeLoadLeaseTracker.TerminalState state) {
            consecutiveFailures++;
            long sinceSuccess = lastSuccessTick < 0L ? Long.MAX_VALUE : Math.max(0L, tick - lastSuccessTick);
            boolean quietEnough = sinceSuccess >= ASSIST_STALL_QUIET_TICKS;
            boolean cooldownReady = lastEventTick < 0L || tick - lastEventTick >= ASSIST_STALL_EVENT_COOLDOWN_TICKS;
            boolean stalled = consecutiveFailures >= ASSIST_STALL_FAILURE_THRESHOLD && quietEnough && cooldownReady;
            if (stalled) {
                lastEventTick = tick;
            }
            return new AssistStallSignal(
                stalled,
                consecutiveFailures,
                sinceSuccess,
                state == null ? "unknown" : state.name().toLowerCase()
            );
        }
    }

    private static final class PendingRegion {
        private final String runtimeCacheKey;
        private final int regionX;
        private final int regionZ;
        private final String key;
        private volatile long dirtyEpoch;
        private volatile long lastDirtyEpoch;
        private volatile long dirtyVersion;
        private volatile long lastTouchedTick;
        private volatile long nextAttemptTick;
        private volatile long outstandingRequestVersion;
        private volatile long outstandingRequestTick;
        private volatile RequestKind outstandingRequestKind;
        private volatile boolean outstandingBridgeOwnedWritePrime;
        private volatile boolean outstandingRequestStarted;
        private volatile long lastBridgeRequestVersion;
        private volatile long lastBridgeRequestTick;
        private volatile long timeoutBackoffUntilTick;
        private volatile int consecutiveTimeouts;
        private volatile long lastAssistSuccessVersion;
        private volatile long lastAssistSuccessTick;
        private volatile long lastAssistSuccessDirtyEpoch;
        private volatile long lastSingleflightLogTick;
        private volatile String lastSingleflightLogResult;

        private PendingRegion(
            String runtimeCacheKey,
            int regionX,
            int regionZ,
            long dirtyEpoch,
            long lastDirtyEpoch,
            long dirtyVersion,
            long currentTick
        ) {
            this.runtimeCacheKey = runtimeCacheKey;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.key = runtimeCacheKey + "|" + regionX + "|" + regionZ;
            this.dirtyEpoch = dirtyEpoch;
            this.lastDirtyEpoch = lastDirtyEpoch;
            this.dirtyVersion = dirtyVersion;
            this.lastTouchedTick = currentTick;
            this.nextAttemptTick = currentTick;
            this.outstandingRequestVersion = -1L;
            this.outstandingRequestTick = -1L;
            this.outstandingRequestKind = null;
            this.outstandingBridgeOwnedWritePrime = false;
            this.outstandingRequestStarted = false;
            this.lastBridgeRequestVersion = -1L;
            this.lastBridgeRequestTick = -1L;
            this.timeoutBackoffUntilTick = 0L;
            this.consecutiveTimeouts = 0;
            this.lastAssistSuccessVersion = -1L;
            this.lastAssistSuccessTick = -1L;
            this.lastAssistSuccessDirtyEpoch = -1L;
            this.lastSingleflightLogTick = -1L;
            this.lastSingleflightLogResult = "";
        }

        private static PendingRegion from(ServerWorld world, int regionX, int regionZ, long currentTick) {
            long dirtyEpoch = BridgeDirtyRegionStore.getDirtyEpoch(world, regionX, regionZ);
            if (dirtyEpoch <= 0L) {
                dirtyEpoch = System.currentTimeMillis();
            }

            long lastDirtyEpoch = BridgeDirtyRegionStore.getLastDirtyEpoch(world, regionX, regionZ);
            if (lastDirtyEpoch <= 0L) {
                lastDirtyEpoch = dirtyEpoch;
            }

            long dirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
            return new PendingRegion(
                BridgePaths.getRuntimeCacheKey(world),
                regionX,
                regionZ,
                dirtyEpoch,
                lastDirtyEpoch,
                dirtyVersion,
                currentTick
            );
        }

        private PendingRegion touch(PendingRegion incoming) {
            this.dirtyEpoch = Math.min(this.dirtyEpoch, incoming.dirtyEpoch);
            this.lastDirtyEpoch = Math.max(this.lastDirtyEpoch, incoming.lastDirtyEpoch);
            this.dirtyVersion = Math.max(this.dirtyVersion, incoming.dirtyVersion);
            this.lastTouchedTick = Math.max(this.lastTouchedTick, incoming.lastTouchedTick);
            return this;
        }

        private void refreshFromDirtyStore(ServerWorld world) {
            long latestDirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
            if (latestDirtyVersion > this.dirtyVersion) {
                this.dirtyVersion = latestDirtyVersion;
                this.lastBridgeRequestVersion = -1L;
                this.lastBridgeRequestTick = -1L;
            }

            long latestLastDirtyEpoch = BridgeDirtyRegionStore.getLastDirtyEpoch(world, regionX, regionZ);
            if (latestLastDirtyEpoch > this.lastDirtyEpoch) {
                this.lastDirtyEpoch = latestLastDirtyEpoch;
            }

            long latestFirstDirtyEpoch = BridgeDirtyRegionStore.getDirtyEpoch(world, regionX, regionZ);
            if (latestFirstDirtyEpoch > 0L && (this.dirtyEpoch <= 0L || latestFirstDirtyEpoch < this.dirtyEpoch)) {
                this.dirtyEpoch = latestFirstDirtyEpoch;
            }
        }

        private void onLoadRequested(long currentTick, boolean bridgeOwnedWritePrime, RequestKind requestKind) {
            this.outstandingRequestVersion = this.dirtyVersion;
            this.outstandingRequestTick = currentTick;
            this.outstandingRequestKind = requestKind;
            this.outstandingBridgeOwnedWritePrime = bridgeOwnedWritePrime;
            this.outstandingRequestStarted = false;
            if (requestKind == RequestKind.BRIDGE_LOAD) {
                this.lastBridgeRequestVersion = this.outstandingRequestVersion;
                this.lastBridgeRequestTick = currentTick;
            }
        }

        private void markRequestAcknowledged() {
            this.consecutiveTimeouts = 0;
            this.timeoutBackoffUntilTick = 0L;
            this.outstandingRequestStarted = false;
        }

        private void markRequestStarted() {
            this.outstandingRequestStarted = true;
        }

        private void markLoadRequestTimedOut(long currentTick) {
            this.consecutiveTimeouts++;
            this.timeoutBackoffUntilTick = currentTick + timeoutBackoffTicks();
        }

        private long timeoutBackoffTicks() {
            if (consecutiveTimeouts <= 0) {
                return RETRY_INTERVAL_TICKS;
            }

            long base = RETRY_INTERVAL_TICKS << Math.min(consecutiveTimeouts - 1, 4);
            return Math.min(MAX_TIMEOUT_BACKOFF_TICKS, Math.max(RETRY_INTERVAL_TICKS, base));
        }

        private void scheduleRetry(long nextAttemptTick) {
            this.nextAttemptTick = nextAttemptTick;
        }

        private void recordAssistSuccess(long dirtyVersion, long lastDirtyEpoch, long successTick) {
            this.lastAssistSuccessVersion = dirtyVersion;
            this.lastAssistSuccessDirtyEpoch = lastDirtyEpoch;
            this.lastAssistSuccessTick = successTick;
        }

        private boolean shouldSkipRecentAssistSuccess(long currentTick, long suppressWindowTicks) {
            if (suppressWindowTicks <= 0L || this.lastAssistSuccessTick < 0L) {
                return false;
            }
            if (this.lastAssistSuccessVersion <= 0L || this.lastAssistSuccessVersion != this.dirtyVersion) {
                return false;
            }
            if (this.lastDirtyEpoch > this.lastAssistSuccessDirtyEpoch) {
                return false;
            }
            return currentTick - this.lastAssistSuccessTick < suppressWindowTicks;
        }

        private long remainingRecentSuccessSuppressTicks(long currentTick, long suppressWindowTicks) {
            if (suppressWindowTicks <= 0L || this.lastAssistSuccessTick < 0L) {
                return 0L;
            }
            long elapsed = Math.max(0L, currentTick - this.lastAssistSuccessTick);
            return Math.max(0L, suppressWindowTicks - elapsed);
        }

        private boolean shouldLogSingleflight(String result, long currentTick, long cooldownTicks) {
            String normalized = result == null ? "unknown" : result;
            if (normalized.equals(this.lastSingleflightLogResult)
                && this.lastSingleflightLogTick >= 0L
                && currentTick - this.lastSingleflightLogTick < Math.max(1L, cooldownTicks)) {
                return false;
            }
            this.lastSingleflightLogResult = normalized;
            this.lastSingleflightLogTick = currentTick;
            return true;
        }

        private boolean hasOutstandingRequest() {
            return outstandingRequestVersion > 0L && outstandingRequestTick >= 0L;
        }

        private void clearOutstandingRequest() {
            this.outstandingRequestVersion = -1L;
            this.outstandingRequestTick = -1L;
            this.outstandingRequestKind = null;
            this.outstandingBridgeOwnedWritePrime = false;
            this.outstandingRequestStarted = false;
        }

        private String key() {
            return key;
        }

        private String runtimeCacheKey() {
            return runtimeCacheKey;
        }

        private int regionX() {
            return regionX;
        }

        private int regionZ() {
            return regionZ;
        }

        private long dirtyEpoch() {
            return dirtyEpoch;
        }

        private long lastDirtyEpoch() {
            return lastDirtyEpoch;
        }

        private long dirtyVersion() {
            return dirtyVersion;
        }

        private long nextAttemptTick() {
            return nextAttemptTick;
        }

        private long outstandingRequestTick() {
            return outstandingRequestTick;
        }

        private long outstandingRequestVersion() {
            return outstandingRequestVersion;
        }

        private RequestKind outstandingRequestKind() {
            return outstandingRequestKind;
        }

        private boolean outstandingBridgeOwnedWritePrime() {
            return outstandingBridgeOwnedWritePrime;
        }

        private boolean outstandingRequestStarted() {
            return outstandingRequestStarted;
        }

        private long timeoutBackoffUntilTick() {
            return timeoutBackoffUntilTick;
        }

        private long lastTouchedTick() {
            return lastTouchedTick;
        }

        private long lastAssistSuccessVersion() {
            return lastAssistSuccessVersion;
        }

        private long lastBridgeRequestTickForVersion(long dirtyVersion) {
            if (dirtyVersion <= 0L) {
                return -1L;
            }
            if (lastBridgeRequestVersion != dirtyVersion) {
                return -1L;
            }
            return lastBridgeRequestTick;
        }
    }
}


