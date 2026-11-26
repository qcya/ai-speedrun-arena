package dev.aiarena.week113;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

/** Use an empty spawner interval to hunt an actual blaze visible in its southern passage. */
final class SouthBlazes {
    private static final double CENTER = -7.5;
    private static final int ARROW_RESERVE = 2;
    private static int started, returnAt, startRods, nextTrip, lastSight, shotAt, lastTarget, pickupSince;
    private static Vec3d lastPosition;
    static String status;

    static void reset() {
        started = returnAt = lastTarget = pickupSince = -1;
        nextTrip = 0;
        lastPosition = null;
        shotAt = -20;
    }

    private static boolean clearLine(MinecraftClient m, Vec3d to) {
        return m.world.rayTrace(new RayTraceContext(m.player.getCameraPosVec(1), to,
                RayTraceContext.ShapeType.COLLIDER, RayTraceContext.FluidHandling.NONE, m.player))
                .getType() == HitResult.Type.MISS;
    }

    private static boolean hallway(Entity e) {
        return Math.abs(e.getX() - CENTER) <= 2.55 && e.getZ() >= 122.0 && e.getZ() <= 157
                && e.getY() >= 56.5 && e.getY() < 62;
    }

    private static boolean clearFoot(MinecraftClient m, BlockPos p) {
        Block block = m.world.getBlockState(p).getBlock();
        return m.world.getBlockState(p).getCollisionShape(m.world,p).isEmpty()
                && m.world.getFluidState(p).isEmpty() && block != Blocks.FIRE && block != Blocks.SOUL_FIRE;
    }

    // Verify all four corners against the actual brick corridor and the two one-block steps.
    private static boolean supported(MinecraftClient m, double x, double z) {
        for (double dx : new double[]{-.28,.28}) for (double dz : new double[]{-.28,.28}) {
            boolean safe = false;
            for (int y = 57; y <= 60; y++) {
                BlockPos feet = new BlockPos(x+dx,y,z+dz);
                if (m.world.getBlockState(feet.down()).getBlock() == Blocks.NETHER_BRICKS
                        && clearFoot(m,feet) && clearFoot(m,feet.up())
                        && Math.abs(y-m.player.getY()) <= 1.55) { safe = true; break; }
            }
            if (!safe) return false;
        }
        return true;
    }

    private static void walk(MinecraftClient m, double x, double z) {
        double dx=x-m.player.getX(), dz=z-m.player.getZ(), len=Math.hypot(dx,dz);
        if (len < .16) return;
        double step=Math.min(.57,len)/len;
        if (!supported(m,m.player.getX()+dx*step,m.player.getZ()+dz*step)) return;
        Race.aim(m,new Vec3d(x,m.player.getEyeY(),z));
        BastionApproach.move(m,x,z,false,true);
        m.options.keyJump.setPressed(m.player.horizontalCollision && m.player.isOnGround());
    }

    private static void cancelDraw(MinecraftClient m) {
        if (m.player.isUsingItem() && m.player.getActiveItem().getItem() == Items.BOW) {
            // Switching away from a held bow is vanilla's cancel gesture. Let the switch take
            // effect before releasing use, so no partial arrow is sent toward the stairwell.
            if (m.player.getMainHandStack().getItem() == Items.BOW)
                m.player.inventory.selectedSlot = (m.player.inventory.selectedSlot+1)%9;
            m.options.keyUse.setPressed(true);
        } else {
            m.options.keyUse.setPressed(false);
        }
    }

    private static boolean returnToCage(MinecraftClient m) {
        cancelDraw(m);
        if (returnAt < 0) returnAt = m.player.age;
        if ((m.player.getZ() < 119.65 && m.player.getY() >= 58.4)
                || m.player.age-returnAt > 150) {
            started = returnAt = -1;
            nextTrip = m.player.age+120;
            return false;
        }
        status = "returning from the southern blaze passage to the spawner";
        if (m.player.getZ() < 121.6) Fortress.approachSpawner(m);
        else walk(m,CENTER,120.65);
        return true;
    }

    private static Vec3d firingPoint(MinecraftClient m, BlazeEntity blaze) {
        Vec3d now=blaze.getPos(), eye=m.player.getCameraPosVec(1);
        Vec3d motion=blaze.getEntityId()==lastTarget && lastPosition!=null
                ? now.subtract(lastPosition) : Vec3d.ZERO;
        lastPosition=now;
        lastTarget=blaze.getEntityId();
        double ticks=Math.hypot(now.x-eye.x,now.z-eye.z)/2.95;
        return blaze.getBoundingBox().getCenter().add(
                MathHelper.clamp(motion.x,-.22,.22)*ticks,
                .10+.025*ticks*Math.max(0,ticks-1)+MathHelper.clamp(motion.y,-.14,.14)*ticks,
                MathHelper.clamp(motion.z,-.22,.22)*ticks);
    }

