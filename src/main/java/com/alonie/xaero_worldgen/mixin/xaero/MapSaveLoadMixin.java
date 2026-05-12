package com.alonie.xaero_worldgen.mixin.xaero;

import com.alonie.xaero_worldgen.bridge.integration.xaero.XaeroBridgeSupport;
import com.alonie.xaero_worldgen.bridge.state.BridgeLoadLeaseTracker;
import com.alonie.xaero_worldgen.bridge.audit.BridgeRegionAuditService;
import com.alonie.xaero_worldgen.bridge.state.BridgeRegionBuildTracker;
import com.alonie.xaero_worldgen.bridge.policy.BridgeSourcePolicy;
import com.alonie.xaero_worldgen.bridge.integration.xaero.XaeroLiveRegionQueue;
import net.minecraft.block.Block;
import net.minecraft.fluid.Fluid;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.biome.BiomeGetter;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.MapRegion;

@Mixin(MapSaveLoad.class)
public abstract class MapSaveLoadMixin {
    @Inject(method = "saveExists", at = @At("HEAD"), cancellable = true)
    private void vwgxwm$announceVoxyBackedRegion(MapRegion region, CallbackInfoReturnable<Boolean> cir) {
        ServerWorld world = XaeroBridgeSupport.resolveWorld(region);
        if (world == null) {
            return;
        }

        BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(world, region.getRegionX(), region.getRegionZ());
        if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY || !XaeroBridgeSupport.isBridgeRegionCandidate(region)) {
            return;
        }

        if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.BRIDGE_ONLY && XaeroBridgeSupport.ensureReleasedBridgeSource(region)) {
            region.setSaveExists(Boolean.TRUE);
            cir.setReturnValue(Boolean.TRUE);
            return;
        }

        region.setSaveExists(Boolean.FALSE);
        cir.setReturnValue(Boolean.FALSE);
    }

    @Inject(method = "loadRegion", at = @At("HEAD"), cancellable = true)
    private void vwgxwm$ensureStubBeforeLoad(
        MapRegion region,
        RegistryWrapper<Block> blockLookup,
        Registry<Block> blockRegistry,
        Registry<Fluid> fluidRegistry,
        BiomeGetter biomeGetter,
        boolean caves,
        int caveStart,
        CallbackInfoReturnable<Boolean> cir
    ) {
        ServerWorld world = XaeroBridgeSupport.resolveWorld(region);
        if (world != null) {
            BridgeLoadLeaseTracker.ackLoadStart(
                world,
                region.getRegionX(),
                region.getRegionZ(),
                XaeroLiveRegionQueue.currentTick()
            );
            BridgeRegionBuildTracker.recordLoadStart(
                world,
                region.getRegionX(),
                region.getRegionZ(),
                XaeroLiveRegionQueue.currentTick()
            );
            BridgeRegionAuditService.touchRegion(world, region.getRegionX(), region.getRegionZ(), "load_start");
        }

        if (world == null) {
            return;
        }

        BridgeSourcePolicy.SourcePolicy sourcePolicy = BridgeSourcePolicy.classify(world, region.getRegionX(), region.getRegionZ());
        if (sourcePolicy == BridgeSourcePolicy.SourcePolicy.VANILLA_ONLY || !XaeroBridgeSupport.isBridgeRegionCandidate(region)) {
            return;
        }

        if (sourcePolicy != BridgeSourcePolicy.SourcePolicy.BRIDGE_ONLY || !XaeroBridgeSupport.ensureReleasedBridgeSource(region)) {
            region.setSaveExists(Boolean.FALSE);
            cir.setReturnValue(Boolean.FALSE);
        }
    }

    @Inject(method = "loadRegion", at = @At("RETURN"))
    private void vwgxwm$trackLoadRegionResult(
        MapRegion region,
        RegistryWrapper<Block> blockLookup,
        Registry<Block> blockRegistry,
        Registry<Fluid> fluidRegistry,
        BiomeGetter biomeGetter,
        boolean caves,
        int caveStart,
        CallbackInfoReturnable<Boolean> cir
    ) {
        ServerWorld world = XaeroBridgeSupport.resolveWorld(region);
        if (world == null) {
            return;
        }

        long currentTick = XaeroLiveRegionQueue.currentTick();
        boolean loaded = Boolean.TRUE.equals(cir.getReturnValue());
        BridgeLoadLeaseTracker.recordLoadResult(
            world,
            region.getRegionX(),
            region.getRegionZ(),
            loaded,
            currentTick
        );
        BridgeRegionAuditService.touchRegion(
            world,
            region.getRegionX(),
            region.getRegionZ(),
            loaded ? "load_result_hit" : "load_result_miss"
        );
    }
}

