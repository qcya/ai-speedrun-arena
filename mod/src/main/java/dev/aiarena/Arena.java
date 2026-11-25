package dev.aiarena;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.scoreboard.*;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dev.aiarena.mixin.KeyBindingAccessor;

public final class Arena {
    public static final Arena INSTANCE = new Arena();
    private static final Logger LOG = LogManager.getLogger("AI Speedrun Arena");
    private Bridge io;
    private IntegratedServer server;
    private String session, seed;
    private long clock;
    private int[] course;
    private CompletableFuture<int[]> courseRead;
    private Run run;
    private boolean applied, loading;
    private JsonObject pendingResult;
    private String pendingPath;
    private long lastError;

    private static final class Run {
        String id;
        Plan plan;
        JsonObject result, start;
        JsonArray trace = new JsonArray();
        boolean[] held = new boolean[Plan.KEYS.size()];
        int tick, step, elapsed, loadingTicks, totalLoadingTicks, lastTrace = -1;
        int acceptContainerUntil = -1;
        Screen ownedScreen;
        boolean sawEnd;
    }

    public void before(MinecraftClient mc) {
        try {
            clock++;
            applied = loading = false;
            if (io == null) io = new Bridge(mc.runDirectory);
            IntegratedServer current = mc.getServer();
            if (current != server) {
                if (run != null) finish(mc,"aborted","singleplayer_session_changed");
                server = current;
                session = current == null ? null : UUID.randomUUID().toString().replace("-","");
                seed = null; course = null; courseRead = null;
            }
            if (current != null && current.getOverworld() != null) {
                if (seed == null) seed = Long.toString(current.getOverworld().getSeed());
                updateCourse(current);
            }
            flushResult();
            if (run == null && pendingResult == null) {
                // Never replay a previous valid id. Session-bound requests from a previous world are rejected at admission.
                if (isReady(mc)) {
                    if (Files.exists(io.dir.resolve("request.json"))) admit(mc);
                    else if (Files.exists(io.dir.resolve("course-reset.json"))) reset(mc);
                }
                if (run == null) return;
            }
            if (run == null) return;
            if (server == null || server.isRemote()) { finish(mc,"aborted","not_private_singleplayer"); return; }
            if (won(mc)) { finish(mc,"success","end_exit_credits"); return; }
            if (!mc.isWindowFocused()) { finish(mc,"aborted","window_lost_focus"); return; }
            if (mc.currentScreen instanceof DownloadingTerrainScreen) {
                release(mc);
                loading = true;
                if (++run.loadingTicks > 1200) finish(mc,"aborted","dimension_load_timeout");
                return;
            }
            // Only enter a handled screen opened by a plan's use or inventory action.
            if (mc.currentScreen instanceof HandledScreen && run.ownedScreen == null && run.tick <= run.acceptContainerUntil)
                run.ownedScreen = mc.currentScreen;
            if (run.ownedScreen != null && mc.currentScreen != run.ownedScreen) {
                finish(mc,"aborted","handled_screen_closed_or_changed"); return;
            }
            String blocked = blockReason(mc);
            if (!"ready".equals(blocked) && !("menu_or_pause".equals(blocked)
                    && run.ownedScreen != null && run.ownedScreen == mc.currentScreen && !mc.isPaused())) {
                finish(mc,"aborted",blocked); return;
            }
            run.loadingTicks = 0;
            run.sawEnd |= "minecraft:the_end".equals(Observe.dimension(mc));
            Plan.Step action = run.plan.steps.get(run.step);
            if (action.ui != null || action.clickMode != null) {
                release(mc);
                if (run.elapsed == 0 && !interactWithScreen(mc,action)) return;
                applied = true; return;
            }
            if (run.ownedScreen != null) {
                // Empty steps may deliberately wait for a recipe/slot update inside a container.
                for (boolean key : action.keys) {
                    if (key) { finish(mc,"failed","world_input_inside_handled_screen"); return; }
                }
                if (action.slot >= 0 || action.target != null || action.yaw != null || action.pitch != null) {
                    finish(mc,"failed","world_input_inside_handled_screen"); return;
                }
                release(mc); applied = true; return;
            }
            if (action.keys[8]) run.acceptContainerUntil = run.tick + 20;
            if (action.yaw != null) mc.player.yaw = action.yaw;
            if (action.pitch != null) mc.player.pitch = action.pitch;
            if (action.slot >= 0) mc.player.inventory.selectedSlot = action.slot;
            // Match the exact ray cast that vanilla tick() uses before handleInputEvents().
            mc.gameRenderer.updateTargetedEntity(1.0F);
            if (action.target != null && !action.target.equals(Observe.targetType(mc))) {
                finish(mc,"failed","target_guard_expected_" + action.target + "_saw_" + Observe.targetType(mc));
                return;
            }
            apply(mc,action.keys);
            applied = true;
        } catch (Exception e) {
            if (run != null) finish(mc,"aborted","bridge_error");
            log(e);
        }
    }

