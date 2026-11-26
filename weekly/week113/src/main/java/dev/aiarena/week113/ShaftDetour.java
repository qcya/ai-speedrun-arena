package dev.aiarena.week113;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** A second exit from the well when a piglin has fallen into the ordinary pillar. */
final class ShaftDetour {
    static final BlockPos MAIN=new BlockPos(-155,35,119);
    static final BlockPos SIDE=new BlockPos(-156,35,120);
    private static final BlockPos LINTEL=new BlockPos(-156,37,119);
    private static final BlockPos PASSAGE=new BlockPos(-156,35,119);
    private static final Vec3d ELBOW=new Vec3d(-155.56,35,119.48);
    private static int occupiedTicks,attemptTicks;
    private static boolean entered,returning,retired;

    static void reset() {
        occupiedTicks=attemptTicks=0;
        entered=returning=retired=false;
    }

    private static boolean empty(MinecraftClient m,BlockPos p) {
        return m.world.getBlockState(p).isAir();
    }

    private static boolean safeRock(MinecraftClient m,BlockPos p) {
        Block b=m.world.getBlockState(p).getBlock();
        return b==Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS || b==Blocks.BLACKSTONE
                || b==Blocks.POLISHED_BLACKSTONE_BRICKS;
    }

    private static boolean matches(MinecraftClient m) {
        if(!empty(m,SIDE)||!m.world.getBlockState(SIDE.down()).isFullCube(m.world,SIDE.down())
                ||!m.world.getBlockState(LINTEL).isFullCube(m.world,LINTEL)
                || !m.world.getBlockState(PASSAGE.down()).isFullCube(m.world,PASSAGE.down())
                || !empty(m,PASSAGE) || !empty(m,PASSAGE.up()) || !Middle.haveShaftSupports(m,4))return false;
        for(int i=1;i<=2;i++)if(!empty(m,SIDE.up(i))&&!safeRock(m,SIDE.up(i)))return false;
        for(int i=3;i<=6;i++)if(!empty(m,SIDE.up(i)))return false;
        return true;
    }

    private static boolean pigIn(MinecraftClient m,Box box) {
        for(Entity e:m.world.getEntities()){
            if(e instanceof PiglinEntity && e.isAlive() && e.getBoundingBox().intersects(box))return true;
        }
        return false;
    }

    static boolean active(MinecraftClient m) {
        if(attemptTicks>0)return true;
        if(retired || m.player.inventory.count(Items.ENDER_PEARL)<13 || m.player.inventory.count(Items.IRON_PICKAXE)==0
                || m.player.getY()>35.8 || m.player.squaredDistanceTo(Vec3d.ofCenter(MAIN))>3.5
                || !pigIn(m,new Box(MAIN))){
            occupiedTicks=0;return false;
        }
        // A transient crossing should keep the ordinary four-block climb.
        if(++occupiedTicks<8 || !matches(m) || pigIn(m,new Box(SIDE).stretch(0,2,0)))return false;
        attemptTicks=1;
        return true;
    }

    // Called only while this branch owns the climb. Both original rooms stay intact.
    static boolean readyToPillar(MinecraftClient m) {
        if(entered)return true;
        Race.stop(m);m.options.keyAttack.setPressed(false);
        if(++attemptTicks>140 || (!pigIn(m,new Box(MAIN)) && attemptTicks<24)
                || pigIn(m,new Box(SIDE).stretch(0,2,0)))returning=true;
        if(returning) {
            if(m.player.getZ()>119.72) {
                Race.aim(m,new Vec3d(ELBOW.x,m.player.getEyeY(),ELBOW.z));
                BastionApproach.move(m,ELBOW.x,ELBOW.z,false,false);
            }else{retired=true;attemptTicks=0;}
            Middle.status="returning through the covered elbow to the main pillar";
            return false;
        }
        for(int i=1;i<=2;i++){
            BlockPos stone=SIDE.up(i);
            if(empty(m,stone))continue;
            if(!safeRock(m,stone)){returning=true;return false;}
            // Approach through the west pocket. The southern wall hides this face from the east.
            if(Math.abs(m.player.getX()-ELBOW.x)>.18 || Math.abs(m.player.getZ()-ELBOW.z)>.20){
                Race.aim(m,new Vec3d(ELBOW.x,m.player.getEyeY(),ELBOW.z));
                BastionApproach.move(m,ELBOW.x,ELBOW.z,false,false);
            }else if(Middle.select(m,Items.IRON_PICKAXE)){
                m.options.keyAttack.setPressed(Middle.aim(m,stone,null)!=null);
            }
            Middle.status="cutting the two side-wall blocks below the well exit";
            return false;
        }
        double x=m.player.getX(),z=m.player.getZ();
        if(Math.abs(x-(SIDE.getX()+.5))<.14 && Math.abs(z-(SIDE.getZ()+.5))<.14 && m.player.isOnGround()){
            entered=true;return true;
        }
        // Clear the southeast corner with the player's whole 0.6-wide body before turning.
        Vec3d target=(x>SIDE.getX()+.70 && z<120.0)?ELBOW:Vec3d.ofBottomCenter(SIDE);
        Race.aim(m,new Vec3d(target.x,m.player.getEyeY(),target.z));
        double d=Math.hypot(target.x-x,target.z-z);
        BastionApproach.move(m,target.x,target.z,d<.36,false);
        Middle.status="walking into the cleared side shaft";
        return false;
    }
}
