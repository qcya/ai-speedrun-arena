package dev.aiarena.week113;

import java.util.UUID;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;


/** Follow the measured E31 return stair only for a native four-stack east and nine on the west lintel. */
final class EastHallRecovery {
    private static final int[][] route = {
        {-155,39,119}, {-156,38,119}, {-155,39,119}, {-154,40,119},
        {-153,40,119}, {-153,40,118}, {-152,40,118}, {-151,39,118},
        {-150,38,118}, {-149,37,118}, {-148,36,118}, {-147,36,118},
        {-146,36,118}, {-145,35,118}, {-144,36,118}, {-143,36,118}
    };
    private static final int stair = 7;
    private static boolean running, climbed, returning;
    private static int index, westGoal, waiting;
    private static Vec3d hall;
    private static UUID hallUuid;
    private static long lastSight, expires;
    static String status;

    static void reset() {
        running = climbed = returning = false;
        index = westGoal = waiting = 0;
        hall = null;
        hallUuid = null;
    }

    static boolean active() { return running; }

    // The measured path ends on the solid Y35 blackstone floor under the Z118 corridor.
    static void observe(MinecraftClient mc) {
        if (running || mc.world.getRegistryKey()!=World.NETHER || mc.player.getX()>-125
                || mc.player.getX()<-162 || mc.player.getZ()<110 || mc.player.getZ()>127) return;
        long now=mc.world.getTime();
        for (Entity e:mc.world.getEntities()) {
            if (!(e instanceof ItemEntity) || !e.isAlive() || !e.isOnGround()) continue;
            ItemEntity item=(ItemEntity)e;
            if (item.getStack().getItem()!=Items.ENDER_PEARL || item.getStack().getCount()!=4
                    || e.getX()<-143.12 || e.getX()>-141.88 || e.getZ()<118 || e.getZ()>118.8
                    || Math.abs(e.getY()-36)>.06) continue;
            hall=e.getPos();
            hallUuid=e.getUuid();
            lastSight=now;
            expires=now+Math.max(0,6000-item.getAge());
            return;
        }
        if (hall!=null && (mc.player.squaredDistanceTo(hall)<9 || now-lastSight>60 || now>=expires)) {
            hall=null;
            hallUuid=null;
        }
    }

    static boolean onWestLintel(ItemEntity item) {
        return item.isOnGround() && Math.abs(item.getY()-38)<.07
                && item.getX()>=-157.15 && item.getX()<=-155.95
                && item.getZ()>=118.80 && item.getZ()<=120.48;
    }

    static boolean begin(MinecraftClient mc,int pearls,int west,int local) {
        if (running || local>=13 || west!=9 || pearls+west+4<13 || hall==null
                || mc.world.getTime()>=expires || mc.world.getTime()-lastSight>60
                || mc.player.squaredDistanceTo(-154.70,35,119.60)>2.25) return false;
        running=true;
        climbed=returning=false;
        index=waiting=0;
        westGoal=pearls+west;
        status="recovering the observed east four and both western stacks";
        return true;
    }

    private static void faceForTravel(MinecraftClient mc, Vec3d to) {
        PiglinEntity nearest = null;
        double limit = 18;
        if (mc.player.getOffHandStack().getItem() == Items.SHIELD) {
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof PiglinEntity) || !e.isAlive() || !mc.player.canSee(e)) continue;
                PiglinEntity pig = (PiglinEntity)e;
                if (pig.getActivity() != PiglinEntity.Activity.ATTACKING_WITH_MELEE_WEAPON) continue;
                double d = pig.squaredDistanceTo(mc.player);
                if (d < limit) { limit = d; nearest = pig; }
            }
        }
        if (nearest == null) {
            Middle.lowerShield(mc);
            Race.aim(mc,new Vec3d(to.x,mc.player.getEyeY()-.18,to.z));
        } else {
            Race.aim(mc,nearest.getBoundingBox().getCenter());
            if (!mc.player.isUsingItem()) mc.interactionManager.interactItem(mc.player,mc.world,Hand.OFF_HAND);
            mc.options.keyUse.setPressed(true);
        }
    }

    private static boolean clearTemporaryStair(MinecraftClient mc) {
        Vec3d perch = new Vec3d(-150.66,39,118.55);
        double d = Math.hypot(mc.player.getX()-perch.x,mc.player.getZ()-perch.z);
        for (int y=39; y>=37; y--) {
            BlockPos p = new BlockPos(-149,y,118);
            if (mc.world.getBlockState(p).getBlock() != Blocks.DIRT) continue;
            if (d>.30 || Math.abs(mc.player.getY()-39)>.3) {
                faceForTravel(mc,perch);
                Middle.approach(mc,perch,false);
                if (mc.player.isOnGround() && mc.player.getY()<38.9) mc.options.keyJump.setPressed(true);
            } else {
                Middle.lowerShield(mc);
                mc.options.keySneak.setPressed(true);
                if (Middle.select(mc,Opening.shovelOrPick(mc))) {
                    mc.options.keyAttack.setPressed(Middle.aim(mc,p,null)!=null);
                }
            }
            status = "opening the saved third chest stair at "+p.toShortString();
            return true;
        }
        return false;
    }

    static boolean tick(MinecraftClient mc) {
        if (!running || mc.world.getRegistryKey()!=World.NETHER) return false;
        Race.stop(mc);
        mc.options.keyAttack.setPressed(false);
        int pearls = mc.player.inventory.count(Items.ENDER_PEARL);
        if (!climbed) {
            status = "climbing the original west trading pillar for the nine settled pearls";
            climbed = Middle.climbTradingShaft(mc);
            if (climbed) index=1;
            return true;
        }
        if (!returning && index==stair && clearTemporaryStair(mc)) return true;
        int[] cell=route[index];
        Vec3d to = new Vec3d(cell[0]+.5,cell[1],cell[2]+.5);
        if (index==1) to = new Vec3d(-155.93,38,119.54);
        if (index==route.length-1 && hall!=null)
            to = new Vec3d(hall.x,36,Math.max(118.29,Math.min(118.70,hall.z)));
        if (!returning && pearls>=13) {
            if (index<=2) { reset(); return false; }
            returning=true;
        }
        double d=Math.hypot(to.x-mc.player.getX(),to.z-mc.player.getZ());
        if (d<.27 && Math.abs(mc.player.getY()-to.y)<.39 && mc.player.isOnGround()) {
            if (index==1 && !returning && pearls<westGoal) {
                if (++waiting>40) { reset(); return false; }
                // Remain on the solid western lintel while both native items enter pickup range.
                status="picking up both western pearl stacks on the lintel, inventory "+pearls;
                return true;
            }
            if (returning && index==2) {
                reset();
                return false;
            }
            if (!returning && index==route.length-1 && pearls<13) {
                boolean present=false;
                for (Entity entity:mc.world.getEntities()) {
                    if (entity.isAlive() && entity.getUuid().equals(hallUuid)) { present=true; break; }
                }
                if (!present || ++waiting>40) returning=true;
                return true;
            }
            waiting=0;
            if (returning) index--;
            else if(index+1<route.length) index++;
            status="following the saved bastion stair "+index;
            return true;
        }
        faceForTravel(mc,to);
        Middle.approach(mc,to,false);
        if (mc.player.isOnGround() && d<1.55 && to.y>mc.player.getY()+.42) mc.options.keyJump.setPressed(true);
        status=(returning?"returning through the saved east hall ":"collecting pearls from the saved east hall ")+index;
        return true;
    }
    private EastHallRecovery() {}
}
