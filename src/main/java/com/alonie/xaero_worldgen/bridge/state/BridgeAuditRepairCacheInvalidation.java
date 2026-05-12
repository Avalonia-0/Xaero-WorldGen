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

public final class BridgeAuditRepairCacheInvalidation {
    private static final ConcurrentHashMap<String, Long> REQUESTED = new ConcurrentHashMap<>();

    private BridgeAuditRepairCacheInvalidation() {
    }

    public static void requestInvalidation(ServerWorld world, int regionX, int regionZ, long dirtyVersion) {
        if (world == null) {
            return;
        }
        long requestedVersion = dirtyVersion > 0L ? dirtyVersion : 1L;
        String key = regionKey(world, regionX, regionZ);
        REQUESTED.merge(key, requestedVersion, Math::max);
    }

    public static boolean consumeIfRequested(ServerWorld world, int regionX, int regionZ, long currentDirtyVersion) {
        if (world == null) {
            return false;
        }
        String key = regionKey(world, regionX, regionZ);
        Long requestedVersion = REQUESTED.get(key);
        if (requestedVersion == null) {
            return false;
        }
        if (currentDirtyVersion > 0L && requestedVersion > currentDirtyVersion) {
            return false;
        }
        return REQUESTED.remove(key, requestedVersion);
    }

    public static void clearRuntimeState() {
        REQUESTED.clear();
    }

    private static String regionKey(ServerWorld world, int regionX, int regionZ) {
        return BridgePaths.getRuntimeCacheKey(world) + "|" + regionX + "|" + regionZ;
    }
}


