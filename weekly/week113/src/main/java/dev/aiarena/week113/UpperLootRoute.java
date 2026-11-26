package dev.aiarena.week113;

import dev.aiarena.week29.VisibleSlots;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Optional two-chest route. Both lines are foot cells walked in the local course. */
final class UpperLootRoute {
    private static final List<BlockPos> inbound=Middle.cells("upper-loot-0.csv");
    private static final List<BlockPos> outbound=Middle.cells("upper-loot-1.csv");
    private static final BlockPos chest=new BlockPos(-122,40,109);
    private static final BlockPos bridgePerch=new BlockPos(-110,39,111);
    private static final BlockPos farSupport=new BlockPos(-112,38,111);
    private static final BlockPos nearSupport=new BlockPos(-111,38,111);
    private static int aimUntil,guardUntil;
    static boolean enabled;
    static String status="";
    private static int phase,index,stable;
    private UpperLootRoute(){}

    static void reset(MinecraftClient mc){
        enabled=mc.getServer()!=null && java.nio.file.Files.isRegularFile(
                mc.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("astra-two-upper-chests"));
        phase=index=stable=aimUntil=guardUntil=0;
    }
    private static boolean solidFloor(MinecraftClient mc,BlockPos pos) {
        net.minecraft.block.BlockState seen=mc.world.getBlockState(pos);
        if(!seen.isFullCube(mc.world,pos)) return false;
        // Wait for the local prediction and the integrated world to agree before crossing.
        net.minecraft.server.world.ServerWorld world=mc.getServer().getWorld(mc.world.getRegistryKey());
        return world!=null && world.getBlockState(pos).getBlock()==seen.getBlock()
                && world.getBlockState(pos).isFullCube(world,pos);
    }

    private static boolean reverseBridge(MinecraftClient mc) {
        boolean far=solidFloor(mc,farSupport), near=solidFloor(mc,nearSupport);
        if(far && near) return false;
        // Stop on the natural basalt. Both placements are possible from its center,
        // starting with the far brick's east face, then the new block's east face.
        double x=bridgePerch.getX()+.5,z=bridgePerch.getZ()+.5;
        double distance=Math.hypot(mc.player.getX()-x,mc.player.getZ()-z);
        if(!mc.player.isOnGround() || !mc.player.getBlockPos().equals(bridgePerch)
                || distance>.33 || !solidFloor(mc,bridgePerch.down())) {
            if(!BastionEgress.guardRamp(mc,true)) Race.aim(mc,new Vec3d(x,mc.player.getEyeY()-.12,z));
            BastionApproach.move(mc,x,z,distance<.95,false);
            status="approaching the basalt center before the two missing floor blocks";
            return true;
        }
        mc.options.keySneak.setPressed(true);
        boolean threat=BastionEgress.guardRamp(mc,false)
                && mc.player.getOffHandStack().getItem()==Items.SHIELD;
        int now=mc.player.age;
        if(threat) {
            if(now>=aimUntil) {
                guardUntil=now+(mc.player.getHealth()<11?14:8);
                aimUntil=guardUntil+8;
            }
            if(now<guardUntil || mc.player.hurtTime>5) {
                BastionEgress.guardRamp(mc,true);
                status="shielding on the center of the upper basalt";
                return true;
            }
        } else aimUntil=guardUntil=0;
        if(!Middle.selectBuildingBlock(mc,false)) {
            status="selecting a solid block on the upper basalt";
            return true;
        }
        BlockPos target=far?nearSupport:farSupport;
        if(!BastionApproach.open(mc,target)) {
            status="waiting for the upper floor block to settle";
            return true;
        }
        BlockPos anchor=target.west();
        BlockHitResult hit=Middle.aim(mc,anchor,Direction.EAST);
        status=far?"filling the near floor block from the basalt center"
                :"filling the far floor block from the basalt center";
        if(hit!=null) {
            BastionEgress.lowerShield(mc);
            if(Middle.use(mc,hit) && threat) aimUntil=now;
        }
        return true;
    }

