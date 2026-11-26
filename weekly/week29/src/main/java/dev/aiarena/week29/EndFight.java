package dev.aiarena.week29;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.item.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.world.GameMode;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Ordinary survival inputs for reaching the island, bedding the dragon and entering the fountain.
 */
public final class EndFight {
    private static ClientWorld world;
    private static MinecraftClient mc;
    private static ClientPlayerEntity player;
    private static int tick, lastBlast, fountain, nextUse, ageOfLastBed, blastCount;
    private static String action = "waiting for End";
    private static BlockPos previousMine;
    private static Direction approach;
    private static BlockPos bridgeSupport;
    private static int dirX = 1, dirZ = 0;
    private static double px, pz, standbyAngle = -Math.PI / 2;
    private static float previousYaw, previousPitch;
    private static boolean bedSeen, deathSeen;

    public static String status() {
        return action;
    }

    public static boolean tick(MinecraftClient client) {
        if (client.world == null
                || client.player == null
                || client.interactionManager == null
                || !World.END.equals(client.world.getRegistryKey())
                || client.interactionManager.getCurrentGameMode() != GameMode.SURVIVAL)
            return false;
        mc = client;
        player = mc.player;
        if (world != mc.world) {
            world = mc.world;
            tick = 0;
            lastBlast = -100;
            fountain = -1;
            nextUse = 0;
            ageOfLastBed = 0;
            blastCount = 0;
            bedSeen = false;
            deathSeen = false;
            previousMine = null;
            bridgeSupport = null;
            approach = null;
            dirX = 1;
            dirZ = 0;
        }
        tick++;
        clear();
        if (!player.isAlive()) {
            action = "respawn";
            return true;
        }
        if (VisibleSlots.equipping(mc) || (mc.currentScreen == null && tick % 4 == 1 && tick < 100)) {
            if (!VisibleSlots.equip(mc)) {
                action = VisibleSlots.status;
                return true;
            }
        }
        if (mc.currentScreen != null) return true;
        px = player.getX();
        pz = player.getZ();
        previousYaw = player.yaw;
        previousPitch = player.pitch;
        if (fountain < 0 && (tick % 10 == 0 || tick == 1)) {
            for (int y = 120; y >= 35; y--)
                if (block(0, y, 0) == Blocks.BEDROCK) {
                    fountain = y - 3;
                    break;
                }
        }
        if (fountain >= 0 && Math.hypot(px - .5, pz - .5) < 6.5 && extinguishNearby()) return true;
        if (fountain >= 0
                && (block(1, fountain, 0) == Blocks.END_PORTAL
                        || block(0, fountain, 1) == Blocks.END_PORTAL)) {
            action = "entering active exit portal";
            BlockPos best = null;
            double nearest = Double.MAX_VALUE;
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++) {
                    if (block(x, fountain, z) != Blocks.END_PORTAL) continue;
                    double d = Math.hypot(px - x - .5, pz - z - .5);
                    if (d < nearest) {
                        nearest = d;
                        best = new BlockPos(x, fountain, z);
                    }
                }
            if (best != null) walk(best.getX() + .5, best.getZ() + .5, false, false);
            return true;
        }
        EnderDragonEntity dragon = null;
        for (Entity e : world.getEntities())
            if (e instanceof EnderDragonEntity) {
                dragon = (EnderDragonEntity) e;
                break;
            }
        if (dragon != null
                && (dragon.getHealth() <= 0
                        || dragon.getPhaseManager().getCurrent().getType() == PhaseType.DYING))
            deathSeen = true;
        if (!deathSeen && dragon != null) {
            for (Entity e : world.getEntities()) {
                if (!(e instanceof AreaEffectCloudEntity)) continue;
                double ax = px - e.getX(), az = pz - e.getZ(), d = Math.hypot(ax, az);
                double margin =
                        dragon.getPhaseManager().getCurrent().getType() == PhaseType.LANDING
                                        || dragon.getPhaseManager().getCurrent().getType()
                                                == PhaseType.LANDING_APPROACH
                                ? .15
                                : 1.3;
                if (d < ((AreaEffectCloudEntity) e).getRadius() + margin
                        && player.getY() < e.getY() + .7
                        && player.getY() + 1.8 > e.getY()) {
                    if (d < .05) {
                        ax = 1;
                        az = 0;
                        d = 1;
                    }
                    travel(px + ax / d * 8, pz + az / d * 8);
                    action = "evading dragon breath";
                    return true;
                }
            }
        }
        if ((deathSeen
                        || dragon == null
                        || dragon.getPhaseManager().getCurrent().getType()
                                        != PhaseType.STRAFE_PLAYER
                                && dragon.partHead.getPos().squaredDistanceTo(eye()) > 26 * 26)
                && eat()) return true;
        if (deathSeen) {
            action = "waiting for exit portal";
            return true;
        }
        if (fountain >= 0
                && dragon != null
                && Math.hypot(px - .5, pz - .5) < 27
                && world.getBlockState(local(-1, fountain + 3, 0)).getBlock() instanceof BedBlock) {
            PhaseType<?> phase = dragon.getPhaseManager().getCurrent().getType();
            if (phase == PhaseType.HOLDING_PATTERN
                    || phase == PhaseType.STRAFE_PLAYER
                    || phase == PhaseType.CHARGING_PLAYER) {
                double radius = 18;
                if (phase != PhaseType.HOLDING_PATTERN)
                    standbyAngle = Math.atan2(pz - .5, px - .5) + .61;
                double tx = .5 + Math.cos(standbyAngle) * radius,
                        tz = .5 + Math.sin(standbyAngle) * radius;
                for (Entity e : world.getEntities()) {
                    if (!(e instanceof AreaEffectCloudEntity)) continue;
                    if (Math.hypot(tx - e.getX(), tz - e.getZ())
                            < ((AreaEffectCloudEntity) e).getRadius() + 4.0) {
                        standbyAngle += .94;
                        tx = .5 + Math.cos(standbyAngle) * radius;
                        tz = .5 + Math.sin(standbyAngle) * radius;
                    }
                }
                if (Math.hypot(px - tx, pz - tz) > 1.5) {
                    travel(tx, tz);
                    action = "waiting clear of fountain, " + phase;
                    return true;
                }
                watchFountain();
                action = "waiting for dragon to land, " + phase;
                return true;
            }
        }
        boolean pursuit =
                dragon != null
                        && fountain >= 0
                        && !hasBed()
                        && !((world.getBlockState(local(-1, fountain + 3, 0)).getBlock())
                                instanceof BedBlock)
                        && dragon.getPhaseManager().getCurrent().isSittingOrHovering();
        if ((!pursuit && Math.hypot(px - .5, pz - .5) > 5.4) || fountain < 0) {
            if (px > 21 && px < 72) travel(16.5, 14.5);
            else travel(.5, -1.1);
            return true;
        }
        if (fountain < 0) return true;
        // Fire can replace either empty bed half after an explosion. A survival hit extinguishes
        // it.
        BlockPos foot = local(-1, fountain + 3, 0), head = local(-1, fountain + 3, 1);
        boolean placed = world.getBlockState(foot).getBlock() instanceof BedBlock;
        if (!placed && dragon != null && !hasBed()) {
            action = "tracking dragon for melee";
            melee(dragon);
            return true;
        }
        if (bedSeen && !placed) {
            lastBlast = tick;
            blastCount++;
        }
        bedSeen = placed;
        double[] station = point(placed ? .65 : -.19, placed ? -.52 : -.55);
        double dist = Math.hypot(px - station[0], pz - station[1]);
        if (dist > .105 || player.getY() > fountain + .2 || player.getY() < fountain - .2) {
            action = placed ? "moving behind fountain cover" : "approaching bed placement position";
            if (dist > 2.4) {
                // Enter the north half of the bowl before sliding beside the center post.
                double[] entry = point(.5, -1.25);
                if (Math.hypot(px - entry[0], pz - entry[1]) > 1
                        && (px - .5) * dirZ - (pz - .5) * dirX < -.2) {
                    travel(entry[0], entry[1]);
                    return true;
                }
            }
            walk(station[0], station[1], dist > 1.4 && player.horizontalCollision, false);
            return true;
        }
        if (placed) {
            BlockHitResult trigger = bedTrigger(foot);
            if (tick <= nextUse || dragon == null) return true;
            Vec3d h = dragon.partHead.getPos(), boom = Vec3d.ofCenter(head);
            double d = h.distanceTo(boom);
            action =
                    String.format(
                            Locale.ROOT,
                            "bed armed. head %.1f, dragon %.0f, blasts %d, phase %s",
                            d,
                            dragon.getHealth(),
                            blastCount,
                            dragon.getPhaseManager().getCurrent().getType());
            if (d <= 4.6
                    || (d < 6.3
                            && (dragon.getHealth() < 25
                                    || dragon.getPhaseManager()
                                            .getCurrent()
                                            .isSittingOrHovering()))) {
                if (tick - lastBlast < 11) return true;
                if (trigger != null) {
                    use(trigger);
                    nextUse = tick + 4;
                } else {
                    action = "lining up with exposed bed foot";
                }
            }
            return true;
        }
        for (BlockPos pos : new BlockPos[] {foot, head}) {
            if (world.getBlockState(pos).getBlock() == Blocks.FIRE) {
                mine(pos);
                return true;
            }
        }
        if (!select(s -> s.getItem() instanceof BedItem)) {
            action = "out of beds; awaiting melee range";
            if (dragon != null) melee(dragon);
            return true;
        }
        BlockPos pillar = local(0, fountain + 3, 0);
        double[] side = point(-.002, .74);
        BlockHitResult hit = aimAt(pillar, new Vec3d(side[0], pillar.getY() + .36, side[1]));
        if (hit == null || hit.getSide() != direction(-1, 0)) {
            action = "aligning with fountain side";
            return true;
        }
        // A normal movement packet must have reached the server before the placement packet so its
        // orientation matches ours.
        if (!settledAim() || tick < nextUse) {
            action = "aiming bed";
            return true;
        }
        use(hit);
        nextUse = tick + 3;
        ageOfLastBed = tick;
        action = "placing bed beside fountain";
        return true;
    }

    private static boolean eat() {
        if (!player.isUsingItem()
                && (player.getHungerManager().getFoodLevel() > 17
                        && (player.getHealth() >= 19
                                || player.getHungerManager().getFoodLevel() >= 20))) return false;
        if (!select(s -> s.isFood())) return false;
        if (!player.isUsingItem())
            mc.interactionManager.interactItem(player, world, Hand.MAIN_HAND);
        mc.options.keyUse.setPressed(true);
        action = "eating";
        return true;
    }

    private static boolean extinguishNearby() {
        BlockPos center = new BlockPos(px, player.getY() + .02, pz);
        BlockPos best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) {
                BlockPos p = center.add(x, 0, z);
                if (world.getBlockState(p).getBlock() != Blocks.FIRE) continue;
                double d =
                        (p.getX() + .5 - px) * (p.getX() + .5 - px)
                                + (p.getZ() + .5 - pz) * (p.getZ() + .5 - pz);
                if (d < distance) {
                    distance = d;
                    best = p;
                }
            }
        if (best == null) return false;
        for (double dx : new double[] {.5, .12, .88})
            for (double dz : new double[] {.5, .12, .88}) {
                BlockHitResult hit =
                        aimAt(
                                best,
                                new Vec3d(best.getX() + dx, best.getY() + .02, best.getZ() + dz));
                if (hit == null) continue;
                mc.interactionManager.attackBlock(best, hit.getSide());
                player.swingHand(Hand.MAIN_HAND);
                action = "extinguishing ground fire";
                return true;
            }
        return false;
    }

    private static boolean hasBed() {
        for (int i = 0; i < 36; i++)
            if (player.inventory.getStack(i).getItem() instanceof BedItem) return true;
        return false;
    }

    private static void watchFountain() {
        double[] side = point(-.002, .74);
        aim(new Vec3d(side[0], fountain + 3.36, side[1]));
    }

    private static BlockHitResult bedTrigger(BlockPos foot) {
        for (double x : new double[] {-.86, -.52, -.12})
            for (double z : new double[] {.04, .17, .45}) {
                double[] p = point(x, z);
                Vec3d target = new Vec3d(p[0], foot.getY() + .40, p[1]);
                if (visible(foot, target)) return turnAndTrace(foot, target);
            }
        return null;
    }

    private static void avoidEndermanGaze() {
        Vec3d origin = eye();
        double yaw = Math.toRadians(player.yaw), pitch = Math.toRadians(player.pitch);
        Vec3d look =
                new Vec3d(
                        -Math.sin(yaw) * Math.cos(pitch),
                        -Math.sin(pitch),
                        Math.cos(yaw) * Math.cos(pitch));
        for (Entity e : world.getEntities()) {
            if (!(e instanceof EndermanEntity)) continue;
            Vec3d head = new Vec3d(e.getX(), e.getEyeY(), e.getZ()).subtract(origin);
            double d = head.length();
            if (d < 1.25
                    || d < 65 && look.dotProduct(head.normalize()) > 1.0 - .24 / Math.max(1, d)) {
                turn(player.yaw, 76);
                return;
            }
        }
    }

    private static void melee(EnderDragonEntity dragon) {
        if (!dragon.getPhaseManager().getCurrent().isSittingOrHovering()) {
            if (fountain >= 0) watchFountain();
            return;
        }
        Box head = dragon.partHead.getBoundingBox();
        Vec3d look =
                new Vec3d(
                        MathHelper.clamp(px, head.minX + .08, head.maxX - .08),
                        MathHelper.clamp(player.getEyeY(), head.minY + .08, head.maxY - .08),
                        MathHelper.clamp(pz, head.minZ + .08, head.maxZ - .08));
        if (eye().distanceTo(look) > 2.85) {
            Vec3d target = head.getCenter();
            walk(
                    target.x,
                    target.z,
                    look.y > player.getEyeY() + 2.5 || player.horizontalCollision,
                    false);
            return;
        }
        if (!select(s -> s.getItem() instanceof SwordItem)
                && !select(s -> s.getItem() instanceof AxeItem))
            select(s -> s.getItem() instanceof PickaxeItem);
        aim(look);
        mc.gameRenderer.updateTargetedEntity(1.0f);
        if (mc.crosshairTarget instanceof EntityHitResult
                && ((EntityHitResult) mc.crosshairTarget).getEntity() == dragon.partHead
                && player.getAttackCooldownProgress(.5f) >= .95f) {
            mc.interactionManager.attackEntity(player, dragon.partHead);
            player.swingHand(Hand.MAIN_HAND);
            action = "striking perched dragon";
        }
    }

    private static void travel(double tx, double tz) {
        if (px > 39 && px < 50 && Math.abs(pz - .5) < 7 && tx < px - 1) {
            double side = pz >= .5 ? 9.5 : -8.5;
            walk(px + .8, side, player.horizontalCollision, false);
            action = "rounding obsidian pillar";
            return;
        }
        if (previousMine != null && solid(previousMine)) {
            if (mine(previousMine)) return;
            previousMine = null;
        }
        if (bridgeSupport != null) {
            bridge();
            return;
        }
        int fy = MathHelper.floor(player.getY() + .05);
        BlockPos feet = new BlockPos(px, fy, pz);
        double dx = tx - px, dz = tz - pz;
        Direction d =
                Math.abs(dx) >= Math.abs(dz)
                        ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                        : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        if (Math.hypot(dx, dz) < 1.8) {
            walk(tx, tz, player.horizontalCollision, false);
            return;
        }
        BlockPos ahead = feet.offset(d);
        if (Math.abs(dx) > 5
                && Math.abs(dz) < 3
                && player.horizontalCollision
                && Math.abs(pz - feet.getZ() - .5) > .15) {
            action = "aligning in mining lane";
            walk(px, feet.getZ() + .5, false, false);
            return;
        }
        boolean head = solid(ahead.up()), body = solid(ahead), floor = solid(ahead.down());
        if (body && solid(feet.up(2)) && mine(feet.up(2))) return;
        // Stairstep toward daylight if the obsidian platform generated inside end stone.
        if (body
                && (head || solid(ahead.up(2)))
                && (player.horizontalCollision
                        || Math.hypot(px - ahead.getX() - .5, pz - ahead.getZ() - .5) < 1.0)) {
            BlockPos dig = solid(ahead.up(2)) ? ahead.up(2) : ahead.up();
            if (!mine(dig) && !(head && mine(ahead.up()))) mine(ahead);
            return;
        }
        if (!body && head && player.horizontalCollision) {
            mine(ahead.up());
            return;
        }
        if (player.isOnGround()
                && !floor
                && !body
                && !solid(ahead.down(2))
                && !solid(ahead.down(3))
                && !solid(ahead.down(4))
                && solid(feet.down())) {
            bridgeSupport = feet.down();
            approach = d;
            bridge();
            return;
        }
        // A completely open column allows a gentle diagonal toward the fountain.
        boolean jump = body && !head && !solid(ahead.up(2)) || player.horizontalCollision;
        action = "crossing island";
        walk(tx, tz, jump, false);
    }

    private static void bridge() {
        BlockPos target = bridgeSupport.offset(approach);
        if (solid(target)) {
            bridgeSupport = null;
            return;
        }
        if (!select(
                s ->
                        s.getItem() instanceof BlockItem
                                && !(s.getItem() instanceof BedItem)
                                && ((BlockItem) s.getItem())
                                        .getBlock()
                                        .getDefaultState()
                                        .isFullCube(world, bridgeSupport))) {
            action = "bridge needs solid blocks";
            return;
        }
        int dx = approach.getOffsetX(), dz = approach.getOffsetZ();
        double cx = bridgeSupport.getX() + .5, cz = bridgeSupport.getZ() + .5;
        double progress = (px - cx) * dx + (pz - cz) * dz;
        action = "sneak bridging";
        mc.options.keySneak.setPressed(true);
        if (progress < .57) {
            walk(cx + dx * .74, cz + dz * .74, false, true);
            return;
        }
        Vec3d surface = new Vec3d(cx + dx * .499, bridgeSupport.getY() + .75, cz + dz * .499);
        BlockHitResult hit = aimAt(bridgeSupport, surface);
        if (hit != null && hit.getSide() == approach && tick >= nextUse) {
            use(hit);
            nextUse = tick + 3;
        }
    }

    private static boolean mine(BlockPos pos) {
        select(s -> s.getItem() instanceof PickaxeItem);
        for (double dy : new double[] {.5, .2, .8}) {
            BlockHitResult hit =
                    aimAt(pos, new Vec3d(pos.getX() + .5, pos.getY() + dy, pos.getZ() + .5));
            if (hit == null) continue;
            mc.options.keyAttack.setPressed(true);
            if (!pos.equals(previousMine)) mc.interactionManager.attackBlock(pos, hit.getSide());
            previousMine = pos;
            action = "mining " + pos;
            return true;
        }
        return false;
    }

    private static void walk(double x, double z, boolean jump, boolean forcedSneak) {
        double dx = x - px, dz = z - pz, d = Math.hypot(dx, dz);
        boolean sneak = forcedSneak || d < 1.15;
        mc.options.keySneak.setPressed(sneak);
        if (d < (sneak ? .07 : .14)) return;
        if (fountain >= 0 && Math.hypot(px - .5, pz - .5) < 7.0) {
            watchFountain();
        } else {
            turn((float) Math.toDegrees(Math.atan2(-dx, dz)), 35);
            avoidEndermanGaze();
        }
        // Project the movement onto the current camera axes. Small changes of position
        // around the post use strafe/back keys while the crosshair stays on the fountain.
        double angle = Math.toRadians(player.yaw);
        double forward = (-Math.sin(angle) * dx + Math.cos(angle) * dz) / d;
        double left = (Math.cos(angle) * dx + Math.sin(angle) * dz) / d;
        mc.options.keyForward.setPressed(forward > .3827);
        mc.options.keyBack.setPressed(forward < -.3827);
        mc.options.keyLeft.setPressed(left > .3827);
        mc.options.keyRight.setPressed(left < -.3827);
        mc.options.keyJump.setPressed(jump);
        mc.options.keySprint.setPressed(forward > .3827 && d > 5 && !sneak);
    }

    private static void clear() {
        mc.options.keyForward.setPressed(false);
        mc.options.keyBack.setPressed(false);
        mc.options.keyLeft.setPressed(false);
        mc.options.keyRight.setPressed(false);
        mc.options.keyJump.setPressed(false);
        mc.options.keySprint.setPressed(false);
        mc.options.keySneak.setPressed(false);
        mc.options.keyAttack.setPressed(false);
        mc.options.keyUse.setPressed(false);
    }

    private static boolean select(Predicate<ItemStack> condition) {
        if (condition.test(player.getMainHandStack())) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.inventory.getStack(i);
            if (s.isEmpty() || !condition.test(s)) continue;
            if (i < 9) player.inventory.selectedSlot = i;
            else
                mc.interactionManager.clickSlot(
                        player.playerScreenHandler.syncId,
                        i,
                        player.inventory.selectedSlot,
                        SlotActionType.SWAP,
                        player);
            return true;
        }
        return false;
    }

    private static void use(BlockHitResult hit) {
        mc.interactionManager.interactBlock(player, world, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
    }

    private static Vec3d eye() {
        return new Vec3d(px, player.getEyeY(), pz);
    }

    private static void aim(Vec3d target) {
        Vec3d d = target.subtract(eye());
        turn((float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
    }

    private static void turn(float yaw, float pitch) {
        player.yaw = previousYaw + MathHelper.clamp(MathHelper.wrapDegrees(yaw - previousYaw), -12f, 12f);
        player.pitch = previousPitch + MathHelper.clamp(pitch - previousPitch, -8f, 8f);
    }

    private static boolean visible(BlockPos wanted, Vec3d target) {
        Vec3d eye = eye();
        Vec3d end = eye.add(target.subtract(eye).normalize().multiply(mc.interactionManager.getReachDistance()));
        BlockHitResult geometric = world.rayTrace(new RayTraceContext(eye, end,
                RayTraceContext.ShapeType.OUTLINE, RayTraceContext.FluidHandling.NONE, player));
        return geometric.getType() == HitResult.Type.BLOCK && geometric.getBlockPos().equals(wanted);
    }

    private static BlockHitResult aimAt(BlockPos wanted, Vec3d target) {
        return visible(wanted, target) ? turnAndTrace(wanted, target) : null;
    }

    private static BlockHitResult turnAndTrace(BlockPos wanted, Vec3d target) {
        aim(target);
        HitResult ray = player.rayTrace(mc.interactionManager.getReachDistance(), 1.0f, false);
        if (ray instanceof BlockHitResult
                && ray.getType() == HitResult.Type.BLOCK
                && ((BlockHitResult) ray).getBlockPos().equals(wanted)) return (BlockHitResult) ray;
        return null;
    }

    private static boolean settledAim() {
        return Math.abs(MathHelper.wrapDegrees(player.yaw - previousYaw)) < 2
                && Math.abs(player.pitch - previousPitch) < 2;
    }

    private static Block block(int x, int y, int z) {
        return world.getBlockState(new BlockPos(x, y, z)).getBlock();
    }

    private static boolean solid(BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static double[] point(double x, double z) {
        return new double[] {
            .5 + (x - .5) * dirX - (z - .5) * dirZ, .5 + (x - .5) * dirZ + (z - .5) * dirX
        };
    }

    private static BlockPos local(int x, int y, int z) {
        return new BlockPos(x * dirX - z * dirZ, y, x * dirZ + z * dirX);
    }

    private static Direction direction(int x, int z) {
        return Direction.fromVector(x * dirX - z * dirZ, 0, x * dirZ + z * dirX);
    }
}
