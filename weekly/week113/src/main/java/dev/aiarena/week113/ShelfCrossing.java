package dev.aiarena.week113;

import java.nio.file.Files;
import java.util.*;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.util.Hand;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;

final class ShelfCrossing {
    private static final BlockPos LIP=new BlockPos(-57,78,185), DOCK=new BlockPos(-55,57,186);
    private static final Vec3d RETURN=new Vec3d(-30.50,57,167.45);
    private static boolean entered, joined, boardedHigh;
    private static int phase, firstStat, point, stationary, captures;
    private static Vec3d previous;
    private static List<BlockPos> upper=Collections.emptyList();
    private static int used(MinecraftClient m){
        ServerPlayerEntity self=m.getServer().getPlayerManager().getPlayer(m.player.getUuid());
        return self==null?-1:self.getStatHandler().getStat(Stats.USED,Items.ENDER_PEARL);
    }
    static void reset(){entered=joined=boardedHigh=false;phase=point=stationary=captures=0;previous=null;firstStat=-1;upper=Collections.emptyList();}
    static boolean canUseEntrance(MinecraftClient m){
        if(m.getServer()==null || !Files.isRegularFile(m.getServer().getSavePath(WorldSavePath.ROOT).resolve("astra-coast-one"))
                || m.player.inventory.count(Items.ENDER_PEARL)==0) return false;
        firstStat=used(m);return firstStat==0;
    }
    static void entranceUsed(){entered=true;}
    static boolean routeActive(){return entered;}
    static boolean rejoined(){return entered && joined;}
    static boolean canThrow(MinecraftClient m,String action){
        return !action.equals("pearl1") && !action.equals("pearl3") || used(m)==firstStat+(action.equals("pearl1")?1:2);
    }
    private static boolean foot(MinecraftClient m,BlockPos p){
        return m.world.getBlockState(p.down()).isSideSolidFullSquare(m.world,p.down(),Direction.UP)
                && m.world.getBlockState(p).isAir() && m.world.getBlockState(p.up()).isAir();
    }
    private static boolean edge(MinecraftClient m,BlockPos a,BlockPos b){
        if(!foot(m,b) || b.getY()>a.getY() && !m.world.getBlockState(a.up(2)).isAir())return false;
        for(int y=b.getY();y<=a.getY()+1;y++)if(!m.world.getBlockState(new BlockPos(b.getX(),y,b.getZ())).isAir())return false;
        if(a.getX()!=b.getX() && a.getZ()!=b.getZ()) for(int y=Math.max(a.getY(),b.getY());y<=Math.max(a.getY(),b.getY())+1;y++)
            if(!m.world.getBlockState(new BlockPos(a.getX(),y,b.getZ())).isAir() || !m.world.getBlockState(new BlockPos(b.getX(),y,a.getZ())).isAir())return false;
        return true;
    }
    private static boolean wetExit(MinecraftClient m,BlockPos a,BlockPos b){
        double sx=m.player.getX(),sz=m.player.getZ(),dx=b.getX()+.5-sx,dz=b.getZ()+.5-sz;
        int steps=(int)Math.ceil(Math.hypot(dx,dz)*4);
        for(int i=0;i<=steps;i++)for(double ex:new double[]{-.28,.28})for(double ez:new double[]{-.28,.28}){
            BlockPos p=new BlockPos(sx+dx*i/steps+ex,a.getY(),sz+dz*i/steps+ez);
            if(!m.world.getBlockState(p.down()).isSideSolidFullSquare(m.world,p.down(),Direction.UP)
                    || !m.world.getBlockState(p).getCollisionShape(m.world,p).isEmpty()
                    || !m.world.getBlockState(p.up()).getCollisionShape(m.world,p.up()).isEmpty())return false;
        }
        return true;
    }
    private static boolean leaveLava(MinecraftClient m){
        BlockPos a=m.player.getBlockPos();
        if(!m.player.isInLava() && !m.world.getBlockState(a).isOf(Blocks.LAVA))return false;
        BlockPos bank=null;double best=Double.POSITIVE_INFINITY;
        for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++){
            BlockPos b=a.add(dx,0,dz);
            if(!foot(m,b) || !wetExit(m,a,b))continue;
            double score=off(m,b.getX()+.5,b.getZ()+.5)+.18*Math.hypot(b.getX()-LIP.getX(),b.getZ()-LIP.getZ());
            if(score<best){best=score;bank=b;}
        }
        boolean using=Middle.refreshFireResistance(m);
        if(bank!=null && m.currentScreen==null){
            go(m,bank.getX()+.5,bank.getZ()+.5,true);
            m.options.keyJump.setPressed(true);
        }
        if(!using)Middle.status="leaving the lava for a clear terrace footing";
        return true;
    }
    private static List<BlockPos> upperPath(MinecraftClient m){
        BlockPos init=m.player.getBlockPos();
        if(!foot(m,init)) {
            BlockPos found=null;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++){
                BlockPos p=init.add(dx,dy,dz);if(foot(m,p) && (found==null || m.player.squaredDistanceTo(Vec3d.ofCenter(p))<m.player.squaredDistanceTo(Vec3d.ofCenter(found))))found=p;
            }
            if(found==null)return Collections.emptyList();init=found;
        }
        Map<BlockPos,BlockPos> prev=new HashMap<>();ArrayDeque<BlockPos> q=new ArrayDeque<>();prev.put(init,init);q.add(init);
        int[] axis={1,0,-1};
        while(!q.isEmpty()){
            BlockPos a=q.remove();
            if(a.equals(LIP)){
                List<BlockPos> seq=new ArrayList<>();for(BlockPos p=a;!p.equals(init);p=prev.get(p))seq.add(p);
                Collections.reverse(seq);return seq;
            }
            for(int dx:axis)for(int dz:axis)if(dx!=0 || dz!=0)for(int dy:new int[]{0,1,-1,-2}){
                BlockPos p=a.add(dx,dy,dz);
                if(p.getX()< -93 || p.getX()>-53 || p.getZ()<157 || p.getZ()>191 || p.getY()<69 || p.getY()>83 || prev.containsKey(p) || !edge(m,a,p))continue;
                prev.put(p,a);q.add(p);
            }
        }
        return Collections.emptyList();
    }
    private static void go(MinecraftClient m,double x,double z,boolean sprint){
        Race.aim(m,new Vec3d(x,m.player.getEyeY()-.08,z));
        BastionApproach.move(m,x,z,false,sprint);
        m.options.keyJump.setPressed(m.player.isOnGround() && m.player.horizontalCollision);
    }
    private static double off(MinecraftClient m,double x,double z){return Math.hypot(m.player.getX()-x,m.player.getZ()-z);}
    private static BoatEntity boat(MinecraftClient m){
        BoatEntity best=null;
        for(Entity e:m.world.getEntities())if(e instanceof BoatEntity && e.isAlive() && e.squaredDistanceTo(Vec3d.ofCenter(DOCK))<16
                && (best==null || e.squaredDistanceTo(m.player)<best.squaredDistanceTo(m.player)))best=(BoatEntity)e;
        return best;
    }
    private static void face(MinecraftClient m,Vec3d p){Race.aim(m,p);m.gameRenderer.updateTargetedEntity(1);}
    private static void descendInBoat(MinecraftClient m){
        // Park the hull on the protruding netherrack from the step above it. A full boat is
        // 1.375 blocks wide, wider than a player's feet. Stay aboard through the actual landing.
        Vec3d park=new Vec3d(-55.14,77,185.85);
        if(m.player.getVehicle() instanceof BoatEntity){
            boardedHigh=true;
            BoatEntity b=(BoatEntity)m.player.getVehicle();
            if(b.getY()<60 && b.isOnGround()){phase=2;return;}
            Middle.status="riding the boat down to the southern fortress bridge";
            if(b.getY()>76.9 && b.getZ()<186.95){
                float diff=MathHelper.wrapDegrees(-b.yaw);
                m.options.keyLeft.setPressed(diff< -3);m.options.keyRight.setPressed(diff>3);
                m.options.keyForward.setPressed(Math.abs(diff)<12);
            }
            return;
        }
        if(boardedHigh){if(m.player.isOnGround() && m.player.getY()<60)phase=3;return;}
        BoatEntity nearby=null;
        for(Entity e:m.world.getEntities())if(e instanceof BoatEntity && e.isAlive() && e.squaredDistanceTo(park)<4){nearby=(BoatEntity)e;break;}
        if(nearby!=null){
            Middle.status="boarding the boat on the upper terrace before the drop";
            face(m,nearby.getBoundingBox().getCenter());
            if(m.crosshairTarget instanceof EntityHitResult && ((EntityHitResult)m.crosshairTarget).getEntity()==nearby)
                m.interactionManager.interactEntity(m.player,nearby,Hand.MAIN_HAND);
            return;
        }
        Middle.status="placing the boat on the dry terrace edge";
        if(!Hotbar.select(m,Items.OAK_BOAT,5))return;
        face(m,park);
        if(!(m.crosshairTarget instanceof BlockHitResult))return;
        BlockHitResult hit=(BlockHitResult)m.crosshairTarget;
        if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(new BlockPos(-56,76,185))
                || hit.getSide()!=Direction.UP || hit.getPos().squaredDistanceTo(park)>.016)return;
        ServerPlayerEntity self=m.getServer().getPlayerManager().getPlayer(m.player.getUuid());
        if(self!=null && self.getMainHandStack().getItem()==Items.OAK_BOAT)
            m.interactionManager.interactItem(m.player,m.world,Hand.MAIN_HAND);
    }
    private static boolean recover(MinecraftClient m){
        if(m.player.inventory.count(Items.OAK_BOAT)>0)return true;
        for(Entity e:m.world.getEntities())if(e instanceof ItemEntity && e.isAlive() && ((ItemEntity)e).getStack().getItem()==Items.OAK_BOAT && e.squaredDistanceTo(m.player)<60){
            go(m,e.getX(),e.getZ(),false);return false;
        }
        BoatEntity b=boat(m);
        if(b==null)return ++captures>12;
        if(b.squaredDistanceTo(m.player)>7){go(m,b.getX(),b.getZ(),false);return false;}
        if(!Hotbar.select(m,Items.DIAMOND_SWORD,7))return false;
        face(m,b.getBoundingBox().getCenter());
        if(m.crosshairTarget instanceof EntityHitResult && ((EntityHitResult)m.crosshairTarget).getEntity()==b && m.player.getAttackCooldownProgress(.5f)>.95){
            m.interactionManager.attackEntity(m.player,b);m.player.swingHand(Hand.MAIN_HAND);
        }
        return false;
    }
    private static boolean pave(MinecraftClient m){
        int x=-21;
        for(int z=184;z>=180;z--){
            BlockPos target=new BlockPos(x,58,z);
            if(foot(m,target))continue;
            BlockPos previous=target.south();
            if(off(m,previous.getX()+.5,previous.getZ()+.5)>1.05 || m.player.getY()<57.8){
                go(m,previous.getX()+.5,previous.getZ()+.5,false);
                m.options.keyJump.setPressed(m.player.isOnGround() && m.player.getY()<57.8);
            }else BastionApproach.bridge(m,previous,target);
            return false;
        }
        return true;
    }
    static boolean travel(MinecraftClient m){
        Race.stop(m);m.options.keyAttack.setPressed(false);m.options.keyUse.setPressed(false);
        if(phase==0){
            Middle.status="following the high terrace to the brick bridge drop";
            if(m.player.getY()<69)return false;
            if(leaveLava(m))return false;
            if(m.player.isUsingItem() && Middle.refreshFireResistance(m))return false;
            if(upper.isEmpty() || stationary>24){
                if(!m.player.isOnGround())return false;
                upper=upperPath(m);point=stationary=0;
            }
            if(previous!=null && m.player.getPos().squaredDistanceTo(previous)<.0008)stationary++;else stationary=0;
            previous=m.player.getPos();
            while(point<upper.size()){
                BlockPos p=upper.get(point);double x=p.getX()+.5,z=p.getZ()+.5;
                if(off(m,x,z)<.43 && Math.abs(m.player.getY()-p.getY())<.7 && m.player.isOnGround()){point++;continue;}
                go(m,x,z,false);
                if(p.getY()>m.player.getY()+.45 && m.player.isOnGround())m.options.keyJump.setPressed(true);
                return false;
            }
            if(off(m,-56.50,185.50)>.52 || !m.player.isOnGround() || m.player.getY()<77.8)return false;
            if(!Hotbar.select(m,Items.OAK_BOAT,5))return false;
            if(!foot(m,DOCK) || !foot(m,DOCK.south()) || !foot(m,DOCK.east()))return false;
            phase=1;
        }
        if(phase==1){descendInBoat(m);return false;}
        if(phase==2){
            Middle.status="dismounting the boat on the south fortress bridge";
            if(m.player.hasVehicle()){m.options.keySneak.setPressed(true);return false;}
            phase=3;
        }
        if(phase==3){
            Middle.status="recovering the boat on the south fortress bridge";
            if(!recover(m))return false;phase=4;
        }
        if(phase==4){
            Middle.status="following the southern nether bricks to the missing span";
            if(off(m,-20.5,186.55)>.65){go(m,-20.5,186.55,true);return false;}
            phase=5;
        }
        if(phase==5){
            Middle.status="laying five solid steps across the missing fortress span";
            if(!pave(m))return false;
            if(m.player.getZ()>178.68){go(m,-20.50,178.40,false);return false;}
            phase=6;
        }
        if(phase==6){
            Middle.status="following the northern fortress bridge to the original return throw";
            if(m.player.getZ()>168.65){go(m,-21.5,168.35,true);return false;}
            phase=7;
        }
        if(phase==7){
            Middle.status="following the clear central lane to the original return throw";
            if(m.player.getX()>-28.45){go(m,-28.65,167.82,true);return false;}
            if(off(m,RETURN.x,RETURN.z)>.21){go(m,RETURN.x,RETURN.z,false);return false;}
            if(m.player.isOnGround()){joined=true;return true;}
        }
        return joined;
    }
    private ShelfCrossing(){}
}
