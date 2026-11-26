package dev.aiarena.week113;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Approach a stalled rim drop from the supported side of its actual pickup box. */
final class CageRimPickup {
    private static final double[] OFFSETS={0,-.48,.48,-.94,.94,-1.22,1.22,-1.32,1.32};
    private static int item=-1, seenAt, nextPlan;
    private static Vec3d goal, approach, original;
    private static BlockPos lip;
    private static boolean reachedApproach;
    static String status;

    static void reset() {
        item=-1;
        goal=approach=original=null;
        lip=null;
        reachedApproach=false;
    }

    private static boolean supported(MinecraftClient mc,Vec3d foot) {
        return mc.world.getBlockState(new BlockPos(foot).down()).isOf(Blocks.NETHER_BRICKS);
    }

    private static boolean dryAir(MinecraftClient mc, BlockPos pos) {
        Block block=mc.world.getBlockState(pos).getBlock();
        return mc.world.getBlockState(pos).getCollisionShape(mc.world,pos).isEmpty()
                && mc.world.getFluidState(pos).isEmpty() && block!=Blocks.FIRE && block!=Blocks.SOUL_FIRE;
    }

    // Only the upper brick of the enclosing rim may be opened. Keep the player's floor
    // and the item support, and refuse blocks adjacent to any exposed fluid.
    private static boolean canOpen(MinecraftClient mc, BlockPos pos, BlockPos support) {
        if(pos.getY()!=60 || pos.equals(support)
                || !(pos.getX()==-11 || pos.getX()==-5 || pos.getZ()==112)
                || !mc.world.getBlockState(pos).isOf(Blocks.NETHER_BRICKS)
                || mc.player.inventory.count(Items.IRON_PICKAXE)==0)return false;
        for(Direction side:Direction.values())if(!mc.world.getFluidState(pos.offset(side)).isEmpty())return false;
        return true;
    }

    private static boolean footClear(MinecraftClient mc,Vec3d foot,BlockPos open) {
        if(!supported(mc,foot))return false;
        for(double dx:new double[]{-.299,.299})for(double dz:new double[]{-.299,.299}){
            BlockPos base=new BlockPos(foot.x+dx,59,foot.z+dz);
            for(int h=0;h<2;h++){
                BlockPos pos=base.up(h);
                if(!pos.equals(open) && !dryAir(mc,pos))return false;
            }
        }
        return true;
    }

    private static double stance(double coordinate) {
        int block=MathHelper.floor(coordinate);
        return block+MathHelper.clamp(coordinate-block,.36,.64);
    }

    private static void plan(MinecraftClient mc,ItemEntity rod) {
        Vec3d player=mc.player.getPos(),drop=rod.getPos();
        BlockPos support=new BlockPos(drop.x,drop.y-.04,drop.z);
        double best=Double.POSITIVE_INFINITY;
        goal=approach=null;
        lip=null;
        reachedApproach=false;
        for(double dx:OFFSETS)for(double dz:OFFSETS){
            Vec3d end=new Vec3d(drop.x+dx,59,drop.z+dz);
            if(end.x< -9.95 || end.x> -5.02 || end.z<113.02 || end.z>118.95 || !supported(mc,end))continue;
            double lowerBound=player.distanceTo(end);
            if(lowerBound>=best)continue;
            Vec3d stage=new Vec3d(stance(end.x),59,stance(end.z));
            if(!Fortress.cageFloorLine(mc,stage,stage))continue;
            BlockPos blocker=null;
            if(!footClear(mc,end,null)){
                BlockPos center=new BlockPos(end);
                // The short segment stays within the same supported floor cell. A single
                // neighboring brick may overlap the edge of the player's upper body.
                for(Direction side:Direction.Type.HORIZONTAL){
                    BlockPos pos=center.up().offset(side);
                    if(canOpen(mc,pos,support) && footClear(mc,end,pos)){blocker=pos;break;}
                }
                if(blocker==null)continue;
            }
            double cost=lowerBound+(blocker==null?0:4);
            if(cost>=best)continue;
            if(!Fortress.cageFloorLine(mc,player,stage)){
                Vec3d corner=Fortress.rodCorner(mc,player,stage);
                if(corner==null)continue;
                cost=player.distanceTo(corner)+corner.distanceTo(stage)+stage.distanceTo(end)+(blocker==null?0:4);
            }
            if(cost<best){best=cost;goal=end;approach=stage;lip=blocker;}
        }
    }

    static boolean tick(MinecraftClient mc,ItemEntity rod) {
        int now=mc.player.age;
        if(item!=rod.getEntityId() || original==null || original.squaredDistanceTo(rod.getPos())>.16){
            reset();
            item=rod.getEntityId();original=rod.getPos();seenAt=now;nextPlan=now+20;
        }
        if(now-seenAt<20 || now<nextPlan && goal==null)return false;
        if(goal==null){plan(mc,rod);nextPlan=now+16;}
        if(goal==null)return false;
        Vec3d foot=mc.player.getPos();
        if(foot.distanceTo(approach)<.18)reachedApproach=true;
        if(reachedApproach && foot.distanceTo(approach)>.57)reachedApproach=false;
        if(!reachedApproach){
            Vec3d next=Fortress.cageFloorLine(mc,foot,approach)?approach:Fortress.rodCorner(mc,foot,approach);
            if(next==null){goal=null;nextPlan=now+4;return false;}
            Race.aim(mc,new Vec3d(next.x,mc.player.getEyeY(),next.z));
            BastionApproach.move(mc,next.x,next.z,foot.distanceTo(next)<.41,false);
            status="walking to a supported pickup position beside the cage rim";
        }else if(lip!=null && !dryAir(mc,lip)){
            if(!canOpen(mc,lip,new BlockPos(rod.getX(),rod.getY()-.04,rod.getZ()))){goal=null;return false;}
            if(Middle.select(mc,Items.IRON_PICKAXE))mc.options.keyAttack.setPressed(Middle.aim(mc,lip,null)!=null);
            status="opening one rim brick from the supported pickup floor";
        }else{
            if(!footClear(mc,goal,null)){goal=null;return false;}
            if(foot.distanceTo(goal)>.047){
                Race.aim(mc,new Vec3d(goal.x,mc.player.getEyeY(),goal.z));
                BastionApproach.move(mc,goal.x,goal.z,true,false);
            }
            status="standing within reach of the elevated rod";
        }
        return true;
    }

    private CageRimPickup(){}
}
