package dev.aiarena.week29;

import com.google.gson.JsonObject;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Dispatch only inside an explicitly prepared, private practice save. */
public final class Week29 {
    private static IntegratedServer session;
    private static Path directory;
    private static boolean active, started, finished;
    private static long ticks;

    static Path directory() {
        return directory;
    }

    private static void record(String kind, JsonObject fields) {
        fields.addProperty("event", kind);
        fields.addProperty("tick", ticks);
        fields.addProperty("time_ms", System.currentTimeMillis());
        try {
            Files.write(
                    directory.resolve("observed-run.jsonl"),
                    (fields + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LogManager.getLogger("ASTRA Week 29").error("Cannot write run observation", e);
        }
    }

    public static void tick(MinecraftClient mc) {
        IntegratedServer current = mc.getServer();
        if (session != current) {
            session = current;
            directory =
                    current == null ? null : current.getSavePath(WorldSavePath.ROOT).normalize();
            active =
                    directory != null
                            && Files.isRegularFile(directory.resolve("astra-week29.enabled"));
            started = false;
            finished = false;
            ticks = 0;
            SurvivalRoute.newSession();
        }
        if (!active || current == null || current.isRemote() || finished) return;
        if (mc.currentScreen instanceof CreditsScreen) {
            finished = true;
            record("end_credits", new JsonObject());
            return;
        }
        if (mc.player == null || mc.world == null || mc.isPaused()) return;
        if (!started) {
            record("spawn", new JsonObject());
            started = true;
        }
        ticks++;
        boolean end = mc.world.getRegistryKey().equals(World.END);
        if (ticks % 10 == 1) {
            JsonObject s = new JsonObject();
            s.addProperty("dimension", mc.world.getRegistryKey().getValue().toString());
            s.addProperty("x", mc.player.getX());
            s.addProperty("y", mc.player.getY());
            s.addProperty("z", mc.player.getZ());
            s.addProperty("hp", mc.player.getHealth());
            s.addProperty("food", mc.player.getHungerManager().getFoodLevel());
            JsonObject inv = new JsonObject();
            for (ItemStack stack : mc.player.inventory.main) {
                if (stack.isEmpty()) continue;
                String id = Registry.ITEM.getId(stack.getItem()).getPath();
                inv.addProperty(id, (inv.has(id) ? inv.get(id).getAsInt() : 0) + stack.getCount());
            }
            s.add("inv", inv);
            if (end) {
                s.addProperty("stage", EndFight.status());
                for (Entity entity : mc.world.getEntities()) {
                    if (entity instanceof EnderDragonEntity) {
                        EnderDragonEntity dragon = (EnderDragonEntity) entity;
                        s.addProperty("dragon_hp", dragon.getHealth());
                        s.addProperty(
                                "dragon_phase",
                                dragon.getPhaseManager().getCurrent().getType().toString());
                    }
                }
            }
            record("state", s);
        }
        if (!mc.player.isAlive()) return;
        if (end) EndFight.tick(mc);
        else SurvivalRoute.tick(mc);
    }
}
