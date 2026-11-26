package dev.aiarena.week113;

import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Walk the high eastern shore, then use the overworld Enderman pearl if one was obtained. */
final class EastBastionApproach {
    private static final List<BlockPos> first=Middle.cells("east-first.csv");
    private static final List<BlockPos> escape=Middle.cells("east-escape.csv");
    private static int phase,index,aimTicks,flight;
    private static boolean offeredGold, lowFallback;
    private static int highPoint, nextPad, slowTicks;
    private static double checkX, checkZ;
    private static final double[][] HIGH = {
        {32.0,.15},{29.25,6.24},{27.12,11.45},{25.50,16.72},{23.84,22.00},
        {21.73,27.56},{20.21,32.88},{19.27,37.01},{18.51,39.73},{17.46,42.15},
        {16.34,44.83},{14.98,47.36},{13.24,49.58},{11.31,51.46},{10.00,55.05},
        {6.67,55.10},{4.23,55.46},{1.50,56.50}
    };
    private static final BlockPos[] PADS={new BlockPos(17,40,42),new BlockPos(16,41,45),new BlockPos(13,41,48)};
    private static boolean leftPortal, selectedExit;
    private static double exitX,exitZ;
    static String status="";

    static void reset(){phase=0;index=0;aimTicks=0;flight=0;offeredGold=false;leftPortal=false;selectedExit=false;lowFallback=false;highPoint=nextPad=slowTicks=0;checkX=checkZ=0;}
    static boolean shouldHandle(MinecraftClient m){
        if(phase==0){if(m.currentScreen!=null || m.player.getY()>57)return false;phase=m.player.getX()>20?1:5;}
        return phase<5;
    }

