package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

/** Recheck the runner's own inventory through the ordinary visible pickup interaction. */
final class InventoryReceipt {
    private static int slot = -1, phase, age;
    private static ItemStack original = ItemStack.EMPTY;
    private InventoryReceipt() {}

    static void reset() { slot = -1; phase = age = 0; original = ItemStack.EMPTY; }

    private static ServerPlayerEntity owner(MinecraftClient mc) {
        return mc.getServer() == null ? null : mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
    }

    static boolean matches(MinecraftClient mc, int index) {
        ServerPlayerEntity self = owner(mc);
        return self != null && ItemStack.areEqual(mc.player.inventory.getStack(index), self.inventory.getStack(index));
    }

    static boolean findMissing(MinecraftClient mc, Item wanted) {
        ServerPlayerEntity self = owner(mc);
        if (self == null || mc.currentScreen != null) return false;
        for (int index = 0; index < 36; index++) {
            if (mc.player.inventory.getStack(index).isEmpty()
                    && self.inventory.getStack(index).getItem() == wanted) {
                start(mc, index);
                return active();
            }
        }
        return false;
    }

    static void start(MinecraftClient mc, int index) {
        ServerPlayerEntity self = owner(mc);
        if (slot >= 0 || mc.currentScreen != null || self == null
                || !self.inventory.getCursorStack().isEmpty() || !mc.player.inventory.getCursorStack().isEmpty()) return;
        slot = index;
        phase = age = 0;
        mc.openScreen(new InventoryScreen(mc.player));
    }

    static boolean active() { return slot >= 0; }

    static boolean tick(MinecraftClient mc) {
        if (slot < 0) return false;
        if (!(mc.currentScreen instanceof InventoryScreen)) { reset(); return false; }
        Race.stop(mc);
        mc.options.keyUse.setPressed(false);
        mc.options.keyAttack.setPressed(false);
        Middle.status = "checking the visible inventory slot";
        ServerPlayerEntity self = owner(mc);
        int screenSlot = slot < 9 ? 36 + slot : slot;
        if (self == null || ++age < 3) return true;
        ItemStack serverCursor = self.inventory.getCursorStack();
        ItemStack clientCursor = mc.player.inventory.getCursorStack();
        boolean cursorsEmpty = serverCursor.isEmpty() && clientCursor.isEmpty();
        if (phase == 0) {
            if (!cursorsEmpty) return true;
            if (matches(mc, slot)) {
                mc.player.closeHandledScreen();
                reset();
            } else {
                original = self.inventory.getStack(slot).copy();
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, screenSlot, 0,
                        SlotActionType.PICKUP, mc.player);
                phase = 1;
                age = 0;
            }
            return true;
        }
        if (!matches(mc, slot) || !ItemStack.areEqual(clientCursor, serverCursor)) return true;
        // A real action may consume or replace a stack while the receipt is pending.
        // Only release an abandoned attempt once both sides agree and neither cursor carries anything.
        if (cursorsEmpty && (ItemStack.areEqual(self.inventory.getStack(slot), original) || age >= 40)) {
            mc.player.closeHandledScreen();
            reset();
        } else if (phase == 1 && !clientCursor.isEmpty() && ItemStack.areEqual(clientCursor, original)
                && mc.player.inventory.getStack(slot).isEmpty()) {
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, screenSlot, 0,
                    SlotActionType.PICKUP, mc.player);
            phase = 2;
            age = 0;
        }
        return true;
    }
}
