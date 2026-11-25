package dev.aiarena.week29;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;

import java.util.*;

/** Local path planning. All travel uses standard movement keys and world collision. */
final class RouteMovement {
    private final MinecraftClient mc;
    private final Map<BlockPos, Cell> cells = new HashMap<>();
    private final Deque<BlockPos> path = new ArrayDeque<>();
    private BlockPos goal, breaking;
    private int ticksAtNode, lastPlan;
    private final LinkedHashMap<BlockPos, Integer> penalties = new LinkedHashMap<>();

    String describe() {
        return "next="
                + (path.isEmpty() ? "none" : path.peekFirst().toShortString())
                + " goal="
                + (goal == null ? "none" : goal.toShortString())
                + " age="
                + ticksAtNode
                + " collision="
                + mc.player.horizontalCollision
                + " digging="
                + (breaking == null ? "none" : breaking.toShortString());
    }

    private static final int[][] DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    RouteMovement(MinecraftClient mc) {
        this.mc = mc;
    }

    void release() {
        mc.options.keyForward.setPressed(false);
        mc.options.keyAttack.setPressed(false);
        mc.options.keyJump.setPressed(false);
        mc.options.keySprint.setPressed(false);
        mc.options.keySneak.setPressed(false);
    }

    void reset() {
        release();
        cancelBreak();
        path.clear();
        cells.clear();
        goal = null;
    }

    void cancelBreak() {
        if (breaking != null) {
            mc.interactionManager.cancelBlockBreaking();
            breaking = null;
        }
    }

    void aim(Vec3d target) {
        double x = target.x - mc.player.getX(),
                y = target.y - mc.player.getEyeY(),
                z = target.z - mc.player.getZ();
        mc.player.yaw = (float) Math.toDegrees(Math.atan2(-x, z));
        mc.player.pitch = (float) -Math.toDegrees(Math.atan2(y, Math.hypot(x, z)));
        mc.gameRenderer.updateTargetedEntity(1f);
    }

    private BlockHitResult hit() {
        return mc.crosshairTarget instanceof BlockHitResult
                        && mc.crosshairTarget.getType() == HitResult.Type.BLOCK
                ? (BlockHitResult) mc.crosshairTarget
                : null;
    }

    BlockHitResult visibleFace(BlockPos target) {
        Vec3d center = Vec3d.ofCenter(target);
        if (mc.player.getCameraPosVec(1).squaredDistanceTo(center) > 28) return null;
        Vec3d[] offsets = {
            new Vec3d(0, 0, 0),
            new Vec3d(0, -.35, 0),
            new Vec3d(0, .42, 0),
            new Vec3d(.4, 0, 0),
            new Vec3d(-.4, 0, 0),
            new Vec3d(0, 0, .4),
            new Vec3d(0, 0, -.4)
        };
        for (Vec3d offset : offsets) {
            aim(center.add(offset));
            BlockHitResult hit = hit();
            if (hit != null && target.equals(hit.getBlockPos())) return hit;
        }
        return null;
    }

    boolean mine(BlockPos target) {
        BlockHitResult hit = visibleFace(target);
        if (hit == null) return false;
        if (!target.equals(breaking)) {
            cancelBreak();
            SurvivalRoute.tool(target);
            breaking = target;
            return true;
        }
        mc.options.keyAttack.setPressed(true);
        return true;
    }

    boolean clearSight(BlockPos target) {
        Vec3d point = Vec3d.ofCenter(target);
        if (mc.player.getCameraPosVec(1).squaredDistanceTo(point) > 20.0) return false;
        aim(point);
        BlockHitResult hit = hit();
        if (hit == null || target.equals(hit.getBlockPos())) return false;
        BlockPos obstruction = hit.getBlockPos();
        BlockState block = mc.world.getBlockState(obstruction);
        if (block.getHardness(mc.world, obstruction) < 0
                || block.getHardness(mc.world, obstruction) > 8
                || block.getBlock() instanceof BedBlock
                || block.getBlock() instanceof ChestBlock) return false;
        return mine(obstruction);
    }

