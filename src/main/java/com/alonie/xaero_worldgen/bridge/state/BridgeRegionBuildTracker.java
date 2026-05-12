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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class BridgeRegionBuildTracker {
    private static final ConcurrentHashMap<String, BuildState> BUILD_STATES = new ConcurrentHashMap<>();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final long REQUEST_TTL_MILLIS = 30_000L;
    private static final long IN_FLIGHT_TTL_MILLIS = 120_000L;

    private BridgeRegionBuildTracker() {
    }

    public static void recordRefreshRequested(ServerWorld world, int regionX, int regionZ) {
        recordRefreshRequested(world, regionX, regionZ, -1L);
    }

    public static void recordRefreshRequested(ServerWorld world, int regionX, int regionZ, long requestTick) {
        recordRequest(world, regionX, regionZ, requestTick, RequestKind.REFRESH, false);
    }

    public static void recordLoadRequested(
        ServerWorld world,
        int regionX,
        int regionZ,
        long requestTick,
        boolean bridgeOwnedWritePrime
    ) {
        recordRequest(world, regionX, regionZ, requestTick, RequestKind.LOAD, bridgeOwnedWritePrime);
    }

    public static long recordBuildStart(ServerWorld world, int regionX, int regionZ) {
        purgeExpiredStates();
        String key = keyOf(world, regionX, regionZ);
        BuildState state = BUILD_STATES.get(key);
        if (state == null) {
            long dirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
            if (dirtyVersion <= 0L) {
                return -1L;
            }
            state = BuildState.synthetic(dirtyVersion, System.currentTimeMillis());
            BUILD_STATES.put(key, state);
        }

        state.markBuildStart(System.currentTimeMillis());
        return state.requestedDirtyVersion;
    }

    public static void recordLoadStart(ServerWorld world, int regionX, int regionZ, long loadTick) {
        purgeExpiredStates();
        BuildState state = BUILD_STATES.get(keyOf(world, regionX, regionZ));
        if (state == null) {
            return;
        }
        state.markLoadStart(loadTick);
    }

    public static long consumeBuildVersion(ServerWorld world, int regionX, int regionZ) {
        purgeExpiredStates();
        BuildState state = BUILD_STATES.remove(keyOf(world, regionX, regionZ));
        return state != null && state.inFlight ? state.requestedDirtyVersion : -1L;
    }

    public static long consumeTrackedVersion(ServerWorld world, int regionX, int regionZ) {
        purgeExpiredStates();
        BuildState state = BUILD_STATES.remove(keyOf(world, regionX, regionZ));
        return state != null ? state.requestedDirtyVersion : -1L;
    }

    public static long peekBuildVersion(ServerWorld world, int regionX, int regionZ) {
        purgeExpiredStates();
        BuildState state = BUILD_STATES.get(keyOf(world, regionX, regionZ));
        return state != null && state.inFlight ? state.requestedDirtyVersion : -1L;
    }

    public static RequestStatus getRequestStatus(ServerWorld world, int regionX, int regionZ) {
        purgeExpiredStates();
        BuildState state = BUILD_STATES.get(keyOf(world, regionX, regionZ));
        if (state == null) {
            return null;
        }

        return state.toStatus();
    }

    public static boolean isBuildInFlight(ServerWorld world, int regionX, int regionZ) {
        return peekBuildVersion(world, regionX, regionZ) > 0L;
    }

    public static int countInFlight(String runtimeCacheKey) {
        purgeExpiredStates();
        String prefix = runtimeCacheKey + "|";
        int count = 0;
        for (var entry : BUILD_STATES.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue().inFlight) {
                count++;
            }
        }
        return count;
    }

    public static String describeInFlight(String runtimeCacheKey, int limit) {
        purgeExpiredStates();
        String prefix = runtimeCacheKey + "|";
        long now = System.currentTimeMillis();
        ArrayList<String> descriptions = new ArrayList<>();
        for (var entry : BUILD_STATES.entrySet()) {
            if (!entry.getKey().startsWith(prefix) || !entry.getValue().inFlight) {
                continue;
            }

            String region = extractRegion(entry.getKey());
            long age = now - Math.max(0L, entry.getValue().inFlightSinceMillis);
            descriptions.add(region + "@" + age + "ms");
        }

        descriptions.sort(Comparator.naturalOrder());
        if (descriptions.isEmpty()) {
            return "none";
        }

        if (limit <= 0 || descriptions.size() <= limit) {
            return String.join("|", descriptions);
        }

        return String.join("|", descriptions.subList(0, limit)) + "|...+" + (descriptions.size() - limit);
    }

    public static int countInFlightGlobal() {
        purgeExpiredStates();
        int count = 0;
        for (BuildState state : BUILD_STATES.values()) {
            if (state.inFlight) {
                count++;
            }
        }
        return count;
    }

    public static void clearRuntimeState() {
        BUILD_STATES.clear();
    }

    private static String keyOf(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }

    private static void recordRequest(
        ServerWorld world,
        int regionX,
        int regionZ,
        long requestTick,
        RequestKind requestKind,
        boolean bridgeOwnedWritePrime
    ) {
        purgeExpiredStates();
        long dirtyVersion = BridgeDirtyRegionStore.getDirtyVersion(world, regionX, regionZ);
        if (dirtyVersion <= 0L) {
            return;
        }

        long now = System.currentTimeMillis();
        long requestId = REQUEST_SEQUENCE.incrementAndGet();
        BUILD_STATES.put(
            keyOf(world, regionX, regionZ),
            new BuildState(dirtyVersion, requestId, now, requestTick, requestKind, bridgeOwnedWritePrime)
        );
    }

    private static void purgeExpiredStates() {
        long now = System.currentTimeMillis();
        BUILD_STATES.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private static String extractRegion(String key) {
        int lastSplit = key.lastIndexOf('|');
        if (lastSplit < 0 || lastSplit + 1 >= key.length()) {
            return "?,?";
        }

        int secondLastSplit = key.lastIndexOf('|', lastSplit - 1);
        if (secondLastSplit < 0 || secondLastSplit + 1 >= lastSplit) {
            return "?,?";
        }

        return key.substring(secondLastSplit + 1, lastSplit) + "," + key.substring(lastSplit + 1);
    }

    private static final class BuildState {
        private final long requestedDirtyVersion;
        private final long requestId;
        private final long requestedAtMillis;
        private final long requestTick;
        private final RequestKind requestKind;
        private final boolean bridgeOwnedWritePrime;
        private volatile boolean inFlight;
        private volatile long inFlightSinceMillis;
        private volatile boolean acknowledgedByBuildStart;
        private volatile boolean acknowledgedByLoadStart;
        private volatile long acknowledgedAtTick;

        private BuildState(
            long requestedDirtyVersion,
            long requestId,
            long requestedAtMillis,
            long requestTick,
            RequestKind requestKind,
            boolean bridgeOwnedWritePrime
        ) {
            this.requestedDirtyVersion = requestedDirtyVersion;
            this.requestId = requestId;
            this.requestedAtMillis = requestedAtMillis;
            this.requestTick = requestTick;
            this.requestKind = requestKind;
            this.bridgeOwnedWritePrime = bridgeOwnedWritePrime;
            this.inFlight = false;
            this.inFlightSinceMillis = -1L;
            this.acknowledgedByBuildStart = false;
            this.acknowledgedByLoadStart = false;
            this.acknowledgedAtTick = -1L;
        }

        private static BuildState synthetic(long dirtyVersion, long now) {
            return new BuildState(
                dirtyVersion,
                REQUEST_SEQUENCE.incrementAndGet(),
                now,
                -1L,
                RequestKind.SYNTHETIC,
                false
            );
        }

        private void markBuildStart(long now) {
            this.inFlight = true;
            this.inFlightSinceMillis = now;
            this.acknowledgedByBuildStart = true;
            if (this.requestTick >= 0L) {
                this.acknowledgedAtTick = this.requestTick;
            }
        }

        private void markLoadStart(long loadTick) {
            this.acknowledgedByLoadStart = true;
            if (loadTick >= 0L) {
                this.acknowledgedAtTick = loadTick;
            } else if (this.requestTick >= 0L) {
                this.acknowledgedAtTick = this.requestTick;
            }
        }

        private boolean isExpired(long now) {
            long ttl = inFlight ? IN_FLIGHT_TTL_MILLIS : REQUEST_TTL_MILLIS;
            long basis = inFlight ? inFlightSinceMillis : requestedAtMillis;
            return basis <= 0L || now - basis > ttl;
        }

        private RequestStatus toStatus() {
            return new RequestStatus(
                this.requestedDirtyVersion,
                this.requestTick,
                this.acknowledgedByBuildStart,
                this.acknowledgedByLoadStart,
                this.acknowledgedAtTick,
                this.inFlight,
                this.requestKind,
                this.bridgeOwnedWritePrime
            );
        }
    }

    public record RequestStatus(
        long requestedDirtyVersion,
        long requestTick,
        boolean acknowledgedByBuildStart,
        boolean acknowledgedByLoadStart,
        long acknowledgedAtTick,
        boolean inFlight,
        RequestKind requestKind,
        boolean bridgeOwnedWritePrime
    ) {
    }

    public enum RequestKind {
        LOAD,
        REFRESH,
        SYNTHETIC
    }
}