    private static boolean clearOfPiglins(MinecraftClient m, BlazeEntity target, Vec3d aim) {
        Vec3d pos=m.player.getCameraPosVec(1).add(0,-.1,0);
        java.util.List<ZombifiedPiglinEntity> pigs=m.world.getNonSpectatingEntities(
                ZombifiedPiglinEntity.class,new Box(pos,aim).expand(2));
        if (pigs.isEmpty()) return true;
        Vec3d speed=aim.subtract(m.player.getCameraPosVec(1)).normalize().multiply(3);
        Box targetBox=target.getBoundingBox().expand(.12);
        int flight=(int)Math.ceil(pos.distanceTo(aim)/2.7)+2;
        for (int t=0;t<flight;t++) {
            Vec3d end=pos.add(speed);
            java.util.Optional<Vec3d> targetHit=targetBox.rayTrace(pos,end);
            double limit=targetHit.isPresent()?pos.squaredDistanceTo(targetHit.get()):Double.POSITIVE_INFINITY;
            for (ZombifiedPiglinEntity pig:pigs) {
                java.util.Optional<Vec3d> hit=pig.getBoundingBox().expand(.37).rayTrace(pos,end);
                if (hit.isPresent() && pos.squaredDistanceTo(hit.get())<=limit) return false;
            }
            if (targetHit.isPresent()) return true;
            pos=end;speed=speed.multiply(.99).add(0,-.05,0);
        }
        return true;
    }

    private static void bow(MinecraftClient m, BlazeEntity blaze) {
        Vec3d point=firingPoint(m,blaze);
        if (!clearOfPiglins(m,blaze,point)) {
            cancelDraw(m);
            // A target on the west side of the hall is better viewed from the west jamb.
            double lane=CENTER+(blaze.getX()<CENTER?-.82:.82);
            walk(m,lane,m.player.getZ());
            status="stepping clear of a zombified piglin in the firing lane";
            return;
        }
        Race.aim(m,point);
        if (!Hotbar.select(m,Items.BOW) || !clearLine(m,point) || m.player.age-shotAt<3) return;
        if (!m.player.isUsingItem())
            m.interactionManager.interactItem(m.player,m.world,Hand.MAIN_HAND);
        boolean drawing=m.player.isUsingItem() && m.player.getActiveItem().getItem()==Items.BOW;
        m.options.keyUse.setPressed(drawing);
        if (!drawing || m.player.getItemUseTime()<20 || m.player.inventory.count(Items.ARROW)<=ARROW_RESERVE)
            return;
        // During world loading a client can advance its draw considerably ahead of the server.
        // Read only our own acknowledged draw, and release through the usual interaction manager.
        ServerPlayerEntity self=m.getServer().getPlayerManager().getPlayer(m.player.getUuid());
        if (self!=null && self.isUsingItem() && self.getActiveItem().getItem()==Items.BOW
                && self.getItemUseTime()>=20 && self.inventory.count(Items.ARROW)>ARROW_RESERVE
                && m.player.getRotationVec(1).dotProduct(point.subtract(m.player.getCameraPosVec(1)).normalize())>.9996) {
            m.options.keyUse.setPressed(false);
            m.interactionManager.stopUsingItem(m.player);
            shotAt=m.player.age;
        }
    }

    private static void sword(MinecraftClient m, BlazeEntity blaze) {
        cancelDraw(m);
        if (m.player.isUsingItem() || !Hotbar.select(m,Items.DIAMOND_SWORD)) return;
        Race.aim(m,blaze.getBoundingBox().getCenter());
        m.gameRenderer.updateTargetedEntity(1);
        if (m.crosshairTarget instanceof EntityHitResult
                && ((EntityHitResult)m.crosshairTarget).getEntity()==blaze
                && m.player.getAttackCooldownProgress(.5f)>=.95f) {
            m.interactionManager.attackEntity(m.player,blaze);
            m.player.swingHand(Hand.MAIN_HAND);
        }
    }