    private static boolean leavePortal(MinecraftClient m) {
        if(leftPortal)return true;
        if(!selectedExit){
            BlockPos feet=m.player.getBlockPos(),closest=null;double best=25;
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){
                BlockPos point=feet.add(x,0,z);
                if(m.world.getBlockState(point).getBlock()!=net.minecraft.block.Blocks.NETHER_PORTAL)continue;
                double dist=m.player.squaredDistanceTo(point.getX()+.5,m.player.getY(),point.getZ()+.5);
                if(dist<best){best=dist;closest=point;}
            }
            if(closest==null){leftPortal=true;return true;}
            boolean alongX=m.world.getBlockState(closest).get(net.minecraft.block.NetherPortalBlock.AXIS)==net.minecraft.util.math.Direction.Axis.X;
            exitX=alongX?closest.getX()+.5:closest.getX()-1.2;
            exitZ=alongX?closest.getZ()+2.2:closest.getZ()+.5;
            selectedExit=true;
        }
        if(Math.hypot(m.player.getX()-exitX,m.player.getZ()-exitZ)<.40){
            leftPortal=true;index=3;return true;
        }
        Race.aim(m,new Vec3d(exitX,m.player.getEyeY(),exitZ));
        BastionApproach.move(m,exitX,exitZ,false,false);
        m.options.keyJump.setPressed(m.player.horizontalCollision);
        status="leaving the eastern portal on its open face";
        return false;
    }
    private static boolean walk(MinecraftClient m,List<BlockPos> cells) {
        // A sprint through a diagonal turn can bypass a cell center even though
        // the player is already standing on a later safe foot cell.
        for(int i=Math.min(index+6,cells.size()-1);i>index;i--){
            BlockPos p=cells.get(i);
            if(!BastionApproach.open(m,p.down()) && Math.abs(m.player.getY()-p.getY())<1.2
                    && Math.hypot(m.player.getX()-p.getX()-.5,m.player.getZ()-p.getZ()-.5)<.75){
                index=i;break;
            }
        }
        while(index<cells.size()){
            BlockPos p=cells.get(index);
            double distance=Math.hypot(m.player.getX()-p.getX()-.5,m.player.getZ()-p.getZ()-.5);
            if(distance>.64 || Math.abs(m.player.getY()-p.getY())>1.15 || BastionApproach.open(m,p.down()))break;
            index++;
        }
        if(index>=cells.size())return true;
        BlockPos p=cells.get(index),prev=cells.get(Math.max(0,index-1));
        if(BastionApproach.open(m,p.down())){
            status="bridging the eastern shore "+index+"/"+cells.size();
            BastionApproach.bridge(m,prev,p);
            return false;
        }
        BlockPos aim=p;
        for(int i=Math.min(index+6,cells.size()-1);i>index;i--){
            BlockPos target=cells.get(i);
            if(Math.abs(target.getY()-m.player.getY())>1.2 || !BastionApproach.corridor(m,target,false))continue;
            boolean clear=true;
            for(int j=index;j<=i;j++)if(BastionApproach.open(m,cells.get(j).down())){clear=false;break;}
            if(!clear)continue;
            aim=target;
            // A player can physically pass an intermediate grid cell on a diagonal sprint.
            if(Math.hypot(m.player.getX()-target.getX()-.5,m.player.getZ()-target.getZ()-.5)<1.12)index=i;
            break;
        }
        double x=aim.getX()+.5,z=aim.getZ()+.5;
        boolean gap=index+1<cells.size() && BastionApproach.open(m,cells.get(index+1).down());
        double dy=p.getY()-m.player.getY(),d=Math.hypot(x-m.player.getX(),z-m.player.getZ());
        Race.aim(m,new Vec3d(x,m.player.getEyeY()-.12,z));
        BastionApproach.move(m,x,z,gap && d<.9,!gap);
        m.options.keyJump.setPressed(!gap && (dy>.45 || m.player.horizontalCollision || (d>3.7 && BastionApproach.corridor(m,aim,true))));
        status="running along the eastern Nether shore "+index+"/"+cells.size();
        return false;
    }

    // The registered soul sand anchors are solid; clicking their genuine top faces
    // adds three full-height, ordinary sprint-jump landing surfaces.
    private static boolean aimAtPad(MinecraftClient m,BlockPos pad) {
        if(!Middle.selectBuildingBlock(m,false))return false;
        if(m.currentScreen!=null || m.getServer()==null)return false;
        net.minecraft.server.network.ServerPlayerEntity self=m.getServer().getPlayerManager().getPlayer(m.player.getUuid());
        if(self==null || self.getMainHandStack().getItem()!=m.player.getMainHandStack().getItem())return false;
        BlockPos base=pad.down();
        net.minecraft.util.shape.VoxelShape shape=m.world.getBlockState(base).getOutlineShape(m.world,base);
        if(shape.isEmpty())return false;
        for(double x:new double[]{.50,.17,.83})for(double z:new double[]{.50,.17,.83}) {
            Vec3d point=new Vec3d(base.getX()+x,base.getY()+shape.getBoundingBox().maxY-.002,base.getZ()+z);
            net.minecraft.util.hit.BlockHitResult planned=BastionApproach.line(m,point);
            if(planned==null || !planned.getBlockPos().equals(base)
                    || planned.getSide()!=net.minecraft.util.math.Direction.UP)continue;
            Race.aim(m,point);
            m.gameRenderer.updateTargetedEntity(1);
            if(m.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult) {
                net.minecraft.util.hit.BlockHitResult h=(net.minecraft.util.hit.BlockHitResult)m.crosshairTarget;
                if(h.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK && h.getBlockPos().equals(base)
                        && h.getSide()==net.minecraft.util.math.Direction.UP
                        && m.interactionManager.interactBlock(m.player,m.world,Hand.MAIN_HAND,h).isAccepted())
                    m.player.swingHand(Hand.MAIN_HAND);
            }
            return true;
        }
        return false;
    }

    private static boolean highShore(MinecraftClient m) {
        if(lowFallback)return walk(m,first);
        double x=m.player.getX(), z=m.player.getZ();
        // The portal's obsidian posts run east-west. Leave through the open south
        // face before trying to sprint-jump beneath the frame's upper crossbar.
        if(z<-.90 && x>30.0) {
            Race.aim(m,new Vec3d(32.85,m.player.getEyeY(),.45));
            BastionApproach.move(m,32.85,.45,false,true);
            m.options.keyJump.setPressed(false);
            status="leaving the eastern Nether portal through its south face";
            return false;
        }
        if(++slowTicks>=40) {
            boolean stalled=highPoint>1 && Math.hypot(x-checkX,z-checkZ)<.65;
            checkX=x;checkZ=z;slowTicks=0;
            if(stalled || m.player.getY()<38.5) {
                lowFallback=true;
                index=nearest(first,m,0,first.size()-1);
                return walk(m,first);
            }
        }
        while(highPoint<HIGH.length-1) {
            double[] here=HIGH[highPoint],next=HIGH[highPoint+1];
            double dx=x-here[0],dz=z-here[1],sx=next[0]-here[0],sz=next[1]-here[1];
            if(Math.hypot(dx,dz)>1.05 && dx*sx+dz*sz<0)break;
            highPoint++;
        }
        double[] goal=HIGH[highPoint];
        if(highPoint==HIGH.length-1 && Math.hypot(x-goal[0],z-goal[1])<.5
                && Math.abs(m.player.getY()-41)<1.15)return true;
        while(nextPad<PADS.length && (!BastionApproach.open(m,PADS[nextPad])
                || z>PADS[nextPad].getZ()+1.1))nextPad++;
        boolean placing=false;
        if(nextPad<PADS.length) {
            BlockPos pad=PADS[nextPad];
            if(Math.hypot(x-pad.getX()-.5,z-pad.getZ()-.5)<4.4)placing=aimAtPad(m,pad);
        }
        if(!placing)Race.aim(m,new Vec3d(goal[0],m.player.getEyeY()-.1,goal[1]));
        BastionApproach.move(m,goal[0],goal[1],false,true);
        boolean nearLaunch=Math.hypot(x-1.5,z-56.5)<3.2;
        m.options.keyJump.setPressed(m.player.isOnGround() && !nearLaunch);
        status="sprint-jumping along the upper eastern shore "+highPoint+"/"+HIGH.length;
        return false;
    }

    static int tick(MinecraftClient m,List<BlockPos> low){
        if(phase==1){
            if(!leavePortal(m))return -1;
            if(!highShore(m))return -1;
            index=0;phase=m.player.inventory.count(Items.ENDER_PEARL)>0?2:4;
        }
        if(phase==2){
            final double bx=1.5,bz=56.5;
            double dist=Math.hypot(m.player.getX()-bx,m.player.getZ()-bz);
            if(dist>.18){
                Race.aim(m,new Vec3d(bx,m.player.getEyeY()-.1,bz));
                BastionApproach.move(m,bx,bz,dist<1.0,false);
                status="centering for the cave Enderman pearl";
                return -1;
            }
            if(!Middle.select(m,Items.ENDER_PEARL))return -1;
            double yaw=Math.toRadians(56),pitch=Math.toRadians(-33);
            Vec3d dir=new Vec3d(-Math.sin(yaw)*Math.cos(pitch),-Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch));
            Race.aim(m,m.player.getCameraPosVec(1).add(dir.multiply(10)));
            status="throwing the bonus pearl across the bastion approach";
            if(m.player.getRotationVec(1).dotProduct(dir)<.9998 || Math.hypot(m.player.getVelocity().x,m.player.getVelocity().z)>.045){aimTicks=0;return -1;}
            if(++aimTicks<4)return -1;
            if(m.player.isOnGround()){m.options.keyJump.setPressed(true);return -1;}
            if(m.player.getVelocity().y<.32)return -1;
            if(m.interactionManager.interactItem(m.player,m.world,Hand.MAIN_HAND).isAccepted()){
                m.player.swingHand(Hand.MAIN_HAND);phase=3;flight=0;index=0;
            }
            return -1;
        }
        if(phase==3){
            status="waiting for the cave Enderman pearl to land";
            if(m.player.getX() < -35 && m.player.isOnGround()){
                int pos=nearest(low,m,96,151);phase=5;return pos;
            }
            if(m.player.getX()>-12){
                walk(m,escape);
                for(net.minecraft.entity.Entity e:m.world.getEntities()){
                    if(!(e instanceof net.minecraft.entity.mob.PiglinEntity) || !e.isAlive()
                            || e.squaredDistanceTo(m.player)>12 || !m.player.canSee(e))continue;
                    net.minecraft.entity.mob.PiglinEntity pig=(net.minecraft.entity.mob.PiglinEntity)e;
                    if(pig.isBaby() || pig.getActivity()!=net.minecraft.entity.mob.PiglinEntity.Activity.ATTACKING_WITH_MELEE_WEAPON)continue;
                    Race.aim(m,pig.getBoundingBox().getCenter());
                    m.gameRenderer.updateTargetedEntity(1);
                    boolean aimed=m.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult
                            && ((net.minecraft.util.hit.EntityHitResult)m.crosshairTarget).getEntity()==pig;
                    if(!offeredGold && m.player.inventory.count(Items.GOLD_INGOT)>0){
                        if(Middle.select(m,Items.GOLD_INGOT) && aimed &&
                              m.interactionManager.interactEntity(m.player,pig,Hand.MAIN_HAND).isAccepted())offeredGold=true;
                    } else if(m.player.getOffHandStack().getItem()==Items.SHIELD) {
                        if(!m.player.isUsingItem())m.interactionManager.interactItem(m.player,m.world,Hand.OFF_HAND);
                        m.options.keyUse.setPressed(true);
                    } else if(Middle.select(m,Items.DIAMOND_SWORD) && aimed && m.player.getAttackCooldownProgress(.5f)>.9f) {
                        m.interactionManager.attackEntity(m.player,pig);m.player.swingHand(Hand.MAIN_HAND);
                    }
                    if(index<escape.size()){
                        BlockPos destination=escape.get(index);
                        BastionApproach.move(m,destination.getX()+.5,destination.getZ()+.5,false,false);
                    }
                    break;
                }
                status="moving to cover during the bonus pearl flight";
            }
            if(++flight>150 && m.player.getX()>-8){phase=4;index=0;}
            return -1;
        }
        if(!walk(m,escape))return -1;
        phase=5;return nearest(low,m,70,89);
    }

    private static int nearest(List<BlockPos> path,MinecraftClient m,int start,int end){
        int best=start;double min=Double.POSITIVE_INFINITY;
        for(int i=start;i<=end;i++){
            BlockPos p=path.get(i);
            double d=m.player.squaredDistanceTo(p.getX()+.5,p.getY(),p.getZ()+.5);
            if(d<min && !BastionApproach.open(m,p.down())){min=d;best=i;}
        }
        return best;
    }
    private EastBastionApproach(){}
}
