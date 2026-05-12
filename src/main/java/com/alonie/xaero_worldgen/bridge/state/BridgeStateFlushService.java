package com.alonie.xaero_worldgen.bridge.state;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeStateFlushService {
    private static final long FLUSH_INTERVAL_MILLIS = 2_000L;
    private static final long MAX_DEFER_MILLIS = 10_000L;
    private static final ConcurrentHashMap<String, FlushRequest> PENDING = new ConcurrentHashMap<>();

    private BridgeStateFlushService() {
    }

    public static void markDirtyRuntime(ServerWorld world) {
        FlushRequest request = requestFor(world);
        request.dirtyPending = true;
        request.lastRequestMillis = System.currentTimeMillis();
    }

    public static void markKnownRegionRuntime(ServerWorld world) {
        FlushRequest request = requestFor(world);
        request.knownPending = true;
        request.lastRequestMillis = System.currentTimeMillis();
    }

    public static void requestFlush(ServerWorld world) {
        FlushRequest request = requestFor(world);
        request.lastRequestMillis = System.currentTimeMillis();
    }

    public static void flushDue(MinecraftServer server) {
        long now = System.currentTimeMillis();
        ArrayList<Map.Entry<String, FlushRequest>> snapshot = new ArrayList<>(PENDING.entrySet());
        for (Map.Entry<String, FlushRequest> entry : snapshot) {
            FlushRequest request = entry.getValue();
            ServerWorld world = request.worldRef.get();
            if (world == null || world.getServer() != server) {
                if (world == null) {
                    PENDING.remove(entry.getKey(), request);
                }
                continue;
            }

            if (!request.hasPending()) {
                PENDING.remove(entry.getKey(), request);
                continue;
            }

            long sinceRequest = now - request.lastRequestMillis;
            long sinceLastFlush = now - request.lastFlushMillis;
            if (sinceRequest < FLUSH_INTERVAL_MILLIS && sinceLastFlush < MAX_DEFER_MILLIS) {
                continue;
            }

            flushRequest(entry.getKey(), request, world, now);
        }
    }

    public static void flushNow(MinecraftServer server) {
        long now = System.currentTimeMillis();
        ArrayList<Map.Entry<String, FlushRequest>> snapshot = new ArrayList<>(PENDING.entrySet());
        for (Map.Entry<String, FlushRequest> entry : snapshot) {
            FlushRequest request = entry.getValue();
            ServerWorld world = request.worldRef.get();
            if (world == null || world.getServer() != server) {
                continue;
            }
            flushRequest(entry.getKey(), request, world, now);
        }
    }

    public static void flushNow(ServerWorld world) {
        FlushRequest request = PENDING.get(BridgePaths.getRuntimeCacheKey(world));
        if (request == null) {
            return;
        }

        flushRequest(BridgePaths.getRuntimeCacheKey(world), request, world, System.currentTimeMillis());
    }

    public static void clearRuntimeState() {
        PENDING.clear();
    }

    private static FlushRequest requestFor(ServerWorld world) {
        String runtimeCacheKey = BridgePaths.getRuntimeCacheKey(world);
        return PENDING.compute(runtimeCacheKey, (ignored, existing) -> {
            if (existing == null) {
                return new FlushRequest(new WeakReference<>(world), System.currentTimeMillis());
            }

            existing.worldRef = new WeakReference<>(world);
            return existing;
        });
    }

    private static void flushRequest(String key, FlushRequest request, ServerWorld world, long now) {
        boolean dirtyDone = !request.dirtyPending || BridgeDirtyRegionStore.flushWorld(world);
        boolean knownDone = !request.knownPending || VoxyGeneratedRegionIndex.flushWorld(world);

        if (dirtyDone) {
            request.dirtyPending = false;
        }
        if (knownDone) {
            request.knownPending = false;
        }

        if (dirtyDone || knownDone) {
            request.lastFlushMillis = now;
        }

        if (!request.hasPending()) {
            PENDING.remove(key, request);
        }
    }

    private static final class FlushRequest {
        private WeakReference<ServerWorld> worldRef;
        private volatile boolean dirtyPending;
        private volatile boolean knownPending;
        private volatile long lastRequestMillis;
        private volatile long lastFlushMillis;

        private FlushRequest(WeakReference<ServerWorld> worldRef, long now) {
            this.worldRef = worldRef;
            this.lastRequestMillis = now;
            this.lastFlushMillis = now;
        }

        private boolean hasPending() {
            return dirtyPending || knownPending;
        }
    }
}


