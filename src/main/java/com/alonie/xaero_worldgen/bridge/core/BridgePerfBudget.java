package com.alonie.xaero_worldgen.bridge.core;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
public final class BridgePerfBudget {
    public static final int LIVE_CAPTURE_CHUNKS_PER_TICK = 2;
    public static final long LIVE_CAPTURE_TIME_BUDGET_NANOS = 2_000_000L;

    public static final int HYDRATE_CHUNKS_PER_TICK = 2;
    public static final long HYDRATE_TIME_BUDGET_NANOS = 2_000_000L;

    public static final int LOAD_REQUESTS_PER_TICK = 3;
    public static final int LOAD_REQUESTS_PER_TICK_THROTTLED = 1;
    public static final int RECENT_TIMEOUT_WINDOW_TICKS = 200;
    public static final int RECENT_TIMEOUT_THROTTLE_THRESHOLD = 3;

    public static final long PERF_LOG_INTERVAL_TICKS = 200L;

    // Runtime tuning switches.
    public static final long ASSIST_MIN_RETRY_TICKS = Math.max(20L, getLong("vwgxwm.assist_min_retry_ticks", 80L));
    // Keep strict no-mixed-read for existing vanilla chunks, but allow controlled fallback for
    // chunks that are absent in the MCA header when assist keeps failing.
    public static final boolean VANILLA_MISSING_CHUNK_FALLBACK = getBoolean("vwgxwm.vanilla_missing_chunk_fallback", true);

    private BridgePerfBudget() {
    }

    private static boolean getBoolean(String key, boolean fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return "1".equals(raw) || Boolean.parseBoolean(raw);
    }

    private static long getLong(String key, long fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}


