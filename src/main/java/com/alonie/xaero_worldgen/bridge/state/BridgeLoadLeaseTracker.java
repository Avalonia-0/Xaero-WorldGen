package com.alonie.xaero_worldgen.bridge.state;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import net.minecraft.server.world.ServerWorld;

import java.util.concurrent.ConcurrentHashMap;

public final class BridgeLoadLeaseTracker {
    private static final ConcurrentHashMap<String, LeaseState> LEASES = new ConcurrentHashMap<>();
    private static final long REQUEST_ACK_TIMEOUT_TICKS = 200L;
    private static final long BUILD_TIMEOUT_TICKS = 400L;
    private static final long TERMINAL_KEEP_TICKS = 600L;

    private BridgeLoadLeaseTracker() {
    }

    public static void requestLease(
        ServerWorld world,
        int regionX,
        int regionZ,
        long requestedDirtyVersion,
        long requestTick,
        boolean bridgeOwnedWritePrime,
        LeaseKind leaseKind
    ) {
        if (world == null || requestedDirtyVersion <= 0L || requestTick < 0L) {
            return;
        }

        LeaseState state = new LeaseState(requestedDirtyVersion, requestTick, bridgeOwnedWritePrime, leaseKind);
        LEASES.put(regionKey(world, regionX, regionZ), state);
    }

    public static void ackLoadStart(ServerWorld world, int regionX, int regionZ, long loadTick) {
        LeaseState state = LEASES.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return;
        }

