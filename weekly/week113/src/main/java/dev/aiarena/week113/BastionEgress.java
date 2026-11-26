package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.SwordItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Keep above a group of sword piglins occupying the normal first-chest descent. */
final class BastionEgress {
    private static final int[][] UPPER = {
        {-111,36,109},{-112,36,109},{-113,36,108},{-114,36,108},{-115,36,108},
        {-116,36,109},{-117,36,109},{-117,36,110},{-118,36,111},{-119,36,112},
        {-119,36,113},{-119,36,114},{-119,36,115},{-119,36,116},{-119,36,117},
        {-119,36,118},{-119,36,119},{-119,36,120},{-120,36,120},{-121,36,120},
        {-122,36,120},{-123,36,120},{-123,36,121}
    };
    private static final BlockPos CHEST = new BlockPos(-124,33,122);
    private static boolean decided,useUpper;
    private static int index;
    static boolean arrived;
    static String status="";

    static void reset() {
        decided=false;useUpper=false;arrived=false;index=1;
    }

    private static boolean crowded(MinecraftClient mc) {
        int count=0;
        for(Entity entity:mc.world.getEntities()) {
            if(!(entity instanceof PiglinEntity)||!entity.isAlive()||entity.getY()>34.6)continue;
            PiglinEntity piglin=(PiglinEntity)entity;
            if(piglin.isBaby()||!(piglin.getMainHandStack().getItem() instanceof SwordItem))continue;
            if(Math.hypot(entity.getX()+111,entity.getZ()-113)<6.7 && ++count>=2)return true;
        }
        return false;
    }

    private static BlockPos at(int i) {
        int[] xyz=UPPER[i];return new BlockPos(xyz[0],xyz[1],xyz[2]);
    }

    static boolean tick(MinecraftClient mc) {
        if(!decided){
            decided=true;useUpper=BastionApproach.onLootPerch(mc)&&crowded(mc);
        }
        if(!useUpper||arrived)return false;
        Race.stop(mc);mc.options.keyAttack.setPressed(false);Race.cap(2131);
        if(mc.player.getY()<33.5){useUpper=false;return false;}
        BlockPos p=at(index),previous=at(index-1);
        // Keep walking at Y36, over the second chest's soul-sand stair. In the
        // native crowded run a piglin climbed that stair when the route dropped.
        if(index>=16 && mc.player.isOnGround()){
            // The lid of a normal chest reaches 14/16 of a block. Test points
            // inside its real shape to permit immediate looting from any safe footing.
            for(double y:new double[]{.74,.54,.31})for(double x:new double[]{.5,.21,.79})for(double z:new double[]{.5,.21,.79}){
                BlockHitResult hit=BastionApproach.line(mc,new Vec3d(CHEST.getX()+x,CHEST.getY()+y,CHEST.getZ()+z));
                if(hit!=null&&CHEST.equals(hit.getBlockPos())){arrived=true;return false;}
            }
        }
        if(BastionApproach.open(mc,p.down())){
            status="bridging above the first-chest piglins "+index+"/"+(UPPER.length-1);
            BastionApproach.bridge(mc,previous,p);return true;
        }
        double x=p.getX()+.5,z=p.getZ()+.5;
        double d=Math.hypot(mc.player.getX()-x,mc.player.getZ()-z);
        if(index<UPPER.length-1 && d<.38 && Math.abs(mc.player.getY()-p.getY())<.55) {
            index++;return true;
        }
        status="crossing the upper bastion terrace "+index+"/"+(UPPER.length-1);
        Race.aim(mc,new Vec3d(x,mc.player.getEyeY()-.1,z));
        boolean downhill=p.getY()<previous.getY();
        boolean nearGap=index+1<UPPER.length && BastionApproach.open(mc,at(index+1).down());
        BastionApproach.move(mc,x,z,!downhill && (nearGap || d<.35),!nearGap && d>1.1);
        mc.options.keyJump.setPressed(mc.player.horizontalCollision && p.getY()>mc.player.getY()+.45);
        return true;
    }
    private BastionEgress(){}
}
