package dev.aiarena.week113;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

/**
 * Intercepts genuine, observable combat and rod drops while the reference route visits its spawner.
 */
public final class Fortress {
    private static final Vec3d SPAWNER = new Vec3d(-7.5, 60.5, 115.5);
    private static int previousEnemy, swordTicks;
    private static boolean ownsInventory, fighting;
    public static String status = "approaching the fortress spawner";

    private Fortress() {}

    public static void reset() {
        previousEnemy = -1;
        swordTicks = 0;
        ownsInventory = fighting = false;
    }

    private static Vec3d visibleAim(MinecraftClient mc, BlazeEntity mob, Vec3d eye) {
        Box b = mob.getBoundingBox();
        // The near side can already be in the real three-block entity reach
        // while the center (on the far side of a spawner cage) is not.
        double x = MathHelper.clamp(eye.x, b.minX + .045, b.maxX - .045);
        double z = MathHelper.clamp(eye.z, b.minZ + .045, b.maxZ - .045);
        double y = MathHelper.clamp(eye.y, b.minY + .13, b.maxY - .13);
        Vec3d[] probes = {
            new Vec3d(x, y, z),
            b.getCenter(),
            new Vec3d(x, b.maxY - .13, z),
            new Vec3d(x, b.minY + .25, z)
        };
        for (Vec3d point : probes)
            if (mc.world
                            .rayTrace(
                                    new RayTraceContext(
                                            eye,
                                            point,
                                            RayTraceContext.ShapeType.OUTLINE,
                                            RayTraceContext.FluidHandling.NONE,
                                            mc.player))
                            .getType()
                    == HitResult.Type.MISS) return point;
        return null;
    }

    private static void takeControls(MinecraftClient mc) {
        Race.stop(mc);
        mc.options.keyAttack.setPressed(false);
        if (!fighting) mc.interactionManager.cancelBlockBreaking();
        fighting = true;
    }