    public void after(MinecraftClient mc) {
        if (io == null) return;
        try {
            if (run != null && (applied || loading)) {
                run.tick++;
                if (loading) run.totalLoadingTicks++;
                if (won(mc)) finish(mc,"success","end_exit_credits");
                else if (mc.player != null && !mc.player.isAlive()) finish(mc,"aborted","player_died");
                else if (applied) {
                    if (mc.player == null || mc.world == null) { finish(mc,"aborted","world_unavailable"); }
                    else {
                        if (run.tick % 20 == 0) sample(mc);
                        if (goal(mc)) finish(mc,"success","goal_reached");
                        else if (++run.elapsed >= run.plan.steps.get(run.step).ticks) {
                            run.elapsed = 0;
                            if (++run.step >= run.plan.steps.size()) finish(mc,"failed","plan_exhausted");
                        }
                    }
                }
            }
            flushResult();
            if (clock % 5 == 0) publish(mc);
        } catch (Exception e) {
            if (run != null) finish(mc,"aborted","bridge_error");
            log(e);
        }
    }

    private void updateCourse(IntegratedServer current) {
        if (courseRead != null && courseRead.isDone()) {
            course = courseRead.getNow(null);
            courseRead = null;
        }
        if (courseRead != null || clock % 5 != 0) return;
        // Scoreboard data is owned by the integrated server thread.
        courseRead = current.submit(() -> {
            Scoreboard b = current.getScoreboard();
            ScoreboardObjective o = b.getNullableObjective("asra_course");
            if (o == null || !b.playerHasObjective("#generation",o) || !b.playerHasObjective("#villagers",o)) return null;
            int gen = b.getPlayerScore("#generation",o).getScore();
            int variant = b.getPlayerScore("#villagers",o).getScore();
            return gen > 0 && (variant == 0 || variant == 1) ? new int[]{gen,variant} : null;
        });
    }

    private void courseFields(JsonObject j) {
        if (course != null) {
            j.addProperty("course_id","asa:course_v1");
            j.addProperty("course_generation",course[0]);
            j.addProperty("course_variant",course[1]);
        }
    }

    private boolean isReady(MinecraftClient mc) { return "ready".equals(blockReason(mc)); }
    private String blockReason(MinecraftClient mc) {
        if (server == null || server.isRemote() || mc.player == null || mc.world == null || seed == null)
            return "private_singleplayer_world_required";
        if (mc.interactionManager == null || mc.interactionManager.getCurrentGameMode() != GameMode.SURVIVAL)
            return "survival_required";
        if (!mc.player.isAlive()) return "player_died";
        if (mc.currentScreen != null || mc.isPaused()) return "menu_or_pause";
        if (!mc.isWindowFocused()) return "window_lost_focus";
        return "ready";
    }

    private void publish(MinecraftClient mc) throws IOException {
        JsonObject j = new JsonObject();
        j.addProperty("game_version","1.16.1");
        j.addProperty("world_seed", seed);
        j.addProperty("session_id",session);
        j.addProperty("heartbeat_ms",System.currentTimeMillis());
        String reason = blockReason(mc);
        boolean busy = run != null || pendingResult != null;
        j.addProperty("ready",!busy && "ready".equals(reason));
        j.addProperty("reason",busy ? "busy" : reason);
        j.addProperty("active_id",run == null ? null : run.id);
        if (run != null) {
            j.addProperty("tick",run.tick);
            j.addProperty("step",run.step);
        }
        if (server != null && !server.isRemote() && mc.player != null && mc.world != null) {
            j.addProperty("world_time",mc.world.getTime());
            if (mc.interactionManager != null) j.addProperty("game_mode",mc.interactionManager.getCurrentGameMode().getName());
            j.add("player",Observe.player(mc));
            j.add("target",Observe.target(mc));
            j.add("screen",Observe.screen(mc));
            j.add("nearby_entities",Observe.nearby(mc));
            courseFields(j);
        }
        io.put("state.json",j);
    }

