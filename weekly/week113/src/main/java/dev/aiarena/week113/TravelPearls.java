package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

/** Native wall clutch and second throw across the fortress lava lake. */
final class TravelPearls {
    private static final Vec3d FORTRESS = new Vec3d(-25.5, 57, 167.5);
    private static Vec3d aimDirection;
    private static boolean jumping;
    private static Vec3d firstDirection;
    private static int firstReady;
    private static int readyTicks;

    static void reset() { aimDirection = null; jumping = false; readyTicks = 0; firstDirection=null; firstReady=0; }

    static boolean catchWall(MinecraftClient mc) {
        if (mc.player.getX() < -98) {
            // Track the approaching fortress wall during pearl flight.
            Race.aim(mc,mc.player.getCameraPosVec(1).add(0,-1.5,1));
            return false;
        }
        BlockPos at = mc.player.getBlockPos();
        if (mc.player.isOnGround() && at.getY()>34 &&
                !mc.world.getBlockState(at.down()).getCollisionShape(mc.world,at.down()).isEmpty()) return true;
        if (!Middle.selectBuildingBlock(mc,false)) return false;
        if (mc.player.isInLava()) mc.options.keyJump.setPressed(true);
        // A pearl can strike a different brick or height on each attempt.
        for (int below=1;below<=3;below++) {
            BlockPos floor=at.down(below);
            if (!mc.world.getBlockState(floor).getMaterial().isReplaceable()) continue;
            for (Direction d : new Direction[]{Direction.SOUTH,Direction.EAST,Direction.WEST,Direction.NORTH}) {
                BlockPos wall=floor.offset(d);
                if (mc.world.getBlockState(wall).getCollisionShape(mc.world,wall).isEmpty()) continue;
                BlockHitResult hit=Middle.aim(mc,wall,d.getOpposite());
                if (hit!=null) Middle.use(mc,hit);
                return false;
            }
        }
        return false;
    }

    private static Vec3d direction(double yaw,double pitch) {
        double y=Math.toRadians(yaw),p=Math.toRadians(pitch);
        return new Vec3d(-Math.sin(y)*Math.cos(p),-Math.sin(p),Math.cos(y)*Math.cos(p));
    }

