package dev.aiarena.week113;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Fixed weekly approach. Every bridge needs a real, reachable support face and a held block. */
public final class BastionApproach {
    private static final List<BlockPos> path = new ArrayList<>();
    static {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                BastionApproach.class.getResourceAsStream("/bastion-approach.csv"), StandardCharsets.UTF_8))) {
            for (String line; (line=reader.readLine())!=null;) {
                if(line.isEmpty() || line.startsWith("#"))continue;
                String[] xyz=line.split(",");
                path.add(new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2])));
            }
        } catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    static String status="";
    private static int index, inventoryTicks;
    private static Boolean originalAutoJump;
    public static void reset(){restoreJump();index=0;inventoryTicks=0;status="";}
    private static void restoreJump(){
        if(originalAutoJump!=null){MinecraftClient.getInstance().options.autoJump=originalAutoJump;originalAutoJump=null;}
    }
    static boolean open(MinecraftClient m,BlockPos p){
        return m.world.getBlockState(p).getCollisionShape(m.world,p).isEmpty();
    }
    private static boolean select(MinecraftClient m,Item... choices){
        for(Item item:choices)for(int slot=0;slot<9;slot++) {
            if(m.player.inventory.getStack(slot).getItem()==item){m.player.inventory.selectedSlot=slot;return true;}
        }
        for(Item item:choices)for(int slot=9;slot<36;slot++){
            if(m.player.inventory.getStack(slot).getItem()!=item)continue;
            if(m.currentScreen==null){m.openScreen(new InventoryScreen(m.player));inventoryTicks=0;return false;}
            if(++inventoryTicks<2)return false;
            m.interactionManager.clickSlot(m.player.playerScreenHandler.syncId,slot,m.player.inventory.selectedSlot,SlotActionType.SWAP,m.player);
            m.player.closeHandledScreen();return false;
        }
        return false;
    }
    static void move(MinecraftClient m,double x,double z,boolean sneak,boolean sprint){
        double dx=x-m.player.getX(),dz=z-m.player.getZ();
        double norm=Math.hypot(dx,dz);
        m.options.keySneak.setPressed(sneak);
        if(norm<.045)return;
        double ang=Math.toRadians(m.player.yaw),forward=(-Math.sin(ang)*dx+Math.cos(ang)*dz)/norm;
        double left=(Math.cos(ang)*dx+Math.sin(ang)*dz)/norm;
        m.options.keyForward.setPressed(forward>.3827);m.options.keyBack.setPressed(forward<-.3827);
        m.options.keyLeft.setPressed(left>.3827);m.options.keyRight.setPressed(left<-.3827);
        m.options.keySprint.setPressed(sprint && forward>.8 && !sneak);
    }
    static boolean bridge(MinecraftClient m,BlockPos previous,BlockPos target){
        BlockPos anchor=previous.down();int dx=target.getX()-previous.getX(),dz=target.getZ()-previous.getZ();
        Direction face=Direction.fromVector(dx,0,dz);
        m.options.keySneak.setPressed(true);
        if(face==null || Math.abs(dx)+Math.abs(dz)!=1 || open(m,anchor))return false;
        if(!select(m,Items.DIRT,Items.NETHERRACK,Items.BLACKSTONE,Items.COBBLESTONE))return false;
        Vec3d center=Vec3d.ofCenter(anchor);
        double across=(m.player.getX()-center.x)*dx+(m.player.getZ()-center.z)*dz;
        if(across>.51)Race.aim(m,center.add(dx*.499,.21,dz*.499));
        else Race.aim(m,new Vec3d(m.player.getX()-dx*.15,m.player.getEyeY()-1.95,m.player.getZ()-dz*.15));
        m.gameRenderer.updateTargetedEntity(1);
        if(m.crosshairTarget!=null && m.crosshairTarget.getType()==HitResult.Type.BLOCK){
            BlockHitResult hit=(BlockHitResult)m.crosshairTarget;
            if(hit.getBlockPos().equals(anchor) && hit.getSide()==face){
                ActionResult r=m.interactionManager.interactBlock(m.player,m.world,Hand.MAIN_HAND,hit);
                if(r.isAccepted()){m.player.swingHand(Hand.MAIN_HAND);return true;}
            }
        }
        if(across<.69)move(m,center.x+dx*.69,center.z+dz*.69,across>.47,false);
        return false;
    }
    static BlockHitResult line(MinecraftClient m, Vec3d point){
        Vec3d eye=m.player.getCameraPosVec(1);
        if(eye.squaredDistanceTo(point)>m.interactionManager.getReachDistance()*m.interactionManager.getReachDistance())return null;
        BlockHitResult hit=m.world.rayTrace(new RayTraceContext(eye,point,RayTraceContext.ShapeType.OUTLINE,RayTraceContext.FluidHandling.NONE,m.player));
        return hit.getType()==HitResult.Type.BLOCK?hit:null;
    }
    private static boolean extinguish(MinecraftClient m){
        BlockPos fire=new BlockPos(-22,41,54);
        if(m.world.getBlockState(fire).getBlock()!=Blocks.FIRE)return false;
        Vec3d point=new Vec3d(fire.getX()+.5,fire.getY()+.04,fire.getZ()+.5);
        BlockHitResult planned=line(m,point);
        if(planned==null || !planned.getBlockPos().equals(fire))return false;
        Race.aim(m,point);m.gameRenderer.updateTargetedEntity(1);
        if(m.crosshairTarget instanceof BlockHitResult && ((BlockHitResult)m.crosshairTarget).getBlockPos().equals(fire)){
            BlockHitResult hit=(BlockHitResult)m.crosshairTarget;
            m.interactionManager.attackBlock(fire,hit.getSide());m.player.swingHand(Hand.MAIN_HAND);
        }
        return true;
    }
    private static boolean chestInReach(MinecraftClient m){
        BlockPos chest=new BlockPos(-114,36,106);
        if(m.world.getBlockState(chest).getBlock()!=Blocks.CHEST)return false;
        Vec3d mid=Vec3d.ofCenter(chest);
        for(Direction face:Direction.values()){
            BlockHitResult hit=line(m,mid.add(Vec3d.of(face.getVector()).multiply(.43)));
            if(hit!=null && hit.getBlockPos().equals(chest))return true;
        }
        return false;
    }
    private static final BlockPos lootPerch = new BlockPos(-111,36,109);
    static boolean onLootPerch(MinecraftClient m) {
        return m.player.isOnGround() && m.player.getBlockPos().equals(lootPerch) && !open(m,lootPerch.down());
    }
    private static boolean reachChestTerrace(MinecraftClient m,BlockPos stance) {
        double x=stance.getX()+.5,z=stance.getZ()+.5;
        double offset=Math.hypot(x-m.player.getX(),z-m.player.getZ());
        boolean supported=m.player.isOnGround() && Math.abs(m.player.getY()-stance.getY())<.12
                && !open(m,stance.down()) && m.player.getBlockPos().equals(stance);
        if (supported && offset<.48 && Math.hypot(m.player.getVelocity().x,m.player.getVelocity().z)<.11 && chestInReach(m)) {
            restoreJump();
            return true;
        }
        // Brake on a verified solid block before letting the chest and inventory screens take input.
        Race.aim(m,new Vec3d(x,m.player.getEyeY(),z));
        if (offset>.15) move(m,x,z,offset<.85,false);
        else m.options.keySneak.setPressed(true);
        status="settling on the grounded first-chest terrace";
        return false;
    }

    private static boolean safe(MinecraftClient m,BlockPos p) {
        return m.world.getBlockState(p).getBlock()!=Blocks.FIRE && !m.world.getBlockState(p).getMaterial().isLiquid() && !open(m,p.down()) && open(m,p) && open(m,p.up()) &&
                m.world.getBlockState(p.down()).getBlock()!=Blocks.MAGMA_BLOCK && !m.world.getBlockState(p.down()).getMaterial().isLiquid();
    }
    // Trace the player's width across the live surface. Select a distant aim point
    // only when a sprinting player can run all the way there without a new bridge.
    private static boolean corridor(MinecraftClient m,BlockPos target,boolean forJump) {
        double ax=m.player.getX(),az=m.player.getZ(),dx=target.getX()+.5-ax,dz=target.getZ()+.5-az,len=Math.hypot(dx,dz);
        if(len<.25)return false;
        double nx=-dz/len*.26,nz=dx/len*.26;
        int y=(int)Math.floor(m.player.getY()+.1),steps=(int)Math.ceil(len/.4);
        for(int i=0;i<=steps;i++) {
            double f=(double)i/steps,x=ax+f*dx,z=az+f*dz;
            int surface=Integer.MIN_VALUE;
            for(int shift=1;shift>=-2;shift--){
                BlockPos feet=new BlockPos(x,y+shift,z);
                if(safe(m,feet)){surface=feet.getY();break;}
            }
            if(surface==Integer.MIN_VALUE || (forJump && (surface<y-1 || surface>y+1)))return false;
            y=surface;
            for(int side=-1;side<=1;side+=2){
                BlockPos feet=new BlockPos(x+side*nx,y,z+side*nz);
                if(!open(m,feet) || !open(m,feet.up()) || forJump && !open(m,feet.up(2)))return false;
            }
        }
        return Math.abs(y-target.getY())<=1;
    }
    /** Packets only follow ordinary local input or a rendered crosshair interaction. */
    public static boolean tick(MinecraftClient m){
        Race.stop(m);m.options.keyAttack.setPressed(false);
        if(index>=path.size())return reachChestTerrace(m,path.get(path.size()-1));
        // One supported dirt extension leaves the next cell open as a moat against sword pigs.
        if(index>181 && !open(m,lootPerch.down()) && m.player.squaredDistanceTo(
                lootPerch.getX()+.5,lootPerch.getY(),lootPerch.getZ()+.5)<3.2
                && (m.player.getX()>lootPerch.getX()-.45 || onLootPerch(m))) {
            return reachChestTerrace(m,lootPerch);
        }
        if(originalAutoJump==null){originalAutoJump=m.options.autoJump;m.options.autoJump=false;}
        // Sprinting across a corner or stepping onto a stair can carry the player
        // directly to a later foot cell. Resume from the tile actually occupied.
        if(index>=65 && index<=75 && extinguish(m)){status="extinguishing fire near the bastion route";return false;}
        boolean rapid=(index>=24 && index<=46) || (index>=65 && index<=84) || (index>=97 && index<=165);
        if(rapid){
            BlockPos least=path.get(index);
            double best=m.player.squaredDistanceTo(least.getX()+.5,least.getY(),least.getZ()+.5);
            int nearest=index;
            for(int j=index+1;j<Math.min(path.size(),index+9);j++){
                BlockPos q=path.get(j);
                if(open(m,q.down()))break;
                double distance=m.player.squaredDistanceTo(q.getX()+.5,q.getY(),q.getZ()+.5);
                if(distance<best && distance<4){best=distance;nearest=j;}
            }
            index=nearest;
        }
        if(m.player.isOnGround() || rapid)for(int j=Math.min(index+(rapid?8:6),path.size()-1);j>index;j--) {
            BlockPos q=path.get(j);
            if(m.player.getY()-q.getY()>-.65 && m.player.getY()-q.getY()<1.65 && !open(m,q.down()) &&
                    Math.hypot(m.player.getX()-q.getX()-.5,m.player.getZ()-q.getZ()-.5)<.98){index=j;break;}
        }
        while(index<path.size()){
            BlockPos p=path.get(index);double dx=p.getX()+.5-m.player.getX(),dz=p.getZ()+.5-m.player.getZ();
            boolean near=Math.hypot(dx,dz)<.49 && Math.abs(m.player.getY()-p.getY())<.81;
            if(!near && index>0 && Math.abs(m.player.getY()-p.getY())<.81) {
                BlockPos prev=path.get(index-1);
                double ax=p.getX()-prev.getX(),az=p.getZ()-prev.getZ(),len=Math.hypot(ax,az);
                if(len>0) near=(-dx*ax-dz*az)/len>=-.12 && Math.abs(dx*az-dz*ax)/len<.72;
            }
            if(!near || open(m,p.down()))break;
            index++;
        }
        if(index==path.size())return reachChestTerrace(m,path.get(path.size()-1));
        BlockPos p=path.get(index),previous=path.get(Math.max(0,index-1));
        status="walking to bastion "+index+"/"+path.size()+" "+p.toShortString();
        if(open(m,p.down())){
            status="bridging to bastion "+index+"/"+path.size()+" "+p.toShortString();
            bridge(m,previous,p);return false;
        }
        double dy=p.getY()-m.player.getY();
        BlockPos overhead=m.player.getBlockPos().up(2);
        BlockPos obstruction=dy>.6 && !open(m,overhead)?overhead:!open(m,p.up())?p.up():!open(m,p)?p:null;
        if(obstruction!=null) {
            status="mining approach "+obstruction.toShortString();
            if(!select(m,Items.IRON_PICKAXE))return false;
            Race.aim(m,Vec3d.ofCenter(obstruction));m.gameRenderer.updateTargetedEntity(1);
            if(m.crosshairTarget instanceof BlockHitResult && ((BlockHitResult)m.crosshairTarget).getBlockPos().equals(obstruction))m.options.keyAttack.setPressed(true);
            return false;
        }
        BlockPos look=p;
        boolean besideGap=index+1<path.size() && open(m,path.get(index+1).down());
        boolean careful=besideGap || m.world.getBlockState(p.down()).getBlock()==Blocks.MAGMA_BLOCK;
        int sx=p.getX()-previous.getX(),sz=p.getZ()-previous.getZ();
        // Look along the straight portion; travel continues through each intermediate foot cell.
        for(int j=index+1;!careful && j<Math.min(index+6,path.size());j++){
            BlockPos q=path.get(j);
            if(q.getY()!=look.getY() || q.getX()-look.getX()!=sx || q.getZ()-look.getZ()!=sz ||
                    open(m,q.down()) || !open(m,q) || !open(m,q.up()))break;
            look=q;
        }
        if(rapid && !careful && Math.abs(dy)<1.3) {
            for(int j=Math.min(index+8,path.size()-1);j>index+1;j--){
                BlockPos q=path.get(j);
                double ahead=Math.hypot(q.getX()+.5-m.player.getX(),q.getZ()+.5-m.player.getZ());
                if(ahead>7.5 || Math.abs(q.getY()-m.player.getY())>2)continue;
                boolean uninterrupted=true;
                for(int k=index;k<=j;k++) if(open(m,path.get(k).down())){uninterrupted=false;break;}
                if(uninterrupted && corridor(m,q,false)){look=q;break;}
            }
        }
        double x=look.getX()+.5,z=look.getZ()+.5;
        double far=Math.hypot(x-m.player.getX(),z-m.player.getZ());
        boolean jumpLine=rapid && !careful && far>3.6 && Math.abs(dy)<.65 && corridor(m,look,true);
        if(dy<-.4){
            // Brake on a short downhill step to land inside its walkable footprint.
            x-=Math.max(-.34,Math.min(.34,m.player.getVelocity().x*1.6));
            z-=Math.max(-.34,Math.min(.34,m.player.getVelocity().z*1.6));
        }
        Race.aim(m,new Vec3d(x,m.player.getEyeY()-.16,z));
        move(m,x,z,careful && dy>-.4 && Math.hypot(p.getX()+.5-m.player.getX(),p.getZ()+.5-m.player.getZ())<.85,!careful && dy>-.4);
        m.options.keyJump.setPressed(!careful && (jumpLine || dy>.44 || m.player.horizontalCollision && dy>-.4));
        return false;
    }
    private BastionApproach(){}
}
