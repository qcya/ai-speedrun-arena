package dev.aiarena.week113;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.block.Blocks;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.tag.FluidTags;
import net.minecraft.world.RayTraceContext;

final class EasternOpening {
    private static final double[][] COAST={{228.6,6.4},{225.3,6.3},{224.2,.4},{223.5,-6.1}};
    private static final double[][] APPROACH={{228,-9.2},{235,-11.7},{242.7,-8.86},{249,-10.1},{253.9,-11.3},{255.7,-11.55}};
    private static final double[][] RAVINE={{262.7,-15.3},{263.4,-20.6},{264.4,-23.5},{266.1,-26.2},{266.62,-29.0}};
    private static int phase,point,ticksInPhase,lastUse;
    static String status="";
    private static Entity caveTarget;
    private static boolean landedInBoat;
    private static int dismountTicks;
    private static boolean eating;
    private static int lostTargetTicks, caveBoatUse=-10, boatSettle;
    private static boolean pushingCaveBoat;
    private static BlockPos aimedSource;
    private static int aimTicks;
    static void reset(){lastBucketAttemptStep=-1;phase=0;point=0;ticksInPhase=0;lastUse=-10;castAge=castIndex=0;usedAt=-10;caveTarget=null;landedInBoat=false;dismountTicks=0;eating=false;lostTargetTicks=boatSettle=0;caveBoatUse=-10;pushingCaveBoat=false;aimedSource=null;aimTicks=0;}
    static void jumpTo(int phaseNumber){phase=phaseNumber;point=0;ticksInPhase=0;}
    private static void advance(){jumpTo(phase+1);}
    private static void face(MinecraftClient m, Vec3d point){Race.aim(m,point);m.gameRenderer.updateTargetedEntity(1);}
    private static boolean choose(MinecraftClient m,Item item){return Opening.select(m,item);}
    private static int count(MinecraftClient m,Item i){return m.player.inventory.count(i);}
    private static Entity target(MinecraftClient m,boolean enderman,double radius) {
        Entity best=null; double score=radius*radius;
        for(Entity e:m.world.getEntities()){
            if(!e.isAlive() || !(enderman ? e instanceof EndermanEntity:e instanceof BoatEntity))continue;
            double d=e.squaredDistanceTo(m.player);
            if(d<score){best=e;score=d;}
        }return best;
    }
    private static void move(MinecraftClient m,double x,double z,boolean sprint) {
        double l=Math.hypot(x-m.player.getX(),z-m.player.getZ());
        if(l<.12)return;
        Race.aim(m,new Vec3d(x,m.player.getEyeY()-.05,z));
        BastionApproach.move(m,x,z,false,sprint);
        m.options.keyJump.setPressed(sprint && l>2.5 && m.player.isOnGround() || m.player.horizontalCollision);
    }
    private static boolean follow(MinecraftClient m,double[][] path,double radius){
        while(point<path.length && Math.hypot(path[point][0]-m.player.getX(),path[point][1]-m.player.getZ())<radius)point++;
        if(point>=path.length){advance();return true;}
        move(m,path[point][0],path[point][1],true);return false;
    }
    private static boolean water(MinecraftClient m) {
        if(count(m,Items.WATER_BUCKET)>0){
            net.minecraft.server.network.ServerPlayerEntity self=ownServerPlayer(m);
            return self!=null && self.inventory.count(Items.WATER_BUCKET)>0;
        }
        BlockPos center=m.player.getBlockPos();
        for(int r=0;r<=4;r++)for(int x=-r;x<=r;x++)for(int z=-r;z<=r;z++)for(int y=-2;y<=1;y++){
            if(Math.max(Math.abs(x),Math.abs(z))!=r)continue;
            BlockPos p=center.add(x,y,z);
            if(!m.world.getFluidState(p).isStill() || !m.world.getFluidState(p).isIn(FluidTags.WATER))continue;
            Vec3d q=Opening.sourcePoint(m,p,false);if(q!=null && choose(m,Items.BUCKET)){
                face(m,q);
                if(Opening.fluidRay(m).getBlockPos().equals(p) && serverCanScoop(m,p,false) && ticksInPhase-lastUse>=4){m.interactionManager.interactItem(m.player,m.world,Hand.MAIN_HAND); lastUse=ticksInPhase;}
                return false;
            }
        }
        move(m,222.15,-7.85,false);
        return false;
    }
    private static void useBoat(MinecraftClient m){
        if(!choose(m,Items.OAK_BOAT))return;
        net.minecraft.server.network.ServerPlayerEntity self=ownServerPlayer(m);
        if(self==null || self.getMainHandStack().getItem()!=Items.OAK_BOAT)return;
        Vec3d eye=m.player.getCameraPosVec(1);
        double dist=eye.y-45.0;
        double tx=262.64;
        double tz=-12.67;
        face(m,new Vec3d(tx,44.8,tz));
        if(dist<=5 && dist>=0) m.interactionManager.interactItem(m.player,m.world,Hand.MAIN_HAND);
    }
    private static void firstDrop(MinecraftClient m) {
        if(m.player.hasVehicle()){landedInBoat=true;m.options.keySneak.setPressed(true);return;}
        if(landedInBoat && m.player.getY()<47){
            m.options.keySneak.setPressed(true);
            if(++dismountTicks>=5)advance();
            return;
        }
        if(m.player.getY()<47 && m.player.isOnGround()){advance();return;}
        if(m.player.getY()>57 && !choose(m,Items.OAK_BOAT))return;
        Vec3d landing=new Vec3d(262.30,45,-12.88);
        if(m.player.getY()>49){
            move(m,landing.x,landing.z,false);
            if(m.player.getY()>56 && m.player.getX()<257.6) m.options.keySprint.setPressed(true);
        }
        if(!m.player.isOnGround() && m.player.getY()<55) {
            m.player.pitch=77;
            if(Math.hypot(landing.x-m.player.getX(),landing.z-m.player.getZ())>.55)
                BastionApproach.move(m,landing.x,landing.z,false,false);
            Entity boat=target(m,false,4.5);
            if(boat==null)useBoat(m);
            else {face(m,boat.getBoundingBox().getCenter());m.interactionManager.interactEntity(m.player,boat,Hand.MAIN_HAND);}
        }
    }
    private static boolean caveBoatSlot(MinecraftClient m) {
        int slot=m.player.inventory.getStack(5).getItem()==Items.DIAMOND_SWORD?6:5;
        return Hotbar.select(m,Items.OAK_BOAT,slot);
    }

