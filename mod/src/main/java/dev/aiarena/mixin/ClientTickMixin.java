package dev.aiarena.mixin;

import dev.aiarena.Arena;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class ClientTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void arenaBeforeTick(CallbackInfo ci) {
        Arena.INSTANCE.before((MinecraftClient) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void arenaAfterTick(CallbackInfo ci) {
        Arena.INSTANCE.after((MinecraftClient) (Object) this);
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void arenaStopping(CallbackInfo ci) {
        Arena.INSTANCE.shutdown((MinecraftClient) (Object) this);
    }
}