    private static boolean walk(MinecraftClient mc,List<BlockPos> cells){
        if(index>=cells.size())return true;
        BlockPos p=cells.get(index);
        if (mc.player.isOnGround() && Math.abs(mc.player.getY()-p.getY())<.35
                && !BastionApproach.open(mc,p.down())) {
            int end=index;
            while (end+1<cells.size() && end-index<6) {
                BlockPos cell=cells.get(end+1);
                if (cell.getY()!=p.getY() || BastionApproach.open(mc,cell.down())
                        || !BastionApproach.open(mc,cell) || !BastionApproach.open(mc,cell.up())) break;
                end++;
            }
            for (int j=end; j>index; j--) {
                if (BastionApproach.corridor(mc,cells.get(j),false)) {
                    index=j;
                    p=cells.get(j);
                    break;
                }
            }
        }
        if(cells==inbound && index>=20 && index<=22 && reverseBridge(mc)) return false;
        BlockPos prev=cells.get(Math.max(0,index-1));
        double x=p.getX()+.5,z=p.getZ()+.5;
        if(BastionApproach.open(mc,p.down())) {
            status="building upper bastion crossing "+index+"/"+cells.size();
            BastionEgress.guardBridge(mc,prev,p);return false;
        }
        double d=Math.hypot(x-mc.player.getX(),z-mc.player.getZ());
        double height=p.getY()-mc.player.getY();
        // For stairs allow their actual top surface half a block above the voxel base.
        if(d<.39 && height<.65 && height>-.7 && mc.player.isOnGround()) {
            index++;
            return index>=cells.size();
        }
        status="walking on upper bastion terrace "+index+"/"+cells.size();
        boolean gap=index+1<cells.size() && BastionApproach.open(mc,cells.get(index+1).down());
        if (!BastionEgress.guardRamp(mc,true)) Race.aim(mc,new Vec3d(x,mc.player.getEyeY()-.12,z));
        BastionApproach.move(mc,x,z,gap && height>-.45 && d<.70,!gap && d>1.15);
        boolean longJump = !gap && d>4.1 && Math.abs(height)<.25
                && BastionApproach.corridor(mc,p,true);
        mc.options.keyJump.setPressed(mc.player.isOnGround() && (longJump
                || height>.48 && (d<1.42 || mc.player.horizontalCollision)));
        return false;
    }

    static boolean tick(MinecraftClient mc){
        Race.stop(mc);mc.options.keyAttack.setPressed(false);
        if(phase==0 && walk(mc,inbound)){BastionEgress.lowerShield(mc);phase=1;index=0;}
        if(phase==1) {
            status="looting the upper bastion chest";
            mc.options.keySneak.setPressed(false);
            if(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
                if(VisibleSlots.loot(mc,stack-> stack.getItem()==Items.OBSIDIAN || stack.getItem()==Items.STRING
                        || stack.getItem()==Items.GOLDEN_CHESTPLATE || stack.getItem()==Items.GOLDEN_BOOTS,false)) {
                    mc.player.closeHandledScreen();
                    if(mc.player.inventory.count(Items.OBSIDIAN)>=5 && mc.player.inventory.count(Items.STRING)>=3)phase=2;
                }
            } else if(stable++>1 && Middle.select(mc,Items.DIAMOND_SWORD)) {
                // The crouch-release must reach the server before interacting. With a sword
                // in hand a failed chest click also cannot put a block in front of the lid.
                Middle.use(mc,Middle.aim(mc,chest,null));
            }
        } else if(phase==2) {
            status="equipping the upper chest's gold armor";
            if(VisibleSlots.equip(mc))phase=3;
        } else if(phase==3 && walk(mc,outbound)) {
            BastionEgress.lowerShield(mc);phase=4;
        }
        return phase==4;
    }
}
