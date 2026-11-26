package dev.aiarena.week113;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Inspect the narrow east drop shaft from the lower spawner steps. */
final class EastRodPocket {
    private static final BlockPos WALL=new BlockPos(-6,57,120);
    private static int stage,started,peeked;
    private static boolean checked;
    static String status;

    static boolean active() {return stage!=0;}

    static void reset() {stage=started=peeked=0;checked=false;}

    private static boolean air(MinecraftClient m,BlockPos p) {
        return m.world.getBlockState(p).getCollisionShape(m.world,p).isEmpty() && m.world.getFluidState(p).isEmpty();
    }

    private static boolean safeFloor(MinecraftClient m,BlockPos p) {
        return m.world.getBlockState(p).isFullCube(m.world,p) && m.world.getFluidState(p).isEmpty();
    }

    private static boolean go(MinecraftClient m,double x,double z) {
        double dx=x-m.player.getX(),dz=z-m.player.getZ(),distance=Math.hypot(dx,dz);
        if(distance<.14)return true;
        double step=Math.min(.35,distance)/distance;
        double px=m.player.getX()+dx*step,pz=m.player.getZ()+dz*step;
        // Each verified floor is dry and at most one block below the current feet.
        BlockPos floor=new BlockPos(px,56,pz);
        if(!safeFloor(m,floor))return false;
        Race.aim(m,new Vec3d(x,m.player.getEyeY()-.10,z));
        BastionApproach.move(m,x,z,distance<.55,distance>1.6);
        return false;
    }

    private static boolean mineWall(MinecraftClient m,BlockPos wall) {
        if(air(m,wall))return true;
        if(!m.world.getBlockState(wall).isOf(Blocks.NETHER_BRICKS))return false;
        if(Middle.select(m,Items.IRON_PICKAXE))m.options.keyAttack.setPressed(Middle.aim(m,wall,null)!=null);
        return false;
    }

    static boolean inspect(MinecraftClient m,int goal,boolean busy) {
        if(m.player.inventory.count(Items.BLAZE_ROD)!=goal-1 || m.currentScreen!=null || m.player.getHealth()<9){
            stage=0;return false;
        }
        double x=m.player.getX(),y=m.player.getY(),z=m.player.getZ();
        if(stage==0) {
            if(checked || busy || x< -11.1 || x> -6.1 || y<56.9 || y>58.6 || z<120.12 || z>123.1)return false;
            if(!safeFloor(m,WALL.down()) || !safeFloor(m,WALL.east().down()) || !air(m,WALL.east()) || !air(m,WALL.east().up()))return false;
            checked=true;stage=1;started=m.player.age;peeked=0;
        }
        if(m.player.age-started>150 || y<56.8 || y>59.25){stage=0;return false;}
        Race.stop(m);m.options.keyAttack.setPressed(false);
        if(stage==1) {
            status="looking into the lower eastern spawner passage";
            if(x< -9.15 && z<121.32)go(m,x,121.55);
            else if(x< -8.45)go(m,-8.25,121.52);
            else if(Math.abs(z-120.50)>.18)go(m,x,120.50);
            else if(go(m,-6.70,120.50))stage=2;
        } else if(stage==2 || stage==3) {
            status="opening the two-brick side of the eastern rod pocket";
            if(mineWall(m,stage==2?WALL.up():WALL))stage++;
        } else {
            ItemEntity rod=null;
            for(Entity e:m.world.getEntities())if(e instanceof ItemEntity && e.isAlive() &&
                    ((ItemEntity)e).getStack().getItem()==Items.BLAZE_ROD && e.getBlockPos().equals(WALL.east()) && m.player.canSee(e)){
                rod=(ItemEntity)e;break;
            }
            if(rod!=null){
                status="collecting the visible rod through the eastern pocket";
                go(m,Math.max(-4.69,Math.min(-4.32,rod.getX())),Math.max(120.35,Math.min(120.65,rod.getZ())));
            } else {
                status="checking the exposed eastern floor for a rod";
                if(go(m,-5.48,120.51) && ++peeked>8){stage=0;return false;}
            }
        }
        return true;
    }
    private EastRodPocket(){}
}
