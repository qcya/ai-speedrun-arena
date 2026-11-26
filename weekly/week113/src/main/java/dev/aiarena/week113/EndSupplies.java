package dev.aiarena.week113;

import net.minecraft.entity.Entity;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Recipe;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.tag.ItemTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

import java.util.Optional;

/** Buy the remaining height with carried blocks before jumping off the island floor. */
final class EndSupplies {
    private static BlockPos base, digging;
    private static int craftWait, waitForDrop;
    private static Item pending;
    private static boolean leftBase, manual, awaitingInventory;

    private EndSupplies() {}

    static void reset() {
        base = digging = null;
        pending = null;
        leftBase = manual = awaitingInventory = false;
        craftWait = waitForDrop = 0;
    }

    static boolean active() { return base != null; }

    static boolean stable(Item i) {
        return ItemTags.PLANKS.contains(i) || ItemTags.LOGS.contains(i) || ItemTags.STONE_BRICKS.contains(i)
                || i == Items.NETHERRACK || i == Items.DIRT || i == Items.COARSE_DIRT
                || i == Items.STONE || i == Items.COBBLESTONE || i == Items.MOSSY_COBBLESTONE
                || i == Items.ANDESITE || i == Items.DIORITE || i == Items.GRANITE
                || i == Items.POLISHED_ANDESITE || i == Items.POLISHED_DIORITE || i == Items.POLISHED_GRANITE
                || i == Items.BLACKSTONE || i == Items.POLISHED_BLACKSTONE || i == Items.GILDED_BLACKSTONE
                || i == Items.POLISHED_BLACKSTONE_BRICKS || i == Items.CRACKED_POLISHED_BLACKSTONE_BRICKS
                || i == Items.CHISELED_POLISHED_BLACKSTONE || i == Items.BASALT || i == Items.POLISHED_BASALT
                || i == Items.END_STONE || i == Items.END_STONE_BRICKS || i == Items.NETHER_BRICKS
                || i == Items.CHISELED_NETHER_BRICKS || i == Items.CRACKED_NETHER_BRICKS
                || i == Items.RED_NETHER_BRICKS || i == Items.BRICKS || i == Items.SOUL_SOIL
                || i == Items.OBSIDIAN || i == Items.CRYING_OBSIDIAN || i == Items.QUARTZ_BLOCK;
    }

    // A chain of already placed gravel must itself rest on a solid top face.
    static boolean anchored(MinecraftClient m, BlockPos target) {
        BlockPos below = target.down();
        while (below.getY() > 0 && m.world.getBlockState(below).isOf(Blocks.GRAVEL)) below = below.down();
        return m.world.getBlockState(below).isSideSolidFullSquare(m.world, below, Direction.UP);
    }

    private static int carried(Iterable<ItemStack> stacks, boolean gravel) {
        int n = 0;
        for (ItemStack stack : stacks)
            if (stable(stack.getItem()) || (gravel && stack.getItem() == Items.GRAVEL)) n += stack.getCount();
        return n;
    }

    private static boolean enough(MinecraftClient m) {
        int missing = 0;
        for (int y = base.getY(); y <= 84; y++)
            if (m.world.getBlockState(new BlockPos(base.getX(), y, base.getZ())).getMaterial().isReplaceable()) missing++;
        boolean gravel = anchored(m, base);
        int count = carried(m.player.inventory.main, gravel);
        ServerPlayerEntity server = m.getServer() == null ? null
                : m.getServer().getPlayerManager().getPlayer(m.player.getUuid());
        awaitingInventory = count >= missing;
        if (server == null || server.getEntityWorld().getRegistryKey() != World.END) return false;
        count = Math.min(count, carried(server.inventory.main, gravel));
        FinalFight.status = "launch column supplies " + count + "/" + missing;
        return count >= missing;
    }

    static boolean begin(MinecraftClient m, BlockPos foot) {
        if (base == null) base = foot;
        return prepare(m);
    }

