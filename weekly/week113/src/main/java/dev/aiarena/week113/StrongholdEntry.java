package dev.aiarena.week113;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

/** Joins the lower corridor when the Nether return opens in the eastern stronghold cave. */
public final class StrongholdEntry {
    private static final BlockPos POOL = new BlockPos(-764, 39, 1275);
    private static BlockPos waterSpot;
    private static boolean poured;
    private static int waterUseAge;
    private static final BlockPos SHAFT = new BlockPos(-679, 35, 1302);
    // Surveyed passable turns in the real corridor. It rises from Y31 to Y33 at the far end.
    private static final double[][] TURNS = {
        {-680.5, 1302.5},
        {-680.5, 1303.5},
        {-688.5, 1303.5},
        {-688.5, 1304.5},
        {-693.5, 1304.5},
        {-693.5, 1308.5},
        {-710.6, 1308.5}
    };
    // Door lower block, real stone button, direction of travel, and the first accepted replay tick.
    private static final int[][] DOORS = {
        {-716, 33, 1291, -715, 34, 1292, 0, -1, 5520},
        {-718, 33, 1288, -717, 34, 1289, -1, 0, 5542},
        {-743, 39, 1275, -742, 40, 1276, -1, 0, 5673}
    };
    private static int door = -1, misses;
    private static boolean returnToButton;
    private static boolean active, finished;
    private static int phase, point;
    public static String status = "leaving the eastern stronghold portal";

    private StrongholdEntry() {}

    public static void reset() {
        active = finished = false;
        phase = point = 0;
        door = -1;
        misses = 0;
        returnToButton = false;
        waterSpot = null;
        poured = false;
        waterUseAge = -100;
    }

    private static boolean walk(MinecraftClient mc, double x, double z, boolean precision) {
        double dx = x - mc.player.getX(), dz = z - mc.player.getZ(), length = Math.hypot(dx, dz);
        if (length < (precision ? .13 : .4)) return true;
        Race.aim(mc, new Vec3d(x, mc.player.getEyeY() - .15 * length, z));
        double yaw = Math.toRadians(mc.player.yaw),
                forward = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / length;
        double left = (Math.cos(yaw) * dx + Math.sin(yaw) * dz) / length;
        mc.options.keyForward.setPressed(forward > .3827);
        mc.options.keyBack.setPressed(forward < -.3827);
        mc.options.keyLeft.setPressed(left > .3827);
        mc.options.keyRight.setPressed(left < -.3827);
        mc.options.keySprint.setPressed(!precision && length > 2 && forward > .85);
        mc.options.keySneak.setPressed(precision && length < 1.2);
        mc.options.keyJump.setPressed(mc.player.horizontalCollision && !precision);
        return false;
    }

    private static boolean pick(MinecraftClient mc) {
        for (int i = 0; i < 36; i++) {
            if (mc.player.inventory.getStack(i).getItem() != Items.IRON_PICKAXE) continue;
            if (i < 9) {
                if (mc.currentScreen instanceof InventoryScreen) mc.player.closeHandledScreen();
                mc.player.inventory.selectedSlot = i;
                return mc.currentScreen == null;
            }
            if (mc.currentScreen == null) mc.openScreen(new InventoryScreen(mc.player));
            else if (mc.currentScreen instanceof InventoryScreen)
                mc.interactionManager.clickSlot(
                        mc.player.playerScreenHandler.syncId, i, 2, SlotActionType.SWAP, mc.player);
            return false;
        }
        return false;
    }

    private static boolean mineDown(MinecraftClient mc, BlockPos cell) {
        if (mc.world.getBlockState(cell).getMaterial().isReplaceable()) return true;
        if (!pick(mc)) return false;
        Race.aim(mc, new Vec3d(cell.getX() + .5, cell.getY() + .96, cell.getZ() + .5));
        mc.gameRenderer.updateTargetedEntity(1);
        mc.options.keyAttack.setPressed(
                mc.crosshairTarget instanceof BlockHitResult
                        && mc.crosshairTarget.getType() == HitResult.Type.BLOCK
                        && cell.equals(((BlockHitResult) mc.crosshairTarget).getBlockPos()));
        return false;
    }

