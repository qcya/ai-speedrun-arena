package dev.aiarena.week29;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

/** Survival eating, using the ordinary client inventory and use action. */
public final class ArenaFood {
    private static int selectedBefore, waitTicks, startFood, elapsed;
    private static boolean eating;

    static void newSession() {
        eating = false;
        waitTicks = 0;
        elapsed = 0;
    }

    public static boolean tick(MinecraftClient game) {
        if (game.player == null || game.world == null || game.interactionManager == null) {
            eating = false;
            return false;
        }
        if (game.currentScreen != null || !game.player.isAlive()) {
            if (eating) release(game);
            return false;
        }
        int food = game.player.getHungerManager().getFoodLevel();
        if (eating) {
            elapsed++;
            if (food > startFood || elapsed >= 56) {
                release(game);
                return false;
            }
            holdStill(game);
            if (waitTicks-- > 0) return true;
            game.options.keyUse.setPressed(true);
            if (!game.player.isUsingItem() && game.player.getMainHandStack().isFood()) {
                game.interactionManager.interactItem(game.player, game.world, Hand.MAIN_HAND);
            }
            return true;
        }
        if (game.player.isSubmergedInWater()
                || food >= 18
                || game.player.getHealth() >= 17 && food > 12) return false;
        int best = -1;
        float bestScore = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = game.player.inventory.getStack(i);
            FoodComponent nutrition = stack.getItem().getFoodComponent();
            if (stack.isEmpty() || nutrition == null || !nutrition.getStatusEffects().isEmpty())
                continue;
            float score =
                    Math.min(20 - food, nutrition.getHunger()) + nutrition.getSaturationModifier();
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        if (best < 0 || game.player.currentScreenHandler != game.player.playerScreenHandler)
            return false;
        selectedBefore = game.player.inventory.selectedSlot;
        if (best >= 9) {
            game.interactionManager.clickSlot(
                    game.player.playerScreenHandler.syncId,
                    best,
                    8,
                    SlotActionType.SWAP,
                    game.player);
            best = 8;
        }
        game.player.inventory.selectedSlot = best;
        game.interactionManager.cancelBlockBreaking();
        eating = true;
        startFood = food;
        waitTicks = 1;
        elapsed = 0;
        holdStill(game);
        return true;
    }

    private static void holdStill(MinecraftClient g) {
        g.options.keyForward.setPressed(false);
        g.options.keyBack.setPressed(false);
        g.options.keyLeft.setPressed(false);
        g.options.keyRight.setPressed(false);
        g.options.keyJump.setPressed(false);
        g.options.keySprint.setPressed(false);
        g.options.keyAttack.setPressed(false);
        g.player.setSprinting(false);
    }

    private static void release(MinecraftClient g) {
        g.options.keyUse.setPressed(false);
        if (g.player.isUsingItem()) g.interactionManager.stopUsingItem(g.player);
        g.player.inventory.selectedSlot = selectedBefore;
        eating = false;
    }
}
