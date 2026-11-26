package dev.aiarena.film.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.StringRenderable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChatHud.class)
public interface FilmChatAccess {
    @Invoker("addMessage")
    void astra$addRecordedLine(StringRenderable text, int messageId, int originalTick, boolean visibleOnly);
}
