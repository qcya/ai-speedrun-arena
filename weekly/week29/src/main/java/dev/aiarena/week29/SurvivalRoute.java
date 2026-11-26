package dev.aiarena.week29;

import com.google.gson.*;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.*;
import net.minecraft.recipe.Recipe;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Executes an editable set-seed itinerary using vanilla controls and inventory interactions. */
public final class SurvivalRoute {
    private static MinecraftClient mc;
    private static RouteMovement nav;
    private static Path planPath, statusPath;
    private static long revision = -1;
    private static JsonArray steps;
    private static int index, age, ticks;
    private static BlockPos target, table, approachFor, approachPos, restAt, pendingRestAt;
    private static int gatherGoal,
            bedCountBeforeRest,
            placedAtTick,
            nextRestUseTick,
            bedMissingSince;
    private static ItemEntity pickupItem;
    private static int pickupStillSince, detourUntil, detourIndex;
    private static double pickupLastX, pickupLastZ;
    private static BlockPos pickupDetour;
    private static String state = "idle", goalName = "";
    private static boolean finished = false;

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    private static int num(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    private static boolean flag(JsonObject o, String k, boolean def) {
        return o.has(k) ? o.get(k).getAsBoolean() : def;
    }

    private static BlockPos coordinate(JsonObject o) {
        return new BlockPos(num(o, "x", 0), num(o, "y", 64), num(o, "z", 0));
    }

    private static String id(Item item) {
        return Registry.ITEM.getId(item).getPath();
    }

    private static String id(Block block) {
        return Registry.BLOCK.getId(block).getPath();
    }

    private static boolean matches(String actual, String filter) {
        for (String f : filter.split("\\|")) {
            if (f.startsWith("*") && actual.endsWith(f.substring(1))) return true;
            if (actual.equals(f)) return true;
        }
        return false;
    }

    private static int count(String filter) {
        int n = 0;
        for (ItemStack stack : mc.player.inventory.main)
            if (!stack.isEmpty() && matches(id(stack.getItem()), filter)) n += stack.getCount();
        return n;
    }

    private static boolean select(String filter) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.inventory.main.get(i);
            if (stack.isEmpty() || !matches(id(stack.getItem()), filter)) continue;
            if (i < 9) {
                mc.player.inventory.selectedSlot = i;
                return true;
            }
            if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return false;
            mc.interactionManager.clickSlot(
                    mc.player.playerScreenHandler.syncId, i, 7, SlotActionType.SWAP, mc.player);
            mc.player.inventory.selectedSlot = 7;
            return true;
        }
        return false;
    }

    static void tool(BlockPos p) {
        BlockState s = mc.world.getBlockState(p);
        int slot = -1;
        float speed = 1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.inventory.main.get(i);
            float candidate = stack.getMiningSpeedMultiplier(s);
            if (candidate > speed) {
                speed = candidate;
                slot = i;
            }
        }
        if (slot >= 0) select(id(mc.player.inventory.main.get(slot).getItem()));
    }

    private static BlockPos nearest(String blocks, int r, int vertical) {
        BlockPos feet = mc.player.getBlockPos(), best = null;
        double distance = Double.POSITIVE_INFINITY;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = feet.getX() - r; x <= feet.getX() + r; x++)
            for (int z = feet.getZ() - r; z <= feet.getZ() + r; z++) {
                int dx = feet.getX() - x, dz = feet.getZ() - z;
                double h = dx * dx + dz * dz;
                if (h > r * r || h >= distance) continue;
                if (!mc.world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(1, feet.getY() - vertical);
                        y <= Math.min(254, feet.getY() + vertical);
                        y++) {
                    double d = h + (feet.getY() - y + .5) * (feet.getY() - y + .5);
                    if (d >= distance) continue;
                    cursor.set(x, y, z);
                    Block b = mc.world.getBlockState(cursor).getBlock();
                    if (b != Blocks.AIR && matches(id(b), blocks)) {
                        distance = d;
                        best = cursor.toImmutable();
                    }
                }
            }
        return best;
    }

    private static ItemEntity drop(String filter) {
        ItemEntity nearest = null;
        double d = 64;
        for (Entity e : mc.world.getEntities())
            if (e instanceof ItemEntity
                    && e.isAlive()
                    && matches(id(((ItemEntity) e).getStack().getItem()), filter)) {
                double dist = mc.player.squaredDistanceTo(e);
                if (dist < d) {
                    nearest = (ItemEntity) e;
                    d = dist;
                }
            }
        return nearest;
    }

    private static BlockPos pickupBarrier(Vec3d from, Vec3d to) {
        BlockHitResult hit =
                mc.world.rayTrace(
                        new RayTraceContext(
                                from,
                                to,
                                RayTraceContext.ShapeType.COLLIDER,
                                RayTraceContext.FluidHandling.NONE,
                                mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos at = hit.getBlockPos();
        BlockState block = mc.world.getBlockState(at);
        if (at.equals(mc.player.getBlockPos().down())
                || block.getHardness(mc.world, at) < 0
                || block.getHardness(mc.world, at) > 8
                || block.getBlock() instanceof BedBlock
                || block.getBlock() instanceof ChestBlock) return null;
        return at;
    }

    private static boolean clearPickupPath(ItemEntity item) {
        Vec3d eyes = mc.player.getCameraPosVec(1);
        Vec3d itemCenter = new Vec3d(item.getX(), item.getY() + .15, item.getZ());
        if (eyes.squaredDistanceTo(itemCenter) > 20.0) return false;
        BlockPos barrier = pickupBarrier(eyes, itemCenter);
        if (barrier != null && nav.mine(barrier)) return true;
        // An unobstructed view of the dropped item can still leave a wall at
        // shoulder or foot height. Each ray ends at the item, never beyond it.
        double footHeight = .3;
        Vec3d feet = new Vec3d(mc.player.getX(), mc.player.getY() + footHeight, mc.player.getZ());
        barrier =
                pickupBarrier(feet, new Vec3d(item.getX(), item.getY() + footHeight, item.getZ()));
        if (barrier != null && nav.mine(barrier)) return true;
        barrier = pickupBarrier(eyes, new Vec3d(item.getX(), item.getY() + 1.45, item.getZ()));
        return barrier != null && nav.mine(barrier);
    }

    private static BlockPos pickupSide(ItemEntity item) {
        double dx = item.getX() - mc.player.getX(), dz = item.getZ() - mc.player.getZ();
        Direction side = Math.abs(dx) > Math.abs(dz) ? Direction.NORTH : Direction.WEST;
        if ((detourIndex++ & 1) != 0) side = side.getOpposite();
        BlockPos current = mc.player.getBlockPos();
        for (int pass = 0; pass < 2; pass++, side = side.getOpposite()) {
            for (int rise : new int[] {0, 1, -1}) {
                BlockPos step = current.offset(side).up(rise);
                if (nav.walkable(step)) return step;
            }
        }
        return null;
    }

    private static boolean pickup(String filter) {
        ItemEntity item = drop(filter);
        if (item == null) return false;
        double dy = item.getY() - mc.player.getY();
        double drift = Math.hypot(item.getX() - mc.player.getX(), item.getZ() - mc.player.getZ());
        double moved = Math.hypot(mc.player.getX() - pickupLastX, mc.player.getZ() - pickupLastZ);
        if (item != pickupItem || moved > .45) {
            if (item != pickupItem) pickupDetour = null;
            pickupItem = item;
            pickupLastX = mc.player.getX();
            pickupLastZ = mc.player.getZ();
            pickupStillSince = ticks;
        }
        if (clearPickupPath(item)) {
            pickupDetour = null;
            pickupStillSince = ticks;
            return true;
        }
        if (pickupDetour != null && ticks < detourUntil) {
            if (!nav.travel(pickupDetour, .25, true)) return true;
            pickupDetour = null;
        }
        if (drift < 4.4 && ticks - pickupStillSince >= (mc.player.horizontalCollision ? 6 : 16)) {
            pickupDetour = pickupSide(item);
            pickupStillSince = ticks;
            if (pickupDetour != null) {
                detourUntil = ticks + 12;
                nav.travel(pickupDetour, .25, true);
                return true;
            }
        }
        if (drift < 1.85 && Math.abs(dy) < 1.55 && !mc.player.horizontalCollision) {
            nav.cancelBreak();
            if (drift > .12) {
                nav.aim(new Vec3d(item.getX(), mc.player.getEyeY(), item.getZ()));
                mc.options.keyForward.setPressed(true);
            }
            mc.options.keyJump.setPressed(dy > .4 || mc.player.isTouchingWater());
            return true;
        }
        nav.travel(item.getBlockPos(), .1, true);
        return true;
    }

    private static BlockPos approach(BlockPos p) {
        if (p.equals(approachFor) && approachPos != null) return approachPos;
        BlockPos best = null;
        double score = Double.POSITIVE_INFINITY;
        for (int h = -2; h <= 1; h++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos q = p.add(dx, h, dz);
                    if (!nav.walkable(q)) continue;
                    double d =
                            mc.player.squaredDistanceTo(Vec3d.ofBottomCenter(q))
                                    + .1 * q.getSquaredDistance(p);
                    if (d < score) {
                        score = d;
                        best = q;
                    }
                }
        approachFor = p;
        approachPos = best == null ? p : best;
        return approachPos;
    }

    private static boolean gather(JsonObject o) {
        String blocks = str(o, "block", "*_log"), items = str(o, "item", blocks);
        int held = count(items);
        if (age == 1) {
            gatherGoal = o.has("additional") ? held + num(o, "additional", 0) : num(o, "count", 1);
        }
        if (held >= gatherGoal) return true;
        int budget = num(o, "max_ticks", 0);
        if (budget > 0 && age >= budget && flag(o, "optional", true)) {
            state = "optional gather timed out: " + items + " held " + held + " of " + gatherGoal;
            return true;
        }
        if (pickup(items)) {
            state = "picking up " + items;
            return false;
        }
        if (target != null && !matches(id(mc.world.getBlockState(target).getBlock()), blocks)) {
            target = null;
            nav.cancelBreak();
        }
        if (target == null && (age % 15 == 1 || age % 15 == 0))
            target = nearest(blocks, num(o, "radius", 25), num(o, "vertical", 10));
        if (target == null) {
            state = "looking for " + blocks;
            return false;
        }
        state = "harvesting " + blocks + " " + target.toShortString();
        tool(target);
        if (!nav.mine(target) && !nav.enterRoom(target) && !nav.clearSight(target)) nav.travel(approach(target), .25, true);
        return false;
    }

    private static String outputRecipe(JsonObject o) {
        String recipe = str(o, "recipe", "");
        if (!recipe.equals("planks")) return recipe;
        for (ItemStack stack : mc.player.inventory.main) {
            String name = id(stack.getItem());
            if (name.endsWith("_log")) return name.substring(0, name.length() - 4) + "_planks";
        }
        return "";
    }

    private static boolean craft(JsonObject o) {
        String recipe = outputRecipe(o);
        String wanted = str(o, "item", str(o, "recipe", "").equals("planks") ? "*_planks" : recipe);
        if (count(wanted) >= num(o, "count", 1)) {
            if (mc.currentScreen instanceof HandledScreen) mc.player.closeHandledScreen();
            return true;
        }
        if (recipe.isEmpty()) {
            ScreenHandler sh = mc.player.currentScreenHandler;
            if (mc.currentScreen instanceof HandledScreen
                    && !sh.getSlot(0).getStack().isEmpty()
                    && matches(id(sh.getSlot(0).getStack().getItem()), wanted)) {
                mc.interactionManager.clickSlot(
                        sh.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                state = "taking " + wanted;
            } else state = "waiting for recipe ingredients";
            return false;
        }
        boolean big = flag(o, "table", false);
        if (big && !(mc.player.currentScreenHandler instanceof CraftingScreenHandler)) {
            if (table == null || mc.world.getBlockState(table).getBlock() != Blocks.CRAFTING_TABLE)
                table = nearest("crafting_table", 5, 4);
            if (table == null) {
                if (!select("crafting_table")) {
                    state = "needs crafting table";
                    return false;
                }
                BlockPos feet = mc.player.getBlockPos();
                for (int reach = 1; reach <= 2 && table == null; reach++) {
                    for (int dy = 0; dy <= 2 && table == null; dy++)
                        for (Direction d : Direction.Type.HORIZONTAL) {
                            BlockPos pos = feet.offset(d, reach).up(dy);
                            if (nav.place(pos)) {
                                table = pos;
                                break;
                            }
                        }
                }
            } else if (age % 4 == 0) nav.use(table);
            state = "opening crafting table";
            return false;
        }
        if (!big && !(mc.currentScreen instanceof InventoryScreen))
            mc.openScreen(new InventoryScreen(mc.player));
        ScreenHandler sh = mc.player.currentScreenHandler;
        if (age % 4 != 0) return false;
        if (!sh.getSlot(0).getStack().isEmpty()
                && matches(id(sh.getSlot(0).getStack().getItem()), wanted)) {
            mc.interactionManager.clickSlot(sh.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
            state = "taking " + wanted;
            return false;
        }
        Optional<? extends Recipe<?>> found =
                mc.world.getRecipeManager().get(new Identifier(recipe));
        if (found.isPresent() && mc.player.getRecipeBook().contains(found.get())) {
            mc.interactionManager.clickRecipe(sh.syncId, found.get(), flag(o, "all", false));
            state = "crafting " + recipe;
        } else state = "recipe locked " + recipe;
        return false;
    }

    private static boolean rest() {
        if (!mc.world.getRegistryKey().equals(World.OVERWORLD)) return true;
        long day = mc.world.getTimeOfDay() % 24000;
        boolean night = day >= 12600 && day < 23800;
        if (age == 1) {
            restAt = null;
            pendingRestAt = null;
            bedMissingSince = -1;
            nextRestUseTick = ticks;
            bedCountBeforeRest = count("*_bed");
        }

        if (pendingRestAt != null) {
            state = "confirming bed at " + pendingRestAt.toShortString();
            if (ticks < nextRestUseTick) return false;
            if (mc.world.getBlockState(pendingRestAt).getBlock() instanceof BedBlock) {
                restAt = pendingRestAt;
                pendingRestAt = null;
            } else {
                // One placement request per sleep step. Some bed placements only
                // appear after the server responds. Never spend another bed here.
                if (pickup("*_bed")) return false;
                if (ticks - placedAtTick < 40) return false;
                if (count("*_bed") < bedCountBeforeRest) {
                    // Snow and other replaceable clicked blocks can shift the foot.
                    BlockPos nearby = nearest("*_bed", 3, 2);
                    if (nearby != null && nearby.getSquaredDistance(pendingRestAt) <= 4) {
                        restAt = nearby;
                        pendingRestAt = null;
                    } else if (ticks - placedAtTick < 100) {
                        return false;
                    }
                }
                if (restAt == null) {
                    state = "no confirmed bed at " + pendingRestAt.toShortString();
                    return true;
                }
            }
        }

        if (restAt != null) {
            if (mc.world.getBlockState(restAt).getBlock() instanceof BedBlock) {
                bedMissingSince = -1;
                if (!night || age > 110) {
                    state = "recovering bed";
                    if (!nav.mine(restAt) && !nav.clearSight(restAt)) {
                        nav.travel(approach(restAt), .4, true);
                    }
                } else if (ticks >= nextRestUseTick) {
                    state = "sleeping in bed";
                    select(
                            "iron_pickaxe|stone_pickaxe|wooden_pickaxe|diamond_pickaxe|golden_pickaxe|netherite_pickaxe");
                    if (nav.use(restAt)) nextRestUseTick = ticks + 5;
                }
                return false;
            }

            if (count("*_bed") >= bedCountBeforeRest) return true;
            // Block removal can arrive before the item entity or inventory update.
            state = "waiting for dropped bed";
            if (pickup("*_bed")) {
                bedMissingSince = -1;
                return false;
            }
            if (bedMissingSince < 0) bedMissingSince = ticks;
            if (ticks - bedMissingSince < 60) return false;
            state = "bed drop no longer visible at " + restAt.toShortString();
            return true;
        }

        if (!night || age > 95 || bedCountBeforeRest == 0) return true;
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof net.minecraft.entity.mob.HostileEntity
                    && mc.player.squaredDistanceTo(e) < 110) {
                return true;
            }
        }
        if (ticks < nextRestUseTick || !select("*_bed")) return false;
        BlockPos feet = mc.player.getBlockPos();
        state = "placing bed";
        for (int distance = 1; distance <= 2; distance++) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = feet.offset(direction, distance).up(dy);
                    BlockPos head = pos.offset(direction);
                    if (!mc.world.getBlockState(head).getMaterial().isReplaceable()) continue;
                    if (!nav.place(pos)) continue;
                    pendingRestAt = pos;
                    placedAtTick = ticks;
                    nextRestUseTick = ticks + 5;
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean execute(JsonObject o) {
        String op = str(o, "op", "");
        goalName = op;
        if (op.equals("gather")) return gather(o);
        if (op.equals("craft")) return craft(o);
        if (op.equals("sleep")) return rest();
        if (op.equals("wait")) {
            state = "waiting";
            return age >= num(o, "ticks", 20);
        }
        if (op.equals("walk") || op.equals("surface")) {
            BlockPos p = coordinate(o);
            if (op.equals("surface") && mc.world.isChunkLoaded(p)) {
                for (int h = 155; h > 10; h--) {
                    BlockPos feet = new BlockPos(p.getX(), h, p.getZ());
                    if (nav.walkable(feet)
                            && !(mc.world.getBlockState(feet.down()).getBlock()
                                    instanceof LeavesBlock)) {
                        p = feet;
                        break;
                    }
                }
            }
            state = "walking " + p.toShortString();
            double radius = o.has("radius") ? o.get("radius").getAsDouble() : 1.0;
            if (radius >= 1.8
                    && age > 180
                    && nav.horizontal(p) < 4.5
                    && Math.abs(mc.player.getY() - p.getY()) < 4.5) return true;
            return nav.travel(p, radius, flag(o, "dig", false) || age > 260);
        }
        if (op.equals("descend")) {
            BlockPos p = coordinate(o);
            String equip = str(o, "equip", "stone_pickaxe|iron_pickaxe|wooden_pickaxe");
            select(equip);
            if (mc.player.getY() <= p.getY() + .5 && nav.horizontal(p) < 1.3) return true;
            if (nav.horizontal(p) > .25 && mc.player.getY() > p.getY() + .5) {
                BlockPos column = new BlockPos(p.getX(), mc.player.getY(), p.getZ());
                state = "aligning above shaft";
                if (nav.horizontal(p) < 1.25) {
                    nav.aim(new Vec3d(p.getX() + .5, mc.player.getEyeY(), p.getZ() + .5));
                    mc.options.keyForward.setPressed(true);
                } else nav.travel(column, .2, true);
                return false;
            }
            BlockPos under = mc.player.getBlockPos().down();
            state = "mining shaft " + under.toShortString();
            nav.mine(under);
            return false;
        }
        if (op.equals("loot")) {
            boolean all = flag(o, "all", false);
            String filter = str(o, "item", "");
            boolean enough = !all && count(filter) >= num(o, "count", 1);
            if (enough && mc.player.currentScreenHandler == mc.player.playerScreenHandler) return true;
            if (mc.player.currentScreenHandler == mc.player.playerScreenHandler) {
                if (target == null)
                    target = o.has("x") ? coordinate(o) : nearest("chest", num(o, "radius", 12), 6);
                if (target != null) {
                    state = "approaching chest " + target.toShortString();
                    if (nav.visibleFace(target) != null) {
                        if (age % 4 == 0) nav.use(target);
                    } else if (!nav.enterRoom(target) && !nav.clearSight(target)) nav.travel(approach(target), .3, true);
                }
                return false;
            }
            boolean done = VisibleSlots.loot(mc, stack -> matches(id(stack.getItem()), filter), enough);
            state = VisibleSlots.status;
            if (done && (all || enough)) {
                mc.player.closeHandledScreen();
                return true;
            }
            return false;
        }
        if (op.equals("armor")) {
            boolean done = VisibleSlots.equip(mc);
            state = VisibleSlots.status;
            return done;
        }
        if (op.equals("select")) return select(str(o, "item", ""));
        if (op.equals("locate")) {
            BlockPos found =
                    nearest(str(o, "block", ""), num(o, "radius", 50), num(o, "vertical", 45));
            state = found == null ? "not visible" : "found " + found.toShortString();
            return false;
        }
        state = "unknown operation " + op;
        return false;
    }

    private static void report() throws IOException {
        JsonObject out = new JsonObject();
        out.addProperty("step", index);
        out.addProperty("age", age);
        out.addProperty("tick", ticks);
        out.addProperty("state", state);
        out.addProperty("op", goalName);
        out.addProperty("x", mc.player.getX());
        out.addProperty("y", mc.player.getY());
        out.addProperty("z", mc.player.getZ());
        out.addProperty("hp", mc.player.getHealth());
        out.addProperty("food", mc.player.getHungerManager().getFoodLevel());
        out.addProperty("day_time", mc.world.getTimeOfDay());
        out.addProperty("dimension", mc.world.getRegistryKey().getValue().toString());
        out.addProperty("complete", finished);
        JsonObject inv = new JsonObject();
        for (ItemStack s : mc.player.inventory.main)
            if (!s.isEmpty()) inv.addProperty(id(s.getItem()), count(id(s.getItem())));
        out.addProperty("path", nav.describe());
        out.addProperty("attack", mc.options.keyAttack.isPressed());
        out.addProperty("forward", mc.options.keyForward.isPressed());
        out.addProperty("cursor", mc.mouse.isCursorLocked());
        out.addProperty("breaking", mc.interactionManager.isBreakingBlock());
        out.add("inventory", inv);
        if (target != null) out.addProperty("target", target.toShortString());
        if (mc.currentScreen != null)
            out.addProperty("screen", mc.currentScreen.getClass().getSimpleName());
        Path temp = statusPath.resolveSibling("survival-status.tmp");
        Files.write(temp, out.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(
                temp,
                statusPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    static void newSession() {
        mc = null;
        nav = null;
        revision = -1;
        steps = null;
        index = 0;
        age = 0;
        ticks = 0;
        target = null;
        table = null;
        approachFor = null;
        approachPos = null;
        restAt = null;
        pendingRestAt = null;
        pickupItem = null;
        pickupDetour = null;
        state = "idle";
        goalName = "";
        finished = false;
        ArenaFood.newSession();
    }

    public static boolean tick(MinecraftClient client) {
        if (client.player == null
                || client.world == null
                || client.interactionManager == null
                || client.world.getRegistryKey().equals(World.END)) return false;
        if (mc != client) {
            mc = client;
            nav = new RouteMovement(mc);
            planPath = Week29.directory().resolve("survival-route.json");
            statusPath = Week29.directory().resolve("survival-status.json");
        }
        ticks++;
        try {
            if (!Files.isRegularFile(planPath)) {
                if (steps != null) {
                    nav.reset();
                    steps = null;
                    revision = -1;
                }
                return false;
            }
            long mod = Files.getLastModifiedTime(planPath).toMillis();
            if (mod != revision) {
                JsonArray newSteps =
                        new JsonParser()
                                .parse(
                                        new String(
                                                Files.readAllBytes(planPath),
                                                StandardCharsets.UTF_8))
                                .getAsJsonArray();
                steps = newSteps;
                revision = mod;
                index = 0;
                age = 0;
                target = null;
                finished = false;
                nav.reset();
            }
            if (finished) return false;
            nav.release();
            if (!mc.player.isAlive()) {
                state = "player died";
                if (ticks % 10 == 0) report();
                return false;
            }
            if (mc.currentScreen == null && !mc.mouse.isCursorLocked()) mc.mouse.lockCursor();
            if (mc.currentScreen == null && ArenaFood.tick(mc)) {
                nav.cancelBreak();
                state = "eating";
                if (ticks % 10 == 0) report();
                return true;
            }
            if (index >= steps.size()) {
                state = "itinerary complete";
                finished = true;
                nav.reset();
                report();
                return false;
            }
            if (mc.currentScreen == null || mc.currentScreen instanceof HandledScreen) {
                age++;
                if (execute(steps.get(index).getAsJsonObject())) {
                    if (nav != null) nav.reset();
                    index++;
                    age = 0;
                    target = null;
                }
            }
            if (ticks % 10 == 0) report();
            return true;
        } catch (Exception e) {
            state = "error " + e.toString();
            try {
                report();
            } catch (Exception ignored) {
            }
            return false;
        }
    }
}