    private void admit(MinecraftClient mc) throws IOException {
        JsonObject req = io.take("request.json",16L * 1024 * 1024);
        if (req == null) return;
        String id = Bridge.id(req);
        if (Files.exists(io.dir.resolve("results/"+id+".json"))) return;
        Run r = new Run(); r.id = id;
        r.result = new JsonObject();
        r.result.addProperty("id",id); r.result.addProperty("game_version","1.16.1");
        r.result.addProperty("world_seed",seed); r.result.addProperty("game_mode","survival");
        r.result.addProperty("session_id",session); r.result.addProperty("start_world_time",mc.world.getTime());
        r.start = Observe.player(mc); courseFields(r.start); courseFields(r.result);
        r.result.add("start",r.start); r.result.add("trace",r.trace);
        run = r; sample(mc);
        try {
            if (req.has("session_id") && !session.equals(Plan.string(req,"session_id"))) {
                finish(mc,"aborted","stale_session"); return;
            }
            if (expired(req)) { finish(mc,"aborted","expired_request"); return; }
            if (req.has("expected_course_generation") && (course == null || Plan.integer(req,"expected_course_generation",1,Integer.MAX_VALUE) != course[0])) {
                finish(mc,"aborted","course_generation_changed"); return;
            }
            if (req.has("expected_course_variant") && (course == null || Plan.integer(req,"expected_course_variant",0,1) != course[1])) {
                finish(mc,"aborted","course_variant_changed"); return;
            }
            JsonObject p = req.getAsJsonObject("plan");
            r.plan = new Plan(p);
            r.result.addProperty("agent",r.plan.agent);
            r.result.add("goal",p.get("goal"));
            if ("item".equals(r.plan.type) && goal(mc)) finish(mc,"failed","goal_already_met_at_start");
        } catch (RuntimeException e) {
            finish(mc,"failed","invalid_plan: " + e.getMessage());
        }
    }

    private static boolean expired(JsonObject req) {
        return req.has("expires_at_ms") && Plan.number(req,"expires_at_ms") < System.currentTimeMillis();
    }

    private void reset(MinecraftClient mc) throws IOException {
        JsonObject req = io.take("course-reset.json",4096);
        if (req == null) return;
        String id = Bridge.id(req), path = "resets/"+id+".json";
        if (Files.exists(io.dir.resolve(path))) return;
        JsonObject ack = new JsonObject();
        ack.addProperty("id",id);
        String why = null;
        if (!isReady(mc) || run != null || pendingResult != null || Files.exists(io.dir.resolve("request.json"))) why = "busy";
        else if (course == null || !"minecraft:overworld".equals(Observe.dimension(mc))) why = "course_required";
        else if (!mc.player.hasPermissionLevel(2)) why = "commands_required";
        else if (req.has("session_id") && !session.equals(Plan.string(req,"session_id"))) why = "stale_session";
        else if (expired(req)) why = "expired_request";
        if (why == null) mc.player.sendChatMessage("/function asa:reset");
        ack.addProperty("status",why == null ? "sent" : "refused");
        ack.addProperty("reason",why == null ? "course_reset_submitted" : why);
        io.put(path,ack);
    }

    private boolean won(MinecraftClient mc) {
        return run != null && run.plan != null && "dragon_exit".equals(run.plan.type) && run.sawEnd && mc.currentScreen instanceof CreditsScreen;
    }
    private boolean goal(MinecraftClient mc) {
        if ("item".equals(run.plan.type)) return Observe.count(mc,run.plan.item) >= run.plan.count;
        if (!"displacement".equals(run.plan.type) || !Observe.dimension(mc).equals(run.start.get("dimension").getAsString())) return false;
        double dx = mc.player.getX() - run.start.get("x").getAsDouble();
        double dz = mc.player.getZ() - run.start.get("z").getAsDouble();
        return Math.hypot(dx,dz) >= run.plan.blocks;
    }

