package dev.aiarena.week113;

import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.recipe.Recipe;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Native survival controls for the buried treasure and the flooded ravine opening. */
public final class Opening {
    private static final BlockPos TREASURE = new BlockPos(217, 49, 25);
    private static final Item[] SUPPLIES = {Items.IRON_INGOT, Items.DIAMOND, Items.TNT,
            Items.COOKED_SALMON, Items.COOKED_COD, Items.GOLD_INGOT};
    private static boolean eastern;
    private static boolean looted,shieldOpening,ravineInventory,preparedGold;
    private static int age;
    private static Item shovel=Items.GOLDEN_SHOVEL;
    public static String status = "following coast";

    private Opening() {}

    public static void reset() {
        net.minecraft.server.integrated.IntegratedServer server=MinecraftClient.getInstance().getServer();
        eastern=server!=null && java.nio.file.Files.isRegularFile(
                server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("astra-opening-east"));
        shieldOpening=server!=null&&java.nio.file.Files.isRegularFile(
                server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("astra-opening-shield"));
        shovel=server!=null && java.nio.file.Files.isRegularFile(
                server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("astra-opening-save-gold"))
                ? Items.WOODEN_SHOVEL : Items.GOLDEN_SHOVEL;
        EasternOpening.reset();looted = false; ravineInventory=false; preparedGold=false; doorAimAge=-1;previousDoor=null;doorFacingTicks=0;firstTree=0;shieldSwapAge=-30;landStep=0; blastAge=0; boatPoint=0; divePoint=0; castStep=0; castStarted=0; anchorTicks=0; bucketAimAge=-1;bucketUseAge=-30;bucketTicks=0;lastBucketGoal=null; boarded=false; launched=false; table=null; gravelPos=null; tableSupport=null;
        age = 0; extraSupportAge=-1;lowMold=false;inWell=false; stayBelow=false;riseFromWell=false;seekingAir=false; requestedRecipe=null; recipeAge=-30;
        status = "following coast";
    }

    static Item shovelOrPick(MinecraftClient mc) {
        for (Item tool : new Item[]{Items.GOLDEN_SHOVEL, Items.IRON_SHOVEL, Items.WOODEN_SHOVEL})
            if (mc.player.inventory.count(tool) > 0) return tool;
        return Items.IRON_PICKAXE;
    }

    static BlockHitResult target(MinecraftClient mc, BlockPos pos) {
        Vec3d eye=mc.player.getCameraPosVec(1),point=Vec3d.ofCenter(pos);
        BlockHitResult initial=mc.world.rayTrace(new net.minecraft.world.RayTraceContext(eye,point,
            net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,net.minecraft.world.RayTraceContext.FluidHandling.NONE,mc.player));
        if(initial.getType()!=HitResult.Type.BLOCK||!initial.getBlockPos().equals(pos)){
            search:for(net.minecraft.util.math.Box box:mc.world.getBlockState(pos).getOutlineShape(mc.world,pos).getBoundingBoxes())
                for(double y:new double[]{.5,.9,.1})for(double x:new double[]{.5,.9,.1})for(double z:new double[]{.5,.9,.1}){
                    Vec3d test=new Vec3d(pos.getX()+box.minX+(box.maxX-box.minX)*x,
                        pos.getY()+box.minY+(box.maxY-box.minY)*y,pos.getZ()+box.minZ+(box.maxZ-box.minZ)*z);
                    BlockHitResult ray=mc.world.rayTrace(new net.minecraft.world.RayTraceContext(eye,test,
                       net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,net.minecraft.world.RayTraceContext.FluidHandling.NONE,mc.player));
                    if(ray.getType()==HitResult.Type.BLOCK&&ray.getBlockPos().equals(pos)){point=test;break search;}
                }
        }
        Race.aim(mc,point);
        mc.gameRenderer.updateTargetedEntity(1.0f);
        if (!(mc.crosshairTarget instanceof BlockHitResult) || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return null;
        BlockHitResult hit = (BlockHitResult)mc.crosshairTarget;
        return pos.equals(hit.getBlockPos()) ? hit : null;
    }

    private static boolean wanted(Item i) {
        for (Item s : SUPPLIES) if (i == s) return true;
        return false;
    }

    private static int count(MinecraftClient mc, Item i) {
        int result = 0;
        for (ItemStack s : mc.player.inventory.main) if (s.getItem() == i) result += s.getCount();
        return result;
    }

    private static final BlockPos TNT_SITE = new BlockPos(230,72,6);
    private static final BlockPos PLATE = new BlockPos(230,72,7);
    private static final BlockPos TABLE = new BlockPos(228,69,5);
    private static int landStep, firstTree,shieldSwapAge,blastAge,boatPoint;
    private static boolean boarded, launched;
    private static BlockPos table, gravelPos, tableSupport;

    static boolean select(MinecraftClient mc, Item item) {
        if(ravineInventory) {
            int slot=item==Items.OAK_DOOR?5:item==Items.DIRT?6:item==Items.IRON_PICKAXE?1:
                    item==Items.FLINT_AND_STEEL?4:item==shovel?3:7;
            return Hotbar.select(mc,item,slot);
        }
        for(int i=0;i<36;i++){
            if(mc.player.inventory.main.get(i).getItem()!=item)continue;
            if(i<9){mc.player.inventory.selectedSlot=i;return true;}
            if(mc.player.currentScreenHandler!=mc.player.playerScreenHandler)return false;
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,i,7,SlotActionType.SWAP,mc.player);
            mc.player.inventory.selectedSlot=7;return true;
        }
        return false;
    }

    static boolean hitFace(MinecraftClient mc,BlockPos support,Direction side,Vec3d exact) {
        if(mc.player.getCameraPosVec(1).squaredDistanceTo(exact)>20.15)return false;
        Race.aim(mc,exact);mc.gameRenderer.updateTargetedEntity(1);
        if(!(mc.crosshairTarget instanceof BlockHitResult)||mc.crosshairTarget.getType()!=HitResult.Type.BLOCK)return false;
        BlockHitResult hit=(BlockHitResult)mc.crosshairTarget;
        if(!hit.getBlockPos().equals(support)||hit.getSide()!=side)return false;
        mc.interactionManager.interactBlock(mc.player,mc.world,Hand.MAIN_HAND,hit);mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }
    static boolean place(MinecraftClient mc,BlockPos to,Item item) {
        return placeOnVisibleFace(mc,to,item,false);
    }

