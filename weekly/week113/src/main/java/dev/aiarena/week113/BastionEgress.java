package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.SwordItem;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Keep above a group of sword piglins occupying the normal first-chest descent. */
final class BastionEgress {
    private static final int[][] UPPER = {
        {-111,36,109},{-112,36,109},{-113,36,108},{-114,36,108},{-115,36,108},
        {-116,36,108},{-117,36,109},{-117,36,110},{-117,36,111},{-118,36,112},
        {-118,36,113},{-118,36,114},{-118,36,115},{-118,36,116},{-118,36,117},
        {-118,36,118},{-118,36,119},{-119,36,119},{-120,36,119},{-121,36,119},
        {-122,36,119},{-123,36,119},{-123,36,120}
    };
    private static final int BRANCH=15;
    private static final int[][] RECESS = {
        {-119,35,118},{-120,35,119},{-121,35,120},{-122,35,120}
    };
    private static boolean prepared,shortRecess,branchDecided;
    private static int padTicks,bridgeAimFrom,bridgeGuardUntil;
    private static final BlockPos CHEST = new BlockPos(-124,33,122);
    private static boolean decided,useUpper,covering,waitForPiglins,upperAllowed,waiting;
    private static int index,settledTicks,waitingTicks,clearTicks;
    private static BlockPos lootFoot,lastUnreachable;
    static boolean arrived;
    static String status="";

    static void reset() {
        prepared=shortRecess=branchDecided=false;padTicks=0;bridgeAimFrom=-1;bridgeGuardUntil=0;
        decided=false;useUpper=false;arrived=false;covering=false;waiting=false;index=1;lootFoot=null;lastUnreachable=null;settledTicks=waitingTicks=clearTicks=0;
        net.minecraft.server.integrated.IntegratedServer server=MinecraftClient.getInstance().getServer();
        waitForPiglins=server!=null&&java.nio.file.Files.isRegularFile(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("astra-bastion-wait"));
        upperAllowed=server!=null&&java.nio.file.Files.isRegularFile(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("astra-bastion-upper"));
    }

    private static boolean crowded(MinecraftClient mc) {
        int count=0;
        for(Entity entity:mc.world.getEntities()) {
            if(!(entity instanceof PiglinEntity)||!entity.isAlive())continue;
            PiglinEntity piglin=(PiglinEntity)entity;
            if(piglin.isBaby()||!(piglin.getMainHandStack().getItem() instanceof SwordItem))continue;
            // A sword piglin already on the chest ledge can knock the player into
            // the lower group before the first descent block is in reach.
            if(entity.getY() >= 35.5 && Math.abs(entity.getY()-mc.player.getY()) < 2.25
                    && entity.squaredDistanceTo(mc.player) < 20.25
                    && piglin.getActivity()==PiglinEntity.Activity.ATTACKING_WITH_MELEE_WEAPON) return true;
            if(entity.getY()>35.5)continue;
            // A second adult on the western approach is within striking distance
            // a few seconds after the drop even when it is far from the first chest.
            double x=entity.getX(),z=entity.getZ();
            double fraction=Math.max(0,Math.min(1,((x+111)*-8+(z-112)*3)/73));
            double distance=Math.hypot(x-(-111-8*fraction),z-(112+3*fraction));
            if(distance<5.1 && ++count>=2)return true;
        }
        return false;
    }

    // The chest perch can see both faces without stepping off the floor or opening the chest.
    static boolean prepareExit(MinecraftClient mc) {
        if(waitForPiglins || mc.currentScreen!=null || padTicks>=20 || !BastionApproach.onLootPerch(mc))return false;
        if(!prepared && !crowded(mc))return false;
        prepared=true;
        BlockPos anchor=new BlockPos(-113,35,108);
        net.minecraft.util.math.Direction face=net.minecraft.util.math.Direction.EAST;
        if(!BastionApproach.open(mc,anchor.offset(face))) {
            anchor=anchor.offset(face);face=net.minecraft.util.math.Direction.SOUTH;
        }
        if(!BastionApproach.open(mc,anchor.offset(face)))return false;
        ++padTicks;Race.stop(mc);mc.options.keySneak.setPressed(true);
        mc.options.keyAttack.setPressed(false);
        if(Middle.selectBuildingBlock(mc,false))Middle.use(mc,Middle.aim(mc,anchor,face));
        status="preparing the westward exit before opening the first chest";
        return true;
    }

