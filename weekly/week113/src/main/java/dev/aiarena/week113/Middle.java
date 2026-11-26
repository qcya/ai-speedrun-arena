package dev.aiarena.week113;

import dev.aiarena.week29.VisibleSlots;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.*;
import net.minecraft.recipe.Recipe;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.*;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Chest, bastion, fortress, return portal and stronghold controls for local survival. */
public final class Middle {
    private static final class Step {
        final int tick;
        final String kind, value;
        final BlockPos target;
        Step(String line) {
            String[] f = line.split(",");
            tick = Integer.parseInt(f[0]);
            kind = f[1];
            target = new BlockPos(Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]));
            value = f[5];
        }
    }
    private static final List<Step> steps = new ArrayList<>();
    private static final List<Step> bowSteps = new ArrayList<>(4);
    private static final BlockPos bowBench = new BlockPos(-9,57,120);
    private static int bowStep, bowStarted, bowRetryAfter;
    static {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                Middle.class.getResourceAsStream("/middle-actions.csv"), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null;) {
                if (!line.startsWith("#") && !line.isEmpty()) {
                    Step step = new Step(line);
                    steps.add(step);
                    if (step.tick >= 4620 && step.tick <= 4671) bowSteps.add(step);
                }
            }
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static final List<BlockPos> returnWalk=cells("fortress-return-walk.csv");
    private static int cell, next, age, entered, lastUse, thirdThrow, originalDistance = -1;
    private static boolean arrivedAtBastion, eating, equipAfterChest, drinkingFireResistance;
    private static int splashFireTime;
    private static BlockPos pillar;
    private static int barricade, pearlWalk, earlyTradeStarted=-1;
    private static Vec3d earlyTradeLocation;
    private static boolean collectingAbove, retreatingToShaft, escapingFirstFloor, rushingWest;
    private static int northRoofStarted;
    private static int offeredPig, offerStart, offerRetry;
    private static final Step[] eastCover={new Step("0,place,-149,37,118,dirt"),
            new Step("0,place,-149,38,118,dirt"),new Step("0,place,-149,39,118,dirt")};
    public static String status = "approaching bastion";
    private Middle() {}

    public static void reset() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (originalDistance > 0 && mc != null && mc.options != null) mc.options.viewDistance = originalDistance;
        originalDistance = -1;
        next = age = entered = 0;
        shelterBowStart=shelterBowStep=shelterBowRetryAfter=0;
        lastDropSlot=lastDropObservation=-1;
        previousDropItem=null; rodSlotsPrepared=false;
        bowStep = -1;
        bowStarted = bowRetryAfter = 0;
        lastUse = thirdThrow = -1000;
        arrivedAtBastion=false;earlyTradeStarted=-1;earlyTradeLocation=null;
        UpperLootRoute.reset(mc); EastHallRecovery.reset();
        BastionApproach.reset();
        BastionEgress.reset();
        Fortress.reset();
        ReturnFrame.reset();
        TravelPearls.reset();
        Hotbar.reset();EarlyBarter.reset();WellApple.reset();appleStarted=0;
        StrongholdEntry.reset();
        eating=false; drinkingFireResistance=false; splashFireTime=-100;
        equipAfterChest=false;equipReturnLeggings=false;ThirdChest.reset();
        pillar=null;
        barricade=0;ShaftDetour.reset();
        pearlWalk=0;
        chestPearlStep=-1; pendingWestPearls=false;
        collectingAbove=false;
        escapingFirstFloor=false;rushingWest=false;northRoofStarted=-1;
        retreatingToShaft=false;offeredPig=-1;offerStart=-1;offerRetry=0;
        cell=0;
    }

    private static int count(MinecraftClient mc, Item item) { return mc.player.inventory.count(item); }
    private static Item item(String path) { return Registry.ITEM.get(new Identifier(path)); }

    static boolean select(MinecraftClient mc, Item item) {
        return Hotbar.select(mc,item);
    }

    static boolean selectBuildingBlock(MinecraftClient mc,boolean pillar) {
        Item[] options={Items.DIRT,Items.NETHERRACK,Items.BLACKSTONE,Items.CRACKED_POLISHED_BLACKSTONE_BRICKS,
                Items.CHISELED_POLISHED_BLACKSTONE,Items.POLISHED_BLACKSTONE_BRICKS,Items.COBBLESTONE,Items.OAK_PLANKS};
        for(Item option:options) if (count(mc,option)>0) return select(mc,option);
        return pillar && count(mc,Items.GRAVEL)>0 && select(mc,Items.GRAVEL);
    }

    private static void finish(MinecraftClient mc, Step step) {
        mc.options.keyAttack.setPressed(false);
        mc.options.keyUse.setPressed(false);
        mc.interactionManager.cancelBlockBreaking();
        next++;
        entered = age;
        // As soon as the third chest closes, the exposed runner needs its escape
        // controls. A threatening piglin can otherwise hold the guide on this tick.
        int resume=step.tick;
        if (step.tick==2306 && next<steps.size() && steps.get(next).tick==2307)
            resume=steps.get(next).tick;
        Race.advanceTo(resume);
    }

    /** First test visibility geometrically; the eventual interaction uses the player's own crosshair. */
    static BlockHitResult aim(MinecraftClient mc, BlockPos pos, Direction required) {
        Vec3d eye = mc.player.getCameraPosVec(1);
        double reach = mc.interactionManager.getReachDistance();
        Vec3d center = Vec3d.ofCenter(pos);
        if (required != null) {
            center = center.add(Vec3d.of(required.getVector()).multiply(.499));
        } else {
            net.minecraft.util.shape.VoxelShape outline = mc.world.getBlockState(pos).getOutlineShape(mc.world,pos);
            if (!outline.isEmpty()) center = outline.getBoundingBox().offset(pos).getCenter();
        }
        for (double delta : new double[] {0, -.29, .29, -.477, .477}) {
            Vec3d point = required == Direction.UP || required == Direction.DOWN
                    ? center.add(delta, 0, delta) : center.add(0, delta, 0);
            Vec3d end = eye.add(point.subtract(eye).normalize().multiply(reach));
            BlockHitResult planned = mc.world.rayTrace(new RayTraceContext(eye, end,
                    RayTraceContext.ShapeType.OUTLINE, RayTraceContext.FluidHandling.NONE, mc.player));
            if (planned.getType() != HitResult.Type.BLOCK || !pos.equals(planned.getBlockPos())
                    || required != null && planned.getSide() != required) continue;
            Race.aim(mc, point);
            mc.gameRenderer.updateTargetedEntity(1);
            if (mc.crosshairTarget instanceof BlockHitResult && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                BlockHitResult actual = (BlockHitResult) mc.crosshairTarget;
                if (pos.equals(actual.getBlockPos()) && (required == null || required == actual.getSide())) return actual;
            }
            break;
        }
        return null;
    }

    static boolean use(MinecraftClient mc, BlockHitResult hit) {
        if (hit == null || age - lastUse < 2) return false;
        lastUse = age;
        ActionResult result = mc.interactionManager.interactBlock(mc.player, mc.world, Hand.MAIN_HAND, hit);
        if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        return result.isAccepted();
    }

    static void approach(MinecraftClient mc, Vec3d to, boolean sneak) {
        double dx = to.x - mc.player.getX(), dz = to.z - mc.player.getZ(), length = Math.hypot(dx,dz);
        if (length < .10) return;
        double angle = Math.toRadians(mc.player.yaw);
        double f = (-Math.sin(angle)*dx + Math.cos(angle)*dz)/length;
        double l = (Math.cos(angle)*dx + Math.sin(angle)*dz)/length;
        mc.options.keyForward.setPressed(f > .3827);
        mc.options.keyBack.setPressed(f < -.3827);
        mc.options.keyLeft.setPressed(l > .3827);
        mc.options.keyRight.setPressed(l < -.3827);
        mc.options.keySneak.setPressed(sneak);
        mc.options.keyJump.setPressed(!sneak && mc.player.horizontalCollision);
    }

    static List<BlockPos> cells(String name) {
        List<BlockPos> out=new ArrayList<>();
        try (BufferedReader reader=new BufferedReader(new InputStreamReader(
                Middle.class.getResourceAsStream("/"+name),StandardCharsets.UTF_8))) {
            for(String line;(line=reader.readLine())!=null;) {
                if(line.startsWith("#")||line.isEmpty()) continue;
                String[] xyz=line.split(",");
                out.add(new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2])));
            }
        } catch(IOException e) { throw new ExceptionInInitializerError(e); }
        return out;
    }

    private static boolean open(MinecraftClient mc,BlockPos p) {
        return mc.world.getBlockState(p).getCollisionShape(mc.world,p).isEmpty();
    }

    private static boolean walkCells(MinecraftClient mc,List<BlockPos> list) {
        Race.stop(mc);
        mc.options.keyAttack.setPressed(false);
        if (cell>=list.size()) return true;
        BlockPos target=list.get(cell);
        Vec3d center=Vec3d.ofCenter(target);
        double dx=center.x-mc.player.getX(),dz=center.z-mc.player.getZ();
        double dy=target.getY()-mc.player.getY();
        if (Math.hypot(dx,dz)<.26 && Math.abs(dy)<.32 && mc.player.isOnGround()) {
            cell++;
            return cell==list.size();
        }
        BlockPos floor=target.down();
        if (open(mc,floor)) {
            mc.options.keySneak.setPressed(true);
            if (!select(mc,Items.DIRT) && !select(mc,Items.NETHERRACK) && !select(mc,Items.BLACKSTONE)) return false;
            BlockPos previous=cell>0 ? list.get(cell-1) : mc.player.getBlockPos();
            BlockPos anchor=previous.down();
            int stepX=target.getX()-previous.getX(), stepZ=target.getZ()-previous.getZ();
            Direction side=Direction.fromVector(stepX,0,stepZ);
            if(side==null || Math.abs(stepX)+Math.abs(stepZ)!=1 || open(mc,anchor)) return false;
            Vec3d facePoint=Vec3d.ofCenter(anchor).add(stepX*.499,.22,stepZ*.499);
            Race.aim(mc,facePoint);
            mc.gameRenderer.updateTargetedEntity(1);
            if(mc.crosshairTarget instanceof BlockHitResult && mc.crosshairTarget.getType()==HitResult.Type.BLOCK) {
                BlockHitResult hit=(BlockHitResult)mc.crosshairTarget;
                if(anchor.equals(hit.getBlockPos()) && hit.getSide()==side && use(mc,hit)) return false;
            }
            double across=(mc.player.getX()-anchor.getX()-.5)*stepX+(mc.player.getZ()-anchor.getZ()-.5)*stepZ;
            if(across<.55) approach(mc,new Vec3d(anchor.getX()+.5+stepX*.64,mc.player.getY(),
                    anchor.getZ()+.5+stepZ*.64),true);
            return false;
        }
        BlockPos overhead=mc.player.getBlockPos().up(2);
        BlockPos blocked=dy>.60 && !open(mc,overhead) ? overhead :
                !open(mc,target.up()) ? target.up() : !open(mc,target) ? target : null;
        if (blocked!=null) {
            select(mc,Items.IRON_PICKAXE);
            if (aim(mc,blocked,null)!=null) mc.options.keyAttack.setPressed(true);
            return false;
        }
        boolean protect=mc.world.getBlockState(floor).getBlock()==Blocks.MAGMA_BLOCK;
        boolean nextGap=cell+1<list.size() && open(mc,list.get(cell+1).down());
        if (!protect && !nextGap && cell+2<list.size() && Math.abs(dy)<.4) {
            // Continue through consecutive collinear grounded cells without stop-start motion.
            int end=cell;
            while(end<Math.min(cell+4,list.size()-1)) {
                BlockPos a=list.get(end),b=list.get(end+1);
                if(b.getY()!=a.getY() || open(mc,b.down()) || !open(mc,b)||!open(mc,b.up())
                        || mc.world.getBlockState(b.down()).getBlock()==Blocks.MAGMA_BLOCK) break;
                if (Math.abs(dx)>.6 && (b.getZ()!=target.getZ() || (b.getX()-a.getX())*dx<=0)) break;
                if (Math.abs(dz)>.6 && (b.getX()!=target.getX() || (b.getZ()-a.getZ())*dz<=0)) break;
                if (Math.abs(dx)<=.6 && Math.abs(dz)<=.6) break;
                end++;
            }
            if(end>cell) {
                center=Vec3d.ofCenter(list.get(end));
                if (mc.player.squaredDistanceTo(Vec3d.ofCenter(list.get(cell+1)))<
                        mc.player.squaredDistanceTo(Vec3d.ofCenter(target))) cell++;
            }
        }
        Race.aim(mc,new Vec3d(center.x,mc.player.getEyeY()-.2,center.z));
        approach(mc,center,protect || (nextGap && dy<.35));
        mc.options.keyJump.setPressed(dy>.46 || (!protect && mc.player.horizontalCollision && dy>-.3));
        return false;
    }

    private static boolean place(MinecraftClient mc, Step s) {
        if(s.tick==3290) return TravelPearls.catchWall(mc);
        Block wanted = ((BlockItem)item(s.value)).getBlock();
        BlockState current = mc.world.getBlockState(s.target);
        if (current.getBlock() == wanted) return true;
        if (s.tick==2397 && mc.player.isOnGround() && mc.player.getY()>s.target.getY()+1.8
                && Math.hypot(mc.player.getX()-.5-s.target.getX(),mc.player.getZ()-.5-s.target.getZ())<1.4) return true;
        // Naturally placed terrain already supports the crossing, but frame blocks must be obsidian.
        if (!current.getMaterial().isReplaceable() && wanted != Blocks.OBSIDIAN && wanted != Blocks.CRAFTING_TABLE) return true;
        Item building=item(s.value);
        if ((building==Items.NETHERRACK || building==Items.DIRT) && count(mc,building)==0) {
            boolean returnLedge=s.tick>=5174 && s.tick<=5217;
            if (!(returnLedge ? ReturnFrame.selectSupport(mc) : selectBuildingBlock(mc,false))) return false;
        } else if (!select(mc,building)) return false;
        for (Direction face : new Direction[]{Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.DOWN}) {
            BlockPos support = s.target.offset(face.getOpposite());
            if (mc.world.getBlockState(support).getMaterial().isReplaceable()) continue;
            if (use(mc,aim(mc,support,face))) return false;
        }
        if (age-entered > 7) {
            Vec3d center = Vec3d.ofCenter(s.target);
            if (mc.player.getBoundingBox().intersects(new Box(s.target)) && mc.player.getY() >= s.target.getY()-.1)
                mc.options.keyJump.setPressed(true);
            else approach(mc,center,true);
        }
        return false;
    }

    private static boolean openTradingStairs(MinecraftClient mc) {
        if(mc.world.getChunkManager().getChunk(-149>>4,118>>4,net.minecraft.world.chunk.ChunkStatus.FULL,false)==null) {
            status="waiting for the native bastion stair chunk";
            return true;
        }
        // Remove only the temporary east wall. The normal Y40 gilded-blackstone canopy stays in place.
        for(int y=39;y>=37;y--) {
            BlockPos dirt=new BlockPos(-149,y,118);
            if(mc.world.getBlockState(dirt).getBlock()!=Blocks.DIRT) continue;
            Vec3d shelf=new Vec3d(-150.65,39,118.57);
            if(mc.player.getX()>-150.25 || mc.player.getY()<38.85 ||
                    Math.hypot(mc.player.getX()-shelf.x,mc.player.getZ()-shelf.z)>.78) {
                Race.aim(mc,new Vec3d(shelf.x,mc.player.getEyeY(),shelf.z));
                approach(mc,shelf,false);
                mc.options.keyJump.setPressed(mc.player.isOnGround() && mc.player.getY()<38.85);
            } else {
                mc.options.keySneak.setPressed(true);
                if (!select(mc,Opening.shovelOrPick(mc))) return true;
                mc.options.keyAttack.setPressed(aim(mc,dirt,null)!=null);
            }
            status="reopening the piglin stairs from the upper ledge";
            return true;
        }
        return false;
    }

    private static boolean mine(MinecraftClient mc, Step s) {
        if (s.tick==2391 && !retreatingToShaft && openTradingStairs(mc)) return false;
        if (mc.world.getBlockState(s.target).getMaterial().isReplaceable()) return true;
        select(mc,Items.IRON_PICKAXE);
        if (s.tick==2419 || s.tick==2433) {
            Race.stop(mc);
            // Keep the pick aimed at the same block while moving into the one-block opening.
            // Sneaking on its rim prevents the final slide into the shaft after the floor breaks.
            Vec3d center=Vec3d.ofCenter(s.target);
            double dx=center.x-mc.player.getX(), dz=center.z-mc.player.getZ();
            boolean aligned=Math.abs(dx)<.19 && Math.abs(dz)<.19;
            boolean hit;
            if (Math.abs(dx)<.48 && Math.abs(dz)<.48) {
                mc.player.pitch=90f;
                mc.gameRenderer.updateTargetedEntity(1);
                hit=mc.crosshairTarget instanceof BlockHitResult
                        && ((BlockHitResult)mc.crosshairTarget).getBlockPos().equals(s.target);
            } else hit=aim(mc,s.target,Direction.UP)!=null;
            mc.options.keyAttack.setPressed(hit);
            if (!aligned) BastionApproach.move(mc,center.x,center.z,false,false);
            return false;
        }
        BlockHitResult hit = aim(mc,s.target,null);
        if (hit == null) {
            if (s.tick>=3711 && s.tick<=4353 && age-entered>6 && Fortress.clearToward(mc,s.target)) return false;
            if (s.tick>=2476 && s.tick<=2774 && age-entered>14) {
                Vec3d target=Vec3d.ofCenter(s.target);
                Race.aim(mc,target);
                mc.gameRenderer.updateTargetedEntity(1);
                if (mc.crosshairTarget instanceof BlockHitResult && mc.crosshairTarget.getType()==HitResult.Type.BLOCK) {
                    BlockPos obstruct=((BlockHitResult)mc.crosshairTarget).getBlockPos();
                    String material=Registry.BLOCK.getId(mc.world.getBlockState(obstruct).getBlock()).getPath();
                    if (material.contains("blackstone") && !obstruct.equals(mc.player.getBlockPos().down())) {
                        mc.options.keyAttack.setPressed(true);
                        return false;
                    }
                }
            }
            if (age-entered>6) {
                Vec3d origin=Race.stance(s.tick);
                if (mc.player.squaredDistanceTo(origin)>.45) {
                    Race.aim(mc,new Vec3d(origin.x,mc.player.getEyeY(),origin.z));
                    approach(mc,origin,false);
                    mc.options.keyJump.setPressed(mc.player.horizontalCollision || origin.y>mc.player.getY()+.45);
                }
            }
            return false;
        }
        mc.options.keyAttack.setPressed(true);
        return false;
    }

    private static boolean coverNorthOfThirdChest(MinecraftClient mc) {
        BlockPos gap=new BlockPos(-150,39,117);
        if (!open(mc,gap)) return false;
        if (mc.world.getChunkManager().getChunk(gap.getX()>>4,gap.getZ()>>4,
                net.minecraft.world.chunk.ChunkStatus.FULL,false)==null || open(mc,gap.up())) {
            status="waiting for the chest roof to load";return true;
        }
        if (northRoofStarted<0) northRoofStarted=age;
        if (age-northRoofStarted>14) return false;
        if (!select(mc,Items.DIRT)) return true;
        for (Entity entity:mc.world.getEntities()) if (entity.isAlive() && entity.getBoundingBox().intersects(new Box(gap))) return false;
        for (Direction side:new Direction[]{Direction.EAST,Direction.DOWN}) {
            BlockHitResult hit=aim(mc,gap.offset(side.getOpposite()),side);
            if (hit!=null) { use(mc,hit);break; }
        }
        status="covering the northern lip above the gold chest";
        return true;
    }

    private static boolean loot(MinecraftClient mc, Step s) {
        boolean pearlChest=s.tick==5479 && s.value.equals("ender_pearl");
        if (pearlChest && count(mc,Items.ENDER_PEARL)>=12
                && !(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler))return true;
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler)) {
            if (s.value.contains("arrow")) {
                // Opening the chest from maximum reach brakes the jump one block short of
                // the raised dirt support, leaving the player on the floor with the piglins.
                Vec3d landing=new Vec3d(-149.66,38,118.50);
                if (Math.hypot(mc.player.getX()-landing.x,mc.player.getZ()-landing.z)>.43
                        || mc.player.getY()<37.92) {
                    Race.aim(mc,new Vec3d(landing.x,mc.player.getEyeY(),landing.z));
                    approach(mc,landing,false);
                    mc.options.keySprint.setPressed(true);
                    mc.options.keyJump.setPressed(mc.player.isOnGround() && mc.player.getY()<37.98);
                    return false;
                }
                if (barricade<eastCover.length) {
                    if (place(mc,eastCover[barricade])) barricade++;
                    status="covering the open side of the bastion ledge";
                    return false;
                }
            }
            if(s.tick==2187 && BastionEgress.arrived) {BastionEgress.openSecondChest(mc);return false;}
            use(mc,aim(mc,s.target,null));
            if (age-entered>12 && !(s.tick==2089 && BastionApproach.onLootPerch(mc))
                    && mc.player.getCameraPosVec(1).squaredDistanceTo(Vec3d.ofCenter(s.target))>15)
                approach(mc,Vec3d.ofCenter(s.target),false);
            return false;
        }
        List<String> wants=Arrays.asList(s.value.split("\\+"));
        boolean takeLeggings=pearlChest && mc.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS).isEmpty();
        if (!VisibleSlots.loot(mc,stack->wants.contains(Registry.ITEM.getId(stack.getItem()).getPath())
                || (takeLeggings && stack.getItem()==Items.IRON_LEGGINGS),false)) return false;
        if(takeLeggings && count(mc,Items.IRON_LEGGINGS)>0)equipReturnLeggings=true;
        mc.player.closeHandledScreen();
        if (s.value.contains("golden_helmet")) equipAfterChest=true;
        return true;
    }

    public static boolean climbLedge(MinecraftClient mc,double targetY) {
        if (pillar==null) {
            if (!mc.player.isOnGround() || targetY<mc.player.getY()+1.45) return false;
            BlockPos foot=mc.player.getBlockPos();
            if (open(mc,foot.down()) || !open(mc,foot) || !open(mc,foot.up(2))) return false;
            pillar=foot;
        }
        if (!open(mc,pillar) || Math.abs(mc.player.getX()-.5-pillar.getX())>1
                || Math.abs(mc.player.getZ()-.5-pillar.getZ())>1) {
            pillar=null;
            return false;
        }
        if (!select(mc,Items.DIRT) && !select(mc,Items.NETHERRACK)) { pillar=null; return false; }
        Race.stop(mc);
        mc.options.keyJump.setPressed(true);
        BlockHitResult top=aim(mc,pillar.down(),Direction.UP);
        if (mc.player.getY()>=pillar.getY()+1 && top!=null) use(mc,top);
        status="jumping up onto the bastion ledge";
        return true;
    }

    /** Fill part of the spawner wait using the same survival crafting and placement steps. */
    private static boolean earlyBow(MinecraftClient mc) {
        if (count(mc,Items.BOW)>0) {
            if (bowStep>=0 && mc.currentScreen!=null) mc.player.closeHandledScreen();
            bowStep=-1;
            return false;
        }
        if (bowStep<0) {
            int rods=count(mc,Items.BLAZE_ROD);
            if (age<bowRetryAfter || rods<2 || rods>=6 || count(mc,Items.STRING)<3
                    || mc.currentScreen!=null || !mc.player.isOnGround() || mc.player.isUsingItem()
                    || mc.player.getHealth()<12 || mc.player.getCameraPosVec(1).squaredDistanceTo(Vec3d.ofCenter(bowBench))>16)
                return false;
            boolean placed=mc.world.getBlockState(bowBench).getBlock()==Blocks.CRAFTING_TABLE;
            if (!placed && (!mc.world.getBlockState(bowBench).getMaterial().isReplaceable()
                    || mc.player.getBoundingBox().intersects(new Box(bowBench)))) return false;
            if ((!placed && count(mc,Items.CRAFTING_TABLE)==0 && count(mc,Items.OAK_PLANKS)<4)
                    || (count(mc,Items.STICK)<3 && count(mc,Items.OAK_PLANKS)<2)) return false;
            bowStep=0;
            bowStarted=age;
        }
        boolean unsafe=false;
        for (Entity e:mc.world.getEntities()) {
            if (e instanceof BlazeEntity && e.isAlive() && e.squaredDistanceTo(mc.player)<6*6 && mc.player.canSee(e)) {
                unsafe=true;
                break;
            }
        }
        if (unsafe || age-bowStarted>60 || mc.player.getCameraPosVec(1).squaredDistanceTo(Vec3d.ofCenter(bowBench))>20) {
            if (mc.currentScreen!=null) mc.player.closeHandledScreen();
            bowStep=-1;
            bowRetryAfter=age+20;
            return false;
        }
        Race.stop(mc);
        mc.options.keyAttack.setPressed(false);
        mc.interactionManager.cancelBlockBreaking();
        Step step=bowSteps.get(bowStep);
        status="crafting the bow between blaze spawns";
        boolean placed=mc.world.getBlockState(bowBench).getBlock()==Blocks.CRAFTING_TABLE;
        boolean done;
        if (step.kind.equals("place")) done=placed || place(mc,step);
        else done=(placed && step.value.equals("crafting_table")) || recipe(mc,step);
        if (done && ++bowStep==bowSteps.size()) bowStep=-1;
        return true;
    }

    private static boolean recipe(MinecraftClient mc, Step s) {
        Item result=item(s.value);
        int goal = result == Items.STICK ? 3 : result == Items.ENDER_EYE ? 11
                : result == Items.BLAZE_POWDER ? 11 : result == Items.GOLD_INGOT ? 18 : 1;
        if(result==Items.GOLD_INGOT && EarlyBarter.refreshLastCraft(mc,age))return false;
        boolean complete=result==Items.GOLD_INGOT && EarlyBarter.paid()
                ? EarlyBarter.remainingGoldCrafted(mc) : count(mc,result)>=goal;
        if (complete) {
            if (mc.currentScreen!=null) mc.player.closeHandledScreen();
            return true;
        }
        if (result == Items.BOW || result == Items.GOLDEN_APPLE) {
            if (!(mc.player.currentScreenHandler instanceof CraftingScreenHandler)) {
                use(mc,aim(mc,s.target,null));
                return false;
            }
        } else if (!(mc.currentScreen instanceof InventoryScreen)) {
            mc.openScreen(new InventoryScreen(mc.player));
            return false;
        }
        ScreenHandler sh=mc.player.currentScreenHandler;
        if (age%3!=0) return false;
        int needed=goal-count(mc,result);
        int stagedPearls=0;
        if (result==Items.ENDER_EYE) {
            for (int i=1;i<=4;i++) {
                ItemStack stack=sh.getSlot(i).getStack();
                if (stack.getItem()==Items.ENDER_PEARL) stagedPearls+=stack.getCount();
            }
        }
        boolean reserve=result==Items.ENDER_EYE && (count(mc,Items.ENDER_PEARL)+stagedPearls)>needed;
        if (sh.getSlot(0).getStack().getItem()==result) {
            if (reserve && stagedPearls<needed) {
                mc.world.getRecipeManager().get(new Identifier("ender_eye")).ifPresent(
                        r->mc.interactionManager.clickRecipe(sh.syncId,r,false));
                return false;
            }
            mc.interactionManager.clickSlot(sh.syncId,0,0,SlotActionType.QUICK_MOVE,mc.player);
            return false;
        }
        String path = result == Items.GOLD_INGOT ? "gold_ingot_from_gold_block" : s.value;
        Optional<? extends Recipe<?>> r=mc.world.getRecipeManager().get(new Identifier(path));
        if (r.isPresent() && mc.player.getRecipeBook().contains(r.get()))
            mc.interactionManager.clickRecipe(sh.syncId,r.get(),result==Items.GOLD_INGOT || result==Items.BLAZE_POWDER || (result==Items.ENDER_EYE && !reserve));
        return false;
    }

    private static int lastDropSlot, lastDropObservation;

    private static Item previousDropItem;
    private static boolean rodSlotsPrepared;

    private static boolean confirmedDropHand(MinecraftClient mc,Item item) {
        if (mc.currentScreen != null || mc.player.getMainHandStack().getItem() != item
                || mc.getServer() == null) return false;
        // Vanilla transmits the number-key selection on the next interaction-manager tick.
        // The drop must follow that packet even if an older server snapshot shows this slot.
        int slot = mc.player.inventory.selectedSlot;
        boolean sustained = previousDropItem == item && slot == lastDropSlot && age - lastDropObservation == 1;
        previousDropItem = item;
        lastDropSlot = slot;
        lastDropObservation = age;
        net.minecraft.server.network.ServerPlayerEntity player =
                mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        return sustained && player != null && player.inventory.selectedSlot == slot
                && player.getMainHandStack().getItem() == item;
    }

    private static final Item[] ROD_DISCARDS={Items.GLASS_BOTTLE,Items.KELP,Items.OAK_SAPLING,
            Items.OAK_PRESSURE_PLATE,Items.BONE,Items.NETHER_BRICK,Items.MAGMA_BLOCK,Items.SOUL_SAND,
            Items.FIRE_CHARGE,Items.ROTTEN_FLESH,Items.IRON_NUGGET,Items.LEATHER,Items.QUARTZ};

    private static int emptyMainSlots(MinecraftClient mc) {
        int free=0;
        for(ItemStack stack:mc.player.inventory.main)if(stack.isEmpty())free++;
        return free;
    }

    static boolean makeRoomForRods(MinecraftClient mc,int goal) {
        if(count(mc,Items.BLAZE_ROD)>0 || emptyMainSlots(mc)>=goal)return false;
        for(Item item:ROD_DISCARDS) {
            if(count(mc,item)==0)continue;
            Race.stop(mc);
            mc.options.keyAttack.setPressed(false);
            status="discarding "+Registry.ITEM.getId(item)+" to make room for blaze rods";
            if(!select(mc,item))return true;
            Vec3d eye=mc.player.getCameraPosVec(1);
            Vec3d away=new Vec3d(-1,.15,0).normalize();
            Race.aim(mc,eye.add(away.multiply(6)));
            if(mc.player.getRotationVec(1).dotProduct(away)>.98 && confirmedDropHand(mc,item))
                mc.player.dropSelectedItem(true);
            return true;
        }
        return false;
    }

    private static boolean feed(MinecraftClient mc, Step s) {
        if(s.tick==2582){
            int paid=WellApple.pay(mc,age);
            if(paid>=0){status="reserving eight and paying the acknowledged west-well balance";return paid==1;}
        }
        if (count(mc,Items.GOLD_INGOT)==0) return true;
        if (select(mc,Items.GOLD_INGOT)) dropTradeStack(mc,0);
        return false;
    }

    static boolean dropTradeStack(MinecraftClient mc,int exactCount) {
        Vec3d origin=new Vec3d(-154.70,35,119.60);
        if (mc.player.squaredDistanceTo(origin)>.12) { approach(mc,origin,true); return false; }
        // The reference ingots leave the hand at eye level and travel west into the trap.
        // Pointing at their spawn coordinate would put them on our own floor one block below the pigs.
        double yaw=Math.toRadians(88.63),pitch=Math.toRadians(-10.78);
        Vec3d direction=new Vec3d(-Math.sin(yaw)*Math.cos(pitch),-Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch));
        Race.aim(mc,mc.player.getCameraPosVec(1).add(direction.multiply(8)));
        if (mc.player.getRotationVec(1).dotProduct(direction)>.99 && age-lastUse>=3 && confirmedDropHand(mc,Items.GOLD_INGOT)) {
            if(exactCount>0) {
                net.minecraft.server.network.ServerPlayerEntity self=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
                if(self==null || self.getMainHandStack().getCount()!=exactCount
                        || mc.player.getMainHandStack().getCount()!=exactCount)return false;
            }
            boolean dropped=mc.player.dropSelectedItem(true);
            lastUse=age;
            return dropped;
        }
        return false;
    }

    private static boolean collect(MinecraftClient mc, Item item, int goal) {
        if (count(mc,item)>=goal) return true;
        ItemEntity closest=null;
        for (Entity e:mc.world.getEntities()) {
            if (!(e instanceof ItemEntity) || ((ItemEntity)e).getStack().getItem()!=item || e.squaredDistanceTo(mc.player)>81) continue;
            if (closest==null || e.squaredDistanceTo(mc.player)<closest.squaredDistanceTo(mc.player)) closest=(ItemEntity)e;
        }
        if (closest!=null) approach(mc,closest.getPos(),false);
        return false;
    }

    private static final Item[] SHAFT_SUPPORTS={Items.DIRT,Items.BLACKSTONE,Items.NETHERRACK,
            Items.CRACKED_POLISHED_BLACKSTONE_BRICKS,Items.CHISELED_POLISHED_BLACKSTONE,
            Items.POLISHED_BLACKSTONE_BRICKS,Items.COBBLESTONE,Items.NETHER_BRICKS};

    static boolean haveShaftSupports(MinecraftClient mc,int required) {
        for(Item item:SHAFT_SUPPORTS) {
            required-=count(mc,item);
            if(required<=0)return true;
        }
        return false;
    }

    private static boolean selectShaftSupport(MinecraftClient mc) {
        for (Item item:SHAFT_SUPPORTS) {
            if (count(mc,item)>0) return select(mc,item);
        }
        status="looking for a solid block to climb the trading pillar";
        return false;
    }

    /** Keep outstanding ingots with the admiring pigs, and pillar straight up on our side. */
    static boolean climbTradingShaft(MinecraftClient mc) {
        boolean side=ShaftDetour.active(mc);
        if (side && !ShaftDetour.readyToPillar(mc)) return false;
        BlockPos shaft=side?ShaftDetour.SIDE:ShaftDetour.MAIN;
        BlockPos foot=mc.player.getBlockPos();
        if (mc.player.getY()>=38.98 && mc.player.isOnGround()) {
            return true;
        }
        Vec3d column=new Vec3d(shaft.getX()+.5,mc.player.getY(),shaft.getZ()+.5);
        // Keep the full 0.6-block player width inside the shaft before jumping.
        double offset=Math.hypot(column.x-mc.player.getX(),column.z-mc.player.getZ());
        if (offset>.13) {
            Race.aim(mc,new Vec3d(column.x,mc.player.getEyeY(),column.z));
            approach(mc,column,offset<=.45);
            if (offset>.45 && offset<1.6 && mc.player.isOnGround()) {
                BlockPos step=new BlockPos(shaft.getX(),mc.player.getY(),shaft.getZ());
                if (!open(mc,step) && open(mc,step.up()) && open(mc,step.up(2))) {
                    mc.options.keyJump.setPressed(true);
                }
            }
            status="returning to the protected pillar with trade pearls";
            return false;
        }
        BlockPos roof=shaft.up(3);
        if (foot.getY()<38 && !open(mc,roof)) {
            Item tool=mc.world.getBlockState(roof).getBlock()==Blocks.DIRT
                    ? Opening.shovelOrPick(mc) : Items.IRON_PICKAXE;
            if (!select(mc,tool)) return false;
            mc.options.keyAttack.setPressed(aim(mc,roof,null)!=null);
            status="opening the top of the escape pillar";
            return false;
        }
        mc.options.keyAttack.setPressed(false);
        if (!selectShaftSupport(mc)) return false;
        int floor=(int)Math.floor(mc.player.getY()-.001);
        while (floor>33 && open(mc,new BlockPos(shaft.getX(),floor,shaft.getZ()))) floor--;
        BlockPos base=new BlockPos(shaft.getX(),floor,shaft.getZ());
        BlockHitResult support=aim(mc,base,Direction.UP);
        mc.options.keyJump.setPressed(true);
        if (mc.player.getY()>=floor+2.002) use(mc,support);
        status="pillaring above the piglins with trade pearls";
        return false;
    }

    private static boolean leaveTradingShaft(MinecraftClient mc) {
        if (!climbTradingShaft(mc)) return false;
        while(next+1<steps.size() && steps.get(next+1).tick<=2976) next++;
        Race.advanceTo(2976);
        return true;
    }

    // The near lintel remains intact. Its upper face lets Astra collect thrown items without entering the piglins' cell.
    private static final Vec3d[] pearlLedges={new Vec3d(-155.72,38,119.50),new Vec3d(-154.50,39,119.50),
            new Vec3d(-153.52,40,119.50),new Vec3d(-152.53,40,118.54),new Vec3d(-151.50,40,118.54),
            new Vec3d(-150.66,39,118.55)};

    private static boolean gatherFromLedges(MinecraftClient mc,int pearls,ItemEntity nearest) {
        if (pearlWalk==0) {
            if (!climbTradingShaft(mc)) return false;
            pearlWalk=1;
        }
        if (pearls>=13 && pearlWalk>0) {
            if (pearlWalk<=2) return leaveTradingShaft(mc);
            pearlWalk=-Math.min(pearlWalk-1,pearlLedges.length-1);
        }
        if (pearlWalk==-1) return leaveTradingShaft(mc);
        int index=pearlWalk>0?Math.min(pearlWalk-1,pearlLedges.length-1):-pearlWalk;
        Vec3d to=pearlLedges[index];
        if (pearlWalk>pearlLedges.length && nearest!=null) to=nearest.getPos();
        double d=Math.hypot(to.x-mc.player.getX(),to.z-mc.player.getZ());
        if (d<.32 && Math.abs(to.y-mc.player.getY())<1.15 && mc.player.isOnGround()) {
            if (pearlWalk<=pearlLedges.length) pearlWalk++;
            return false;
        }
        Race.aim(mc,new Vec3d(to.x,mc.player.getEyeY()-.25,to.z));
        approach(mc,to,d<.42 && to.y<=mc.player.getY()+.25);
        if (d<1.25 && to.y>mc.player.getY()+.48) mc.options.keyJump.setPressed(true);
        status="collecting barter pearls from above the intact lintel "+pearlWalk;
        return false;
    }

    static void lowerShield(MinecraftClient mc) {
        if (mc.player.isUsingItem() && mc.player.getActiveItem().getItem()==Items.SHIELD) {
            mc.options.keyUse.setPressed(false);
            mc.interactionManager.stopUsingItem(mc.player);
        }
    }

    /** Uses the real offhand item. The server applies its normal five-tick raise delay. */
    private static boolean shield(MinecraftClient mc,boolean waiting) {
        if (mc.currentScreen!=null || mc.player.getOffHandStack().getItem()!=Items.SHIELD) return false;
        PiglinEntity threat=null;
        double best=144;
        for(Entity e:mc.world.getEntities()) {
            if (!(e instanceof PiglinEntity) || !e.isAlive() || !mc.player.canSee(e)) continue;
            PiglinEntity pig=(PiglinEntity)e;
            double d=pig.squaredDistanceTo(mc.player);
            PiglinEntity.Activity activity=pig.getActivity();
            boolean melee=activity==PiglinEntity.Activity.ATTACKING_WITH_MELEE_WEAPON && d<12;
            boolean bow=activity==PiglinEntity.Activity.CROSSBOW_CHARGE
                    || (activity==PiglinEntity.Activity.CROSSBOW_HOLD && (waiting || mc.player.hurtTime>0));
            if (!(bow || melee) || d>=best) continue;
            best=d;threat=pig;
        }
        if (threat==null || (!waiting && age%31>=15)) { lowerShield(mc);return false; }
        Race.stop(mc);mc.options.keyAttack.setPressed(false);
        Race.aim(mc,threat.getBoundingBox().getCenter());
        if (!mc.player.isUsingItem() && !mc.interactionManager.interactItem(mc.player,mc.world,Hand.OFF_HAND).isAccepted()) return false;
        mc.options.keyUse.setPressed(true);
        status="blocking an approaching piglin with the offhand shield";
        return true;
    }

    private static void descendTradingShaft(MinecraftClient mc) {
        Vec3d shaft = new Vec3d(-154.50, mc.player.getY(), 119.50);
        if (Math.hypot(shaft.x-mc.player.getX(),shaft.z-mc.player.getZ()) > .24) {
            Race.aim(mc,shaft.add(0,1.1,0));
            approach(mc,shaft,false);
            status="returning to the trading pillar above the settled pearls";
            return;
        }
        BlockPos support = new BlockPos(-155, Math.floor(mc.player.getY()-.02), 119);
        net.minecraft.block.Block block = mc.world.getBlockState(support).getBlock();
        for (Item item:SHAFT_SUPPORTS) {
            if (block.asItem()!=item) continue;
            if (!select(mc,item==Items.DIRT ? Opening.shovelOrPick(mc) : Items.IRON_PICKAXE)) return;
            mc.options.keyAttack.setPressed(aim(mc,support,Direction.UP)!=null);
            break;
        }
        status="descending the protected trading pillar to the settled pearls";
    }

    private static final BlockPos shelterBench = new BlockPos(-155,36,118);
    private static final Step makeShelterBench = new Step("2583,craft,-155,36,118,crafting_table");
    private static final Step makeShelterSticks = new Step("2583,craft,-155,36,118,stick");
    private static final Step makeShelterBow = new Step("2583,craft,-155,36,118,bow");
    private static int shelterBowStart, shelterBowStep, shelterBowRetryAfter;

    private static final Step makeShelterApple = new Step("2583,craft,-155,36,118,golden_apple");
    private static int appleStarted;

    private static boolean craftShelterApple(MinecraftClient mc){
        if(!WellApple.reserved())return false;
        net.minecraft.server.network.ServerPlayerEntity self=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if(self==null)return false;
        if(count(mc,Items.GOLDEN_APPLE)>0 && self.inventory.count(Items.GOLDEN_APPLE)>0){
            if(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen)mc.player.closeHandledScreen();
            WellApple.finish();return false;
        }
        if(appleStarted==0)appleStarted=age;
        if(mc.player.hurtTime>0 || mc.player.getHealth()<8 || age-appleStarted>105
                || mc.player.squaredDistanceTo(-154.70,35,119.60)>1.2){
            if(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen)mc.player.closeHandledScreen();
            mc.options.keyAttack.setPressed(false);WellApple.finish();return false;
        }
        Race.stop(mc);lowerShield(mc);mc.options.keyAttack.setPressed(false);
        if(mc.world.getBlockState(shelterBench).getBlock()==Blocks.CRAFTING_TABLE){
            status="crafting a golden apple with the eight reserved ingots";
            recipe(mc,makeShelterApple);return true;
        }
        if(count(mc,Items.CRAFTING_TABLE)==0){
            // During recipe-book placement the four planks can be in the crafting slots.
            if(count(mc,Items.OAK_PLANKS)<4 && !(mc.currentScreen instanceof InventoryScreen)){
                WellApple.finish();return false;
            }
            status="crafting the workbench for the apple and bow";
            recipe(mc,makeShelterBench);return true;
        }
        if(mc.currentScreen instanceof InventoryScreen){mc.player.closeHandledScreen();return true;}
        if(!mc.world.getBlockState(shelterBench).getMaterial().isReplaceable()){
            status="opening the north workbench niche for the apple and bow";
            if(select(mc,Items.IRON_PICKAXE))mc.options.keyAttack.setPressed(aim(mc,shelterBench,null)!=null);
        }else{
            status="placing the workbench beside the protected trading shelf";
            if(select(mc,Items.CRAFTING_TABLE))use(mc,aim(mc,shelterBench.down(),Direction.UP));
        }
        return true;
    }

    private static boolean craftWhileBartering(MinecraftClient mc) {
        boolean placed = mc.world.getBlockState(shelterBench).getBlock() == Blocks.CRAFTING_TABLE;
        boolean ready = mc.player.hurtTime == 0 && mc.player.getHealth() >= 8;
        boolean canOpenNiche = placed || mc.world.getBlockState(shelterBench).getMaterial().isReplaceable()
                || count(mc,Items.IRON_PICKAXE) > 0;
        boolean hasBow = count(mc,Items.BOW) > 0;
        if (shelterBowStart > 0 && (hasBow || !ready || !canOpenNiche || age - shelterBowStart > 95)) {
            if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen)
                mc.player.closeHandledScreen();
            mc.options.keyAttack.setPressed(false);
            mc.interactionManager.cancelBlockBreaking();
            shelterBowStart = shelterBowStep = 0;
            shelterBowRetryAfter = age + 8;
            return false;
        }
        if (shelterBowStart == 0) {
            int boardsNeeded = placed || count(mc,Items.CRAFTING_TABLE)>0 ? 0 : 4;
            if (count(mc,Items.STICK)<3) boardsNeeded+=2;
            if (hasBow || !ready || !canOpenNiche || age < shelterBowRetryAfter
                    || mc.currentScreen!=null || count(mc,Items.STRING)<3
                    || count(mc,Items.OAK_PLANKS)<boardsNeeded
                    || mc.player.squaredDistanceTo(-154.70,35,119.60)>1.2) return false;
            shelterBowStart=age;
        }
        Race.stop(mc);
        mc.options.keyAttack.setPressed(false);
        switch (shelterBowStep) {
            case 0:
                status="crafting the bow workbench under the piglin shelf";
                if (placed || recipe(mc,makeShelterBench)) shelterBowStep++;
                break;
            case 1:
                status="crafting bow sticks under the piglin shelf";
                if (recipe(mc,makeShelterSticks)) shelterBowStep++;
                break;
            case 2:
                if (placed) { shelterBowStep++; break; }
                if (!mc.world.getBlockState(shelterBench).getMaterial().isReplaceable()) {
                    status="opening a workbench niche in the north shelter wall";
                    if (select(mc,Items.IRON_PICKAXE)) mc.options.keyAttack.setPressed(aim(mc,shelterBench,null)!=null);
                } else {
                    status="placing the workbench in the shelter wall";
                    if (select(mc,Items.CRAFTING_TABLE)) use(mc,aim(mc,shelterBench.down(),Direction.UP));
                }
                break;
            default:
                status="crafting the bow while the piglins barter";
                recipe(mc,makeShelterBow);
        }
        return true;
    }

    private static final BlockPos CHEST_PEARL=new BlockPos(-149,36,117);
    private static final BlockPos[] CHEST_STAIRS={
            new BlockPos(-151,39,118),new BlockPos(-150,38,118),new BlockPos(-149,37,118),CHEST_PEARL,
            new BlockPos(-149,37,118),new BlockPos(-150,38,118),new BlockPos(-151,39,118),
            new BlockPos(-152,40,118),new BlockPos(-153,40,118),new BlockPos(-153,40,119),
            new BlockPos(-154,40,119),new BlockPos(-155,39,119)};
    private static int chestPearlStep=-1, chestPearlBefore;
    private static boolean pendingWestPearls;

    /** Follow the existing south-side stairs when a barter rolls into the lower chest chamber. */
    private static boolean chestPearlStairs(MinecraftClient mc,int pearls,boolean eastDrop,boolean westDrop) {
        double x=mc.player.getX(),y=mc.player.getY(),z=mc.player.getZ();
        if(pendingWestPearls) {
            if(!westDrop || pearls>=13)pendingWestPearls=false;
            else {
                Race.stop(mc);mc.options.keyAttack.setPressed(false);
                if(y>35.1)descendTradingShaft(mc);
                else {
                    Vec3d pickup=new Vec3d(-155.43,35,119.51);
                    Race.aim(mc,pickup.add(0,1.05,0));approach(mc,pickup,false);
                    status="collecting the western pearl stack beside the trading pillar";
                }
                return true;
            }
        }
        boolean aboveChest=Math.abs(x+148.5)<.47 && Math.abs(z-117.5)<.47 && y>39.9 && y<43.2;
        if(chestPearlStep<0) {
            if(!eastDrop || pearls>=13)return false;
            boolean atStairs=collectingAbove && pearlWalk>=pearlLedges.length
                    && mc.player.squaredDistanceTo(-150.66,39.6,118.55)<2.0;
            if(!aboveChest && !atStairs)return false;
            chestPearlBefore=pearls;
            chestPearlStep=aboveChest?3:0;
        }

        Race.stop(mc);
        mc.options.keyAttack.setPressed(false);
        if(chestPearlStep<=3 && (pearls>chestPearlBefore || !eastDrop))chestPearlStep=4;
        if(chestPearlStep==3 && aboveChest) {
            // Recover a runner already above the chest. The two roof blocks only cover air
            // and a verified solid landing at Y35. Never drill below the pocket floor.
            double dx=x+148.5,dz=z-117.5;
            if(Math.abs(dx)>.19 || Math.abs(dz)>.19) {
                Vec3d center=new Vec3d(-148.5,y,117.5);
                Race.aim(mc,center.add(0,1.0,0));approach(mc,center,true);
            } else if(mc.player.isOnGround()) {
                BlockPos roof=new BlockPos(-149,Math.floor(y-.02),117);
                if(roof.getY()>=40 && roof.getY()<=41 && mc.world.getBlockState(roof).getBlock()==Blocks.BLACKSTONE
                        && select(mc,Items.IRON_PICKAXE))
                    mc.options.keyAttack.setPressed(aim(mc,roof,Direction.UP)!=null);
            }
            status="opening the two-block roof over the lower chest pearls";
            return true;
        }

        BlockPos p=CHEST_STAIRS[chestPearlStep];
        Vec3d to=new Vec3d(p.getX()+.5,p.getY(),p.getZ()+.5);
        double distance=Math.hypot(x-to.x,z-to.z);
        if(distance<.32 && Math.abs(y-to.y)<.38 && mc.player.isOnGround()) {
            if(chestPearlStep==3)return true;
            if(++chestPearlStep==CHEST_STAIRS.length) {
                // Resume the original downward dig beneath the protected trading pillar.
                chestPearlStep=-1;pendingWestPearls=westDrop && pearls<13;collectingAbove=false;pearlWalk=0;
            }
            return true;
        }
        Race.aim(mc,new Vec3d(to.x,mc.player.getEyeY()-.24,to.z));
        approach(mc,to,false);
        if(mc.player.isOnGround() && distance<1.5 && to.y>y+.42)mc.options.keyJump.setPressed(true);
        status="following the lower chest stair to the barter pearls "+chestPearlStep;
        return true;
    }

    private static boolean shelter(MinecraftClient mc,Step s) {
        int pearls=count(mc,Items.ENDER_PEARL);
        if (EastHallRecovery.active() && EastHallRecovery.tick(mc)) {
            status=EastHallRecovery.status;
            return false;
        }
        if (shelterBowStart>0 && craftWhileBartering(mc)) return false;
        int westUpper=0;
        boolean eastDrop=false,westDrop=false;
        int visible=pearls;
        int settledPearls=pearls, lowerPearls=pearls;
        ItemEntity nearest=null;
        Vec3d protectedSpot=new Vec3d(-154.70,35,119.60);
        ItemEntity missed=null;
        for (Entity entity:mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity) || !entity.isAlive()) continue;
            ItemEntity drop=(ItemEntity)entity;
            if (drop.getStack().getItem()==Items.ENDER_PEARL && drop.getX()>=-159 && drop.getX()<=-146
                    && drop.getY()>=34 && drop.getY()<=42 && drop.getZ()>=115 && drop.getZ()<=123) {
                if (drop.isOnGround()) settledPearls+=drop.getStack().getCount();
                if (EastHallRecovery.onWestLintel(drop)) westUpper+=drop.getStack().getCount();
                if(drop.isOnGround() && drop.getBlockPos().equals(CHEST_PEARL))eastDrop=true;
                if(drop.isOnGround() && drop.getBlockPos().equals(new BlockPos(-157,36,119)))westDrop=true;
                if (drop.squaredDistanceTo(protectedSpot)<=38 && drop.getY()<37.6) lowerPearls+=drop.getStack().getCount();
                if (nearest==null || drop.squaredDistanceTo(mc.player)<nearest.squaredDistanceTo(mc.player)) nearest=drop;
            }
            if (drop.squaredDistanceTo(protectedSpot)>38) continue;
            if (drop.getStack().getItem()==Items.ENDER_PEARL) visible+=drop.getStack().getCount();
            if (drop.getStack().getItem()==Items.GOLD_INGOT && drop.getY()<35.45
                    && drop.squaredDistanceTo(protectedSpot)<3.0 && age-entered>45) missed=drop;
        }
        if(WellApple.reserved() && (visible>=13 || collectingAbove)){
            // Do not hold the escape to start an apple after the required pearl batch has arrived.
            if(appleStarted>0 && mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen){
                ScreenHandler grid=mc.player.currentScreenHandler;
                if(grid instanceof CraftingScreenHandler && grid.getSlot(0).getStack().getItem()==Items.GOLDEN_APPLE){
                    mc.interactionManager.clickSlot(grid.syncId,0,0,SlotActionType.QUICK_MOVE,mc.player);return false;
                }
                mc.player.closeHandledScreen();
            }
            WellApple.finish();
        }
        // The last barter can settle while the vanilla recipe screen for the next gold block is still open.
        if ((collectingAbove || settledPearls>=13 || lowerPearls>=13) && mc.currentScreen instanceof InventoryScreen) {
            mc.player.closeHandledScreen();
            return false;
        }
        if(chestPearlStairs(mc,pearls,eastDrop,westDrop))return false;
        if (pearls<13 && lowerPearls>=13 && mc.player.getY()>35.1) {
            collectingAbove=false;
            pearlWalk=0;
            descendTradingShaft(mc);
            return false;
        }
        if (settledPearls>=13 && lowerPearls<13) collectingAbove=true;
        if (collectingAbove) return gatherFromLedges(mc,pearls,nearest);
        if (pearls>=13) {
            if (craftWhileBartering(mc)) return false;
            // This weekly seed's three pearl barters give four, five and four.
            // Loose stacks within the player's pickup box are collected automatically on departure.
            lowerShield(mc);
            return leaveTradingShaft(mc);
        }
        if (visible>=13) {
            lowerShield(mc);
            if (age-entered>45) collect(mc,Items.ENDER_PEARL,13);
            status="collecting the piglin pearl drops";
            return false;
        }
        if (UpperLootRoute.enabled && EastHallRecovery.begin(mc,pearls,westUpper,
                Math.max(visible,Math.max(settledPearls,lowerPearls)))) {
            status=EastHallRecovery.status;
            return false;
        }
        // Gold and barter drops fit under the near lintel. Adult piglins need two blocks of clearance.
        BlockPos lid=new BlockPos(-157,37,119);
        if(!mc.world.getBlockState(lid).isAir()) {
            status="opening the far side of the piglin shelf at "+lid.toShortString();
            select(mc,Items.IRON_PICKAXE);
            mc.options.keyAttack.setPressed(aim(mc,lid,null)!=null);
            return false;
        }
        mc.options.keyAttack.setPressed(false);
        if(craftShelterApple(mc))return false;
        if (count(mc,Items.GOLD_BLOCK)>0 || mc.currentScreen instanceof InventoryScreen) {
            if (!(mc.currentScreen instanceof InventoryScreen)) {
                mc.openScreen(new InventoryScreen(mc.player));
            } else {
                ScreenHandler grid=mc.player.currentScreenHandler;
                if (grid.getSlot(0).getStack().getItem()==Items.GOLD_INGOT) {
                    mc.interactionManager.clickSlot(grid.syncId,0,0,SlotActionType.QUICK_MOVE,mc.player);
                } else if (count(mc,Items.GOLD_BLOCK)==0) {
                    mc.player.closeHandledScreen();
                } else if (age%3==0) {
                    mc.world.getRecipeManager().get(new Identifier("gold_ingot_from_gold_block"))
                        .ifPresent(r->mc.interactionManager.clickRecipe(grid.syncId,r,true));
                }
            }
            return false;
        }
        if (count(mc,Items.GOLD_INGOT)>0) {
            lowerShield(mc);
            feed(mc,s);
            return false;
        }
        if (craftWhileBartering(mc)) return false;
        if (missed!=null) {
            approach(mc,missed.getPos(),true);
            status="retrieving ingots below the piglin shelf";
        } else if (!shield(mc,true)) status="waiting under cover for piglin trades ("+visible+" pearls in reach)";
        return false;
    }

    private static void rememberEarlyBarter(MinecraftClient mc, Entity recipient) {
        int ref=Race.referenceTick();
        boolean onUpperHall = ref >= 2262 && ref < 2306
                && mc.player.getX() < -126 && mc.player.getX() > -146;
        if (earlyTradeStarted>=0 || ref<2089 || ref>=2262 && !onUpperHall
                || count(mc,Items.ENDER_PEARL)>=4) return;
        // Both the first chest and the floor below the second chest are possible
        // handoff sites. The action cursor may already point to the next dirt placement.
        earlyTradeStarted=age;
        earlyTradeLocation=recipient.getPos();
    }

    static boolean offerExposedIngot(MinecraftClient mc,boolean firstChest) {
        if (mc.currentScreen!=null || age<offerRetry || count(mc,Items.GOLD_INGOT)==0) return false;
        PiglinEntity nearest=null;
        double range=12.25;
        for (Entity e:mc.world.getEntities()) {
            if (!(e instanceof PiglinEntity) || !e.isAlive() || !mc.player.canSee(e)) continue;
            PiglinEntity pig=(PiglinEntity)e;
            if (pig.isBaby() || pig.getActivity()!=PiglinEntity.Activity.ATTACKING_WITH_MELEE_WEAPON) continue;
            double d=pig.squaredDistanceTo(mc.player);
            if (d<range) { nearest=pig;range=d; }
        }
        if (nearest==null) { offerStart=-1;return false; }
        if (offerStart<0 || offeredPig!=nearest.getEntityId()) {
            offerStart=age;offeredPig=nearest.getEntityId();
        }
        // Never stall the descent trying to aim through another pig or a block.
        if (age-offerStart>8) { offerStart=-1;offerRetry=age+12;return false; }
        Race.stop(mc);mc.options.keyAttack.setPressed(false);lowerShield(mc);
        mc.options.keySneak.setPressed(firstChest);
        Race.aim(mc,nearest.getBoundingBox().getCenter());
        mc.gameRenderer.updateTargetedEntity(1);
        if (!select(mc,Items.GOLD_INGOT) || !confirmedDropHand(mc,Items.GOLD_INGOT)) return true;
        if (!(mc.crosshairTarget instanceof EntityHitResult)
                || ((EntityHitResult)mc.crosshairTarget).getEntity()!=nearest) return true;
        if (age-lastUse<3) return true;
        lastUse=age;
        if (mc.interactionManager.interactEntity(mc.player,nearest,Hand.MAIN_HAND).isAccepted())
            mc.player.swingHand(Hand.MAIN_HAND);
        else mc.player.dropSelectedItem(false);
        rememberEarlyBarter(mc,nearest);
        offerStart=-1;offerRetry=age+10;
        status="giving gold to the sword piglin guarding the descent";
        return true;
    }

    private static boolean protectFromPiglin(MinecraftClient mc) {
        if (mc.currentScreen!=null) return false;
        if (shield(mc,false)) return true;
        PiglinEntity closest=null;
        double nearest=3.5*3.5;
        for (Entity e:mc.world.getEntities()) {
            if (!(e instanceof PiglinEntity) || !e.isAlive() || !mc.player.canSee(e)) continue;
            PiglinEntity pig=(PiglinEntity)e;
            if (pig.getActivity()!=PiglinEntity.Activity.ATTACKING_WITH_MELEE_WEAPON) continue;
            double range=pig.squaredDistanceTo(mc.player);
            if (range<nearest) { closest=pig; nearest=range; }
        }
        if (closest==null) return false;
        // Stop the previous route input while facing the attacker. A forward key
        // held through a sword swing can carry the player off the one-block ledge.
        Race.stop(mc);
        if(BastionApproach.onLootPerch(mc))mc.options.keySneak.setPressed(true);
        mc.options.keyAttack.setPressed(false);
        Race.aim(mc,closest.getBoundingBox().getCenter());
        mc.gameRenderer.updateTargetedEntity(1);
        boolean barter=count(mc,Items.GOLD_INGOT)>0;
        Item held=barter?Items.GOLD_INGOT:Items.DIAMOND_SWORD;
        if (mc.player.getMainHandStack().getItem()!=held) {
            select(mc,held);
            return true;
        }
        if (!(mc.crosshairTarget instanceof EntityHitResult)) return true;
        Entity target=((EntityHitResult)mc.crosshairTarget).getEntity();
        if (!(target instanceof PiglinEntity) || ((PiglinEntity)target).getActivity()!=PiglinEntity.Activity.ATTACKING_WITH_MELEE_WEAPON) return true;
        if (barter && age-lastUse>=3 && confirmedDropHand(mc,Items.GOLD_INGOT)) {
            lastUse=age;
            if (mc.interactionManager.interactEntity(mc.player,target,Hand.MAIN_HAND).isAccepted()) {
                mc.player.swingHand(Hand.MAIN_HAND);
            } else {
                mc.player.dropSelectedItem(false);
            }
            rememberEarlyBarter(mc,target);
        } else if (!barter && mc.player.getAttackCooldownProgress(.5f)>.91f) {
            mc.interactionManager.attackEntity(mc.player,target);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        status=barter?"giving an ingot to a nearby piglin":"defending with the drawn diamond sword";
        return true;
    }

    /** Keep the shield raised while an actual handoff is in progress. */
    private static boolean guardFirstTrade(MinecraftClient mc) {
        if (mc.currentScreen != null || mc.player.getOffHandStack().getItem() != Items.SHIELD) return false;
        List<Vec3d> bearings = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof PiglinEntity) || !entity.isAlive() || !mc.player.canSee(entity)) continue;
            PiglinEntity pig = (PiglinEntity)entity;
            PiglinEntity.Activity activity = pig.getActivity();
            double range = pig.squaredDistanceTo(mc.player);
            boolean sword = activity == PiglinEntity.Activity.ATTACKING_WITH_MELEE_WEAPON && range < 36;
            boolean bow = (activity == PiglinEntity.Activity.CROSSBOW_CHARGE
                    || activity == PiglinEntity.Activity.CROSSBOW_HOLD) && range < 144;
            if (!sword && !bow) continue;
            Vec3d direction = new Vec3d(pig.getX() - mc.player.getX(), 0, pig.getZ() - mc.player.getZ());
            if (direction.lengthSquared() < .0001) continue;
            bearings.add(direction.normalize());
            weights.add(1 / Math.max(1, Math.sqrt(range)));
        }
        if (bearings.isEmpty()) {
            if (!mc.player.isUsingItem() || mc.player.getActiveItem().getItem() != Items.SHIELD) return false;
        } else {
            // A pair delimiting a visible cluster has a middle bearing that can cover both.
            // Prefer covering additional attacks, then keeping them farther from the shield edge.
            Vec3d best = bearings.get(0);
            double bestScore = -1;
            for (int i = 0; i < bearings.size(); i++) {
                for (int j = i; j < bearings.size(); j++) {
                    Vec3d center = bearings.get(i).add(bearings.get(j)).normalize();
                    if (center.lengthSquared() < .5) continue;
                    double score = 0;
                    for (int k = 0; k < bearings.size(); k++) {
                        double dot = center.dotProduct(bearings.get(k));
                        if (dot > .05) score += weights.get(k) * (1 + .1 * dot);
                    }
                    if (score > bestScore) { bestScore = score; best = center; }
                }
            }
            Race.aim(mc, mc.player.getCameraPosVec(1).add(best.multiply(8)));
            if (!mc.player.isUsingItem()
                    && !mc.interactionManager.interactItem(mc.player, mc.world, Hand.OFF_HAND).isAccepted()) return false;
        }
        mc.options.keyAttack.setPressed(false);
        mc.options.keyUse.setPressed(true);
        return true;
    }

    private static boolean equipReturnLeggings;

    private static boolean finishReturnLeggings(MinecraftClient mc) {
        if(!equipReturnLeggings)return false;
        Race.stop(mc);
        status="equipping the iron leggings from the pearl chest";
        if(!VisibleSlots.equip(mc,Items.IRON_LEGGINGS))return true;
        net.minecraft.item.Item equipped=mc.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS).getItem();
        if(equipped==Items.IRON_LEGGINGS){
            net.minecraft.server.network.ServerPlayerEntity self=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
            if(self!=null && self.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS).getItem()!=Items.IRON_LEGGINGS)
                return true;
        }
        equipReturnLeggings=false;
        return false;
    }

    private static boolean collectFirstTrade(MinecraftClient mc) {
        if(earlyTradeStarted<0)return false;
        if(count(mc,Items.ENDER_PEARL)>=4) {
            if(mc.player.getY()<34 && mc.player.getX()>-117 && mc.player.getZ()>107.5) escapingFirstFloor=true;
            earlyTradeStarted=-1;earlyTradeLocation=null;
            return false;
        }
        ItemEntity best=null;
        boolean stillAdmiring=false;
        for(Entity e:mc.world.getEntities()) {
            if (e instanceof PiglinEntity && e.isAlive() && e.getPos().squaredDistanceTo(earlyTradeLocation)<=225
                    && ((PiglinEntity)e).getOffHandStack().getItem()==Items.GOLD_INGOT) stillAdmiring=true;
            if(!(e instanceof ItemEntity) || !e.isAlive())continue;
            ItemEntity drop=(ItemEntity)e;
            if(drop.getStack().getItem()!=Items.ENDER_PEARL || drop.getPos().squaredDistanceTo(earlyTradeLocation)>225)continue;
            if(best==null || drop.squaredDistanceTo(mc.player)<best.squaredDistanceTo(mc.player))best=drop;
        }
        Race.stop(mc);
        if(best!=null) {
            lowerShield(mc);
            Vec3d goal=best.getPos();
            BlockPos pocket=best.getBlockPos();
            if (best.isOnGround() && mc.player.getY()>goal.y+1.8 && mc.player.getY()<goal.y+4.2
                    && mc.player.squaredDistanceTo(best)<24 && !open(mc,pocket.down())
                    && open(mc,pocket) && open(mc,pocket.up()) && open(mc,pocket.up(2))) {
                // A dropped item may rest against the lip while the player's wider feet
                // still touch the bridge above. Move over the center of the open fall.
                goal=new Vec3d(pocket.getX()+.5,goal.y,pocket.getZ()+.5);
            }
            Race.aim(mc,goal.add(0,.24,0));
            approach(mc,goal,false);
            status="collecting the first bastion pearl stack";
            return true;
        }
        if(age-earlyTradeStarted>200 && !stillAdmiring) {
            // A missed handoff or a different real barter may produce no local pearls.
            earlyTradeStarted=-1;earlyTradeLocation=null;
            return false;
        }
        if(!guardFirstTrade(mc) && !protectFromPiglin(mc))mc.options.keySneak.setPressed(mc.player.getY()>35.5);
        status="waiting for the first bastion piglin to finish its trade";
        return true;
    }

    private static boolean exitFirstTradeFloor(MinecraftClient mc) {
        if (!escapingFirstFloor) return false;
        if (mc.player.getX() < -121.5 || mc.player.getY()<30.5) {
            escapingFirstFloor=false;Race.advanceTo(2180);return false;
        }
        // A descent support is unnecessary once the player has reached the solid floor by foot.
        if(next<steps.size() && steps.get(next).tick==2131) { next++;entered=age; }
        Race.advanceTo(2144);Race.stop(mc);lowerShield(mc);mc.options.keyAttack.setPressed(false);
        double x=mc.player.getX()>-115.5?-116.2:-122.4;
        double z=mc.player.getX()>-115.5?114.95:116.9;
        if(mc.player.getZ()<112.3 && mc.player.getX()>-112.1) { x=-110.6;z=112.65; }
        Race.aim(mc,new Vec3d(x,mc.player.getEyeY()-.15,z));
        BastionApproach.move(mc,x,z,false,true);
        mc.options.keyJump.setPressed(mc.player.horizontalCollision);
        status="running west with the first four pearls";
        return true;
    }

    private static boolean surroundedOnWestLedge(MinecraftClient mc) {
        double x=mc.player.getX();
        if (x < -152.0 || x > -149.8 || mc.player.getY()<38.8 || mc.player.getY()>41.5) return false;
        boolean west=false,east=false;
        for (Entity e:mc.world.getEntities()) {
            if (!(e instanceof PiglinEntity) || !e.isAlive() || e.squaredDistanceTo(mc.player)>18) continue;
            PiglinEntity pig=(PiglinEntity)e;
            if (pig.isBaby() || pig.getActivity()!=PiglinEntity.Activity.ATTACKING_WITH_MELEE_WEAPON) continue;
            west|=e.getX()<x-.20;
            east|=e.getX()>x+.20;
            if (west && east) return true;
        }
        return false;
    }

    static boolean refreshFireResistance(MinecraftClient mc) {
        if (drinkingFireResistance) {
            if (mc.player.isUsingItem()) {
                Race.stop(mc); mc.options.keyUse.setPressed(true);
                status="drinking the bartered fire resistance potion";
                return true;
            }
            drinkingFireResistance=false;
            mc.options.keyUse.setPressed(false);
        }
        net.minecraft.entity.effect.StatusEffectInstance effect=mc.player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE);
        if (mc.currentScreen!=null || (effect!=null && effect.getDuration()>300) || age-splashFireTime<10) return false;
        for (Item type : new Item[]{Items.POTION,Items.SPLASH_POTION}) {
            for (int slot=0;slot<36;slot++) {
                ItemStack stack=mc.player.inventory.getStack(slot);
                if(stack.getItem()!=type || net.minecraft.potion.PotionUtil.getPotionEffects(stack).stream()
                        .noneMatch(e->e.getEffectType()==net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE)) continue;
                Race.stop(mc);
                if(!Hotbar.selectSlot(mc,slot,0)) return true;
                if(type==Items.SPLASH_POTION) {
                    if(!mc.player.isOnGround()) return false;
                    Race.aim(mc,mc.player.getCameraPosVec(1).add(0,-5,.02));
                    if(mc.player.pitch<86) return true;
                    status="splashing bartered fire resistance at the shore";
                } else status="drinking the bartered fire resistance potion";
                if(mc.interactionManager.interactItem(mc.player,mc.world,Hand.MAIN_HAND).isAccepted()) {
                    if(type==Items.POTION) {
                        drinkingFireResistance=true;
                        mc.options.keyUse.setPressed(true);
                    } else {
                        splashFireTime=age;
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static boolean leavingEntrancePortal(MinecraftClient mc) {
        if (arrivedAtBastion || mc.world.getRegistryKey()!=World.NETHER) return false;
        // The portal dismisses a newly opened inventory before the food swap is sent.
        // Let the movement controller step completely clear of its collision first.
        Box box=mc.player.getBoundingBox().expand(.3,0,.3);
        for (int x=MathHelper.floor(box.minX);x<=MathHelper.floor(box.maxX);x++)
            for (int z=MathHelper.floor(box.minZ);z<=MathHelper.floor(box.maxZ);z++) {
                BlockPos feet=new BlockPos(x,mc.player.getY(),z);
                if (mc.world.getBlockState(feet).getBlock()==Blocks.NETHER_PORTAL) return true;
            }
        return false;
    }

    private static boolean guardUpperTerraceDuringMeal(MinecraftClient mc) {
        if (!UpperLootRoute.enabled || arrivedAtBastion || mc.player.getOffHandStack().getItem()!=Items.SHIELD
                || mc.player.getX() < -125 || mc.player.getX() > -99 || mc.player.getZ()<96 || mc.player.getZ()>117
                || mc.player.getY()<36 || mc.player.getY()>44) return false;
        if (mc.player.getActiveItem().getItem()==Items.GOLDEN_APPLE
                || mc.player.getHealth()<9 && count(mc,Items.GOLDEN_APPLE)>0) return false;
        for (PiglinEntity pig:mc.world.getNonSpectatingEntities(PiglinEntity.class,mc.player.getBoundingBox().expand(4))) {
            if (!pig.isAlive() || pig.isBaby() || !pig.isAttacking() || !mc.player.canSee(pig)
                    || !(pig.getMainHandStack().getItem() instanceof SwordItem)
                    || pig.getY()+2.25<mc.player.getY() || mc.player.squaredDistanceTo(pig)>=15) continue;
            // Let the existing shield-and-walk controller take over before a normal meal.
            if (eating && mc.player.isUsingItem() && mc.player.getActiveItem().isFood()) {
                mc.interactionManager.stopUsingItem(mc.player);
                mc.options.keyUse.setPressed(false);
                eating=false;
            }
            return true;
        }
        return false;
    }

    private static boolean eat(MinecraftClient mc) {
        if (!eating && leavingEntrancePortal(mc)) return false;
        if (guardUpperTerraceDuringMeal(mc)) return false;
        // A shield may already be raised on the way out of a bastion chest.
        // Let its owner keep moving while blocking.
        if (mc.player.isUsingItem() && !mc.player.getActiveItem().isFood()) {
            eating=false;
            return false;
        }
        if (eating) {
            if (!mc.player.isUsingItem()) {
                eating=false;
                mc.options.keyUse.setPressed(false);
                return false;
            }
        } else {
            if (mc.currentScreen!=null || !mc.player.isOnGround() || mc.player.isInLava()) return false;
            int hunger=mc.player.getHungerManager().getFoodLevel();
            boolean emergencyApple=mc.player.getHealth()<9 && count(mc,Items.GOLDEN_APPLE)>0;
            if (!emergencyApple && hunger>14 && (mc.player.getHealth()>=14 || hunger>=18)) return false;
            Item food=null;
            for (ItemStack s:mc.player.inventory.main) {
                if (s.getItem()==Items.COOKED_SALMON || s.getItem()==Items.COOKED_COD || s.getItem()==Items.BREAD) {
                    food=s.getItem();
                    break;
                }
            }
            if (emergencyApple) food=Items.GOLDEN_APPLE;
            if (food==null && count(mc,Items.APPLE)>0 && hunger<=14) food=Items.APPLE;
            Hand hand=Hand.MAIN_HAND;
            if (food==null) {
                Item offhand=mc.player.getOffHandStack().getItem();
                if (offhand!=Items.COOKED_SALMON && offhand!=Items.COOKED_COD && offhand!=Items.BREAD) return false;
                hand=Hand.OFF_HAND;
            } else if (!select(mc,food)) return false;
            if (!mc.interactionManager.interactItem(mc.player,mc.world,hand).isAccepted()
                    || !mc.player.isUsingItem() || !mc.player.getActiveItem().isFood()) return false;
            eating=true;
        }
        Race.stop(mc);
        mc.options.keyUse.setPressed(true);
        status="eating to restore hunger";
        return true;
    }

    private static boolean fight(MinecraftClient mc, Step s) {
        int goal=Integer.parseInt(s.value);
        if (count(mc,Items.BLAZE_ROD)>=goal) return true;
        BlazeEntity best=null;
        for (Entity e:mc.world.getEntities()) if(e instanceof BlazeEntity && e.isAlive()
                && e.squaredDistanceTo(mc.player)<18*18 && (best==null || e.squaredDistanceTo(mc.player)<best.squaredDistanceTo(mc.player))) best=(BlazeEntity)e;
        if (best==null || best.squaredDistanceTo(mc.player)>14) {
            collect(mc,Items.BLAZE_ROD,goal);
            if (best!=null && best.squaredDistanceTo(mc.player)>14) approach(mc,best.getPos(),false);
            return false;
        }
        select(mc,Items.DIAMOND_SWORD);
        Race.aim(mc,best.getBoundingBox().getCenter());
        mc.gameRenderer.updateTargetedEntity(1);
        if (mc.crosshairTarget instanceof EntityHitResult && ((EntityHitResult)mc.crosshairTarget).getEntity()==best
                && mc.player.getAttackCooldownProgress(.5f)>.94f) {
            mc.interactionManager.attackEntity(mc.player,best);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        return false;
    }

    private static boolean launch(MinecraftClient mc, Step s) {
        double yaw, pitch;
        switch(s.value) {
            case "pearl1": yaw=-54.78; pitch=-36.09; break;
            case "pearl2": yaw=-116.78; pitch=-26.17; break;
            case "pearl3": yaw=79.23; pitch=-40.68; break;
            default: yaw=mc.player.yaw; pitch=89.3;
        }
        // Fire resistance is handled before the first lake crossing, including bottles from barters.
        if(s.kind.equals("drink")) return true;
        if(ShelfCrossing.routeActive() && s.value.equals("pearl2"))
            return ShelfCrossing.travel(mc);
        if(ShelfCrossing.routeActive() && !ShelfCrossing.canThrow(mc,s.value)) {
            status="confirming the number of pearls used before the fortress throw";
            return false;
        }
        Item wanted=Items.ENDER_PEARL;
        if (!s.value.equals("pearl1") && !select(mc,wanted))
            return false;
        if (s.value.equals("pearl1")) {
            if (!TravelPearls.aimFirst(mc)) return false;
        } else if (s.value.equals("pearl2")) {
            if (!TravelPearls.aimSecond(mc)) return false;
        } else if (s.value.equals("pearl3")) {
            if(mc.options.viewDistance<8) distance(mc,8);
            if (!TravelPearls.aimReturn(mc)) return false;
        } else {
        double y=Math.toRadians(yaw), p=Math.toRadians(pitch);
        Race.aim(mc,mc.player.getCameraPosVec(1).add(-Math.sin(y)*Math.cos(p)*10,-Math.sin(p)*10,Math.cos(y)*Math.cos(p)*10));
        if (Math.abs(MathHelper.wrapDegrees(mc.player.yaw-(float)yaw))>1 || Math.abs(mc.player.pitch-(float)pitch)>1) return false;
        }
        if (mc.player.getItemCooldownManager().isCoolingDown(wanted)) return false;
        if (wanted==Items.ENDER_PEARL && mc.player.isOnGround()) { mc.options.keyJump.setPressed(true); return false; }
        if (mc.interactionManager.interactItem(mc.player,mc.world,Hand.MAIN_HAND).isAccepted()) {
            mc.player.swingHand(Hand.MAIN_HAND);
            if (s.value.equals("pearl1")) Race.advanceTo(3267);
            if (s.value.equals("pearl3")) {
                thirdThrow=age;
                distance(mc,2);
            }
            return true;
        }
        return false;
    }

    private static void distance(MinecraftClient mc, int chunks) {
        if (mc.options.viewDistance==chunks) return;
        if (originalDistance<0) originalDistance=mc.options.viewDistance;
        DistanceKeys.towards(mc,chunks);
    }

    private static void manageStasis(MinecraftClient mc,int tick) {
        if (thirdThrow<0 || mc.world.getRegistryKey()!=World.NETHER) return;
        // Hold the far landing chunk unloaded. Raising the distance during the flight
        // can make the pearl land while Astra is still walking to the blaze cage.
        // These intermediate shortcut changes mirror the visible reference replay.
        // The pearl advances toward the next chunk before the player leaves the bridge.
        int target;
        if (tick>=5131 && count(mc,Items.BLAZE_ROD)>=6) target=Math.max(12,originalDistance);
        else if (tick>=3621) target=3;
        else if (tick>=3618) target=4;
        else if (tick>=3441) target=5;
        else if (tick>=3431) target=4;
        else if (tick>=3428) target=3;
        else target=2;
        distance(mc,target);
    }

    private static boolean onWestboundFortressBridge(MinecraftClient mc) {
        double x=mc.player.getX(), y=mc.player.getY(), z=mc.player.getZ();
        if(x< -31.4 || x> -23.85 || y<56.94 || y>58.45 || z<167.03 || z>167.97
                || mc.player.hasVehicle() || mc.player.isInLava() || count(mc,Items.ENDER_PEARL)<11) return false;
        net.minecraft.server.network.ServerPlayerEntity self=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if(self==null || self.getStatHandler().getStat(net.minecraft.stat.Stats.USED,Items.ENDER_PEARL)!=2) return false;
        // The middle row is the existing top deck and leads west to the original release.
        for(int xx=-31;xx<=Math.floor(x);xx++) {
            BlockPos feet=new BlockPos(xx,57,167), below=feet.down();
            if(!mc.world.getBlockState(below).isSideSolidFullSquare(mc.world,below,Direction.UP)
                    || !mc.world.getBlockState(feet).getCollisionShape(mc.world,feet).isEmpty()
                    || !mc.world.getBlockState(feet.up()).getCollisionShape(mc.world,feet.up()).isEmpty()
                    || !mc.world.getFluidState(feet).isEmpty()) return false;
        }
        return true;
    }

    public static boolean tick(MinecraftClient mc,int tick) {
        age++;
        if (mc.player==null || mc.world==null || mc.interactionManager==null) return false;
        if (mc.world.getRegistryKey() == World.OVERWORLD && StrongholdEntry.guardCorridor(mc,tick)) {
            eating=false;
            status=StrongholdEntry.status;
            return true;
        }
        if(finishReturnLeggings(mc))return true;
        if (Hotbar.tick(mc)) return true;
        boolean nether=mc.world.getRegistryKey()==World.NETHER;
        if (!nether && mc.world.getRegistryKey()!=World.OVERWORLD) return false;
        if (nether) manageStasis(mc,tick);
        if (nether && ReturnFrame.guardFromEndermite(mc,tick)) {
            eating=false;
            status=ReturnFrame.status;
            return true;
        }
        if (!nether) {
            while(next<steps.size() && steps.get(next).tick<5412) next++;
            if (originalDistance>0) { distance(mc,originalDistance); if (mc.options.viewDistance==originalDistance) originalDistance=-1; }
        }
        if (equipAfterChest) {
            Race.stop(mc);
            if (VisibleSlots.equip(mc)) equipAfterChest=false;
            else { status="equipping gold armor in inventory"; return true; }
        }
        if(nether && tick>=2298 && next<steps.size() && steps.get(next).tick==2306 && ThirdChest.needed(mc)) {
            Race.stop(mc);
            if(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
                lowerShield(mc);
                status="taking obsidian, arrows, and gold from the sheltered third chest";
                Step chest=steps.get(next);
                if(loot(mc,chest))finish(mc,chest);
            } else {
                ThirdChest.tick(mc);
                status=ThirdChest.status;
            }
            return true;
        }
        if(nether && tick>=2307 && tick<2419 && mc.player.getX()<-149) {
            if (!rushingWest) for (Entity e:mc.world.getEntities()) {
                if(e instanceof PiglinEntity && e.isAlive() && ((PiglinEntity)e).isAttacking()
                        && e.squaredDistanceTo(mc.player)<36) { rushingWest=true;retreatingToShaft=true;break; }
            }
            if(rushingWest && tick>=2369 && offerExposedIngot(mc,false))return true;
        }
        if(tick>=2462) rushingWest=false;
        // This optional block above the chest is separate from the west stair we stand on.
        // An admiring pig can prevent placement for two barter cycles while other pigs approach.
        if(nether && tick>=2307 && next<steps.size() && steps.get(next).tick==2307) {
            if (coverNorthOfThirdChest(mc)) { Race.stop(mc);return true; }
            Step cover=steps.get(next);
            Box occupied=new Box(cover.target);
            for(Entity e:mc.world.getEntities()) if(e instanceof PiglinEntity && e.isAlive()
                    && e.getBoundingBox().intersects(occupied)) {
                finish(mc,cover);
                break;
            }
        }
        if(nether && arrivedAtBastion && next<steps.size() && steps.get(next).tick==2089
                && BastionEgress.prepareExit(mc)) {status=BastionEgress.status;return true;}
        if(nether && next<steps.size() && steps.get(next).tick==2548 && EarlyBarter.tick(mc,age)) {
            status=EarlyBarter.status;return true;
        }
        if(nether && UpperLootRoute.enabled && tick>=2262 && tick<=2583 && (age&7)==0) {
            EastHallRecovery.observe(mc);
        }
        if(nether && collectFirstTrade(mc))return true;
        if(nether && tick>=2089 && tick<=2144 && mc.player.getY()>=30.5 && mc.player.getY()<34
                && mc.player.getX()>-117 && mc.player.getX()< -109 && mc.player.getZ()>110 && mc.player.getZ()<119
                && count(mc,Items.ENDER_PEARL)>=4) escapingFirstFloor=true;
        if(nether && exitFirstTradeFloor(mc))return true;
        if(nether && next<steps.size() && steps.get(next).tick==2131){
            if(BastionEgress.tick(mc)){status=BastionEgress.status;return true;}
            if(BastionEgress.arrived){next++;entered=age;Race.advanceTo(2187);tick=2187;}
        }
        boolean enteringShaft=nether && tick>=2418 && tick<2462;
        boolean lootingAcrossGap=nether && next<steps.size() && steps.get(next).tick==2089
                && BastionApproach.onLootPerch(mc);
        if(nether && next<steps.size() && steps.get(next).tick==2187 && BastionEgress.arrived)lootingAcrossGap=true;
        if (nether && tick>=2391 && tick<2419 && !retreatingToShaft && surroundedOnWestLedge(mc)) {
            retreatingToShaft=true;
            lowerShield(mc);
        }
        boolean crossingToShaft=retreatingToShaft && tick>=2391 && tick<2419;
        if (nether && tick>=2391 && tick<=2433 && mc.player.getX()<-152.3 && mc.player.getY()>36.05
                && offerExposedIngot(mc,false)) return true;
        boolean cuttingToWest=next<steps.size() && steps.get(next).tick>=2339 && steps.get(next).tick<=2369
                && mc.player.getX()<-149 && mc.player.getY()>=37.8;
        if (cuttingToWest) lowerShield(mc);
        if (nether && tick>=2089 && tick<2548 && !rushingWest && !cuttingToWest && !enteringShaft && !lootingAcrossGap && !crossingToShaft && protectFromPiglin(mc)) return true;
        boolean openingShaftFloor=next<steps.size() && (steps.get(next).tick==2419 || steps.get(next).tick==2433);
        if (enteringShaft && !openingShaftFloor && mc.currentScreen==null) {
            Vec3d center=new Vec3d(-154.49,36,119.50);
            double offset=Math.hypot(mc.player.getX()-center.x,mc.player.getZ()-center.z);
            if (mc.player.getY()>36.1 && offset>.19) {
                Race.stop(mc);
                Race.aim(mc,new Vec3d(center.x,mc.player.getEyeY()-.65,center.z));
                approach(mc,center,offset<.58);
                mc.options.keyJump.setPressed(false);
                status="centering above the gold shaft";
                return true;
            }
        }
        if (nether && tick>=3267 && tick<3290 && next<steps.size() && steps.get(next).tick==3290) {
            Race.stop(mc); Race.advanceTo(3290); tick=3290;
        }
        if (nether && tick>=3290 && tick<3314 && next<steps.size() && steps.get(next).tick==3314) {
            Race.advanceTo(3314); tick=3314;
        }
        if (nether && tick>=3314 && tick<3391 && next<steps.size() && steps.get(next).tick==3348 && mc.player.getX()<=-39) {
            Race.stop(mc);
            if (TravelPearls.usesLavaRecovery() && mc.player.getOffHandStack().getItem()==Items.SHIELD) {
                // Guard while the second pearl is in flight. Vanilla needs five ticks to raise the shield.
                Race.aim(mc,new Vec3d(-25.5,57.8,167.5));
                if (!mc.player.isUsingItem()) mc.interactionManager.interactItem(mc.player,mc.world,Hand.OFF_HAND);
                mc.options.keyUse.setPressed(mc.player.isUsingItem() && mc.player.getActiveItem().getItem()==Items.SHIELD);
            }
            return true;
        }
        if (nether && tick>=3314 && tick<3391 && mc.player.getX()>-39 && mc.player.getY()>=55 && next<steps.size() && steps.get(next).tick==3348) {
            lowerShield(mc);
            next++; entered=age; Race.advanceTo(ShelfCrossing.rejoined() || TravelPearls.reachedFortressAfterSwim(mc)?3393:3350);
            if (mc.player.squaredDistanceTo(Race.stance(3388))<12) Race.advanceTo(3388);
            tick=Race.referenceTick();
        }
        // Recheck after landing and on every approach tick. A far second pearl can
        // put the player past the reference's eastward excursion and its solid pier.
        if (nether && tick>=3350 && tick<3393 && thirdThrow<0
                && next<steps.size() && steps.get(next).tick==3393 && onWestboundFortressBridge(mc)) {
            Race.stop(mc);
            Race.advanceTo(3393);
            tick=3393;
        }
        if (nether && Fortress.guardBridge(mc,tick)) {
            eating=false;
            drinkingFireResistance=false;
            status=Fortress.status;
            return true;
        }
        if(nether && tick>=3080 && tick<3223 && !rodSlotsPrepared
                && mc.currentScreen==null && mc.player.isOnGround() && !mc.player.isInLava()) {
            if(makeRoomForRods(mc,2))return true;
            rodSlotsPrepared=true;
        }
        if (nether && tick>=3223 && tick<5161 && refreshFireResistance(mc)) return true;
        // Two native repeats found primed creepers in this passage. Remaining
        // stationary for a 32-tick meal let a 30-tick fuse finish at close range.
        if (!nether && StrongholdEntry.creeperNearCorridor(mc,tick)) {
            if (eating) {
                mc.options.keyUse.setPressed(false);
                mc.interactionManager.stopUsingItem(mc.player);
                eating=false;
            }
        } else if (!rushingWest && !(nether && TravelPearls.usesLavaRecovery() && thirdThrow<0 && tick>=3393 && tick<=3402)
                && eat(mc)) return true;
        if (!nether && StrongholdEntry.tick(mc,tick)) { status=StrongholdEntry.status; return true; }
        if (nether && ReturnFrame.tick(mc,tick)) { status=ReturnFrame.status; return true; }
        if (nether && tick>=3490 && tick<5161 && Fortress.tick(mc,6)) {
            status=Fortress.status;
            return true;
        }
        if (nether && tick>=3780 && tick<4620 && earlyBow(mc)) return true;
        if (nether && !arrivedAtBastion && tick<2090) {
            status=BastionApproach.status;
            Race.cap(2089);
            if(BastionApproach.tick(mc)) {
                arrivedAtBastion=true;
                cell=0;
                int resume=UpperLootRoute.enabled?2277:2089;
                while(next<steps.size() && steps.get(next).tick<resume) next++;
                Race.advanceTo(resume);
            }
            return true;
        }
        if (next>=steps.size()) return false;
        if (nether && tick>=3700 && tick<5161 && mc.currentScreen==null) {
            if (count(mc,Items.BLAZE_ROD)>=6) {
                int resume=count(mc,Items.BOW)>0 ? 5161 : 4620;
                if (steps.get(next).tick<resume) {
                    while (next<steps.size() && steps.get(next).tick<resume) next++;
                    entered=age;
                    if (resume==5161) {
                        distance(mc,Math.max(12,originalDistance));
                        Race.advanceTo(5161);
                        tick=5161;
                    } else {
                        // Walk the remaining metres to the crafting site using the usual inputs.
                        Race.advanceTo(4615);
                        tick=4615;
                    }
                }
            }
        }
        Step s=steps.get(next);
        if (nether && s.tick>=5412) return false;
        if (nether && s.kind.equals("mine") && tick<s.tick && s.tick-tick<=64
                && Race.distanceToReference(mc,s.tick)<1.7) {
            Vec3d eye=mc.player.getCameraPosVec(1);
            Vec3d block=Vec3d.ofCenter(s.target);
            if (eye.squaredDistanceTo(block)<16) {
                BlockHitResult visible=mc.world.rayTrace(new RayTraceContext(eye,block,
                        RayTraceContext.ShapeType.OUTLINE,RayTraceContext.FluidHandling.NONE,mc.player));
                if (visible.getType()==HitResult.Type.BLOCK && visible.getBlockPos().equals(s.target)) {
                    Race.advanceTo(s.tick);
                    tick=s.tick;
                }
            }
        }
        // Every frame is in reach from the two blocks placed in the portal room.
        // Once Astra has crafted the eyes, the block-state acknowledgements pace the insertion.
        if (!nether && s.kind.equals("eye") && mc.player.getCameraPosVec(1).squaredDistanceTo(Vec3d.ofCenter(s.target))<17) {
            Race.advanceTo(s.tick);
            tick=s.tick;
        }
        Race.cap(s.tick);
        if (tick<s.tick) { entered=age; return false; }
        Race.stop(mc);
        status=s.kind+" "+s.value+" at "+s.target.toShortString();
        boolean done=false;
        switch(s.kind) {
            case "place": done=place(mc,s); break;
            case "mine": done=mine(mc,s); break;
            case "use": done=use(mc,aim(mc,s.target,null)); break;
            case "chest": done=loot(mc,s); break;
            case "craft": done=recipe(mc,s); break;
            case "barter": done=feed(mc,s); break;
            case "shelter": done=shelter(mc,s); break;
            case "pearls":
                done=collect(mc,Items.ENDER_PEARL,14) || (count(mc,Items.ENDER_PEARL)>=13 && age-entered>=24);
                break;
            case "fight": done=fight(mc,s); break;
            case "drink": case "throw": done=launch(mc,s); break;
            case "stasis":
                done=mc.player.getY()>68 && mc.player.squaredDistanceTo(Vec3d.ofCenter(s.target))<121;
                if(!done && age-entered>50) {
                    status="walking from the fortress to the return portal: "+cell+"/"+returnWalk.size();
                    done=walkCells(mc,returnWalk);
                }
                break;
            case "ignite":
                done=mc.world.getBlockState(s.target).getBlock()==Blocks.NETHER_PORTAL;
                if (!done && select(mc,Items.FLINT_AND_STEEL)) use(mc,aim(mc,s.target.down(),Direction.UP));
                break;
            case "eye":
                BlockState frame=mc.world.getBlockState(s.target);
                done=frame.getBlock() instanceof EndPortalFrameBlock && frame.get(EndPortalFrameBlock.EYE);
                if (!done && select(mc,Items.ENDER_EYE)) use(mc,aim(mc,s.target,null));
                break;
            default: throw new IllegalStateException("Unknown middle action: "+s.kind);
        }
        if (done) finish(mc,s);
        return true;
    }
}
