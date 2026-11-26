package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

final class WestBankPearl {
    private static final double TARGET_X=-72.9, TARGET_Z=97.1;
    private static final double[][] RIDGE={
        {-74.00,97.65},{-77.95,97.12},{-82.15,96.62},{-86.50,96.51},
        {-90.75,96.44},{-94.85,97.36},{-98.69,99.04},{-100.45,99.68},
        {-102.92,100.31}
    };
    private static int ridgePoint, walking;
    private static boolean attempted, jumped;
    static String status;

    static void reset() {attempted=jumped=false;ridgePoint=walking=0;}

    // Solve the vertical crossing of the surveyed dry shelf, retaining this runner's actual inertia.
    private static double[] direction(double x,double y,double z,double vx,double vy,double vz) {
        double best=Double.POSITIVE_INFINITY;
        double[] aim=null;
        for(int degrees=29;degrees<=37;degrees++) {
            double pitch=Math.toRadians(degrees);
            double previous=y,height=y,vertical=1.5*Math.sin(pitch)+vy,power=1,travel=0;
            for(int t=0;t<96;t++) {
                previous=height;height+=vertical;
                if(height<=38 && vertical<0) {
                    travel+=power*(previous-38)/(previous-height);
                    break;
                }
                travel+=power;power*=.99;vertical=vertical*.99-.03;
            }
            double wantedX=(TARGET_X-x)/travel-vx,wantedZ=(TARGET_Z-z)/travel-vz;
            double yaw=Math.atan2(-wantedX,wantedZ),scale=1.5*Math.cos(pitch);
            double dx=-Math.sin(yaw)*scale,dz=Math.cos(yaw)*scale;
            double error=Math.hypot(x+(dx+vx)*travel-TARGET_X,z+(dz+vz)*travel-TARGET_Z);
            if(error<best) {best=error;aim=new double[]{dx,1.5*Math.sin(pitch),dz,error};}
        }
        return aim;
    }

    /** -1 follows the existing approach, 0 continues this run-up, 1 is a real throw. */
    static int launch(MinecraftClient m) {
        double x=m.player.getX(),z=m.player.getZ();
        if(attempted || m.player.inventory.count(Items.ENDER_PEARL)==0 || x>5.2
                || z<53.3 || z>57.9 || m.player.getY()<40 || m.player.getY()>44.8)return -1;
        if(x<.30 || (jumped && m.player.getVelocity().y<.20)) {attempted=true;return -1;}
        Vec3d velocity=m.player.getVelocity(),eye=m.player.getCameraPosVec(1);
        boolean airborne=jumped && !m.player.isOnGround() && velocity.y>.25;
        double[] dir=direction(x,airborne?eye.y-.1:42.94,z,velocity.x,airborne?velocity.y:.3332,velocity.z);
        Vec3d heading=new Vec3d(dir[0],dir[1],dir[2]).normalize();
        Race.aim(m,eye.add(heading.multiply(10)));
        BastionApproach.move(m,-.30,57.10,false,true);
        m.options.keyJump.setPressed(false);
        status="running into the west-bank pearl";
        if(!Middle.select(m,Items.ENDER_PEARL) || m.currentScreen!=null)return 0;
        ServerPlayerEntity self=m.getServer().getPlayerManager().getPlayer(m.player.getUuid());
        if(self==null || self.getMainHandStack().getItem()!=Items.ENDER_PEARL)return 0;
        if(!jumped && x<2.32 && m.player.isOnGround() && dir[3]<2.45
                && velocity.x<-.16 && m.player.getRotationVec(1).dotProduct(heading)>.999){
            m.options.keyJump.setPressed(true);jumped=true;return 0;
        }
        if(!airborne || dir[3]>2.45 || m.player.getRotationVec(1).dotProduct(heading)<.999) return 0;
        if(m.interactionManager.interactItem(m.player,m.world,Hand.MAIN_HAND).isAccepted()){
            m.player.swingHand(Hand.MAIN_HAND);attempted=true;
            status="throwing the cave pearl over the lava stream";return 1;
        }
        return 0;
    }

    static boolean landed(MinecraftClient m) {
        return attempted && m.player.getX()<=-68.9 && m.player.getX()>=-80.5
                && m.player.getZ()>=93.3 && m.player.getZ()<=100.4
                && m.player.getY()>=37 && m.player.getY()<42 && m.player.isOnGround()
                && !m.player.isInLava();
    }

    /** Sprint along Ormo's dry track. The caller picks up its native chest approach at the end. */
    static boolean follow(MinecraftClient m) {
        double x=m.player.getX(),z=m.player.getZ();
        if(++walking>155 || m.player.isInLava() || m.player.getY()<34.7)return true;
        while(ridgePoint<RIDGE.length && (x<=RIDGE[ridgePoint][0]+.30
                || Math.hypot(x-RIDGE[ridgePoint][0],z-RIDGE[ridgePoint][1])<1.1))ridgePoint++;
        if(ridgePoint==RIDGE.length)return true;
        double gx=RIDGE[ridgePoint][0],gz=RIDGE[ridgePoint][1];
        if(ridgePoint>0 && Math.abs(z-gz)>3.15)return true;
        double dx=gx-x,dz=gz-z,dist=Math.max(.01,Math.hypot(dx,dz));
        BlockPos near=new BlockPos(x+dx/dist*.82,m.player.getY(),z+dz/dist*.82);
        for(int h=-1;h<=1;h++){
            BlockPos p=near.up(h);
            if(m.world.getFluidState(p).isIn(net.minecraft.tag.FluidTags.LAVA)
                    || m.world.getBlockState(p).getBlock()==net.minecraft.block.Blocks.MAGMA_BLOCK
                    || m.world.getBlockState(p).getBlock()==net.minecraft.block.Blocks.FIRE)return true;
        }
        Race.aim(m,new Vec3d(gx,m.player.getEyeY()-.10,gz));
        BastionApproach.move(m,gx,gz,false,true);
        m.options.keyJump.setPressed(m.player.isOnGround());
        status="sprint-jumping down the dry western gravel bank";
        return false;
    }
}
