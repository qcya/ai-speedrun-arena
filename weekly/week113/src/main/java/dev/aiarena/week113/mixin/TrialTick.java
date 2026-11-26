package dev.aiarena.week113.mixin;

import dev.aiarena.week113.Race;
import dev.aiarena.week113.PracticeLifecycle;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class TrialTick {
    @Inject(method = "tick", at = @At("HEAD"))
    private void drive(CallbackInfo ci) {
        MinecraftClient client=(MinecraftClient)(Object)this;
        PracticeLifecycle.tick(client);
        Race.tick(client);
    }
}
