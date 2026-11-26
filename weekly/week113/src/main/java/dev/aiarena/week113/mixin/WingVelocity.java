package dev.aiarena.week113.mixin;

import dev.aiarena.week113.FinalFight;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
abstract class WingVelocity {
    @Inject(method="onVelocityUpdate",at=@At("TAIL"))
    private void observeWingVelocity(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        FinalFight.onWingVelocity(MinecraftClient.getInstance(),packet);
    }
}
