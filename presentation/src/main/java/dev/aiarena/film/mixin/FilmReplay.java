package dev.aiarena.film.mixin;

import dev.aiarena.film.ReplayLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional. ReplayMod 2.6.20 owns the active render framebuffer during this call. */
@Pseudo
@Mixin(targets="com.replaymod.render.hooks.EntityRendererHandler", remap=false)
abstract class FilmReplay {
    @Inject(method="renderWorld(FJ)V", at=@At("HEAD"), remap=false)
    private void beginRecordedFrame(float partial,long renderNanos,CallbackInfo ci) {
        ReplayLayer.begin(this);
    }
    @Inject(method="renderWorld(FJ)V", at=@At("RETURN"), remap=false)
    private void finishRecordedFrame(float partial,long renderNanos,CallbackInfo ci) {
        ReplayLayer.finish();
    }
}
