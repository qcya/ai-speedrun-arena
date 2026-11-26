package dev.aiarena.week113;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Joins the lower corridor when the Nether return opens in the eastern stronghold cave. */
public final class StrongholdEntry {
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
    private static int door = -1;
    private static boolean active, finished;
    private static int phase, point;
    public static String status = "leaving the eastern stronghold portal";

    private StrongholdEntry() {}

    public static void reset() {
        active = finished = false;
        phase = point = 0;
        door = -1;
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

    private static boolean doorPass(MinecraftClient mc, int reference) {
        if (mc.currentScreen != null) return false;
        if (door < 0)
            for (int i = 0; i < DOORS.length; i++) {
                int[] d = DOORS[i];
                if (reference <= d[8] || reference > d[8] + 52) continue;
                double dx = mc.player.getX() - d[0] - .5, dz = mc.player.getZ() - d[2] - .5;
                if (Math.abs(mc.player.getY() - d[1]) > 1.8
                        || Math.hypot(dx, dz) > 2.3
                        || dx * d[6] + dz * d[7] > .05) continue;
                BlockState state = mc.world.getBlockState(new BlockPos(d[0], d[1], d[2]));
                if (state.getBlock() instanceof DoorBlock && !state.get(DoorBlock.OPEN)) {
                    door = i;
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
        if (state.get(DoorBlock.OPEN)) {
            status = "walking through the open stronghold door";
            walk(mc, x + d[6] * 1.2, z + d[7] * 1.2, false);
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
                        .isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    public static boolean tick(MinecraftClient mc, int reference) {
        if (doorPass(mc, reference)) return true;
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
