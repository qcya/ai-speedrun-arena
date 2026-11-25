package dev.aiarena.week29;

import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

/** Builds a real portal on a natural flat floor using a three-block jump pillar. */
final class NetherExit {
    private static int stage, tick, nextClick;
    private static BlockPos origin;
    private static Object world;
    private static boolean alongX;
    static String status = "";

    // u spans the door, h is relative to the lowest portal cell; v points towards the builder.
    private static BlockPos at(int u, int h, int v) {
        return alongX ? origin.add(u,h,v) : origin.add(-v,h,u);
    }
    private static String id(MinecraftClient mc, int slot) {
        ItemStack s=mc.player.inventory.main.get(slot);
        return s.isEmpty() ? "" : Registry.ITEM.getId(s.getItem()).getPath();
    }
    private static boolean equip(MinecraftClient mc, String... wanted) {
        for (String w:wanted) {
            if (w.equals(id(mc,mc.player.inventory.selectedSlot))) return true;
            for (int i=0;i<mc.player.inventory.main.size();i++) {
                if (!w.equals(id(mc,i))) continue;
                if (i<9) mc.player.inventory.selectedSlot=i;
                else {
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,i,7,SlotActionType.SWAP,mc.player);
                    mc.player.inventory.selectedSlot=7;
                }
                return true;
            }
        }
        return false;
    }
    private static boolean filler(MinecraftClient mc) {
        return equip(mc,"cobblestone","netherrack","blackstone","basalt","dirt","stone","andesite","granite","end_stone","spruce_planks","oak_planks");
    }
    private static boolean solid(MinecraftClient mc,BlockPos p) {
        return !mc.world.getBlockState(p).getCollisionShape(mc.world,p).isEmpty();
    }
    private static boolean stepBlock(MinecraftClient mc,RouteMovement nav,BlockPos p,boolean obsidian) {
        Block block=mc.world.getBlockState(p).getBlock();
        if (obsidian ? block==Blocks.OBSIDIAN : solid(mc,p)) return true;
        if (!mc.world.getBlockState(p).getMaterial().isReplaceable()) {
            nav.mine(p);
            return false;
        }
        if (tick<nextClick || !(obsidian ? equip(mc,"obsidian") : filler(mc))) return false;
        if (nav.place(p)) nextClick=tick+3;
        return false;
    }
    private static boolean pillar(MinecraftClient mc,RouteMovement nav,int height) {
        BlockPos p=at(1,height,2);
        if (solid(mc,p)) return mc.player.getY()>=p.getY()+.93;
        if (nav.horizontal(p)>.21) {
            nav.aim(new Vec3d(p.getX()+.5, mc.player.getEyeY(), p.getZ()+.5));
            mc.options.keyForward.setPressed(true);
            return false;
        }
        mc.options.keyJump.setPressed(true);
        if (mc.player.getY()>=p.getY()+1.001) stepBlock(mc,nav,p,false);
        return false;
    }
    private static boolean ignite(MinecraftClient mc,RouteMovement nav) {
        BlockPos bottom=at(1,-1,0),air=bottom.up();
        if (mc.world.getBlockState(air).getBlock()==Blocks.NETHER_PORTAL) return true;
        if (!mc.world.getBlockState(air).getMaterial().isReplaceable()) {
            nav.mine(air);return false;
        }
        if (tick<nextClick || !equip(mc,"flint_and_steel","fire_charge")) return false;
        nav.aim(new Vec3d(bottom.getX()+.5,bottom.getY()+.999,bottom.getZ()+.5));
        if (!(mc.crosshairTarget instanceof BlockHitResult)) return false;
        BlockHitResult hit=(BlockHitResult)mc.crosshairTarget;
        if (hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(bottom) || hit.getSide()!=Direction.UP) return false;
        mc.interactionManager.interactBlock(mc.player,mc.world,Hand.MAIN_HAND,hit);
        mc.player.swingHand(Hand.MAIN_HAND);nextClick=tick+5;
        return false;
    }
    static boolean tick(MinecraftClient mc,RouteMovement nav,JsonObject step) {
        if (mc.world.getRegistryKey().equals(World.OVERWORLD)) { world=null; return true; }
        BlockPos b=new BlockPos(step.get("x").getAsInt(),step.get("y").getAsInt(),step.get("z").getAsInt());
        if (world!=mc.world || !b.equals(origin)) {
            world=mc.world; origin=b; stage=0; tick=0;nextClick=0;
            alongX=step.has("axis") && "x".equals(step.get("axis").getAsString());
        }
        tick++; boolean ok=false;
        status="portal build "+stage+"/18";
        switch(stage) {
          case 0: ok=nav.travel(at(1,-1,2),.30,true);break;
          case 1: ok=stepBlock(mc,nav,at(0,-1,0),true);break;
          case 2: ok=stepBlock(mc,nav,at(1,-1,0),true);break;
          case 3: ok=stepBlock(mc,nav,at(-1,-1,0),false);break;
          case 4: ok=stepBlock(mc,nav,at(2,-1,0),false);break;
          case 5: ok=stepBlock(mc,nav,at(-1,0,0),true);break;
          case 6: ok=stepBlock(mc,nav,at(2,0,0),true);break;
          case 7: ok=pillar(mc,nav,-1);break;
          case 8: ok=stepBlock(mc,nav,at(-1,1,0),true);break;
          case 9: ok=stepBlock(mc,nav,at(2,1,0),true);break;
          case 10: ok=pillar(mc,nav,0);break;
          case 11: ok=stepBlock(mc,nav,at(-1,2,0),true);break;
          case 12: ok=stepBlock(mc,nav,at(2,2,0),true);break;
          case 13: ok=pillar(mc,nav,1);break;
          case 14: ok=stepBlock(mc,nav,at(-1,3,0),false);break;
          case 15: ok=stepBlock(mc,nav,at(0,3,0),true);break;
          case 16: ok=stepBlock(mc,nav,at(1,3,0),true);break;
          case 17: ok=ignite(mc,nav);break;
          default:
            BlockPos target=at(1,0,0);
            if (nav.horizontal(target)>.30) {
                nav.aim(new Vec3d(target.getX()+.5,mc.player.getEyeY(),target.getZ()+.5));
                mc.options.keyForward.setPressed(true);
            }
        }
        if (ok) { stage++; nav.cancelBreak(); }
        return false;
    }
}
