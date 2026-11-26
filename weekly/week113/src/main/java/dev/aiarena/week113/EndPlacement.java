package dev.aiarena.week113;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Arrays;

/** Keep delayed acknowledgements for an exhausted placement stack out of its successor's slot. */
final class EndPlacement {
    private static int held = -1, pending = -1;
    private static Item item;
    private static long swappedAt;
    private static final long[] lastUsed = new long[9];

    private EndPlacement() {}

    static void reset() {
        held = pending = -1;
        item = null;
        Arrays.fill(lastUsed, Long.MIN_VALUE / 2);
    }

    private static boolean known(MinecraftClient mc, ServerPlayerEntity server, int slot) {
        return ItemStack.areEqual(mc.player.inventory.getStack(slot), server.inventory.getStack(slot));
    }

    private static boolean equip(MinecraftClient mc, int slot, long now) {
        if (mc.currentScreen instanceof InventoryScreen) mc.player.closeHandledScreen();
        if (mc.currentScreen != null) return false;
        mc.player.inventory.selectedSlot = held = slot;
        item = mc.player.inventory.getStack(slot).getItem();
        lastUsed[slot] = now;
        return true;
    }

    static boolean select(MinecraftClient mc, int kind) {
        ServerPlayerEntity server = mc.getServer() == null ? null
                : mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if (server == null) return false;
        long now = mc.world.getTime();
        if (pending >= 0) {
            if (mc.player.inventory.getStack(pending).getItem() == item
                    && known(mc, server, pending)) {
                int destination = pending;
                pending = -1;
                if (FinalFight.match(item, kind)) return equip(mc, destination, now);
            } else if (now - swappedAt < 24) return false;
            else pending = -1;
        }
        if (held >= 0 && mc.player.inventory.getStack(held).getItem() == item
                && FinalFight.match(item, kind) && server.inventory.getStack(held).getItem() == item)
            return equip(mc, held, now);

        int source = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.inventory.getStack(slot);
            if (stack.isEmpty() || !FinalFight.match(stack.getItem(), kind) || !known(mc, server, slot)) continue;
            if (source < 0 || stack.getCount() > mc.player.inventory.getStack(source).getCount()) source = slot;
        }
        if (source < 0) return false;
        if (source < 9) return equip(mc, source, now);
        if (!(mc.currentScreen instanceof InventoryScreen)) {
            if (mc.currentScreen == null) mc.openScreen(new InventoryScreen(mc.player));
            return false;
        }
        int destination = -1;
        for (int slot : new int[]{6, 7, 4, 0, 8, 3, 5, 2, 1}) {
            if (slot == held || now - lastUsed[slot] < 24 || !known(mc, server, slot)) continue;
            if (destination < 0) destination = slot;
            if (mc.player.inventory.getStack(slot).isEmpty()) { destination = slot; break; }
        }
        if (destination < 0) return false;
        item = mc.player.inventory.getStack(source).getItem();
        swappedAt = now;
        pending = destination;
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, source, destination,
                SlotActionType.SWAP, mc.player);
        return false;
    }
}
