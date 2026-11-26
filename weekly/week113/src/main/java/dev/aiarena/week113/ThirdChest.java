package dev.aiarena.week113;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

final class ThirdChest {
    private static final BlockPos CHEST = new BlockPos(-150,36,117);
    private static final BlockPos FRONT_FOOT = new BlockPos(-149,37,118);
    private static final BlockPos FRONT_HEAD = FRONT_FOOT.up();
    private static final BlockPos[] WALL = {
        FRONT_FOOT, FRONT_HEAD, new BlockPos(-149,36,117), new BlockPos(-149,37,117),
        new BlockPos(-149,38,117), new BlockPos(-150,38,116)
    };
    private static boolean active;
    private static int arrived;
    static String status;

    static void reset() {active=false;arrived=-1;}

    static boolean needed(MinecraftClient mc) {
        if (active) return true;
        double x=mc.player.getX(),z=mc.player.getZ();
        if (x> -148.10 || x< -150.05 || z<117.90 || z>119.05 || mc.player.getY()<37.4) return false;
        for (Entity e:mc.world.getEntities()) {
            if (!(e instanceof PiglinEntity) || !e.isAlive() || !((PiglinEntity)e).isAttacking()
                    || e.squaredDistanceTo(mc.player)>100) continue;
            active=true;
            return true;
        }
        return false;
    }

    private static boolean onPerch(MinecraftClient mc) {
        return mc.player.getY()>=37.92 && mc.player.getX() < -149.32
                && Math.abs(mc.player.getZ()-118.50)<.33;
    }

    private static boolean placeCover(MinecraftClient mc, ServerPlayerEntity self, BlockPos cell) {
        if (!self.world.getBlockState(cell).getMaterial().isReplaceable()
                || !mc.world.getBlockState(cell).getMaterial().isReplaceable()) return false;
        Box box=new Box(cell);
        for (Entity e:mc.world.getEntities()) if (e.isAlive() && e.getBoundingBox().intersects(box)) return false;
        Direction face=cell.getZ()==116?Direction.EAST:Direction.UP;
        BlockPos support=cell.offset(face.getOpposite());
        if(mc.world.getBlockState(support).getMaterial().isReplaceable()) return false;
        for(double a:new double[]{.5,.14,.86})for(double b:new double[]{.5,.14,.86}) {
            Vec3d p=face==Direction.UP?new Vec3d(support.getX()+a,support.getY()+.999,support.getZ()+b)
                    :new Vec3d(support.getX()+.999,support.getY()+a,support.getZ()+b);
            if(Opening.hitFace(mc,support,face,p)) return true;
        }
        return false;
    }

    private static BlockHitResult chestHit(MinecraftClient mc) {
        Vec3d eye=mc.player.getCameraPosVec(1);
        for(double z:new double[]{.5,.76,.9})for(double x:new double[]{.5,.27,.73}) {
            Vec3d point=new Vec3d(CHEST.getX()+x,CHEST.getY()+.83,CHEST.getZ()+z);
            if(eye.squaredDistanceTo(point)>18)continue;
            BlockHitResult ray=mc.world.rayTrace(new RayTraceContext(eye,point,RayTraceContext.ShapeType.OUTLINE,
                    RayTraceContext.FluidHandling.NONE,mc.player));
            if(ray.getType()!=HitResult.Type.BLOCK || !ray.getBlockPos().equals(CHEST))continue;
            Race.aim(mc,point);
            mc.gameRenderer.updateTargetedEntity(1);
            if(mc.crosshairTarget instanceof BlockHitResult && mc.crosshairTarget.getType()==HitResult.Type.BLOCK
                    && ((BlockHitResult)mc.crosshairTarget).getBlockPos().equals(CHEST))return (BlockHitResult)mc.crosshairTarget;
        }
        return null;
    }

    /** A drawn sword on the same stair can stop a normal placement with its hitbox. */
    private static PiglinEntity stairThreat(MinecraftClient mc) {
        PiglinEntity nearest=null;
        double best=3.1*3.1;
        for(Entity e:mc.world.getEntities(mc.player,mc.player.getBoundingBox().expand(3.2))) {
            if(!(e instanceof PiglinEntity) || !e.isAlive() || !mc.player.canSee(e))continue;
            PiglinEntity pig=(PiglinEntity)e;
            double d=pig.squaredDistanceTo(mc.player);
            if(pig.isBaby() || pig.getActivity()!=PiglinEntity.Activity.ATTACKING_WITH_MELEE_WEAPON
                    || d>=best)continue;
            nearest=pig;best=d;
        }
        return nearest;
    }

    private static void clearStair(MinecraftClient mc, PiglinEntity pig) {
        Middle.select(mc,Items.DIAMOND_SWORD);
        Race.aim(mc,pig.getBoundingBox().getCenter());mc.gameRenderer.updateTargetedEntity(1);
        boolean ray=mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult
                && ((net.minecraft.util.hit.EntityHitResult)mc.crosshairTarget).getEntity()==pig;
        if(ray && mc.player.getMainHandStack().getItem()==Items.DIAMOND_SWORD
                && mc.player.getAttackCooldownProgress(.5f)>.95f) {
            Middle.lowerShield(mc);
            mc.interactionManager.attackEntity(mc.player,pig);
            mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        } else if(mc.player.getOffHandStack().getItem()==Items.SHIELD) {
            if(!mc.player.isUsingItem())mc.interactionManager.interactItem(mc.player,mc.world,net.minecraft.util.Hand.OFF_HAND);
            mc.options.keyUse.setPressed(true);
        }
        status="holding the third chest perch against a sword piglin on its open stair";
    }

    static void tick(MinecraftClient mc) {
        if (!onPerch(mc)) {
            Vec3d perch=new Vec3d(-149.62,38,118.50);
            Race.aim(mc,new Vec3d(perch.x,mc.player.getEyeY(),perch.z));
            Middle.approach(mc,perch,false);
            mc.options.keySprint.setPressed(true);
            mc.options.keyJump.setPressed(mc.player.isOnGround() && mc.player.getY()<37.98);
            status="landing on the south side of the third chest";
            return;
        }
        if (arrived<0) arrived=mc.player.age;
        ServerPlayerEntity self=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if(self==null)return;
        boolean openStair=self.world.getBlockState(FRONT_HEAD).getMaterial().isReplaceable()
                || mc.world.getBlockState(FRONT_HEAD).getMaterial().isReplaceable();
        PiglinEntity threat=stairThreat(mc);
        if(threat!=null && openStair && (threat.getBoundingBox().intersects(new Box(FRONT_FOOT))
                || threat.getBoundingBox().intersects(new Box(FRONT_HEAD))
                || threat.squaredDistanceTo(mc.player)<2.2*2.2)) {
            clearStair(mc,threat);return;
        }
        Middle.lowerShield(mc);
        if((mc.player.age-arrived<22 || threat!=null && openStair) && mc.player.inventory.count(Items.DIRT)>0) {
            boolean missing=false;
            for(BlockPos c:WALL)if(self.world.getBlockState(c).getMaterial().isReplaceable()){missing=true;break;}
            if(missing) {
                status="closing the eastern stair and far chest before looting";
                if (!Middle.select(mc,Items.DIRT) || self.getMainHandStack().getItem()!=Items.DIRT) return;
                for(BlockPos c:WALL) if(placeCover(mc,self,c)) return;
            }
        }
        if(threat!=null && openStair) {clearStair(mc,threat);return;}
        mc.options.keySneak.setPressed(false);
        Middle.use(mc,chestHit(mc));
        status="opening the third chest behind the stair wall";
    }
}
