package dev.aiarena;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.util.registry.Registry;

final class Observe {
    static String dimension(MinecraftClient mc) {
        return mc.world.getRegistryKey().getValue().toString();
    }
    static JsonObject position(MinecraftClient mc) {
        JsonObject j = new JsonObject();
        j.addProperty("x",mc.player.getX());
        j.addProperty("y",mc.player.getY());
        j.addProperty("z",mc.player.getZ());
        j.addProperty("dimension",dimension(mc));
        return j;
    }
    static JsonObject player(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        JsonObject j = position(mc);
        j.addProperty("yaw",p.yaw);
        j.addProperty("pitch",p.pitch);
        j.addProperty("health",p.getHealth());
        j.addProperty("food",p.getHungerManager().getFoodLevel());
        j.addProperty("saturation",p.getHungerManager().getSaturationLevel());
        j.addProperty("selected_slot",p.inventory.selectedSlot);
        j.addProperty("on_ground",p.isOnGround());
        j.addProperty("sprinting",p.isSprinting());
        j.addProperty("sneaking",p.isSneaking());
        j.addProperty("experience_level",p.experienceLevel);
        j.addProperty("experience_progress",p.experienceProgress);
        j.addProperty("velocity_x",p.getVelocity().x);
        j.addProperty("velocity_y",p.getVelocity().y);
        j.addProperty("velocity_z",p.getVelocity().z);
        JsonArray inventory = new JsonArray();
        for (int i=0;i<p.inventory.size();i++) {
            ItemStack s = p.inventory.getStack(i);
            if (!s.isEmpty()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("slot",i);
                entry.addProperty("item",Registry.ITEM.getId(s.getItem()).toString());
                entry.addProperty("count",s.getCount());
                if (s.hasTag()) entry.addProperty("nbt",s.getTag().toString());
                inventory.add(entry);
            }
        }
        j.add("inventory",inventory);
        JsonArray effects = new JsonArray();
        List<StatusEffectInstance> sorted = new ArrayList<>(p.getStatusEffects());
        sorted.sort(Comparator.comparing(e -> Registry.STATUS_EFFECT.getId(e.getEffectType()).toString()));
        for (StatusEffectInstance e : sorted) {
            JsonObject v = new JsonObject();
            v.addProperty("id",Registry.STATUS_EFFECT.getId(e.getEffectType()).toString());
            v.addProperty("amplifier",e.getAmplifier());
            v.addProperty("duration",e.getDuration());
            effects.add(v);
        }
        j.add("effects",effects);
        return j;
    }

    static String targetType(MinecraftClient mc) {
        return mc.crosshairTarget == null ? "miss" : mc.crosshairTarget.getType().name().toLowerCase(Locale.ROOT);
    }
    static JsonObject target(MinecraftClient mc) {
        JsonObject j = new JsonObject();
        String type = targetType(mc);
        j.addProperty("type",type);
        if ("block".equals(type)) {
            BlockHitResult h = (BlockHitResult) mc.crosshairTarget;
            BlockPos pos = h.getBlockPos();
            j.addProperty("block",Registry.BLOCK.getId(mc.world.getBlockState(pos).getBlock()).toString());
            j.addProperty("x",pos.getX()); j.addProperty("y",pos.getY()); j.addProperty("z",pos.getZ());
            j.addProperty("face",h.getSide().getName());
        } else if ("entity".equals(type)) {
            Entity e = ((EntityHitResult) mc.crosshairTarget).getEntity();
            j.addProperty("entity",Registry.ENTITY_TYPE.getId(e.getType()).toString());
            j.addProperty("entity_id",e.getEntityId());
        }
        return j;
    }

    static JsonObject screen(MinecraftClient mc) {
        JsonObject j = new JsonObject();
        if (!(mc.currentScreen instanceof HandledScreen)) {
            j.addProperty("type", mc.currentScreen == null ? "world" : "other");
            return j;
        }
        ScreenHandler h = ((HandledScreen<?>)mc.currentScreen).getScreenHandler();
        String type = h == mc.player.playerScreenHandler ? "inventory" : Registry.SCREEN_HANDLER.getId(h.getType()).toString();
        j.addProperty("type",type);
        j.addProperty("sync_id",h.syncId);
        j.addProperty("slot_count",h.slots.size());
        j.add("cursor",stack(mc.player.inventory.getCursorStack()));
        JsonArray items = new JsonArray();
        for (int i=0;i<h.slots.size();i++) {
            ItemStack s = h.slots.get(i).getStack();
            if (!s.isEmpty()) {
                JsonObject entry = stack(s);
                entry.addProperty("slot",i);
                items.add(entry);
            }
        }
        j.add("occupied_slots",items);
        return j;
    }
    private static JsonObject stack(ItemStack s) {
        JsonObject j = new JsonObject();
        j.addProperty("item",Registry.ITEM.getId(s.getItem()).toString());
        j.addProperty("count",s.isEmpty() ? 0 : s.getCount());
        return j;
    }

    static JsonArray nearby(MinecraftClient mc) {
        List<Entity> found = mc.world.getEntities(mc.player, mc.player.getBoundingBox().expand(12));
        found.sort(Comparator.comparingDouble(mc.player::squaredDistanceTo));
        JsonArray out = new JsonArray();
        for (int i=0;i<Math.min(16,found.size());i++) {
            Entity e = found.get(i);
            JsonObject j = new JsonObject();
            j.addProperty("entity",Registry.ENTITY_TYPE.getId(e.getType()).toString());
            j.addProperty("x",e.getX()); j.addProperty("y",e.getY()); j.addProperty("z",e.getZ());
            out.add(j);
        }
        return out;
    }

    static int count(MinecraftClient mc, String item) {
        int total = 0;
        for (int i=0;i<mc.player.inventory.size();i++) {
            ItemStack s = mc.player.inventory.getStack(i);
            if (!s.isEmpty() && Registry.ITEM.getId(s.getItem()).toString().equals(item)) total += s.getCount();
        }
        return total;
    }
}
