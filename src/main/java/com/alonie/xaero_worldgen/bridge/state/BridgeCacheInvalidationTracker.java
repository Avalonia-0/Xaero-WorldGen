package com.alonie.xaero_worldgen.bridge.state;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BridgeCacheInvalidationTracker {
    private static final ConcurrentHashMap<String, Long> INVALIDATED_VERSIONS = new ConcurrentHashMap<>();

    private BridgeCacheInvalidationTracker() {
    }

    public static boolean shouldInvalidate(String cacheRegionKey, long dirtyVersion) {
        if (cacheRegionKey == null || cacheRegionKey.isBlank() || dirtyVersion <= 0L) {
            return false;
        }

        return dirtyVersion > INVALIDATED_VERSIONS.getOrDefault(cacheRegionKey, 0L);
    }

    public static void markInvalidated(String cacheRegionKey, long dirtyVersion) {
        if (cacheRegionKey == null || cacheRegionKey.isBlank() || dirtyVersion <= 0L) {
            return;
        }

        INVALIDATED_VERSIONS.merge(cacheRegionKey, dirtyVersion, Math::max);
    }

    public static void allowReinvalidate(String cacheRegionKey, long dirtyVersion) {
        if (cacheRegionKey == null || cacheRegionKey.isBlank() || dirtyVersion <= 0L) {
            return;
        }

        INVALIDATED_VERSIONS.compute(cacheRegionKey, (ignored, previous) -> {
            long rollbackVersion = Math.max(0L, dirtyVersion - 1L);
            if (previous == null) {
                return rollbackVersion;
            }
            if (previous >= dirtyVersion) {
                return rollbackVersion;
            }
            return previous;
        });
    }

    public static void clearRuntimeState() {
        INVALIDATED_VERSIONS.clear();
    }
}


