package dev.aiarena.week113;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.EndermiteEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
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
    private static int stage, index, edgeTicks;
    private static boolean active;
    private static int bankStep=-1, dryStep=-1;
    private static final double[][] DRY_BANK = {
        {-84.62,171.38}, {-85.56,170.58}, {-85.55,167.54}, {-85.45,166.63}, {-84.55,165.50}
    };
    private static final double[][] RETURN_BANK = {
        {-84.52,174.50},{-84.52,172.48},{-84.52,170.50},{-85.50,170.50},{-85.50,166.58},{-84.55,166.52}
    };

    public static String status = "approaching the return ledge";

    private ReturnFrame() {}

    public static void reset() {
        stage = index = 0;
        bankStep=dryStep=-1;EndMeal.reset();
        edgeTicks = 0;
        active = false;
    }

    private static boolean select(MinecraftClient m, Item primary, Item other) {
        for (int slot = 0; slot < 36; slot++) {
            Item i = m.player.inventory.getStack(slot).getItem();
            if (i != primary && (other == null || i != other)) continue;
            return Hotbar.select(m,i,exchangeSlot(m));
        }
        return false;
    }

    static boolean selectSupport(MinecraftClient m) {
        for(Item item:new Item[]{Items.NETHERRACK,Items.DIRT,Items.NETHER_BRICKS,Items.BLACKSTONE,
                Items.CRACKED_POLISHED_BLACKSTONE_BRICKS,Items.CHISELED_POLISHED_BLACKSTONE,
                Items.POLISHED_BLACKSTONE_BRICKS,Items.COBBLESTONE,Items.ANDESITE,Items.DIORITE,Items.GRANITE}) {
            if(m.player.inventory.count(item)>0)return select(m,item,null);
        }
        return false;
    }

    private static int exchangeSlot(MinecraftClient m) {
        // Keep both frame materials and the lighter available through the entire build.
        for (int i=0;i<9;i++) if (m.player.inventory.getStack(i).isEmpty()) return i;
        for (int i=0;i<9;i++) {
            Item item=m.player.inventory.getStack(i).getItem();
            if (item!=Items.DIRT && item!=Items.NETHERRACK && item!=Items.OBSIDIAN
                    && item!=Items.FLINT_AND_STEEL && item!=Items.FIRE_CHARGE
                    && item!=Items.IRON_PICKAXE && item!=Items.DIAMOND_SWORD) return i;
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
        if (!(obsidian ? select(m,Items.OBSIDIAN,null) : selectSupport(m)))
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
            if (!selectSupport(m)) return;
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
    static boolean guardFromEndermite(MinecraftClient m, int reference) {
        if (reference < 5161 || reference >= 5412 || m.player.getY() < 71
                || m.player.squaredDistanceTo(-85, 75, 171) > 15 * 15) return false;
        Entity nearest = null;
        double distance = 3.4 * 3.4;
        for (Entity e : m.world.getEntities(m.player, m.player.getBoundingBox().expand(4))) {
            if (!(e instanceof EndermiteEntity) || !e.isAlive() || !m.player.canSee(e)) continue;
            double d = m.player.squaredDistanceTo(e);
            if (d < distance) { nearest = e; distance = d; }
        }
        if (nearest == null) return false;
        Race.stop(m);
        m.options.keySneak.setPressed(true);
        if (m.player.isUsingItem() && m.player.getActiveItem().getItem() != Items.SHIELD)
            m.interactionManager.stopUsingItem(m.player);
        Race.aim(m, nearest.getBoundingBox().getCenter());
        m.gameRenderer.updateTargetedEntity(1);
        if (m.currentScreen == null && m.player.getMainHandStack().getItem() != Items.DIAMOND_SWORD)
            Hotbar.select(m, Items.DIAMOND_SWORD);
        boolean aimed = m.crosshairTarget instanceof EntityHitResult
                && ((EntityHitResult)m.crosshairTarget).getEntity() == nearest;
        if (aimed && m.player.getMainHandStack().getItem() == Items.DIAMOND_SWORD
                && m.player.getAttackCooldownProgress(.5f) >= .89f) {
            if (m.player.isUsingItem()) m.interactionManager.stopUsingItem(m.player);
            m.options.keyUse.setPressed(false);
            m.interactionManager.attackEntity(m.player, nearest);
            m.player.swingHand(Hand.MAIN_HAND);
        } else if (m.player.getOffHandStack().getItem() == Items.SHIELD) {
            if (!m.player.isUsingItem())
                m.interactionManager.interactItem(m.player, m.world, Hand.OFF_HAND);
            m.options.keyUse.setPressed(true);
        }
        status = "guarding the return ledge from an endermite";
        return true;
    }

    private static boolean onBuiltReturnBank(MinecraftClient m,int reference) {
        if(reference<5208 || reference>=5218 || m.player.getX()<-84.2 || m.player.getX()>-82.9
                || m.player.getZ()<174.3 || m.player.getZ()>175.8 || m.player.getY()<74.8 || m.player.getY()>77.4)
            return false;
        return full(m,new BlockPos(-83,74,177)) && full(m,new BlockPos(-83,74,176))
                && full(m,new BlockPos(-83,75,176)) && full(m,new BlockPos(-85,74,172));
    }

    private static boolean nearDryBank(MinecraftClient m, int reference) {
        if (reference < 5161 || reference > 5220 || m.player.getX() < -87.1 || m.player.getX() > -82.8
                || m.player.getZ() < 168.0 || m.player.getZ() > 176.3 || m.player.getY() < 73
                || m.player.getY() > 78 || m.player.inventory.count(Items.BLAZE_ROD) < 6)
            return false;
        net.minecraft.entity.effect.StatusEffectInstance fire = m.player.getStatusEffect(
                net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE);
        if (fire == null || fire.getDuration() < 160) return false;
        for (double[] stance : DRY_BANK) {
            BlockPos at = new BlockPos(stance[0],74,stance[1]);
            if (!full(m,at) && !full(m,at.down())) return false;
            if (full(m,at.up(2))) return false;
        }
        return true;
    }

    public static boolean tick(MinecraftClient m, int reference) {
        if (!active) {
            boolean onBank=onBuiltReturnBank(m,reference);
            boolean dry=nearDryBank(m,reference);
            if ((!onBank && !dry && reference < 5218)
                    || reference >= 5412
                    || m.player.getY() < 68
                    || m.player.getY() > 84
                    || m.player.squaredDistanceTo(-84.5, 75, 168.5) > 15 * 15) return false;
            if(dry)dryStep=m.player.getZ()<170.50 ? 2 : m.player.getZ()<172.7 ? 1 : 0;
            else if(onBank)bankStep=0;
            active = true;
        }
        Race.stop(m);
        m.options.keyAttack.setPressed(false);
        if (dryStep >= 0 && dryStep < DRY_BANK.length) {
            double[] to = DRY_BANK[dryStep];
            status = "crossing to the dry western edge of the portal ledge";
            if (go(m,to[0],to[1],dryStep == DRY_BANK.length-1 ? .16 : .35,false)) {
                if (++dryStep == DRY_BANK.length) finishStage();
            } else if (m.player.isInLava()) {
                m.options.keySneak.setPressed(false);
                m.options.keyJump.setPressed(true);
            } else if (Math.hypot(to[0]-m.player.getX(),to[1]-m.player.getZ()) > 1.7) {
                m.options.keySprint.setPressed(m.options.keyForward.isPressed());
            }
            return true;
        }
        if(bankStep>=0 && bankStep<RETURN_BANK.length) {
            double[] to=RETURN_BANK[bankStep];
            status="crossing the shallow lava onto the solid western return bank "+(bankStep+1);
            if(go(m,to[0],to[1],.30,false))bankStep++;
            else if(m.player.isInLava()) {
                m.options.keySneak.setPressed(false);
                m.options.keyJump.setPressed(true);
            }
            return true;
        }
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
                boolean corner = UpperLootRoute.enabled && c[1] == 77 && (c[0] == -88 || c[0] == -85);
                if (corner ? full(m, next) : m.world.getBlockState(next).getBlock() == Blocks.OBSIDIAN) {
                    index++;
                    break;
                }
                if (!(corner ? selectSupport(m) : select(m, Items.OBSIDIAN, null))) break;
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
                if (++edgeTicks <= 150) {
                    status = "holding the outer edge of the high return portal";
                    double x = -86.69, z = 165.264;
                    double dx = x - m.player.getX() - 1.15 * m.player.getVelocity().x;
                    double dz = z - m.player.getZ() - 1.15 * m.player.getVelocity().z;
                    if (Math.hypot(dx, dz) > .013) {
                        Race.aim(m, new Vec3d(m.player.getX()+dx*6, m.player.getEyeY(),
                                m.player.getZ()+dz*6));
                        move(m, dx, dz, true);
                    }
                    m.options.keySneak.setPressed(true);
                    if(EndMeal.inReturn(m,edgeTicks))status="eating on the outer edge of the return portal";
                    break;
                }
                go(m, -86.7, 164.515625, .11, false);
                // The protected pad is one block higher than the portal sill.
                m.options.keySneak.setPressed(false);
        }
        return true;
    }
}