    private static boolean equipSword(MinecraftClient mc) {
        if (mc.player.getMainHandStack().getItem() == Items.DIAMOND_SWORD) {
            if (ownsInventory && mc.currentScreen instanceof InventoryScreen)
                mc.player.closeHandledScreen();
            ownsInventory = false;
            return mc.currentScreen == null && ++swordTicks >= 2;
        }
        swordTicks = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (mc.player.inventory.getStack(slot).getItem() != Items.DIAMOND_SWORD) continue;
            if (slot < 9) {
                mc.player.inventory.selectedSlot = slot;
                return false;
            }
            if (mc.currentScreen == null) {
                mc.openScreen(new InventoryScreen(mc.player));
                ownsInventory = true;
            } else if (ownsInventory && mc.currentScreen instanceof InventoryScreen) {
                mc.interactionManager.clickSlot(
                        mc.player.playerScreenHandler.syncId,
                        slot,
                        mc.player.inventory.selectedSlot,
                        SlotActionType.SWAP,
                        mc.player);
            }
            return false;
        }
        return false;
    }

    private static boolean clear(MinecraftClient mc, BlockPos p) {
        return mc.world.getBlockState(p).getCollisionShape(mc.world, p).isEmpty();
    }

    private static boolean safeStep(MinecraftClient mc, double dx, double dz) {
        BlockPos feet =
                new BlockPos(
                        mc.player.getX() + dx * .65,
                        mc.player.getY() + .06,
                        mc.player.getZ() + dz * .65);
        if (!clear(mc, feet)
                || !clear(mc, feet.up())
                || mc.world.getFluidState(feet).isIn(FluidTags.LAVA)) return false;
        BlockPos floor = feet.down();
        if (clear(mc, floor)) floor = floor.down();
        return !clear(mc, floor) && !mc.world.getFluidState(floor).isIn(FluidTags.LAVA);
    }

    private static void walk(MinecraftClient mc, double dx, double dz) {
        double length = Math.hypot(dx, dz);
        if (length < .08) return;
        dx /= length;
        dz /= length;
        if (!safeStep(mc, dx, dz)) {
            // Try stepping around a fence post or the spawner instead of backing into a wall.
            double leftX = (dx + dz) * .707, leftZ = (dz - dx) * .707;
            double rightX = (dx - dz) * .707, rightZ = (dz + dx) * .707;
            if (safeStep(mc, leftX, leftZ)) {
                dx = leftX;
                dz = leftZ;
            } else if (safeStep(mc, rightX, rightZ)) {
                dx = rightX;
                dz = rightZ;
            } else return;
        }
        double yaw = Math.toRadians(mc.player.yaw);
        double forward = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double left = Math.cos(yaw) * dx + Math.sin(yaw) * dz;
        mc.options.keyForward.setPressed(forward > .3827);
        mc.options.keyBack.setPressed(forward < -.3827);
        mc.options.keyLeft.setPressed(left > .3827);
        mc.options.keyRight.setPressed(left < -.3827);
    }

    /** Clear a real intervening block when the requested excavation face is hidden. */
    public static boolean clearToward(MinecraftClient mc, BlockPos requested) {
        Race.aim(mc, Vec3d.ofCenter(requested));
        mc.gameRenderer.updateTargetedEntity(1);
        if (!(mc.crosshairTarget instanceof BlockHitResult)
                || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return false;
        BlockPos obstruction = ((BlockHitResult) mc.crosshairTarget).getBlockPos();
        if (obstruction.equals(mc.player.getBlockPos().down())) return false;
        Block block = mc.world.getBlockState(obstruction).getBlock();
        if (block != Blocks.NETHERRACK && block != Blocks.NETHER_BRICKS) return false;
        mc.options.keyAttack.setPressed(true);
        return true;
    }

    private static void approachSpawner(MinecraftClient mc) {
        double x = -7.62, z = mc.player.getZ() > 120.8 ? 120.0 : 116.55;
        double dx = x - mc.player.getX(), dz = z - mc.player.getZ();
        Race.aim(mc, new Vec3d(x, mc.player.getEyeY() - .38, z));
        double yaw = Math.toRadians(mc.player.yaw), d = Math.max(.01, Math.hypot(dx, dz));
        double forward = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / d;
        double left = (Math.cos(yaw) * dx + Math.sin(yaw) * dz) / d;
        mc.options.keyForward.setPressed(forward > .38);
        mc.options.keyBack.setPressed(forward < -.38);
        mc.options.keyLeft.setPressed(left > .38);
        mc.options.keyRight.setPressed(left < -.38);
        mc.options.keyJump.setPressed(
                mc.player.horizontalCollision
                        || (mc.player.getY() < 59.8 && mc.player.getZ() < 120.3));
        status = "climbing the fortress stairs to the spawning blazes";
    }

    private static void takeRod(MinecraftClient mc, ItemEntity rod) {
        status = "collecting a dropped blaze rod";
        previousEnemy = -1;
        Vec3d p = rod.getPos();
        Race.aim(mc, p.add(0, .25, 0));
        walk(mc, p.x - mc.player.getX(), p.z - mc.player.getZ());
    }

    /**
     * Returns true only while handling a visible rod, a nearby live blaze, or our sword inventory
     * screen.
     */
    public static boolean tick(MinecraftClient mc, int goal) {
        if (mc.player.inventory.count(Items.BLAZE_ROD) >= goal
                || mc.player.squaredDistanceTo(SPAWNER) > 26 * 26
                || (mc.currentScreen != null && !ownsInventory)) {
            fighting = false;
            return false;
        }
        if (ownsInventory) {
            takeControls(mc);
            equipSword(mc);
            return true;
        }

        Vec3d eye = mc.player.getCameraPosVec(1), aim = null;
        BlazeEntity target = null;
        ItemEntity rod = null;
        boolean blazesAtSpawner = false;
        double best = Double.POSITIVE_INFINITY, rodRange = 6 * 6;
        for (Entity e : mc.world.getEntities()) {
            if (!e.isAlive()) continue;
            double range = e.squaredDistanceTo(mc.player);
            if (e instanceof BlazeEntity && e.squaredDistanceTo(SPAWNER) < 8 * 8)
                blazesAtSpawner = true;
            if (e instanceof ItemEntity
                    && ((ItemEntity) e).getStack().getItem() == Items.BLAZE_ROD) {
                if (range < rodRange && mc.player.canSee(e)) {
                    rod = (ItemEntity) e;
                    rodRange = range;
                }
            } else if (e instanceof BlazeEntity && range < 5.0 * 5.0) {
                BlazeEntity candidate = (BlazeEntity) e;
                Vec3d point = visibleAim(mc, candidate, eye);
                if (point == null) continue;
                double score =
                        eye.distanceTo(point)
                                + candidate.getHealth() * .045
                                - (e.getEntityId() == previousEnemy ? .55 : 0);
                if (score < best) {
                    aim = point;
                    target = candidate;
                    best = score;
                }
            }
        }
        if (blazesAtSpawner
                && rod == null
                && (target == null || mc.player.getY() < 59.6)
                && mc.player.getZ() > 117.5
                && mc.player.getZ() < 132
                && Math.abs(mc.player.getX() + 7.5) < 3.6) {
            takeControls(mc);
            if (equipSword(mc)) approachSpawner(mc);
            return true;
        }
        if (target == null && rod == null) {
            fighting = false;
            previousEnemy = -1;
            swordTicks = 0;
            return false;
        }
        takeControls(mc);
        if (rod != null
                && (target == null
                        || rodRange < .65 * .65
                        || target.squaredDistanceTo(mc.player) > 3.25 * 3.25)) {
            takeRod(mc, rod);
            return true;
        }
        previousEnemy = target.getEntityId();
        status = "fighting the blaze at sword reach";
        if (!equipSword(mc)) return true;
        Race.aim(mc, aim);
        mc.gameRenderer.updateTargetedEntity(1);
        double dx = target.getX() - mc.player.getX(),
                dz = target.getZ() - mc.player.getZ(),
                range = Math.hypot(dx, dz);
        if (range < 2.40) walk(mc, -dx, -dz);
        else if (range > 2.85 || eye.distanceTo(aim) > 2.98) walk(mc, dx, dz);
        if (mc.crosshairTarget instanceof EntityHitResult
                && ((EntityHitResult) mc.crosshairTarget).getEntity() == target
                && mc.player.getAttackCooldownProgress(.5f) >= .995f) {
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        return true;
    }
}