    boolean use(BlockPos target) {
        BlockHitResult hit = visibleFace(target);
        if (hit == null) return false;
        cancelBreak();
        mc.interactionManager.interactBlock(mc.player, mc.world, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    boolean place(BlockPos target) {
        if (!mc.world.getBlockState(target).getMaterial().isReplaceable()) return false;
        for (Direction face : Direction.values()) {
            BlockPos base = target.offset(face.getOpposite());
            if (mc.world.getBlockState(base).getCollisionShape(mc.world, base).isEmpty()) continue;
            Vec3d center = Vec3d.ofCenter(base);
            Vec3d facePoint =
                    center.add(
                            .499 * face.getOffsetX(),
                            .499 * face.getOffsetY(),
                            .499 * face.getOffsetZ());
            aim(facePoint);
            BlockHitResult hit = hit();
            if (hit == null || !base.equals(hit.getBlockPos()) || hit.getSide() != face) continue;
            cancelBreak();
            mc.interactionManager.interactBlock(mc.player, mc.world, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private static final class Cell {
        boolean pass, support, water, unsafe;
        float mineCost;
    }

    private boolean woodenDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock && state.getMaterial() != Material.METAL;
    }

    private Cell cell(BlockPos p) {
        Cell cached = cells.get(p);
        if (cached != null) return cached;
        Cell c = new Cell();
        BlockState s = mc.world.getBlockState(p);
        Block b = s.getBlock();
        c.water = s.getMaterial() == Material.WATER;
        c.unsafe =
                p.getY() < 2
                        || p.getY() > 253
                        || s.getMaterial() == Material.LAVA
                        || b == Blocks.FIRE
                        || b == Blocks.CAMPFIRE
                        || b == Blocks.MAGMA_BLOCK
                        || b == Blocks.CACTUS
                        || b == Blocks.SWEET_BERRY_BUSH;
        if (!mc.world.isChunkLoaded(p)) c.unsafe = true;
        double top =
                s.getCollisionShape(mc.world, p).isEmpty()
                        ? 0
                        : s.getCollisionShape(mc.world, p).getMax(Direction.Axis.Y);
        c.pass = !c.unsafe && (top == 0 || b == Blocks.SNOW || woodenDoor(s));
        c.support = !c.unsafe && top >= .43 && top <= 1.001;
        float hardness = s.getHardness(mc.world, p);
        c.mineCost =
                c.unsafe
                                || hardness < 0
                                || hardness > 8
                                || c.water
                                || b instanceof BedBlock
                                || b instanceof ChestBlock
                                || b == Blocks.CRAFTING_TABLE
                        ? Float.POSITIVE_INFINITY
                        : 4 + 7 * hardness;
        cells.put(p, c);
        return c;
    }

    boolean walkable(BlockPos p) {
        return cell(p).pass
                && cell(p.up()).pass
                && (cell(p.down()).support || cell(p).water || cell(p.down()).water);
    }

    private static final class Node {
        BlockPos pos;
        Node prev;
        double g, f;

        Node(BlockPos p, Node parent, double g, double f) {
            this.pos = p;
            prev = parent;
            this.g = g;
            this.f = f;
        }
    }

    private double estimate(BlockPos p, BlockPos target) {
        return Math.hypot(p.getX() - target.getX(), p.getZ() - target.getZ())
                + .48 * Math.abs(p.getY() - target.getY());
    }

    private void plan(BlockPos target, boolean allowMine) {
        cells.clear();
        path.clear();
        lastPlan = 0;
        ticksAtNode = 0;
        BlockPos start = mc.player.getBlockPos();
        Node best = new Node(start, null, 0, estimate(start, target));
        double bestEstimate = best.f;
        PriorityQueue<Node> pending = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<BlockPos, Double> distance = new HashMap<>();
        pending.add(best);
        distance.put(start, 0d);
        for (int expanded = 0; !pending.isEmpty() && expanded < 2400; expanded++) {
            Node from = pending.remove();
            if (from.g > distance.get(from.pos) + .001) continue;
            double h = estimate(from.pos, target);
            if (h < bestEstimate - .01 || from.pos.equals(target)) {
                best = from;
                bestEstimate = h;
            }
            if (from.pos.equals(target)) break;
            for (int[] d : DIRECTIONS) {
                boolean diag = d[0] != 0 && d[1] != 0;
                for (int dy : new int[] {0, 1, -1, -2, -3}) {
                    BlockPos p = from.pos.add(d[0], dy, d[1]);
                    if (Math.abs(p.getX() - start.getX()) > 34
                            || Math.abs(p.getZ() - start.getZ()) > 34) continue;
                    Cell foot = cell(p), head = cell(p.up()), floor = cell(p.down());
                    if (foot.unsafe || head.unsafe || floor.unsafe) continue;
                    if (!(floor.support || foot.water || floor.water)) continue;
                    if (!foot.pass || !head.pass) {
                        if (!allowMine || diag) continue;
                    }
                    double breakCost =
                            (foot.pass ? 0 : foot.mineCost) + (head.pass ? 0 : head.mineCost);
                    if (!Double.isFinite(breakCost)) continue;
                    if (dy > 0) {
                        if (diag) continue;
                        Cell overhead = cell(from.pos.up(2));
                        if (!overhead.pass) {
                            if (!allowMine || !Double.isFinite(overhead.mineCost)) continue;
                            breakCost += overhead.mineCost;
                        }
                    }
                    if (dy < 0 && (!cell(p.up(-dy + 1)).pass || !cell(p.up(-dy)).pass)) continue;
                    if (diag
                            && (!cell(from.pos.add(d[0], 0, 0)).pass
                                    || !cell(from.pos.add(d[0], 1, 0)).pass
                                    || !cell(from.pos.add(0, 0, d[1])).pass
                                    || !cell(from.pos.add(0, 1, d[1])).pass)) continue;
                    double cost =
                            from.g
                                    + penalties.getOrDefault(p, 0)
                                    + (diag ? 1.42 : 1)
                                    + Math.abs(dy) * .45
                                    + breakCost
                                    + ((foot.water || floor.water) ? 1 : 0);
                    if (cost >= distance.getOrDefault(p, Double.POSITIVE_INFINITY)) continue;
                    distance.put(p, cost);
                    pending.add(new Node(p, from, cost, cost + 1.22 * estimate(p, target)));
                }
            }
        }
        while (best.prev != null) {
            path.addFirst(best.pos);
            best = best.prev;
        }
    }

    double horizontal(BlockPos p) {
        return Math.hypot(mc.player.getX() - p.getX() - .5, mc.player.getZ() - p.getZ() - .5);
    }

    boolean travel(BlockPos target, double tolerance, boolean allowMine) {
        if (horizontal(target) < tolerance && Math.abs(mc.player.getY() - target.getY()) < 1.35) {
            reset();
            return true;
        }
        if (ticksAtNode > 45 && breaking == null && !path.isEmpty()) {
            BlockPos stuck = path.peekFirst();
            penalties.put(stuck, penalties.getOrDefault(stuck, 0) + 12);
            if (penalties.size() > 55) penalties.remove(penalties.keySet().iterator().next());
            path.clear();
        }
        if (!target.equals(goal) || path.isEmpty() || lastPlan > 150) {
            goal = target;
            plan(target, allowMine);
        }
        lastPlan++;
        ticksAtNode++;
        while (!path.isEmpty()
                && horizontal(path.peekFirst()) < (path.size() > 1 ? .60 : .35)
                && Math.abs(mc.player.getY() - path.peekFirst().getY()) < 1.05) {
            path.removeFirst();
            ticksAtNode = 0;
        }
        if (path.isEmpty()) return false;
        BlockPos next = path.peekFirst();
        BlockPos obstacle = null;
        BlockState door = mc.world.getBlockState(next);
        if (woodenDoor(door) && !door.get(DoorBlock.OPEN) && use(next)) return false;
        if (allowMine) {
            cells.remove(next);
            cells.remove(next.up());
            BlockPos above = mc.player.getBlockPos().up(2);
            cells.remove(above);
            if (next.getY() > mc.player.getBlockPos().getY() && !cell(above).pass) obstacle = above;
            else if (!cell(next.up()).pass) obstacle = next.up();
            else if (!cell(next).pass) obstacle = next;
        }
        if (obstacle != null && mine(obstacle)) return false;
        cancelBreak();
        boolean wet = mc.player.isTouchingWater();
        double y = mc.player.getEyeY() + (wet ? .15 : 0);
        aim(new Vec3d(next.getX() + .5, y, next.getZ() + .5));
        mc.options.keyJump.setPressed(
                wet
                        || (next.getY() > mc.player.getY() + .15)
                        || (mc.player.horizontalCollision && ticksAtNode > 5));
        mc.options.keyForward.setPressed(true);
        mc.options.keySprint.setPressed(
                !wet
                        && ticksAtNode < 20
                        && mc.player.getHungerManager().getFoodLevel() > 8
                        && (path.size() > 3 && !allowMine));
        return false;
    }
}
