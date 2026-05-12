package com.alonie.xaero_worldgen.mixin.xaero;

import com.alonie.xaero_worldgen.bridge.core.BridgeContext;
import com.alonie.xaero_worldgen.bridge.state.BridgeBuildQualityTracker;
import com.alonie.xaero_worldgen.bridge.state.BridgeLoadLeaseTracker;
import com.alonie.xaero_worldgen.bridge.audit.BridgeRegionAuditService;
import com.alonie.xaero_worldgen.bridge.state.BridgeRegionBuildTracker;
import com.alonie.xaero_worldgen.bridge.policy.BridgeSourcePolicy;
import com.alonie.xaero_worldgen.bridge.state.BridgeXaeroLoadedChunkTracker;
import com.alonie.xaero_worldgen.bridge.integration.voxy.VoxyChunkNbtProvider;
import com.alonie.xaero_worldgen.bridge.integration.xaero.XaeroLiveRegionQueue;
import net.minecraft.block.Block;
import net.minecraft.fluid.Fluid;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.executor.Executor;
import xaero.map.file.worldsave.WorldDataReader;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(WorldDataReader.class)
public abstract class WorldDataReaderMixin {
    @Shadow
    private CompletableFuture<Optional<NbtCompound>>[] chunkNBTCompounds;

    @Inject(method = "buildRegion", at = @At("HEAD"))
    private void vwgxwm$pushWorldContext(
        MapRegion region,
        ServerWorld world,
        RegistryWrapper<Block> blockLookup,
        Registry<Block> blockRegistry,
        Registry<Fluid> fluidRegistry,
        boolean caves,
        int[] heightLimits,
        Executor executor,
        CallbackInfoReturnable<Boolean> cir
    ) {
        BridgeContext.setCurrentWorld(world);
        BridgeLoadLeaseTracker.ackBuildStart(world, region.getRegionX(), region.getRegionZ(), XaeroLiveRegionQueue.currentTick());
        long buildVersion = BridgeRegionBuildTracker.recordBuildStart(world, region.getRegionX(), region.getRegionZ());
        BridgeBuildQualityTracker.beginRegionBuild(world, region.getRegionX(), region.getRegionZ(), buildVersion);
        BridgeRegionAuditService.touchRegion(world, region.getRegionX(), region.getRegionZ(), "build_start");
    }

