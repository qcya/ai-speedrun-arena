package dev.aiarena.film.mixin;

import dev.aiarena.film.FilmClock;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToastManager.class)
abstract class FilmToast {
    @Inject(method="draw", at=@At("HEAD"))
    private void offsetToasts(MatrixStack matrices, CallbackInfo ci) {
        matrices.push();
        if (FilmClock.visible()) matrices.translate(0, FilmClock.height()+2, 0);
    }
    @Inject(method="draw", at=@At("RETURN"))
    private void paintClock(MatrixStack matrices, CallbackInfo ci) {
        matrices.pop();
        if (FilmClock.visible()) FilmClock.draw(MinecraftClient.getInstance(), matrices);
    }
}
