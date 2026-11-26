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
    private static Vec3d returnDirection;
    private static int returnReady;
    private static boolean swimmingToFortress;

    static void reset() { swimmingToFortress=false; aimDirection = null; jumping = false; readyTicks = 0; firstDirection=null; firstReady=0; returnDirection=null; returnReady=0; }

    static boolean catchWall(MinecraftClient mc) {
        if(!swimmingToFortress && mc.player.isInLava() && mc.player.getY()<35 &&
                mc.player.getX()>-98 && mc.player.getX()<-39) swimmingToFortress=true;
        if(swimmingToFortress) return swimToLaunch(mc);
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
        return landingScore(mc,start,velocity,goal,minY,maxY,Integer.MAX_VALUE);
    }

    private static double landingScore(MinecraftClient mc,Vec3d start,Vec3d velocity,Vec3d goal,int minY,int maxY,int maxFloorX) {
        Vec3d pos=start;
        for (int t=0;t<88;t++) {
            Vec3d next=pos.add(velocity);
            BlockHitResult hit=mc.world.rayTrace(new RayTraceContext(pos,next,RayTraceContext.ShapeType.COLLIDER,RayTraceContext.FluidHandling.NONE,mc.player));
            if (hit.getType()==HitResult.Type.BLOCK) {
                BlockPos floor=hit.getBlockPos();
                if (hit.getSide()!=Direction.UP || floor.getX()>maxFloorX || floor.getY()<minY || floor.getY()>maxY
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
        Vec3d base=new Vec3d(-105.50,40,148.50);
        double horizontal=Math.hypot(mc.player.getX()-base.x,mc.player.getZ()-base.z);
        if (mc.world.getBlockState(new BlockPos(base.x,39,base.z)).getMaterial().isReplaceable() || horizontal>.26) {
            if (horizontal>.22) {
                Race.aim(mc,new Vec3d(base.x,mc.player.getEyeY()-.12,base.z));
                Middle.approach(mc,base,horizontal<.7);
                mc.options.keyJump.setPressed(false);
                return false;
            }
            if (!Middle.selectBuildingBlock(mc,true)) return false;
            BlockPos top=new BlockPos(base.x,39,base.z);
            while (top.getY()>29 && mc.world.getBlockState(top).getMaterial().isReplaceable()) top=top.down();
            BlockHitResult hit=Middle.aim(mc,top,Direction.UP);
            mc.options.keyJump.setPressed(true);
            if (mc.player.getY()>=top.getY()+1.99 && hit!=null) Middle.use(mc,hit);
            return false;
        }
        if (firstDirection==null && mc.player.isOnGround()) {
            // The lower pillar and more vertical shot clear the lip above the shore.
            // This angle clears its side and lands on the broad floor at Y74.
            firstDirection=direction(-52.875,-57.25);
        }
        if(firstDirection==null || !Middle.select(mc,Items.ENDER_PEARL))return false;
        Race.aim(mc,mc.player.getCameraPosVec(1).add(firstDirection.multiply(10)));
        if(mc.player.getRotationVec(1).dotProduct(firstDirection)<.9998){firstReady=0;return false;}
        if(++firstReady<3) return false;
        if(mc.player.isOnGround()){mc.options.keyJump.setPressed(true);return false;}
        return rising(mc,firstDirection);
    }

    // The delayed third pearl has to land on the return-portal terrace after the fortress fight.
    static boolean aimReturn(MinecraftClient mc) {
        Vec3d base=new Vec3d(-30.50,57,167.45);
        Vec3d terrace=new Vec3d(-85.5,75,177.5);
        if (returnDirection==null) {
            double distance=Math.hypot(mc.player.getX()-base.x,mc.player.getZ()-base.z);
            if (distance>.21) {
                Race.aim(mc,new Vec3d(base.x,mc.player.getEyeY()-.12,base.z));
                Middle.approach(mc,base,distance<1.2);
                mc.options.keyJump.setPressed(false);
                return false;
            }
            // The takeoff floor is the top of the fortress bridge at Y57.
            // A player who fell beneath that bridge can share the same X and Z.
            BlockPos floor=new BlockPos(base.x,56,base.z);
            if (Math.abs(mc.player.getY()-57)>.15
                    || mc.world.getBlockState(floor).getCollisionShape(mc.world,floor).isEmpty())
                return false;
            if (!mc.player.isOnGround() || Math.hypot(mc.player.getVelocity().x,mc.player.getVelocity().z)>.05)
                return false;
            Vec3d start=mc.player.getPos().add(0,mc.player.getStandingEyeHeight()+.42-.10,0);
            double best=Double.POSITIVE_INFINITY;
            for(int yaw=73;yaw<=89;yaw++) for(int pitch=-49;pitch<=-34;pitch++) {
                double score=0;
                for(double lift:new double[]{.333,.42}) for(int spread:new int[]{-1,0,1}) {
                    Vec3d v=direction(yaw+spread,pitch-spread*.5).multiply(1.5).add(0,lift,0);
                    score=Math.max(score,landingScore(mc,start,v,terrace,71,78,-82));
                }
                if (score<best) {best=score;returnDirection=direction(yaw,pitch);}
            }
            // Prefer a landing on the dry part of the same high ledge when the nearby
            // terrain is loaded. The number of throws and the distance keys are identical.
            BlockPos shelf=new BlockPos(-86,74,170);
            if (!mc.world.getBlockState(shelf).getCollisionShape(mc.world,shelf).isEmpty()
                    && mc.world.getFluidState(shelf.up()).isEmpty()
                    && mc.world.getBlockState(shelf.up()).getCollisionShape(mc.world,shelf.up()).isEmpty()) {
                returnDirection=direction(86.1,-40.1);
            }
            // Client chunks can still be loading at the furthest edge during a rapid reset.
            // This fallback hit the far terrace in an observed native return from this launch cell.
            if(returnDirection==null) returnDirection=direction(83,-42);
        }
        Race.aim(mc,mc.player.getCameraPosVec(1).add(returnDirection.multiply(10)));
        if(mc.player.getRotationVec(1).dotProduct(returnDirection)<.9998) {returnReady=0;return false;}
        if(++returnReady<3)return false;
        if(mc.player.isOnGround()) {mc.options.keyJump.setPressed(true);return false;}
        return rising(mc,returnDirection);
    }

    static boolean reachedFortressAfterSwim(MinecraftClient mc) {
        if (!swimmingToFortress) return false;
        double x=mc.player.getX(), y=mc.player.getY(), z=mc.player.getZ();
        if (x< -32.30 || x> -25.10 || z<165.8 || z>168.1 || y<56.99 || y>=58.65) return false;
        // A pearl teleports the feet to the preceding projectile tick. At the bridge
        // edge the player's body can overlap solid brick while its center is over air.
        for (int floorY=56;floorY<=57;floorY++) {
            double aboveTop=y-(floorY+1);
            if (aboveTop<-.01 || aboveTop>.65) continue;
            for (int bx=(int)Math.floor(x-.295);bx<=(int)Math.floor(x+.295);bx++) {
                for (int bz=(int)Math.floor(z-.295);bz<=(int)Math.floor(z+.295);bz++) {
                    BlockPos p=new BlockPos(bx,floorY,bz);
                    if (mc.world.getBlockState(p).getBlock()==Blocks.NETHER_BRICKS
                            && mc.world.getBlockState(p.up()).getCollisionShape(mc.world,p.up()).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    static boolean usesLavaRecovery() {
        return swimmingToFortress;
    }

    private static boolean swimToLaunch(MinecraftClient mc) {
        double x=-41.50,z=167.50;
        double distance=Math.hypot(mc.player.getX()-x,mc.player.getZ()-z);
        mc.options.keyJump.setPressed(true);
        if(distance>.32) {
            Vec3d target=new Vec3d(x,mc.player.getEyeY(),z);
            Race.aim(mc,target);
            Middle.approach(mc,target,false);
            mc.options.keyJump.setPressed(true);
            mc.options.keySprint.setPressed(true);
            Middle.status="swimming across the lava to the fortress throw";
            return false;
        }
        Middle.status="settling at the fortress throw on the lava surface";
        if(!Middle.select(mc,Items.ENDER_PEARL))return false;
        return Math.hypot(mc.player.getVelocity().x,mc.player.getVelocity().z)<.06;
    }

    static boolean aimSecond(MinecraftClient mc) {
        if(swimmingToFortress){
            if(!swimToLaunch(mc))return false;
            // The shorter, steeper arc clears the protruding west face at (-32,56,167).
            Vec3d dir=direction(-91.0,-77.75);
            Race.aim(mc,mc.player.getCameraPosVec(1).add(dir.multiply(10)));
            net.minecraft.server.network.ServerPlayerEntity player=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
            if(player==null || player.getRotationVec(1).dotProduct(dir)<.9998 ||
                    player.getY()<31.55 || player.getY()>31.96 ||
                    player.getVelocity().y<-.088 || player.getVelocity().y>-.034) return false;
            Middle.status="throwing from the lava surface to the fortress bridge";
            return true;
        }
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