    static boolean creeperNearCorridor(MinecraftClient mc, int reference) {
        if (reference < 5542 || reference > 5688) return false;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof CreeperEntity)
                    || !entity.isAlive()
                    || mc.player.squaredDistanceTo(entity) > 9 * 9) continue;
            CreeperEntity creeper = (CreeperEntity) entity;
            if (mc.player.canSee(creeper) || creeper.getClientFuseTime(1) > .05f) return true;
        }
        return false;
    }

    private static BlockHitResult bucketRay(Entity player, RayTraceContext.FluidHandling fluids) {
        Vec3d eye = player.getCameraPosVec(1);
        return player.getEntityWorld()
                .rayTrace(
                        new RayTraceContext(
                                eye,
                                eye.add(player.getRotationVec(1).multiply(5)),
                                RayTraceContext.ShapeType.OUTLINE,
                                fluids,
                                player));
    }

    /**
     * Extinguish the runner directly, swim onto the adjacent obsidian the water creates and refill
     * the same bucket from a stable position.
     */
    private static boolean coolPortalPool(MinecraftClient mc, int reference) {
        if (reference < 5710
                || reference > 5753
                || mc.player.squaredDistanceTo(Vec3d.ofCenter(POOL)) > 23) return false;
        if (waterSpot == null) {
            if ((!mc.player.isInLava() && mc.player.getY() > 40.1)
                    || Math.hypot(
                                    mc.player.getX() - POOL.getX() - .5,
                                    mc.player.getZ() - POOL.getZ() - .5)
                            > .8
                    || !mc.world.getFluidState(POOL).isIn(FluidTags.LAVA)
                    || mc.player.inventory.count(Items.WATER_BUCKET) == 0) return false;
            waterSpot = POOL;
        }
        Race.stop(mc);
        mc.options.keyJump.setPressed(mc.player.isInLava() || mc.player.isTouchingWater());
        ServerPlayerEntity server =
                mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if (server == null) return true;
        if (!poured && server.getEntityWorld().getFluidState(waterSpot).isIn(FluidTags.WATER))
            poured = true;
        if (!poured) {
            status = "extinguishing the runner in the portal-room lava";
            if (!Hotbar.select(mc, Items.WATER_BUCKET)) return true;
            if (Middle.aim(mc, waterSpot.down(), Direction.UP) != null
                    && bucketRay(server, RayTraceContext.FluidHandling.NONE)
                            .getBlockPos()
                            .equals(waterSpot.down())
                    && mc.player.age - waterUseAge >= 6) {
                mc.interactionManager.interactItem(mc.player, mc.world, Hand.MAIN_HAND);
                waterUseAge = mc.player.age;
            }
            return true;
        }
        if (!server.getEntityWorld().getFluidState(waterSpot).isIn(FluidTags.WATER)) {
            waterSpot = null;
            poured = false;
            return false;
        }
        if (server.isOnFire() || server.isInLava()) {
            status = "waiting in the extinguishing water";
            return true;
        }
        // Get completely off the center block so the subsequent dirt placement
        // can never collide with the runner, even if no fire resistance remains.
        BlockPos ledge = POOL.east();
        if (server.getEntityWorld()
                .getBlockState(ledge)
                .getCollisionShape(server.getEntityWorld(), ledge)
                .isEmpty()) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos other = POOL.offset(d);
                if (!server.getEntityWorld()
                        .getBlockState(other)
                        .getCollisionShape(server.getEntityWorld(), other)
                        .isEmpty()) {
                    ledge = other;
                    break;
                }
            }
        }
        if (Math.hypot(mc.player.getX() - ledge.getX() - .5, mc.player.getZ() - ledge.getZ() - .5)
                        > .43
                || mc.player.getY() < 39.99) {
            status = "swimming onto obsidian beside the portal";
            walk(mc, ledge.getX() + .5, ledge.getZ() + .5, false);
            mc.options.keyJump.setPressed(true);
            return true;
        }
        status = "refilling the water bucket for the End landing";
        if (!Hotbar.select(mc, Items.BUCKET)) return true;
        Race.aim(mc, Vec3d.ofCenter(waterSpot).add(0, .34, 0));
        if (bucketRay(mc.player, RayTraceContext.FluidHandling.SOURCE_ONLY)
                        .getBlockPos()
                        .equals(waterSpot)
                && bucketRay(server, RayTraceContext.FluidHandling.SOURCE_ONLY)
                        .getBlockPos()
                        .equals(waterSpot)
                && mc.player.age - waterUseAge >= 6) {
            mc.interactionManager.interactItem(mc.player, mc.world, Hand.MAIN_HAND);
            waterUseAge = mc.player.age;
        }
        return true;
    }

    private static boolean pastPanel(MinecraftClient mc, int[] d, BlockState state) {
        BlockPos pos = new BlockPos(d[0], d[1], d[2]);
        // Each closed door occupies only 3/16 of its block. Its real collision
        // plane can be at either edge depending on the facing in this stronghold.
        Box panel =
                state.with(DoorBlock.OPEN, false)
                        .getCollisionShape(mc.world, pos)
                        .getBoundingBox()
                        .offset(pos);
        return (mc.player.getX() - panel.getCenter().x) * d[6]
                        + (mc.player.getZ() - panel.getCenter().z) * d[7]
                > .015;
    }

    private static boolean doorPass(MinecraftClient mc, int reference) {
        if (mc.currentScreen != null) return false;
        if (door < 0)
            for (int i = 0; i < DOORS.length; i++) {
                int[] d = DOORS[i];
                if (reference <= d[8] || reference > d[8] + 52) continue;
                double dx = mc.player.getX() - d[0] - .5, dz = mc.player.getZ() - d[2] - .5;
                if (Math.abs(mc.player.getY() - d[1]) > 1.8 || Math.hypot(dx, dz) > 2.3) continue;
                BlockState state = mc.world.getBlockState(new BlockPos(d[0], d[1], d[2]));
                if (state.getBlock() instanceof DoorBlock
                        && !state.get(DoorBlock.OPEN)
                        && !pastPanel(mc, d, state)) {
                    door = i;
                    misses = 0;
                    returnToButton = false;
                    break;
                }
            }
        if (door < 0) return false;
        int[] d = DOORS[door];
        double x = d[0] + .5, z = d[2] + .5;
        if ((mc.player.getX() - x) * d[6] + (mc.player.getZ() - z) * d[7] > .65) {
            door = -1;
            return false;
        }
        BlockPos lower = new BlockPos(d[0], d[1], d[2]), button = new BlockPos(d[3], d[4], d[5]);
        BlockState state = mc.world.getBlockState(lower);
        if (!(state.getBlock() instanceof DoorBlock)) {
            door = -1;
            return false;
        }
        Race.stop(mc);
        mc.options.keyAttack.setPressed(false);
        if (state.get(DoorBlock.OPEN) || pastPanel(mc, d, state)) {
            returnToButton = false;
            misses = 0;
            status = "walking through the stronghold doorway";
            walk(mc, x + d[6] * 1.2, z + d[7] * 1.2, false);
            return true;
        }
        if (returnToButton) {
            status = "stepping back to the visible side of the stone button";
            if (walk(mc, x - d[6] * 2.05, z - d[7] * 2.05, false)) {
                returnToButton = false;
                misses = 0;
            }
            return true;
        }
        status = "reopening the timed stronghold door";
        net.minecraft.util.shape.VoxelShape shape =
                mc.world.getBlockState(button).getOutlineShape(mc.world, button);
        if (shape.isEmpty()) {
            door = -1;
            return false;
        }
        Race.aim(mc, shape.getBoundingBox().offset(button).getCenter());
        mc.gameRenderer.updateTargetedEntity(1.0f);
        if (mc.crosshairTarget instanceof BlockHitResult
                && mc.crosshairTarget.getType() == HitResult.Type.BLOCK
                && button.equals(((BlockHitResult) mc.crosshairTarget).getBlockPos())
                && mc.interactionManager
                        .interactBlock(
                                mc.player,
                                mc.world,
                                Hand.MAIN_HAND,
                                (BlockHitResult) mc.crosshairTarget)
                        .isAccepted()) {
            mc.player.swingHand(Hand.MAIN_HAND);
            misses = 0;
        } else if (++misses >= 6) {
            returnToButton = true;
        }
        return true;
    }

    public static boolean tick(MinecraftClient mc, int reference) {
        if (coolPortalPool(mc, reference) || doorPass(mc, reference)) return true;
        if (finished) return false;
        if (!active) {
            // The first client frame following a dimension change can still
            // hold the login spawn position. Wait for the observed portal location.
            if (reference < 5412
                    || reference >= 5479
                    || mc.player.getY() < 33
                    || mc.player.getY() > 41
                    || Math.hypot(mc.player.getX() + 678.5, mc.player.getZ() - 1303.5) > 7.5)
                return false;
            active = true;
        }
        Race.stop(mc);
        mc.options.keyAttack.setPressed(false);
        switch (phase) {
            case 0:
                status = "aligning over the eastern stronghold corridor";
                if (walk(mc, -678.5, 1302.5, true)) phase++;
                break;
            case 1:
            case 2:
                status = "mining the two blocks above the stronghold passage";
                if (mineDown(mc, phase == 1 ? SHAFT : SHAFT.down())) phase++;
                break;
            case 3:
                status = "dropping into the stronghold corridor";
                if (mc.player.isOnGround() && mc.player.getY() < 32) phase++;
                break;
            default:
                status = "running through the eastern stronghold corridor";
                double[] target = TURNS[point];
                if (walk(mc, target[0], target[1], false) && ++point == TURNS.length) {
                    finished = true;
                    Race.advanceTo(5452);
                }
        }
        return true;
    }
}