    private static boolean lowerLandingOccupied(MinecraftClient mc) {
        for(Entity e:mc.world.getEntities()) {
            if(!(e instanceof PiglinEntity) || !e.isAlive())continue;
            PiglinEntity pig=(PiglinEntity)e;
            if(pig.isBaby() || !(pig.getMainHandStack().getItem() instanceof SwordItem))continue;
            if(Math.abs(e.getY()-35)<2.5 && (e.squaredDistanceTo(-119.3,35,118.7)<25
                    || e.squaredDistanceTo(-121.5,35,120.5)<25))return true;
        }
        return false;
    }

    private static boolean descentThreatened(MinecraftClient mc) {
        for (Entity entity:mc.world.getEntities()) {
            if (!(entity instanceof PiglinEntity) || !entity.isAlive()) continue;
            PiglinEntity pig=(PiglinEntity)entity;
            if (pig.isBaby() || !pig.isAttacking() || pig.getY()<30.5 || pig.getY()>38.5) continue;
            double x=pig.getX(), z=pig.getZ();
            double t=Math.max(0,Math.min(1,((x+110.5)*-12+(z-109.5)*12)/288));
            double routeDistance=Math.hypot(x-(-110.5-12*t),z-(109.5+12*t));
            double radius=pig.getMainHandStack().getItem() instanceof SwordItem ? 6.0 : 12.0;
            if (routeDistance<radius) return true;
        }
        return false;
    }

    static void lowerShield(MinecraftClient mc){
        if(!covering)return;
        mc.options.keyUse.setPressed(false);
        if(mc.player.isUsingItem())mc.interactionManager.stopUsingItem(mc.player);
        covering=false;
    }