    private static boolean caveHasPassenger(MinecraftClient m,Entity mob) {
        if(!(mob.getVehicle() instanceof BoatEntity))return false;
        net.minecraft.server.network.ServerPlayerEntity self=ownServerPlayer(m);
        Entity serverMob=self==null?null:self.getEntityWorld().getEntityById(mob.getEntityId());
        return serverMob instanceof EndermanEntity && serverMob.getVehicle() instanceof BoatEntity;
    }

    private static void replaceCaveBoat(MinecraftClient m,Entity mob) {
        if(!caveBoatSlot(m))return;
        net.minecraft.server.network.ServerPlayerEntity self=ownServerPlayer(m);
        if(self==null || self.getMainHandStack().getItem()!=Items.OAK_BOAT)return;
        double dx=m.player.getX()-mob.getX(),dz=m.player.getZ()-mob.getZ(),distance=Math.hypot(dx,dz);
        if(distance<1.45 || distance>3.4) {
            move(m,261.54,-12.60,false);
            return;
        }
        // Place on the floor just in front of the Enderman. At its natural spawn
        // this is (263.02,45,-12.52), within the empirical capture radius.
        Vec3d floor=new Vec3d(mob.getX()+.48*dx/distance,mob.getY()-.015,mob.getZ()+.48*dz/distance);
        face(m,floor);
        Vec3d eye=self.getCameraPosVec(1),end=eye.add(self.getRotationVec(1).multiply(5));
        BlockHitResult h=self.world.rayTrace(new RayTraceContext(eye,end,RayTraceContext.ShapeType.OUTLINE,
                RayTraceContext.FluidHandling.ANY,self));
        if(h.getType()!=HitResult.Type.BLOCK || h.getSide()!=Direction.UP
                || h.getPos().squaredDistanceTo(floor)>.055 || ticksInPhase-caveBoatUse<4)return;
        if(m.interactionManager.interactItem(m.player,m.world,Hand.MAIN_HAND).isAccepted()) {
            caveBoatUse=ticksInPhase;
            boatSettle=0;
        }
    }