    static boolean placeOnVisibleFace(MinecraftClient mc,BlockPos to,Item item,boolean bucket) {
        if(!select(mc,item))return false;
        Vec3d eye=mc.player.getCameraPosVec(1);
        final double[] edge={.50,.14,.86};
        for(Direction side:new Direction[]{Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.DOWN}){
            BlockPos support=to.offset(side.getOpposite());
            if(mc.world.getBlockState(support).getMaterial().isReplaceable())continue;
            // Thin door panels can be the only supporting face under open water.
            // Their faces can lie inside their block, unlike a full cube's six faces.
            for(net.minecraft.util.math.Box box:mc.world.getBlockState(support).getOutlineShape(mc.world,support).getBoundingBoxes())
            for(double a:edge)for(double b:edge){
                double u=side.getAxis()==Direction.Axis.X?(side.getOffsetX()>0?.999:.001):a;
                double v=side.getAxis()==Direction.Axis.Y?(side.getOffsetY()>0?.999:.001):(side.getAxis()==Direction.Axis.X?a:b);
                double w=side.getAxis()==Direction.Axis.Z?(side.getOffsetZ()>0?.999:.001):b;
                Vec3d point=new Vec3d(support.getX()+box.minX+(box.maxX-box.minX)*u,
                    support.getY()+box.minY+(box.maxY-box.minY)*v,support.getZ()+box.minZ+(box.maxZ-box.minZ)*w);
                if(eye.squaredDistanceTo(point)>20.15)continue;
                BlockHitResult ray=mc.world.rayTrace(new net.minecraft.world.RayTraceContext(eye,point,
                        net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,net.minecraft.world.RayTraceContext.FluidHandling.NONE,mc.player));
                if(ray.getType()!=HitResult.Type.BLOCK || !ray.getBlockPos().equals(support)||ray.getSide()!=side)continue;
                if(!bucket)return hitFace(mc,support,side,point);
                Race.aim(mc,point);mc.gameRenderer.updateTargetedEntity(1);
                if(mc.crosshairTarget instanceof BlockHitResult && mc.crosshairTarget.getType()==HitResult.Type.BLOCK){
                    BlockHitResult h=(BlockHitResult)mc.crosshairTarget;
                    if(h.getBlockPos().equals(support)&&h.getSide()==side && steadyAim(mc,to) && age-bucketUseAge>=6){
                        bucketUseAge=age;mc.interactionManager.interactItem(mc.player,mc.world,Hand.MAIN_HAND);return true;
                    }
                }
                return false;
            }
        }
        return false;
    }
    // A door may be placed in a swimmer's current cell when its thin collision slab
    // is on the far edge. Choose a real visible top-face point with the recorded heading.
    private static int doorAimAge,doorFacingTicks;
    private static BlockPos previousDoor;
    private static Direction previousFacing;
    private static boolean door(MinecraftClient mc,BlockPos to,float referenceYaw){
        if(!select(mc,Items.OAK_DOOR))return false;
        BlockPos support=to.down();Vec3d eye=mc.player.getCameraPosVec(1),best=null;
        double score=Double.POSITIVE_INFINITY;
        for(double u=.03;u<1;u+=.09)for(double v=.03;v<1;v+=.09) {
            Vec3d point=new Vec3d(support.getX()+u,support.getY()+.999,support.getZ()+v);
            if(eye.squaredDistanceTo(point)>20)continue;
            float yaw=(float)Math.toDegrees(Math.atan2(eye.x-point.x,point.z-eye.z));
            float difference=Math.abs(MathHelper.wrapDegrees(yaw-referenceYaw));
            if(difference>=score)continue;
            BlockHitResult ray=mc.world.rayTrace(new net.minecraft.world.RayTraceContext(eye,point,
                    net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,net.minecraft.world.RayTraceContext.FluidHandling.NONE,mc.player));
            if(ray.getType()==HitResult.Type.BLOCK&&ray.getBlockPos().equals(support)&&ray.getSide()==Direction.UP){best=point;score=difference;}
        }
        if(best==null)return false;
        if(castStep==2||castStep==3||castStep==16){
            // The server chooses the panel orientation from the player's synchronized
            // heading, while the placement packet supplies only the clicked top face.
            Race.aim(mc,best);
            Direction facing=Direction.fromRotation(mc.player.yaw);
            if(facing!=Direction.fromRotation(referenceYaw)){
                doorFacingTicks=0;return false;
            }
            // The clicked face and hit position travel with the placement packet.
            // The server uses only the horizontal facing quadrant for door direction.
            // A swimmer may bob or drift while retaining that synchronized quadrant.
            boolean same=to.equals(previousDoor)&&previousFacing==facing&&doorAimAge==age-1;
            doorFacingTicks=same?doorFacingTicks+1:1;previousDoor=to;previousFacing=facing;doorAimAge=age;
            if(doorFacingTicks<3)return false;
        }
        return hitFace(mc,support,Direction.UP,best);
    }
    static boolean useBlock(MinecraftClient mc,BlockPos pos) {
        BlockHitResult hit=target(mc,pos);
        if(hit==null)return false;
        mc.interactionManager.interactBlock(mc.player,mc.world,Hand.MAIN_HAND,hit);
        return true;
    }
    private static void align(MinecraftClient mc,double x,double z){
        Race.stop(mc);
        double dx=x-mc.player.getX(),dz=z-mc.player.getZ(),r=Math.toRadians(mc.player.yaw);
        double f=-Math.sin(r)*dx+Math.cos(r)*dz,l=Math.cos(r)*dx+Math.sin(r)*dz;
        double threshold=.30*Math.hypot(dx,dz);
        mc.options.keyForward.setPressed(f>threshold);mc.options.keyBack.setPressed(f< -threshold);
        mc.options.keyLeft.setPressed(l>threshold);mc.options.keyRight.setPressed(l< -threshold);
        mc.options.keySneak.setPressed(true);
    }
    private static void walk(MinecraftClient mc,Vec3d point,boolean run) {
        Race.stop(mc);Race.aim(mc,new Vec3d(point.x,mc.player.getEyeY(),point.z));
        double dx=point.x-mc.player.getX(),dz=point.z-mc.player.getZ();
        float target=(float)Math.toDegrees(Math.atan2(-dx,dz));
        if(Math.abs(MathHelper.wrapDegrees(target-mc.player.yaw))<55) {
            mc.options.keyForward.setPressed(true);
            mc.options.keySprint.setPressed(run);
            if(mc.player.horizontalCollision)mc.options.keyJump.setPressed(true);
        }
    }
    private static String requestedRecipe;
    private static int recipeAge;
    private static boolean craft(MinecraftClient mc,String recipe, Item result,int goal,boolean workstation,boolean craftAll) {
        if(count(mc,result)>=goal)return true;
        status="crafting "+recipe;
        Race.stop(mc);
        if(workstation){
            if(!(mc.player.currentScreenHandler instanceof CraftingScreenHandler)){
                if(mc.currentScreen!=null){mc.player.closeHandledScreen();return false;}
                if(table!=null)useBlock(mc,table);
                return false;
            }
        } else if(!(mc.currentScreen instanceof InventoryScreen)){
            if(mc.currentScreen!=null){mc.player.closeHandledScreen();return false;}
            mc.openScreen(new InventoryScreen(mc.player));return false;
        }
        ScreenHandler sh=mc.player.currentScreenHandler;
        if(age%2!=0)return false;
        if(sh.getSlot(0).getStack().getItem()==result){
            mc.interactionManager.clickSlot(sh.syncId,0,0,SlotActionType.QUICK_MOVE,mc.player);
            return false;
        }
        java.util.Optional<? extends Recipe<?>> r=mc.world.getRecipeManager().get(new Identifier(recipe));
        if(r.isPresent()&&mc.player.getRecipeBook().contains(r.get()) && (!recipe.equals(requestedRecipe)||age-recipeAge>=22)){
            // Recipe-book clicks are handled by the server. Repeating a still-pending click
            // adds another recipe's ingredients, so one pressure plate can spend four iron.
            mc.interactionManager.clickRecipe(sh.syncId,r.get(),craftAll);
            // The door recipe makes three per set. Queue precisely two sets in
            // the native recipe book, so a single shift-click collects all six.
            if(result==Items.OAK_DOOR&&goal-count(mc,result)>=6)mc.interactionManager.clickRecipe(sh.syncId,r.get(),false);
            requestedRecipe=recipe;recipeAge=age;
        }
        return false;
    }

