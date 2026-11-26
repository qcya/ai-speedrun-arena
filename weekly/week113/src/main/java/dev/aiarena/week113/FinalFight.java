package dev.aiarena.week113;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.world.World;

import java.io.*;
import java.util.*;

/** Reads the dragon, builds with held items and fires through the real bow mechanics. */
public final class FinalFight {
    private static final List<int[]> blocks = new ArrayList<>();
    private static int next, phase, age, held, jumpAge;
    private static int aimTicks, boardRequest, boatRequest, supportY, pearlAge, brakeTicks;
    private static double launchServerSpeed;
    private static long pearlThrownAt;
    private static boolean waterPlaced, landed, killed, pearlAttempted, pearlLanded, eating;
    private static boolean crossingChosen, keepPearl;
    private static double lastDragonDistance, lastDragonY;
    public static String status = "end approach";

    static {
        try (BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(
                                FinalFight.class.getResourceAsStream("/end-placements.csv"),
                                "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int[] block = Arrays.stream(line.split(",")).mapToInt(Integer::parseInt).toArray();
                blocks.add(block);
                // Close the champion's running-jump gap all the way to the shelf.
                // Each extra block is spent from the runner's survival inventory.
                if (block[0] == 5940)
                    for (int x = 77; x >= 73; x--) blocks.add(new int[] {5941 + 77 - x, x, 54, 4});
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void reset() {
        next = phase = age = held = jumpAge = aimTicks = 0;
        boardRequest = boatRequest = -100;
        supportY = -1;
        brakeTicks = 0;
        EndCrossing.reset();
        pearlAttempted = pearlLanded = eating = crossingChosen = keepPearl = false;
        pearlAge = 0;
        waterPlaced = landed = killed = false;
        launchServerSpeed = 0;
        lastDragonDistance = Double.POSITIVE_INFINITY;
        lastDragonY = Double.NaN;
        status = "end approach";
    }

    private static boolean match(Item item, int kind) {
        if (kind == 4) return item == Items.ENDER_PEARL;
        if (kind == 3) return item == Items.WATER_BUCKET;
        if (kind == 2) return item instanceof BoatItem;
        if (kind == 1) return item == Items.BOW;
        return item == Items.NETHERRACK
                || item == Items.DIRT
                || item == Items.COBBLESTONE
                || item == Items.BLACKSTONE
                || item == Items.END_STONE
                || item == Items.OAK_PLANKS
                || item == Items.NETHER_BRICKS
                || item == Items.CRACKED_POLISHED_BLACKSTONE_BRICKS
                || item == Items.CHISELED_POLISHED_BLACKSTONE;
    }

    private static boolean select(MinecraftClient m, int kind) {
        for (int i = 0; i < 9; i++)
            if (match(m.player.inventory.getStack(i).getItem(), kind)) {
                if (m.currentScreen instanceof InventoryScreen) m.player.closeHandledScreen();
                m.player.inventory.selectedSlot = i;
                return m.currentScreen == null;
            }
        if (m.currentScreen == null) {
            m.openScreen(new InventoryScreen(m.player));
            return false;
        }
        if (!(m.currentScreen instanceof InventoryScreen)) return false;
        for (int i = 9; i < 36; i++)
            if (match(m.player.inventory.getStack(i).getItem(), kind)) {
                m.interactionManager.clickSlot(
                        m.player.playerScreenHandler.syncId, i, 6, SlotActionType.SWAP, m.player);
                return false;
            }
        m.player.closeHandledScreen();
        return false;
    }

    static boolean selectBuildingBlock(MinecraftClient m) {
        return select(m, 0);
    }

    private static void face(MinecraftClient m, Vec3d v) {
        Race.aim(m, v);
        m.gameRenderer.updateTargetedEntity(1);
    }

    private static boolean place(MinecraftClient m, BlockPos p) {
        Vec3d eye = m.player.getCameraPosVec(1);
        BlockPos anchor = null;
        Direction face = null;
        Vec3d point = null;
        double best = Double.MAX_VALUE;
        for (Direction direction : Direction.values()) {
            BlockPos a = p.offset(direction.getOpposite());
            BlockState s = m.world.getBlockState(a);
            if (s.getMaterial().isReplaceable() || s.getCollisionShape(m.world, a).isEmpty())
                continue;
            Vec3d v =
                    Vec3d.ofCenter(a)
                            .add(
                                    direction.getOffsetX() * .499,
                                    direction.getOffsetY() * .499,
                                    direction.getOffsetZ() * .499);
            if (eye.squaredDistanceTo(v) > 20) continue;
            // Pick a face visible from the eye to avoid turning back and forth between support
            // blocks.
            Vec3d n =
                    new Vec3d(
                            direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
            if (eye.subtract(v).dotProduct(n) <= 0) continue;
            if (eye.squaredDistanceTo(v) < best) {
                anchor = a;
                face = direction;
                point = v;
                best = eye.squaredDistanceTo(v);
            }
        }
        if (point == null) return false;
        face(m, point);
        if (!(m.crosshairTarget instanceof BlockHitResult)) return false;
        BlockHitResult h = (BlockHitResult) m.crosshairTarget;
        if (h.getType() != HitResult.Type.BLOCK
                || !h.getBlockPos().equals(anchor)
                || h.getSide() != face) return false;
        if (m.interactionManager.interactBlock(m.player, m.world, Hand.MAIN_HAND, h).isAccepted()) {
            m.player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private static boolean deckSection(MinecraftClient mc, int[] step) {
        int x = step[1], y = step[2];
        BlockPos target = new BlockPos(x, y, 0);
        Race.stop(mc);
        if (!select(mc, 0)) return true;
        if (x == 0) {
            status = "building the vertical launch pillar";
            double dx = .5 - mc.player.getX(), dz = .5 - mc.player.getZ();
            if (Math.hypot(dx, dz) > .28) {
                moveOnDeck(mc, dx, dz);
                return true;
            }
            BlockPos support = target.down();
            face(mc, new Vec3d(.5, support.getY() + .999, .5));
            mc.options.keyJump.setPressed(true);
            if (!mc.player.getBoundingBox().intersects(new Box(target))) place(mc, target);
            return true;
        }
        status = "sneak-bridging the horizontal launch deck";
        mc.options.keySneak.setPressed(true);
        BlockPos anchor = target.west();
        if (mc.world.getBlockState(anchor).getMaterial().isReplaceable()) return true;
        Vec3d side = new Vec3d(anchor.getX() + .999, anchor.getY() + .72, .5);
        face(mc, side);
        if (mc.crosshairTarget instanceof BlockHitResult) {
            BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
            if (hit.getType() == HitResult.Type.BLOCK
                    && anchor.equals(hit.getBlockPos())
                    && hit.getSide() == Direction.EAST) {
                if (mc.interactionManager
                        .interactBlock(mc.player, mc.world, Hand.MAIN_HAND, hit)
                        .isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
                return true;
            }
        }
        // The side becomes visible just beyond the last full block. Leave
        // space for friction to settle before the edge of the sneak allowance.
        double advance = mc.player.getX() - anchor.getX() - .5;
        if (advance < .55) {
            moveOnDeck(mc, anchor.getX() + 1.14 - mc.player.getX(), .5 - mc.player.getZ());
            if (advance < .03) mc.options.keySneak.setPressed(false);
        }
        return true;
    }

    private static void moveOnDeck(MinecraftClient mc, double dx, double dz) {
        double dist = Math.hypot(dx, dz);
        if (dist < .02) return;
        double a = Math.toRadians(mc.player.yaw);
        double f = (-Math.sin(a) * dx + Math.cos(a) * dz) / dist;
        double l = (Math.cos(a) * dx + Math.sin(a) * dz) / dist;
        mc.options.keyForward.setPressed(f > .3827);
        mc.options.keyBack.setPressed(f < -.3827);
        mc.options.keyLeft.setPressed(l > .3827);
        mc.options.keyRight.setPressed(l < -.3827);
        mc.options.keySneak.setPressed(true);
    }

    private static boolean bridgeToIsland(MinecraftClient mc, BlockPos target) {
        Race.stop(mc);
        if (!select(mc, 0)) return true;
        BlockPos anchor = target.east();
        double ax = anchor.getX() + .5, az = anchor.getZ() + .5;
        BlockPos stepUp = anchor.east().down();
        if (mc.player.getY() < target.getY() + .94
                && Math.abs(mc.player.getX() - stepUp.getX() - .5) < .81
                && Math.abs(mc.player.getZ() - stepUp.getZ() - .5) < .6
                && mc.world.getBlockState(stepUp).getMaterial().isReplaceable()) {
            status = "placing the intermediate step onto the bridge";
            face(mc, Vec3d.ofCenter(stepUp.down()).add(0, .499, 0));
            mc.options.keyJump.setPressed(true);
            if (!mc.player.getBoundingBox().intersects(new Box(stepUp))) place(mc, stepUp);
            return true;
        }
        if (mc.player.getY() < target.getY() + .94
                || mc.player.getX() > ax + .65
                || Math.abs(mc.player.getZ() - az) > .80) {
            status = "stepping onto the island bridge";
            face(mc, new Vec3d(ax, mc.player.getEyeY() - .2, az));
            moveOnDeck(mc, ax - mc.player.getX(), az - mc.player.getZ());
            mc.options.keySneak.setPressed(false);
            mc.options.keyJump.setPressed(
                    mc.player.getY() < target.getY() + .94 || mc.player.horizontalCollision);
            return true;
        }
        status = "bridging west toward the island";
        // Aim back toward the inner face from the moment we start each block.
        // Crossing the face no longer asks the camera to spin 180 degrees.
        double x = mc.player.getX();
        double z = mc.player.getZ();
        double sightX = Math.max(anchor.getX() + .001, x + .025);
        face(mc, new Vec3d(sightX, anchor.getY() + .72, z));
        moveOnDeck(mc, -1, MathHelper.clamp(az - z, -.15, .15));
        // From here the player's bounding box still overlaps the last solid
        // block. Sneaking automatically holds the lip if a placement is delayed.
        mc.options.keySneak.setPressed(x < anchor.getX() - .035);
        if (mc.crosshairTarget instanceof BlockHitResult) {
            BlockHitResult h = (BlockHitResult) mc.crosshairTarget;
            if (h.getType() == HitResult.Type.BLOCK
                    && anchor.equals(h.getBlockPos())
                    && h.getSide() == Direction.WEST) {
                if (mc.interactionManager
                        .interactBlock(mc.player, mc.world, Hand.MAIN_HAND, h)
                        .isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
            }
        }
        return true;
    }

    private static boolean food(Item item) {
        return item == Items.COOKED_SALMON
                || item == Items.COOKED_COD
                || item == Items.BREAD
                || item == Items.COOKED_BEEF
                || item == Items.COOKED_PORKCHOP;
    }

    private static boolean recoverHunger(MinecraftClient m) {
        if (eating) {
            if (m.player.isUsingItem()) {
                Race.stop(m);
                m.options.keyUse.setPressed(true);
                status = "eating before the End crossing";
                return true;
            }
            eating = false;
            m.options.keyUse.setPressed(false);
        }
        int hunger = m.player.getHungerManager().getFoodLevel();
        if (hunger >= 20 || (hunger >= 18 && m.player.getHealth() >= 14)) return false;
        if (hunger > 15 && m.player.getHealth() >= 16) return false;
        if (!m.player.isOnGround()) return false;
        Hand use = Hand.OFF_HAND;
        if (!food(m.player.getOffHandStack().getItem())) {
            use = Hand.MAIN_HAND;
            int slot = -1;
            for (int i = 0; i < 36; i++)
                if (food(m.player.inventory.getStack(i).getItem())) {
                    slot = i;
                    break;
                }
            if (slot < 0) return false;
            if (slot < 9) {
                if (m.currentScreen instanceof InventoryScreen) m.player.closeHandledScreen();
                m.player.inventory.selectedSlot = slot;
            } else {
                if (m.currentScreen == null) m.openScreen(new InventoryScreen(m.player));
                else if (m.currentScreen instanceof InventoryScreen)
                    m.interactionManager.clickSlot(
                            m.player.playerScreenHandler.syncId,
                            slot,
                            6,
                            SlotActionType.SWAP,
                            m.player);
                return true;
            }
        }
        Race.stop(m);
        if (m.interactionManager.interactItem(m.player, m.world, use).isAccepted()
                && m.player.isUsingItem()) {
            eating = true;
            m.options.keyUse.setPressed(true);
            status = "eating before the End crossing";
            return true;
        }
        return false;
    }

    private static ServerPlayerEntity endPlayer(MinecraftClient mc) {
        if (mc.getServer() == null) return null;
        ServerPlayerEntity p = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        return p != null && p.getEntityWorld().getRegistryKey() == World.END ? p : null;
    }

    private static boolean chooseCrossing(MinecraftClient mc) {
        if (crossingChosen || next != 0 || mc.player.getX() < 96) return true;
        ServerPlayerEntity server = endPlayer(mc);
        // A freshly allocated client entity initially reports 20 HP even when
        // the actual player arrived wounded. Wait for the real arrival position.
        if (server == null || server.getX() < 96 || Math.abs(server.getZ()) > 3) {
            Race.stop(mc);
            status = "arriving on the End platform";
            return false;
        }
        keepPearl = server.getHealth() < 15;
        crossingChosen = true;
        if (mc.options.viewDistance < 8
                && !keepPearl
                && mc.player.inventory.count(Items.ENDER_PEARL) > 0) {
            mc.options.viewDistance = 8;
            mc.options.onPlayerModelPartChange();
        }
        return true;
    }

    private static long pearlClock(MinecraftClient m) {
        if (m.getServer() != null && m.getServer().getWorld(net.minecraft.world.World.END) != null)
            return m.getServer().getWorld(net.minecraft.world.World.END).getTime();
        return m.world.getTime();
    }

    /** Use only a pearl left in the actual inventory after the portal is filled. */
    private static boolean pearlFromArrival(MinecraftClient m) {
        if (keepPearl || pearlLanded || (!pearlAttempted && next != 0)) return false;
        if (!pearlAttempted && m.player.inventory.count(Items.ENDER_PEARL) == 0) return false;
        if (pearlAttempted) {
            if (m.player.getX() < 72 && m.player.getY() > 52) {
                // The native pearl has already teleported the player to real island terrain.
                while (next < blocks.size() && blocks.get(next)[0] < 5958) next++;
                Race.advanceTo(5958);
                pearlLanded = true;
                return false;
            }
            // The client can continue ticking while the integrated server generates
            // the End. Only time a projectile out against the server simulation.
            if (pearlClock(m) - pearlThrownAt > 180) return false;
            Race.stop(m);
            status = "waiting for the pearl to land on the main island";
            return true;
        }
        if (m.player.getX() < 97 || Math.abs(m.player.getZ()) > 3) return false;
        Race.stop(m);
        status = "throwing the spare pearl across the End gap";
        if (!select(m, 4) || !m.player.isOnGround()) return true;
        double yaw = Math.toRadians(83), pitch = Math.toRadians(-39);
        Vec3d dir =
                new Vec3d(
                        -Math.sin(yaw) * Math.cos(pitch),
                        -Math.sin(pitch),
                        Math.cos(yaw) * Math.cos(pitch));
        face(m, m.player.getCameraPosVec(1).add(dir.multiply(10)));
        ServerPlayerEntity server = endPlayer(m);
        if (server != null
                && server.isOnGround()
                && Math.abs(MathHelper.wrapDegrees(server.yaw - 83)) < .25
                && Math.abs(server.pitch + 39) < .25
                && Math.abs(MathHelper.wrapDegrees(m.player.yaw - 83)) < .15
                && Math.abs(m.player.pitch + 39) < .15
                && ++pearlAge >= 2) {
            int before = m.player.inventory.count(Items.ENDER_PEARL);
            m.interactionManager.interactItem(m.player, m.world, Hand.MAIN_HAND);
            if (m.player.inventory.count(Items.ENDER_PEARL) < before) {
                pearlAttempted = true;
                pearlThrownAt = pearlClock(m);
                pearlAge = 0;
                m.player.swingHand(Hand.MAIN_HAND);
            }
        }
        return true;
    }

    private static boolean pillarUnderBoat(MinecraftClient m) {
        Race.stop(m);
        if (!select(m, 0)) return true;
        double dx = SEAT.x + .055 - m.player.getX(), dz = SEAT.z - .25 - m.player.getZ();
        if (Math.hypot(dx, dz) > .22) {
            brakeTicks = 0;
            // Record that the final approach now owns the controls before
            // walking away from the last overland waypoint.
            if (supportY == -1) supportY = 0;
            status = "aligning below the boat placement point";
            face(
                    m,
                    new Vec3d(
                            SEAT.x + .055,
                            m.player.getEyeY() - .48 * Math.hypot(dx, dz),
                            SEAT.z - .25));
            moveOnDeck(m, dx, dz);
            if (Math.hypot(dx, dz) > 1.6) {
                m.options.keySneak.setPressed(false);
                m.options.keyJump.setPressed(m.player.horizontalCollision);
            }
            return true;
        }
        if (supportY <= 0) {
            if (!pearlLanded) {
                m.options.keySneak.setPressed(true);
                if (++brakeTicks < 4) return true;
            }
            if (!m.player.isOnGround()
                    || Math.hypot(m.player.getVelocity().x, m.player.getVelocity().z) > .06)
                return true;
            supportY = m.player.getBlockPos().getY();
        }
        while (supportY <= 84
                && !m.world
                        .getBlockState(new BlockPos(5, supportY, 0))
                        .getMaterial()
                        .isReplaceable()) supportY++;
        if (supportY > 84) {
            if (m.player.isOnGround() && m.player.getY() > 84.95) {
                next = blocks.size();
                Race.advanceTo(6435);
            }
            return true;
        }
        BlockPos target = new BlockPos(5, supportY, 0);
        status = "building the launch column under the boat";
        face(m, new Vec3d(5.5, supportY - .001, .5));
        if (Math.hypot(dx, dz) > .031) moveOnDeck(m, dx, dz);
        // Release jump in the air so the next grounded jump is immediate.
        m.options.keyJump.setPressed(m.player.isOnGround());
        if (!m.player.getBoundingBox().intersects(new Box(target))) place(m, target);
        return true;
    }

    private static boolean build(MinecraftClient m, int t) {
        while (next < blocks.size()) {
            int[] a = blocks.get(next);
            BlockPos b = new BlockPos(a[1], a[2], a[3]);
            if (a[0] >= 5958 && a[0] < 6187) {
                // After reaching the shelf, its continuous end-stone slope needs no
                // sprint-jump scaffolding. Aim across the flat region toward the fountain.
                // Route south of the obsidian tower centered near X=40, Z=0.
                double gx = m.player.getX() > 40 ? 39 : m.player.getX() > 33 ? 32 : 9.5;
                double gz = m.player.getX() > 33 ? 7.1 : .65;
                double dx = gx - m.player.getX(), dz = gz - m.player.getZ();
                if (supportY >= 0
                        || (gx == 9.5
                                && Math.hypot(dx, dz) < 1.0
                                && Math.abs(m.player.getY() - 62) < 2)) {
                    return pillarUnderBoat(m);
                }
                Race.stop(m);
                status = "sprinting across the natural island";
                // Keep the line of sight below Endermen's heads while sprinting.
                face(m, new Vec3d(gx, m.player.getEyeY() - .5 * Math.hypot(dx, dz), gz));
                double aYaw = Math.toRadians(m.player.yaw), length = Math.hypot(dx, dz);
                m.options.keyForward.setPressed(
                        (-Math.sin(aYaw) * dx + Math.cos(aYaw) * dz) / Math.max(.01, length) > .55);
                m.options.keySprint.setPressed(length > 2);
                m.options.keyJump.setPressed(length > 2 || m.player.horizontalCollision);
                return true;
            }
            if (!m.world.getBlockState(b).getMaterial().isReplaceable()) {
                next++;
                if (a[0] >= 6231 || (a[0] >= 5901 && a[0] <= 5945)) Race.advanceTo(a[0]);
                continue;
            }
            if (a[0] >= 6231
                    && (a[0] > 6231
                            || (m.player.getY() >= 65.7
                                    && Math.hypot(m.player.getX() - .5, m.player.getZ() - .5)
                                            < 1.15))) {
                Race.cap(a[0]);
                return deckSection(m, a);
            }
            if (a[0] >= 5901 && a[0] <= 5945 && m.player.getX() < 88) {
                Race.cap(a[0]);
                return bridgeToIsland(m, b);
            }
            Race.cap(a[0] + 2);
            if (t < a[0] - 12
                    || m.player.getCameraPosVec(1).squaredDistanceTo(Vec3d.ofCenter(b)) > 22)
                return false;
            if (!select(m, 0)) return true;
            boolean under = m.player.getBoundingBox().intersects(new Box(b));
            if (t < a[0] - 2 && !under) return false;
            Race.stop(m);
            if (under
                    || (m.player.getBlockPos().getX() == b.getX()
                            && m.player.getBlockPos().getZ() == b.getZ())) {
                m.options.keyJump.setPressed(true);
            }
            status = "placing support " + b.toShortString();
            if (!under) {
                if (place(m, b)) {
                    jumpAge = 0;
                    return true;
                }
            }
            if (++jumpAge % 16 == 0) return false;
            return true;
        }
        return false;
    }

    private static EnderDragonEntity dragon(MinecraftClient m) {
        for (Entity e : m.world.getEntities())
            if (e instanceof EnderDragonEntity) return (EnderDragonEntity) e;
        return null;
    }

    // The champion's grounded arrow and boat share the pixel targeted on the top of
    // the fifth scaffolding block. The precise boat origin also fixes the rider's X/Z.
    private static final Vec3d SEAT = new Vec3d(5.2713981, 85.0, .35800287);
    private static final BlockPos DECK = new BlockPos(5, 84, 0);

    private static BoatEntity boat(MinecraftClient mc) {
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof BoatEntity && e.squaredDistanceTo(SEAT) < 2.0) return (BoatEntity) e;
        }
        return null;
    }

    private static boolean position(MinecraftClient mc) {
        Vec3d p = mc.player.getPos();
        // Aim from the south-facing boat's rear. A west-facing boat legitimately
        // dismounts onto the narrow connecting deck, missing the launch by >1 block.
        double dx = SEAT.x + .055 - p.x, dz = SEAT.z - .25 - p.z, distance = Math.hypot(dx, dz);
        if (distance < .041 && Math.abs(p.y - SEAT.y) < .16 && mc.player.isOnGround()) return true;
        // Keep the runner on the one-block-wide platform even when correcting sideways.
        if (distance > .027) {
            Race.aim(mc, new Vec3d(SEAT.x + .055, mc.player.getEyeY() - .2, SEAT.z - .25));
            moveOnDeck(mc, dx, dz);
        }
        return false;
    }

    private static boolean floorPoint(MinecraftClient mc, double tolerance) {
        face(mc, SEAT);
        if (!(mc.crosshairTarget instanceof BlockHitResult)) return false;
        BlockHitResult h = (BlockHitResult) mc.crosshairTarget;
        return h.getType() == HitResult.Type.BLOCK
                && h.getSide() == Direction.UP
                && DECK.equals(h.getBlockPos())
                && Math.hypot(h.getPos().x - SEAT.x, h.getPos().z - SEAT.z) < tolerance;
    }

    private static boolean serverLookingAtDeck(MinecraftClient mc) {
        ServerPlayerEntity server = endPlayer(mc);
        if (server == null) return false;
        Vec3d eye = server.getCameraPosVec(1), direction = server.getRotationVec(1);
        if (direction.y >= -.01 || eye.y <= SEAT.y) return false;
        double travel = (SEAT.y - eye.y) / direction.y;
        if (travel > 5) return false;
        Vec3d hit = eye.add(direction.multiply(travel));
        return Math.hypot(hit.x - SEAT.x, hit.z - SEAT.z) < .034;
    }

    private static boolean draw(MinecraftClient mc) {
        if (!select(mc, 1)) return false;
        if (!mc.player.isUsingItem() && mc.player.inventory.count(Items.ARROW) > 0)
            mc.interactionManager.interactItem(mc.player, mc.world, Hand.MAIN_HAND);
        mc.options.keyUse.setPressed(mc.player.isUsingItem());
        return mc.player.isUsingItem() && mc.player.getItemUseTime() >= 20;
    }

    private static void release(MinecraftClient mc) {
        mc.options.keyUse.setPressed(false);
        mc.interactionManager.stopUsingItem(mc.player);
    }

    private static boolean seat(MinecraftClient mc) {
        if (mc.player.getVehicle() instanceof BoatEntity) return true;
        BoatEntity existing = boat(mc);
        if (existing != null) {
            Vec3d target = existing.getBoundingBox().getCenter();
            face(mc, target);
            if (age - boardRequest >= 8
                    && mc.crosshairTarget instanceof EntityHitResult
                    && ((EntityHitResult) mc.crosshairTarget).getEntity() == existing) {
                mc.interactionManager.interactEntity(mc.player, existing, Hand.MAIN_HAND);
                boardRequest = age;
            }
            return false;
        }
        if (age - boatRequest < 20) return false; // wait for the boat entity spawn packet
        if (!select(mc, 2)) return false;
        if (mc.player.isOnGround()) mc.options.keyJump.setPressed(true);
        // Wait through at least one movement update with the correct aim before the
        // server raycasts the BoatItem. Never manufacture a placement hit or velocity.
        if (floorPoint(mc, .004) && mc.player.getY() >= SEAT.y + 1.15 && serverLookingAtDeck(mc)) {
            if (++aimTicks >= 2) {
                mc.interactionManager.interactItem(mc.player, mc.world, Hand.MAIN_HAND);
                boatRequest = age;
                aimTicks = 0;
            }
        } else aimTicks = 0;
        return false;
    }

    private static boolean passage(MinecraftClient mc, EnderDragonEntity dragon) {
        double dx = dragon.getX() - SEAT.x, dz = dragon.getZ() - SEAT.z;
        double dist = Math.hypot(dx, dz);
        boolean approaching =
                Double.isFinite(lastDragonDistance) && dist < lastDragonDistance - .08;
        double height = dragon.getY() - SEAT.y;
        boolean descendingOverhead = dist < 4.5 && height >= 0 && height < 3.5
                && dragon.getY() < lastDragonY - .08;
        lastDragonDistance = dist;
        lastDragonY = dragon.getY();
        // The reference pass is nearly level. An already charged rider can also dismount
        // when a dragon drops directly over the boat and misses the horizontal approach window.
        return descendingOverhead || (approaching && dist <= 8.1 && dist >= 4.5 && Math.abs(height) < 3.5);
    }

    private static void sightAlongPass(MinecraftClient mc, EnderDragonEntity dragon) {
        double dist = Math.hypot(dragon.getX() - SEAT.x, dragon.getZ() - SEAT.z);
        float yaw = 28.0f, pitch = 7.5f;
        if (phase == 4) {
            yaw = 21;
            pitch = -18;
        } else if (dist < 8) {
            pitch = -1.5f;
        }
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        face(
                mc,
                mc.player
                        .getCameraPosVec(1)
                        .add(
                                -Math.sin(y) * Math.cos(p) * 10,
                                -Math.sin(p) * 10,
                                Math.cos(y) * Math.cos(p) * 10));
    }

    /**
     * Read-only velocity observation inside this opt-in integrated-server practice save. The
     * display-side knockback message saturates at +/-3.9 per axis. All actions still go through the
     * normal client bow and all damage is resolved by Minecraft.
     */
    private static double serverSpeed(MinecraftClient mc) {
        if (mc.getServer() == null) return 0;
        net.minecraft.server.network.ServerPlayerEntity player =
                mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if (player == null) return 0;
        Vec3d v = player.getVelocity();
        return Math.hypot(v.x, v.z);
    }

    private static boolean recover(MinecraftClient mc) {
        Race.stop(mc);
        mc.options.keyUse.setPressed(false);
        if (!landed) {
            if (mc.player.isTouchingWater() || (waterPlaced && mc.player.isOnGround())) {
                landed = true;
                if (killed) Race.advanceTo(6576);
            } else {
                status = "preparing water for the landing";
                if (!waterPlaced && !select(mc, 3)) return true;
                // Point vertically down as soon as the arrow has left, long
                // before the floor comes into range at the end of the fall.
                Vec3d eye = mc.player.getCameraPosVec(1);
                double yaw = Math.toRadians(mc.player.yaw);
                face(mc, eye.add(-Math.sin(yaw) * .001, -8, Math.cos(yaw) * .001));
                if (!waterPlaced
                        && mc.player.getVelocity().y < -.25
                        && mc.crosshairTarget instanceof BlockHitResult
                        && mc.crosshairTarget.getType() == HitResult.Type.BLOCK
                        && ((BlockHitResult) mc.crosshairTarget).getSide() == Direction.UP) {
                    status = "placing the landing water";
                    mc.interactionManager.interactItem(mc.player, mc.world, Hand.MAIN_HAND);
                    if (mc.player.getMainHandStack().getItem() == Items.BUCKET) waterPlaced = true;
                }
                if (waterPlaced) status = "waiting for water contact";
                return true;
            }
        }
        if (!killed) {
            status = "landed safely after the missed shot";
            return true;
        }
        // Stand inside the near side of the fountain ring. The live game
        // creates the exit blocks after the dragon's death animation.
        double x = .5, z = -1.5;
        if (mc.world.getBlockState(new BlockPos(0, 62, -2)).getBlock() == Blocks.END_PORTAL)
            status = "stepping into the active exit portal";
        else status = "returning to the fountain";
        double dx = x - mc.player.getX(), dz = z - mc.player.getZ(), dist = Math.hypot(dx, dz);
        if (dist < .36) return true;
        face(mc, new Vec3d(x, mc.player.getEyeY() - .45 * dist, z));
        double a = Math.toRadians(mc.player.yaw);
        double forward = (-Math.sin(a) * dx + Math.cos(a) * dz) / dist;
        mc.options.keyForward.setPressed(forward > .7);
        mc.options.keySprint.setPressed(forward > .9 && dist > 4);
        mc.options.keyJump.setPressed(mc.player.isTouchingWater() || mc.player.horizontalCollision);
        return true;
    }

    public static boolean tick(MinecraftClient m, int t) {
        age++;
        EnderDragonEntity d = dragon(m);
        if (d != null
                && (d.getHealth() <= 0
                        || d.getPhaseManager().getCurrent().getType()
                                == net.minecraft.entity.boss.dragon.phase.PhaseType.DYING)) {
            killed = true;
            Race.advanceTo(6521);
        }
        if (killed) return recover(m);
        if (!chooseCrossing(m)) return true;
        if (eating
                || (next == 0
                        && phase == 0
                        && (pearlAttempted
                                || keepPearl
                                || m.player.inventory.count(Items.ENDER_PEARL) == 0))) {
            if (recoverHunger(m)) return true;
        }
        if (pearlFromArrival(m)) return true;
        if (next == 0
                && !pearlLanded
                && m.player.getX() > 72
                && (keepPearl || m.player.inventory.count(Items.ENDER_PEARL) == 0)) {
            if (EndCrossing.tick(m)) {
                while (next < blocks.size() && blocks.get(next)[0] < 5958) next++;
                Race.advanceTo(5958);
            }
            status = EndCrossing.status;
            return true;
        }
        if (next < blocks.size() && build(m, t)) return true;
        if (t < 6435 || next < blocks.size() || d == null) return false;
        Race.stop(m);
        switch (phase) {
            case 0:
                status = "aligning on the launch platform";
                if (position(m)) {
                    // The ground arrow is recoverable practice. The actual kill
                    // only requires the charged final arrow and the grounded boat.
                    phase = !pearlLanded || d.getY() < 94 ? 2 : 1;
                }
                break;
            case 1:
                status = "charging the recoverable grounded arrow";
                boolean onTarget = floorPoint(m, .06);
                if (draw(m) && m.player.getItemUseTime() >= 25 && onTarget) {
                    release(m);
                    m.options.keyJump.setPressed(true);
                    phase = 2;
                }
                break;
            case 2:
                status = "placing and boarding boat, recovering arrow";
                if (seat(m)) {
                    phase = 3;
                    lastDragonDistance = Double.POSITIVE_INFINITY;
                    lastDragonY = Double.NaN;
                }
                break;
            case 3:
                status = "holding full draw aboard boat";
                sightAlongPass(m, d);
                boolean inbound = passage(m, d);
                if (draw(m) && inbound) {
                    phase = 4;
                    held = 0;
                    m.options.keySneak.setPressed(true);
                }
                break;
            case 4:
                status = "dismounting and jumping into wing impulse";
                sightAlongPass(m, d);
                // Keep the right mouse button held across dismount; Minecraft
                // cancels an item-use animation if the use binding is released.
                if (!draw(m)) break;
                if (m.player.hasVehicle()) {
                    m.options.keySneak.setPressed(true);
                    break;
                }
                held++;
                if (held <= 6) m.options.keyJump.setPressed(true);
                Vec3d v = m.player.getVelocity();
                double horizontal = Math.hypot(v.x, v.z);
                launchServerSpeed = serverSpeed(m);
                status =
                        String.format(
                                Locale.ROOT,
                                "wing pass %d: %.2f horizontal, %.2f vertical, %.1f server"
                                        + " horizontal",
                                held,
                                horizontal,
                                v.y,
                                launchServerSpeed);
                // A normal jump, a timer and the first low-speed wing touch all
                // fail this check. Network snapshots saturate each component at
                // 3.9, even when the server's true momentum is much larger.
                if (held >= 3 && launchServerSpeed >= 110) {
                    release(m);
                    phase = 5;
                    held = 0;
                } else if (held > 17) {
                    // An off-axis pass leaves the arrow on the string. The
                    // player can reboard and attempt another incoming pass.
                    phase = 2;
                    aimTicks = 0;
                }
                break;
            default:
                if (++held > 9 && (m.player.getVelocity().y < -.18 || waterPlaced || landed))
                    return recover(m);
                status = "waiting for actual dragon damage";
                m.options.keyUse.setPressed(false);
                break;
        }
        return true;
    }
}
