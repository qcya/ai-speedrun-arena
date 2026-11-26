package dev.aiarena.week29;

import dev.aiarena.week29.mixin.HandledScreenPosition;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import org.lwjgl.glfw.GLFW;

import java.util.Objects;
import java.util.function.Predicate;

/** Visible interactions with the vanilla survival and chest screens. */
public final class VisibleSlots {
    private enum Action {
        SCAN,
        TAKE,
        DOUBLE_CLICK,
        DEPOSIT
    }

    private static GenericContainerScreenHandler chest;
    private static InventoryScreen armorScreen;
    private static Action action = Action.SCAN;
    private static int source, hold, armorSlot = -1, armorDelay;
    private static boolean consolidate;
    static String status = "";

    private VisibleSlots() {}

    private static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty()
                && !b.isEmpty()
                && a.getItem() == b.getItem()
                && Objects.equals(a.getTag(), b.getTag());
    }

    public static void hover(MinecraftClient mc, HandledScreen<?> screen, Slot slot) {
        HandledScreenPosition position = (HandledScreenPosition) screen;
        double x = position.astraLeft() + slot.x + 8.0;
        double y = position.astraTop() + slot.y + 8.0;
        GLFW.glfwSetCursorPos(
                mc.getWindow().getHandle(),
                x * mc.getWindow().getWidth() / mc.getWindow().getScaledWidth(),
                y * mc.getWindow().getHeight() / mc.getWindow().getScaledHeight());
    }

    private static void click(MinecraftClient mc, int slot, SlotActionType type) {
        mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId, slot, 0, type, mc.player);
    }

    private static int vacancy(int firstPlayerSlot) {
        for (int i = firstPlayerSlot; i < chest.slots.size(); i++) {
            if (!chest.getSlot(i).hasStack()) return i;
        }
        return -1;
    }

    private static int mergeTarget(ItemStack stack, int first, int end) {
        for (int i = first; i < end; i++) {
            Slot slot = chest.getSlot(i);
            ItemStack there = slot.getStack();
            if (same(stack, there)
                    && there.getCount() < there.getMaxCount()
                    && slot.canInsert(stack)) return i;
        }
        return -1;
    }

    private static boolean deposit(MinecraftClient mc, HandledScreen<?> gui, int firstPlayerSlot) {
        ItemStack held = mc.player.inventory.getCursorStack();
        if (held.isEmpty()) return true;
        int dest = mergeTarget(held, firstPlayerSlot, chest.slots.size());
        if (dest < 0) dest = vacancy(firstPlayerSlot);
        // A concurrent inventory change may have filled the last player slot while the cursor was
        // busy.
        if (dest < 0) dest = mergeTarget(held, 0, firstPlayerSlot);
        if (dest < 0 && source >= 0 && !chest.getSlot(source).hasStack()) dest = source;
        if (dest < 0) {
            for (int i = 0; i < firstPlayerSlot; i++)
                if (!chest.getSlot(i).hasStack()) {
                    dest = i;
                    break;
                }
        }
        if (dest < 0) {
            status = "waiting for free slot";
            return false;
        }
        hover(mc, gui, chest.getSlot(dest));
        click(mc, dest, SlotActionType.PICKUP);
        status = "depositing held stack";
        return mc.player.inventory.getCursorStack().isEmpty();
    }

    /** A normal double-left-click produces PICKUP followed by PICKUP_ALL on the same chest slot. */
    public static boolean loot(
            MinecraftClient mc, Predicate<ItemStack> wanted, boolean hasRequestedCount) {
        if (!(mc.currentScreen instanceof GenericContainerScreen)
                || !(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler))
            return false;
        HandledScreen<?> gui = (HandledScreen<?>) mc.currentScreen;
        GenericContainerScreenHandler opened =
                (GenericContainerScreenHandler) mc.player.currentScreenHandler;
        if (chest != opened) {
            chest = opened;
            action = Action.SCAN;
            source = -1;
            hold = 2;
        }
        if (hold-- > 0) return false;
        int firstPlayerSlot = chest.getRows() * 9;
        ItemStack held = mc.player.inventory.getCursorStack();
        if (action == Action.DEPOSIT || (!held.isEmpty() && action != Action.DOUBLE_CLICK)) {
            if (deposit(mc, gui, firstPlayerSlot)) action = Action.SCAN;
            return false;
        }
        if (action == Action.DOUBLE_CLICK) {
            if (!held.isEmpty()) {
                hover(mc, gui, chest.getSlot(source));
                click(mc, source, SlotActionType.PICKUP_ALL);
            }
            action = Action.DEPOSIT;
            status = "double-clicking matching stacks";
            return false;
        }
        if (hasRequestedCount) return true;
        if (action == Action.TAKE) {
            Slot target = chest.getSlot(source);
            ItemStack item = target.getStack().copy();
            action = Action.SCAN;
            if (item.isEmpty() || !wanted.test(item)) return false;
            hover(mc, gui, target);
            if (consolidate && vacancy(firstPlayerSlot) >= 0) {
                click(mc, source, SlotActionType.PICKUP);
                action = Action.DOUBLE_CLICK;
                status = "picking up first matching stack";
            } else {
                // These are exactly the source-slot transfers vanilla uses for Shift +
                // double-click.
                for (int i = 0; i < firstPlayerSlot; i++) {
                    if (same(item, chest.getSlot(i).getStack()))
                        click(mc, i, SlotActionType.QUICK_MOVE);
                }
                status = "quick-moving matching chest stacks";
            }
            return false;
        }
        for (int i = 0; i < firstPlayerSlot; i++) {
            ItemStack item = chest.getSlot(i).getStack();
            if (item.isEmpty() || !wanted.test(item)) continue;
            if (vacancy(firstPlayerSlot) < 0
                    && mergeTarget(item, firstPlayerSlot, chest.slots.size()) < 0) continue;
            source = i;
            consolidate = false;
            if (item.getCount() < item.getMaxCount()) {
                for (int j = i + 1; j < firstPlayerSlot; j++) {
                    if (same(item, chest.getSlot(j).getStack())) {
                        consolidate = true;
                        break;
                    }
                }
            }
            hover(mc, gui, chest.getSlot(source));
            action = Action.TAKE;
            status = "hovering chest slot";
            return false;
        }
        return true;
    }

    private static int nextArmor(MinecraftClient mc, net.minecraft.item.Item wanted) {
        for (int slot = 9; slot < 45; slot++) {
            ItemStack stack = mc.player.playerScreenHandler.getSlot(slot).getStack();
            if ((wanted == null || stack.getItem() == wanted) && stack.getItem() instanceof ArmorItem
                    && mc.player
                            .getEquippedStack(((ArmorItem) stack.getItem()).getSlotType())
                            .isEmpty()) return slot;
        }
        return -1;
    }

    static boolean equipping(MinecraftClient mc) {
        return armorScreen != null && mc.currentScreen == armorScreen;
    }

    public static boolean equip(MinecraftClient mc) {
        return equip(mc, null);
    }

    public static boolean equip(MinecraftClient mc, net.minecraft.item.Item wanted) {
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return false;
        if (!equipping(mc)) {
            if (nextArmor(mc, wanted) < 0) return true;
            if (mc.currentScreen != null) return false;
            armorScreen = new InventoryScreen(mc.player);
            mc.openScreen(armorScreen);
            armorSlot = -1;
            armorDelay = 2;
            status = "opening survival inventory";
            return false;
        }
        if (armorDelay-- > 0) return false;
        int next = nextArmor(mc, wanted);
        if (next < 0) {
            mc.player.closeHandledScreen();
            armorScreen = null;
            return true;
        }
        if (next != armorSlot) {
            armorSlot = next;
            hover(mc, armorScreen, mc.player.playerScreenHandler.getSlot(next));
            status = "hovering armor slot";
        } else {
            click(mc, next, SlotActionType.QUICK_MOVE);
            status = "equipping armor in inventory";
            armorSlot = -1;
            armorDelay = 1;
        }
        return false;
    }
}