    static boolean prepare(MinecraftClient m) {
        Race.stop(m);
        m.options.keyAttack.setPressed(false);
        if (pending != null) {
            craft(m);
            return false;
        }
        if (enough(m)) {
            if (m.currentScreen instanceof InventoryScreen) m.player.closeHandledScreen();
            if (leftBase) {
                double x = base.getX() + .5, z = base.getZ() + .5;
                if (Math.hypot(x - m.player.getX(), z - m.player.getZ()) > .32
                        || m.player.getY() < base.getY() - .05 || !m.player.isOnGround()) {
                    FinalFight.status = "returning from the island quarry";
                    walk(m, x, z);
                    return false;
                }
            }
            reset();
            return true;
        }
        if (awaitingInventory) return false;
        if (m.player.inventory.count(Items.QUARTZ) >= 4) pending = Items.QUARTZ_BLOCK;
        else if (m.player.inventory.count(Items.NETHER_BRICK) >= 4) pending = Items.NETHER_BRICKS;
        if (pending != null) {
            craftWait = 0;
            craft(m);
            return false;
        }
        // Mining is confined to one surface layer of the island by the tower foot.
        // Existing elevated columns and their foundations are outside the quarry.
        quarry(m);
        return false;
    }

    private static int rawSlot(MinecraftClient m, Item i) {
        for (int n = 0; n < 36; n++)
            if (m.player.inventory.getStack(n).getItem() == i) return n < 9 ? n + 36 : n;
        return -1;
    }

    private static void click(MinecraftClient m, int slot, int button, SlotActionType type) {
        m.interactionManager.clickSlot(m.player.playerScreenHandler.syncId, slot, button, type, m.player);
    }

    // Only an empty bottle or bone is expendable. Never discard a tool, weapon,
    // projectile, meal, shield, bucket, boat, raw material or placement stack.
    private static boolean room(MinecraftClient m, Item output) {
        if (m.player.inventory.getEmptySlot() >= 0
                || m.player.inventory.getOccupiedSlotWithRoomForStack(new ItemStack(output)) >= 0) return true;
        for (Item junk : new Item[]{Items.GLASS_BOTTLE, Items.BONE}) {
            int slot = rawSlot(m, junk);
            if (slot >= 0) {
                if (!(m.currentScreen instanceof InventoryScreen)) m.openScreen(new InventoryScreen(m.player));
                else click(m, slot, 1, SlotActionType.THROW);
                return false;
            }
        }
        return false;
    }

    private static void craft(MinecraftClient m) {
        FinalFight.status = "crafting launch blocks in the inventory";
        if (!(m.currentScreen instanceof InventoryScreen)) {
            if (m.currentScreen == null) m.openScreen(new InventoryScreen(m.player));
            return;
        }
        ScreenHandler handler = m.player.playerScreenHandler;
        Item raw = pending == Items.QUARTZ_BLOCK ? Items.QUARTZ : Items.NETHER_BRICK;
        if (handler.getSlot(0).getStack().getItem() == pending) {
            if (!room(m, pending)) return;
            click(m, 0, 0, SlotActionType.QUICK_MOVE);
            m.player.closeHandledScreen();
            pending = null;
            craftWait = 0;
            return;
        }
        if (++craftWait != 1 && craftWait < 16) return;
        if (craftWait >= 16) {
            // Closing a rejected recipe returns the real crafting slots to inventory.
            m.player.closeHandledScreen();
            manual = true;
            pending = null;
            craftWait = 0;
            return;
        }
        if (m.player.inventory.count(raw) < 4) { pending = null; return; }
        if (!room(m, pending)) { craftWait = 0; return; }
        String id = raw == Items.QUARTZ ? "quartz_block" : "nether_bricks";
        Optional<? extends Recipe<?>> recipe = m.world.getRecipeManager().get(new Identifier(id));
        if (!manual && recipe.isPresent() && m.player.getRecipeBook().contains(recipe.get())) {
            m.interactionManager.clickRecipe(handler.syncId, recipe.get(), true);
            return;
        }
        // A locked recipe is still craftable by hand. Split one real item into
        // each inventory-grid cell; any unused cursor items go back to the source.
        int cell = 1;
        while (cell <= 4) {
            int source = rawSlot(m, raw);
            if (source < 0 || !m.player.inventory.getCursorStack().isEmpty()) break;
            click(m, source, 0, SlotActionType.PICKUP);
            while (cell <= 4 && !m.player.inventory.getCursorStack().isEmpty())
                click(m, cell++, 1, SlotActionType.PICKUP);
            if (!m.player.inventory.getCursorStack().isEmpty()) click(m, source, 0, SlotActionType.PICKUP);
        }
    }

