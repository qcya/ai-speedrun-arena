package dev.aiarena.week29.mixin;

import dev.aiarena.week29.Week29;

import net.minecraft.client.MinecraftClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class RunTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void step(CallbackInfo callback) {
        Week29.tick((MinecraftClient) (Object) this);
    }
}
