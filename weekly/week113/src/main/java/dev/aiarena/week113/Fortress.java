package dev.aiarena.week113;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
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
    private static boolean excavating, guardingBridge;
    private static int bridgeSwing = -20;
    private static int previousEnemy, swordTicks, jumpingAt = -1, jumpTicks;
    private static int lastSwing = -20;
    private static boolean ownsInventory, fighting;
    private static int pocketRods = -1, exitStep, observedCageRod = -1;
    public static String status = "approaching the fortress spawner";

    private Fortress() {}

    public static void reset() {
        excavating = guardingBridge = false;
        bridgeSwing = -20;
        previousEnemy = -1;
        pocketRods = observedCageRod = -1;
        exitStep = 0;
        swordTicks = 0;
        jumpingAt = -1;
        jumpTicks = 0;
        lastSwing = -20;
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

    /** Covers the bridge after the second pearl while the third launch still uses the reference route. */
    static boolean guardBridge(MinecraftClient mc, int reference) {
        WitherSkeletonEntity threat = null;
        double nearest = guardingBridge ? 81 : 49;
        boolean onReturnBridge = reference >= 5161 && reference < 5412
                && mc.player.inventory.count(Items.BLAZE_ROD) >= 6
                && mc.player.getX() >= -38 && mc.player.getX() <= 3
                && mc.player.getZ() >= 150 && mc.player.getZ() <= 174;
        if (((reference >= 3350 && reference < 3490) || onReturnBridge) && mc.player.getX() > -39
                && mc.player.getY() >= 54 && !mc.player.isInLava() && mc.currentScreen == null) {
            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof WitherSkeletonEntity) || !entity.isAlive()
                        || Math.abs(entity.getY() - mc.player.getY()) > 2.75) continue;
                double distance = entity.squaredDistanceTo(mc.player);
                if (distance < nearest && mc.player.canSee(entity)) {
                    nearest = distance;
                    threat = (WitherSkeletonEntity) entity;
                }
            }
        }
        if (threat == null) {
            if (guardingBridge) {
                mc.options.keyUse.setPressed(false);
                if (mc.player.getActiveItem().getItem() == Items.SHIELD)
                    mc.interactionManager.stopUsingItem(mc.player);
            }
            guardingBridge = false;
            return false;
        }

        guardingBridge = true;
        Race.stop(mc);
        mc.options.keyAttack.setPressed(false);
        if (mc.player.isUsingItem() && mc.player.getActiveItem().getItem() != Items.SHIELD) {
            mc.options.keyUse.setPressed(false);
            mc.interactionManager.stopUsingItem(mc.player);
        }
        Vec3d point = threat.getPos().add(0, 1.55, 0);
        Race.aim(mc, point);
        mc.gameRenderer.updateTargetedEntity(1);
        boolean sword = Middle.select(mc, Items.DIAMOND_SWORD);
        boolean shield = mc.player.getOffHandStack().getItem() == Items.SHIELD;
        double dx = threat.getX() - mc.player.getX(), dz = threat.getZ() - mc.player.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < 2.65) walk(mc, -dx, -dz);
        // A counter after the enemy's visible swing leaves time to raise the shield again.
        // At outer sword reach we can also interrupt the approach before its stone sword connects.
        boolean canStrike = !shield || distance >= 2.50 || threat.handSwinging;
        if (sword && canStrike && mc.player.age - bridgeSwing >= 13
                && mc.player.getAttackCooldownProgress(.5f) >= .995f
                && mc.crosshairTarget instanceof EntityHitResult
                && ((EntityHitResult)mc.crosshairTarget).getEntity() == threat) {
            if (mc.player.isUsingItem()) mc.interactionManager.stopUsingItem(mc.player);
            mc.options.keyUse.setPressed(false);
            mc.interactionManager.attackEntity(mc.player, threat);
            mc.player.swingHand(Hand.MAIN_HAND);
            bridgeSwing = mc.player.age;
            status = "countering the wither skeleton on the fortress bridge";
            return true;
        }
        if (shield && !mc.player.isUsingItem())
            mc.interactionManager.interactItem(mc.player, mc.world, net.minecraft.util.Hand.OFF_HAND);
        mc.options.keyUse.setPressed(shield);
        status = "guarding against the wither skeleton on the fortress bridge";
        return true;
    }

    private static boolean clear(MinecraftClient mc, BlockPos p) {
        return mc.world.getBlockState(p).getCollisionShape(mc.world, p).isEmpty();
    }

    private static boolean safeStep(MinecraftClient mc, double dx, double dz, BlockPos rodLanding) {
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
        // A two-block descent needs an observed rod and a checked landing.
        // Ordinary combat movement stays shallow.
        if (rodLanding != null && clear(mc, floor)
                && (floor.equals(rodLanding) || cageRodApproach(mc, floor, rodLanding)))
            floor = floor.down();
        return !clear(mc, floor) && !mc.world.getFluidState(floor).isIn(FluidTags.LAVA);
    }

    private static boolean cageRodApproach(MinecraftClient mc, BlockPos feet, BlockPos rodFeet) {
        if (feet.getY() != 59 || rodFeet.getY() != 59
                || feet.getX() < -10 || feet.getX() > -6 || feet.getZ() < 113 || feet.getZ() > 118
                || rodFeet.getX() < -10 || rodFeet.getX() > -6 || rodFeet.getZ() < 113 || rodFeet.getZ() > 118
                || mc.world.getBlockState(feet.down()).getBlock() != Blocks.NETHER_BRICKS) return false;
        // The open area around the spawner is two blocks lower. Verify each intermediate
        // landing column even when the observed rod rests a further column away.
        for (int h = 0; h <= 3; h++) {
            BlockPos cell = feet.up(h);
            Block block = mc.world.getBlockState(cell).getBlock();
            if (!clear(mc, cell) || !mc.world.getFluidState(cell).isEmpty()
                    || block == Blocks.FIRE || block == Blocks.SOUL_FIRE) return false;
        }
        return true;
    }

    private static void walk(MinecraftClient mc, double dx, double dz) {
        walk(mc, dx, dz, null, false);
    }

    private static boolean canClimbCageStep(MinecraftClient mc, double dx, double dz) {
        BlockPos feet = new BlockPos(mc.player.getX() + .65 * dx, mc.player.getY() + .06,
                mc.player.getZ() + .65 * dz);
        if (feet.getY() < 58 || feet.getY() > 61 || feet.getX() < -10 || feet.getX() > -5
                || feet.getZ() < 112 || feet.getZ() > 120
                || mc.world.getBlockState(feet).getBlock() != Blocks.NETHER_BRICKS) return false;
        return clear(mc, feet.up()) && clear(mc, feet.up(2))
                && clear(mc, mc.player.getBlockPos().up(2))
                && mc.world.getBlockState(feet.up()).getBlock() != Blocks.FIRE
                && !mc.world.getFluidState(feet.up()).isIn(FluidTags.LAVA);
    }

    private static void walk(MinecraftClient mc, double dx, double dz, BlockPos rodLanding, boolean chase) {
        double length = Math.hypot(dx, dz);
        if (length < .08) return;
        dx /= length;
        dz /= length;
        if (chase && canClimbCageStep(mc, dx, dz)) {
            mc.options.keyJump.setPressed(mc.player.isOnGround());
        } else if (!safeStep(mc, dx, dz, rodLanding)) {
            // Try stepping around a fence post or the spawner instead of backing into a wall.
            double leftX = (dx + dz) * .707, leftZ = (dz - dx) * .707;
            double rightX = (dx - dz) * .707, rightZ = (dz + dx) * .707;
            if (safeStep(mc, leftX, leftZ, rodLanding)) {
                dx = leftX;
                dz = leftZ;
            } else if (safeStep(mc, rightX, rightZ, rodLanding)) {
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

    private static boolean enterSouthCageGap(MinecraftClient mc, BlazeEntity target, Vec3d aim) {
        if (target==null || target.getZ()>116.7 || mc.player.getZ()<117.5 || mc.player.getZ()>119.1
                || mc.player.getX() < -10.0 || mc.player.getX()>-8.50
                || mc.player.getY()<59.6 || mc.player.getY()>61.1
                || mc.player.getCameraPosVec(1).distanceTo(aim)<=3.0) return false;
        // The west post blocks a direct approach. Only drop beside it onto verified brick.
        BlockPos post=new BlockPos(-10,61,117);
        if (mc.world.getBlockState(post).getBlock()!=Blocks.NETHER_BRICK_FENCE) return false;
        for (int z=117;z<=118;z++) {
            BlockPos floor=new BlockPos(-9,58,z);
            if (mc.world.getBlockState(floor).getBlock()!=Blocks.NETHER_BRICKS) return false;
            for (int h=1;h<=4;h++) {
                BlockPos cell=floor.up(h);
                if (!clear(mc,cell) || !mc.world.getFluidState(cell).isEmpty()
                        || mc.world.getBlockState(cell).getBlock()==Blocks.FIRE) return false;
            }
        }
        takeControls(mc);
        equipSword(mc);
        double x=-8.47,z=118.40;
        Race.aim(mc,new Vec3d(x,mc.player.getEyeY()-.12,z));
        BastionApproach.move(mc,x,z,false,false);
        status="stepping down beside the southern cage fence";
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

    private static BlockPos lowerRodFloor(MinecraftClient mc, ItemEntity rod) {
        BlockPos feet = new BlockPos(rod.getX(), rod.getY() + .04, rod.getZ());
        double drop = mc.player.getY() - feet.getY();
        if (drop < .8
                || drop > 2.2
                || Math.hypot(mc.player.getX() - rod.getX(), mc.player.getZ() - rod.getZ()) > 2.65)
            return null;
        BlockPos ground = feet.down();
        net.minecraft.util.shape.VoxelShape support =
                mc.world.getBlockState(ground).getCollisionShape(mc.world, ground);
        if (support.isEmpty()
                || !Block.isShapeFullCube(support)
                || !mc.world.getFluidState(ground).isEmpty()
                || mc.world.getBlockState(ground).getBlock() == Blocks.MAGMA_BLOCK) return null;
        for (int h = 0; h <= 3; h++) {
            BlockPos body = feet.up(h);
            Block b = mc.world.getBlockState(body).getBlock();
            if (!clear(mc, body)
                    || mc.world.getFluidState(body).isIn(FluidTags.LAVA)
                    || b == Blocks.FIRE
                    || b == Blocks.SOUL_FIRE) return null;
        }
        return feet;
    }

    private static boolean leaveRodPocket(MinecraftClient mc, int goal) {
        if (pocketRods < 0) return false;
        int rods = mc.player.inventory.count(Items.BLAZE_ROD);
        if (rods >= goal && mc.player.inventory.count(Items.BOW) > 0) {
            pocketRods = -1;
            return false;
        }
        if (rods <= pocketRods || mc.player.getY() > 59.05) return false;
        if (mc.player.getZ() > 124
                || mc.player.getZ() < 111
                || mc.player.getX() < -12
                || mc.player.getX() > -7.5) {
            pocketRods = -1;
            return false;
        }
        if (exitStep == 0 && mc.player.getZ() >= 120.15) exitStep++;
        if (exitStep == 1 && mc.player.getZ() < 121.13
                && mc.player.getZ() >= 120.12 && Math.abs(mc.player.getX() + 10.51) < .75) {
            // The pocket ends against a natural netherrack wall in some original runs.
            // Open clearance for the player and for the one-block step just beyond it.
            for (int y : new int[]{58, 57, 59}) {
                BlockPos wall = new BlockPos(-11, y, 121);
                if (mc.world.getBlockState(wall).getBlock() != Blocks.NETHERRACK) continue;
                takeControls(mc);
                if (Middle.select(mc, Items.IRON_PICKAXE))
                    mc.options.keyAttack.setPressed(Middle.aim(mc, wall, null) != null);
                status = "mining the netherrack at the exit from the rod pocket";
                return true;
            }
            mc.options.keyAttack.setPressed(false);
        }
        if (exitStep == 1 && mc.player.getZ() >= 121.13) exitStep++;
        if (exitStep>=2 && mc.player.getX()<=-9.96 && mc.player.getX()>=-11.1
                && mc.player.getZ()>=121.13 && mc.player.getZ()<121.9) {
            // The mined north-south pocket can open onto a second wall east of the turn.
            BlockPos floor=new BlockPos(-10,56,121);
            if (mc.world.getBlockState(floor).isSideSolidFullSquare(mc.world,floor,net.minecraft.util.math.Direction.UP)) {
                for (int y:new int[]{58,57}) {
                    BlockPos wall=new BlockPos(-10,y,121);
                    Block material=mc.world.getBlockState(wall).getBlock();
                    if (material!=Blocks.NETHERRACK && material!=Blocks.NETHER_BRICKS) continue;
                    takeControls(mc);
                    if (Middle.select(mc,Items.IRON_PICKAXE))
                        mc.options.keyAttack.setPressed(Middle.aim(mc,wall,null)!=null);
                    status="mining the eastern wall at the lower fortress turn";
                    return true;
                }
            }
        }
        if (exitStep >= 2 && mc.player.getX() > -8.28) {
            pocketRods = -1;
            exitStep = 0;
            return false;
        }
        double x = exitStep < 2 ? -10.51 : -7.8, z = exitStep == 0 ? 120.55 : 121.4;
        double dx = x - mc.player.getX(),
                dz = z - mc.player.getZ(),
                dist = Math.max(.01, Math.hypot(dx, dz));
        takeControls(mc);
        Race.aim(mc, new Vec3d(x, mc.player.getEyeY() - .25 * dist, z));
        double yaw = Math.toRadians(mc.player.yaw),
                forward = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / dist,
                left = (Math.cos(yaw) * dx + Math.sin(yaw) * dz) / dist;
        mc.options.keyForward.setPressed(forward > .38);
        mc.options.keyBack.setPressed(forward < -.38);
        mc.options.keyLeft.setPressed(left > .38);
        mc.options.keyRight.setPressed(left < -.38);
        mc.options.keyJump.setPressed(mc.player.horizontalCollision && mc.player.isOnGround());
        status = "leaving the rod pocket through the lower fortress corridor";
        return true;
    }

    private static final Vec3d[] CAGE_CORNERS = {
        new Vec3d(-8.46,59,114.54),new Vec3d(-6.54,59,114.54),
        new Vec3d(-6.54,59,116.46),new Vec3d(-8.46,59,116.46)
    };

    private static boolean onCageFloor(Vec3d p) {
        return p.y>=58.95 && p.y<59.30 && p.x>=-9.95 && p.x< -4.95 && p.z>=113 && p.z<118.95;
    }

    /** Check the full width of the player's body and a supported dry floor along each leg. */
    private static boolean cageFloorLine(MinecraftClient mc, Vec3d from, Vec3d to) {
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(to.x-from.x,to.z-from.z)/.20));
        for(int i=0;i<=steps;i++){
            double part=(double)i/steps,x=from.x+(to.x-from.x)*part,z=from.z+(to.z-from.z)*part;
            for(double dx:new double[]{-.299,.299})for(double dz:new double[]{-.299,.299}){
                BlockPos feet=new BlockPos(x+dx,59,z+dz);
                if(mc.world.getBlockState(feet.down()).getBlock()!=Blocks.NETHER_BRICKS)return false;
                for(int h=0;h<2;h++){
                    BlockPos body=feet.up(h);Block b=mc.world.getBlockState(body).getBlock();
                    if(!clear(mc,body) || !mc.world.getFluidState(body).isEmpty()
                            || b==Blocks.FIRE || b==Blocks.SOUL_FIRE)return false;
                }
            }
        }
        return true;
    }

    private static Vec3d cagePickupFoot(MinecraftClient mc, Vec3d player,Vec3d rod) {
        if(rod.y<58.95 || rod.y>61.18 || rod.x< -10.4 || rod.x> -4.4 || rod.z<112.4 || rod.z>119)return null;
        Vec3d goal=null;double best=Double.POSITIVE_INFINITY;
        for(double dx:new double[]{-.94,0,.94})for(double dz:new double[]{-.94,0,.94}){
            Vec3d next=new Vec3d(rod.x+dx,59,rod.z+dz);
            double dist=player.squaredDistanceTo(next);
            if(dist<best && cageFloorLine(mc,next,next)){best=dist;goal=next;}
        }
        return goal;
    }

    private static Vec3d landingFromSpawner(MinecraftClient mc,Vec3d player,Vec3d goal) {
        if(player.y<59.3 || player.y>61.35 || Math.abs(player.x+7.5)>1.3 || Math.abs(player.z-115.5)>1.3)return null;
        if(mc.world.getBlockState(new BlockPos(-8,60,115)).getBlock()!=Blocks.SPAWNER)return null;
        Vec3d best=null;double cost=Double.POSITIVE_INFINITY;
        for(int[] offset:new int[][]{{0,-1},{1,0},{0,1},{-1,0}}){
            Vec3d landing=new Vec3d(-7.5+offset[0]*1.03,59,115.5+offset[1]*1.03);
            if(!cageFloorLine(mc,landing,landing))continue;
            // A vertical two-block descent must remain unobstructed above the hollow cage floor.
            BlockPos feet=new BlockPos(landing);
            if(!clear(mc,feet.up(2)) || !clear(mc,feet.up(3)))continue;
            double length=player.distanceTo(landing)+landing.distanceTo(goal);
            if(length<cost){best=landing;cost=length;}
        }
        return best;
    }

    private static Vec3d rodCorner(MinecraftClient mc, Vec3d start,Vec3d target) {
        if(cageFloorLine(mc,start,target))return null;
        double best=Double.POSITIVE_INFINITY;Vec3d next=null;
        for(int i=0;i<CAGE_CORNERS.length;i++){
            Vec3d corner=CAGE_CORNERS[i];
            double first=start.distanceTo(corner);
            if(first<.10 || !cageFloorLine(mc,start,corner)) continue;
            for(int step=-1;step<=1;step++){
                Vec3d last=CAGE_CORNERS[(i+step+4)%4];
                double distance=first+corner.distanceTo(last)+last.distanceTo(target);
                if(distance>=best || !cageFloorLine(mc,last,target)
                        || step!=0 && !cageFloorLine(mc,corner,last))continue;
                best=distance;next=corner;
            }
        }
        return next;
    }

    private static void takeRod(MinecraftClient mc, ItemEntity rod) {
        status = "collecting a dropped blaze rod";
        previousEnemy = -1;
        Vec3d p = rod.getPos();
        Vec3d player=mc.player.getPos();
        Vec3d goal=cagePickupFoot(mc,player,p);
        if(goal!=null && Math.abs(player.x+7.5)<3.4 && Math.abs(player.z-115.5)<4.1) {
            observedCageRod=rod.getEntityId();
            Vec3d next=landingFromSpawner(mc,player,goal);
            if(next!=null) status="stepping from the spawner onto the clear pickup floor";
            else if(onCageFloor(player)){
                if(cageFloorLine(mc,player,goal))next=goal;
                else next=rodCorner(mc,player,goal);
                if(next!=null)status="walking around the spawner base to the observed rod";
            }
            if(next!=null){
                Race.aim(mc,new Vec3d(next.x,mc.player.getEyeY(),next.z));
                BastionApproach.move(mc,next.x,next.z,false,false);
                return;
            }
        }
        Race.aim(mc, p.add(0, .25, 0));
        BlockPos landing = lowerRodFloor(mc, rod);
        if (landing != null
                && landing.getX() == -11
                && landing.getY() == 57
                && landing.getZ() >= 113
                && landing.getZ() <= 119
                && pocketRods < 0) {
            pocketRods = mc.player.inventory.count(Items.BLAZE_ROD);
            exitStep = 0;
        }
        walk(mc, p.x - mc.player.getX(), p.z - mc.player.getZ(), landing, false);
    }

    private static boolean openCageFloor(MinecraftClient mc) {
        if (Race.referenceTick() < 3700 || mc.player.inventory.count(Items.BOW) == 0
                || mc.player.inventory.count(Items.IRON_PICKAXE) == 0 || !mc.player.isOnGround()
                || mc.player.getY() < 59 || mc.player.getY() > 61.1
                || Math.hypot(mc.player.getX() + 7.5, mc.player.getZ() - 115.5) > 5.5) return false;
        Vec3d eye = mc.player.getCameraPosVec(1);
        BlockPos best = null;
        double score = 16;
        for (int x = -9; x <= -6; x++) {
            for (int z = 113; z <= 117; z++) {
                BlockPos p = new BlockPos(x, 59, z);
                if (mc.world.getBlockState(p).getBlock() != Blocks.NETHER_BRICKS
                        || mc.world.getBlockState(p.down()).getBlock() != Blocks.NETHER_BRICKS
                        || !clear(mc, p.up()) || mc.player.getBoundingBox().intersects(new Box(p.up()))) continue;
                Vec3d top = new Vec3d(x+.5, 59.99, z+.5);
                double distance = eye.squaredDistanceTo(top);
                if (distance >= score) continue;
                BlockHitResult hit = mc.world.rayTrace(new RayTraceContext(eye, top,
                        RayTraceContext.ShapeType.OUTLINE, RayTraceContext.FluidHandling.NONE, mc.player));
                if (hit.getType() == HitResult.Type.BLOCK && p.equals(hit.getBlockPos())) {
                    score = distance;
                    best = p;
                }
            }
        }
        if (best == null) return false;
        Race.stop(mc);
        status = "opening spawn space in the fortress cage";
        mc.options.keyAttack.setPressed(false);
        if (!Middle.select(mc, Items.IRON_PICKAXE)) return true;
        if (Middle.aim(mc, best, net.minecraft.util.math.Direction.UP) != null)
            mc.options.keyAttack.setPressed(true);
        excavating = true;
        return true;
    }

    /** On the flat cage floor, two fully charged falling sword hits defeat a full-health blaze. */
    private static boolean waitForFallingHit(MinecraftClient mc, BlazeEntity blaze, double range) {
        float hp = blaze.getHealth();
        if (jumpingAt != blaze.getEntityId()) {
            jumpingAt = blaze.getEntityId();
            jumpTicks = 0;
        }
        // A grounded diamond-sword sweep can enrage a nearby neutral piglin.
        // Wait for a descending jump even when the blaze needs only one ordinary hit.
        for (ZombifiedPiglinEntity piglin : mc.world.getNonSpectatingEntities(
                ZombifiedPiglinEntity.class, blaze.getBoundingBox().expand(1.15, .35, 1.15))) {
            if (piglin.squaredDistanceTo(mc.player) > 3.3 * 3.3 || !mc.player.canSee(piglin)) continue;
            if (mc.player.fallDistance >= .21f) break;
            if (mc.player.isOnGround()) mc.options.keyJump.setPressed(true);
            return true;
        }
        // The first descending movement packet can still be in transit when a click is issued.
        if (mc.player.fallDistance >= .21f && !mc.player.isSprinting()) return false;
        if (hp <= 7 || (hp > 10.5f && hp <= 17.5f) || range > 3.35
                || mc.player.getHealth() < 7 || mc.player.getY() < 59.5
                || mc.player.getY() > 62.5 || mc.player.getZ() < 111.0 || mc.player.getZ() > 119.9)
            return false;
        if (mc.player.isOnGround()) {
            if (!clear(mc, mc.player.getBlockPos().up(2))) return false;
            if (mc.player.getAttackCooldownProgress(.5f) >= .40f)
                mc.options.keyJump.setPressed(true);
        }
        return ++jumpTicks <= 14;
    }

    /**
     * Returns true only while handling a visible rod, a nearby live blaze, or our sword inventory
     * screen.
     */
    public static boolean tick(MinecraftClient mc, int goal) {
        if (leaveRodPocket(mc, goal)) return true;
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
                if (range < rodRange && (mc.player.canSee(e) || e.getEntityId()==observedCageRod
                        && onCageFloor(mc.player.getPos()) && e.getY()>=58.95 && e.getY()<=61.18)) {
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
        if (rod==null && enterSouthCageGap(mc,target,aim)) return true;
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
            if (openCageFloor(mc)) return true;
            if (excavating) {
                mc.options.keyAttack.setPressed(false);
                mc.interactionManager.cancelBlockBreaking();
                excavating = false;
            }
            previousEnemy = -1;
            swordTicks = 0;
            return false;
        }
        takeControls(mc);
        if (rod != null
                && (target == null
                        || rodRange < .65 * .65
                        || target.squaredDistanceTo(mc.player) > 3.25 * 3.25)) {
            if (Middle.makeRoomForRods(mc,1)) {
                status=Middle.status;
                return true;
            }
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
        else if (range > 2.85 || eye.distanceTo(aim) > 2.98) walk(mc, dx, dz, null, true);
        boolean waitForCritical = waitForFallingHit(mc, target, range);
        if (!waitForCritical && mc.player.age - lastSwing >= 13
                && mc.crosshairTarget instanceof EntityHitResult
                && ((EntityHitResult) mc.crosshairTarget).getEntity() == target
                && mc.player.getAttackCooldownProgress(.5f) >= .995f) {
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
            jumpTicks = 0;
            lastSwing = mc.player.age;
        }
        return true;
    }
}
