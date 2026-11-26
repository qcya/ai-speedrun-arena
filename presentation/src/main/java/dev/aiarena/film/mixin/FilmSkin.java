package dev.aiarena.film.mixin;

import dev.aiarena.film.FilmClock;
import dev.aiarena.film.LocalTexture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayerEntity.class)
abstract class FilmSkin {
    @Inject(method="getSkinTexture", at=@At("HEAD"), cancellable=true)
    private void localTexture(CallbackInfoReturnable<Identifier> result) {
        if (FilmClock.localSkin(this)) {
            Identifier local=LocalTexture.get();
            if(local!=null)result.setReturnValue(local);
        }
    }
    @Inject(method="getModel", at=@At("HEAD"), cancellable=true)
    private void localModel(CallbackInfoReturnable<String> result) {
        if (FilmClock.localSkin(this)) result.setReturnValue(FilmClock.model);
    }
}
