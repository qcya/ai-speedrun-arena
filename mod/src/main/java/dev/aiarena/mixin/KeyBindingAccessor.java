package dev.aiarena.mixin;

import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    // Direct state also works when the player has configured toggle sneak/sprint.
    @Accessor("pressed") void arenaSetHeld(boolean held);
    @Accessor("timesPressed") void arenaSetPressCount(int presses);
}