    private boolean interactWithScreen(MinecraftClient mc, Plan.Step action) {
        if ("inventory".equals(action.ui)) {
            if (mc.currentScreen != null || mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
                finish(mc,"failed","inventory_requires_world_view"); return false;
            }
            InventoryScreen inv = new InventoryScreen(mc.player);
            mc.openScreen(inv);
            run.ownedScreen = inv;
            return true;
        }
        if (run.ownedScreen == null || run.ownedScreen != mc.currentScreen || !(mc.currentScreen instanceof HandledScreen)) {
            finish(mc,"failed","handled_screen_required"); return false;
        }
        if ("close".equals(action.ui)) {
            run.ownedScreen = null; run.acceptContainerUntil = -1;
            mc.player.closeHandledScreen();
            return true;
        }
        ScreenHandler handler = ((HandledScreen<?>)mc.currentScreen).getScreenHandler();
        if (handler != mc.player.currentScreenHandler || action.clickSlot >= handler.slots.size()) {
            finish(mc,"failed","slot_out_of_range_or_changed_handler"); return false;
        }
        SlotActionType type = "pickup".equals(action.clickMode) ? SlotActionType.PICKUP : SlotActionType.QUICK_MOVE;
        mc.interactionManager.clickSlot(handler.syncId,action.clickSlot,action.clickButton,type,mc.player);
        return true;
    }

    private void apply(MinecraftClient mc, boolean[] keys) {
        GameOptions k = mc.options;
        KeyBinding[] bound = {k.keyForward,k.keyBack,k.keyLeft,k.keyRight,k.keyJump,k.keySprint,k.keySneak,k.keyAttack,k.keyUse};
        for (int i=0;i<bound.length;i++) {
            KeyBindingAccessor b = (KeyBindingAccessor)(Object)bound[i];
            b.arenaSetHeld(keys[i]);
            // Vanilla consumes wasPressed() for initial attack/use and isPressed() for holding.
            b.arenaSetPressCount((i==7 || i==8) && keys[i] && !run.held[i] ? 1 : 0);
        }
        System.arraycopy(keys,0,run.held,0,keys.length);
    }
    private void release(MinecraftClient mc) {
        if (run != null && mc.options != null) apply(mc,new boolean[Plan.KEYS.size()]);
    }

    private void sample(MinecraftClient mc) {
        if (run.lastTrace == run.tick || mc.world == null || mc.player == null) return;
        JsonObject point = Observe.position(mc);
        point.addProperty("tick",run.tick);
        point.addProperty("step",run.step);
        run.trace.add(point);
        run.lastTrace = run.tick;
    }

    private void finish(MinecraftClient mc, String status, String reason) {
        if (run == null) return;
        release(mc);
        if (run.ownedScreen != null && run.ownedScreen == mc.currentScreen && mc.player != null && mc.getServer() == server)
            mc.player.closeHandledScreen();
        if (mc.getServer() == server && mc.world != null && mc.player != null) {
            sample(mc); run.result.add("end",Observe.player(mc));
        }
        run.result.addProperty("status",status); run.result.addProperty("reason",reason);
        run.result.addProperty("ticks",run.tick);
        run.result.addProperty("loading_ticks",run.totalLoadingTicks);
        pendingResult = run.result;
        pendingPath = "results/"+run.id+".json";
        run = null;
    }
    private void flushResult() throws IOException {
        if (pendingResult != null) {
            io.put(pendingPath,pendingResult);
            pendingResult = null; pendingPath = null;
        }
    }
    public void shutdown(MinecraftClient mc) {
        if (io == null) return;
        try {
            finish(mc,"aborted","client_stopped");
            flushResult();
            JsonObject j = new JsonObject();
            j.addProperty("ready",false); j.addProperty("reason","client_stopped");
            j.addProperty("game_version","1.16.1"); j.addProperty("heartbeat_ms",System.currentTimeMillis());
            io.put("state.json",j);
        } catch (Exception e) { log(e); }
    }
    private void log(Exception e) {
        if (clock - lastError >= 100 || lastError == 0) {
            LOG.error("Arena I/O or execution failed",e); lastError = clock;
        }
    }
}
