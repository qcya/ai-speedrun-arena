package dev.aiarena.week29.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HandledScreen.class)
public interface HandledScreenPosition {
    @Accessor("x")
    int astraLeft();

    @Accessor("y")
    int astraTop();
}
