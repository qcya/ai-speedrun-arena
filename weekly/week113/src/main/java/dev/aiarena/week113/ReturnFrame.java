package dev.aiarena.week113;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RayTraceContext;

/** Builds the return portal at the original coordinates, with a survival-built ledge beside it. */
public final class ReturnFrame {
    private static final int FRAME_Z = 164, FLOOR_Y = 73;
    private static final int[][] UPRIGHTS = {
        {-88, 74}, {-88, 75}, {-88, 76}, {-88, 77}, {-85, 74}, {-85, 75}, {-85, 76}, {-85, 77},
        {-87, 77}, {-86, 77}
    };
    private static int stage, index;
    private static boolean active;
    public static String status = "approaching the return ledge";

    private ReturnFrame() {}

    public static void reset() {
        stage = index = 0;
        active = false;
    }

    private static boolean select(MinecraftClient m, Item primary, Item other) {
        for (int slot = 0; slot < 36; slot++) {
            Item i = m.player.inventory.getStack(slot).getItem();
            if (i != primary && (other == null || i != other)) continue;
            if (slot < 9) {
                if (m.currentScreen instanceof InventoryScreen) m.player.closeHandledScreen();
                m.player.inventory.selectedSlot = slot;
                return m.currentScreen == null;
            }
            if (m.currentScreen == null) {
                m.openScreen(new InventoryScreen(m.player));
                return false;
            }
            if (m.currentScreen instanceof InventoryScreen)
                m.interactionManager.clickSlot(
                        m.player.playerScreenHandler.syncId,
                        slot,
                        exchangeSlot(m),
                        SlotActionType.SWAP,
                        m.player);
            return false;
        }
        return false;
    }

    private static int exchangeSlot(MinecraftClient m) {
        // Keep the lighter in its original hotbar slot so there is no need to
        // exchange the same two stacks again when the frame is complete.
        for (int i = 0; i < 9; i++) {
            Item item = m.player.inventory.getStack(i).getItem();
            if (item == Items.DIRT
                    || item == Items.NETHERRACK
                    || m.player.inventory.getStack(i).isEmpty()) return i;
        }
        return m.player.inventory.selectedSlot;
    }

    private static void move(MinecraftClient m, double dx, double dz, boolean sneak) {
        double range = Math.hypot(dx, dz);
        if (range < .015) return;
        double yaw = Math.toRadians(m.player.yaw);
        double f = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / range,
                l = (Math.cos(yaw) * dx + Math.sin(yaw) * dz) / range;
        m.options.keyForward.setPressed(f > .3827);
        m.options.keyBack.setPressed(f < -.3827);
        m.options.keyLeft.setPressed(l > .3827);
        m.options.keyRight.setPressed(l < -.3827);
        m.options.keySneak.setPressed(sneak);
    }

    private static boolean go(
            MinecraftClient m, double x, double z, double tolerance, boolean sneak) {
        double dx = x - m.player.getX(), dz = z - m.player.getZ();
        if (Math.hypot(dx, dz) < tolerance) return true;
        Race.aim(m, new Vec3d(x, m.player.getEyeY() - .38 * Math.hypot(dx, dz), z));
        move(m, dx, dz, sneak || Math.hypot(dx, dz) < .88);
        if (!sneak) m.options.keyJump.setPressed(m.player.horizontalCollision);
        return false;
    }