    private static void walk(MinecraftClient m, double x, double z) {
        double d = Math.hypot(x - m.player.getX(), z - m.player.getZ());
        if (d > .045) {
            Race.aim(m, new Vec3d(x, m.player.getEyeY() - .55 * d, z));
            double yaw = Math.toRadians(m.player.yaw);
            m.options.keyForward.setPressed((-(x - m.player.getX()) * Math.sin(yaw)
                    + (z - m.player.getZ()) * Math.cos(yaw)) / d > .8);
        }
        m.options.keyJump.setPressed(m.player.horizontalCollision && m.player.isOnGround());
    }

    private static boolean protectedColumn(BlockPos p) {
        return p.getX() == 5 && Math.abs(p.getZ()) <= 1;
    }

    private static void quarry(MinecraftClient m) {
        if (!room(m, Items.END_STONE)) return;
        // A dropped item can settle at the bottom of the one-deep excavation.
        // Use its actual coordinates, including Y below the runner's feet.
        ItemEntity drop = null;
        for (Entity entity : m.world.getEntities()) {
            if (!(entity instanceof ItemEntity) || !entity.isAlive()) continue;
            ItemEntity e = (ItemEntity) entity;
            if (e.getStack().getItem() != Items.END_STONE
                    || Math.abs(e.getY() - base.getY()) > 1.7) continue;
            BlockPos p = e.getBlockPos();
            if (p.getX() < 4 || p.getX() > 9 || Math.abs(p.getZ()) > 4
                    || !m.world.getBlockState(new BlockPos(p.getX(), base.getY() - 2, p.getZ())).isOf(Blocks.END_STONE))
                continue;
            if (drop == null || e.squaredDistanceTo(m.player) < drop.squaredDistanceTo(m.player)) drop = e;
        }
        if (drop != null) {
            FinalFight.status = "collecting mined end stone on the island floor";
            leftBase = true;
            if (m.currentScreen instanceof InventoryScreen) m.player.closeHandledScreen();
            walk(m, drop.getX(), drop.getZ());
            return;
        }
        if (digging != null && !m.world.getBlockState(digging).isOf(Blocks.END_STONE)) {
            digging = null;
            waitForDrop = 4;
        }
        if (waitForDrop > 0) { waitForDrop--; return; }
        if (!m.player.isOnGround() || !FinalFight.selectQuarryPick(m)) return;
        if (digging == null) {
            double best = Double.MAX_VALUE;
            Box underPlayer = m.player.getBoundingBox().stretch(0, -1.05, 0);
            for (int x = 4; x <= 9; x++) for (int z = -4; z <= 4; z++) {
                BlockPos p = new BlockPos(x, base.getY() - 1, z);
                double distance = m.player.getCameraPosVec(1).squaredDistanceTo(Vec3d.ofCenter(p));
                if (protectedColumn(p) || distance >= best || distance > 18 || underPlayer.intersects(new Box(p))
                        || !m.world.getBlockState(p).isOf(Blocks.END_STONE)
                        || !m.world.getBlockState(p.down()).isOf(Blocks.END_STONE)
                        || !m.world.getBlockState(p.up()).isAir() || !m.world.getBlockState(p.up(2)).isAir())
                    continue;
                BlockHitResult sight = m.world.rayTrace(new RayTraceContext(m.player.getCameraPosVec(1),
                        Vec3d.ofCenter(p), RayTraceContext.ShapeType.OUTLINE,
                        RayTraceContext.FluidHandling.NONE, m.player));
                if (sight.getType() == HitResult.Type.BLOCK && p.equals(sight.getBlockPos())) {
                    digging = p;
                    best = distance;
                }
            }
        }
        if (digging != null) {
            FinalFight.status = "mining natural end stone for the launch column";
            if (m.player.getBoundingBox().stretch(0, -1.05, 0).intersects(new Box(digging))) {
                digging = null;
                return;
            }
            if (Opening.target(m, digging) != null) m.options.keyAttack.setPressed(true);
        }
    }
}
