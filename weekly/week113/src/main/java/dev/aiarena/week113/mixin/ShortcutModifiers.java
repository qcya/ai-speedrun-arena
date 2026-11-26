package dev.aiarena.week113.mixin;

import dev.aiarena.week113.DistanceKeys;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputUtil.class)
abstract class ShortcutModifiers {
    @Inject(method="isKeyPressed", at=@At("HEAD"), cancellable=true)
    private static void localModifiers(long window, int key, CallbackInfoReturnable<Boolean> result) {
        Boolean pressed = DistanceKeys.held(window, key);
        if (pressed != null) result.setReturnValue(pressed);
    }
}
