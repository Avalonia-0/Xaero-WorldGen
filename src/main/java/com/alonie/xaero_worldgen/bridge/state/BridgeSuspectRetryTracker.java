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
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeSuspectRetryTracker {
    private static final ConcurrentHashMap<String, SuspectState> SUSPECTS = new ConcurrentHashMap<>();
    private static final int MAX_RETRY_REQUESTS_PER_VERSION = 6;
    private static final long BASE_RETRY_TICKS = 20L;
    private static final long MAX_RETRY_TICKS = 240L;
    private static final long EXHAUSTED_RETRY_TICKS = 400L;

    private BridgeSuspectRetryTracker() {
    }

    public static void markSuspect(ServerWorld world, int regionX, int regionZ, long dirtyVersion, String reason) {
        if (world == null || dirtyVersion <= 0L) {
            return;
        }

        SuspectState state = SUSPECTS.computeIfAbsent(regionKey(world, regionX, regionZ), ignored -> new SuspectState());
        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.dirtyVersion != dirtyVersion) {
                state.dirtyVersion = dirtyVersion;
                state.retryRequests = 0;
                state.firstMarkedEpoch = now;
            }
            state.active = true;
            state.lastMarkedEpoch = now;
            state.reason = reason == null || reason.isBlank() ? "unknown" : reason;
        }
    }

    public static boolean hasActiveSuspect(ServerWorld world, int regionX, int regionZ, long dirtyVersion) {
        SuspectState state = SUSPECTS.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return false;
        }

        synchronized (state) {
            return state.active && state.dirtyVersion == dirtyVersion && dirtyVersion > 0L;
        }
    }

    public static boolean canIssueRetry(ServerWorld world, int regionX, int regionZ, long dirtyVersion) {
        SuspectState state = SUSPECTS.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return true;
        }

        synchronized (state) {
            if (!state.active || state.dirtyVersion != dirtyVersion || dirtyVersion <= 0L) {
                return true;
            }
            return state.retryRequests < MAX_RETRY_REQUESTS_PER_VERSION;
        }
    }

    public static long recommendedRetryDelayTicks(ServerWorld world, int regionX, int regionZ, long dirtyVersion) {
        SuspectState state = SUSPECTS.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return BASE_RETRY_TICKS;
        }

        synchronized (state) {
            if (!state.active || state.dirtyVersion != dirtyVersion || dirtyVersion <= 0L) {
                return BASE_RETRY_TICKS;
            }
            if (state.retryRequests >= MAX_RETRY_REQUESTS_PER_VERSION) {
                return EXHAUSTED_RETRY_TICKS;
            }

            int exp = Math.min(4, Math.max(0, state.retryRequests));
            long delay = BASE_RETRY_TICKS << exp;
            return Math.min(MAX_RETRY_TICKS, Math.max(BASE_RETRY_TICKS, delay));
        }
    }

    public static void onRetryRequested(ServerWorld world, int regionX, int regionZ, long dirtyVersion) {
        SuspectState state = SUSPECTS.get(regionKey(world, regionX, regionZ));
        if (state == null) {
            return;
        }

        synchronized (state) {
            if (!state.active || state.dirtyVersion != dirtyVersion || dirtyVersion <= 0L) {
                return;
            }
            state.retryRequests++;
        }
    }

    public static void clearResolved(ServerWorld world, int regionX, int regionZ, long dirtyVersion) {
        String regionKey = regionKey(world, regionX, regionZ);
        SuspectState state = SUSPECTS.get(regionKey);
        if (state == null) {
            return;
        }

        synchronized (state) {
            if (dirtyVersion <= 0L || state.dirtyVersion <= dirtyVersion) {
                SUSPECTS.remove(regionKey, state);
            }
        }
    }

    public static void clearRegion(ServerWorld world, int regionX, int regionZ) {
        SUSPECTS.remove(regionKey(world, regionX, regionZ));
    }

    public static void clearRuntimeState() {
        SUSPECTS.clear();
    }

    public static ArrayList<SuspectRegion> snapshotActiveRegions(ServerWorld world) {
        ArrayList<SuspectRegion> snapshot = new ArrayList<>();
        if (world == null) {
            return snapshot;
        }

        String runtimePrefix = BridgePaths.getRuntimeCacheKey(world) + "|";
        for (var entry : SUSPECTS.entrySet()) {
            if (!entry.getKey().startsWith(runtimePrefix)) {
                continue;
            }

            int[] region = tryParseRegion(entry.getKey());
            if (region == null) {
                continue;
            }

            SuspectState state = entry.getValue();
            synchronized (state) {
                if (!state.active || state.dirtyVersion <= 0L) {
                    continue;
                }
                snapshot.add(
                    new SuspectRegion(
                        region[0],
                        region[1],
                        state.dirtyVersion,
                        state.retryRequests,
                        state.firstMarkedEpoch,
                        state.lastMarkedEpoch,
                        state.reason == null || state.reason.isBlank() ? "unknown" : state.reason
                    )
                );
            }
        }
        return snapshot;
    }

    private static String regionKey(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }

    private static int[] tryParseRegion(String fullKey) {
        int lastSplit = fullKey.lastIndexOf('|');
        if (lastSplit < 0 || lastSplit + 1 >= fullKey.length()) {
            return null;
        }
        int secondLastSplit = fullKey.lastIndexOf('|', lastSplit - 1);
        if (secondLastSplit < 0 || secondLastSplit + 1 >= lastSplit) {
            return null;
        }
        try {
            int regionX = Integer.parseInt(fullKey.substring(secondLastSplit + 1, lastSplit));
            int regionZ = Integer.parseInt(fullKey.substring(lastSplit + 1));
            return new int[] {regionX, regionZ};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record SuspectRegion(
        int regionX,
        int regionZ,
        long dirtyVersion,
        int retryRequests,
        long firstMarkedEpoch,
        long lastMarkedEpoch,
        String reason
    ) {
    }

    private static final class SuspectState {
        private boolean active;
        private long dirtyVersion;
        private int retryRequests;
        private long firstMarkedEpoch;
        private long lastMarkedEpoch;
        private String reason;
    }
}