    @Inject(method = "buildRegion", at = @At("RETURN"))
    private void vwgxwm$clearWorldContext(
        MapRegion region,
        ServerWorld world,
        RegistryWrapper<Block> blockLookup,
        Registry<Block> blockRegistry,
        Registry<Fluid> fluidRegistry,
        boolean caves,
        int[] heightLimits,
        Executor executor,
        CallbackInfoReturnable<Boolean> cir
    ) {
        BridgeRegionAuditService.touchRegion(
            world,
            region.getRegionX(),
            region.getRegionZ(),
            Boolean.TRUE.equals(cir.getReturnValue()) ? "build_success" : "build_fail"
        );
        BridgeBuildQualityTracker.endRegionBuild(world, region.getRegionX(), region.getRegionZ(), Boolean.TRUE.equals(cir.getReturnValue()));
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            BridgeRegionBuildTracker.consumeBuildVersion(world, region.getRegionX(), region.getRegionZ());
            BridgeLoadLeaseTracker.completeLease(
                world,
                region.getRegionX(),
                region.getRegionZ(),
                BridgeLoadLeaseTracker.TerminalState.FAIL,
                XaeroLiveRegionQueue.currentTick(),
                "build_region_return_false"
            );
        }
        BridgeContext.clear();
    }

    @Inject(method = "readChunk", at = @At("RETURN"), cancellable = true)
    private void vwgxwm$injectVoxyChunk(RegionFile regionFile, ChunkPos chunkPos, CallbackInfoReturnable<NbtCompound> cir) {
        ServerWorld world = BridgeContext.getCurrentWorld();
        if (cir.getReturnValue() != null) {
            if (world != null) {
                BridgeBuildQualityTracker.recordVanillaHit(world, chunkPos);
                BridgeXaeroLoadedChunkTracker.markLoadedChunk(
                    world,
                    chunkPos.x,
                    chunkPos.z,
                    BridgeXaeroLoadedChunkTracker.SourceKind.VANILLA_HIT
                );
            }
            return;
        }

        if (world == null) {
            return;
        }

        if (!BridgeSourcePolicy.allowsBridgeFallback(world, chunkPos.x, chunkPos.z)) {
            return;
        }

        NbtCompound replacement = VoxyChunkNbtProvider.INSTANCE.createChunkNbt(world, chunkPos);
        if (replacement != null) {
            BridgeBuildQualityTracker.recordFallbackResult(world, chunkPos, true, hasCoordinateMismatch(chunkPos, replacement));
            BridgeXaeroLoadedChunkTracker.markLoadedChunk(
                world,
                chunkPos.x,
                chunkPos.z,
                BridgeXaeroLoadedChunkTracker.SourceKind.FALLBACK_HIT
            );
            cir.setReturnValue(replacement);
            return;
        }

        BridgeBuildQualityTracker.recordFallbackResult(world, chunkPos, false, false);
    }

    @Inject(method = "readChunkNBTCompounds", at = @At("RETURN"))
    private void vwgxwm$wrapChunkNbtFutures(ServerChunkLoadingManager chunkLoadingManager, MapTileChunk chunk, CallbackInfo ci) {
        ServerWorld world = BridgeContext.getCurrentWorld();
        if (world == null || chunkNBTCompounds == null) {
            return;
        }

        int baseChunkX = chunk.getX() << 2;
        int baseChunkZ = chunk.getZ() << 2;
        for (int localZ = 0; localZ < 4; localZ++) {
            for (int localX = 0; localX < 4; localX++) {
                int index = (localZ << 2) | localX;
                CompletableFuture<Optional<NbtCompound>> future = chunkNBTCompounds[index];
                if (future == null) {
                    continue;
                }

                ChunkPos chunkPos = new ChunkPos(baseChunkX + localX, baseChunkZ + localZ);
                chunkNBTCompounds[index] = future.thenApply(optional -> {
                    if (optional != null && optional.isPresent()) {
                        BridgeBuildQualityTracker.recordVanillaHit(world, chunkPos);
                        BridgeXaeroLoadedChunkTracker.markLoadedChunk(
                            world,
                            chunkPos.x,
                            chunkPos.z,
                            BridgeXaeroLoadedChunkTracker.SourceKind.VANILLA_HIT
                        );
                        return optional;
                    }

                    if (!BridgeSourcePolicy.allowsBridgeFallback(world, chunkPos.x, chunkPos.z)) {
                        return optional == null ? Optional.empty() : optional;
                    }

                    NbtCompound replacement = VoxyChunkNbtProvider.INSTANCE.createChunkNbt(world, chunkPos);
                    if (replacement != null) {
                        BridgeBuildQualityTracker.recordFallbackResult(world, chunkPos, true, hasCoordinateMismatch(chunkPos, replacement));
                        BridgeXaeroLoadedChunkTracker.markLoadedChunk(
                            world,
                            chunkPos.x,
                            chunkPos.z,
                            BridgeXaeroLoadedChunkTracker.SourceKind.FALLBACK_HIT
                        );
                    } else {
                        BridgeBuildQualityTracker.recordFallbackResult(world, chunkPos, false, false);
                    }
                    return Optional.ofNullable(replacement);
                });
            }
        }
    }

    private static boolean hasCoordinateMismatch(ChunkPos chunkPos, NbtCompound nbt) {
        int xPos = readChunkCoordinate(nbt, "xPos");
        int zPos = readChunkCoordinate(nbt, "zPos");
        return xPos != chunkPos.x || zPos != chunkPos.z;
    }

    private static int readChunkCoordinate(NbtCompound nbt, String key) {
        int rootValue = nbt.getInt(key, Integer.MIN_VALUE);
        if (rootValue != Integer.MIN_VALUE) {
            return rootValue;
        }

        return nbt.getCompound("Level")
            .map(level -> level.getInt(key, Integer.MIN_VALUE))
            .orElse(Integer.MIN_VALUE);
    }
}