    /** Cover the closest melee and ranged piglins together while walking sideways. */
    static boolean guardRamp(MinecraftClient mc,boolean turn){
        Entity sword=null,bow=null;
        double swordDistance=15.0,bowDistance=100.0;
        for(Entity entity:mc.world.getEntities()){
            if(!(entity instanceof PiglinEntity)||!entity.isAlive())continue;
            PiglinEntity pig=(PiglinEntity)entity;
            if(pig.isBaby() || !pig.isAttacking() || !mc.player.canSee(pig))continue;
            double dist=mc.player.squaredDistanceTo(pig);
            if(pig.getMainHandStack().getItem() instanceof SwordItem
                    && pig.getY()+2.25>=mc.player.getY() && dist<swordDistance) {
                swordDistance=dist;sword=pig;
            }else if(pig.getMainHandStack().getItem()==Items.CROSSBOW && dist<bowDistance) {
                bowDistance=dist;bow=pig;
            }
        }
        Entity threat=sword!=null?sword:bow;
        if(threat==null){lowerShield(mc);return false;}
        double best=sword!=null?swordDistance:bowDistance;
        Vec3d facing=threat.getBoundingBox().getCenter();
        if(sword!=null && bow!=null && mc.player.getOffHandStack().getItem()==Items.SHIELD){
            double sx=sword.getX()-mc.player.getX(),sz=sword.getZ()-mc.player.getZ();
            double bx=bow.getX()-mc.player.getX(),bz=bow.getZ()-mc.player.getZ();
            double sl=Math.max(.001,Math.hypot(sx,sz)),bl=Math.max(.001,Math.hypot(bx,bz));
            double dx=sx/sl+bx/bl,dz=sz/sl+bz/bl;
            if(Math.hypot(dx,dz)>.10)facing=new Vec3d(mc.player.getX()+dx,mc.player.getEyeY(),mc.player.getZ()+dz);
        }
        if(turn)Race.aim(mc,facing);
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

    static void guardBridge(MinecraftClient mc,BlockPos previous,BlockPos next) {
        mc.options.keySneak.setPressed(true);
        int dx=next.getX()-previous.getX(),dz=next.getZ()-previous.getZ();
        Direction face=Direction.fromVector(dx,0,dz);
        if(face==null || Math.abs(dx)+Math.abs(dz)!=1)return;
        BlockPos support=previous.down();
        Vec3d center=Vec3d.ofCenter(support);
        double across=(mc.player.getX()-center.x)*dx+(mc.player.getZ()-center.z)*dz;
        Vec3d aim=null;
        if(across>.51 && mc.player.age>=bridgeGuardUntil) {
            search:for(double h:new double[]{.34,.71,.19,.85})for(double along:new double[]{.5,.22,.78}) {
                Vec3d point=new Vec3d(support.getX()+(dx>0?.999:dx<0?.001:along),support.getY()+h,
                        support.getZ()+(dz>0?.999:dz<0?.001:along));
                BlockHitResult visible=BastionApproach.line(mc,point);
                if(visible!=null && visible.getBlockPos().equals(support) && visible.getSide()==face){aim=point;break search;}
            }
        }
        if(!guardRamp(mc,aim==null) || mc.player.getOffHandStack().getItem()!=Items.SHIELD) {
            bridgeAimFrom=-1;lowerShield(mc);BastionApproach.bridge(mc,previous,next);return;
        }
        if(aim!=null && Middle.selectBuildingBlock(mc,false)) {
            if(bridgeAimFrom<0)bridgeAimFrom=mc.player.age;
            Race.aim(mc,aim);mc.gameRenderer.updateTargetedEntity(1);
            if(mc.crosshairTarget instanceof BlockHitResult) {
                BlockHitResult hit=(BlockHitResult)mc.crosshairTarget;
                if(hit.getBlockPos().equals(support) && hit.getSide()==face) {
                    lowerShield(mc);Middle.use(mc,hit);bridgeAimFrom=-1;return;
                }
            }
            // A piglin can stand between the player and the face. Re-cover the group before retrying.
            if(mc.player.age-bridgeAimFrom>=6){bridgeAimFrom=-1;bridgeGuardUntil=mc.player.age+7;}
        }else bridgeAimFrom=-1;
        if(across<.69)BastionApproach.move(mc,center.x+dx*.69,center.z+dz*.69,across>.47,false);
    }

    private static Vec3d chestPoint(MinecraftClient mc){
        // A native chest lid is 14/16 high. Probe only points inside that shape.
        for(double y:new double[]{.74,.54,.31})for(double x:new double[]{.5,.21,.79})for(double z:new double[]{.5,.21,.79}){
            Vec3d point=new Vec3d(CHEST.getX()+x,CHEST.getY()+y,CHEST.getZ()+z);
            BlockHitResult hit=BastionApproach.line(mc,point);
            if(hit!=null&&CHEST.equals(hit.getBlockPos()))return point;
        }
        return null;
    }

    private static boolean chestVisible(MinecraftClient mc){return chestPoint(mc)!=null;}

    static void openSecondChest(MinecraftClient mc) {
        Race.stop(mc);mc.options.keySneak.setPressed(false);
        Vec3d aim=chestPoint(mc);
        if(aim==null)return;
        Race.aim(mc,aim);mc.gameRenderer.updateTargetedEntity(1);
        if(mc.crosshairTarget instanceof BlockHitResult) {
            BlockHitResult hit=(BlockHitResult)mc.crosshairTarget;
            if(CHEST.equals(hit.getBlockPos()))Middle.use(mc,hit);
        }
    }

    private static BlockPos at(int i) {
        int[] xyz=shortRecess && i>=BRANCH?RECESS[i-BRANCH]:UPPER[i];return new BlockPos(xyz[0],xyz[1],xyz[2]);
    }

    private static boolean overFirstChestTerrace(MinecraftClient mc) {
        double x=mc.player.getX(), y=mc.player.getY(), z=mc.player.getZ();
        return x>=-111.3 && x<=-107.0 && z>=107.7 && z<=111.8 && y>=35.7 && y<38
                && !BastionApproach.open(mc,new BlockPos(x,35,z));
    }

    static boolean tick(MinecraftClient mc) {
        if(!decided){
            decided=true;
            boolean blocked=overFirstChestTerrace(mc)&&crowded(mc);
            waiting=blocked&&waitForPiglins;
            useUpper=(prepared || blocked&&upperAllowed)&&!waitForPiglins;
        }
        if(!arrived && !waiting && !waitForPiglins && !useUpper && overFirstChestTerrace(mc) && crowded(mc))useUpper=true;
        // The first hand-fed trade takes six seconds. Piglins can fill the lower hall during that wait,
        // and the pickup can leave Astra one cell away from the original chest perch or briefly airborne.
        if(waitForPiglins && !waiting && waitingTicks==0
                && overFirstChestTerrace(mc) && crowded(mc)) waiting=true;
        if(waiting){
            Race.stop(mc);mc.options.keyAttack.setPressed(false);mc.options.keySneak.setPressed(true);
            if(Middle.offerExposedIngot(mc,true)) {
                status="offering the saved ingot to the piglin above the descent";
                return true;
            }
            if(mc.player.getOffHandStack().getItem()==Items.SHIELD) guardRamp(mc,true);
            int angry=0;
            for(Entity entity:mc.world.getEntities())if(entity instanceof PiglinEntity && entity.isAlive()
                    && mc.player.squaredDistanceTo(entity)<256 && ((PiglinEntity)entity).isAttacking())angry++;
            status="waiting on first chest ledge for angry piglins ("+angry+")";
            // The vanilla chest-looting anger memory lasts 600 ticks. Stay on the
            // isolated ledge in equipped gold, without re-aggroing the group.
            waitingTicks++;
            clearTicks=descentThreatened(mc)?0:clearTicks+1;
            if(clearTicks<16 && (waitingTicks<603 || angry>0&&waitingTicks<680) && mc.player.getY()>35.6)return true;
            waiting=false;
            lowerShield(mc);
        }
        if(!useUpper||arrived)return false;
        Race.stop(mc);mc.options.keyAttack.setPressed(false);Race.cap(2131);
        if(mc.player.getY()<33.5){lowerShield(mc);useUpper=false;return false;}
        if(index==BRANCH && !branchDecided){
            branchDecided=true;
            shortRecess=!lowerLandingOccupied(mc);
            // The diagonal descent cannot be built from the upper terrace by a side-face click.
            // Use this shortcut only when every landing has a real collision surface.
            for (int[] foot : RECESS) {
                BlockPos floor = new BlockPos(foot[0], foot[1] - 1, foot[2]);
                if (BastionApproach.open(mc, floor)) shortRecess=false;
            }
        }
        // Recheck while still able to stay on the high crossing.
        if(shortRecess && index==BRANCH && mc.player.getY()>=35.7 && lowerLandingOccupied(mc))shortRecess=false;
        int length=shortRecess?BRANCH+RECESS.length:UPPER.length;
        BlockPos p=at(index),previous=at(index-1);
        if(index==length-1) {
            if(lootFoot==null && mc.player.isOnGround() && mc.player.getBlockPos().equals(p)
                    && !BastionApproach.open(mc,p.down()) && chestVisible(mc))lootFoot=p;
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
            status="bridging above the first-chest piglins "+index+"/"+(length-1);
            guardBridge(mc,previous,p);return true;
        }
        double x=p.getX()+.5,z=p.getZ()+.5;
        double d=Math.hypot(mc.player.getX()-x,mc.player.getZ()-z);
        if(index<length-1 && d<.38 && Math.abs(mc.player.getY()-p.getY())<.55) {
            index++;return true;
        }
        status="crossing the upper bastion terrace "+index+"/"+(length-1);
        if(!guardRamp(mc,true))Race.aim(mc,new Vec3d(x,mc.player.getEyeY()-.1,z));
        boolean downhill=p.getY()<previous.getY();
        boolean nearGap=index+1<length && BastionApproach.open(mc,at(index+1).down());
        BastionApproach.move(mc,x,z,!downhill && (nearGap || d<.35),!nearGap && d>1.1);
        mc.options.keyJump.setPressed(mc.player.horizontalCollision && p.getY()>mc.player.getY()+.45);
        return true;
    }
    private BastionEgress(){}
}