    private static void catchCaveMob(MinecraftClient m,Entity mob) {
        if(ticksInPhase>100 || mob.squaredDistanceTo(m.player)>55 || Math.abs(mob.getY()-45)>2) {
            advance();return;
        }
        Entity boat=target(m,false,6);
        if(boat==null) {
            for(Entity e:m.world.getEntities())if(e instanceof ItemEntity && e.isAlive()
                    && ((ItemEntity)e).getStack().getItem()==Items.OAK_BOAT && e.squaredDistanceTo(m.player)<35) {
                move(m,e.getX(),e.getZ(),false);return;
            }
            if(count(m,Items.OAK_BOAT)>0)replaceCaveBoat(m,mob);
            else if(ticksInPhase>35)advance();
            return;
        }
        if(mob.hasVehicle() || ticksInPhase-caveBoatUse<9)return; // Allow passenger packets to arrive.
        double dx=mob.getX()-boat.getX(),dz=mob.getZ()-boat.getZ(),gap=Math.hypot(dx,dz);
        if(ticksInPhase<24 && gap>.10 && gap<2.2) {
            // Step behind the existing clutch boat and push towards the mob before breaking it.
            double sideX=boat.getX()-dx/gap*.91,sideZ=boat.getZ()-dz/gap*.91;
            if(!pushingCaveBoat && Math.hypot(m.player.getX()-sideX,m.player.getZ()-sideZ)<.45)
                pushingCaveBoat=true;
            move(m,pushingCaveBoat?mob.getX():sideX,pushingCaveBoat?mob.getZ():sideZ,false);return;
        }
        if(++boatSettle<5)return;
        // A pick breaks the empty boat without the sword's sweeping hit on the uncaptured mob.
        if(!choose(m,Items.IRON_PICKAXE))return;
        if(m.player.squaredDistanceTo(boat)>6.5) {move(m,boat.getX(),boat.getZ(),false);return;}
        face(m,boat.getBoundingBox().getCenter());
        if(m.crosshairTarget instanceof EntityHitResult && ((EntityHitResult)m.crosshairTarget).getEntity()==boat
                && m.player.getAttackCooldownProgress(.5f)>.95) {
            m.interactionManager.attackEntity(m.player,boat);
            m.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private static void fight(MinecraftClient m){
        if(count(m,Items.ENDER_PEARL)>0){advance();return;}
        for(Entity e:m.world.getEntities())if(e instanceof ItemEntity && e.isAlive()
                && ((ItemEntity)e).getStack().getItem()==Items.ENDER_PEARL && e.squaredDistanceTo(m.player)<100) {
            move(m,e.getX(),e.getZ(),false);return;
        }
        if(caveTarget==null && ticksInPhase<35)caveTarget=target(m,true,8);
        Entity mob=caveTarget;
        if(mob==null || !mob.isAlive() || m.world.getEntityById(mob.getEntityId())!=mob) {
            if(++lostTargetTicks>26)advance();
            return;
        }
        if(!caveHasPassenger(m,mob)){catchCaveMob(m,mob);return;}
        if(ticksInPhase>205){advance();return;}
        if(!Hotbar.select(m,Items.DIAMOND_SWORD,7))return;
        if(m.player.squaredDistanceTo(mob)>7.7){move(m,mob.getX(),mob.getZ(),false);return;}
        face(m,mob.getPos().add(0,1.48,0));
        if(m.crosshairTarget instanceof EntityHitResult && ((EntityHitResult)m.crosshairTarget).getEntity()==mob
                && m.player.getAttackCooldownProgress(.5f)>.97){
            m.interactionManager.attackEntity(m.player,mob);m.player.swingHand(Hand.MAIN_HAND);
        }
    }
    private static boolean eatAfterFall(MinecraftClient m) {
        if(m.player.getHungerManager().getFoodLevel()>=20 || m.player.getHealth()>=14 || count(m,Items.COOKED_SALMON)==0) {
            if(eating)m.options.keyUse.setPressed(false);
            eating=false;return false;
        }
        if(!choose(m,Items.COOKED_SALMON))return true;
        net.minecraft.server.network.ServerPlayerEntity self=ownServerPlayer(m);
        if(self==null || self.getMainHandStack().getItem()!=Items.COOKED_SALMON)return true;
        if(!m.player.isUsingItem())m.interactionManager.interactItem(m.player,m.world,Hand.MAIN_HAND);
        eating=true;m.options.keyUse.setPressed(true);return true;
    }
    private static void takeBoat(MinecraftClient m){
        if(count(m,Items.OAK_BOAT)>0 || ticksInPhase>45){advance();return;}
        for(Entity e:m.world.getEntities())if(e instanceof ItemEntity && ((ItemEntity)e).getStack().getItem()==Items.OAK_BOAT && e.squaredDistanceTo(m.player)<80){move(m,e.getX(),e.getZ(),false);return;}
        Entity b=target(m,false,7);
        if(b==null){advance();return;}
        if(m.player.squaredDistanceTo(b)>7){move(m,b.getX(),b.getZ(),false);return;}
        Item tool=caveTarget!=null && caveTarget.isAlive() && !caveTarget.hasVehicle() ? Items.IRON_PICKAXE : Items.DIAMOND_SWORD;
        if(!choose(m,tool))return;
        face(m,b.getBoundingBox().getCenter());
        if(m.crosshairTarget instanceof EntityHitResult && ((EntityHitResult)m.crosshairTarget).getEntity()==b && m.player.getAttackCooldownProgress(.5f)>.95){
            m.interactionManager.attackEntity(m.player,b);m.player.swingHand(Hand.MAIN_HAND);
        }
    }
    static boolean tick(MinecraftClient m){
        Race.stop(m);m.options.keyAttack.setPressed(false);ticksInPhase++;
        status="eastern ravine "+phase+" waypoint "+point;
        if(phase>=4 && m.player.getVehicle()!=null){
            m.options.keySneak.setPressed(true);return true;
        }
        if(Hotbar.tick(m))return true;
        switch(phase){
          case 0:follow(m,COAST,1.35);break;
          case 1:if(water(m)) advance();break;
          case 2:if(choose(m,Items.OAK_BOAT))follow(m,APPROACH,.85);break;
          case 3:firstDrop(m);break;
          case 4:fight(m);break;
          case 5:takeBoat(m);break;
          case 6:if(eatAfterFall(m))break;if(count(m,Items.WATER_BUCKET)==0 || choose(m,Items.WATER_BUCKET))follow(m,RAVINE,.8);break;
          default:stageRavine(m);break;
        }return true;
    }
    private static int castIndex,castAge,usedAt=-10;
    private static int lastBucketAttemptStep=-1;
    private static final int DIG=0,PLUG=1,WATER=2,DRAIN=3,LAVA=4,POUR=5,STAND=6,LIGHT=7,WAIT=8;
    private static final int[][] CAST={
        {DIG,263,16,-33},{DIG,263,15,-33},{DIG,263,14,-33},{DIG,263,13,-33},
        {PLUG,266,10,-31},{WATER,265,10,-31},{DIG,266,10,-31},{WAIT,266,9,-31},
        {DRAIN,265,10,-31},{STAND,265,11,-32},
        {DIG,266,12,-31},{DIG,266,13,-31},{DIG,266,13,-30},{WATER,266,13,-30},
        {DIG,267,12,-31},{DIG,267,11,-31},
        {LAVA,267,10,-32},{POUR,267,11,-31},{LAVA,267,10,-33},{POUR,267,12,-31},
        {LAVA,266,10,-33},{POUR,266,13,-31},{LAVA,264,10,-32},{DIG,265,13,-31},
        {DIG,265,13,-30},{POUR,264,11,-31},{LAVA,264,10,-33},{POUR,264,12,-31},
        {LAVA,265,10,-33},{POUR,265,13,-31},{DIG,266,12,-30},{DRAIN,266,13,-30},{DIG,265,10,-31},{LIGHT,266,9,-31}
    };
    private static final BlockPos LEFT_CHANNEL = new BlockPos(265,13,-30);
    private static final BlockPos LEFT_SPILL = new BlockPos(264,13,-30);

    private static net.minecraft.server.network.ServerPlayerEntity ownServerPlayer(MinecraftClient m) {
        return m.getServer()==null ? null : m.getServer().getPlayerManager().getPlayer(m.player.getUuid());
    }

    private static Vec3d visibleSource(MinecraftClient m,BlockPos at,boolean lava) {
        net.minecraft.server.network.ServerPlayerEntity self=ownServerPlayer(m);
        if(self==null)return null;
        net.minecraft.fluid.FluidState fluid=self.world.getFluidState(at);
        if(!fluid.isStill() || !fluid.isIn(lava?FluidTags.LAVA:FluidTags.WATER))return null;
        return Opening.sourcePoint(m,at,lava);
    }

    private static boolean serverCanScoop(MinecraftClient m,BlockPos at,boolean lava) {
        net.minecraft.server.network.ServerPlayerEntity self=ownServerPlayer(m);
        if(self==null || self.getMainHandStack().getItem()!=Items.BUCKET)return false;
        Vec3d eye=self.getCameraPosVec(1), end=eye.add(self.getRotationVec(1).multiply(5));
        BlockHitResult hit=self.world.rayTrace(new RayTraceContext(eye,end,RayTraceContext.ShapeType.OUTLINE,
                RayTraceContext.FluidHandling.SOURCE_ONLY,self));
        net.minecraft.fluid.FluidState fluid=self.world.getFluidState(at);
        return hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(at) && fluid.isStill()
                && fluid.isIn(lava?FluidTags.LAVA:FluidTags.WATER);
    }

    private static boolean bucket(MinecraftClient m,BlockPos at,Item full,boolean collect) {
        boolean lava=full==Items.LAVA_BUCKET;
        net.minecraft.server.network.ServerPlayerEntity self=ownServerPlayer(m);
        if(collect && count(m,full)>0) {
            if(lava)return self!=null && self.getMainHandStack().getItem()==full;
            return castAge-usedAt>=5 && self!=null && self.inventory.count(full)>0;
        }
        if(!collect && count(m,Items.BUCKET)>0) {
            if(lava)return self!=null && self.getMainHandStack().getItem()==Items.BUCKET
                    && self.world.getBlockState(at).getBlock()==Blocks.OBSIDIAN
                    && m.world.getBlockState(at).getBlock()==Blocks.OBSIDIAN;
            return castAge-usedAt>=6 && m.world.getFluidState(at).isIn(FluidTags.WATER);
        }
        // Retry the same use at the existing rate. A confirmed transaction may aim at the next cell.
        if((!lava || lastBucketAttemptStep==castIndex) && castAge-usedAt<6)return false;
        Item held=collect?Items.BUCKET:full;
        if(!choose(m,held) || (lava && (self==null || self.getMainHandStack().getItem()!=held)))return false;
        if(collect) {
            Vec3d q=visibleSource(m,at,full==Items.LAVA_BUCKET);
            if(q==null && full==Items.LAVA_BUCKET){
                for(int x=264;x<=268 && q==null;x++)for(int z=-34;z<=-32 && q==null;z++){
                    BlockPos candidate=new BlockPos(x,10,z);
                    Vec3d visible=visibleSource(m,candidate,true);
                    if(visible!=null){at=candidate;q=visible;}
                }
            }
            if(q==null)return false;
            face(m,q);
            if(!at.equals(aimedSource)){aimedSource=at;aimTicks=0;}
            if(++aimTicks<3)return false;
            BlockHitResult ray=Opening.fluidRay(m);
            if(ray.getType()!=HitResult.Type.BLOCK || !at.equals(ray.getBlockPos())
                    || !serverCanScoop(m,at,full==Items.LAVA_BUCKET))return false;
            m.interactionManager.interactItem(m.player,m.world,Hand.MAIN_HAND);
            lastBucketAttemptStep=castIndex;usedAt=castAge;aimTicks=0;
        } else if(Opening.placeOnVisibleFace(m,at,full,true)) {
            lastBucketAttemptStep=castIndex;usedAt=castAge;
        }
        return false;
    }
    private static void stageRavine(MinecraftClient m){
        castAge++;
        if(phase==7) {
            status="descending the eastern ravine with the water bucket";
            if(m.player.getY()>20 || !m.player.isOnGround() && !m.player.isTouchingWater()){
                if(m.player.getY()>30)move(m,266.55,-31.75,false);
                if(choose(m,Items.WATER_BUCKET)){
                    m.player.pitch=89;m.gameRenderer.updateTargetedEntity(1);
                    if(m.crosshairTarget instanceof BlockHitResult && m.crosshairTarget.getType()==HitResult.Type.BLOCK){
                        BlockHitResult floor=(BlockHitResult)m.crosshairTarget;
                        if(floor.getSide()==Direction.UP && floor.getBlockPos().getY()<=19 && m.player.getVelocity().y<-.28)
                            m.interactionManager.interactItem(m.player,m.world,Hand.MAIN_HAND);
                    }
                }
            } else if(count(m,Items.WATER_BUCKET)==0)water(m);else advance();
            return;
        }
        if(phase==8){
            status="aligning over the eastern lava pocket";
            double x=263.35,z=-32.3,dist=Math.hypot(x-m.player.getX(),z-m.player.getZ());
            if(dist<.17 && m.player.isOnGround()){advance();return;}
            if(dist>.11){Race.aim(m,new Vec3d(x,m.player.getEyeY(),z));BastionApproach.move(m,x,z,dist<.8,false);}
            return;
        }
        if(castIndex>=CAST.length){
            status="waiting inside the eastern Nether portal";Race.advanceTo(1335);
            if(m.world.getBlockState(m.player.getBlockPos()).getBlock()!=Blocks.NETHER_PORTAL)
                move(m,265.86,-30.35,false);
            return;
        }
        if(castIndex>=14 && castIndex<30 && Math.hypot(m.player.getX()-265.49,m.player.getZ()+31.23)>.43){
            double tx=265.49,tz=-31.23;
            Race.aim(m,new Vec3d(tx,m.player.getEyeY(),tz));
            BastionApproach.move(m,tx,tz,false,false);
            status="regaining footing above the lava";return;
        }
        if(castIndex==30 && (m.player.getY()>10.25 ||
                Math.hypot(m.player.getX()-265.86,m.player.getZ()+30.5)>.35)){
            status="stepping into the empty portal frame to recover the water";
            Race.aim(m,new Vec3d(265.86,m.player.getEyeY(),-30.50));
            BastionApproach.move(m,265.86,-30.50,false,false);
            return;
        }
        int[] step=CAST[castIndex];int action=step[0];BlockPos at=new BlockPos(step[1],step[2],step[3]);boolean done=false;
        status="eastern lava cast "+(castIndex+1)+"/"+CAST.length+" at "+at.toShortString();
        switch(action){
          case DIG:
            if(at.equals(LEFT_CHANNEL)) {
                // The outside drop wets the remaining three source cells from above.
                // Seal it before opening the straight chute beside the left portal upright.
                net.minecraft.server.network.ServerPlayerEntity self=ownServerPlayer(m);
                if(self==null || self.world.getBlockState(LEFT_SPILL).getBlock()!=Blocks.DIRT) {
                    if(m.world.getBlockState(LEFT_SPILL).getBlock()!=Blocks.DIRT)
                        Opening.place(m,LEFT_SPILL,Items.DIRT);
                    break;
                }
            }
            done=m.world.getBlockState(at).getMaterial().isReplaceable();
            if(!done && choose(m,m.world.getBlockState(at).getBlock()==Blocks.DIRT && count(m,Items.WOODEN_SHOVEL)>0 ? Items.WOODEN_SHOVEL:Items.IRON_PICKAXE))
                m.options.keyAttack.setPressed(Opening.target(m,at)!=null);
            break;
          case PLUG:
            if(m.player.getY()>11.1 || m.player.getX()<263.77 || m.player.getZ() < -31.17) {
                double tx=263.86,tz=-31.02;
                Race.aim(m,new Vec3d(tx,m.player.getEyeY(),tz));BastionApproach.move(m,tx,tz,false,false);break;
            }
            done=m.world.getBlockState(at).getBlock()==Blocks.DIRT;if(!done)Opening.place(m,at,Items.DIRT);break;
          case WATER:case DRAIN:case LAVA:case POUR:
            done=bucket(m,at,action==LAVA||action==POUR?Items.LAVA_BUCKET:Items.WATER_BUCKET,action==DRAIN||action==LAVA);break;
          case WAIT:
            done=m.world.getBlockState(at).getBlock()==Blocks.OBSIDIAN;
            if(!done && m.world.getBlockState(at.up()).getBlock()==Blocks.COBBLESTONE && choose(m,Items.IRON_PICKAXE))
                m.options.keyAttack.setPressed(Opening.target(m,at.up())!=null);
            break;
          case STAND:{
            double x=265.50,z=-31.25,delta=Math.hypot(x-m.player.getX(),z-m.player.getZ());
            done=delta<.24 && m.player.isOnGround() && m.player.getY()<11.6;
            if(!done){Race.aim(m,new Vec3d(x,m.player.getEyeY(),z));BastionApproach.move(m,x,z,delta<.8,false);}break;
          }
          case LIGHT:
            done=m.world.getBlockState(at.up()).getBlock()==Blocks.NETHER_PORTAL;
            if(!done && choose(m,Items.FLINT_AND_STEEL)){
                BlockHitResult h=Middle.aim(m,at,Direction.UP);
                if(h!=null && castAge-usedAt>=4){
                    if(m.interactionManager.interactBlock(m.player,m.world,Hand.MAIN_HAND,h).isAccepted())
                        m.player.swingHand(Hand.MAIN_HAND);
                    usedAt=castAge;
                }
            }break;
        }
        if(done){castIndex++;m.options.keyAttack.setPressed(false);}
    }

}
