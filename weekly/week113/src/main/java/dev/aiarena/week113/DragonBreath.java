package dev.aiarena.week113;

import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Keep a returning rider off a dragon-breath cloud while the boat is still above. */
final class DragonBreath {
    static String status;
    private DragonBreath() {}

    private static double clearance(MinecraftClient mc, List<Entity> nearby, double x, double z) {
        double result = 100.0;
        for (Entity e : nearby) {
            if (!(e instanceof AreaEffectCloudEntity) || !e.isAlive()) continue;
            AreaEffectCloudEntity cloud = (AreaEffectCloudEntity)e;
            if (cloud.getParticleType().getType() != ParticleTypes.DRAGON_BREATH
                    || Math.abs(mc.player.getY() - cloud.getY()) > 3.75) continue;
            result = Math.min(result, Math.hypot(x - e.getX(), z - e.getZ()) - cloud.getRadius());
        }
        return result;
    }

    private static int groundHeight(MinecraftClient mc, double x, double z, int previous) {
        for (int y = previous + 1; y >= previous - 1; y--) {
            boolean stable = true;
            for (double dx : new double[] {-.29, .29}) for (double dz : new double[] {-.29, .29}) {
                BlockPos foot = new BlockPos(x + dx, y, z + dz);
                BlockState floor = mc.world.getBlockState(foot.down());
                if (!floor.isFullCube(mc.world, foot.down()) || !mc.world.getFluidState(foot).isEmpty()
                        || !BastionApproach.open(mc,foot) || !BastionApproach.open(mc,foot.up())) stable=false;
            }
            if(stable) return y;
        }
        return Integer.MIN_VALUE;
    }

    static boolean avoid(MinecraftClient mc, int returnSide) {
        double x=mc.player.getX(), z=mc.player.getZ();
        if(mc.player.getY()>72) return false;
        List<Entity> nearby=mc.world.getEntities(mc.player,mc.player.getBoundingBox().expand(15,5,15));
        double here=clearance(mc,nearby,x,z);
        if(here>13) return false;
        double tx=5.5, tz=returnSide==0?(z<.5?-.5:1.5):returnSide+.5;
        double goalDistance=Math.hypot(tx-x,tz-z), goal=clearance(mc,nearby,tx,tz);
        double closest=here;
        for(double step=.5; step<=Math.min(9,goalDistance); step+=.5)
            closest=Math.min(closest,clearance(mc,nearby,x+(tx-x)*step/goalDistance,z+(tz-z)*step/goalDistance));
        if (here>=1.3 && goal>=1 && closest>=.75) return false;
        Race.stop(mc);
        if (here>=1.45 && goal<1) {
            status="waiting clear of dragon breath beneath the boat";
            return true;
        }
        int surface=(int)Math.floor(mc.player.getY()+.04);
        if(!mc.player.isOnGround()) {
            for(int i=0;i<4;i++) {
                BlockPos p=new BlockPos(x,surface-1,z);
                if(!BastionApproach.open(mc,p))break;
                surface--;
            }
        }
        double best=-1e5, bestX=x, bestZ=z;
        for(int i=0;i<24;i++) {
            double angle=i*Math.PI/12, dx=Math.cos(angle), dz=Math.sin(angle), minimum=100;
            int previous=surface;
            boolean safe=true;
            for(double step=.39;step<=2.8;step+=.39) {
                double nx=x+dx*step,nz=z+dz*step;
                previous=groundHeight(mc,nx,nz,previous);
                if(previous==Integer.MIN_VALUE){safe=false;break;}
                minimum=Math.min(minimum,clearance(mc,nearby,nx,nz));
            }
            if(!safe || minimum<Math.min(here,.8)-.12)continue;
            double nx=x+dx*2.7,nz=z+dz*2.7;
            double end=clearance(mc,nearby,nx,nz);
            double score=here<1.3 ? 3*end+minimum : 2*Math.min(end,2.5)+Math.min(minimum,1.5)-Math.hypot(nx-tx,nz-tz);
            if(score>best){best=score;bestX=nx;bestZ=nz;}
        }
        status="moving to supported ground outside dragon breath";
        if(best> -1e5) {
            Race.aim(mc,new Vec3d(bestX,mc.player.getEyeY()-.85,bestZ));
            BastionApproach.move(mc,bestX,bestZ,false,true);
            mc.options.keyJump.setPressed(mc.player.isOnGround() && mc.player.horizontalCollision);
        }
        return true;
    }
}