    private static double landingScore(MinecraftClient mc,Vec3d start,Vec3d velocity,Vec3d goal,int minY,int maxY) {
        Vec3d pos=start;
        for (int t=0;t<88;t++) {
            Vec3d next=pos.add(velocity);
            BlockHitResult hit=mc.world.rayTrace(new RayTraceContext(pos,next,RayTraceContext.ShapeType.COLLIDER,RayTraceContext.FluidHandling.NONE,mc.player));
            if (hit.getType()==HitResult.Type.BLOCK) {
                BlockPos floor=hit.getBlockPos();
                if (hit.getSide()!=Direction.UP || floor.getY()<minY || floor.getY()>maxY
                        || !mc.world.getBlockState(floor.up()).getCollisionShape(mc.world,floor.up()).isEmpty()
                        || !mc.world.getBlockState(floor.up(2)).getCollisionShape(mc.world,floor.up(2)).isEmpty()
                        || mc.world.getBlockState(floor).getBlock()==Blocks.MAGMA_BLOCK) return Double.POSITIVE_INFINITY;
                int support=0;
                for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                    BlockPos q=floor.offset(d);
                    if (!mc.world.getBlockState(q).getCollisionShape(mc.world,q).isEmpty()
                         && mc.world.getBlockState(q.up()).getCollisionShape(mc.world,q.up()).isEmpty()) support++;
                }
                return hit.getPos().distanceTo(goal)+(4-support)*1.3;
            }
            if(next.y<31)return Double.POSITIVE_INFINITY;
            pos=next;velocity=velocity.multiply(.99).add(0,-.03,0);
        }
        return Double.POSITIVE_INFINITY;
    }

    // Raise the shoreline throw above the basalt shelf. The first pearl lands on
    // its broad upper surface, leaving a clear second throw to the fortress.
    private static boolean rising(MinecraftClient mc,Vec3d aim) {
        if(mc.player.getVelocity().y<=0 || mc.getServer()==null)return false;
        // On a slow integrated server the client can render a later jump tick.
        // Wait for the actual player launch impulse before sending the use input.
        net.minecraft.server.network.ServerPlayerEntity player=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if(player==null || player.isOnGround() || player.getVelocity().y<.389) return false;
        return player.getRotationVec(1).dotProduct(aim)>.998;
    }

    static boolean aimFirst(MinecraftClient mc) {
        Vec3d base=new Vec3d(-105.50,41,148.50);
        double horizontal=Math.hypot(mc.player.getX()-base.x,mc.player.getZ()-base.z);
        if (mc.world.getBlockState(new BlockPos(base.x,40,base.z)).getMaterial().isReplaceable() || horizontal>.26) {
            if (horizontal>.22) {
                Race.aim(mc,new Vec3d(base.x,mc.player.getEyeY()-.12,base.z));
                Middle.approach(mc,base,horizontal<.7);
                mc.options.keyJump.setPressed(false);
                return false;
            }
            if (!Middle.selectBuildingBlock(mc,true)) return false;
            BlockPos top=new BlockPos(base.x,40,base.z);
            while (top.getY()>29 && mc.world.getBlockState(top).getMaterial().isReplaceable()) top=top.down();
            BlockHitResult hit=Middle.aim(mc,top,Direction.UP);
            mc.options.keyJump.setPressed(true);
            if (mc.player.getY()>=top.getY()+1.99 && hit!=null) Middle.use(mc,hit);
            return false;
        }
        if (firstDirection==null && mc.player.isOnGround()) {
            Vec3d start=mc.player.getPos().add(0,mc.player.getStandingEyeHeight()+.42-.10,0);
            Vec3d shelf=new Vec3d(-77.5,74,174.5);
            double best=Double.POSITIVE_INFINITY;
            for(int yaw=-36;yaw>=-60;yaw-=2) for(int pitch=-43;pitch>=-68;pitch--) {
                Vec3d dir=direction(yaw,pitch);
                double a=landingScore(mc,start,dir.multiply(1.5).add(0,.42,0),shelf,70,79);
                if (!Double.isFinite(a)) continue;
                double b=landingScore(mc,start,dir.multiply(1.5).add(0,.335,0),shelf,70,79);
                double score=Math.max(a,b);
                if (score<best) {best=score;firstDirection=dir;}
            }
            if(firstDirection==null) firstDirection=direction(-48,-55);
        }
        if(firstDirection==null || !Middle.select(mc,Items.ENDER_PEARL))return false;
        Race.aim(mc,mc.player.getCameraPosVec(1).add(firstDirection.multiply(10)));
        if(mc.player.getRotationVec(1).dotProduct(firstDirection)<.9998){firstReady=0;return false;}
        if(++firstReady<3) return false;
        if(mc.player.isOnGround()){mc.options.keyJump.setPressed(true);return false;}
        return rising(mc,firstDirection);
    }

    static boolean aimSecond(MinecraftClient mc) {
        if (aimDirection==null && mc.player.isOnGround()) {
            Vec3d offset=FORTRESS.subtract(mc.player.getPos());
            double yaw=Math.toDegrees(Math.atan2(-offset.x,offset.z));
            Vec3d start=mc.player.getPos().add(0,mc.player.getStandingEyeHeight()+.42-.10,0);
            double best=Double.POSITIVE_INFINITY;
            for (int delta : new int[]{0,-3,3,-6,6,-9,9,-12,12}) {
                for (int pitch=26;pitch>=-62;pitch-=2) {
                    Vec3d dir=direction(yaw+delta,pitch);
                    double score=landingScore(mc,start,dir.multiply(1.5).add(0,.42,0),FORTRESS,55,60);
                    if (score<best) { best=score;aimDirection=dir; }
                }
            }
            if(aimDirection==null) aimDirection=direction(yaw,7);
        }
        if (aimDirection==null) return false;
        Race.aim(mc,mc.player.getCameraPosVec(1).add(aimDirection.multiply(10)));
        if(mc.player.getRotationVec(1).dotProduct(aimDirection)<.9998) { readyTicks=0;return false; }
        if(++readyTicks<3)return false;
        if(mc.player.isOnGround()) {mc.options.keyJump.setPressed(true);jumping=true;return false;}
        return jumping && rising(mc,aimDirection);
    }
}
