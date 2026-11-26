package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

/** A visible, acknowledged number-key swap, shared by the local weekly route. */
final class Hotbar {
    private static Item pending;
    private static int source, destination, ticks;
    private static boolean clicked;
    private Hotbar() {}

    static void reset() {
        pending=null;
        clicked=false;
        ticks=0;
    }

    static boolean select(MinecraftClient mc, Item item) {
        return select(mc,item,-1);
    }

    static boolean select(MinecraftClient mc, Item item, int preferred) {
        if (pending!=null) return false;
        if (mc.player.getMainHandStack().getItem()==item) return true;
        for(int i=0;i<36;i++) {
            if(mc.player.inventory.getStack(i).getItem()!=item)continue;
            return selectSlot(mc,i,preferred);
        }
        Middle.status="needs "+net.minecraft.util.registry.Registry.ITEM.getId(item);
        return false;
    }

    static boolean selectSlot(MinecraftClient mc,int slot,int preferred) {
        if(pending!=null || mc.currentScreen!=null) return false;
        Item item=mc.player.inventory.getStack(slot).getItem();
        if(slot<9) {
            mc.player.inventory.selectedSlot=slot;
            return true;
        }
        pending=item;source=slot;destination=mc.player.inventory.selectedSlot;ticks=0;clicked=false;
        if(preferred>=0) destination=preferred;
        else if(item==Items.DIAMOND_SWORD) destination=2;
        else if(item==Items.IRON_PICKAXE) destination=1;
        else if(destination==1 || destination==2) destination=0;
        mc.openScreen(new InventoryScreen(mc.player));
        return false;
    }

    static boolean tick(MinecraftClient mc) {
        if (pending==null)return false;
        if (!(mc.currentScreen instanceof InventoryScreen)) {reset();return false;}
        Race.stop(mc);
        mc.options.keyAttack.setPressed(false);
        mc.options.keyUse.setPressed(false);
        ticks++;
        Middle.status="moving "+net.minecraft.util.registry.Registry.ITEM.getId(pending)+" to the hotbar";
        if(!clicked && ticks>=2) {
            if(mc.player.inventory.getStack(source).getItem()==pending) {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,source,destination,
                        SlotActionType.SWAP,mc.player);
                clicked=true;
            } else {
                mc.player.closeHandledScreen();reset();
            }
            return true;
        }
        if(clicked) {
            ServerPlayerEntity actual=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
            if(mc.player.inventory.getStack(destination).getItem()==pending && actual!=null
                    && actual.inventory.getStack(destination).getItem()==pending) {
                mc.player.inventory.selectedSlot=destination;
                mc.player.closeHandledScreen();reset();
            } else if(ticks>24) {
                mc.player.closeHandledScreen();reset();
            }
        }
        return true;
    }
}
