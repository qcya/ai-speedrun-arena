package dev.aiarena.film.mixin;

import dev.aiarena.film.FilmClock;
import dev.aiarena.film.LocalExport;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class FilmTick {
    @Inject(method="tick", at=@At("HEAD"))
    private void astraTick(CallbackInfo ci) {
        MinecraftClient mc=(MinecraftClient)(Object)this;
        FilmClock.tick(mc);
        LocalExport.tick(mc);
    }
}
