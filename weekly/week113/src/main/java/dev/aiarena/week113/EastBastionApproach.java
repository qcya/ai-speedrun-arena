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
    private static boolean offeredGold;
    static String status="";

    static void reset(){phase=0;index=0;aimTicks=0;flight=0;offeredGold=false;}
    static boolean shouldHandle(MinecraftClient m){
        if(phase==0)phase=m.player.getX()>20?1:5;
        return phase<5;
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

    static int tick(MinecraftClient m,List<BlockPos> low){
        if(phase==1){
            if(!walk(m,first))return -1;
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
