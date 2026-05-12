package com.alonie.xaero_worldgen.mixin.voxy;

import com.alonie.xaero_worldgen.bridge.snapshot.BridgeLiveCaptureQueue;
import com.alonie.xaero_worldgen.bridge.integration.voxy.VoxyChunkReadinessTracker;
import com.alonie.xaero_worldgen.bridge.integration.voxy.VoxyDirtyRegionMarker;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VoxyIntegration.class)
public abstract class VoxyIntegrationMixin {
    @Inject(method = "ingestChunk", at = @At("TAIL"))
    private static void vwgxwm$markIngestedChunk(WorldChunk chunk, CallbackInfo ci) {
        VoxyChunkReadinessTracker.recordFullChunkIngest(chunk.getWorld(), chunk.getPos().x, chunk.getPos().z);
        if (VoxyDirtyRegionMarker.markChunkDirty(chunk.getWorld(), chunk.getPos().x, chunk.getPos().z)) {
            BridgeLiveCaptureQueue.requestCapture(chunk.getWorld(), chunk.getPos().x, chunk.getPos().z);
        }
    }

    @Inject(
        method = "rawIngest(Lnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/chunk/ChunkNibbleArray;)V",
        at = @At("TAIL")
    )
    private static void vwgxwm$markRawChunk(WorldChunk chunk, ChunkNibbleArray blockLight, CallbackInfo ci) {
        VoxyChunkReadinessTracker.recordFullChunkIngest(chunk.getWorld(), chunk.getPos().x, chunk.getPos().z);
        if (VoxyDirtyRegionMarker.markChunkDirty(chunk.getWorld(), chunk.getPos().x, chunk.getPos().z)) {
            BridgeLiveCaptureQueue.requestCapture(chunk.getWorld(), chunk.getPos().x, chunk.getPos().z);
        }
    }

    @Inject(
        method = "rawIngest(Lnet/minecraft/world/World;Lnet/minecraft/world/chunk/ChunkSection;IIILnet/minecraft/world/chunk/ChunkNibbleArray;Lnet/minecraft/world/chunk/ChunkNibbleArray;)V",
        at = @At("TAIL")
    )
    private static void vwgxwm$markRawSection(
        World world,
        ChunkSection section,
        int chunkX,
        int sectionY,
        int chunkZ,
        ChunkNibbleArray blockLight,
        ChunkNibbleArray skyLight,
        CallbackInfo ci
    ) {
        if (VoxyChunkReadinessTracker.recordSectionIngest(world, chunkX, sectionY, chunkZ)) {
            if (VoxyDirtyRegionMarker.markChunkDirty(world, chunkX, chunkZ)) {
                BridgeLiveCaptureQueue.requestCapture(world, chunkX, chunkZ);
            }
        }
    }

    @Inject(
        method = "rawIngest(Lnet/minecraft/world/World;Lnet/minecraft/world/chunk/ChunkSection;IIILnet/minecraft/world/chunk/ChunkNibbleArray;)V",
        at = @At("TAIL")
    )
    private static void vwgxwm$markRawSectionSingleLight(
        World world,
        ChunkSection section,
        int chunkX,
        int sectionY,
        int chunkZ,
        ChunkNibbleArray blockLight,
        CallbackInfo ci
    ) {
        if (VoxyChunkReadinessTracker.recordSectionIngest(world, chunkX, sectionY, chunkZ)) {
            if (VoxyDirtyRegionMarker.markChunkDirty(world, chunkX, chunkZ)) {
                BridgeLiveCaptureQueue.requestCapture(world, chunkX, chunkZ);
            }
        }
    }
}

