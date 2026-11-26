package dev.aiarena.week113;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/** Move the ordinary pre-End meal into the return transfer or onto the safe portal-room footing. */
final class EndMeal {
    private static final Item[] FOODS={Items.COOKED_BEEF,Items.COOKED_PORKCHOP,Items.COOKED_SALMON,
            Items.BREAD,Items.COOKED_COD};
    private static Item eating;
    private static boolean fromReturn, finishedRoom;

    static void reset(){eating=null;fromReturn=finishedRoom=false;}

    private static boolean needsEndMeal(MinecraftClient m){
        int food=m.player.getHungerManager().getFoodLevel();
        float hp=m.player.getHealth();
        return food<20 && !(food>=18 && hp>=14) && !(food>15 && hp>=16);
    }

    private static boolean eat(MinecraftClient m,boolean inNether){
        if(eating!=null){
            if(m.player.isUsingItem() && m.player.getActiveItem().getItem()==eating){
                m.options.keyUse.setPressed(true);return true;
            }
            eating=null;m.options.keyUse.setPressed(false);return false;
        }
        if(m.currentScreen!=null || m.player.isUsingItem() || m.getServer()==null)return false;
        ServerPlayerEntity self=m.getServer().getPlayerManager().getPlayer(m.player.getUuid());
        if(self==null || self.world.getRegistryKey()!=m.world.getRegistryKey())return false;
        for(Item item:FOODS){
            Hand hand=Hand.OFF_HAND;
            if(m.player.getOffHandStack().getItem()!=item){
                int slot=-1;
                // Portals close inventory screens. Use an already accessible meal inside them.
                int last=inNether?9:36;
                for(int i=0;i<last;i++)if(m.player.inventory.getStack(i).getItem()==item){slot=i;break;}
                if(slot<0)continue;
                if(!Hotbar.select(m,item))return true;
                hand=Hand.MAIN_HAND;
            }
            if(self.getStackInHand(hand).getItem()!=item)return true;
            if(m.interactionManager.interactItem(m.player,m.world,hand).isAccepted() && m.player.isUsingItem()){
                eating=item;fromReturn=inNether;m.options.keyUse.setPressed(true);
                return true;
            }
            return false;
        }
        return false;
    }

    static boolean inReturn(MinecraftClient m,int edgeTicks){
        int food=m.player.getHungerManager().getFoodLevel();
        if(eating==null && (edgeTicks>40 || food>=20 || (food==19 && m.player.getHealth()>=16)))return false;
        if(m.world.getRegistryKey()!=World.NETHER || m.getServer()==null)return false;
        ServerPlayerEntity self=m.getServer().getPlayerManager().getPlayer(m.player.getUuid());
        if(self==null || self.world.getRegistryKey()!=World.NETHER)return false;
        // The protected outer pad straddles z=165. Its feet block itself is outside the portal.
        Box client=m.player.getBoundingBox().contract(.001),server=self.getBoundingBox().contract(.001);
        for(int x=-87;x<=-86;x++)for(int y=74;y<=76;y++){
            BlockPos cell=new BlockPos(x,y,164);Box volume=new Box(cell);
            if(client.intersects(volume) && server.intersects(volume)
                    && m.world.getBlockState(cell).getBlock()==Blocks.NETHER_PORTAL
                    && self.world.getBlockState(cell).getBlock()==Blocks.NETHER_PORTAL)return eat(m,true);
        }
        return false;
    }

    static boolean inPortalRoom(MinecraftClient m,int reference){
        if(fromReturn){
            if(eating!=null){eating=null;m.options.keyUse.setPressed(false);}
            fromReturn=false;
        }
        if(finishedRoom || reference<5752 || reference>5776 || m.world.getRegistryKey()!=World.OVERWORLD
                || m.player.getY()<40.98 || m.player.getY()>41.08 || !m.player.isOnGround()
                || Math.hypot(m.player.getX()+763.4,m.player.getZ()-1275.47)>.38
                || m.world.getBlockState(new BlockPos(-764,40,1275)).getCollisionShape(m.world,new BlockPos(-764,40,1275)).isEmpty())return false;
        if(eating==null && !needsEndMeal(m)){finishedRoom=true;return false;}
        for(Entity e:m.world.getEntities(m.player,m.player.getBoundingBox().expand(5))) {
            if(e instanceof HostileEntity && e.isAlive() && e.squaredDistanceTo(m.player)<25 && m.player.canSee(e)){
                if(eating!=null){m.options.keyUse.setPressed(false);m.interactionManager.stopUsingItem(m.player);eating=null;}
                return false;
            }
        }
        if(!eat(m,false))return false;
        Race.stop(m);return true;
    }
    private EndMeal(){}
}
