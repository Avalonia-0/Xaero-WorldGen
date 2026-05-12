package com.alonie.xaero_worldgen.mixin.xaero;

import com.alonie.xaero_worldgen.bridge.integration.xaero.XaeroBridgeSupport;
import net.minecraft.registry.Registry;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.MapProcessor;
import xaero.map.file.OldFormatSupport;
import xaero.map.region.MapRegion;

import java.io.File;

@Mixin(MapRegion.class)
public abstract class MapRegionMixin {
    @Inject(method = "loadCacheTextures", at = @At("HEAD"), cancellable = true)
    private void vwgxwm$skipDirtyCache(
        MapProcessor mapProcessor,
        Registry<Biome> biomeRegistry,
        boolean caves,
        boolean[][] seenTiles,
        int caveStart,
        boolean[] stoppedLoadingChunks,
        boolean[] cacheLoaded,
        int ignoredReason,
        OldFormatSupport oldFormatSupport,
        CallbackInfoReturnable<Boolean> cir
    ) {
        XaeroBridgeSupport.invalidateLegacyMixedCacheIfNeeded((MapRegion) (Object) this, mapProcessor);
        XaeroBridgeSupport.invalidateAuditRepairCacheIfNeeded((MapRegion) (Object) this, mapProcessor);
        if (XaeroBridgeSupport.shouldBypassCache((MapRegion) (Object) this)) {
            XaeroBridgeSupport.invalidateDirtyCacheIfNeeded((MapRegion) (Object) this, mapProcessor);
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "postCache", at = @At("TAIL"))
    private void vwgxwm$clearDirtyAfterCacheWrite(File file, xaero.map.file.MapSaveLoad mapSaveLoad, boolean success, CallbackInfo ci) {
        XaeroBridgeSupport.clearDirtyAfterCacheWrite((MapRegion) (Object) this, file, success);
    }
}

