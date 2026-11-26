package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;

/** Physical, supported route across both gaps when the stronghold leaves no Ender pearl. */
final class EndCrossing {
    private static int stage, index;
    private static boolean tookOff;
    static String status = "crossing the End arrival platform";

    private EndCrossing() {}

    static void reset() {
        stage = index = 0;
        tookOff = false;
    }

    private static void advance() {
        stage++;
        index = 0;
    }

    private static void move(MinecraftClient m, double dx, double dz, boolean sneak) {
        double d = Math.hypot(dx, dz);
        if (d < .01) return;
        double a = Math.toRadians(m.player.yaw);
        double f = (-Math.sin(a) * dx + Math.cos(a) * dz) / d,
                l = (Math.cos(a) * dx + Math.sin(a) * dz) / d;
        m.options.keyForward.setPressed(f > .3827);
        m.options.keyBack.setPressed(f < -.3827);
        m.options.keyLeft.setPressed(l > .3827);
        m.options.keyRight.setPressed(l < -.3827);
        m.options.keySneak.setPressed(sneak);
    }

    private static boolean go(
            MinecraftClient m, double x, double z, double tolerance, boolean sprint) {
        double dx = x - m.player.getX(), dz = z - m.player.getZ(), d = Math.hypot(dx, dz);
        if (d < tolerance) return true;
        Race.aim(m, new Vec3d(x, m.player.getEyeY() - .52 * d, z));
        move(m, dx, dz, d < 1.08);
        m.options.keySprint.setPressed(sprint && d > 2);
        m.options.keyJump.setPressed((sprint && d > 2.2) || m.player.horizontalCollision);
        return false;
    }

    private static void click(MinecraftClient m, BlockPos support, Direction side, Vec3d target) {
        Race.aim(m, target);
        m.gameRenderer.updateTargetedEntity(1);
        if (!(m.crosshairTarget instanceof BlockHitResult)) return;
        BlockHitResult h = (BlockHitResult) m.crosshairTarget;
        if (h.getType() != HitResult.Type.BLOCK
                || !h.getBlockPos().equals(support)
                || h.getSide() != side) return;
        if (m.interactionManager.interactBlock(m.player, m.world, Hand.MAIN_HAND, h).isAccepted())
            m.player.swingHand(Hand.MAIN_HAND);
    }

    private static boolean solid(MinecraftClient m, BlockPos p) {
        return !m.world.getBlockState(p).getCollisionShape(m.world, p).isEmpty();
    }

    private static void pillar(MinecraftClient m) {
        BlockPos target = new BlockPos(98, 49 + index, 2);
        if (index >= 3) {
            if (m.player.isOnGround() && m.player.getY() >= 51.9) advance();
            return;
        }
        if (solid(m, target)) {
            index++;
            return;
        }
        if (!FinalFight.selectPillarBlock(m, target)) return;
        BlockPos under = target.down();
        m.options.keyJump.setPressed(m.player.isOnGround());
        if (!m.player.getBoundingBox().intersects(new Box(target)))
            click(m, under, Direction.UP, new Vec3d(98.5, target.getY() - .001, 2.5));
        else Race.aim(m, new Vec3d(98.5, target.getY() - .001, 2.5));
    }

    private static void west(MinecraftClient m, int first, int last, int y, int z) {
        int x = first - index;
        if (x < last) {
            advance();
            return;
        }
        BlockPos wanted = new BlockPos(x, y, z), anchor = wanted.east();
        if (solid(m, wanted)) {
            index++;
            return;
        }
        if (!FinalFight.selectBuildingBlock(m) || !solid(m, anchor)) return;
        double px = m.player.getX(), pz = m.player.getZ();
        if (px > anchor.getX() + .86 || Math.abs(pz - z - .5) > .30) {
            go(m, anchor.getX() + .45, z + .5, .14, false);
            return;
        }
        click(
                m,
                anchor,
                Direction.WEST,
                new Vec3d(Math.max(px + .025, anchor.getX() + .001), y + .72, pz));
        move(m, -1, MathHelper.clamp(z + .5 - pz, -.15, .15), px < anchor.getX() - .035);
    }

    private static boolean jumpToShelf(MinecraftClient m) {
        double x = m.player.getX(), z = m.player.getZ();
        if (x < 97.0 && z > 3.65 && m.player.isOnGround() && m.player.getY() >= 52.9) return true;
        // The near corner of native end stone is X=97,Z=4. Follow the
        // champion's diagonal from the 3-block pillar and jump at its lip.
        double dx = 96.05 - x, dz = 4.48 - z;
        if (x < 96.45 && z >= 3.92) {
            dx = 84.6 - x;
            dz = 4.48 - z;
        }
        Race.aim(m, new Vec3d(x + dx, m.player.getEyeY() - .48 * Math.hypot(dx, dz), z + dz));
        move(m, dx, dz, false);
        m.options.keySprint.setPressed(true);
        if (!tookOff && m.player.isOnGround() && x < 98.12 && z > 2.73)
            m.options.keyJump.setPressed(true);
        if (!m.player.isOnGround() && m.player.getY() > 52.1) tookOff = true;
        return false;
    }

    static boolean tick(MinecraftClient m) {
        Race.stop(m);
        switch (stage) {
            case 0:
                status = "lining up on the obsidian arrival platform";
                if (go(m, 98.5, 2.5, .13, false)
                        && m.player.isOnGround()
                        && Math.hypot(m.player.getVelocity().x, m.player.getVelocity().z) < .042)
                    advance();
                break;
            case 1:
                status = "raising three blocks at the edge of the arrival platform";
                pillar(m);
                break;
            case 2:
                status = "sprint-jumping from the arrival pillar onto natural end stone";
                if (jumpToShelf(m)) advance();
                break;
            case 3:
                status = "sprinting across the first End shelf";
                if (go(m, 85.8, 4.5, .85, true)) advance();
                break;
            case 4:
                status = "lining up the main island crossing";
                if (go(m, 84.5, 4.5, .18, false)
                        && m.player.isOnGround()
                        && Math.abs(m.player.getY() - 53) < .15) advance();
                break;
            case 5:
                status = "bridging west onto the main End island";
                west(m, 83, 73, 52, 4);
                break;
            default:
                return true;
        }
        return false;
    }
}
