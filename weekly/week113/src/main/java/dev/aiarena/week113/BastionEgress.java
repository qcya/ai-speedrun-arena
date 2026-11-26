package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.SwordItem;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
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
    private static boolean decided,useUpper,covering,waitForPiglins,upperAllowed,waiting;
    private static int index,settledTicks,waitingTicks;
    private static BlockPos lootFoot,lastUnreachable;
    static boolean arrived;
    static String status="";

    static void reset() {
        decided=false;useUpper=false;arrived=false;covering=false;waiting=false;index=1;lootFoot=null;lastUnreachable=null;settledTicks=waitingTicks=0;
        net.minecraft.server.integrated.IntegratedServer server=MinecraftClient.getInstance().getServer();
        waitForPiglins=server!=null&&java.nio.file.Files.isRegularFile(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("astra-bastion-wait"));
        upperAllowed=server!=null&&java.nio.file.Files.isRegularFile(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("astra-bastion-upper"));
    }

    private static boolean crowded(MinecraftClient mc) {
        int count=0;
        for(Entity entity:mc.world.getEntities()) {
            if(!(entity instanceof PiglinEntity)||!entity.isAlive()||entity.getY()>35.5)continue;
            PiglinEntity piglin=(PiglinEntity)entity;
            if(piglin.isBaby()||!(piglin.getMainHandStack().getItem() instanceof SwordItem))continue;
            // A second adult on the western approach is within striking distance
            // a few seconds after the drop even when it is far from the first chest.
            double x=entity.getX(),z=entity.getZ();
            double fraction=Math.max(0,Math.min(1,((x+111)*-8+(z-112)*3)/73));
            double distance=Math.hypot(x-(-111-8*fraction),z-(112+3*fraction));
            if(distance<5.1 && ++count>=2)return true;
        }
        return false;
    }

    private static void lowerShield(MinecraftClient mc){
        if(!covering)return;
        mc.options.keyUse.setPressed(false);
        if(mc.player.isUsingItem())mc.interactionManager.stopUsingItem(mc.player);
        covering=false;
    }

    /** Face the single melee attacker that can reach the ledge while continuing to move. */
    private static boolean guardRamp(MinecraftClient mc){
        Entity threat=null;double best=15.0;
        for(Entity entity:mc.world.getEntities()){
            if(!(entity instanceof PiglinEntity)||!entity.isAlive())continue;
            PiglinEntity pig=(PiglinEntity)entity;
            if(pig.isBaby()||!(pig.getMainHandStack().getItem() instanceof SwordItem)
                    ||pig.getY()+2.25<mc.player.getY()||!mc.player.canSee(pig))continue;
            double dist=mc.player.squaredDistanceTo(pig);
            if(dist<best){best=dist;threat=pig;}
        }
        if(threat==null){lowerShield(mc);return false;}
        Race.aim(mc,threat.getBoundingBox().getCenter());
        if(mc.player.getOffHandStack().getItem()==Items.SHIELD){
            if(!mc.player.isUsingItem())mc.interactionManager.interactItem(mc.player,mc.world,Hand.OFF_HAND);
            mc.options.keyUse.setPressed(true);covering=true;
        }else if(best<9.0 && Middle.select(mc,Items.DIAMOND_SWORD)){
            mc.gameRenderer.updateTargetedEntity(1);
            if(mc.crosshairTarget instanceof EntityHitResult && ((EntityHitResult)mc.crosshairTarget).getEntity()==threat
                    && mc.player.getAttackCooldownProgress(.5f)>.91f){
                mc.interactionManager.attackEntity(mc.player,threat);mc.player.swingHand(Hand.MAIN_HAND);
            }
        }
        return true;
    }

    private static boolean chestVisible(MinecraftClient mc){
        // A native chest lid is 14/16 high. Probe only points inside that shape.
        for(double y:new double[]{.74,.54,.31})for(double x:new double[]{.5,.21,.79})for(double z:new double[]{.5,.21,.79}){
            BlockHitResult hit=BastionApproach.line(mc,new Vec3d(CHEST.getX()+x,CHEST.getY()+y,CHEST.getZ()+z));
            if(hit!=null&&CHEST.equals(hit.getBlockPos()))return true;
        }
        return false;
    }

    private static BlockPos at(int i) {
        int[] xyz=UPPER[i];return new BlockPos(xyz[0],xyz[1],xyz[2]);
    }

    static boolean tick(MinecraftClient mc) {
        if(!decided){
            decided=true;
            boolean blocked=BastionApproach.onLootPerch(mc)&&crowded(mc);
            waiting=blocked&&waitForPiglins;
            useUpper=blocked&&upperAllowed&&!waitForPiglins;
        }
        if(waiting){
            Race.stop(mc);mc.options.keyAttack.setPressed(false);mc.options.keySneak.setPressed(true);
            int angry=0;
            for(Entity entity:mc.world.getEntities())if(entity instanceof PiglinEntity && entity.isAlive()
                    && mc.player.squaredDistanceTo(entity)<256 && ((PiglinEntity)entity).isAttacking())angry++;
            status="waiting on first chest ledge for angry piglins ("+angry+")";
            // The vanilla chest-looting anger memory lasts 600 ticks. Stay on the
            // isolated ledge in equipped gold, without re-aggroing the group.
            waitingTicks++;
            if((waitingTicks<603 || angry>0&&waitingTicks<680) && mc.player.getY()>35.6)return true;
            waiting=false;
        }
        if(!useUpper||arrived)return false;
        Race.stop(mc);mc.options.keyAttack.setPressed(false);Race.cap(2131);
        if(mc.player.getY()<33.5){lowerShield(mc);useUpper=false;return false;}
        BlockPos p=at(index),previous=at(index-1);
        // Keep walking at Y36, over the second chest's soul-sand stair. In the
        // native crowded run a piglin climbed that stair when the route dropped.
        if(index>=16){
            if(lootFoot==null && mc.player.isOnGround() && chestVisible(mc)){
                BlockPos current=mc.player.getBlockPos();
                if(!current.equals(lastUnreachable)&&!BastionApproach.open(mc,current.down()) && current.getY()==36)lootFoot=current;
            }
            if(lootFoot!=null && mc.player.getY()<lootFoot.getY()-.65){lastUnreachable=lootFoot;lootFoot=null;}
            if(lootFoot!=null){
                double bx=lootFoot.getX()+.5,bz=lootFoot.getZ()+.5;
                boolean secure=mc.player.isOnGround() && mc.player.getBlockPos().equals(lootFoot)
                        && Math.hypot(mc.player.getX()-bx,mc.player.getZ()-bz)<.27
                        && Math.hypot(mc.player.getVelocity().x,mc.player.getVelocity().z)<.075;
                if(secure&&chestVisible(mc)){
                    if(++settledTicks>=3){lowerShield(mc);arrived=true;return false;}
                    mc.options.keySneak.setPressed(true);return true;
                }
                if(secure && !chestVisible(mc)){lastUnreachable=lootFoot;lootFoot=null;}
                else{
                    status="braking on the solid upper chest footing";
                    settledTicks=0;
                    Race.aim(mc,new Vec3d(bx,mc.player.getEyeY()-.1,bz));
                    BastionApproach.move(mc,bx-mc.player.getVelocity().x,bz-mc.player.getVelocity().z,true,false);
                    return true;
                }
            }
        }
        if(BastionApproach.open(mc,p.down())){
            lowerShield(mc);
            status="bridging above the first-chest piglins "+index+"/"+(UPPER.length-1);
            BastionApproach.bridge(mc,previous,p);return true;
        }
        double x=p.getX()+.5,z=p.getZ()+.5;
        double d=Math.hypot(mc.player.getX()-x,mc.player.getZ()-z);
        if(index<UPPER.length-1 && d<.38 && Math.abs(mc.player.getY()-p.getY())<.55) {
            index++;return true;
        }
        status="crossing the upper bastion terrace "+index+"/"+(UPPER.length-1);
        if(index<14 || !guardRamp(mc))Race.aim(mc,new Vec3d(x,mc.player.getEyeY()-.1,z));
        boolean downhill=p.getY()<previous.getY();
        boolean nearGap=index+1<UPPER.length && BastionApproach.open(mc,at(index+1).down());
        BastionApproach.move(mc,x,z,!downhill && (nearGap || d<.35),!nearGap && d>1.1);
        mc.options.keyJump.setPressed(mc.player.horizontalCollision && p.getY()>mc.player.getY()+.45);
        return true;
    }
    private BastionEgress(){}
}
