package dev.aiarena.film;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.aiarena.film.mixin.FilmChatAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/** The flat replay export skips InGameHud.render. Give its real chat renderer the original
 * local keyboard lines and timestamps, independently of the export camera's chat history. */
final class RecordedDebugChat {
    private final JsonArray events;
    private ChatHud hud;
    private int next, previousMs=Integer.MIN_VALUE;

    RecordedDebugChat(JsonArray events) { this.events=events; }

    void render(MinecraftClient mc, MatrixStack matrices, int elapsedMs, int currentTick, int height) {
        if(events==null || events.size()==0) return;
        if(hud==null || elapsedMs<previousMs) {
            hud=new ChatHud(mc);
            next=0;
        }
        previousMs=elapsedMs;
        while(next<events.size()) {
            JsonObject event=events.get(next).getAsJsonObject();
            int time=event.get("elapsed_ms").getAsInt();
            if(time>elapsedMs) break;
            next++;
            int created=event.get("chat_tick").getAsInt();
            if(currentTick-created>=200) continue;
            Text line=Text.Serializer.fromJson(event.get("component"));
            ((FilmChatAccess)hud).astra$addRecordedLine(line,0,created,true);
        }
        if(next==0 || currentTick-events.get(next-1).getAsJsonObject().get("chat_tick").getAsInt()>=200) return;
        RenderSystem.pushMatrix();
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableAlphaTest();
            RenderSystem.translatef(0,height-48,0);
            hud.render(matrices,currentTick);
        } finally {
            RenderSystem.popMatrix();
            RenderSystem.enableAlphaTest();
        }
    }
}