    private static boolean click(MinecraftClient m, BlockPos support, Direction side, Vec3d p) {
        Race.aim(m, p);
        m.gameRenderer.updateTargetedEntity(1);
        if (!(m.crosshairTarget instanceof BlockHitResult)) return false;
        BlockHitResult h = (BlockHitResult) m.crosshairTarget;
        if (h.getType() != HitResult.Type.BLOCK
                || !support.equals(h.getBlockPos())
                || h.getSide() != side) return false;
        if (!m.interactionManager.interactBlock(m.player, m.world, Hand.MAIN_HAND, h).isAccepted())
            return false;
        m.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private static boolean place(MinecraftClient m, BlockPos cell) {
        Vec3d eye = m.player.getCameraPosVec(1);
        for (Direction side : Direction.values()) {
            BlockPos anchor = cell.offset(side.getOpposite());
            if (m.world.getBlockState(anchor).getMaterial().isReplaceable()) continue;
            Vec3d normal = new Vec3d(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
            for (double u : new double[] {0, .35, -.35})
                for (double v : new double[] {0, .35, -.35}) {
                    Vec3d p = Vec3d.ofCenter(anchor).add(normal.multiply(.499));
                    if (side.getAxis() == Direction.Axis.X) p = p.add(0, u, v);
                    else if (side.getAxis() == Direction.Axis.Y) p = p.add(u, 0, v);
                    else p = p.add(u, v, 0);
                    if (eye.squaredDistanceTo(p) > 19.8 || eye.subtract(p).dotProduct(normal) <= 0)
                        continue;
                    BlockHitResult seen =
                            m.world.rayTrace(
                                    new RayTraceContext(
                                            eye,
                                            p.subtract(normal.multiply(.012)),
                                            RayTraceContext.ShapeType.OUTLINE,
                                            RayTraceContext.FluidHandling.NONE,
                                            m.player));
                    if (seen.getType() == HitResult.Type.BLOCK
                            && anchor.equals(seen.getBlockPos())
                            && seen.getSide() == side) return click(m, anchor, side, p);
                }
        }
        return false;
    }

    private static void finishStage() {
        stage++;
        index = 0;
    }

    private static void westRow(MinecraftClient m, int z, boolean obsidian) {
        int tx = -86 - index;
        if (tx < -88) {
            finishStage();
            return;
        }
        BlockPos next = new BlockPos(tx, FLOOR_Y, z), anchor = next.east();
        if (obsidian
                ? m.world.getBlockState(next).getBlock() == Blocks.OBSIDIAN
                : !m.world.getBlockState(next).getMaterial().isReplaceable()) {
            index++;
            return;
        }
        if (!select(m, obsidian ? Items.OBSIDIAN : Items.NETHERRACK, obsidian ? null : Items.DIRT))
            return;
        double az = z + .5, x = m.player.getX(), playerZ = m.player.getZ();
        if (Math.abs(az - playerZ) > .28 || x > anchor.getX() + .78) {
            go(m, anchor.getX() + .49, az, .13, true);
            return;
        }
        if (m.world.getBlockState(anchor).getCollisionShape(m.world, anchor).isEmpty()) return;
        double aimX = Math.max(anchor.getX() + .001, x + .025);
        click(m, anchor, Direction.WEST, new Vec3d(aimX, FLOOR_Y + .72, playerZ));
        move(m, -1, MathHelper.clamp(az - playerZ, -.15, .15), x < anchor.getX() - .035);
    }

    private static boolean full(MinecraftClient m, BlockPos pos) {
        return !m.world.getBlockState(pos).getMaterial().isReplaceable();
    }

    private static void builderStep(MinecraftClient m) {
        BlockPos foot = new BlockPos(-87, 74, 165);
        if (!go(m, -86.5, 165.5, .16, true)) return;
        if (!full(m, foot)) {
            if (!select(m, Items.NETHERRACK, Items.DIRT)) return;
            Race.aim(m, new Vec3d(-86.5, 73.999, 165.5));
            m.options.keyJump.setPressed(m.player.isOnGround());
            if (!m.player.getBoundingBox().intersects(new Box(foot))) place(m, foot);
        } else if (m.player.getY() >= 74.95) {
            finishStage();
        } else m.options.keyJump.setPressed(true);
    }

    /**
     * Once the stasis runner reaches the ledge this owns movement through the actual portal
     * transfer.
     */
    public static boolean tick(MinecraftClient m, int reference) {
        if (!active) {
            if (reference < 5218
                    || reference >= 5412
                    || m.player.getY() < 68
                    || m.player.getY() > 84
                    || m.player.squaredDistanceTo(-84.5, 75, 168.5) > 15 * 15) return false;
            active = true;
        }
        Race.stop(m);
        m.options.keyAttack.setPressed(false);
        BlockPos interior = new BlockPos(-86, 74, FRAME_Z);
        if (m.world.getBlockState(interior).getBlock() == Blocks.NETHER_PORTAL) stage = 8;
        switch (stage) {
            case 0:
                status = "approaching the return frame on the solid ledge";
                if (m.player.getZ() > 170 && m.player.getX() < -84.8) {
                    go(m, -83.7, 170, .35, false);
                    break;
                }
                if (go(m, -84.55, 165.5, .16, false)) finishStage();
                break;
            case 1:
                status = "building the safety ledge beside the return frame";
                westRow(m, 165, false);
                break;
            case 2:
                status = "crossing the safety ledge to the first frame block";
                if (go(m, -84.5, 165.5, .15, true)) finishStage();
                break;
            case 3:
                status = "lining up the obsidian bottom rail";
                if (go(m, -84.5, 164.5, .12, true)) finishStage();
                break;
            case 4:
                status = "bridging the bottom of the return frame";
                westRow(m, FRAME_Z, true);
                break;
            case 5:
                status = "stepping up beside the obsidian frame";
                builderStep(m);
                break;
            case 6:
                if (index >= UPRIGHTS.length) {
                    finishStage();
                    break;
                }
                int[] c = UPRIGHTS[index];
                BlockPos next = new BlockPos(c[0], c[1], FRAME_Z);
                status = "building return frame " + next.toShortString();
                if (m.world.getBlockState(next).getBlock() == Blocks.OBSIDIAN) {
                    index++;
                    break;
                }
                if (!select(m, Items.OBSIDIAN, null)) break;
                if (!place(m, next) && c[1] >= 77)
                    m.options.keyJump.setPressed(m.player.isOnGround());
                break;
            case 7:
                status = "lighting the return frame";
                if (!select(m, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE)) break;
                click(m, interior.down(), Direction.UP, new Vec3d(-85.5, 73.999, 164.5));
                break;
            default:
                status = "waiting inside the return portal";
                Race.advanceTo(5332);
                go(m, -86.7, 164.515625, .11, false);
                // The protected pad is one block higher than the portal sill.
                m.options.keySneak.setPressed(false);
        }
        return true;
    }
}
