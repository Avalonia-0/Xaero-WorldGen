package com.alonie.xaero_worldgen.bridge.core;


import com.alonie.xaero_worldgen.bridge.core.*;
import com.alonie.xaero_worldgen.bridge.state.*;
import com.alonie.xaero_worldgen.bridge.policy.*;
import com.alonie.xaero_worldgen.bridge.audit.*;
import com.alonie.xaero_worldgen.bridge.snapshot.*;
import com.alonie.xaero_worldgen.bridge.integration.voxy.*;
import com.alonie.xaero_worldgen.bridge.integration.xaero.*;
import com.alonie.xaero_worldgen.bridge.migration.*;
import net.minecraft.server.world.ServerWorld;

public final class BridgeContext {
    private static final ThreadLocal<ServerWorld> CURRENT_WORLD = new ThreadLocal<>();

    private BridgeContext() {
    }

    public static void setCurrentWorld(ServerWorld world) {
        CURRENT_WORLD.set(world);
    }

    public static ServerWorld getCurrentWorld() {
        return CURRENT_WORLD.get();
    }

    public static void clear() {
        CURRENT_WORLD.remove();
    }
}