        synchronized (state) {
            state.state = LeasePhase.LOAD_STARTED;
            state.loadStartTick = Math.max(loadTick, state.requestTick);
            if (state.startedTick < 0L) {
                state.startedTick = state.loadStartTick;
            }
        }
    }

    public static void ackBuildStart(ServerWorld world, int regionX, int regionZ, long buildTick) {
        LeaseState state = LEASES.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return;
        }

        synchronized (state) {
            state.state = LeasePhase.BUILD_STARTED;
            state.buildStartTick = Math.max(buildTick, state.requestTick);
            if (state.startedTick < 0L) {
                state.startedTick = state.buildStartTick;
            }
        }
    }

    public static void recordLoadResult(ServerWorld world, int regionX, int regionZ, boolean loaded, long tick) {
        LeaseState state = LEASES.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return;
        }

        synchronized (state) {
            state.loadResult = loaded ? LoadResult.HIT : LoadResult.MISS;
            if (state.startedTick < 0L) {
                state.startedTick = Math.max(tick, state.requestTick);
            }
        }
    }

    public static void recordCacheWriteResult(ServerWorld world, int regionX, int regionZ, boolean success, long tick) {
        LeaseState state = LEASES.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return;
        }

        synchronized (state) {
            state.cacheWriteResult = success ? CacheWriteResult.SUCCESS : CacheWriteResult.FAIL;
            if (state.startedTick < 0L) {
                state.startedTick = Math.max(tick, state.requestTick);
            }
        }
    }

    public static void completeLease(ServerWorld world, int regionX, int regionZ, TerminalState terminalState, long tick) {
        completeLease(world, regionX, regionZ, terminalState, tick, null);
    }

    public static void completeLease(
        ServerWorld world,
        int regionX,
        int regionZ,
        TerminalState terminalState,
        long tick,
        String terminalSource
    ) {
        tryFinalizeLease(world, regionX, regionZ, terminalState, tick, terminalSource);
    }

    public static boolean tryFinalizeLease(
        ServerWorld world,
        int regionX,
        int regionZ,
        TerminalState terminalState,
        long tick,
        String terminalSource
    ) {
        LeaseState state = LEASES.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return false;
        }

        boolean notifyAssistTerminal = false;
        synchronized (state) {
            boolean wasActive = state.isActive();
            if (!wasActive) {
                return false;
            }
            state.state = switch (terminalState) {
                case SUCCESS -> LeasePhase.TERMINAL_SUCCESS;
                case FAIL -> LeasePhase.TERMINAL_FAIL;
                case TIMEOUT -> LeasePhase.TERMINAL_TIMEOUT;
                case STALE -> LeasePhase.TERMINAL_STALE;
            };
            state.terminalState = terminalState;
            state.terminalTick = tick;
            state.terminalSource = normalizeTerminalSource(terminalSource);
            notifyAssistTerminal = wasActive && state.leaseKind == LeaseKind.VANILLA_ASSIST_LOAD;
        }
        if (notifyAssistTerminal) {
            BridgeFallbackEscalationPolicy.onAssistLeaseTerminal(world, regionX, regionZ, terminalState);
        }
        return true;
    }

    public static int countActive(String runtimeCacheKey, long currentTick) {
        purgeExpired(runtimeCacheKey, currentTick);
        return countActiveNoPurge(runtimeCacheKey);
    }

    public static int countActiveNoPurge(String runtimeCacheKey) {
        String prefix = runtimeCacheKey + "|";
        int count = 0;
        for (var entry : LEASES.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            LeaseState state = entry.getValue();
            synchronized (state) {
                if (state.isActive()) {
                    count++;
                }
            }
        }
        return count;
    }

    public static boolean isLeaseActive(ServerWorld world, int regionX, int regionZ, long currentTick) {
        RequestStatus status = getLeaseStatus(world, regionX, regionZ, currentTick);
        return status != null && status.active();
    }

    public static RequestStatus getLeaseStatus(ServerWorld world, int regionX, int regionZ, long currentTick) {
        String key = regionKey(world, regionX, regionZ);
        LeaseState state = LEASES.get(key);
        if (state == null) {
            return null;
        }

        if (expireIfNeeded(key, state, currentTick)) {
            return null;
        }

        synchronized (state) {
            return state.toStatus();
        }
    }

    public static void clearRuntimeState() {
        LEASES.clear();
    }

    private static void purgeExpired(String runtimeCacheKey, long currentTick) {
        String prefix = runtimeCacheKey + "|";
        for (var entry : LEASES.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            expireIfNeeded(entry.getKey(), entry.getValue(), currentTick);
        }
    }

    private static boolean expireIfNeeded(String key, LeaseState state, long currentTick) {
        synchronized (state) {
            if (!state.isActive()) {
                if (state.terminalTick >= 0L && currentTick - state.terminalTick <= TERMINAL_KEEP_TICKS) {
                    return false;
                }
                return LEASES.remove(key, state);
            }

            if (state.buildStartTick >= 0L) {
                if (currentTick - state.buildStartTick > BUILD_TIMEOUT_TICKS) {
                    state.state = LeasePhase.TERMINAL_TIMEOUT;
                    state.terminalState = TerminalState.TIMEOUT;
                    state.terminalTick = currentTick;
                    return false;
                }
                return false;
            }

            if (currentTick - state.requestTick > REQUEST_ACK_TIMEOUT_TICKS) {
                state.state = LeasePhase.TERMINAL_TIMEOUT;
                state.terminalState = TerminalState.TIMEOUT;
                state.terminalTick = currentTick;
                return false;
            }
            return false;
        }
    }

    private static String regionKey(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }

    public enum TerminalState {
        SUCCESS,
        FAIL,
        TIMEOUT,
        STALE
    }

    public record RequestStatus(
        long requestedDirtyVersion,
        long requestTick,
        long startedTick,
        long loadStartTick,
        long buildStartTick,
        boolean active,
        boolean bridgeOwnedWritePrime,
        LeaseKind leaseKind,
        LoadResult loadResult,
        CacheWriteResult cacheWriteResult,
        TerminalState terminalState,
        long terminalTick,
        String terminalSource
    ) {
        public boolean started() {
            return startedTick >= 0L || loadStartTick >= 0L || buildStartTick >= 0L;
        }

        public boolean terminal() {
            return !active && terminalState != null;
        }
    }

    public enum LeaseKind {
        BRIDGE_LOAD,
        VANILLA_ASSIST_LOAD
    }

    private enum LeasePhase {
        REQUESTED,
        LOAD_STARTED,
        BUILD_STARTED,
        TERMINAL_SUCCESS,
        TERMINAL_FAIL,
        TERMINAL_TIMEOUT,
        TERMINAL_STALE;

        private boolean active() {
            return this == REQUESTED || this == LOAD_STARTED || this == BUILD_STARTED;
        }
    }

    private static final class LeaseState {
        private final long requestedDirtyVersion;
        private final long requestTick;
        private final boolean bridgeOwnedWritePrime;
        private final LeaseKind leaseKind;
        private LeasePhase state;
        private long startedTick;
        private long loadStartTick;
        private long buildStartTick;
        private LoadResult loadResult;
        private CacheWriteResult cacheWriteResult;
        private TerminalState terminalState;
        private long terminalTick;
        private String terminalSource;

        private LeaseState(long requestedDirtyVersion, long requestTick, boolean bridgeOwnedWritePrime, LeaseKind leaseKind) {
            this.requestedDirtyVersion = requestedDirtyVersion;
            this.requestTick = requestTick;
            this.bridgeOwnedWritePrime = bridgeOwnedWritePrime;
            this.leaseKind = leaseKind == null ? LeaseKind.BRIDGE_LOAD : leaseKind;
            this.state = LeasePhase.REQUESTED;
            this.startedTick = -1L;
            this.loadStartTick = -1L;
            this.buildStartTick = -1L;
            this.loadResult = LoadResult.UNKNOWN;
            this.cacheWriteResult = CacheWriteResult.UNKNOWN;
            this.terminalState = null;
            this.terminalTick = -1L;
            this.terminalSource = "unknown";
        }

        private boolean isActive() {
            return state.active();
        }

        private RequestStatus toStatus() {
            return new RequestStatus(
                requestedDirtyVersion,
                requestTick,
                startedTick,
                loadStartTick,
                buildStartTick,
                state.active(),
                bridgeOwnedWritePrime,
                leaseKind,
                loadResult,
                cacheWriteResult,
                terminalState,
                terminalTick,
                terminalSource
            );
        }
    }

    public enum LoadResult {
        UNKNOWN,
        HIT,
        MISS
    }

    public enum CacheWriteResult {
        UNKNOWN,
        SUCCESS,
        FAIL
    }

    private static String normalizeTerminalSource(String source) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        return source;
    }
}