    private static boolean land(MinecraftClient mc,int tick) {
        if(landStep==0&&!shieldOpening){
            Race.cap(351);
            if(tick<270)return false;
            if(!craft(mc,"heavy_weighted_pressure_plate",Items.HEAVY_WEIGHTED_PRESSURE_PLATE,1,false,false))return true;
            if(mc.currentScreen!=null)mc.player.closeHandledScreen();
            landStep=1;
        }
        if(landStep==0) {
            // One oak log yields a wooden trigger, preserving the iron for a shield.
            Race.cap(355);
            if(tick<331)return false;
            BlockPos trunk=new BlockPos(233,72,8);
            if(firstTree==0){
                if(count(mc,Items.OAK_LOG)>0){firstTree=1;}
                else if(mc.world.getBlockState(trunk).getBlock()==Blocks.OAK_LOG){
                    if(mc.player.getCameraPosVec(1).squaredDistanceTo(Vec3d.ofCenter(trunk))>17.8)return false;
                    status="harvesting oak for shield opening";Race.stop(mc);
                    if(target(mc,trunk)!=null){
                        mc.options.keyAttack.setPressed(true);
                        mc.options.keyForward.setPressed(mc.player.squaredDistanceTo(Vec3d.ofCenter(trunk))>2.6);
                    }else walk(mc,new Vec3d(232.0,72,8.7),false);
                    return true;
                }else{
                    for(net.minecraft.entity.Entity e:mc.world.getEntities())if(e instanceof net.minecraft.entity.ItemEntity &&
                            ((net.minecraft.entity.ItemEntity)e).getStack().getItem()==Items.OAK_LOG && mc.player.squaredDistanceTo(e)<49){
                        status="collecting first oak log";walk(mc,e.getPos(),false);return true;
                    }
                    Race.stop(mc);return true;
                }
            }
            if(firstTree==1){
                if(!craft(mc,"oak_planks",Items.OAK_PLANKS,4,false,false))return true;
                firstTree=2;
            }
            if(!craft(mc,"oak_pressure_plate",Items.OAK_PRESSURE_PLATE,1,false,false))return true;
            if(mc.currentScreen!=null)mc.player.closeHandledScreen();landStep++;
        }
        if(landStep==1){
            Race.cap(351);
            if(tick<339||mc.player.getCameraPosVec(1).squaredDistanceTo(Vec3d.ofCenter(TNT_SITE))>18)return false;
            Race.stop(mc);status="placing TNT at oak trees";
            if(mc.world.getBlockState(TNT_SITE).getBlock()==Blocks.TNT){landStep++;}else place(mc,TNT_SITE,Items.TNT);
            return true;
        }
        if(landStep==2){
            Race.stop(mc);status="placing pressure plate";
            if(mc.world.getBlockState(PLATE).getBlock()==(shieldOpening?Blocks.OAK_PRESSURE_PLATE:Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE))landStep++;
            else place(mc,PLATE,shieldOpening?Items.OAK_PRESSURE_PLATE:Items.HEAVY_WEIGHTED_PRESSURE_PLATE);
            return true;
        }
        if(landStep==3){
            if(mc.world.getBlockState(TNT_SITE).getBlock()==Blocks.TNT){
                status="stepping on pressure plate";walk(mc,Vec3d.ofCenter(PLATE),false);return true;
            }
            blastAge=age;landStep++;
        }
        if(landStep==4){
            int elapsed=age-blastAge;
            if(elapsed<86){
                // The race replay mines gravel twice during the TNT fuse. The lower
                // grassy terrace provides cover while the normal breaking key is held.
                Vec3d cover=new Vec3d(229.46,68,12.35);
                BlockPos pad=new BlockPos(229,69,11);
                if(Math.hypot(mc.player.getX()-cover.x,mc.player.getZ()-cover.z)>.48){
                    status="taking cover from TNT";walk(mc,cover,Math.hypot(mc.player.getX()-cover.x,mc.player.getZ()-cover.z)>2);return true;
                }
                Race.stop(mc);status="mining gravel during TNT fuse";
                if(mc.world.getBlockState(pad).getBlock()==Blocks.GRAVEL){
                    if(target(mc,pad)!=null)mc.options.keyAttack.setPressed(true);
                }else if(elapsed<62&&count(mc,Items.GRAVEL)>0){
                    gravelPos=pad;place(mc,pad,Items.GRAVEL);
                }else if(count(mc,Items.FLINT)==0&&count(mc,Items.GRAVEL)==0){
                    for(net.minecraft.entity.Entity e:mc.world.getEntities())if(e instanceof net.minecraft.entity.ItemEntity){
                        Item i=((net.minecraft.entity.ItemEntity)e).getStack().getItem();
                        if((i==Items.GRAVEL||i==Items.FLINT)&&mc.player.squaredDistanceTo(e)<5){
                            Vec3d dest=e.getPos();walk(mc,new Vec3d(dest.x,dest.y,Math.max(12.13,dest.z)),false);break;
                        }
                    }
                }
                return true;
            }
            if(gravelPos!=null&&mc.world.getBlockState(gravelPos).getBlock()!=Blocks.GRAVEL)gravelPos=null;
            landStep++;Race.advanceTo(435);
        }
        if(landStep==5){
            Race.cap(501);status="gathering oak logs and blocks";
            if(count(mc,Items.OAK_LOG)>=7 && (count(mc,Items.DIRT)>=56 || age-blastAge>260)){landStep++;Race.advanceTo(500);}
            else {
                net.minecraft.entity.ItemEntity log=null;
                double min=200;
                for(net.minecraft.entity.Entity ent:mc.world.getEntities()){
                    if(!(ent instanceof net.minecraft.entity.ItemEntity))continue;
                    net.minecraft.entity.ItemEntity item=(net.minecraft.entity.ItemEntity)ent;
                    Item sought=count(mc,Items.OAK_LOG)>=7?Items.DIRT:Items.OAK_LOG;
                    if(!item.isAlive()||item.getStack().getItem()!=sought)continue;
                    double d=mc.player.squaredDistanceTo(item);
                    if(d<min){min=d;log=item;}
                }
                if(log!=null){walk(mc,log.getPos(),true);return true;}return false;
            }
        }
        if(landStep==17&&!shieldOpening){
            if(mc.currentScreen!=null)mc.player.closeHandledScreen();
            Race.advanceTo(644);landStep=19;
        }
        if(landStep>=6&&landStep<=18) {
            Race.cap(630);
            if(tick<495)return false;
            boolean ok=false;
            switch(landStep){
            case 6:ok=craft(mc,"oak_planks",Items.OAK_PLANKS,20,false,true);break;
            case 7:ok=craft(mc,"crafting_table",Items.CRAFTING_TABLE,1,false,false);break;
            case 8:ok=craft(mc,"stick",Items.STICK,4,false,false);break;
            case 9:
                if(mc.currentScreen!=null)mc.player.closeHandledScreen();
                if(table!=null && mc.world.getBlockState(table).getBlock()==Blocks.CRAFTING_TABLE){ok=true;break;}
                BlockPos feet=mc.player.getBlockPos();
                double best=Double.MAX_VALUE;
                for(int dy=-2;dy<=2;dy++)for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
                    BlockPos tryAt=feet.add(dx,dy,dz);
                    if(mc.world.getBlockState(tryAt).getBlock()==Blocks.CRAFTING_TABLE){table=tryAt;best=0;}
                    if(best==0 || mc.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(tryAt.up())) || mc.world.getBlockState(tryAt).getMaterial().isReplaceable()
                            || !mc.world.getBlockState(tryAt.up()).getMaterial().isReplaceable())continue;
                    Vec3d top=Vec3d.ofCenter(tryAt).add(0,.499,0);
                    net.minecraft.util.hit.BlockHitResult ray=mc.world.rayTrace(new net.minecraft.world.RayTraceContext(mc.player.getCameraPosVec(1),top,
                            net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,net.minecraft.world.RayTraceContext.FluidHandling.NONE,mc.player));
                    double dist=mc.player.getCameraPosVec(1).squaredDistanceTo(top);
                    if(ray.getType()!=HitResult.Type.BLOCK || !tryAt.equals(ray.getBlockPos()) || ray.getSide()!=Direction.UP || dist<1.05 || dist>16 || dist>=best)continue;
                    best=dist;tableSupport=tryAt;
                }
                if(table!=null && mc.world.getBlockState(table).getBlock()==Blocks.CRAFTING_TABLE){ok=true;break;}
                status="placing crafting table on ground";
                if(tableSupport!=null && select(mc,Items.CRAFTING_TABLE)) {
                    if(hitFace(mc,tableSupport,Direction.UP,Vec3d.ofCenter(tableSupport).add(0,.499,0)))table=tableSupport.up();
                }
                break;
            case 10:ok=craft(mc,"iron_pickaxe",Items.IRON_PICKAXE,1,true,false);break;
            case 11:ok=craft(mc,"bucket",Items.BUCKET,1,true,false);break;
            case 12:ok=craft(mc,"oak_boat",Items.OAK_BOAT,1,true,false);break;
            case 13:ok=eastern || craft(mc,"oak_door",Items.OAK_DOOR,6,true,false);break;
            case 14:ok=craft(mc,"stick",Items.STICK,7,true,false);break;
            case 15:ok=craft(mc,"diamond_sword",Items.DIAMOND_SWORD,1,true,false);break;
            case 16:
                // The shield and lighter cost one iron each. Use only the third ingot for a shovel.
                if (shovel==Items.WOODEN_SHOVEL && shieldOpening && count(mc,Items.IRON_INGOT)>=3)
                    shovel=Items.IRON_SHOVEL;
                String recipe=shovel==Items.IRON_SHOVEL?"iron_shovel":shovel==Items.WOODEN_SHOVEL?"wooden_shovel":"golden_shovel";
                ok=craft(mc,recipe,shovel,1,true,false);break;
            case 17:ok=craft(mc,"shield",Items.SHIELD,1,true,false);break;
            case 18:
                status="equipping shield in offhand";Race.stop(mc);
                if(mc.player.getOffHandStack().getItem()==Items.SHIELD){
                    if(mc.currentScreen!=null)mc.player.closeHandledScreen();
                    Race.advanceTo(644);ok=true;break;
                }
                if(!(mc.currentScreen instanceof InventoryScreen)){
                    if(mc.currentScreen!=null)mc.player.closeHandledScreen();
                    else mc.openScreen(new InventoryScreen(mc.player));
                    return true;
                }
                for(net.minecraft.screen.slot.Slot slot:mc.player.playerScreenHandler.slots){
                    if(slot.getStack().getItem()!=Items.SHIELD)continue;
                    dev.aiarena.week29.VisibleSlots.hover(mc,(InventoryScreen)mc.currentScreen,slot);
                    if(age-shieldSwapAge>=10){
                        // Vanilla's F key over a visible slot uses SWAP with offhand key 40.
                        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,slot.id,40,SlotActionType.SWAP,mc.player);
                        shieldSwapAge=age;
                    }
                    break;
                }
                break;
            default:break;
            }
            if(ok)landStep++;
            return true;
        }
        if(landStep==19){
            Race.cap(699);status="obtaining flint";
            if(count(mc,Items.FLINT)>0){landStep++;return true;}
            if(tick<646)return false;
            Race.stop(mc);
            // The item can land behind a one-block lip in the TNT crater. Track the
            // real dropped flint before choosing where to replant another piece of gravel.
            net.minecraft.entity.ItemEntity drop = null;
            double closestDrop = 24 * 24;
            boolean needsGravel = count(mc, Items.GRAVEL) == 0;
            for (net.minecraft.entity.Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof net.minecraft.entity.ItemEntity) || !entity.isAlive()) continue;
                net.minecraft.entity.ItemEntity item = (net.minecraft.entity.ItemEntity)entity;
                Item type = item.getStack().getItem();
                if (type != Items.FLINT && !(needsGravel && type == Items.GRAVEL)) continue;
                double range = mc.player.squaredDistanceTo(item);
                if (range >= 24 * 24) continue;
                if (drop != null && drop.getStack().getItem() == Items.FLINT && type != Items.FLINT) continue;
                if (drop == null || type == Items.FLINT && drop.getStack().getItem() != Items.FLINT
                        || range < closestDrop) {
                    drop = item;
                    closestDrop = range;
                }
            }
            if (drop != null) {
                mc.options.keyAttack.setPressed(false);
                if (!mc.player.getBoundingBox().expand(1, .5, 1).intersects(drop.getBoundingBox()))
                    walk(mc, drop.getPos(), closestDrop > 7);
                status = drop.getStack().getItem() == Items.FLINT ? "collecting the mined flint" : "collecting the mined gravel";
                return true;
            }
            if(gravelPos!=null &&mc.world.getBlockState(gravelPos).getBlock()==Blocks.GRAVEL
                    &&mc.player.getCameraPosVec(1).squaredDistanceTo(Vec3d.ofCenter(gravelPos))>18){
                walk(mc,Vec3d.ofCenter(gravelPos).add(0,0,.6),true);return true;
            }
            if(gravelPos!=null && mc.world.getBlockState(gravelPos).getBlock()!=Blocks.GRAVEL)gravelPos=null;
            if(gravelPos==null) {
                double closest=Double.MAX_VALUE;
                BlockPos feet=mc.player.getBlockPos();
                for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy=-2;dy<=1;dy++){
                    BlockPos support=feet.add(dx,dy,dz),at=support.up();
                    if(mc.world.getBlockState(support).getMaterial().isReplaceable()||!mc.world.getBlockState(at).getMaterial().isReplaceable()
                          || mc.world.getBlockState(support).getBlock()==Blocks.CRAFTING_TABLE
                          || mc.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(at)))continue;
                    Vec3d top=Vec3d.ofCenter(support).add(0,.499,0);
                    BlockHitResult ray=mc.world.rayTrace(new net.minecraft.world.RayTraceContext(mc.player.getCameraPosVec(1),top,
                          net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,net.minecraft.world.RayTraceContext.FluidHandling.NONE,mc.player));
                    double d=mc.player.getCameraPosVec(1).squaredDistanceTo(top);
                    if(ray.getType()!=HitResult.Type.BLOCK||!support.equals(ray.getBlockPos())||ray.getSide()!=Direction.UP||d>16||d>=closest)continue;
                    closest=d;gravelPos=at;
                }
                if(gravelPos==null)return false;
            }
            if(mc.world.getBlockState(gravelPos).getBlock()==Blocks.GRAVEL){
                // Gravel still yields flint when broken by hand if a tool is unavailable.
                if((count(mc,shovel)==0||select(mc,shovel))&&target(mc,gravelPos)!=null)
                    mc.options.keyAttack.setPressed(true);
                return true;
            }
            mc.options.keyAttack.setPressed(false);
            if(count(mc,Items.GRAVEL)>0){
                BlockPos support=gravelPos.down();
                Vec3d top=Vec3d.ofCenter(support).add(0,.499,0);
                if(select(mc,Items.GRAVEL))hitFace(mc,support,Direction.UP,top);
                return true;
            }
            return false;
        }
        if(landStep==20){
            if(!craft(mc,"flint_and_steel",Items.FLINT_AND_STEEL,1,false,false))return true;
            if(mc.currentScreen!=null)mc.player.closeHandledScreen();
            if(count(mc,Items.OAK_PLANKS)<4 && count(mc,Items.CRAFTING_TABLE)==0) {
                // A shield spends six planks. Carry the existing workbench when the
                // remaining planks cannot supply a second one for the fortress bow.
                Race.stop(mc);
                status="recovering the workbench for the fortress bow";
                if(table!=null && mc.world.getBlockState(table).getBlock()==Blocks.CRAFTING_TABLE) {
                    if(target(mc,table)!=null) mc.options.keyAttack.setPressed(true);
                    else if(!mc.player.getBlockPos().down().equals(table)) walk(mc,Vec3d.ofCenter(table),true);
                    // On the table, let target() finish pitching down over successive ticks.
                    return true;
                }
                for(net.minecraft.entity.Entity entity:mc.world.getEntities()) {
                    if(entity instanceof net.minecraft.entity.ItemEntity && entity.isAlive()
                            && ((net.minecraft.entity.ItemEntity)entity).getStack().getItem()==Items.CRAFTING_TABLE
                            && mc.player.squaredDistanceTo(entity)<64) {
                        if(table!=null && entity.getBlockPos().equals(table)
                                && mc.player.getY()>entity.getY()+.55) {
                            // The drop can rest against a corner one block below the rim.
                            // Stand over the empty column so the player's feet can descend.
                            Vec3d center=Vec3d.ofCenter(table);
                            Race.aim(mc,new Vec3d(center.x,mc.player.getEyeY(),center.z));
                            BastionApproach.move(mc,center.x,center.z,false,false);
                        } else {
                            walk(mc,entity.getPos(),false);
                        }
                        break;
                    }
                }
                return true;
            }
            landStep++;Race.advanceTo(698);
        }
        return false;
    }
    private static boolean boat(MinecraftClient mc,int tick) {
        if(tick<710 || tick>=986)return false;
        if(mc.player.hasVehicle()){
            boarded=true;
            double[][] waterPath={{205.9,-.6,752},{187.5,-3.8,800},{169,-8.9,848},
                                  {151.5,-16.8,896},{134.6,-25.8,944},{127.5,-29.7,965}};
            while(boatPoint<waterPath.length && Math.hypot(mc.player.getX()-waterPath[boatPoint][0],mc.player.getZ()-waterPath[boatPoint][1])<2.4){
                Race.advanceTo((int)waterPath[boatPoint][2]);
                boatPoint++;
            }
            Race.stop(mc);
            if(boatPoint<waterPath.length){
                double x=waterPath[boatPoint][0],z=waterPath[boatPoint][1];
                float needed=(float)Math.toDegrees(Math.atan2(mc.player.getX()-x,z-mc.player.getZ()));
                float diff=MathHelper.wrapDegrees(needed-mc.player.getVehicle().yaw);
                Race.aim(mc,new Vec3d(x,mc.player.getEyeY(),z));
                mc.options.keyLeft.setPressed(diff< -3);
                mc.options.keyRight.setPressed(diff>3);
                mc.options.keyForward.setPressed(Math.abs(diff)<55);
                status="rowing to ravine";
                return true;
            }
            mc.options.keySneak.setPressed(true);
            Race.cap(965);
            return true;
        }
        if(boarded){
            Race.stop(mc);
            ServerPlayerEntity self=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
            if(count(mc,Items.OAK_BOAT)>0 && self!=null && self.inventory.count(Items.OAK_BOAT)>0){
                Race.advanceTo(968);
                return false;
            }
            Race.cap(965);
            if(Hotbar.tick(mc))return true;
            status="recovering the boat at the ravine";
            Entity vehicle=null,drop=null;
            double boatDistance=196,dropDistance=196;
            for(Entity entity:mc.world.getEntities()){
                if(!entity.isAlive())continue;
                double distance=mc.player.squaredDistanceTo(entity);
                if(entity instanceof ItemEntity && ((ItemEntity)entity).getStack().getItem()==Items.OAK_BOAT && distance<dropDistance){
                    drop=entity;dropDistance=distance;
                }else if(entity instanceof BoatEntity && distance<boatDistance){
                    vehicle=entity;boatDistance=distance;
                }
            }
            Entity target=drop!=null?drop:vehicle;
            if(target!=null){
                Vec3d point=target.getBoundingBox().getCenter();
                Race.aim(mc,point);
                if(Math.hypot(point.x-mc.player.getX(),point.z-mc.player.getZ())>(drop!=null?.24:1.8))
                    BastionApproach.move(mc,point.x,point.z,false,false);
                if(drop==null && vehicle!=null && Hotbar.select(mc,Items.DIAMOND_SWORD,2)
                        && self!=null && self.getMainHandStack().getItem()==Items.DIAMOND_SWORD
                        && mc.player.getAttackCooldownProgress(.5f)>.95f){
                    mc.gameRenderer.updateTargetedEntity(1);
                    if(mc.crosshairTarget instanceof EntityHitResult && ((EntityHitResult)mc.crosshairTarget).getEntity()==vehicle){
                        mc.interactionManager.attackEntity(mc.player,vehicle);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
            }
            mc.options.keyJump.setPressed(mc.player.isTouchingWater() && (target==null || target.getY()>=mc.player.getY()-.25));
            return true;
        }
        if(tick>=720 && tick<900) {
            net.minecraft.entity.Entity boat=null;
            double best=196;
            for(net.minecraft.entity.Entity e:mc.world.getEntities())if(e instanceof net.minecraft.entity.vehicle.BoatEntity){
                double d=mc.player.squaredDistanceTo(e);if(d<best){best=d;boat=e;}
            }
            if(boat!=null){
                status="boarding boat";Race.stop(mc);Race.cap(735);
                Vec3d aim=boat.getPos().add(0,.3,0);Race.aim(mc,aim);mc.gameRenderer.updateTargetedEntity(1);
                if(mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult && ((net.minecraft.util.hit.EntityHitResult)mc.crosshairTarget).getEntity()==boat) {
                    mc.interactionManager.interactEntity(mc.player,boat,Hand.MAIN_HAND);
                }else if(best>4 || mc.player.isTouchingWater()){
                    mc.options.keyForward.setPressed(best>2);
                    mc.options.keyJump.setPressed(mc.player.isTouchingWater());
                }
                return true;
            }
            if(count(mc,Items.OAK_BOAT)==0){Race.cap(735);Race.stop(mc);mc.options.keyJump.setPressed(true);return true;}
            if(select(mc,Items.OAK_BOAT)){
                Vec3d spot=new Vec3d(210.5,62.8,.5);
                if(mc.player.getCameraPosVec(1).squaredDistanceTo(spot)<19){
                    Race.stop(mc);Race.aim(mc,spot);mc.gameRenderer.updateTargetedEntity(1);
                    mc.interactionManager.interactItem(mc.player,mc.world,Hand.MAIN_HAND);
                    return true;
                }
            }
        }
        return false;
    }

    private static final double[][] DIVE_PATH={
        {125.4,54.4,-31.2,992},{123.1,47.2,-33.0,1008},{120.7,39.9,-34.9,1024},
        {118.6,32.7,-36.9,1040},{118.2,25.4,-38.7,1056},{117.9,18.2,-38.9,1072},
        {117.1,11.25,-38.55,1088}
    };
    private static int divePoint;
    private static boolean dive(MinecraftClient mc,int tick){
        if(castStep>0||tick<968||mc.player.hasVehicle()||mc.player.getY()<12.5||divePoint>=DIVE_PATH.length || !mc.player.isTouchingWater())return false;
        if(Math.hypot(mc.player.getX()-127,mc.player.getZ()+30)>21)return false;
        while(divePoint<DIVE_PATH.length-1&&mc.player.getY()<=DIVE_PATH[divePoint][1]+.5){
            Race.advanceTo((int)DIVE_PATH[divePoint][3]);divePoint++;
        }
        double[] point=DIVE_PATH[divePoint];status="swimming down ocean ravine";
        Race.stop(mc);
        Race.aim(mc,new Vec3d(point[0],point[1]-.45,point[2]));
        mc.options.keySprint.setPressed(true);mc.options.keyForward.setPressed(true);
        mc.options.keySneak.setPressed(mc.player.isSwimming()&&mc.player.getY()>16);
        mc.options.keyJump.setPressed(false);
        if(mc.player.getY()<13.5)Race.advanceTo(1085);
        return true;
    }
    private static final int BREAK=0,DIRT=1,DOOR=2,SCOOP_LAVA=3,POUR_LAVA=4,SCOOP_WATER=5,POUR_WATER=6,TOGGLE=7,FIRE=8;
    // All positions are observed block changes in the supplied race replay. Portal construction
    // still uses the client's actual support faces, current bucket and live block states.
    private static final int[][] RAVINE = {
        {BREAK,115,11,-39}, {DIRT,117,11,-38}, {DOOR,117,11,-39},
        {DOOR,116,11,-39}, {DOOR,115,11,-38}, {DIRT,115,11,-39},
        {BREAK,116,10,-39},{SCOOP_LAVA,116,9,-39},{DOOR,116,9,-39},
        {POUR_LAVA,116,11,-37},{DOOR,116,11,-39},{BREAK,116,10,-39},
        {SCOOP_LAVA,115,9,-39},{POUR_LAVA,116,12,-38},{BREAK,116,10,-38},
        {SCOOP_LAVA,116,9,-38},{DOOR,116,9,-38},{POUR_LAVA,116,11,-40},
        {BREAK,116,9,-39},{SCOOP_LAVA,115,9,-40},{POUR_LAVA,116,12,-39},
        {SCOOP_WATER,116,11,-38},{POUR_WATER,116,9,-39},{TOGGLE,116,10,-38},
        {SCOOP_LAVA,117,9,-38},{BREAK,116,8,-39},{POUR_LAVA,116,8,-39},
        {SCOOP_LAVA,117,9,-37},{BREAK,116,8,-38},{POUR_LAVA,116,8,-38},
        {SCOOP_WATER,116,9,-39},{FIRE,116,8,-39}
    };
    private static boolean seekingAir,inWell,riseFromWell,stayBelow,lowMold;
    private static int extraSupportAge;
    private static BlockPos dryShaft;
    private static int castStep,castStarted,anchorTicks,bucketAimAge,bucketTicks,bucketUseAge;
    private static float lastBucketYaw,lastBucketPitch;
    private static BlockPos lastBucketGoal;
    private static boolean steadyAim(MinecraftClient mc,BlockPos goal){
        // Bucket packets carry a hand, unlike block placement packets which carry a hit.
        // Keep gaze stable across normal movement packet transmission before using the item.
        boolean same=goal.equals(lastBucketGoal)&&bucketAimAge==age-1
            &&Math.abs(MathHelper.wrapDegrees(mc.player.yaw-lastBucketYaw))<.6f
            &&Math.abs(mc.player.pitch-lastBucketPitch)<.6f;
        bucketTicks=same?bucketTicks+1:1;lastBucketGoal=goal;bucketAimAge=age;
        lastBucketYaw=mc.player.yaw;lastBucketPitch=mc.player.pitch;
        return bucketTicks>=3;
    }
    static BlockHitResult fluidRay(MinecraftClient mc){
        Vec3d eye=mc.player.getCameraPosVec(1);
        return mc.world.rayTrace(new net.minecraft.world.RayTraceContext(eye,eye.add(mc.player.getRotationVec(1).multiply(4.5)),
            net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,net.minecraft.world.RayTraceContext.FluidHandling.SOURCE_ONLY,mc.player));
    }

    static Vec3d sourcePoint(MinecraftClient mc,BlockPos pos,boolean lava) {
        net.minecraft.fluid.FluidState original=mc.world.getFluidState(pos);
        if(!original.isStill()||!original.isIn(lava?net.minecraft.tag.FluidTags.LAVA:net.minecraft.tag.FluidTags.WATER))return null;
        Vec3d eye=mc.player.getCameraPosVec(1);
        // Edge and corner rays can see beneath a natural obsidian overhang.
        for(double y:new double[]{.88,.52,.18})for(double x:new double[]{.5,.93,.07})for(double z:new double[]{.5,.93,.07}){
            Vec3d end=new Vec3d(pos.getX()+x,pos.getY()+y,pos.getZ()+z);
            if(eye.squaredDistanceTo(end)>24)continue;
            BlockHitResult h=mc.world.rayTrace(new net.minecraft.world.RayTraceContext(eye,end,
                net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,net.minecraft.world.RayTraceContext.FluidHandling.SOURCE_ONLY,mc.player));
            if(h.getType()!=HitResult.Type.BLOCK||!pos.equals(h.getBlockPos()))continue;
            net.minecraft.fluid.FluidState fluid=mc.world.getFluidState(pos);
            if(fluid.isStill()&&fluid.isIn(lava?net.minecraft.tag.FluidTags.LAVA:net.minecraft.tag.FluidTags.WATER))return end;
        }
        return null;
    }
    private static boolean scoop(MinecraftClient mc,BlockPos pos,Item full){
        if(count(mc,full)>0)return age-bucketUseAge>=(castStep==7?6:4);
        boolean lava=full==Items.LAVA_BUCKET;
        if(castStep==7 && lava && select(mc,Items.BUCKET)){
            // The pick was held on the covering magma while already aiming through
            // it at this source, so the server has the correct gaze before it opens.
            BlockHitResult first=fluidRay(mc);
            if(first.getType()==HitResult.Type.BLOCK&&first.getBlockPos().equals(pos)
                    &&mc.world.getFluidState(pos).isStill()&&mc.world.getFluidState(pos).isIn(net.minecraft.tag.FluidTags.LAVA)
                    &&age-bucketUseAge>=6){
                bucketUseAge=age;mc.interactionManager.interactItem(mc.player,mc.world,Hand.MAIN_HAND);return false;
            }
        }
        if(lava && count(mc,Items.WATER_BUCKET)>0){
            // A scoop can be corrected by the server after the client predicted lava.
            // Empty the mistaken water into the surrounding sea, away from the dry hole.
            placeOnVisibleFace(mc,stayBelow?new BlockPos(116,9,-39):new BlockPos(118,11,-38),Items.WATER_BUCKET,true);
            return false;
        }
        Vec3d point=sourcePoint(mc,pos,lava);
        if(point==null && !lava){
            for(int y=11;y>=9&&point==null;y--)for(int z=-39;z<=-38&&point==null;z++)
                point=sourcePoint(mc,new BlockPos(116,y,z),false);
            for(int y=12;y<=13&&point==null;y++)for(int x=115;x<=118&&point==null;x++)
                for(int z=-40;z<=-37&&point==null;z++)point=sourcePoint(mc,new BlockPos(x,y,z),false);
        }
        if(point==null && lava){
            // Water can reach a newly-opened well before the controller. If the direct
            // source is hidden, use another naturally visible source at the same pool.
            for(int x=113;x<=120&&point==null;x++)for(int z=-41;z<=-36&&point==null;z++){
                if(x==116&&(z==-40||z==-37))continue; // Required lower frame uprights.
                point=sourcePoint(mc,new BlockPos(x,9,z),true);
            }
        }
        if(point==null){
            if(lava && !inWell && castStep>=12){
                for(int z:new int[]{-39,-38}){
                    BlockPos candidate=new BlockPos(116,9,z);
                    if(mc.world.getBlockState(candidate).getBlock()==Blocks.OAK_DOOR){
                        dryShaft=candidate;inWell=true;return false;
                    }
                }
            }
            if(lava){
                BlockPos plug=new BlockPos(116,9,-39);
                Vec3d eye=mc.player.getCameraPosVec(1),best=null;
                double distance=25;
                for(int x=113;x<=120;x++)for(int z=-41;z<=-36;z++){
                    BlockPos open=new BlockPos(x,10,z);
                    if(x==116&&z==-39&&mc.world.getBlockState(plug).getBlock()==Blocks.COBBLESTONE)open=plug;
                    net.minecraft.block.Block block=mc.world.getBlockState(open).getBlock();
                    if(block!=Blocks.COBBLESTONE && !(block==Blocks.MAGMA_BLOCK &&
                       mc.world.getFluidState(open.down()).isIn(net.minecraft.tag.FluidTags.LAVA)))continue;
                    for(double u:new double[]{.5,.15,.85})for(double v:new double[]{.5,.15,.85}){
                        Vec3d spot=new Vec3d(x+u,open.getY()+.985,z+v);
                        double d=eye.squaredDistanceTo(spot);
                        if(d>=distance)continue;
                        BlockHitResult ray=mc.world.rayTrace(new net.minecraft.world.RayTraceContext(eye,spot,
                             net.minecraft.world.RayTraceContext.ShapeType.OUTLINE,net.minecraft.world.RayTraceContext.FluidHandling.NONE,mc.player));
                        if(ray.getType()!=HitResult.Type.BLOCK || !ray.getBlockPos().equals(open))continue;
                        best=spot;distance=d;
                    }
                }
                if(best!=null&&select(mc,Items.IRON_PICKAXE)){
                    Race.aim(mc,best);mc.gameRenderer.updateTargetedEntity(1);
                    BlockPos open=new BlockPos(best);
                    if(mc.crosshairTarget instanceof BlockHitResult && ((BlockHitResult)mc.crosshairTarget).getBlockPos().equals(open))
                        mc.options.keyAttack.setPressed(true);
                }
            }
            return false;
        }
        if(!select(mc,Items.BUCKET))return false;
        Race.aim(mc,point);
        BlockHitResult hit=fluidRay(mc);
        if(hit.getType()==HitResult.Type.BLOCK){
            net.minecraft.fluid.FluidState fluid=mc.world.getFluidState(hit.getBlockPos());
            if(hit.getBlockPos().equals(new BlockPos(point))&&fluid.isStill()&&fluid.isIn(lava?net.minecraft.tag.FluidTags.LAVA:net.minecraft.tag.FluidTags.WATER)
                    &&steadyAim(mc,hit.getBlockPos())&&age-bucketUseAge>=6){
                bucketUseAge=age;
                mc.interactionManager.interactItem(mc.player,mc.world,Hand.MAIN_HAND);
            }
        }
        return false;
    }

    private static boolean pour(MinecraftClient mc,BlockPos to,Item full){
        if(count(mc,Items.WATER_BUCKET)>0&&full==Items.LAVA_BUCKET){
            while(castStep>0 && RAVINE[castStep][0]!=SCOOP_LAVA)castStep--;
            castStarted=age;
            return false;
        }
        if(count(mc,Items.BUCKET)>0){
            if(full==Items.LAVA_BUCKET)return mc.world.getBlockState(to).getBlock()==Blocks.OBSIDIAN;
            return mc.world.getFluidState(to).isIn(net.minecraft.tag.FluidTags.WATER);
        }
        if(full==Items.LAVA_BUCKET && to.getY()==12 && age-castStarted>10){
            BlockPos backing=to.getZ()==-38?to.south():to.north();
            if(mc.world.getBlockState(backing).getMaterial().isReplaceable()
                    && mc.world.getBlockState(backing.down()).getBlock()==Blocks.OBSIDIAN
                    &&count(mc,Items.DIRT)>0){
                place(mc,backing,Items.DIRT);
                return false;
            }
        }
        placeOnVisibleFace(mc,to,full,true);
        return false;
    }
    private static boolean cast(MinecraftClient mc,int tick){
        if(tick<1065)return false;
        if(castStep<RAVINE.length){
            Race.cap(1110);
            if(mc.player.getY()>13.7)return false;
            if(!preparedGold && shovel!=Items.GOLDEN_SHOVEL && count(mc,Items.GOLD_INGOT)>0) {
                if(!Hotbar.select(mc,Items.GOLD_INGOT,0))return true;
                preparedGold=true;
            }
            if(castStep>=20&&mc.player.getY()<10)stayBelow=true;
            if(!stayBelow&&castStep>2&&mc.player.getAir()<72)seekingAir=true;
            if(seekingAir){
                if(mc.player.getAir()>=284)seekingAir=false;
                else {
                    BlockPos door=new BlockPos(117,11,-39);
                    if(mc.world.getBlockState(door).getBlock()==Blocks.OAK_DOOR){
                        status="breathing inside ravine door";
                        Race.stop(mc);
                        if(mc.player.isSubmergedIn(net.minecraft.tag.FluidTags.WATER))
                            align(mc,117.32,-38.38);
                        mc.options.keySneak.setPressed(true);
                        return true;
                    }
                    seekingAir=false;
                }
            }
            BlockPos primary=new BlockPos(116,9,-39), capBlock=new BlockPos(116,11,-38);
            if((castStep==10||castStep==11)&&mc.world.getBlockState(primary).getBlock()==Blocks.OAK_DOOR){
                castStep=12;
            }
            if(castStep==12&&!inWell&&!lowMold&&extraSupportAge!=-2){
                if(extraSupportAge<0)extraSupportAge=age;
                if(age-extraSupportAge>25){extraSupportAge=-2;}
                else {
                    // Dry access below the chamber saves additional ascents. Two dirt
                    // faces outside the frame allow every later bucket to pour from below.
                    BlockPos[] supports={new BlockPos(115,11,-40),new BlockPos(116,12,-37),capBlock};
                    boolean ready=true;
                    for(BlockPos support:supports){
                        if(mc.world.getBlockState(support).getBlock()==Blocks.DIRT)continue;
                        Race.stop(mc);mc.options.keySneak.setPressed(true);status="preparing underwater frame backing";
                        if(mc.world.getBlockState(support).getBlock()==Blocks.KELP_PLANT||mc.world.getBlockState(support).getBlock()==Blocks.KELP){
                            if(target(mc,support)!=null)mc.options.keyAttack.setPressed(true);
                        }else place(mc,support,Items.DIRT);
                        ready=false;break;
                    }
                    if(!ready)return true;
                    lowMold=true;
                }
            }
            if(lowMold&&stayBelow&&(castStep==13||castStep==17)&&age-castStarted>20){
                // A source or door can occupy a different face in another attempt.
                lowMold=false;stayBelow=false;riseFromWell=true;inWell=false;
            }
            if(castStep==5){
                BlockPos chamber=new BlockPos(116,11,-39);
                net.minecraft.block.BlockState panel=mc.world.getBlockState(chamber);
                if(panel.getBlock()==Blocks.OAK_DOOR&&panel.get(net.minecraft.block.DoorBlock.FACING).getAxis()==Direction.Axis.X
                        &&!panel.get(net.minecraft.block.DoorBlock.OPEN)){
                    status="opening side-facing air door";Race.stop(mc);mc.options.keySneak.setPressed(false);
                    if(!mc.player.isSneaking()&&age%4==0)useBlock(mc,chamber.up());
                    return true;
                }
            }
            if(castStep==13 && !riseFromWell && !inWell && mc.world.getBlockState(capBlock).getBlock()!=Blocks.DIRT){
                status="capping second lava well";Race.stop(mc);mc.options.keySneak.setPressed(true);
                place(mc,capBlock,Items.DIRT);return true;
            }
            if(castStep==14&&!inWell&&mc.world.getBlockState(primary).getBlock()==Blocks.OAK_DOOR){
                dryShaft=primary;inWell=true;riseFromWell=false;
            }
            if(castStep==18&&mc.world.getBlockState(primary).getBlock()==Blocks.OAK_DOOR)castStep=19;
            if(castStep==24 && stayBelow && !inWell && count(mc,Items.LAVA_BUCKET)==0){
                BlockPos second=new BlockPos(116,9,-38);
                if(mc.world.getBlockState(second).getBlock()==Blocks.OAK_DOOR){dryShaft=second;inWell=true;}
            }
            if(castStep==29){
                // When the lower door loses its floor, the adjacent pool can create
                // cobblestone above the empty mold before the last lava is poured.
                BlockPos shutter=new BlockPos(116,9,-38);
                if(mc.world.getBlockState(shutter).getBlock()==Blocks.COBBLESTONE){
                    status="mining cobblestone above the final floor";Race.stop(mc);mc.options.keySneak.setPressed(true);
                    if(select(mc,Items.IRON_PICKAXE)&&target(mc,shutter)!=null)mc.options.keyAttack.setPressed(true);
                    return true;
                }
            }
            if(castStep==28&&stayBelow&&mc.player.getZ()>-38.29){
                // The second floor supports the door and the swimmer. Move onto
                // the first, completed obsidian before removing its neighbor.
                status="standing on completed portal floor";align(mc,116.50,-38.52);return true;
            }
            if(castStep==21&&mc.world.getBlockState(capBlock).getBlock()==Blocks.DIRT){
                status="uncovering sealed second well";Race.stop(mc);mc.options.keySneak.setPressed(true);
                if(select(mc,shovel)&&target(mc,capBlock)!=null)mc.options.keyAttack.setPressed(true);return true;
            }
            if(castStep==22){
                BlockPos other=new BlockPos(116,9,-38);
                if(mc.world.getBlockState(primary).getBlock()==Blocks.OAK_DOOR){
                    if(mc.player.getZ()<-37.80){
                        // Both adjacent doors can leave opposing thin panels along X.
                        // Their common opening is then only 0.625 wide, for a 0.6 player.
                        double left=.302,right=.698;
                        for(BlockPos shaft:new BlockPos[]{primary,other}){
                            net.minecraft.block.BlockState state=mc.world.getBlockState(shaft);
                            if(state.getBlock()!=Blocks.OAK_DOOR)continue;
                            net.minecraft.util.math.Box panel=state.getOutlineShape(mc.world,shaft).getBoundingBox();
                            if(panel.maxZ-panel.minZ<.5 && (shaft.equals(primary)?panel.maxZ>.9:panel.minZ<.1)){
                                status="rotating lower door away from passage";Race.stop(mc);
                                if(!mc.player.isSneaking()&&age%4==0)useBlock(mc,shaft.up());
                                return true;
                            }
                            if(panel.maxX<.5)left=Math.max(left,panel.maxX+.302);
                            if(panel.minX>.5)right=Math.min(right,panel.minX-.302);
                        }
                        double x=mc.player.getX()-116;
                        Race.stop(mc);Race.aim(mc,mc.player.getCameraPosVec(1).add(0,0,3));
                        status="moving clear of first portal floor";
                        mc.options.keySneak.setPressed(true);
                        if(Math.abs(MathHelper.wrapDegrees(mc.player.yaw))<8){
                            mc.options.keyLeft.setPressed(x<left+.002);
                            mc.options.keyRight.setPressed(x>right-.002);
                            mc.options.keyForward.setPressed(x>=left&&x<=right);
                        }
                        return true;
                    }
                    status="mining lower door support";Race.stop(mc);mc.options.keySneak.setPressed(true);
                    if(select(mc,Items.IRON_PICKAXE)&&target(mc,primary.down())!=null)mc.options.keyAttack.setPressed(true);
                    return true;
                }
                if(mc.world.getBlockState(primary.down()).getMaterial().isReplaceable()){
                    status="placing temporary floor under water";Race.stop(mc);mc.options.keySneak.setPressed(true);
                    place(mc,primary.down(),Items.DIRT);return true;
                }
            }
            if(riseFromWell){
                status="swimming up from protected lava well";
                if(mc.player.getY()>=10.94){
                    if(mc.player.getX()>117.09){riseFromWell=false;}
                    else {align(mc,117.25,-38.5);mc.options.keySneak.setPressed(false);mc.options.keyJump.setPressed(true);return true;}
                }else{
                    Race.stop(mc);mc.options.keyJump.setPressed(true);return true;
                }
            }
            if(inWell && (mc.player.getY()>9.6 || stayBelow && dryShaft!=null &&
                    Math.hypot(mc.player.getX()-dryShaft.getX()-.5,mc.player.getZ()-dryShaft.getZ()-.5)>.24)){
                status="descending into protected lava well";
                BlockPos shaft=dryShaft==null?primary:dryShaft;
                net.minecraft.util.math.Box panel=mc.world.getBlockState(shaft).getOutlineShape(mc.world,shaft).getBoundingBox();
                double x=shaft.getX()+(panel.maxX<.3?(1+panel.maxX)/2:panel.minX>.7?panel.minX/2:.5);
                double z=shaft.getZ()+(panel.maxZ<.3?(1+panel.maxZ)/2:panel.minZ>.7?panel.minZ/2:.5);
                if(Math.hypot(mc.player.getX()-x,mc.player.getZ()-z)>.035)align(mc,x,z);else Race.stop(mc);
                // Grounded sneak edge-protection prevents walking off the surrounding shelf.
                mc.options.keySneak.setPressed(!mc.player.isOnGround());return true;
            }
            if(castStep==6&&Math.hypot(mc.player.getX()-116.94,mc.player.getZ()+38.5)>.07){
                status="aligning on first magma well";align(mc,116.94,-38.5);return true;
            }
            if(!stayBelow&&!inWell && castStep>0 && castStep<30&&castStep!=6&&castStep!=7){
                double x=castStep==2?117.07:castStep==3?116.76:castStep==4?116.81:castStep>=24?116.72:castStep>=21?116.85:castStep>=12?117.18:116.975;
                double z=castStep==2? -38.34:castStep==3? -38.31:castStep==4? -38.42: -38.50;
                double allowed=castStep==2||castStep==3?.13:.115;
                if(Math.hypot(mc.player.getX()-x,mc.player.getZ()-z)>allowed && anchorTicks++%16<9){
                    align(mc,x,z);return true;
                }
            }
            int[] step=RAVINE[castStep];int op=step[0];BlockPos pos=new BlockPos(step[1],step[2],step[3]);
            net.minecraft.block.BlockState b=mc.world.getBlockState(pos);
            Race.stop(mc);mc.options.keySneak.setPressed(true);mc.options.keyAttack.setPressed(false);
            boolean ok=false;
            status="ravine step "+castStep+" at "+pos.toShortString();
            switch(op){
                case BREAK:
                    ok=b.isAir()||b.getMaterial().isLiquid();
                    if(!ok){
                        if(b.getBlock()==Blocks.DIRT||b.getBlock()==Blocks.GRAVEL)select(mc,shovel);else select(mc,Items.IRON_PICKAXE);
                        if(castStep==6){
                            Race.aim(mc,new Vec3d(116.50,9.5,-38.50));mc.gameRenderer.updateTargetedEntity(1);
                            if(mc.crosshairTarget instanceof BlockHitResult&&((BlockHitResult)mc.crosshairTarget).getBlockPos().equals(pos))
                                mc.options.keyAttack.setPressed(true);
                        }else if(target(mc,pos)!=null)mc.options.keyAttack.setPressed(true);
                    }break;
                case DIRT: case DOOR:
                    ok=op==DIRT ? b.getBlock()==Blocks.DIRT : b.getBlock()==Blocks.OAK_DOOR;
                    if(!ok){
                        if(op==DOOR && !b.getMaterial().isReplaceable()){
                            if(select(mc,Items.IRON_PICKAXE)&&target(mc,pos)!=null)mc.options.keyAttack.setPressed(true);
                        }else if(op==DOOR && count(mc,Items.OAK_DOOR)==0 && castStep>=14){
                            BlockPos spare=new BlockPos(115,11,-38);
                            if(select(mc,Items.IRON_PICKAXE)&&target(mc,spare)!=null)mc.options.keyAttack.setPressed(true);
                        }else if(op==DIRT)place(mc,pos,Items.DIRT);
                        else door(mc,pos,castStep==2?-.7f:castStep==3?24.2f:castStep==4?67.0f:castStep==10?44.4f:32.1f);
                    }break;
                case SCOOP_LAVA:case SCOOP_WATER:ok=scoop(mc,pos,op==SCOOP_LAVA?Items.LAVA_BUCKET:Items.WATER_BUCKET);break;
                case POUR_LAVA:case POUR_WATER:ok=pour(mc,pos,op==POUR_LAVA?Items.LAVA_BUCKET:Items.WATER_BUCKET);break;
                case TOGGLE:
                    mc.options.keySneak.setPressed(false);
                    if(b.getBlock()==Blocks.OAK_DOOR && b.get(net.minecraft.block.DoorBlock.OPEN))ok=true;
                    else if(!mc.player.isSneaking() && age%4==0)useBlock(mc,pos);
                    break;
                case FIRE:
                    if(mc.world.getBlockState(pos.up()).getBlock()==Blocks.NETHER_PORTAL){ok=true;break;}
                    // An early ignition can leave ordinary fire while the top of the
                    // opening is still draining. Portal creation is checked when fire
                    // is placed, so wait for six dry cells and clear any earlier fire.
                    boolean dry=true;
                    for(int h=1;h<=3;h++)for(int z=0;z<2;z++){
                        if(!mc.world.getFluidState(pos.add(0,h,z)).isEmpty())dry=false;
                    }
                    if(!dry){status="waiting for water to drain from frame";break;}
                    if(mc.world.getBlockState(pos.up()).getBlock()==Blocks.FIRE){
                        status="clearing early fire before relighting";
                        if(age%4==0)place(mc,pos.up(),Items.DIRT);
                        break;
                    }
                    if(mc.world.getBlockState(pos.up()).getBlock()==Blocks.DIRT){
                        if(select(mc,shovel)&&target(mc,pos.up())!=null)mc.options.keyAttack.setPressed(true);
                        break;
                    }
                    if(select(mc,Items.FLINT_AND_STEEL)){
                        Vec3d p=Vec3d.ofCenter(pos).add(0,.499,0);
                        hitFace(mc,pos,Direction.UP,p);
                    }break;
                default:throw new IllegalStateException("Unknown ravine action "+op);
            }
            if(ok){
                if((castStep==19||castStep==12&&lowMold)&&inWell){stayBelow=true;inWell=false;riseFromWell=false;}
                if(inWell&&castStep!=14&&castStep!=15){inWell=false;riseFromWell=!stayBelow;}
                castStep++;castStarted=age;
            }
            return true;
        }
        Race.advanceTo(1335);
        status="standing in active Nether portal";Race.stop(mc);
        if(mc.world.getBlockState(mc.player.getBlockPos()).getBlock()!=Blocks.NETHER_PORTAL)
            align(mc,116.52,-38.48);
        return true;
    }
    public static boolean tick(MinecraftClient mc, int tick) {
        age++;
        if (mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.world.getRegistryKey() != World.OVERWORLD) return false;
        mc.options.keyAttack.setPressed(false);
        if(eastern && looted && landStep>=21) {
            ravineInventory=true;
            boolean controlled=EasternOpening.tick(mc);
            status=EasternOpening.status;
            return controlled;
        }
        if (looted) {
            if(landStep>=21 && tick>=986 && tick<1405) {
                ravineInventory=true;
                if(Hotbar.tick(mc))return true;
            }
            if(landStep<21)return land(mc,tick);
            if(tick<=705&&mc.player.getX()>224.8&&Math.abs(mc.player.getZ()-7)<10){
                // The TNT crater's west wall is two high at z=5, but has a
                // walkable one-block staircase immediately south along z=6.
                double x=mc.player.getX(),z=mc.player.getZ();
                double nextX=x>229.1?228.75:z<6.05||z>6.87?x:224.52;
                status="leaving TNT crater by west staircase";
                walk(mc,new Vec3d(nextX,70,6.48),true);
                if(mc.player.horizontalCollision)mc.options.keyJump.setPressed(true);
                Race.cap(698);return true;
            }
            if(boat(mc,tick) || dive(mc,tick))return true;
            return cast(mc,tick);
        }
        Race.cap(173);
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            Race.stop(mc);
            boolean empty = dev.aiarena.week29.VisibleSlots.loot(mc, stack -> wanted(stack.getItem()), false);
            if (!empty) {status="collecting buried treasure";return true;}
            if (count(mc, Items.IRON_INGOT) >= 7) {
                mc.player.closeHandledScreen();
                looted = true;
                status = "buried treasure collected";
                Race.advanceTo(199);
            }
            return true;
        }
        if (tick < 52 || mc.player.getCameraPosVec(1).squaredDistanceTo(Vec3d.ofCenter(TREASURE.up())) > 17.5) return false;
        Race.stop(mc);
        if (mc.world.getBlockState(TREASURE.up()).getBlock() != Blocks.WATER
                && !mc.world.isAir(TREASURE.up())) {
            BlockHitResult cover = target(mc, TREASURE.up());
            if (cover == null) return false;
            status = "digging treasure cover";
            mc.options.keyAttack.setPressed(true);
            return true;
        }
        BlockHitResult chest = target(mc, TREASURE);
        if (chest != null) {
            mc.options.keyAttack.setPressed(false);
            status = "opening buried treasure";
            if (age % 3 == 0) mc.interactionManager.interactBlock(mc.player, mc.world, Hand.MAIN_HAND, chest);
            return true;
        }
        return false;
    }
}
