package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

/** Turn the first mined block into a separate nine-ingot stack while the two later ores are intact. */
final class EarlyBarter {
    private static int started, initialGold, destination, sent, staleSince=-1, lastRefresh=-20;
    private static boolean finished, paid;
    static String status;

    static void reset() {started=sent=0;destination=-1;staleSince=-1;lastRefresh=-20;finished=paid=false;}
    static boolean paid() {return paid;}

    private static boolean nine(ItemStack stack) {
        return stack.getItem()==Items.GOLD_INGOT && stack.getCount()==9;
    }

    private static boolean clearThrow(MinecraftClient mc) {
        Vec3d eye=mc.player.getCameraPosVec(1);
        return mc.world.rayTrace(new RayTraceContext(eye,new Vec3d(-156.85,36.65,119.62),
                RayTraceContext.ShapeType.COLLIDER,RayTraceContext.FluidHandling.NONE,mc.player))
                .getType()==HitResult.Type.MISS;
    }

    private static void end(MinecraftClient mc) {
        if(mc.currentScreen instanceof InventoryScreen)mc.player.closeHandledScreen();
        finished=true;
    }

    static boolean tick(MinecraftClient mc,int age) {
        if(finished || mc.getServer()==null)return false;
        ServerPlayerEntity self=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if(self==null)return false;
        if(started==0) {
            if(mc.currentScreen!=null || mc.player.squaredDistanceTo(-154.70,35,119.60)>.16
                    || !mc.player.isOnGround() || mc.player.inventory.count(Items.GOLD_BLOCK)!=1
                    || self.inventory.count(Items.GOLD_BLOCK)!=1 || !clearThrow(mc))return false;
            initialGold=self.inventory.count(Items.GOLD_INGOT);
            started=age;
        }
        Race.stop(mc);mc.options.keyAttack.setPressed(false);mc.options.keyUse.setPressed(false);
        if(mc.player.isUsingItem())mc.interactionManager.stopUsingItem(mc.player);
        if(sent>0) {
            status="confirming the first nine ingots left the player's hand";
            if(mc.player.inventory.count(Items.GOLD_INGOT)==initialGold
                    && self.inventory.count(Items.GOLD_INGOT)==initialGold) {
                paid=true;end(mc);return false;
            }
            if(age-sent>=24) {end(mc);return false;}
            return true;
        }
        if(age-started>48 || mc.player.hurtTime>0) {end(mc);return false;}
        if(destination>=0 && nine(mc.player.inventory.getStack(destination))
                && nine(self.inventory.getStack(destination))) {
            if(mc.currentScreen instanceof InventoryScreen){mc.player.closeHandledScreen();return true;}
            if(mc.currentScreen!=null)return true;
            status="offering the first nine ingots while the remaining gold is still in the wall";
            mc.player.inventory.selectedSlot=destination;
            if(clearThrow(mc) && Middle.dropTradeStack(mc,9))sent=age;
            return true;
        }
        status="crafting nine early ingots at the sheltered opening";
        if(!(mc.currentScreen instanceof InventoryScreen)) {
            if(mc.currentScreen==null)mc.openScreen(new InventoryScreen(mc.player));
            return true;
        }
        ScreenHandler sh=mc.player.currentScreenHandler;
        ItemStack cursor=mc.player.inventory.getCursorStack();
        if(nine(cursor) && destination>=0 && mc.player.inventory.getStack(destination).isEmpty()) {
            mc.interactionManager.clickSlot(sh.syncId,36+destination,0,SlotActionType.PICKUP,mc.player);
            return true;
        }
        if(!cursor.isEmpty())return true;
        if(nine(sh.getSlot(0).getStack())) {
            if(destination<0) {
                for(int i=0;i<9;i++)if(mc.player.inventory.getStack(i).isEmpty()){destination=i;break;}
                if(destination<0) {
                    // The one source block vacated a normal inventory slot when it entered the grid.
                    for(int i=9;i<36;i++)if(mc.player.inventory.getStack(i).isEmpty()) {
                        mc.interactionManager.clickSlot(sh.syncId,i,6,SlotActionType.SWAP,mc.player);
                        destination=6;break;
                    }
                    return true;
                }
            }
            if(mc.player.inventory.getStack(destination).isEmpty())
                mc.interactionManager.clickSlot(sh.syncId,0,0,SlotActionType.PICKUP,mc.player);
        } else if(mc.player.inventory.count(Items.GOLD_BLOCK)>0 && age%3==0) {
            mc.world.getRecipeManager().get(new Identifier("gold_ingot_from_gold_block"))
                    .ifPresent(r->mc.interactionManager.clickRecipe(sh.syncId,r,false));
        }
        return true;
    }

    // A recipe-book shift craft can leave a stale gold block in one visible slot.
    // An ordinary left click there asks vanilla to return its authoritative contents.
    static boolean refreshLastCraft(MinecraftClient mc,int age) {
        if(!paid || !(mc.currentScreen instanceof InventoryScreen) || mc.getServer()==null)return false;
        ServerPlayerEntity self=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if(self==null || self.inventory.count(Items.GOLD_BLOCK)>0 || self.inventory.count(Items.GOLD_INGOT)<18
                || !mc.player.inventory.getCursorStack().isEmpty() || !self.inventory.getCursorStack().isEmpty()) {
            staleSince=-1;return false;
        }
        ScreenHandler server=self.currentScreenHandler;
        if(server!=self.playerScreenHandler || server.getSlot(0).getStack().getItem()==Items.GOLD_INGOT)return false;
        for(int i=1;i<=4;i++)if(server.getSlot(i).getStack().getItem()==Items.GOLD_BLOCK)return false;
        for(int i=0;i<36;i++) {
            if(mc.player.inventory.getStack(i).getItem()!=Items.GOLD_BLOCK || !self.inventory.getStack(i).isEmpty())continue;
            if(staleSince<0)staleSince=age;
            if(age-staleSince>=6 && age-lastRefresh>=12) {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,
                        i<9?36+i:i,0,SlotActionType.PICKUP,mc.player);
                lastRefresh=age;
            }
            return true;
        }
        staleSince=-1;return false;
    }

    /** All remaining blocks now in hand or the recipe grid belong to the later batch. */
    static boolean remainingGoldCrafted(MinecraftClient mc) {
        if(mc.player.inventory.count(Items.GOLD_BLOCK)>0 || mc.getServer()==null)return false;
        ServerPlayerEntity self=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if(self==null || self.inventory.count(Items.GOLD_BLOCK)>0)return false;
        if(mc.currentScreen instanceof InventoryScreen) {
            ScreenHandler sh=mc.player.currentScreenHandler;
            if(sh.getSlot(0).getStack().getItem()==Items.GOLD_INGOT)return false;
            for(int i=1;i<=4;i++)if(sh.getSlot(i).getStack().getItem()==Items.GOLD_BLOCK)return false;
        }
        return true;
    }
    private EarlyBarter(){}
}
