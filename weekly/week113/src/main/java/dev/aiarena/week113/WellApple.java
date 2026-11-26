package dev.aiarena.week113;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/** Reserve eight physical ingots only when the west-well payments will still total at least 22. */
final class WellApple {
    private static final BlockPos TABLE=new BlockPos(-155,36,118);
    private static int source,reserve,payment,count,started,sent;
    private static boolean disabled,split,reserved,cancelling;
    static void reset(){started=sent=0;disabled=split=reserved=cancelling=false;}
    static boolean reserved(){return reserved;}
    static void finish(){reserved=false;disabled=true;}
    private static int gold(ItemStack stack){return stack.getItem()==Items.GOLD_INGOT?stack.getCount():0;}
    private static int screenSlot(int slot){return slot<9?36+slot:slot;}
    private static void click(MinecraftClient m,int slot,int button,SlotActionType type){
        m.interactionManager.clickSlot(m.player.currentScreenHandler.syncId,screenSlot(slot),button,type,m.player);
    }

    private static boolean eligible(MinecraftClient m,ServerPlayerEntity self){
        if(!EarlyBarter.paid() || m.currentScreen!=null || m.player.hurtTime>0 || m.player.getHealth()<8
                || m.player.squaredDistanceTo(-154.70,35,119.60)>.12 || m.player.inventory.count(Items.APPLE)==0
                || self.inventory.count(Items.APPLE)==0 || m.player.inventory.count(Items.GOLDEN_APPLE)>0)return false;
        boolean hasBench=m.world.getBlockState(TABLE).getBlock()==Blocks.CRAFTING_TABLE
                || m.player.inventory.count(Items.CRAFTING_TABLE)>0 || (m.player.inventory.count(Items.OAK_PLANKS)>=4
                && m.player.inventory.count(Items.IRON_PICKAXE)>0);
        count=self.inventory.count(Items.GOLD_INGOT);
        if(!hasBench || count<21 || count>64 || m.player.inventory.count(Items.GOLD_INGOT)!=count)return false;
        source=reserve=payment=-1;
        for(int i=0;i<36;i++){
            ItemStack a=m.player.inventory.getStack(i),b=self.inventory.getStack(i);
            if(gold(a)==count && gold(b)==count)source=i;
            if(i>=9 && reserve<0 && a.isEmpty() && b.isEmpty())reserve=i;
            if(i<9 && payment<0 && a.isEmpty() && b.isEmpty())payment=i;
        }
        if(source>=0 && source<9)payment=source;
        return source>=0 && reserve>=0 && payment>=0;
    }

    /** -1 for ordinary feeding, 0 for ongoing inventory actions, 1 for confirmed payment. */
    static int pay(MinecraftClient m,int age){
        if(disabled || m.getServer()==null)return -1;
        ServerPlayerEntity self=m.getServer().getPlayerManager().getPlayer(m.player.getUuid());
        if(self==null)return -1;
        if(started==0){if(!eligible(m,self))return -1;started=age;}
        if(sent>0){
            if(gold(m.player.inventory.getStack(reserve))==8 && gold(self.inventory.getStack(reserve))==8
                    && self.inventory.count(Items.GOLD_INGOT)==8 && m.player.inventory.count(Items.GOLD_INGOT)==8){
                reserved=true;disabled=true;return 1;
            }
            if(age-sent>24){finish();return -1;}
            return 0;
        }
        if(m.player.hurtTime>0 || age-started>50)cancelling=true;
        if(cancelling){
            if(m.currentScreen instanceof InventoryScreen){
                // Return our own cursor to its source before releasing the visible inventory.
                ItemStack cursor=m.player.inventory.getCursorStack(),original=m.player.inventory.getStack(source);
                if(gold(cursor)>0 && (original.isEmpty() || gold(original)>0) && gold(cursor)+gold(original)<=64){
                    click(m,source,0,SlotActionType.PICKUP);return 0;
                }
                m.player.closeHandledScreen();return 0;
            }
            finish();return -1;
        }
        int due=count-8;
        if(split){
            if(m.currentScreen instanceof InventoryScreen){m.player.closeHandledScreen();return 0;}
            if(m.currentScreen!=null)return 0;
            m.player.inventory.selectedSlot=payment;
            if(Middle.dropTradeStack(m,due))sent=age;
            return 0;
        }
        if(!(m.currentScreen instanceof InventoryScreen)){
            if(m.currentScreen==null)m.openScreen(new InventoryScreen(m.player));
            return 0;
        }
        ItemStack cursor=m.player.inventory.getCursorStack();int saved=gold(m.player.inventory.getStack(reserve));
        int original=gold(m.player.inventory.getStack(source));
        if(!cursor.isEmpty() && gold(cursor)==0){cancelling=true;return 0;}
        if(!cursor.isEmpty()){
            if(count<=32){
                if(gold(cursor)>8)click(m,source,1,SlotActionType.PICKUP);
                else if(gold(cursor)==8 && saved==0)click(m,reserve,0,SlotActionType.PICKUP);
                else cancelling=true;
            }else if(saved<8)click(m,reserve,1,SlotActionType.PICKUP);
            else click(m,source,0,SlotActionType.PICKUP);
        }else if(saved==0 && original==count){
            // With 21 ingots this picks up 11, puts three back and shelves the remaining eight.
            click(m,source,count<=32?1:0,SlotActionType.PICKUP);
        }else if(saved==8 && original==due && source!=payment){
            click(m,source,payment,SlotActionType.SWAP);
        }else if(saved==8 && gold(m.player.inventory.getStack(payment))==due
                && gold(self.inventory.getStack(payment))==due && gold(self.inventory.getStack(reserve))==8){
            split=true;
        }
        return 0;
    }
    private WellApple(){}
}