    static boolean tick(MinecraftClient m, int goal, Vec3d cage) {
        if (m.currentScreen!=null) return false;
        int age=m.player.age, rods=m.player.inventory.count(Items.BLAZE_ROD);
        boolean active=started>=0;
        if (!active && (age<nextTrip || rods<1 || rods>=goal
                || m.player.inventory.count(Items.BOW)==0
                || m.player.inventory.count(Items.ARROW)<=ARROW_RESERVE || m.player.isUsingItem()
                || m.player.getZ()<116.2 || m.player.getZ()>130.7
                || m.player.getY()<56.8 || m.player.getY()>60.6
                || Math.abs(m.player.getX()-CENTER)>2.55 || m.player.squaredDistanceTo(cage)>256))
            return false;

        boolean cageBusy=false;
        BlazeEntity target=null;
        ItemEntity drop=null;
        double nearest=Double.MAX_VALUE, bestTargetScore=Double.MAX_VALUE;
        for (Entity e : m.world.getEntities()) {
            if (!e.isAlive()) continue;
            if (e instanceof HostileEntity && e.squaredDistanceTo(cage)<64) cageBusy=true;
            if (e instanceof ItemEntity && ((ItemEntity)e).getStack().getItem()==Items.BLAZE_ROD) {
                if (e.squaredDistanceTo(cage)<81 || !hallway(e) && e.squaredDistanceTo(m.player)<36)
                    cageBusy=true;
                else if (hallway(e) && e.getZ()<=155.5 && e.getY()<58.2
                        && m.player.canSee(e) && (drop==null || e.squaredDistanceTo(m.player)<drop.squaredDistanceTo(m.player)))
                    drop=(ItemEntity)e;
            }
            if (e instanceof BlazeEntity && hallway(e) && clearLine(m,e.getBoundingBox().getCenter())) {
                BlazeEntity blaze=(BlazeEntity)e;
                double distance=e.distanceTo(m.player);
                // Arrow knockback can move a wounded blaze a block behind the other one.
                // Finish a visible wounded target before splitting damage across the corridor.
                double score=distance+.65*blaze.getHealth()-(e.getEntityId()==lastTarget?1.6:0);
                if(score<bestTargetScore){
                    bestTargetScore=score;
                    target=blaze;
                    nearest=distance*distance;
                }
            }
        }
        if (!active) {
            if (cageBusy || drop!=null || target==null) return false;
            started=lastSight=age;
            startRods=rods;
            pickupSince=-1;
            lastPosition=null;
            lastTarget=-1;
            m.interactionManager.cancelBlockBreaking();
        }
        Race.stop(m);
        m.options.keyAttack.setPressed(false);
        if (drop!=null && pickupSince<0) pickupSince=age;
        // A real drop may land beyond the ordinary fortress controller's radius of 26.
        // Only the observed pickup can extend the outing, with a hard bound in space and time.
        boolean collecting=drop!=null && age-pickupSince<=95;
        if (returnAt>=0 || cageBusy || rods>startRods || rods>=goal || age-started>(collecting?280:210)
                || m.player.getZ()>156.25 || pickupSince>=0 && !collecting)
            return returnToCage(m);
        if (collecting) {
            cancelDraw(m);
            status="collecting a visible blaze rod in the southern fortress passage";
            // Drops settle against solid wall faces. Keep the wider player inside the
            // actual three-block aisle while approaching their pickup radius.
            walk(m,MathHelper.clamp(drop.getX(),CENTER-.98,CENTER+.98),drop.getZ());
            return true;
        }
        if (target==null || m.player.inventory.count(Items.ARROW)<=ARROW_RESERVE) {
            cancelDraw(m);
            if (age-lastSight>12 || m.player.inventory.count(Items.ARROW)<=ARROW_RESERVE) return returnToCage(m);
            status="watching for the southern blaze's dropped rod";
            return true;
        }
        lastSight=age;
        if (nearest<3.0*3.0) {
            status="striking the visible blaze in the southern passage";
            sword(m,target);
        } else if (m.player.getZ()<122.0 || nearest>16.5*16.5 && m.player.getZ()<130.04
                && !(m.player.isUsingItem() && m.player.getActiveItem().getItem()==Items.BOW && nearest<23*23)) {
            cancelDraw(m);
            status="moving to the clear firing lane south of the fortress spawner";
            walk(m,CENTER,Math.min(130.20,Math.max(123.0,target.getZ()-12)));
        } else if (nearest<=22*22 || m.player.isUsingItem() && nearest<23*23) {
            status="drawing and aiming at the visible southern blaze";
            bow(m,target);
        } else {
            cancelDraw(m);
            status="watching the southern blaze from inside spawner range";
        }
        return true;
    }
}
